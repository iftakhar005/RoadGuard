package com.roadguard.service;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.Rating;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.domain.enums.Specialization;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.RatingRepository;
import com.roadguard.repository.ServiceRequestRepository;
import com.roadguard.repository.UserRepository;
import com.roadguard.security.AuthUser;
import com.roadguard.security.JwtService;
import com.roadguard.web.dto.RatingResponse;
import com.roadguard.web.dto.ServiceRequestResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RatingServiceTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired RatingService ratingService;
    @Autowired RequestService requestService;
    @Autowired MatchingService matchingService;
    @Autowired RatingRepository ratings;
    @Autowired ServiceRequestRepository requests;
    @Autowired MechanicProfileRepository mechanics;
    @Autowired UserRepository users;
    @Autowired JwtService jwtService;
    @Autowired TransactionTemplate tx;
    @Autowired MockMvc mockMvc;

    private record TestFixture(User driver, User mechanic, MechanicProfile profile, ServiceRequest request) {
    }

    private TestFixture createCompletedFixture() {
        return createFixture(RequestStatus.COMPLETED);
    }

    private TestFixture createFixture(RequestStatus status) {
        return tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User driver = users.save(new User("rate_d_" + n, "rate_d_" + n + "@t.com", "pass", Role.DRIVER));
            User mech = users.save(new User("rate_m_" + n, "rate_m_" + n + "@t.com", "pass", Role.MECHANIC));

            MechanicProfile p = new MechanicProfile(mech);
            p.setSpecializations(Set.of(Specialization.TIRE));
            p.setStatus(AvailabilityStatus.ONLINE);
            p.setCurrentLat(23.8103);
            p.setCurrentLng(90.4125);
            p.setLastHeartbeat(Instant.now());
            p.setAvgRating(0.0);
            p.setRatingCount(0);
            mechanics.save(p);

            ServiceRequest req = new ServiceRequest(driver, IssueType.FLAT_TIRE, 23.8103, 90.4125, "rating test");
            req.setAssignedMechanic(mech);
            req.setStatus(status);
            req.setAcceptedAt(Instant.now().minusSeconds(600));
            if (status == RequestStatus.COMPLETED) {
                req.setCompletedAt(Instant.now());
            }
            requests.save(req);

            return new TestFixture(driver, mech, p, req);
        });
    }

    @Test
    @DisplayName("a driver can rate a completed request once")
    void rateOnce() {
        TestFixture fix = createCompletedFixture();
        AuthUser caller = new AuthUser(fix.driver());

        RatingResponse res = ratingService.rate(caller, fix.request().getId(), 5, "Quick and helpful");

        assertNotNull(res.id());
        assertEquals(5, res.stars());
        assertEquals("Quick and helpful", res.comment());
        assertEquals(fix.request().getId(), res.requestId());
        assertEquals(fix.driver().getId(), res.driverId());
        assertEquals(fix.mechanic().getId(), res.mechanicId());

        MechanicProfile updatedProfile = tx.execute(s -> mechanics.findByUserId(fix.mechanic().getId()).orElseThrow());
        assertEquals(5.0, updatedProfile.getAvgRating());
        assertEquals(1, updatedProfile.getRatingCount());

        ServiceRequestResponse reqRes = requestService.getById(caller, fix.request().getId());
        assertTrue(reqRes.rated());
        assertEquals(5, reqRes.ratingStars());
    }

    @Test
    @DisplayName("rating a second time is rejected")
    void rateTwiceRejected() {
        TestFixture fix = createCompletedFixture();
        AuthUser caller = new AuthUser(fix.driver());

        ratingService.rate(caller, fix.request().getId(), 4, "Good");

        assertThrows(IllegalStateException.class, () ->
                ratingService.rate(caller, fix.request().getId(), 5, "Another rating"));
    }

    @Test
    @DisplayName("rating before request completion is rejected")
    void rateBeforeCompletionRejected() {
        TestFixture fix = createFixture(RequestStatus.IN_PROGRESS);
        AuthUser caller = new AuthUser(fix.driver());

        assertThrows(IllegalStateException.class, () ->
                ratingService.rate(caller, fix.request().getId(), 5, "Too early"));
    }

    @Test
    @DisplayName("rating someone else's request is forbidden (403)")
    void rateSomeoneElsesRequestForbidden() {
        TestFixture fix = createCompletedFixture();
        User stranger = tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            return users.save(new User("stranger_" + n, "stranger_" + n + "@t.com", "pass", Role.DRIVER));
        });

        AuthUser strangerAuth = new AuthUser(stranger);

        assertThrows(AccessDeniedException.class, () ->
                ratingService.rate(strangerAuth, fix.request().getId(), 5, "Not mine"));
    }

    @Test
    @DisplayName("stars out of 1-5 range are rejected")
    void starsOutOfRangeRejected() {
        TestFixture fix = createCompletedFixture();
        AuthUser caller = new AuthUser(fix.driver());

        assertThrows(IllegalArgumentException.class, () ->
                ratingService.rate(caller, fix.request().getId(), 0, "Too low"));

        assertThrows(IllegalArgumentException.class, () ->
                ratingService.rate(caller, fix.request().getId(), 6, "Too high"));
    }

    @Test
    @DisplayName("mechanic average rating recomputes correctly over multiple ratings")
    void averageMathsOverMultipleRatings() {
        int n = UNIQUE.incrementAndGet();
        User mech = tx.execute(s -> {
            User m = users.save(new User("avg_m_" + n, "avg_m_" + n + "@t.com", "pass", Role.MECHANIC));
            MechanicProfile p = new MechanicProfile(m);
            p.setSpecializations(Set.of(Specialization.TIRE));
            p.setStatus(AvailabilityStatus.ONLINE);
            p.setCurrentLat(23.8103);
            p.setCurrentLng(90.4125);
            mechanics.save(p);
            return m;
        });

        int[] starsGiven = {5, 4, 3};
        double[] expectedAvgs = {5.0, 4.5, 4.0};

        for (int i = 0; i < starsGiven.length; i++) {
            final int index = i;
            record ReqAndDriver(Long reqId, AuthUser driverAuth) {}
            ReqAndDriver rd = tx.execute(s -> {
                int dn = UNIQUE.incrementAndGet();
                User driver = users.save(new User("avg_d_" + dn, "avg_d_" + dn + "@t.com", "pass", Role.DRIVER));
                ServiceRequest req = new ServiceRequest(driver, IssueType.FLAT_TIRE, 23.8103, 90.4125, "avg test " + index);
                req.setAssignedMechanic(mech);
                req.setStatus(RequestStatus.COMPLETED);
                Long id = requests.save(req).getId();
                return new ReqAndDriver(id, new AuthUser(driver));
            });

            ratingService.rate(rd.driverAuth(), rd.reqId(), starsGiven[i], "Rating " + i);

            MechanicProfile profile = tx.execute(s -> mechanics.findByUserId(mech.getId()).orElseThrow());
            assertEquals(expectedAvgs[i], profile.getAvgRating(), 0.01);
            assertEquals(i + 1, profile.getRatingCount());
        }
    }

    @Test
    @DisplayName("matcher ranks higher-rated mechanic first when distance is equal")
    void matchingPrefersHigherRatedMechanic() {
        int n = UNIQUE.incrementAndGet();
        MechanicProfile lowRated = tx.execute(s -> {
            User m = users.save(new User("low_m_" + n, "low_m_" + n + "@t.com", "pass", Role.MECHANIC));
            MechanicProfile p = new MechanicProfile(m);
            p.setSpecializations(Set.of(Specialization.TIRE));
            p.setStatus(AvailabilityStatus.ONLINE);
            p.setCurrentLat(23.8103 + 0.01);
            p.setCurrentLng(90.4125);
            p.setAvgRating(2.0);
            p.setRatingCount(5);
            return mechanics.save(p);
        });

        MechanicProfile highRated = tx.execute(s -> {
            User m = users.save(new User("high_m_" + n, "high_m_" + n + "@t.com", "pass", Role.MECHANIC));
            MechanicProfile p = new MechanicProfile(m);
            p.setSpecializations(Set.of(Specialization.TIRE));
            p.setStatus(AvailabilityStatus.ONLINE);
            p.setCurrentLat(23.8103 + 0.01);
            p.setCurrentLng(90.4125);
            p.setAvgRating(5.0);
            p.setRatingCount(5);
            return mechanics.save(p);
        });

        List<MatchingService.Candidate> ranked = matchingService.rank(
                List.of(lowRated, highRated), 23.8103, 90.4125, 10.0, Specialization.TIRE);

        assertEquals(2, ranked.size());
        assertEquals(highRated.getUser().getUsername(), ranked.get(0).profile().getUser().getUsername());
    }

    @Test
    @DisplayName("POST /api/requests/{id}/rating endpoint accepts valid rating and returns JSON")
    void ratingEndpointHttp() throws Exception {
        TestFixture fix = createCompletedFixture();
        String token = jwtService.issue(fix.driver());

        mockMvc.perform(post("/api/requests/" + fix.request().getId() + "/rating")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stars\":5,\"comment\":\"Super fast response\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stars").value(5))
                .andExpect(jsonPath("$.comment").value("Super fast response"))
                .andExpect(jsonPath("$.requestId").value(fix.request().getId()));

        mockMvc.perform(post("/api/requests/" + fix.request().getId() + "/rating")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stars\":4}"))
                .andExpect(status().isConflict());
    }
}
