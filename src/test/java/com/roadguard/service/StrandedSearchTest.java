package com.roadguard.service;

import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@SpringBootTest
@ActiveProfiles("test")
class StrandedSearchTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    private static final double MAX_RADIUS = 25;
    private static final int MAX_ATTEMPTS = 3;

    @Autowired AssignmentService assignment;
    @Autowired ServiceRequestRepository requests;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

    private Long searchingRequest(double radiusKm, int attemptsSoFar, Instant searchingSince) {
        return tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User driver = users.save(new User("st_d_" + n, "st_d_" + n + "@test.com", "x", Role.DRIVER));

            ServiceRequest r = new ServiceRequest(driver, IssueType.FLAT_TIRE, 23.8103, 90.4125, "stranded");
            r.setSearchRadiusKm(radiusKm);
            r.setSearchAttempts(attemptsSoFar);
            r.setStatus(RequestStatus.SEARCHING);
            r.setSearchingSince(searchingSince);
            return requests.save(r).getId();
        });
    }

    private ServiceRequest reload(Long id) {
        return tx.execute(s -> requests.findById(id).orElseThrow());
    }

    private Instant longAgo() {
        return Instant.now().minusSeconds(600);
    }

    @Test
    @DisplayName("a request that found nobody widens its radius instead of retrying the same circle")
    void strandedSearchWidens() {
        Long id = searchingRequest(5, 0, longAgo());

        AssignmentService.ExpiryOutcome outcome =
                assignment.expireSearch(id, Instant.now(), MAX_RADIUS, MAX_ATTEMPTS);

        assertEquals(AssignmentService.ExpiryOutcome.WIDENED, outcome);
        assertEquals(10, reload(id).getSearchRadiusKm());
    }

    @Test
    @DisplayName("the radius doubles each round and stops at the ceiling")
    void wideningIsCapped() {
        Long id = searchingRequest(20, 0, longAgo());

        assignment.expireSearch(id, Instant.now(), MAX_RADIUS, MAX_ATTEMPTS);

        assertEquals(MAX_RADIUS, reload(id).getSearchRadiusKm());
    }

    @Test
    @DisplayName("a request still inside its round is left alone")
    void freshRequestIsNotWidened() {
        Long id = searchingRequest(5, 0, Instant.now());

        AssignmentService.ExpiryOutcome outcome = assignment.expireSearch(
                id, Instant.now().minusSeconds(20), MAX_RADIUS, MAX_ATTEMPTS);

        assertEquals(AssignmentService.ExpiryOutcome.NOT_EXPIRED, outcome);
        assertEquals(5, reload(id).getSearchRadiusKm());
        assertEquals(0, reload(id).getSearchAttempts());
    }

    @Test
    @DisplayName("once the radius is at the ceiling and the attempts run out the request escalates")
    void escalatesWhenNobodyIsEverReachable() {
        Long id = searchingRequest(MAX_RADIUS, MAX_ATTEMPTS, longAgo());

        AssignmentService.ExpiryOutcome outcome =
                assignment.expireSearch(id, Instant.now(), MAX_RADIUS, MAX_ATTEMPTS);

        assertEquals(AssignmentService.ExpiryOutcome.ESCALATED, outcome);
        assertEquals(RequestStatus.ESCALATED, reload(id).getStatus());
    }

    @Test
    @DisplayName("attempts alone do not escalate while there is still room to widen")
    void doesNotEscalateWhileTheCircleCanStillGrow() {
        Long id = searchingRequest(5, MAX_ATTEMPTS + 2, longAgo());

        AssignmentService.ExpiryOutcome outcome =
                assignment.expireSearch(id, Instant.now(), MAX_RADIUS, MAX_ATTEMPTS);

        assertEquals(AssignmentService.ExpiryOutcome.WIDENED, outcome);
        assertEquals(10, reload(id).getSearchRadiusKm());
    }

    @Test
    @DisplayName("each widening restarts the clock so the next one waits a full round")
    void wideningResetsTheClock() {
        Instant before = longAgo();
        Long id = searchingRequest(5, 0, before);

        assignment.expireSearch(id, Instant.now(), MAX_RADIUS, MAX_ATTEMPTS);

        Instant after = reload(id).getSearchingSince();
        assertNotEquals(before, after);

        AssignmentService.ExpiryOutcome second = assignment.expireSearch(
                id, Instant.now().minusSeconds(20), MAX_RADIUS, MAX_ATTEMPTS);

        assertEquals(AssignmentService.ExpiryOutcome.NOT_EXPIRED, second);
        assertEquals(10, reload(id).getSearchRadiusKm());
    }

    @Test
    @DisplayName("a released job that is searching again also widens")
    void reassigningAlsoWidens() {
        Long id = tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User driver = users.save(new User("st_r_" + n, "st_r_" + n + "@test.com", "x", Role.DRIVER));

            ServiceRequest r = new ServiceRequest(driver, IssueType.FLAT_TIRE, 23.8103, 90.4125, "released");
            r.setSearchRadiusKm(5);
            r.setStatus(RequestStatus.REASSIGNING);
            r.setSearchingSince(longAgo());
            return requests.save(r).getId();
        });

        AssignmentService.ExpiryOutcome outcome =
                assignment.expireSearch(id, Instant.now(), MAX_RADIUS, MAX_ATTEMPTS);

        assertEquals(AssignmentService.ExpiryOutcome.WIDENED, outcome);
        assertEquals(10, reload(id).getSearchRadiusKm());
    }

    @Test
    @DisplayName("a request that has already been taken is never widened")
    void acceptedRequestIsUntouched() {
        Long id = tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User driver = users.save(new User("st_a_" + n, "st_a_" + n + "@test.com", "x", Role.DRIVER));

            ServiceRequest r = new ServiceRequest(driver, IssueType.FLAT_TIRE, 23.8103, 90.4125, "taken");
            r.setSearchRadiusKm(5);
            r.setStatus(RequestStatus.ACCEPTED);
            r.setSearchingSince(longAgo());
            return requests.save(r).getId();
        });

        AssignmentService.ExpiryOutcome outcome =
                assignment.expireSearch(id, Instant.now(), MAX_RADIUS, MAX_ATTEMPTS);

        assertEquals(AssignmentService.ExpiryOutcome.NOT_EXPIRED, outcome);
        assertEquals(5, reload(id).getSearchRadiusKm());
        assertEquals(RequestStatus.ACCEPTED, reload(id).getStatus());
    }
}
