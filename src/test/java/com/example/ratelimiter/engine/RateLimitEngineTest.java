package com.example.ratelimiter.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ratelimiter.model.Algorithm;
import com.example.ratelimiter.model.ApiEndpoint;
import com.example.ratelimiter.model.Dimension;
import com.example.ratelimiter.model.FailMode;
import com.example.ratelimiter.model.Identity;
import com.example.ratelimiter.model.Mode;
import com.example.ratelimiter.model.RuleOverride;
import com.example.ratelimiter.model.Rule;
import com.example.ratelimiter.store.InMemoryLimiterStore;
import com.example.ratelimiter.store.LimiterResult;
import com.example.ratelimiter.store.LimiterStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RateLimitEngineTest {

    private static final long FREE = 1L;
    private static final long PREMIUM = 2L;

    private final List<Rule> rules = new ArrayList<>();
    private final Map<String, RuleOverride> overrides = new HashMap<>();
    private final Map<String, Long> plans = new HashMap<>();
    private final List<ApiEndpoint> apis = List.of(
            new ApiEndpoint("employees.list", "GET", "/api/employees", ""),
            new ApiEndpoint("payments.create", "POST", "/api/payments", ""));
    private final List<String> outcomes = new ArrayList<>();
    private long nextId = 1;

    private Rule add(String api, Long plan, Dimension dim, Algorithm algo, int limit, int window, Integer burst,
                     FailMode fail, Mode mode) {
        Rule r = new Rule(nextId++, api, plan, dim, algo, limit, window, burst, 100, fail, mode, true, 1,
                "test", Instant.now());
        rules.add(r);
        return r;
    }

    private Rule bucket(String api, Dimension dim, int capacity) {
        // refill is negligible so tests are deterministic
        return add(api, null, dim, Algorithm.TOKEN_BUCKET, 1, 100_000, capacity, FailMode.FAIL_OPEN, Mode.ENFORCE);
    }

    private RateLimitEngine engine(LimiterStore store) {
        return new RateLimitEngine(
                () -> new Snapshot(apis, rules, overrides, plans),
                store,
                (api, rule, outcome, id) -> outcomes.add(outcome.name()),
                Instant::now);
    }

    private static Identity who(String emp, String dept) {
        return new Identity("1.1.1.1", emp, dept, null);
    }

    @Test
    void tokenBucketPerEmployeeDeniesAfterBurst() {
        bucket("employees.list", Dimension.EMP, 3);
        RateLimitEngine e = engine(new InMemoryLimiterStore());
        for (int i = 0; i < 3; i++) {
            assertTrue(e.evaluate("GET", "/api/employees", who("E1", "D1")).isAllowed());
        }
        Decision d = e.evaluate("GET", "/api/employees", who("E1", "D1"));
        assertEquals(Decision.Status.DENIED, d.status());
        assertEquals(Dimension.EMP, d.rule().rule().dimension());
        assertTrue(d.result().retryAfterMs() > 0);
        // another employee has their own bucket
        assertTrue(e.evaluate("GET", "/api/employees", who("E2", "D1")).isAllowed());
    }

    @Test
    void allRulesMustPassAndHeadersReflectTheTightestRule() {
        bucket("employees.list", Dimension.EMP, 10);
        bucket("employees.list", Dimension.DEPT, 4);
        RateLimitEngine e = engine(new InMemoryLimiterStore());
        Decision first = e.evaluate("GET", "/api/employees", who("E1", "D1"));
        assertTrue(first.isAllowed());
        assertEquals(Dimension.DEPT, first.headerRule().rule().dimension()); // 3/4 left vs 9/10 left
        for (int i = 0; i < 3; i++) {
            assertTrue(e.evaluate("GET", "/api/employees", who("E" + (i + 2), "D1")).isAllowed());
        }
        Decision d = e.evaluate("GET", "/api/employees", who("E9", "D1"));
        assertEquals(Decision.Status.DENIED, d.status());
        assertEquals(Dimension.DEPT, d.rule().rule().dimension());
    }

    @Test
    void planSpecificRuleBeatsDefault() {
        bucket("employees.list", Dimension.DEPT, 2);
        add("employees.list", PREMIUM, Dimension.DEPT, Algorithm.TOKEN_BUCKET, 1, 100_000, 100, FailMode.FAIL_OPEN,
                Mode.ENFORCE);
        plans.put(Snapshot.planKey(Dimension.DEPT, "D7"), PREMIUM);
        RateLimitEngine e = engine(new InMemoryLimiterStore());
        for (int i = 0; i < 10; i++) {
            assertTrue(e.evaluate("GET", "/api/employees", who("E1", "D7")).isAllowed());
        }
        assertTrue(e.evaluate("GET", "/api/employees", who("E1", "D1")).isAllowed());
        assertTrue(e.evaluate("GET", "/api/employees", who("E1", "D1")).isAllowed());
        assertFalse(e.evaluate("GET", "/api/employees", who("E1", "D1")).isAllowed());
    }

    @Test
    void planRuleForOtherPlanDoesNotApply() {
        add("employees.list", PREMIUM, Dimension.DEPT, Algorithm.TOKEN_BUCKET, 1, 100_000, 1, FailMode.FAIL_OPEN,
                Mode.ENFORCE);
        RateLimitEngine e = engine(new InMemoryLimiterStore());
        for (int i = 0; i < 5; i++) {
            assertTrue(e.evaluate("GET", "/api/employees", who("E1", "D1")).isAllowed());
        }
    }

    @Test
    void wildcardRuleCoversEveryApiIncludingUnknownOnes() {
        bucket("*", Dimension.IP, 2);
        RateLimitEngine e = engine(new InMemoryLimiterStore());
        assertTrue(e.evaluate("GET", "/api/whatever", who(null, null)).isAllowed());
        assertTrue(e.evaluate("GET", "/api/employees", who(null, null)).isAllowed());
        Decision d = e.evaluate("POST", "/api/payments", who(null, null));
        assertEquals(Decision.Status.DENIED, d.status());
        assertEquals("payments.create", d.apiId());
    }

    @Test
    void ruleIsSkippedWhenCallerHasNoValueForItsDimension() {
        bucket("employees.list", Dimension.EMP, 1);
        RateLimitEngine e = engine(new InMemoryLimiterStore());
        for (int i = 0; i < 5; i++) {
            assertTrue(e.evaluate("GET", "/api/employees", who(null, "D1")).isAllowed());
        }
    }

    @Test
    void blockOverrideDeniesAndExpiredOverrideIsIgnored() {
        Rule r = bucket("employees.list", Dimension.EMP, 100);
        overrides.put(Snapshot.overrideKey(r.ruleId(), Dimension.EMP, "BAD"),
                new RuleOverride(1, r.ruleId(), Dimension.EMP, "BAD", null, null, true, null, "abuse"));
        overrides.put(Snapshot.overrideKey(r.ruleId(), Dimension.EMP, "OLD"),
                new RuleOverride(2, r.ruleId(), Dimension.EMP, "OLD", null, null, true,
                        Instant.now().minusSeconds(60), "expired"));
        RateLimitEngine e = engine(new InMemoryLimiterStore());
        Decision d = e.evaluate("GET", "/api/employees", who("BAD", "D1"));
        assertEquals(Decision.Status.BLOCKED, d.status());
        assertTrue(outcomes.contains("BLOCKED"));
        assertTrue(e.evaluate("GET", "/api/employees", who("OLD", "D1")).isAllowed());
    }

    @Test
    void limitOverrideRaisesTheCapForOneSubject() {
        Rule r = add("employees.list", null, Dimension.EMP, Algorithm.FIXED_WINDOW, 1, 60, null, FailMode.FAIL_OPEN,
                Mode.ENFORCE);
        overrides.put(Snapshot.overrideKey(r.ruleId(), Dimension.EMP, "VIP"),
                new RuleOverride(1, r.ruleId(), Dimension.EMP, "VIP", 5, 60, false, null, "vip"));
        RateLimitEngine e = engine(new InMemoryLimiterStore());
        assertTrue(e.evaluate("GET", "/api/employees", who("REG", "D1")).isAllowed());
        assertFalse(e.evaluate("GET", "/api/employees", who("REG", "D1")).isAllowed());
        for (int i = 0; i < 5; i++) {
            assertTrue(e.evaluate("GET", "/api/employees", who("VIP", "D1")).isAllowed());
        }
        assertFalse(e.evaluate("GET", "/api/employees", who("VIP", "D1")).isAllowed());
    }

    @Test
    void shadowModeRecordsButNeverBlocks() {
        add("employees.list", null, Dimension.EMP, Algorithm.TOKEN_BUCKET, 1, 100_000, 1, FailMode.FAIL_OPEN,
                Mode.SHADOW);
        RateLimitEngine e = engine(new InMemoryLimiterStore());
        for (int i = 0; i < 4; i++) {
            assertTrue(e.evaluate("GET", "/api/employees", who("E1", "D1")).isAllowed());
        }
        assertTrue(outcomes.contains("SHADOW_DENIED"));
        assertFalse(outcomes.contains("DENIED"));
    }

    private static class BrokenStore extends InMemoryLimiterStore {
        @Override
        public LimiterResult tokenBucket(String key, long capacity, double refill, int cost) {
            throw new IllegalStateException("redis down");
        }
    }

    @Test
    void storeFailureFollowsFailMode() {
        add("employees.list", null, Dimension.EMP, Algorithm.TOKEN_BUCKET, 1, 60, 1, FailMode.FAIL_OPEN,
                Mode.ENFORCE);
        add("payments.create", null, Dimension.EMP, Algorithm.TOKEN_BUCKET, 1, 60, 1, FailMode.FAIL_CLOSED,
                Mode.ENFORCE);
        RateLimitEngine e = engine(new BrokenStore());
        assertTrue(e.evaluate("GET", "/api/employees", who("E1", "D1")).isAllowed());
        assertTrue(outcomes.contains("FAIL_OPEN"));
        Decision d = e.evaluate("POST", "/api/payments", who("E1", "D1"));
        assertEquals(Decision.Status.UNAVAILABLE, d.status());
        assertTrue(outcomes.contains("FAIL_CLOSED"));
    }

    @Test
    void concurrencySlotsAreHeldUntilReleased() {
        add("payments.create", null, Dimension.EMP, Algorithm.CONCURRENCY, 2, 30, null, FailMode.FAIL_CLOSED,
                Mode.ENFORCE);
        RateLimitEngine e = engine(new InMemoryLimiterStore());
        Decision a = e.evaluate("POST", "/api/payments", who("E1", "D1"));
        Decision b = e.evaluate("POST", "/api/payments", who("E1", "D1"));
        assertTrue(a.isAllowed() && b.isAllowed());
        assertEquals(1, a.leases().size());
        assertEquals(Decision.Status.DENIED, e.evaluate("POST", "/api/payments", who("E1", "D1")).status());
        e.release(a);
        assertTrue(e.evaluate("POST", "/api/payments", who("E1", "D1")).isAllowed());
    }

    @Test
    void slotTakenByAnEarlierRuleIsReleasedWhenALaterRuleDenies() {
        Rule emp = add("payments.create", null, Dimension.EMP, Algorithm.CONCURRENCY, 1, 30, null,
                FailMode.FAIL_CLOSED, Mode.ENFORCE);
        Rule dept = add("payments.create", null, Dimension.DEPT, Algorithm.CONCURRENCY, 1, 30, null,
                FailMode.FAIL_CLOSED, Mode.ENFORCE);
        assertNotNull(emp);
        assertNotNull(dept);
        RateLimitEngine e = engine(new InMemoryLimiterStore());
        Decision a = e.evaluate("POST", "/api/payments", who("E1", "D1"));
        assertTrue(a.isAllowed());
        // E2 gets an EMP slot, then DEPT D1 is full -> denied, and E2's slot must not leak
        assertFalse(e.evaluate("POST", "/api/payments", who("E2", "D1")).isAllowed());
        e.release(a);
        assertTrue(e.evaluate("POST", "/api/payments", who("E2", "D2")).isAllowed());
    }

    @Test
    void rateRulesRunBeforeConcurrencyRules() {
        add("payments.create", null, Dimension.EMP, Algorithm.CONCURRENCY, 5, 30, null, FailMode.FAIL_CLOSED,
                Mode.ENFORCE);
        bucket("payments.create", Dimension.EMP, 1);
        RateLimitEngine e = engine(new InMemoryLimiterStore());
        Snapshot s = new Snapshot(apis, rules, overrides, plans);
        List<com.example.ratelimiter.model.ResolvedRule> order =
                s.resolve("payments.create", who("E1", "D1"), null, Instant.now());
        assertEquals(Algorithm.TOKEN_BUCKET, order.get(0).rule().algorithm());
        assertEquals(Algorithm.CONCURRENCY, order.get(1).rule().algorithm());
        assertNotNull(e);
    }
}
