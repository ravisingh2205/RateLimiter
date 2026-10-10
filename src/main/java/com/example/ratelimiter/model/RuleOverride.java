package com.example.ratelimiter.model;

import java.time.Instant;

/** Per-subject exception to a rule: a VIP department, an abusive IP, a load-test account. */
public record RuleOverride(
        long overrideId,
        long ruleId,
        Dimension dimension,
        String subjectValue,
        Integer limitCount,
        Integer windowSeconds,
        boolean blocked,
        Instant expiresAt,
        String reason) {

    public boolean activeAt(Instant now) {
        return expiresAt == null || expiresAt.isAfter(now);
    }
}
