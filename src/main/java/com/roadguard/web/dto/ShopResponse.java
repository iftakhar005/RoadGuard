package com.roadguard.web.dto;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.ShopType;
import com.roadguard.domain.enums.Specialization;

import java.util.Set;
import java.util.TreeSet;

public record ShopResponse(
        Long profileId,
        Long mechanicUserId,
        String ownerName,
        String shopName,
        ShopType shopType,
        String shopTypeLabel,
        String contactPhone,
        String shopAddress,
        Double shopLat,
        Double shopLng,
        boolean hasImage,
        String imageUrl,
        Set<Specialization> specializations,
        AvailabilityStatus status,
        double avgRating,
        int ratingCount
) {
    public static ShopResponse from(MechanicProfile p) {
        boolean hasImage = p.getShopImagePath() != null && !p.getShopImagePath().isBlank();
        return new ShopResponse(
                p.getId(),
                p.getUser().getId(),
                p.getUser().getUsername(),
                p.getShopName(),
                p.getShopType(),
                p.getShopType() == null ? null : p.getShopType().getLabel(),
                p.getContactPhone(),
                p.getShopAddress(),
                p.getShopLat(),
                p.getShopLng(),
                hasImage,
                hasImage ? "/api/shops/" + p.getId() + "/image" : null,
                new TreeSet<>(p.getSpecializations()),
                p.getStatus(),
                p.getAvgRating(),
                p.getRatingCount());
    }
}
