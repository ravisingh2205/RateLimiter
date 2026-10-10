package com.example.ratelimiter.model;

import java.time.Instant;

/**
 * One row of rate_limit_rule.
 * apiId "*" means the rule applies to every API.
 * planId null means "default rule"; a rule with a planId only applies to subjects on that plan.
 *
 * Meaning of the numbers per algorithm:
 *  TOKEN_BUCKET   capacity = burstCapacity (or limitCount), refill = limitCount per windowSeconds
 *  FIXED_WINDOW / SLIDING_WINDOW   limitCount requests per windowSeconds
 *  LEAKY_BUCKET   queue depth = burstCapacity (or limitCount), drain = limitCount per windowSeconds
 *  CONCURRENCY    limitCount in-flight requests, windowSeconds = lease TTL (safety net)
 */
public record Rule(
        long ruleId,
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
        boolean enabled,
        int version,
        String updatedBy,
        Instant updatedAt) {
}
