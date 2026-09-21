package com.roadguard.service;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.domain.enums.Specialization;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
class JobLifecycleTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired AssignmentService assignment;
    @Autowired ServiceRequestRepository requests;
    @Autowired MechanicProfileRepository mechanics;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

    private record Job(Long requestId, Long mechanicUserId, Long driverUserId, Long profileId) {
    }

    private Job acceptedJob() {
        return tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User driver = users.save(new User("life_d_" + n, "life_d_" + n + "@test.com", "x", Role.DRIVER));
            User mech = users.save(new User("life_m_" + n, "life_m_" + n + "@test.com", "x", Role.MECHANIC));

            MechanicProfile p = new MechanicProfile(mech);
            p.setSpecializations(Set.of(Specialization.TIRE));
            p.setStatus(AvailabilityStatus.BUSY);
            p.setCurrentLat(23.8103);
            p.setCurrentLng(90.4125);
            p.setLastHeartbeat(Instant.now());
            mechanics.save(p);

            ServiceRequest r = new ServiceRequest(driver, IssueType.FLAT_TIRE, 23.8103, 90.4125, "lifecycle");
            r.setSearchRadiusKm(5);
            r.setAssignedMechanic(mech);
            r.setStatus(RequestStatus.ACCEPTED);
            r.setAcceptedAt(Instant.now());
            requests.save(r);

            return new Job(r.getId(), mech.getId(), driver.getId(), p.getId());
        });
    }

    @Test
    @DisplayName("a mechanic can walk a job from accepted through to completed")
    void fullHappyPath() {
        Job job = acceptedJob();

        for (RequestStatus step : new RequestStatus[]{
                RequestStatus.EN_ROUTE, RequestStatus.ARRIVED,
                RequestStatus.IN_PROGRESS, RequestStatus.COMPLETED}) {
            assertEquals(AssignmentService.StatusChange.OK,
                    assignment.advanceStatus(job.requestId(), job.mechanicUserId(), step),
                    "should be able to move to " + step);
        }

        ServiceRequest after = requests.findById(job.requestId()).orElseThrow();
        assertEquals(RequestStatus.COMPLETED, after.getStatus());
        assertNotNull(after.getCompletedAt(), "completion time should be recorded");
    }

    @Test
    @DisplayName("finishing a job puts the mechanic back on the market")
    void completingFreesTheMechanic() {
        Job job = acceptedJob();

        assignment.advanceStatus(job.requestId(), job.mechanicUserId(), RequestStatus.EN_ROUTE);
        assignment.advanceStatus(job.requestId(), job.mechanicUserId(), RequestStatus.ARRIVED);
        assignment.advanceStatus(job.requestId(), job.mechanicUserId(), RequestStatus.IN_PROGRESS);
        assignment.advanceStatus(job.requestId(), job.mechanicUserId(), RequestStatus.COMPLETED);

        assertEquals(AvailabilityStatus.ONLINE,
                mechanics.findById(job.profileId()).orElseThrow().getStatus(),
                "mechanic should be available again after finishing");
    }

    @Test
    @DisplayName("steps cannot be skipped")
    void cannotSkipSteps() {
        Job job = acceptedJob();

        assertEquals(AssignmentService.StatusChange.NOT_ALLOWED,
                assignment.advanceStatus(job.requestId(), job.mechanicUserId(), RequestStatus.COMPLETED),
                "should not jump straight from accepted to completed");
    }

    @Test
    @DisplayName("another mechanic cannot move someone else's job")
    void onlyTheAssignedMechanicCanUpdate() {
        Job job = acceptedJob();

        Long outsider = tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            return users.save(new User("life_o_" + n, "life_o_" + n + "@test.com", "x", Role.MECHANIC)).getId();
        });

        assertEquals(AssignmentService.StatusChange.NOT_YOURS,
                assignment.advanceStatus(job.requestId(), outsider, RequestStatus.EN_ROUTE));
    }

    @Test
    @DisplayName("a driver can cancel and the mechanic is freed")
    void cancelFreesTheMechanic() {
        Job job = acceptedJob();

        assertEquals(AssignmentService.StatusChange.OK,
                assignment.cancel(job.requestId(), job.driverUserId()));

        ServiceRequest after = requests.findById(job.requestId()).orElseThrow();
        assertEquals(RequestStatus.CANCELLED, after.getStatus());
        assertEquals(AvailabilityStatus.ONLINE,
                mechanics.findById(job.profileId()).orElseThrow().getStatus(),
                "cancelling should release the mechanic");
    }

    @Test
    @DisplayName("someone else's driver cannot cancel the request")
    void onlyOwnerCanCancel() {
        Job job = acceptedJob();

        Long otherDriver = tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            return users.save(new User("life_od_" + n, "life_od_" + n + "@test.com", "x", Role.DRIVER)).getId();
        });

        assertEquals(AssignmentService.StatusChange.NOT_YOURS,
                assignment.cancel(job.requestId(), otherDriver));
    }

    @Test
    @DisplayName("a finished job cannot be cancelled or moved again")
    void completedJobIsFinal() {
        Job job = acceptedJob();
        assignment.advanceStatus(job.requestId(), job.mechanicUserId(), RequestStatus.EN_ROUTE);
        assignment.advanceStatus(job.requestId(), job.mechanicUserId(), RequestStatus.ARRIVED);
        assignment.advanceStatus(job.requestId(), job.mechanicUserId(), RequestStatus.IN_PROGRESS);
        assignment.advanceStatus(job.requestId(), job.mechanicUserId(), RequestStatus.COMPLETED);

        assertEquals(AssignmentService.StatusChange.NOT_ALLOWED,
                assignment.cancel(job.requestId(), job.driverUserId()));
        assertEquals(AssignmentService.StatusChange.NOT_ALLOWED,
                assignment.advanceStatus(job.requestId(), job.mechanicUserId(), RequestStatus.EN_ROUTE));
    }
}
