package com.roadguard.web.dto;

import com.roadguard.domain.enums.IssueType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateSosRequest(

        @NotNull
        IssueType issueType,

        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0")
        Double originLat,

        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0")
        Double originLng,

        @Size(max = 500)
        String note
) {
}
