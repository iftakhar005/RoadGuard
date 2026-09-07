package com.roadguard.web.dto;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.enums.Specialization;
import com.roadguard.service.MatchingService;

import java.util.Set;
import java.util.TreeSet;

public record MechanicCandidateResponse(
        Long mechanicUserId,
        String username,
        Set<Specialization> specializations,
        double distanceKm,
        double score,
        Double currentLat,
        Double currentLng,
        double avgRating,
        int ratingCount
) {
    public static MechanicCandidateResponse from(MatchingService.Candidate candidate) {
        MechanicProfile p = candidate.profile();
        return new MechanicCandidateResponse(
                p.getUser().getId(),
                p.getUser().getUsername(),
                new TreeSet<>(p.getSpecializations()),
                round(candidate.distanceKm()),
                round(candidate.score()),
                p.getCurrentLat(),
                p.getCurrentLng(),
                p.getAvgRating(),
                p.getRatingCount());
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
