package com.example.ratelimiter.config;

import com.example.ratelimiter.store.InMemoryLimiterStore;
import com.example.ratelimiter.store.LimiterStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps the in-memory store from growing without bound. Redis keys use TTLs instead. */
@Component
@ConditionalOnProperty(name = "ratelimit.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryStoreEvictor {

    private final LimiterStore store;

    public InMemoryStoreEvictor(LimiterStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelay = 60_000)
    public void evict() {
        if (store instanceof InMemoryLimiterStore memory) {
            memory.evictExpired();
        }
    }
}
