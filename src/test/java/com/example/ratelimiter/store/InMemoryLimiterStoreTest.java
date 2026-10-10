package com.example.ratelimiter.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InMemoryLimiterStoreTest {

    private final long[] now = {1_000_000L};
    private final InMemoryLimiterStore store = new InMemoryLimiterStore(() -> now[0]);

    private int allowedCount(int attempts, java.util.function.Supplier<LimiterResult> call) {
        int ok = 0;
        for (int i = 0; i < attempts; i++) {
            if (call.get().allowed()) {
                ok++;
            }
        }
        return ok;
    }

    @Test
    void tokenBucketAllowsBurstThenRefills() {
        assertEquals(5, allowedCount(7, () -> store.tokenBucket("tb", 5, 1, 1)));
        LimiterResult denied = store.tokenBucket("tb", 5, 1, 1);
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterMs() > 0);
        now[0] += 1200;
        assertTrue(store.tokenBucket("tb", 5, 1, 1).allowed());
    }

    @Test
    void fixedWindowResetsAtBoundary() {
        assertEquals(3, allowedCount(5, () -> store.fixedWindow("fw", 3, 1)));
        now[0] += 1100;
        assertTrue(store.fixedWindow("fw", 3, 1).allowed());
    }

    @Test
    void slidingWindowWeighsPreviousWindow() {
        now[0] = 10_000_000L; // aligned to a 10s window start
        assertEquals(10, allowedCount(12, () -> store.slidingWindow("sw", 10, 10)));
        now[0] += 15_000;     // halfway through the next window: previous counts 50%
        assertEquals(5, allowedCount(10, () -> store.slidingWindow("sw", 10, 10)));
        now[0] += 30_000;     // long idle: everything forgotten
        assertTrue(store.slidingWindow("sw", 10, 10).allowed());
    }

    @Test
    void leakyBucketDrainsAtConstantRate() {
        assertEquals(3, allowedCount(5, () -> store.leakyBucket("lb", 3, 2)));
        now[0] += 700;
        assertTrue(store.leakyBucket("lb", 3, 2).allowed());
    }

    @Test
    void concurrencyLimitsInFlightAndLeasesExpire() {
        assertTrue(store.acquire("cc", 2, 1, "L1").allowed());
        assertTrue(store.acquire("cc", 2, 1, "L2").allowed());
        assertFalse(store.acquire("cc", 2, 1, "L3").allowed());
        store.release("cc", "L1");
        assertTrue(store.acquire("cc", 2, 1, "L3").allowed());
        now[0] += 1200;
        assertTrue(store.acquire("cc", 2, 1, "L4").allowed());
    }

    @Test
    void evictionDropsIdleKeys() {
        store.tokenBucket("a", 5, 1, 1);
        store.acquire("b", 1, 1, "x");
        now[0] += 10_000_000L;
        store.evictExpired();
        assertEquals(0, store.size());
    }

    @Test
    void tokenBucketIsAtomicUnderConcurrency() throws Exception {
        InMemoryLimiterStore real = new InMemoryLimiterStore();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(200);
        for (int i = 0; i < 200; i++) {
            pool.submit(() -> {
                if (real.tokenBucket("par", 50, 0.0001, 1).allowed()) {
                    allowed.incrementAndGet();
                }
                done.countDown();
            });
        }
        done.await();
        pool.shutdown();
        assertEquals(50, allowed.get());
    }
}
