package com.example.ratelimiter.store;

/**
 * Outcome of one limiter check.
 * @param allowed       request may proceed
 * @param limit         the configured limit (for headers)
 * @param remaining     how many more requests fit right now
 * @param retryAfterMs  when denied: how long until a retry can succeed (0 when allowed)
 * @param resetMs       time until the limit fully recovers (for RateLimit-Reset)
 */
public record LimiterResult(boolean allowed, long limit, long remaining, long retryAfterMs, long resetMs) {
}
