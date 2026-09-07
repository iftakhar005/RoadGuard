package com.roadguard.service;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.Specialization;
import com.roadguard.repository.MechanicProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MatchingService {

    private static final double DISTANCE_WEIGHT = 0.7;
    private static final double RATING_WEIGHT = 0.3;
    private static final double MAX_STARS = 5.0;
    private static final double NEUTRAL_STARS = 3.5;

    private final MechanicProfileRepository mechanics;

    @Transactional(readOnly = true)
    public List<Candidate> findCandidates(ServiceRequest request) {
        double radiusKm = request.getSearchRadiusKm();
        GeoUtils.BoundingBox box = GeoUtils.boundingBox(
                request.getOriginLat(), request.getOriginLng(), radiusKm);

        List<MechanicProfile> pool = mechanics.findCandidatesInBox(
                AvailabilityStatus.ONLINE,
                request.getRequiredSpecialization(),
                box.minLat(), box.maxLat(), box.minLng(), box.maxLng());

        return rank(pool,
                request.getOriginLat(),
                request.getOriginLng(),
                radiusKm,
                request.getRequiredSpecialization());
    }

    public List<Candidate> rank(List<MechanicProfile> pool,
                                double originLat,
                                double originLng,
                                double radiusKm,
                                Specialization required) {

        List<Candidate> specialists = new ArrayList<>();
        List<Candidate> generalists = new ArrayList<>();

        for (MechanicProfile m : pool) {
            if (!m.hasLocation() || m.getStatus() != AvailabilityStatus.ONLINE) {
                continue;
            }
            double distanceKm = GeoUtils.haversineKm(
                    originLat, originLng, m.getCurrentLat(), m.getCurrentLng());
            if (distanceKm > radiusKm) {
                continue;
            }

            Candidate candidate = new Candidate(m, distanceKm, score(m, distanceKm, radiusKm));

            if (required != null && required != Specialization.GENERAL && m.hasSkill(required)) {
                specialists.add(candidate);
            } else {
                generalists.add(candidate);
            }
        }

        List<Candidate> chosen = specialists.isEmpty() ? generalists : specialists;
        chosen.sort(Comparator
                .comparingDouble(Candidate::score)
                .thenComparingLong(c -> c.profile().getId()));
        return chosen;
    }

    private double score(MechanicProfile mechanic, double distanceKm, double radiusKm) {
        double normalisedDistance = radiusKm <= 0
                ? 0.0
                : Math.min(1.0, distanceKm / radiusKm);

        double stars = mechanic.getRatingCount() == 0
                ? NEUTRAL_STARS
                : mechanic.getAvgRating();

        double ratingPenalty = 1.0 - (stars / MAX_STARS);

        return DISTANCE_WEIGHT * normalisedDistance + RATING_WEIGHT * ratingPenalty;
    }

    public record Candidate(MechanicProfile profile, double distanceKm, double score) {
    }
}
