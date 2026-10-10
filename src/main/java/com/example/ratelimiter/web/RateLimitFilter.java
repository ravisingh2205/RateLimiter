package com.example.ratelimiter.web;

import com.example.ratelimiter.engine.Decision;
import com.example.ratelimiter.engine.RateLimitEngine;
import com.example.ratelimiter.model.Identity;
import com.example.ratelimiter.model.ResolvedRule;
import com.example.ratelimiter.store.LimiterResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies rate limiting to EVERY /api/** request before it reaches a controller. Adding a new API
 * needs no code here: add a row to api_endpoint and rows to rate_limit_rule (or rely on the
 * wildcard "*" rules, which cover any path under /api/).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitEngine engine;
    private final IdentityResolver identities;
    private final  mapper;

    public RateLimitFilter(RateLimitEngine engine, IdentityResolver identities, ObjectMapper mapper) {
        this.engine = engine;
        this.identities = identities;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Identity id = identities.from(req);
        Decision d = engine.evaluate(req.getMethod(), req.getRequestURI(), id);

        switch (d.status()) {
            case ALLOWED:
                break;
            case UNAVAILABLE:
                reject(req, res, d, 503, "RATE_LIMITER_UNAVAILABLE",
                        "Rate limiter is unavailable and this API fails closed", 1);
                return;
            case BLOCKED:
                reject(req, res, d, 429, "BLOCKED", blockedMessage(d), retrySeconds(d.result()));
                return;
            case DENIED:
            default:
                reject(req, res, d, 429, "RATE_LIMIT_EXCEEDED", deniedMessage(d), retrySeconds(d.result()));
                return;
        }

        if (d.result() != null) {
            res.setHeader("RateLimit-Limit", String.valueOf(d.result().limit()));
            res.setHeader("RateLimit-Remaining", String.valueOf(d.result().remaining()));
            res.setHeader("RateLimit-Reset", String.valueOf(ceilSeconds(d.result().resetMs())));
            if (d.headerRule() != null) {
                res.setHeader("RateLimit-Policy", d.headerRule().limitCount() + ";w=" + d.headerRule().windowSeconds());
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            engine.release(d); // frees concurrency slots even if the controller threw
        }
    }

    private void reject(HttpServletRequest req, HttpServletResponse res, Decision d, int status, String code,
                        String message, long retryAfterSeconds) throws IOException {
        ResolvedRule rr = d.rule();
        LimiterResult r = d.result();
        res.setStatus(status);
        res.setContentType("application/json");
        res.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        if (r != null) {
            res.setHeader("RateLimit-Limit", String.valueOf(r.limit()));
            res.setHeader("RateLimit-Remaining", "0");
            res.setHeader("RateLimit-Reset", String.valueOf(ceilSeconds(r.resetMs())));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        body.put("apiId", d.apiId());
        if (rr != null) {
            body.put("dimension", rr.rule().dimension().name());
            body.put("algorithm", rr.rule().algorithm().name());
            body.put("limit", rr.limitCount());
            body.put("windowSeconds", rr.windowSeconds());
            body.put("ruleId", rr.rule().ruleId());
        }
        body.put("retryAfterSeconds", retryAfterSeconds);
        String traceId = req.getHeader("X-Request-Id");
        body.put("traceId", traceId != null ? traceId : UUID.randomUUID().toString());
        mapper.writeValue(res.getOutputStream(), body);
    }

    private static String deniedMessage(Decision d) {
        ResolvedRule rr = d.rule();
        return "Too many requests for " + rr.rule().dimension() + " '" + rr.subject() + "' on " + d.apiId()
                + " (" + rr.rule().algorithm() + ", limit " + rr.limitCount() + " per " + rr.windowSeconds() + "s)";
    }

    private static String blockedMessage(Decision d) {
        ResolvedRule rr = d.rule();
        String reason = rr.overrideReason() == null ? "" : ": " + rr.overrideReason();
        return rr.rule().dimension() + " '" + rr.subject() + "' is blocked on " + d.apiId() + reason;
    }

    private static long retrySeconds(LimiterResult r) {
        return Math.max(1, ceilSeconds(r == null ? 1000 : r.retryAfterMs()));
    }

    private static long ceilSeconds(long ms) {
        return (ms + 999) / 1000;
    }
}
