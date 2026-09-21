package com.roadguard.web.dto;

import com.roadguard.domain.RequestOffer;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.Severity;
import com.roadguard.domain.enums.Specialization;
import com.roadguard.service.GeoUtils;

import java.time.Instant;

public record OfferResponse(
        Long requestId,
        String offerToken,
        IssueType issueType,
        Specialization requiredSpecialization,
        Severity severity,
        String note,
        double originLat,
        double originLng,
        Double distanceKm,
        Instant sentAt
) {
    public static OfferResponse from(RequestOffer offer) {
        ServiceRequest r = offer.getRequest();
        return new OfferResponse(
                r.getId(),
                offer.getOfferToken(),
                r.getIssueType(),
                r.getRequiredSpecialization(),
                r.getSeverity(),
                r.getNote(),
                r.getOriginLat(),
                r.getOriginLng(),
                null,
                offer.getSentAt());
    }

    public static OfferResponse from(RequestOffer offer, double mechanicLat, double mechanicLng) {
        ServiceRequest r = offer.getRequest();
        double km = GeoUtils.haversineKm(mechanicLat, mechanicLng, r.getOriginLat(), r.getOriginLng());
        return new OfferResponse(
                r.getId(),
                offer.getOfferToken(),
                r.getIssueType(),
                r.getRequiredSpecialization(),
                r.getSeverity(),
                r.getNote(),
                r.getOriginLat(),
                r.getOriginLng(),
                Math.round(km * 1000.0) / 1000.0,
                offer.getSentAt());
    }
}
