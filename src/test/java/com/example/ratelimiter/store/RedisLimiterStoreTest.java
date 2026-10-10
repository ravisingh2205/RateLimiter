package com.example.ratelimiter.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Runs the Lua scripts through RedisLimiterStore against a real Redis (REDIS_HOST / REDIS_PORT,
 * default localhost:6379). Skipped automatically when no Redis is reachable.
 */
class RedisLimiterStoreTest {

    private static RedisLimiterStore store;

    @BeforeAll
    static void connect() {
        String host = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        boolean up;
        try {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(host, port);
            factory.afterPropertiesSet();
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            template.getConnectionFactory().getConnection().ping();
            store = new RedisLimiterStore(template);
            up = true;
        } catch (Exception e) {
            up = false;
        }
        Assumptions.assumeTrue(up, "Redis not reachable, skipping Redis store tests");
    }

    private static String key() {
        return "test:" + UUID.randomUUID();
    }

    private static int allowed(int attempts, Supplier<LimiterResult> call) {
        int ok = 0;
        for (int i = 0; i < attempts; i++) {
            if (call.get().allowed()) {
                ok++;
            }
        }
        return ok;
    }

    @Test
    void tokenBucket() throws Exception {
        String k = key();
        assertEquals(5, allowed(7, () -> store.tokenBucket(k, 5, 1, 1)));
        assertTrue(store.tokenBucket(k, 5, 1, 1).retryAfterMs() > 0);
        Thread.sleep(1200);
        assertTrue(store.tokenBucket(k, 5, 1, 1).allowed());
    }

    @Test
    void fixedWindow() throws Exception {
        String k = key();
        assertEquals(3, allowed(5, () -> store.fixedWindow(k, 3, 1)));
        Thread.sleep(1100);
        assertTrue(store.fixedWindow(k, 3, 1).allowed());
    }

    @Test
    void slidingWindow() throws Exception {
        String k = key();
        assertEquals(3, allowed(5, () -> store.slidingWindow(k, 3, 1)));
        Thread.sleep(2100);
        assertTrue(store.slidingWindow(k, 3, 1).allowed());
    }

    @Test
    void leakyBucket() throws Exception {
        String k = key();
        assertEquals(3, allowed(5, () -> store.leakyBucket(k, 3, 2)));
        Thread.sleep(700);
        assertTrue(store.leakyBucket(k, 3, 2).allowed());
    }

    @Test
    void concurrency() throws Exception {
        String k = key();
        assertTrue(store.acquire(k, 2, 1, "a").allowed());
        assertTrue(store.acquire(k, 2, 1, "b").allowed());
        assertFalse(store.acquire(k, 2, 1, "c").allowed());
        store.release(k, "a");
        assertTrue(store.acquire(k, 2, 1, "c").allowed());
        Thread.sleep(1200);
        assertTrue(store.acquire(k, 2, 1, "d").allowed());
    }

    @Test
    void tokenBucketIsAtomicAcrossThreads() throws Exception {
        String k = key();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger ok = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(200);
        for (int i = 0; i < 200; i++) {
            pool.submit(() -> {
                try {
                    if (store.tokenBucket(k, 50, 0.0001, 1).allowed()) {
                        ok.incrementAndGet();
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        pool.shutdown();
        assertEquals(50, ok.get());
    }
}
