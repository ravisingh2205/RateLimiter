package com.example.ratelimiter.store;

/**
 * Where limiter state lives. RedisLimiterStore is the production implementation (atomic Lua scripts,
 * shared by all app nodes); InMemoryLimiterStore is for local runs and tests (single node only).
 */
public interface LimiterStore {

    LimiterResult tokenBucket(String key, long capacity, double refillPerSecond, int cost);

    LimiterResult fixedWindow(String key, long limit, int windowSeconds);

    LimiterResult slidingWindow(String key, long limit, int windowSeconds);

    LimiterResult leakyBucket(String key, long capacity, double leakPerSecond);

    /** Try to take one in-flight slot. The lease expires after leaseSeconds if never released. */
    LimiterResult acquire(String key, long max, int leaseSeconds, String leaseId);

    void release(String key, String leaseId);
}
