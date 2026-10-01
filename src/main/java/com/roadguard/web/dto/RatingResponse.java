package com.roadguard.web.dto;

import com.roadguard.domain.Rating;

import java.time.Instant;

public record RatingResponse(
        Long id,
        Long requestId,
        Long driverId,
        Long mechanicId,
        int stars,
        String comment,
        Instant createdAt
) {
    public static RatingResponse from(Rating rating) {
        return new RatingResponse(
                rating.getId(),
                rating.getRequest().getId(),
                rating.getDriver().getId(),
                rating.getMechanic().getId(),
                rating.getStars(),
                rating.getComment(),
                rating.getCreatedAt());
    }
}
