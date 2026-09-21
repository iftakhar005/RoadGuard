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
import com.roadguard.security.AuthUser;
import com.roadguard.web.dto.MechanicProfileResponse;
import com.roadguard.web.dto.SkillsRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class MechanicSkillsTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    private static final double LAT = 21.4272;
    private static final double LNG = 92.0058;

    @Autowired MechanicService mechanicService;
    @Autowired MatchingService matching;
    @Autowired MechanicProfileRepository mechanics;
    @Autowired ServiceRequestRepository requests;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;

    private record Mechanic(Long userId, Long profileId) {
    }

    private Mechanic mechanicWith(Set<Specialization> skills) {
        return tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User u = users.save(new User("skill_m_" + n, "skill_m_" + n + "@test.com", "x", Role.MECHANIC));

            MechanicProfile p = new MechanicProfile(u);
            p.setSpecializations(new java.util.HashSet<>(skills));
            p.setStatus(AvailabilityStatus.ONLINE);
            p.setCurrentLat(LAT);
            p.setCurrentLng(LNG);
            p.setLastHeartbeat(Instant.now());
            mechanics.save(p);

            return new Mechanic(u.getId(), p.getId());
        });
    }

    private AuthUser callerFor(Long userId) {
        return tx.execute(s -> new AuthUser(users.findById(userId).orElseThrow()));
    }

    private ServiceRequest requestNeeding(IssueType issue) {
        return tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User driver = users.save(new User("skill_d_" + n, "skill_d_" + n + "@test.com", "x", Role.DRIVER));
            ServiceRequest r = new ServiceRequest(driver, issue, LAT, LNG, "skills");
            r.setSearchRadiusKm(5);
            return requests.save(r);
        });
    }

    private boolean isCandidate(ServiceRequest request, Long profileId) {
        return matching.findCandidates(request).stream()
                .anyMatch(c -> c.profile().getId().equals(profileId));
    }

    @Test
    @DisplayName("a mechanic can add a second skill and keep the first")
    void addsWithoutLosingWhatWasThere() {
        Mechanic m = mechanicWith(Set.of(Specialization.ENGINE));

        MechanicProfileResponse after = mechanicService.updateSkills(
                callerFor(m.userId()),
                new SkillsRequest(Set.of(Specialization.ENGINE, Specialization.TIRE, Specialization.BRAKES)));

        assertEquals(3, after.specializations().size());
        assertTrue(after.specializations().containsAll(
                List.of(Specialization.ENGINE, Specialization.TIRE, Specialization.BRAKES)));
    }

    @Test
    @DisplayName("the new skills survive a reload from the database")
    void skillsArePersisted() {
        Mechanic m = mechanicWith(Set.of(Specialization.ENGINE));

        mechanicService.updateSkills(callerFor(m.userId()),
                new SkillsRequest(Set.of(Specialization.TIRE, Specialization.TOWING)));

        Set<Specialization> stored = tx.execute(s ->
                Set.copyOf(mechanics.findById(m.profileId()).orElseThrow().getSpecializations()));

        assertEquals(Set.of(Specialization.TIRE, Specialization.TOWING), stored);
    }

    @Test
    @DisplayName("a skill that was removed is really gone")
    void removedSkillDoesNotLinger() {
        Mechanic m = mechanicWith(Set.of(Specialization.TIRE, Specialization.BATTERY));

        mechanicService.updateSkills(callerFor(m.userId()),
                new SkillsRequest(Set.of(Specialization.BATTERY)));

        Set<Specialization> stored = tx.execute(s ->
                Set.copyOf(mechanics.findById(m.profileId()).orElseThrow().getSpecializations()));

        assertFalse(stored.contains(Specialization.TIRE));
        assertEquals(Set.of(Specialization.BATTERY), stored);
    }

    @Test
    @DisplayName("sending no skills falls back to general rather than leaving nobody reachable")
    void emptySelectionBecomesGeneral() {
        Mechanic m = mechanicWith(Set.of(Specialization.ENGINE));

        MechanicProfileResponse after = mechanicService.updateSkills(
                callerFor(m.userId()), new SkillsRequest(Set.of()));

        assertEquals(Set.of(Specialization.GENERAL), Set.copyOf(after.specializations()));
    }

    @Test
    @DisplayName("a null skill list is treated the same as an empty one")
    void nullSelectionBecomesGeneral() {
        Mechanic m = mechanicWith(Set.of(Specialization.ENGINE));

        MechanicProfileResponse after = mechanicService.updateSkills(
                callerFor(m.userId()), new SkillsRequest(null));

        assertEquals(Set.of(Specialization.GENERAL), Set.copyOf(after.specializations()));
    }

    @Test
    @DisplayName("an engine-only mechanic is invisible to a flat tyre until the skill is added")
    void addingTheSkillMakesTheMechanicReachable() {
        Mechanic m = mechanicWith(Set.of(Specialization.ENGINE));
        ServiceRequest flatTyre = requestNeeding(IssueType.FLAT_TIRE);

        assertFalse(isCandidate(flatTyre, m.profileId()),
                "an engine specialist should not be offered a flat tyre");

        mechanicService.updateSkills(callerFor(m.userId()),
                new SkillsRequest(Set.of(Specialization.ENGINE, Specialization.TIRE)));

        assertTrue(isCandidate(flatTyre, m.profileId()),
                "after adding tyres the same mechanic should be a candidate");
    }

    @Test
    @DisplayName("dropping a skill removes the mechanic from that kind of job")
    void removingTheSkillHidesTheMechanic() {
        Mechanic m = mechanicWith(Set.of(Specialization.TIRE));
        ServiceRequest flatTyre = requestNeeding(IssueType.FLAT_TIRE);

        assertTrue(isCandidate(flatTyre, m.profileId()));

        mechanicService.updateSkills(callerFor(m.userId()),
                new SkillsRequest(Set.of(Specialization.BRAKES)));

        assertFalse(isCandidate(flatTyre, m.profileId()));
    }

    @Test
    @DisplayName("changing skills does not disturb a job already accepted")
    void inFlightJobIsUntouched() {
        Mechanic m = mechanicWith(Set.of(Specialization.TIRE));

        Long requestId = tx.execute(s -> {
            int n = UNIQUE.incrementAndGet();
            User driver = users.save(new User("skill_jd_" + n, "skill_jd_" + n + "@test.com", "x", Role.DRIVER));
            User mech = users.findById(m.userId()).orElseThrow();

            ServiceRequest r = new ServiceRequest(driver, IssueType.FLAT_TIRE, LAT, LNG, "in flight");
            r.setSearchRadiusKm(5);
            r.setAssignedMechanic(mech);
            r.setStatus(RequestStatus.EN_ROUTE);
            r.setAcceptedAt(Instant.now());
            return requests.save(r).getId();
        });

        mechanicService.updateSkills(callerFor(m.userId()),
                new SkillsRequest(Set.of(Specialization.BRAKES)));

        tx.executeWithoutResult(s -> {
            ServiceRequest r = requests.findById(requestId).orElseThrow();
            assertEquals(RequestStatus.EN_ROUTE, r.getStatus());
            assertEquals(m.userId(), r.getAssignedMechanic().getId());
        });
    }
}
