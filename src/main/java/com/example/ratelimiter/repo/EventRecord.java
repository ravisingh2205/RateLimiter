package com.example.ratelimiter.repo;

import java.time.Instant;

public record EventRecord(long eventId, Instant occurredAt, String apiId, Long ruleId, String dimension,
                          String subjectValue, String decision) {
}
