package com.example.ratelimiter.engine;

import com.example.ratelimiter.model.ResolvedRule;
import com.example.ratelimiter.store.LimiterResult;
import java.util.List;

/** What the filter needs to know after the engine has looked at a request. */
public final class Decision {

    public enum Status { ALLOWED, DENIED, BLOCKED, UNAVAILABLE }

    /** An in-flight slot taken by a CONCURRENCY rule; must be released when the request finishes. */
    public record Lease(String key, String leaseId) { }

    private final Status status;
    private final String apiId;
    private final ResolvedRule rule;       // the rule that denied / blocked / failed (null when allowed)
    private final LimiterResult result;    // result of that rule, or the most constraining allowed rule
    private final ResolvedRule headerRule; // rule that produced 'result' when allowed
    private final List<Lease> leases;

    private Decision(Status status, String apiId, ResolvedRule rule, LimiterResult result,
                     ResolvedRule headerRule, List<Lease> leases) {
        this.status = status;
        this.apiId = apiId;
        this.rule = rule;
        this.result = result;
        this.headerRule = headerRule;
        this.leases = leases;
    }

    public static Decision allowed(String apiId, ResolvedRule headerRule, LimiterResult headerResult,
                                   List<Lease> leases) {
        return new Decision(Status.ALLOWED, apiId, null, headerResult, headerRule, leases);
    }

    public static Decision denied(String apiId, ResolvedRule rule, LimiterResult result) {
        return new Decision(Status.DENIED, apiId, rule, result, null, List.of());
    }

    public static Decision blocked(String apiId, ResolvedRule rule, LimiterResult result) {
        return new Decision(Status.BLOCKED, apiId, rule, result, null, List.of());
    }

    public static Decision unavailable(String apiId, ResolvedRule rule) {
        return new Decision(Status.UNAVAILABLE, apiId, rule, null, null, List.of());
    }

    public Status status() { return status; }
    public boolean isAllowed() { return status == Status.ALLOWED; }
    public String apiId() { return apiId; }
    public ResolvedRule rule() { return rule; }
    public LimiterResult result() { return result; }
    public ResolvedRule headerRule() { return headerRule; }
    public List<Lease> leases() { return leases; }
}
