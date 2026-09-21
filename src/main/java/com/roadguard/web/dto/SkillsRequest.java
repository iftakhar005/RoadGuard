package com.roadguard.web.dto;

import com.roadguard.domain.enums.Specialization;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record SkillsRequest(

        @Size(max = 8, message = "that is more skills than exist")
        Set<Specialization> specializations
) {
}
