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

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class EscalatedRevivalTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired AssignmentService assignment;
    @Autowired ServiceRequestRepository requests;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

    private Long requestInState(RequestStatus status, double radiusKm, int attempts) {
        return tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User driver = users.save(new User("rev_d_" + n, "rev_d_" + n + "@test.com", "x", Role.DRIVER));

            ServiceRequest r = new ServiceRequest(driver, IssueType.FLAT_TIRE, 23.8103, 90.4125, "revive");
            r.setSearchRadiusKm(radiusKm);
            r.setSearchAttempts(attempts);
            r.setStatus(status);
            return requests.save(r).getId();
        });
    }

    private ServiceRequest reload(Long id) {
        return tx.execute(s -> requests.findById(id).orElseThrow());
    }

    @Test
    @DisplayName("a request that was given up on can be picked up again")
    void escalatedGoesBackToSearching() {
        Long id = requestInState(RequestStatus.ESCALATED, 25, 4);

        assertTrue(assignment.reviveEscalated(id));
        assertEquals(RequestStatus.SEARCHING, reload(id).getStatus());
    }

    @Test
    @DisplayName("reviving resets the attempts so the ladder can run again")
    void attemptsAreResetOnRevival() {
        Long id = requestInState(RequestStatus.ESCALATED, 25, 4);

        assignment.reviveEscalated(id);

        assertEquals(0, reload(id).getSearchAttempts());
    }

    @Test
    @DisplayName("the widened radius is kept, since the search was already struggling")
    void radiusIsNotThrownAway() {
        Long id = requestInState(RequestStatus.ESCALATED, 25, 4);

        assignment.reviveEscalated(id);

        assertEquals(25, reload(id).getSearchRadiusKm());
    }

    @Test
    @DisplayName("reviving restarts the clock so it gets a full round before widening")
    void clockRestarts() {
        Long id = requestInState(RequestStatus.ESCALATED, 25, 4);

        assignment.reviveEscalated(id);

        assertTrue(reload(id).getSearchingSince() != null);
    }

    @Test
    @DisplayName("a request nobody gave up on is left alone")
    void searchingRequestIsUntouched() {
        Long id = requestInState(RequestStatus.SEARCHING, 5, 1);

        assertFalse(assignment.reviveEscalated(id));
        assertEquals(RequestStatus.SEARCHING, reload(id).getStatus());
        assertEquals(1, reload(id).getSearchAttempts());
    }

    @Test
    @DisplayName("a cancelled request is never brought back")
    void cancelledStaysCancelled() {
        Long id = requestInState(RequestStatus.CANCELLED, 5, 1);

        assertFalse(assignment.reviveEscalated(id));
        assertEquals(RequestStatus.CANCELLED, reload(id).getStatus());
    }

    @Test
    @DisplayName("a completed job is never brought back")
    void completedStaysCompleted() {
        Long id = requestInState(RequestStatus.COMPLETED, 5, 1);

        assertFalse(assignment.reviveEscalated(id));
        assertEquals(RequestStatus.COMPLETED, reload(id).getStatus());
    }

    @Test
    @DisplayName("reviving twice does nothing the second time")
    void revivingIsIdempotent() {
        Long id = requestInState(RequestStatus.ESCALATED, 25, 4);

        assertTrue(assignment.reviveEscalated(id));
        assertFalse(assignment.reviveEscalated(id));
        assertEquals(RequestStatus.SEARCHING, reload(id).getStatus());
    }
}
