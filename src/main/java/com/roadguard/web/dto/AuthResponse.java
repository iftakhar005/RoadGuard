package com.roadguard.web.dto;

import com.roadguard.domain.enums.Role;

// Sent back after a successful register or login. The client keeps the token and
// attaches it to everything afterwards.
//
// Deliberately does not include the password hash or anything else off the User
// row - only what the frontend actually needs.
public record AuthResponse(
        String token,
        long expiresInSeconds,
        Long userId,
        String username,
        Role role
) {
}
