package com.example.ratelimiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end: real filter, real rules from the seeded H2 database, in-memory limiter store.
 * Every test uses its own IP / employee / department values so tests do not share quota.
 */
@SpringBootTest(properties = {
        "ratelimit.store=memory",
        "ratelimit.trust-forwarded-for=true",
        "ratelimit.admin-token=test-token"})
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String ip, String emp,
                                                    String dept) {
        b.header("X-Forwarded-For", ip);
        if (emp != null) {
            b.header("X-Emp-Id", emp);
        }
        if (dept != null) {
            b.header("X-Dept-Id", dept);
        }
        return b;
    }

    private MvcResult getEmployees(String ip, String emp, String dept) throws Exception {
        return mvc.perform(as(get("/api/employees"), ip, emp, dept)).andReturn();
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder b) {
        return b.header("X-Admin-Token", "test-token");
    }

    @Test
    void employeesTokenBucketPerEmployeeThenReturns429WithRetryAfter() throws Exception {
        int ok = 0;
        int denied = 0;
        MvcResult lastDenied = null;
        for (int i = 0; i < 25; i++) {
            MvcResult r = getEmployees("10.1.0.1", "it-emp-1", "it-d-1");
            if (r.getResponse().getStatus() == 200) {
                ok++;
            } else if (r.getResponse().getStatus() == 429) {
                denied++;
                lastDenied = r;
            }
        }
        assertTrue(ok >= 20 && ok <= 22, "burst of about 20 expected, was " + ok);
        assertTrue(denied >= 3);
        assertNotNull(lastDenied.getResponse().getHeader("Retry-After"));
        JsonNode body = mapper.readTree(lastDenied.getResponse().getContentAsString());
        assertEquals("RATE_LIMIT_EXCEEDED", body.get("error").asText());
        assertEquals("EMP", body.get("dimension").asText());
        assertEquals("employees.list", body.get("apiId").asText());
        // a different employee is unaffected
        assertEquals(200, getEmployees("10.1.0.1", "it-emp-2", "it-d-1").getResponse().getStatus());
    }

    @Test
    void successfulResponsesCarryRateLimitHeaders() throws Exception {
        mvc.perform(as(get("/api/employees"), "10.1.0.9", "it-hdr-1", "it-d-9"))
                .andExpect(status().isOk())
                .andExpect(header().exists("RateLimit-Limit"))
                .andExpect(header().exists("RateLimit-Remaining"))
                .andExpect(header().exists("RateLimit-Reset"));
    }

    @Test
    void loginBruteForceIsBlockedAfterFiveAttemptsPerUsername() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(as(post("/api/auth/login"), "10.1.0.2", "it-user-1", null)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"it-user-1\",\"password\":\"wrong\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(as(post("/api/auth/login"), "10.1.0.2", "it-user-1", null)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"it-user-1\",\"password\":\"secret\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.algorithm").value("SLIDING_WINDOW"));
    }

    @Test
    void paymentsAllowBurstOfFivePerEmployee() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(as(post("/api/payments"), "10.1.0.3", "it-pay-1", "it-d-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":10,\"currency\":\"INR\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(as(post("/api/payments"), "10.1.0.3", "it-pay-1", "it-d-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10,\"currency\":\"INR\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void paymentsIdempotencyKeyReplaysInsteadOfCreatingTwice() throws Exception {
        String key = "idem-" + System.nanoTime();
        String first = mvc.perform(as(post("/api/payments"), "10.1.0.10", "it-pay-2", "it-d-2")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(as(post("/api/payments"), "10.1.0.10", "it-pay-2", "it-d-2")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true)).andReturn().getResponse().getContentAsString();
        assertEquals(mapper.readTree(first).get("paymentId").asText(), mapper.readTree(second).get("paymentId").asText());
    }

    @Test
    void reportExportAllowsTwoConcurrentPerDepartmentAndReleasesSlots() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            results.add(pool.submit(() -> mvc.perform(
                            as(post("/api/reports/export").param("durationMs", "1500"), "10.1.0.4", "it-rep-e", "it-rep-1"))
                    .andReturn().getResponse().getStatus()));
        }
        int ok = 0;
        int denied = 0;
        for (Future<Integer> f : results) {
            int s = f.get();
            if (s == 200) {
                ok++;
            } else if (s == 429) {
                denied++;
            }
        }
        pool.shutdown();
        assertEquals(2, ok);
        assertEquals(1, denied);
        // slots were released when the calls finished
        mvc.perform(as(post("/api/reports/export").param("durationMs", "10"), "10.1.0.4", "it-rep-e", "it-rep-1"))
                .andExpect(status().isOk());
    }

    @Test
    void premiumDepartmentGetsHigherLimitThanFreeDepartment() throws Exception {
        int freeDenied = 0;
        int premiumDenied = 0;
        for (int i = 0; i < 40; i++) {
            String emp = "pl-e" + (i % 5);
            if (getEmployees("10.1.0.5", emp, "D1").getResponse().getStatus() == 429) {
                freeDenied++;
            }
            if (getEmployees("10.1.0.6", emp + "-p", "D7").getResponse().getStatus() == 429) {
                premiumDenied++;
            }
        }
        assertTrue(freeDenied >= 5, "FREE plan (burst 30) should deny some of 40, denied " + freeDenied);
        assertEquals(0, premiumDenied);
    }

    @Test
    void notificationsLeakyBucketCapsBurstPerDepartment() throws Exception {
        int accepted = 0;
        int denied = 0;
        for (int i = 0; i < 30; i++) {
            int s = mvc.perform(as(post("/api/notifications/send"), "10.1.0.11", "it-n-1", "it-n-dept")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"channel\":\"EMAIL\"}"))
                    .andReturn().getResponse().getStatus();
            if (s == 202) {
                accepted++;
            } else if (s == 429) {
                denied++;
            }
        }
        assertTrue(accepted >= 20 && accepted <= 24, "queue depth 20 plus a little drain, was " + accepted);
        assertTrue(denied >= 6);
    }

    @Test
    void unknownApiStillGetsTheWildcardIpRule() throws Exception {
        mvc.perform(as(get("/api/does-not-exist"), "10.1.0.8", null, null))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("RateLimit-Limit"));
    }

    @Test
    void adminApiRequiresToken() throws Exception {
        mvc.perform(get("/admin/rate-limits/rules")).andExpect(status().isUnauthorized());
        mvc.perform(admin(get("/admin/rate-limits/rules"))).andExpect(status().isOk());
    }

    @Test
    void adminCanBlockAndUnblockAnEmployeeWithAnOverride() throws Exception {
        String rules = mvc.perform(admin(get("/admin/rate-limits/rules").param("apiId", "employees.list")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long empRuleId = 0;
        for (JsonNode n : mapper.readTree(rules)) {
            if ("EMP".equals(n.get("dimension").asText())) {
                empRuleId = n.get("ruleId").asLong();
            }
        }
        assertTrue(empRuleId > 0);

        String created = mvc.perform(admin(post("/admin/rate-limits/overrides"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("ruleId", empRuleId, "dimension", "EMP",
                                "subjectValue", "it-bad-1", "blocked", true, "reason", "abuse"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long overrideId = mapper.readTree(created).get("overrideId").asLong();

        mvc.perform(as(get("/api/employees"), "10.1.0.12", "it-bad-1", "it-d-3"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("BLOCKED"));
        mvc.perform(as(get("/api/employees"), "10.1.0.12", "it-good-1", "it-d-3")).andExpect(status().isOk());

        mvc.perform(admin(delete("/admin/rate-limits/overrides/" + overrideId))).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/employees"), "10.1.0.12", "it-bad-1", "it-d-3")).andExpect(status().isOk());
    }

    @Test
    void shadowRuleRecordsViolationsButNeverBlocks() throws Exception {
        String created = mvc.perform(admin(post("/admin/rate-limits/rules"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("apiId", "employees.list", "dimension", "API_KEY",
                                "algorithm", "FIXED_WINDOW", "limitCount", 2, "windowSeconds", 60, "mode", "SHADOW"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long ruleId = mapper.readTree(created).get("ruleId").asLong();

        for (int i = 0; i < 5; i++) {
            mvc.perform(as(get("/api/employees").header("X-Api-Key", "it-key-1"), "10.1.0.7", "it-sh-1", "it-d-4"))
                    .andExpect(status().isOk());
        }

        boolean recorded = false;
        for (int attempt = 0; attempt < 30 && !recorded; attempt++) {
            String events = mvc.perform(admin(get("/admin/rate-limits/events").param("limit", "100")))
                    .andReturn().getResponse().getContentAsString();
            for (JsonNode n : mapper.readTree(events)) {
                if ("SHADOW_DENIED".equals(n.get("decision").asText()) && "it-key-1".equals(n.get("subjectValue").asText())) {
                    recorded = true;
                }
            }
            if (!recorded) {
                Thread.sleep(100);
            }
        }
        assertTrue(recorded, "shadow violation should be recorded in rate_limit_event");
        mvc.perform(admin(delete("/admin/rate-limits/rules/" + ruleId))).andExpect(status().isNoContent());
    }

    @Test
    void simulateShowsWhichRulesApplyWithoutConsumingQuota() throws Exception {
        mvc.perform(admin(post("/admin/rate-limits/simulate")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"method\":\"POST\",\"path\":\"/api/payments\",\"empId\":\"E1\",\"deptId\":\"D2\",\"ip\":\"9.9.9.9\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiId").value("payments.create"))
                .andExpect(jsonPath("$.rules.length()").value(5));
    }

    @Test
    void invalidRuleIsRejectedWith400() throws Exception {
        mvc.perform(admin(post("/admin/rate-limits/rules")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiId\":\"\",\"dimension\":\"EMP\",\"algorithm\":\"TOKEN_BUCKET\",\"limitCount\":0,\"windowSeconds\":60}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }
}
