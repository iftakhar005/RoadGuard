package com.roadguard.service;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.domain.enums.Specialization;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchingServiceTest {

    private static final double ORIGIN_LAT = 23.8103;
    private static final double ORIGIN_LNG = 90.4125;

    private final MatchingService matching = new MatchingService(null);

    private MechanicProfile mechanic(long id,
                                     String name,
                                     double lat,
                                     double lng,
                                     Set<Specialization> skills,
                                     AvailabilityStatus status,
                                     double avgRating,
                                     int ratingCount) {
        User user = new User(name, name + "@test.com", "hash", Role.MECHANIC);
        user.setId(id);

        MechanicProfile p = new MechanicProfile(user);
        p.setId(id);
        p.setCurrentLat(lat);
        p.setCurrentLng(lng);
        p.setSpecializations(skills);
        p.setStatus(status);
        p.setAvgRating(avgRating);
        p.setRatingCount(ratingCount);
        return p;
    }

    private MechanicProfile atOffset(long id, String name, double latOffset, Set<Specialization> skills) {
        return mechanic(id, name, ORIGIN_LAT + latOffset, ORIGIN_LNG, skills,
                AvailabilityStatus.ONLINE, 0.0, 0);
    }

    @Test
    @DisplayName("closer mechanic ranks first when ratings are equal")
    void nearestFirst() {
        MechanicProfile far = atOffset(1, "far", 0.03, Set.of(Specialization.TIRE));
        MechanicProfile near = atOffset(2, "near", 0.005, Set.of(Specialization.TIRE));

        List<MatchingService.Candidate> ranked =
                matching.rank(List.of(far, near), ORIGIN_LAT, ORIGIN_LNG, 10.0, Specialization.TIRE);

        assertEquals(2, ranked.size());
        assertEquals("near", ranked.get(0).profile().getUser().getUsername());
    }

    @Test
    @DisplayName("mechanics beyond the radius are excluded")
    void outsideRadiusExcluded() {
        MechanicProfile inside = atOffset(1, "inside", 0.01, Set.of(Specialization.TIRE));
        MechanicProfile outside = atOffset(2, "outside", 0.5, Set.of(Specialization.TIRE));

        List<MatchingService.Candidate> ranked =
                matching.rank(List.of(inside, outside), ORIGIN_LAT, ORIGIN_LNG, 5.0, Specialization.TIRE);

        assertEquals(1, ranked.size());
        assertEquals("inside", ranked.get(0).profile().getUser().getUsername());
    }

    @Test
    @DisplayName("offline mechanics are excluded even if they are closest")
    void offlineExcluded() {
        MechanicProfile offline = mechanic(1, "offline", ORIGIN_LAT, ORIGIN_LNG,
                Set.of(Specialization.TIRE), AvailabilityStatus.OFFLINE, 5.0, 10);
        MechanicProfile online = atOffset(2, "online", 0.01, Set.of(Specialization.TIRE));

        List<MatchingService.Candidate> ranked =
                matching.rank(List.of(offline, online), ORIGIN_LAT, ORIGIN_LNG, 10.0, Specialization.TIRE);

        assertEquals(1, ranked.size());
        assertEquals("online", ranked.get(0).profile().getUser().getUsername());
    }

    @Test
    @DisplayName("a mechanic with no location is skipped")
    void noLocationSkipped() {
        MechanicProfile nowhere = mechanic(1, "nowhere", 0, 0,
                Set.of(Specialization.TIRE), AvailabilityStatus.ONLINE, 0.0, 0);
        nowhere.setCurrentLat(null);
        nowhere.setCurrentLng(null);

        List<MatchingService.Candidate> ranked =
                matching.rank(List.of(nowhere), ORIGIN_LAT, ORIGIN_LNG, 10.0, Specialization.TIRE);

        assertTrue(ranked.isEmpty());
    }

    @Test
    @DisplayName("generalists are only used when no specialist is in range")
    void generalIsFallbackOnly() {
        MechanicProfile specialist = atOffset(1, "specialist", 0.02, Set.of(Specialization.TIRE));
        MechanicProfile generalist = atOffset(2, "generalist", 0.001, Set.of(Specialization.GENERAL));

        List<MatchingService.Candidate> withSpecialist =
                matching.rank(List.of(specialist, generalist), ORIGIN_LAT, ORIGIN_LNG, 10.0, Specialization.TIRE);

        assertEquals(1, withSpecialist.size());
        assertEquals("specialist", withSpecialist.get(0).profile().getUser().getUsername());

        List<MatchingService.Candidate> onlyGeneralist =
                matching.rank(List.of(generalist), ORIGIN_LAT, ORIGIN_LNG, 10.0, Specialization.TIRE);

        assertEquals(1, onlyGeneralist.size());
        assertEquals("generalist", onlyGeneralist.get(0).profile().getUser().getUsername());
    }

    @Test
    @DisplayName("a better rated mechanic wins when distances are equal")
    void ratingBreaksTie() {
        MechanicProfile poor = mechanic(1, "poor", ORIGIN_LAT + 0.01, ORIGIN_LNG,
                Set.of(Specialization.TIRE), AvailabilityStatus.ONLINE, 2.0, 10);
        MechanicProfile great = mechanic(2, "great", ORIGIN_LAT + 0.01, ORIGIN_LNG,
                Set.of(Specialization.TIRE), AvailabilityStatus.ONLINE, 5.0, 10);

        List<MatchingService.Candidate> ranked =
                matching.rank(List.of(poor, great), ORIGIN_LAT, ORIGIN_LNG, 10.0, Specialization.TIRE);

        assertEquals("great", ranked.get(0).profile().getUser().getUsername());
    }

    @Test
    @DisplayName("an unrated mechanic is treated as neutral, not as zero stars")
    void unratedIsNotPunished() {
        MechanicProfile unrated = mechanic(1, "unrated", ORIGIN_LAT + 0.01, ORIGIN_LNG,
                Set.of(Specialization.TIRE), AvailabilityStatus.ONLINE, 0.0, 0);
        MechanicProfile badlyRated = mechanic(2, "badly_rated", ORIGIN_LAT + 0.01, ORIGIN_LNG,
                Set.of(Specialization.TIRE), AvailabilityStatus.ONLINE, 1.0, 20);

        List<MatchingService.Candidate> ranked =
                matching.rank(List.of(unrated, badlyRated), ORIGIN_LAT, ORIGIN_LNG, 10.0, Specialization.TIRE);

        assertEquals("unrated", ranked.get(0).profile().getUser().getUsername(),
                "a mechanic with no ratings should not be ranked below a one-star mechanic");
    }

    @Test
    @DisplayName("ranking is stable for identical mechanics")
    void deterministicOrder() {
        MechanicProfile a = atOffset(1, "a", 0.01, Set.of(Specialization.TIRE));
        MechanicProfile b = atOffset(2, "b", 0.01, Set.of(Specialization.TIRE));

        List<MatchingService.Candidate> first =
                matching.rank(List.of(b, a), ORIGIN_LAT, ORIGIN_LNG, 10.0, Specialization.TIRE);
        List<MatchingService.Candidate> second =
                matching.rank(List.of(a, b), ORIGIN_LAT, ORIGIN_LNG, 10.0, Specialization.TIRE);

        assertEquals(first.get(0).profile().getId(), second.get(0).profile().getId());
    }
}
