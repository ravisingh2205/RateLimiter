package com.example.ratelimiter.events;

import com.example.ratelimiter.repo.RuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Writes denial events off the request thread. In production replace this with a Kafka producer
 * (topic rate_limit_events) and let a consumer batch-insert, so the database is never on the hot path.
 */
@Component
public class EventWriter {

    private static final Logger log = LoggerFactory.getLogger(EventWriter.class);

    private final RuleRepository repository;

    public EventWriter(RuleRepository repository) {
        this.repository = repository;
    }

    @Async("rlEvents")
    public void write(String apiId, Long ruleId, String dimension, String subject, String decision) {
        try {
            repository.insertEvent(apiId, ruleId, dimension, subject, decision);
        } catch (RuntimeException e) {
            log.debug("Could not record rate limit event: {}", e.toString());
        }
    }
}
