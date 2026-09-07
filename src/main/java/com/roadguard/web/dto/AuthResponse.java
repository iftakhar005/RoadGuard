package com.roadguard.web.dto;

import com.roadguard.domain.enums.Role;

public record AuthResponse(
        String token,
        long expiresInSeconds,
        Long userId,
        String username,
        Role role
) {
}
