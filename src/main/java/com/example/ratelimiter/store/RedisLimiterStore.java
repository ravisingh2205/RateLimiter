package com.example.ratelimiter.store;

import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Production store. Every check is ONE Lua script call, so check-and-update is atomic across all
 * app nodes, and time comes from Redis (TIME) so node clock skew does not matter.
 */
public class RedisLimiterStore implements LimiterStore {

    private final StringRedisTemplate redis;

    private final RedisScript<List> tokenBucket = script("lua/token_bucket.lua");
    private final RedisScript<List> fixedWindow = script("lua/fixed_window.lua");
    private final RedisScript<List> slidingWindow = script("lua/sliding_window.lua");
    private final RedisScript<List> leakyBucket = script("lua/leaky_bucket.lua");
    private final RedisScript<List> acquire = script("lua/concurrency_acquire.lua");

    public RedisLimiterStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static RedisScript<List> script(String path) {
        DefaultRedisScript<List> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource(path));
        s.setResultType(List.class);
        return s;
    }

    @Override
    public LimiterResult tokenBucket(String key, long capacity, double refillPerSecond, int cost) {
        List<?> r = redis.execute(tokenBucket, List.of(key),
                String.valueOf(capacity), String.valueOf(refillPerSecond), String.valueOf(cost));
        return toResult(r, capacity);
    }

    @Override
    public LimiterResult fixedWindow(String key, long limit, int windowSeconds) {
        List<?> r = redis.execute(fixedWindow, List.of(key),
                String.valueOf(limit), String.valueOf(windowSeconds * 1000L));
        return toResult(r, limit);
    }

    @Override
    public LimiterResult slidingWindow(String key, long limit, int windowSeconds) {
        // hash tag keeps the derived current/previous window keys in the same Redis Cluster slot
        List<?> r = redis.execute(slidingWindow, List.of("{" + key + "}"),
                String.valueOf(limit), String.valueOf(windowSeconds * 1000L));
        return toResult(r, limit);
    }

    @Override
    public LimiterResult leakyBucket(String key, long capacity, double leakPerSecond) {
        List<?> r = redis.execute(leakyBucket, List.of(key),
                String.valueOf(capacity), String.valueOf(leakPerSecond));
        return toResult(r, capacity);
    }

    @Override
    public LimiterResult acquire(String key, long max, int leaseSeconds, String leaseId) {
        List<?> r = redis.execute(acquire, List.of(key),
                String.valueOf(max), String.valueOf(leaseSeconds * 1000L), leaseId);
        return toResult(r, max);
    }

    @Override
    public void release(String key, String leaseId) {
        redis.opsForZSet().remove(key, leaseId);
    }

    private static LimiterResult toResult(List<?> r, long limit) {
        if (r == null || r.size() < 4) {
            throw new IllegalStateException("Unexpected script result: " + r);
        }
        return new LimiterResult(
                ((Number) r.get(0)).longValue() == 1L,
                limit,
                ((Number) r.get(1)).longValue(),
                ((Number) r.get(2)).longValue(),
                ((Number) r.get(3)).longValue());
    }
}
