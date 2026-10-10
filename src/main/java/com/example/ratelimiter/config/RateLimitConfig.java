package com.example.ratelimiter.config;

import com.example.ratelimiter.engine.DecisionListener;
import com.example.ratelimiter.engine.RateLimitEngine;
import com.example.ratelimiter.repo.RuleCache;
import com.example.ratelimiter.store.InMemoryLimiterStore;
import com.example.ratelimiter.store.LimiterStore;
import com.example.ratelimiter.store.RedisLimiterStore;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RateLimitConfig {

    @Bean
    @ConditionalOnProperty(name = "ratelimit.store", havingValue = "redis")
    public LimiterStore redisLimiterStore(StringRedisTemplate redis) {
        return new RedisLimiterStore(redis);
    }

    @Bean
    @ConditionalOnProperty(name = "ratelimit.store", havingValue = "memory", matchIfMissing = true)
    public LimiterStore inMemoryLimiterStore() {
        return new InMemoryLimiterStore();
    }

    @Bean
    public RateLimitEngine rateLimitEngine(RuleCache cache, LimiterStore store, DecisionListener listener) {
        return new RateLimitEngine(cache::current, store, listener, Instant::now);
    }
}
