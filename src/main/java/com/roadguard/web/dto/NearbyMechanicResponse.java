package com.roadguard.web.dto;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.enums.Specialization;

import java.util.Set;
import java.util.TreeSet;

public record NearbyMechanicResponse(
        String name,
        double lat,
        double lng,
        double distanceKm,
        Set<Specialization> skills,
        double avgRating,
        int ratingCount
) {
    public static NearbyMechanicResponse from(MechanicProfile profile, double distanceKm) {
        return new NearbyMechanicResponse(
                profile.getUser().getUsername(),
                profile.getCurrentLat(),
                profile.getCurrentLng(),
                distanceKm,
                new TreeSet<>(profile.getSpecializations()),
                profile.getAvgRating(),
                profile.getRatingCount());
    }
}
