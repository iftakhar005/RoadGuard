package com.roadguard.web.dto;

import com.roadguard.domain.enums.AvailabilityStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record MechanicStatusRequest(

        @NotNull
        AvailabilityStatus status,

        @DecimalMin("-90.0") @DecimalMax("90.0")
        Double lat,

        @DecimalMin("-180.0") @DecimalMax("180.0")
        Double lng
) {
}
