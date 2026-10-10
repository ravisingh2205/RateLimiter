package com.example.ratelimiter.admin;

import com.example.ratelimiter.model.Algorithm;
import com.example.ratelimiter.model.Dimension;
import com.example.ratelimiter.model.FailMode;
import com.example.ratelimiter.model.Mode;
import com.example.ratelimiter.repo.RuleInput;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RuleRequest(
        @NotBlank String apiId,                 // an api_id from /admin/rate-limits/apis, or "*" for all APIs
        Long planId,                            // null = default rule
        @NotNull Dimension dimension,
        @NotNull Algorithm algorithm,
        @Min(1) int limitCount,
        @Min(1) int windowSeconds,
        @Min(1) Integer burstCapacity,
        Integer priority,                       // default 100
        FailMode failMode,                      // default FAIL_OPEN
        Mode mode,                              // default ENFORCE
        Boolean enabled) {                      // default true

    public RuleInput toInput() {
        return new RuleInput(apiId, planId, dimension, algorithm, limitCount, windowSeconds, burstCapacity,
                priority == null ? 100 : priority,
                failMode == null ? FailMode.FAIL_OPEN : failMode,
                mode == null ? Mode.ENFORCE : mode,
                enabled == null || enabled);
    }
}
