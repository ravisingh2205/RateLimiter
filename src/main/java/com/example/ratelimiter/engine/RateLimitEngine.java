package com.example.ratelimiter.engine;

import com.example.ratelimiter.model.Algorithm;
import com.example.ratelimiter.model.FailMode;
import com.example.ratelimiter.model.Identity;
import com.example.ratelimiter.model.Mode;
import com.example.ratelimiter.model.ResolvedRule;
import com.example.ratelimiter.store.LimiterResult;
import com.example.ratelimiter.store.LimiterStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Decides whether a request may proceed. Pure Java: no Spring, no database. It reads rules from the
 * current Snapshot and state from the LimiterStore.
 *
 * Evaluation: every rule that applies must pass. Rules are evaluated one after another and
 * evaluation stops at the first denial. Tokens/quota consumed by rules that already passed are not
 * refunded (the usual, accepted trade-off; concurrency slots ARE released).
 */
public class RateLimitEngine {

    private final Supplier<Snapshot> snapshots;
    private final LimiterStore store;
    private final DecisionListener listener;
    private final Supplier<Instant> clock;

    public RateLimitEngine(Supplier<Snapshot> snapshots, LimiterStore store, DecisionListener listener,
                           Supplier<Instant> clock) {
        this.snapshots = snapshots;
        this.store = store;
        this.listener = listener;
        this.clock = clock;
    }

    /** What would apply to this call, without consuming anything (used by the admin "simulate" API). */
    public Resolution resolve(String method, String path, Identity id) {
        Snapshot snap = snapshots.get();
        String apiId = snap.resolveApi(method, path);
        Long planId = snap.planFor(id);
        return new Resolution(apiId, planId, snap.resolve(apiId, id, planId, clock.get()));
    }

    public record Resolution(String apiId, Long planId, List<ResolvedRule> rules) { }

    public Decision evaluate(String method, String path, Identity id) {
        Resolution res = resolve(method, path, id);
        String apiId = res.apiId();
        List<Decision.Lease> leases = new ArrayList<>();
        ResolvedRule headerRule = null;
        LimiterResult headerResult = null;

        for (ResolvedRule rr : res.rules()) {
            boolean shadow = rr.rule().mode() == Mode.SHADOW;

            if (rr.blocked()) {
                if (shadow) {
                    listener.onOutcome(apiId, rr, Outcome.SHADOW_DENIED, id);
                    continue;
                }
                release(leases);
                listener.onOutcome(apiId, rr, Outcome.BLOCKED, id);
                long retry = rr.overrideExpiresAt() != null
                        ? Math.max(1000, rr.overrideExpiresAt().toEpochMilli() - clock.get().toEpochMilli())
                        : 3_600_000L;
                return Decision.blocked(apiId, rr, new LimiterResult(false, 0, 0, retry, retry));
            }

            String leaseId = rr.rule().algorithm() == Algorithm.CONCURRENCY ? UUID.randomUUID().toString() : null;
            LimiterResult r;
            try {
                r = check(rr, leaseId);
            } catch (RuntimeException e) {
                if (rr.rule().failMode() == FailMode.FAIL_CLOSED) {
                    release(leases);
                    listener.onOutcome(apiId, rr, Outcome.FAIL_CLOSED, id);
                    return Decision.unavailable(apiId, rr);
                }
                listener.onOutcome(apiId, rr, Outcome.FAIL_OPEN, id);
                continue;
            }

            if (!r.allowed()) {
                if (shadow) {
                    listener.onOutcome(apiId, rr, Outcome.SHADOW_DENIED, id);
                    continue;
                }
                release(leases);
                listener.onOutcome(apiId, rr, Outcome.DENIED, id);
                return Decision.denied(apiId, rr, r);
            }

            if (leaseId != null) {
                leases.add(new Decision.Lease(rr.key(), leaseId));
            } else if (headerResult == null || tighter(r, headerResult)) {
                headerRule = rr;
                headerResult = r;
            }
        }

        listener.onOutcome(apiId, null, Outcome.ALLOWED, id);
        return Decision.allowed(apiId, headerRule, headerResult, leases);
    }

    /** Call when the request has finished (success or error) to free concurrency slots. */
    public void release(Decision decision) {
        release(decision.leases());
    }

    private void release(List<Decision.Lease> leases) {
        for (Decision.Lease l : leases) {
            try {
                store.release(l.key(), l.leaseId());
            } catch (RuntimeException ignored) {
                // the lease has a TTL, so a failed release heals itself
            }
        }
        leases.clear();
    }

    private LimiterResult check(ResolvedRule rr, String leaseId) {
        switch (rr.rule().algorithm()) {
            case TOKEN_BUCKET:
                return store.tokenBucket(rr.key(), rr.capacity(), rr.ratePerSecond(), 1);
            case FIXED_WINDOW:
                return store.fixedWindow(rr.key(), rr.limitCount(), rr.windowSeconds());
            case SLIDING_WINDOW:
                return store.slidingWindow(rr.key(), rr.limitCount(), rr.windowSeconds());
            case LEAKY_BUCKET:
                return store.leakyBucket(rr.key(), rr.capacity(), rr.ratePerSecond());
            case CONCURRENCY:
                return store.acquire(rr.key(), rr.limitCount(), rr.windowSeconds(), leaseId);
            default:
                throw new IllegalStateException("Unsupported algorithm " + rr.rule().algorithm());
        }
    }

    /** True when a leaves less headroom (as a share of its limit) than b. */
    private static boolean tighter(LimiterResult a, LimiterResult b) {
        double ra = a.limit() == 0 ? 0 : (double) a.remaining() / a.limit();
        double rb = b.limit() == 0 ? 0 : (double) b.remaining() / b.limit();
        return ra < rb;
    }
}
