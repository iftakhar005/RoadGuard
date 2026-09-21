package com.roadguard.service;

import com.roadguard.domain.RequestOffer;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.OfferOutcome;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.repository.RequestOfferRepository;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class OfferTimeoutTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    private static final double MAX_RADIUS = 25;
    private static final int MAX_ATTEMPTS = 3;

    @Autowired AssignmentService assignment;
    @Autowired OfferTimeoutService timeouts;
    @Autowired ServiceRequestRepository requests;
    @Autowired RequestOfferRepository offers;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

    private record Offered(Long requestId, String token, Long mechanicUserId) {
    }

    private Offered offeredRequest(double radiusKm, int attemptsSoFar, Instant offeredAt) {
        return tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User driver = users.save(new User("to_d_" + n, "to_d_" + n + "@test.com", "x", Role.DRIVER));
            User mech = users.save(new User("to_m_" + n, "to_m_" + n + "@test.com", "x", Role.MECHANIC));

            ServiceRequest r = new ServiceRequest(driver, IssueType.FLAT_TIRE, 23.8103, 90.4125, "timeout");
            r.setSearchRadiusKm(radiusKm);
            r.setSearchAttempts(attemptsSoFar);
            String token = r.startNewOfferRound(Set.of(mech.getId()));
            r.setStatus(RequestStatus.OFFERED);
            r.setOfferedAt(offeredAt);
            requests.save(r);

            offers.save(new RequestOffer(r, mech, token));
            return new Offered(r.getId(), token, mech.getId());
        });
    }

    @Test
    @DisplayName("nobody accepts, so the search radius doubles and it goes back out")
    void radiusDoublesOnTimeout() {
        Offered o = offeredRequest(5, 0, Instant.now().minusSeconds(600));

        AssignmentService.ExpiryOutcome outcome =
                assignment.expireRound(o.requestId(), o.token(), MAX_RADIUS, MAX_ATTEMPTS);

        assertEquals(AssignmentService.ExpiryOutcome.WIDENED, outcome);

        ServiceRequest after = requests.findById(o.requestId()).orElseThrow();
        assertEquals(10.0, after.getSearchRadiusKm(), 0.0001, "5km should widen to 10km");
        assertEquals(RequestStatus.SEARCHING, after.getStatus(), "should go back out for dispatch");
        assertEquals(1, after.getSearchAttempts());
        assertNull(after.getCurrentOfferToken(), "the stale round should be cleared");
    }

    @Test
    @DisplayName("the radius never grows past the configured maximum")
    void radiusIsCapped() {
        Offered o = offeredRequest(20, 0, Instant.now().minusSeconds(600));

        assignment.expireRound(o.requestId(), o.token(), MAX_RADIUS, MAX_ATTEMPTS);

        ServiceRequest after = requests.findById(o.requestId()).orElseThrow();
        assertEquals(MAX_RADIUS, after.getSearchRadiusKm(), 0.0001,
                "20km doubled is 40km but the cap is 25km");
    }

    @Test
    @DisplayName("after enough failed attempts it is escalated to an admin")
    void escalatesAfterMaxAttempts() {
        Offered o = offeredRequest(25, MAX_ATTEMPTS, Instant.now().minusSeconds(600));

        AssignmentService.ExpiryOutcome outcome =
                assignment.expireRound(o.requestId(), o.token(), MAX_RADIUS, MAX_ATTEMPTS);

        assertEquals(AssignmentService.ExpiryOutcome.ESCALATED, outcome);
        assertEquals(RequestStatus.ESCALATED,
                requests.findById(o.requestId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("a round that has not timed out yet is left alone")
    void freshRoundSurvives() {
        Offered o = offeredRequest(5, 0, Instant.now());

        timeouts.sweepOnce();

        ServiceRequest after = requests.findById(o.requestId()).orElseThrow();
        assertEquals(RequestStatus.OFFERED, after.getStatus(), "a fresh offer should stay open");
        assertEquals(5.0, after.getSearchRadiusKm(), 0.0001);
    }

    @Test
    @DisplayName("expiring marks the unanswered offers as timed out")
    void offersAreMarkedTimedOut() {
        Offered o = offeredRequest(5, 0, Instant.now().minusSeconds(600));

        assignment.expireRound(o.requestId(), o.token(), MAX_RADIUS, MAX_ATTEMPTS);

        long timedOut = offers.findByRequestIdAndOfferToken(o.requestId(), o.token()).stream()
                .filter(x -> x.getOutcome() == OfferOutcome.TIMEOUT)
                .count();
        assertEquals(1, timedOut, "the unanswered offer should be recorded as a timeout");
    }

    @Test
    @DisplayName("a stale token cannot expire the current round")
    void staleTokenCannotExpire() {
        Offered o = offeredRequest(5, 0, Instant.now().minusSeconds(600));

        AssignmentService.ExpiryOutcome outcome =
                assignment.expireRound(o.requestId(), "some-old-token", MAX_RADIUS, MAX_ATTEMPTS);

        assertEquals(AssignmentService.ExpiryOutcome.NOT_EXPIRED, outcome);
        assertEquals(RequestStatus.OFFERED,
                requests.findById(o.requestId()).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("the sweep widens a timed out request")
    void sweepPicksUpTimedOutRequests() {
        Offered o = offeredRequest(5, 0, Instant.now().minusSeconds(600));

        OfferTimeoutService.SweepResult result = timeouts.sweepOnce();

        assertTrue(result.widened() >= 1, "the sweep should have widened at least this request");

        ServiceRequest after = requests.findById(o.requestId()).orElseThrow();
        assertEquals(10, after.getSearchRadiusKm(), "the circle should have doubled");
        assertNull(after.getAssignedMechanic(), "nobody accepted, so it stays unassigned");

        assertTrue(after.getStatus() == RequestStatus.SEARCHING
                        || after.getStatus() == RequestStatus.OFFERED,
                "it is looking again, either queued or already back out on offer, but was "
                        + after.getStatus());
    }

    @Test
    @DisplayName("repeated timeouts widen step by step then escalate")
    void widensThenEscalates() {
        Offered o = offeredRequest(5, 0, Instant.now().minusSeconds(600));
        Long id = o.requestId();

        double[] expectedRadius = {10, 20, 25};
        for (double expected : expectedRadius) {
            String token = tx.execute(s -> {
                ServiceRequest r = requests.findById(id).orElseThrow();
                String t = r.startNewOfferRound(Set.of(o.mechanicUserId()));
                r.setStatus(RequestStatus.OFFERED);
                r.setOfferedAt(Instant.now().minusSeconds(600));
                requests.save(r);
                return t;
            });
            if (expected == expectedRadius[0]) {
                assignment.expireRound(id, token, MAX_RADIUS, MAX_ATTEMPTS);
            } else {
                assignment.expireRound(id, token, MAX_RADIUS, MAX_ATTEMPTS);
            }
            assertEquals(expected, requests.findById(id).orElseThrow().getSearchRadiusKm(), 0.0001);
        }

        String lastToken = tx.execute(s -> {
            ServiceRequest r = requests.findById(id).orElseThrow();
            String t = r.startNewOfferRound(Set.of(o.mechanicUserId()));
            r.setStatus(RequestStatus.OFFERED);
            r.setOfferedAt(Instant.now().minusSeconds(600));
            requests.save(r);
            return t;
        });
        assertEquals(AssignmentService.ExpiryOutcome.ESCALATED,
                assignment.expireRound(id, lastToken, MAX_RADIUS, MAX_ATTEMPTS));
        assertEquals(RequestStatus.ESCALATED,
                requests.findById(id).orElseThrow().getStatus());
    }
}
