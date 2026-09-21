package com.roadguard.web.dto;

import com.roadguard.domain.enums.RequestStatus;
import jakarta.validation.constraints.NotNull;

public record StatusUpdateRequest(

        @NotNull
        RequestStatus status
) {
}
