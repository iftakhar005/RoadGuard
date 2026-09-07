package com.roadguard.web.dto;

import com.roadguard.domain.enums.Role;
import com.roadguard.domain.enums.Specialization;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record RegisterRequest(

        @NotBlank @Size(min = 3, max = 50)
        String username,

        @NotBlank @Email @Size(max = 120)
        String email,

        @NotBlank @Size(min = 6, max = 100)
        String password,

        @NotNull
        Role role,

        @Size(max = 30)
        String phone,

        Set<Specialization> specializations
) {
}
