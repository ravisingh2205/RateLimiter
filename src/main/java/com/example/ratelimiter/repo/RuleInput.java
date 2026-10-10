package com.example.ratelimiter.repo;

import com.example.ratelimiter.model.Algorithm;
import com.example.ratelimiter.model.Dimension;
import com.example.ratelimiter.model.FailMode;
import com.example.ratelimiter.model.Mode;

/** Fields needed to create or replace a rule. */
public record RuleInput(
        String apiId,
        Long planId,
        Dimension dimension,
        Algorithm algorithm,
        int limitCount,
        int windowSeconds,
        Integer burstCapacity,
        int priority,
        FailMode failMode,
        Mode mode,
        boolean enabled) {
}
