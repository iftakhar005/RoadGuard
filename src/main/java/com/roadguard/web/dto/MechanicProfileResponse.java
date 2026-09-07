package com.roadguard.web.dto;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.Specialization;

import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

public record MechanicProfileResponse(
        Long profileId,
        Long userId,
        String username,
        AvailabilityStatus status,
        Set<Specialization> specializations,
        Double currentLat,
        Double currentLng,
        Instant lastHeartbeat,
        double avgRating,
        int ratingCount
) {
    public static MechanicProfileResponse from(MechanicProfile profile) {
        return new MechanicProfileResponse(
                profile.getId(),
                profile.getUser().getId(),
                profile.getUser().getUsername(),
                profile.getStatus(),
                new TreeSet<>(profile.getSpecializations()),
                profile.getCurrentLat(),
                profile.getCurrentLng(),
                profile.getLastHeartbeat(),
                profile.getAvgRating(),
                profile.getRatingCount());
    }
}
