package com.example.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * ratelimit.* settings.
 * store               memory (single node, no Redis needed) or redis (shared, production)
 * ruleRefreshMillis   how often rules are re-read from the database as a safety net
 * trustForwardedFor   take the client IP from X-Forwarded-For. Only enable behind a proxy you control.
 * adminToken          value of the X-Admin-Token header required by /admin/** (replace with real auth)
 * recordEvents        write denials to the rate_limit_event table
 */
@ConfigurationProperties(prefix = "ratelimit")
public record RateLimitProperties(
        @DefaultValue("memory") String store,
        @DefaultValue("30000") long ruleRefreshMillis,
        @DefaultValue("false") boolean trustForwardedFor,
        @DefaultValue("changeme") String adminToken,
        @DefaultValue("true") boolean recordEvents) {
}
