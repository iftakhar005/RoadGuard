package com.roadguard.web.dto;

import com.roadguard.domain.enums.ShopType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ShopRequest(

        @NotBlank @Size(min = 2, max = 120)
        String shopName,

        @NotNull
        ShopType shopType,

        @NotBlank
        @Pattern(regexp = "[0-9+() -]{6,30}", message = "does not look like a phone number")
        String contactPhone,

        @Size(max = 300)
        String shopAddress,

        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0")
        Double shopLat,

        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0")
        Double shopLng
) {
}
