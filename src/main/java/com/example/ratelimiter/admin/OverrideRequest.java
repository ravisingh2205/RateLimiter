package com.example.ratelimiter.admin;

import com.example.ratelimiter.model.Dimension;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record OverrideRequest(
        @NotNull Long ruleId,
        @NotNull Dimension dimension,
        @NotBlank String subjectValue,          // e.g. "D7", "E42", "10.0.0.5"
        @Min(1) Integer limitCount,             // null = inherit from rule
        @Min(1) Integer windowSeconds,          // null = inherit from rule
        Boolean blocked,                        // true = hard block
        Instant expiresAt,                      // null = never expires
        String reason) {
}
