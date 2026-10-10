package com.example.ratelimiter.store;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Single-node implementation of the same algorithms the Lua scripts implement.
 * Use it for local development and tests; it is NOT shared between app instances.
 * Each key owns a small state object and all updates to it are synchronized on that object.
 */
public class InMemoryLimiterStore implements LimiterStore {

    private static final class State {
        double value;          // tokens (token bucket) or level (leaky bucket)
        long ts;               // last update, ms
        long count;            // fixed window / sliding window current count
        long prevCount;        // sliding window previous count
        long windowStart;      // fixed window / sliding window start, ms
        long expireAt;         // for eviction
        final Map<String, Long> leases = new HashMap<>(); // leaseId -> expiry ms
    }

    private final ConcurrentMap<String, State> states = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public InMemoryLimiterStore() {
        this(System::currentTimeMillis);
    }

    /** Clock is injectable so tests can control time. */
    public InMemoryLimiterStore(LongSupplier clock) {
        this.clock = clock;
    }

    private State state(String key) {
        return states.computeIfAbsent(key, k -> new State());
    }

    @Override
    public LimiterResult tokenBucket(String key, long capacity, double refillPerSecond, int cost) {
        long now = clock.getAsLong();
        State s = state(key);
        synchronized (s) {
            if (s.ts == 0) {
                s.value = capacity;
                s.ts = now;
            }
            double tokens = Math.min(capacity, s.value + Math.max(0, now - s.ts) / 1000.0 * refillPerSecond);
            boolean allowed = tokens >= cost;
            long retry = 0;
            if (allowed) {
                tokens -= cost;
            } else {
                retry = (long) Math.ceil((cost - tokens) / refillPerSecond * 1000);
            }
            s.value = tokens;
            s.ts = now;
            s.expireAt = now + (long) Math.ceil(capacity / refillPerSecond * 1000) * 2 + 1000;
            long reset = (long) Math.ceil((capacity - tokens) / refillPerSecond * 1000);
            return new LimiterResult(allowed, capacity, (long) Math.floor(tokens), retry, reset);
        }
    }

    @Override
    public LimiterResult fixedWindow(String key, long limit, int windowSeconds) {
        long now = clock.getAsLong();
        long win = windowSeconds * 1000L;
        State s = state(key);
        synchronized (s) {
            if (s.windowStart == 0 || now >= s.windowStart + win) {
                s.windowStart = now;
                s.count = 0;
            }
            long ttl = s.windowStart + win - now;
            s.expireAt = s.windowStart + win;
            if (s.count >= limit) {
                return new LimiterResult(false, limit, 0, ttl, ttl);
            }
            s.count++;
            return new LimiterResult(true, limit, limit - s.count, 0, ttl);
        }
    }

    @Override
    public LimiterResult slidingWindow(String key, long limit, int windowSeconds) {
        long now = clock.getAsLong();
        long win = windowSeconds * 1000L;
        State s = state(key);
        synchronized (s) {
            long curStart = now - (now % win);
            if (s.windowStart == 0) {
                s.windowStart = curStart;
            }
            if (curStart != s.windowStart) {
                // rolled over: previous becomes the old current only if it is exactly one window back
                s.prevCount = (curStart - s.windowStart == win) ? s.count : 0;
                s.count = 0;
                s.windowStart = curStart;
            }
            long elapsed = now - curStart;
            long untilNext = win - elapsed;
            double weightedPrev = s.prevCount * ((double) (win - elapsed) / win);
            s.expireAt = curStart + 2 * win;
            if (weightedPrev + s.count + 1 > limit) {
                return new LimiterResult(false, limit, 0, untilNext, untilNext);
            }
            s.count++;
            long remaining = Math.max(0, (long) Math.floor(limit - (weightedPrev + s.count)));
            return new LimiterResult(true, limit, remaining, 0, untilNext);
        }
    }

    @Override
    public LimiterResult leakyBucket(String key, long capacity, double leakPerSecond) {
        long now = clock.getAsLong();
        State s = state(key);
        synchronized (s) {
            if (s.ts == 0) {
                s.value = 0;
                s.ts = now;
            }
            double level = Math.max(0, s.value - Math.max(0, now - s.ts) / 1000.0 * leakPerSecond);
            boolean allowed = level + 1 <= capacity;
            long retry = 0;
            if (allowed) {
                level += 1;
            } else {
                retry = (long) Math.ceil((level + 1 - capacity) / leakPerSecond * 1000);
            }
            s.value = level;
            s.ts = now;
            s.expireAt = now + (long) Math.ceil(capacity / leakPerSecond * 1000) * 2 + 1000;
            long reset = (long) Math.ceil(level / leakPerSecond * 1000);
            return new LimiterResult(allowed, capacity, (long) Math.floor(capacity - level), retry, reset);
        }
    }

    @Override
    public LimiterResult acquire(String key, long max, int leaseSeconds, String leaseId) {
        long now = clock.getAsLong();
        State s = state(key);
        synchronized (s) {
            Iterator<Map.Entry<String, Long>> it = s.leases.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue() <= now) {
                    it.remove();
                }
            }
            if (s.leases.size() >= max) {
                long wait = 1000;
                for (long exp : s.leases.values()) {
                    wait = Math.min(wait == 1000 ? Long.MAX_VALUE : wait, Math.max(1, exp - now));
                }
                return new LimiterResult(false, max, 0, wait, wait);
            }
            s.leases.put(leaseId, now + leaseSeconds * 1000L);
            s.expireAt = now + leaseSeconds * 2000L;
            return new LimiterResult(true, max, max - s.leases.size(), 0, 0);
        }
    }

    @Override
    public void release(String key, String leaseId) {
        State s = states.get(key);
        if (s != null) {
            synchronized (s) {
                s.leases.remove(leaseId);
            }
        }
    }

    /** Drops idle keys so the map does not grow forever. Called periodically by the Spring config. */
    public void evictExpired() {
        long now = clock.getAsLong();
        states.entrySet().removeIf(e -> {
            State s = e.getValue();
            synchronized (s) {
                s.leases.values().removeIf(exp -> exp <= now);
                return s.expireAt != 0 && s.expireAt < now && s.leases.isEmpty();
            }
        });
    }

    public int size() {
        return states.size();
    }
}
