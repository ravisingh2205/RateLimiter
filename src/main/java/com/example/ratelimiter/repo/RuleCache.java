package com.example.ratelimiter.repo;

import com.example.ratelimiter.engine.Snapshot;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Holds the current Snapshot. Refreshed (a) at startup, (b) right after every admin change on this
 * node, and (c) on a timer. In a multi-node deployment publish a "rules_changed" message (Redis
 * pub/sub or Kafka) from the admin API so the other nodes call refresh() immediately instead of waiting
 * for the timer.
 */
@Component
public class RuleCache {

    private static final Logger log = LoggerFactory.getLogger(RuleCache.class);

    private final RuleRepository repository;
    private volatile Snapshot current = Snapshot.empty();

    public RuleCache(RuleRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        refresh();
        log.info("Loaded {} rules for {} APIs", current.ruleCount(), current.apiCount());
    }

    @Scheduled(fixedDelayString = "${ratelimit.rule-refresh-millis:30000}")
    public void refresh() {
        try {
            current = repository.loadSnapshot();
        } catch (RuntimeException e) {
            // keep serving the last good snapshot
            log.warn("Rule refresh failed, keeping previous snapshot: {}", e.toString());
        }
    }

    public Snapshot current() {
        return current;
    }
}
