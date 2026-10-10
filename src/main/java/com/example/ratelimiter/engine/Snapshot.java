package com.example.ratelimiter.engine;

import com.example.ratelimiter.model.Algorithm;
import com.example.ratelimiter.model.ApiEndpoint;
import com.example.ratelimiter.model.Dimension;
import com.example.ratelimiter.model.Identity;
import com.example.ratelimiter.model.RuleOverride;
import com.example.ratelimiter.model.ResolvedRule;
import com.example.ratelimiter.model.Rule;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, in-memory copy of the configuration (APIs, rules, overrides, plan assignments).
 * The hot path reads ONLY this object, never PostgreSQL. RuleCache swaps in a fresh one on refresh.
 */
public final class Snapshot {

    public static final String UNKNOWN_API = "UNKNOWN";
    public static final String ALL_APIS = "*";

    private final List<ApiEndpoint> apis;
    private final List<Rule> rules;                 // enabled rules only
    private final Map<String, RuleOverride> overrides;  // key: ruleId|DIMENSION|subject
    private final Map<String, Long> subjectPlans;   // key: DIMENSION|subject -> planId

    public Snapshot(List<ApiEndpoint> apis, List<Rule> rules, Map<String, RuleOverride> overrides,
                    Map<String, Long> subjectPlans) {
        this.apis = List.copyOf(apis);
        this.rules = List.copyOf(rules);
        this.overrides = Map.copyOf(overrides);
        this.subjectPlans = Map.copyOf(subjectPlans);
    }

    public static Snapshot empty() {
        return new Snapshot(List.of(), List.of(), Map.of(), Map.of());
    }

    public static String overrideKey(long ruleId, Dimension d, String subject) {
        return ruleId + "|" + d + "|" + subject;
    }

    public static String planKey(Dimension d, String subject) {
        return d + "|" + subject;
    }

    /** Maps method + path to an API id from the api_endpoint catalogue; UNKNOWN when nothing matches. */
    public String resolveApi(String method, String path) {
        for (ApiEndpoint a : apis) {
            boolean methodOk = "*".equals(a.httpMethod()) || a.httpMethod().equalsIgnoreCase(method);
            if (methodOk && PathMatcher.matches(a.pathPattern(), path)) {
                return a.apiId();
            }
        }
        return UNKNOWN_API;
    }

    /** Plan of the caller: department first, then employee, then API key. Null = default plan. */
    public Long planFor(Identity id) {
        for (Dimension d : new Dimension[] {Dimension.DEPT, Dimension.EMP, Dimension.API_KEY}) {
            String v = id.valueFor(d);
            if (v != null) {
                Long p = subjectPlans.get(planKey(d, v));
                if (p != null) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * Rules that apply to this call, in evaluation order.
     * - rules for the API itself and rules for "*" (all APIs) both apply
     * - per (api, dimension, algorithm) a plan-specific rule beats the default (plan_id NULL) rule
     * - rules whose dimension the caller has no value for are skipped (anonymous callers have no empId)
     * - overrides are applied; concurrency rules run last so a slot is only taken if everything else passed
     */
    public List<ResolvedRule> resolve(String apiId, Identity id, Long planId, Instant now) {
        Map<String, Rule> chosen = new LinkedHashMap<>();
        for (Rule r : rules) {
            if (!r.apiId().equals(apiId) && !r.apiId().equals(ALL_APIS)) {
                continue;
            }
            if (r.planId() != null && !r.planId().equals(planId)) {
                continue;
            }
            String group = r.apiId() + "|" + r.dimension() + "|" + r.algorithm();
            Rule current = chosen.get(group);
            if (current == null || (current.planId() == null && r.planId() != null)) {
                chosen.put(group, r);
            }
        }

        List<ResolvedRule> out = new ArrayList<>();
        for (Rule r : chosen.values()) {
            String subject = id.valueFor(r.dimension());
            if (subject == null) {
                continue;
            }
            RuleOverride o = overrides.get(overrideKey(r.ruleId(), r.dimension(), subject));
            if (o != null && o.activeAt(now)) {
                int limit = o.limitCount() != null ? o.limitCount() : r.limitCount();
                int window = o.windowSeconds() != null ? o.windowSeconds() : r.windowSeconds();
                out.add(new ResolvedRule(r, subject, limit, window, o.blocked(), true, o.reason(), o.expiresAt()));
            } else {
                out.add(new ResolvedRule(r, subject, r.limitCount(), r.windowSeconds(), false, false, null, null));
            }
        }
        out.sort(Comparator
                .comparing((ResolvedRule rr) -> rr.rule().algorithm() == Algorithm.CONCURRENCY)
                .thenComparingInt(rr -> rr.rule().priority())
                .thenComparingLong(rr -> rr.rule().ruleId()));
        return out;
    }

    public int ruleCount() {
        return rules.size();
    }

    public int apiCount() {
        return apis.size();
    }
}
