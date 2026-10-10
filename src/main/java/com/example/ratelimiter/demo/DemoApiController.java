package com.example.ratelimiter.demo;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Five sample business APIs. They contain NO rate limiting code: the RateLimitFilter protects them
 * from the outside, driven by the rules in the database. The X-Emp-Id / X-Dept-Id / X-Api-Key
 * header parameters exist so Swagger UI shows them; the filter reads them, not these methods.
 *
 *  1. POST /api/auth/login             sliding window per IP + username, fail-closed
 *  2. GET  /api/employees              token bucket per employee + department (plan-aware)
 *  3. POST /api/payments               token bucket + concurrency limit, fail-closed, idempotent
 *  4. POST /api/reports/export         concurrency limit + daily quota per department
 *  5. POST /api/notifications/send     leaky bucket per department + global
 */
@RestController
@RequestMapping("/api")
public class DemoApiController {

    private final Map<String, Map<String, Object>> payments = new ConcurrentHashMap<>();

    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-Emp-Id", required = false) String empId) {
        // demo credential check: any user, password "secret"
        if ("secret".equals(body.get("password"))) {
            return ResponseEntity.ok(Map.of("token", "demo-" + UUID.randomUUID(), "user", body.getOrDefault("username", "")));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "INVALID_CREDENTIALS"));
    }

    @GetMapping("/employees")
    public List<Map<String, Object>> employees(
            @RequestHeader(value = "X-Emp-Id", required = false) String empId,
            @RequestHeader(value = "X-Dept-Id", required = false) String deptId) {
        return List.of(
                Map.of("id", "E1", "name", "Asha Rao", "dept", "D1"),
                Map.of("id", "E2", "name", "Vikram Shah", "dept", "D1"),
                Map.of("id", "E3", "name", "Meera Iyer", "dept", "D7"));
    }

    @PostMapping("/payments")
    public ResponseEntity<Map<String, Object>> createPayment(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Emp-Id", required = false) String empId,
            @RequestHeader(value = "X-Dept-Id", required = false) String deptId) {
        // A retry after a timeout or a 429 must not charge twice: replay the stored response.
        if (idempotencyKey != null && payments.containsKey(idempotencyKey)) {
            Map<String, Object> replay = new LinkedHashMap<>(payments.get(idempotencyKey));
            replay.put("replayed", true);
            return ResponseEntity.ok(replay);
        }
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("paymentId", UUID.randomUUID().toString());
        payment.put("amount", body.get("amount"));
        payment.put("currency", body.getOrDefault("currency", "INR"));
        payment.put("status", "ACCEPTED");
        payment.put("createdAt", Instant.now().toString());
        if (idempotencyKey != null) {
            payments.put(idempotencyKey, payment);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(payment);
    }

    @PostMapping("/reports/export")
    public Map<String, Object> exportReport(
            @RequestParam(defaultValue = "2000") long durationMs,
            @RequestHeader(value = "X-Dept-Id", required = false) String deptId,
            @RequestHeader(value = "X-Emp-Id", required = false) String empId) throws InterruptedException {
        // simulates an expensive query so concurrent calls overlap
        Thread.sleep(Math.max(0, Math.min(durationMs, 10_000)));
        return Map.of("report", "employees-" + System.currentTimeMillis() + ".csv", "tookMs", durationMs);
    }

    @PostMapping("/notifications/send")
    public ResponseEntity<Map<String, Object>> sendNotification(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Dept-Id", required = false) String deptId) {
        return ResponseEntity.accepted().body(Map.of("notificationId", UUID.randomUUID().toString(),
                "channel", body.getOrDefault("channel", "EMAIL"), "status", "QUEUED"));
    }
}
