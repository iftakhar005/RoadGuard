package com.roadguard.service;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.RequestOffer;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.AcceptOutcome;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.domain.enums.Specialization;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.RequestOfferRepository;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class HeartbeatReaperTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired HeartbeatReaper reaper;
    @Autowired AssignmentService assignment;
    @Autowired MechanicProfileRepository mechanics;
    @Autowired ServiceRequestRepository requests;
    @Autowired RequestOfferRepository offers;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

    private record Seeded(Long requestId, Long mechanicUserId, Long profileId, String token) {
    }

    private Long newMechanic(AvailabilityStatus status, Instant heartbeat) {
        return tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User u = users.save(new User("reap_m_" + n, "reap_m_" + n + "@test.com", "x", Role.MECHANIC));
            MechanicProfile p = new MechanicProfile(u);
            p.setSpecializations(Set.of(Specialization.TIRE));
            p.setStatus(status);
            p.setCurrentLat(23.8103);
            p.setCurrentLng(90.4125);
            p.setLastHeartbeat(heartbeat);
            mechanics.save(p);
            return p.getId();
        });
    }

    private Seeded seedAcceptedJob(Instant mechanicHeartbeat) {
        return tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User driver = users.save(new User("reap_d_" + n, "reap_d_" + n + "@test.com", "x", Role.DRIVER));
            User mech = users.save(new User("reap_mm_" + n, "reap_mm_" + n + "@test.com", "x", Role.MECHANIC));

            MechanicProfile p = new MechanicProfile(mech);
            p.setSpecializations(Set.of(Specialization.TIRE));
            p.setStatus(AvailabilityStatus.BUSY);
            p.setCurrentLat(23.8103);
            p.setCurrentLng(90.4125);
            p.setLastHeartbeat(mechanicHeartbeat);
            mechanics.save(p);

            ServiceRequest r = new ServiceRequest(driver, IssueType.FLAT_TIRE, 23.8103, 90.4125, "reaper fixture");
            r.setSearchRadiusKm(5);
            r.setAssignedMechanic(mech);
            r.setStatus(RequestStatus.ACCEPTED);
            r.setAcceptedAt(Instant.now());
            requests.save(r);

            return new Seeded(r.getId(), mech.getId(), p.getId(), null);
        });
    }

    @Test
    @DisplayName("a mechanic who stops sending heartbeats is marked offline")
    void staleMechanicGoesOffline() {
        Long profileId = newMechanic(AvailabilityStatus.ONLINE, Instant.now().minusSeconds(600));

        reaper.reapOnce();

        assertEquals(AvailabilityStatus.OFFLINE,
                mechanics.findById(profileId).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("a mechanic still sending heartbeats is left alone")
    void freshMechanicSurvives() {
        Long profileId = newMechanic(AvailabilityStatus.ONLINE, Instant.now());

        reaper.reapOnce();

        assertEquals(AvailabilityStatus.ONLINE,
                mechanics.findById(profileId).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("dropping off mid job releases the request for re-dispatch")
    void activeJobIsReleased() {
        Seeded seeded = seedAcceptedJob(Instant.now().minusSeconds(600));

        reaper.reapOnce();

        ServiceRequest after = requests.findById(seeded.requestId()).orElseThrow();
        assertEquals(RequestStatus.REASSIGNING, after.getStatus(), "job should be going back out");
        assertNull(after.getAssignedMechanic(), "the dropped mechanic must be released");

        assertEquals(AvailabilityStatus.OFFLINE,
                mechanics.findById(seeded.profileId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("a job held by a mechanic who is still alive is not touched")
    void liveMechanicKeepsJob() {
        Seeded seeded = seedAcceptedJob(Instant.now());

        reaper.reapOnce();

        ServiceRequest after = requests.findById(seeded.requestId()).orElseThrow();
        assertEquals(RequestStatus.ACCEPTED, after.getStatus());
        assertNotNull(after.getAssignedMechanic(), "job should stay with its mechanic");
    }

    @Test
    @DisplayName("the released request can be accepted again by someone else")
    void releasedJobCanBeRetaken() {
        Seeded seeded = seedAcceptedJob(Instant.now().minusSeconds(600));
        reaper.reapOnce();

        Long rescuerId = tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User u = users.save(new User("rescuer_" + n, "rescuer_" + n + "@test.com", "x", Role.MECHANIC));
            MechanicProfile p = new MechanicProfile(u);
            p.setSpecializations(Set.of(Specialization.TIRE));
            p.setStatus(AvailabilityStatus.ONLINE);
            p.setCurrentLat(23.8103);
            p.setCurrentLng(90.4125);
            p.setLastHeartbeat(Instant.now());
            mechanics.save(p);
            return u.getId();
        });

        String token = tx.execute(s -> {
            ServiceRequest r = requests.findById(seeded.requestId()).orElseThrow();
            String t = r.startNewOfferRound(Set.of(rescuerId));
            requests.save(r);
            offers.save(new RequestOffer(r, users.findById(rescuerId).orElseThrow(), t));
            return t;
        });

        assertEquals(AcceptOutcome.ACCEPTED,
                assignment.accept(seeded.requestId(), rescuerId, token));

        ServiceRequest after = requests.findById(seeded.requestId()).orElseThrow();
        assertEquals(RequestStatus.ACCEPTED, after.getStatus());
        assertEquals(rescuerId, after.getAssignedMechanic().getId());
    }

    @RepeatedTest(15)
    @DisplayName("the reaper and an accept on the same request never both win")
    void reaperDoesNotRaceWithAccept() throws Exception {
        Long driverId = tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            return users.save(new User("rc_d_" + n, "rc_d_" + n + "@test.com", "x", Role.DRIVER)).getId();
        });

        Long mechId = tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User u = users.save(new User("rc_m_" + n, "rc_m_" + n + "@test.com", "x", Role.MECHANIC));
            MechanicProfile p = new MechanicProfile(u);
            p.setSpecializations(Set.of(Specialization.TIRE));
            p.setStatus(AvailabilityStatus.ONLINE);
            p.setCurrentLat(23.8103);
            p.setCurrentLng(90.4125);
            p.setLastHeartbeat(Instant.now().minusSeconds(600));
            mechanics.save(p);
            return u.getId();
        });

        Object[] seeded = tx.execute(s -> {
            ServiceRequest r = new ServiceRequest(
                    users.findById(driverId).orElseThrow(),
                    IssueType.FLAT_TIRE, 23.8103, 90.4125, "race with reaper");
            r.setSearchRadiusKm(5);
            String t = r.startNewOfferRound(Set.of(mechId));
            r.setStatus(RequestStatus.OFFERED);
            requests.save(r);
            offers.save(new RequestOffer(r, users.findById(mechId).orElseThrow(), t));
            return new Object[]{r.getId(), t};
        });
        Long requestId = (Long) seeded[0];
        String token = (String) seeded[1];

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<AcceptOutcome> accepting = pool.submit(() -> {
            go.await();
            return assignment.accept(requestId, mechId, token);
        });
        Future<?> reaping = pool.submit(() -> {
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return reaper.reapOnce();
        });

        go.countDown();
        AcceptOutcome outcome = accepting.get(30, TimeUnit.SECONDS);
        reaping.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        ServiceRequest after = requests.findById(requestId).orElseThrow();

        if (outcome == AcceptOutcome.ACCEPTED) {
            boolean consistent = (after.getStatus() == RequestStatus.ACCEPTED
                    && after.getAssignedMechanic() != null)
                    || (after.getStatus() == RequestStatus.REASSIGNING
                    && after.getAssignedMechanic() == null);
            assertTrue(consistent,
                    "after an accept the request must be either cleanly assigned or cleanly released, was "
                            + after.getStatus() + " assigned=" + after.getAssignedMechanic());
        } else {
            assertNull(after.getAssignedMechanic(),
                    "nobody won, so no mechanic should be attached");
        }

        boolean assignedButNotAccepted =
                after.getAssignedMechanic() != null && !after.getStatus().isAssignedToMechanic();
        assertTrue(!assignedButNotAccepted,
                "a mechanic is attached to a request that is not in an assigned state");
    }

    @Test
    @DisplayName("an already offline mechanic is not counted twice")
    void offlineMechanicNotReapedAgain() {
        newMechanic(AvailabilityStatus.OFFLINE, Instant.now().minusSeconds(600));

        HeartbeatReaper.ReapResult first = reaper.reapOnce();
        HeartbeatReaper.ReapResult second = reaper.reapOnce();

        assertEquals(0, second.droppedMechanics(),
                "an offline mechanic should not be dropped again");
        assertTrue(first.droppedMechanics() >= 0);
    }
}
