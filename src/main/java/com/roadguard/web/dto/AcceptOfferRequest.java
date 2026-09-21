package com.roadguard.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AcceptOfferRequest(

        @NotBlank
        String offerToken
) {
}
