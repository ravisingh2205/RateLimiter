package com.example.ratelimiter.events;

import com.example.ratelimiter.config.RateLimitProperties;
import com.example.ratelimiter.engine.DecisionListener;
import com.example.ratelimiter.engine.Outcome;
import com.example.ratelimiter.model.Identity;
import com.example.ratelimiter.model.ResolvedRule;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Counts every decision in Micrometer (scrape /actuator/prometheus) and records non-allowed ones.
 * Metric tags are api, dimension, algorithm and outcome only. Never tag with ip/empId: that is
 * unbounded cardinality and will take your metrics backend down.
 */
@Component
public class ObservabilityListener implements DecisionListener {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityListener.class);

    private final MeterRegistry meters;
    private final EventWriter events;
    private final RateLimitProperties props;

    public ObservabilityListener(MeterRegistry meters, EventWriter events, RateLimitProperties props) {
        this.meters = meters;
        this.events = events;
        this.props = props;
    }

    @Override
    public void onOutcome(String apiId, ResolvedRule rule, Outcome outcome, Identity identity) {
        String dimension = rule == null ? "none" : rule.rule().dimension().name();
        String algorithm = rule == null ? "none" : rule.rule().algorithm().name();
        meters.counter("ratelimit.decisions",
                "api", apiId, "dimension", dimension, "algorithm", algorithm, "outcome", outcome.name()).increment();

        if (outcome == Outcome.ALLOWED || rule == null) {
            return;
        }
        if (outcome == Outcome.FAIL_OPEN) {
            log.warn("Limiter store failure on {} rule {} (failing open)", apiId, rule.rule().ruleId());
            return;
        }
        if (props.recordEvents()) {
            events.write(apiId, rule.rule().ruleId(), dimension, rule.subject(), outcome.name());
        }
    }
}
