package com.roadguard.service;

import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.domain.enums.Severity;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.repository.UserRepository;
import com.roadguard.security.AuthUser;
import com.roadguard.web.dto.CreateSosRequest;
import com.roadguard.web.dto.ServiceRequestResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class TriageDispatchTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired RequestService requests;
    @Autowired AssignmentService assignment;
    @Autowired DispatchService dispatch;
    @Autowired ServiceRequestRepository requestRows;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

    private AuthUser newDriver() {
        return tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            return new AuthUser(users.save(
                    new User("tri_d_" + n, "tri_d_" + n + "@test.com", "x", Role.DRIVER)));
        });
    }

    private ServiceRequest reload(Long id) {
        return tx.execute(s -> requestRows.findById(id).orElseThrow());
    }

    @Test
    @DisplayName("an SOS with a photo waits to be diagnosed instead of going straight out")
    void photoRequestWaitsForTriage() {
        ServiceRequestResponse created = requests.createSos(newDriver(),
                new CreateSosRequest(IssueType.FLAT_TIRE, 23.8103, 90.4125, "photo coming", true));

        assertEquals(RequestStatus.DIAGNOSING, created.status(),
                "it must not be searching yet, or the dispatcher will offer it before triage");
    }

    @Test
    @DisplayName("an SOS without a photo behaves exactly as before")
    void plainRequestGoesStraightToSearching() {
        ServiceRequestResponse created = requests.createSos(newDriver(),
                new CreateSosRequest(IssueType.FLAT_TIRE, 23.8103, 90.4125, "no photo", false));

        assertEquals(RequestStatus.SEARCHING, created.status());
    }

    @Test
    @DisplayName("a missing hasPhoto flag is treated as no photo")
    void nullFlagMeansNoPhoto() {
        ServiceRequestResponse created = requests.createSos(newDriver(),
                new CreateSosRequest(IssueType.FLAT_TIRE, 23.8103, 90.4125, null, null));

        assertEquals(RequestStatus.SEARCHING, created.status());
    }

    @Test
    @DisplayName("a request whose photo never arrives is not stranded")
    void abandonedDiagnosingIsSweptOn() {
        ServiceRequestResponse created = requests.createSos(newDriver(),
                new CreateSosRequest(IssueType.DEAD_BATTERY, 23.8103, 90.4125, "never sent", true));

        boolean movedOn = assignment.giveUpOnDiagnosing(created.id(), Instant.now().plusSeconds(60));

        assertTrue(movedOn);
        ServiceRequest after = reload(created.id());
        assertEquals(RequestStatus.SEARCHING, after.getStatus());
        assertEquals(com.roadguard.domain.enums.Specialization.BATTERY,
                after.getRequiredSpecialization(), "falls back to the issue type");
    }

    @Test
    @DisplayName("a request still inside its diagnosing window is left alone")
    void freshDiagnosingIsLeftAlone() {
        ServiceRequestResponse created = requests.createSos(newDriver(),
                new CreateSosRequest(IssueType.FLAT_TIRE, 23.8103, 90.4125, "just sent", true));

        boolean movedOn = assignment.giveUpOnDiagnosing(created.id(), Instant.now().minusSeconds(30));

        assertFalse(movedOn);
        assertEquals(RequestStatus.DIAGNOSING, reload(created.id()).getStatus());
    }

    @Test
    @DisplayName("a request already searching is never swept as if it were diagnosing")
    void searchingRequestIsNotSwept() {
        ServiceRequestResponse created = requests.createSos(newDriver(),
                new CreateSosRequest(IssueType.FLAT_TIRE, 23.8103, 90.4125, "plain", false));

        assertFalse(assignment.giveUpOnDiagnosing(created.id(), Instant.now().plusSeconds(60)));
    }

    @Test
    @DisplayName("an urgent job is offered to more mechanics than a routine one")
    void urgentJobsReachMorePeople() {
        assertEquals(5, dispatch.offersFor(Severity.CRITICAL));
        assertEquals(5, dispatch.offersFor(Severity.HIGH));
        assertEquals(3, dispatch.offersFor(Severity.MEDIUM));
        assertEquals(3, dispatch.offersFor(Severity.LOW));
        assertEquals(3, dispatch.offersFor(null));
    }
}
