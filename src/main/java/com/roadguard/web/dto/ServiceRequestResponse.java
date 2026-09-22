package com.roadguard.web.dto;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.ServiceRequest;
import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.RequestStatus;
import com.roadguard.domain.enums.Severity;
import com.roadguard.domain.enums.Specialization;

import java.time.Instant;

public record ServiceRequestResponse(
        Long id,
        RequestStatus status,
        IssueType issueType,
        Specialization requiredSpecialization,
        Severity severity,
        String note,
        double originLat,
        double originLng,
        double searchRadiusKm,
        Long assignedMechanicId,
        String assignedMechanicName,
        Double mechanicLat,
        Double mechanicLng,
        String aiFaultCategory,
        String aiGuidance,
        Instant createdAt,
        Instant acceptedAt,
        Instant completedAt
) {
    public static ServiceRequestResponse from(ServiceRequest req) {
        return from(req, null);
    }

    public static ServiceRequestResponse from(ServiceRequest req, MechanicProfile mechanic) {

        boolean share = mechanic != null
                && req.getStatus().isAssignedToMechanic()
                && mechanic.hasLocation();

        return new ServiceRequestResponse(
                req.getId(),
                req.getStatus(),
                req.getIssueType(),
                req.getRequiredSpecialization(),
                req.getSeverity(),
                req.getNote(),
                req.getOriginLat(),
                req.getOriginLng(),
                req.getSearchRadiusKm(),
                req.getAssignedMechanic() == null ? null : req.getAssignedMechanic().getId(),
                req.getAssignedMechanic() == null ? null : req.getAssignedMechanic().getUsername(),
                share ? mechanic.getCurrentLat() : null,
                share ? mechanic.getCurrentLng() : null,
                req.getAiFaultCategory(),
                req.getAiGuidance(),
                req.getCreatedAt(),
                req.getAcceptedAt(),
                req.getCompletedAt());
    }
}
