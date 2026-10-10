package com.example.ratelimiter.admin;

import com.example.ratelimiter.engine.RateLimitEngine;
import com.example.ratelimiter.model.ApiEndpoint;
import com.example.ratelimiter.model.Identity;
import com.example.ratelimiter.model.ResolvedRule;
import com.example.ratelimiter.model.Rule;
import com.example.ratelimiter.model.RuleOverride;
import com.example.ratelimiter.repo.EventRecord;
import com.example.ratelimiter.repo.PlanRecord;
import com.example.ratelimiter.repo.RuleCache;
import com.example.ratelimiter.repo.RuleRepository;
import com.example.ratelimiter.repo.SubjectPlanRecord;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Management API for rules, overrides, plans and plan assignments. Every write refreshes the local
 * rule cache immediately. Requires header X-Admin-Token.
 */
@RestController
@RequestMapping("/admin/rate-limits")
public class AdminController {

    private final RuleRepository repo;
    private final RuleCache cache;
    private final RateLimitEngine engine;

    public AdminController(RuleRepository repo, RuleCache cache, RateLimitEngine engine) {
        this.repo = repo;
        this.cache = cache;
        this.engine = engine;
    }

    // ---- APIs ----

    @GetMapping("/apis")
    public List<ApiEndpoint> apis() {
        return repo.findApis();
    }

    // ---- rules ----

    @GetMapping("/rules")
    public List<Rule> rules(@RequestParam(required = false) String apiId) {
        return repo.findRules(apiId);
    }

    @GetMapping("/rules/{ruleId}")
    public Rule rule(@PathVariable long ruleId) {
        return repo.findRule(ruleId).orElseThrow(() -> new NoSuchElementException("Rule " + ruleId + " not found"));
    }

    @PostMapping("/rules")
    public ResponseEntity<Rule> createRule(@Valid @RequestBody RuleRequest req,
                                           @RequestHeader(value = "X-Admin-User", defaultValue = "admin") String user) {
        long id = repo.insertRule(req.toInput(), user);
        cache.refresh();
        return ResponseEntity.status(HttpStatus.CREATED).body(rule(id));
    }

    @PutMapping("/rules/{ruleId}")
    public Rule updateRule(@PathVariable long ruleId, @Valid @RequestBody RuleRequest req,
                           @RequestHeader(value = "X-Admin-User", defaultValue = "admin") String user) {
        if (!repo.updateRule(ruleId, req.toInput(), user)) {
            throw new NoSuchElementException("Rule " + ruleId + " not found");
        }
        cache.refresh();
        return rule(ruleId);
    }

    @DeleteMapping("/rules/{ruleId}")
    public ResponseEntity<Void> deleteRule(@PathVariable long ruleId) {
        if (!repo.deleteRule(ruleId)) {
            throw new NoSuchElementException("Rule " + ruleId + " not found");
        }
        cache.refresh();
        return ResponseEntity.noContent().build();
    }

    // ---- overrides ----

    @GetMapping("/overrides")
    public List<RuleOverride> overrides(@RequestParam(required = false) Long ruleId) {
        return repo.findOverrides(ruleId);
    }

    @PostMapping("/overrides")
    public ResponseEntity<Map<String, Object>> createOverride(@Valid @RequestBody OverrideRequest req) {
        repo.findRule(req.ruleId())
                .orElseThrow(() -> new NoSuchElementException("Rule " + req.ruleId() + " not found"));
        long id = repo.upsertOverride(req.ruleId(), req.dimension(), req.subjectValue(), req.limitCount(),
                req.windowSeconds(), Boolean.TRUE.equals(req.blocked()), req.expiresAt(), req.reason());
        cache.refresh();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("overrideId", id);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @DeleteMapping("/overrides/{overrideId}")
    public ResponseEntity<Void> deleteOverride(@PathVariable long overrideId) {
        if (!repo.deleteOverride(overrideId)) {
            throw new NoSuchElementException("Override " + overrideId + " not found");
        }
        cache.refresh();
        return ResponseEntity.noContent().build();
    }

    // ---- plans ----

    @GetMapping("/plans")
    public List<PlanRecord> plans() {
        return repo.findPlans();
    }

    @PostMapping("/plans")
    public ResponseEntity<Map<String, Object>> createPlan(@Valid @RequestBody PlanRequest req) {
        long id = repo.insertPlan(req.name(), req.description());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("planId", id);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/subject-plans")
    public List<SubjectPlanRecord> subjectPlans() {
        return repo.findSubjectPlans();
    }

    /** Puts a department / employee / API key on a plan. Plan-specific rules then apply to it. */
    @PutMapping("/subject-plans")
    public List<SubjectPlanRecord> assignPlan(@Valid @RequestBody SubjectPlanRequest req) {
        repo.assignPlan(req.dimension(), req.subjectValue(), req.planId());
        cache.refresh();
        return repo.findSubjectPlans();
    }

    // ---- operations ----

    @GetMapping("/events")
    public List<EventRecord> events(@RequestParam(defaultValue = "50") int limit) {
        return repo.findEvents(Math.max(1, Math.min(limit, 500)));
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        cache.refresh();
        return Map.of("rules", cache.current().ruleCount(), "apis", cache.current().apiCount());
    }

    /** Shows which rules WOULD apply to a call. Consumes no quota. */
    @PostMapping("/simulate")
    public Map<String, Object> simulate(@Valid @RequestBody SimulateRequest req) {
        Identity id = new Identity(req.ip(), req.empId(), req.deptId(), req.apiKey());
        RateLimitEngine.Resolution r = engine.resolve(req.method() == null ? "GET" : req.method(), req.path(), id);
        List<Map<String, Object>> rules = new ArrayList<>();
        for (ResolvedRule rr : r.rules()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruleId", rr.rule().ruleId());
            m.put("dimension", rr.rule().dimension());
            m.put("subject", rr.subject());
            m.put("algorithm", rr.rule().algorithm());
            m.put("limit", rr.limitCount());
            m.put("windowSeconds", rr.windowSeconds());
            m.put("burstCapacity", rr.rule().burstCapacity());
            m.put("failMode", rr.rule().failMode());
            m.put("mode", rr.rule().mode());
            m.put("planSpecific", rr.rule().planId() != null);
            m.put("overridden", rr.overridden());
            m.put("blocked", rr.blocked());
            m.put("key", rr.key());
            rules.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiId", r.apiId());
        body.put("planId", r.planId());
        body.put("rules", rules);
        return body;
    }
}
