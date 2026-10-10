package com.example.ratelimiter.model;

import java.time.Instant;

/** A rule bound to a concrete subject (e.g. EMP E42), with any override already applied. */
public record ResolvedRule(
        Rule rule,
        String subject,
        int limitCount,
        int windowSeconds,
        boolean blocked,
        boolean overridden,
        String overrideReason,
        Instant overrideExpiresAt) {

    public long capacity() {
        return rule.burstCapacity() != null ? rule.burstCapacity() : limitCount;
    }

    /** Units per second for TOKEN_BUCKET refill and LEAKY_BUCKET drain. */
    public double ratePerSecond() {
        return (double) limitCount / Math.max(1, windowSeconds);
    }

    /** Redis/in-memory key. Includes the algorithm because each algorithm stores a different structure. */
    public String key() {
        return "rl:" + rule.apiId() + ":" + rule.dimension() + ":" + subject + ":" + rule.algorithm();
    }
}
