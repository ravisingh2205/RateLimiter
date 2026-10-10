package com.example.ratelimiter.engine;

import com.example.ratelimiter.model.Identity;
import com.example.ratelimiter.model.ResolvedRule;

/**
 * Callback for observability. The engine stays free of metrics/DB code; the Spring app plugs in a
 * listener that updates Micrometer counters and (asynchronously) records events.
 * For ALLOWED the rule is null (one call per request).
 */
public interface DecisionListener {

    void onOutcome(String apiId, ResolvedRule rule, Outcome outcome, Identity identity);

    DecisionListener NOOP = (apiId, rule, outcome, identity) -> { };
}
