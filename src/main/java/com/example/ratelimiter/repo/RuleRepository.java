package com.example.ratelimiter.repo;

import com.example.ratelimiter.engine.Snapshot;
import com.example.ratelimiter.model.Algorithm;
import com.example.ratelimiter.model.ApiEndpoint;
import com.example.ratelimiter.model.Dimension;
import com.example.ratelimiter.model.FailMode;
import com.example.ratelimiter.model.Mode;
import com.example.ratelimiter.model.Rule;
import com.example.ratelimiter.model.RuleOverride;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** All SQL lives here (plain JdbcTemplate, portable between PostgreSQL and the H2 used locally). */
@Repository
public class RuleRepository {

    private final JdbcTemplate jdbc;

    public RuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- mapping helpers ----------

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime o = rs.getObject(col, OffsetDateTime.class);
        return o == null ? null : o.toInstant();
    }

    private static OffsetDateTime odt(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, java.time.ZoneOffset.UTC);
    }

    private static final RowMapper<Rule> RULE = (rs, n) -> new Rule(
            rs.getLong("rule_id"),
            rs.getString("api_id"),
            (Long) rs.getObject("plan_id"),
            Dimension.valueOf(rs.getString("dimension")),
            Algorithm.valueOf(rs.getString("algorithm")),
            rs.getInt("limit_count"),
            rs.getInt("window_seconds"),
            (Integer) rs.getObject("burst_capacity"),
            rs.getInt("priority"),
            FailMode.valueOf(rs.getString("fail_mode")),
            Mode.valueOf(rs.getString("mode")),
            rs.getBoolean("enabled"),
            rs.getInt("version"),
            rs.getString("updated_by"),
            instant(rs, "updated_at"));

    private static final RowMapper<RuleOverride> OVERRIDE = (rs, n) -> new RuleOverride(
            rs.getLong("override_id"),
            rs.getLong("rule_id"),
            Dimension.valueOf(rs.getString("dimension")),
            rs.getString("subject_value"),
            (Integer) rs.getObject("limit_count"),
            (Integer) rs.getObject("window_seconds"),
            rs.getBoolean("blocked"),
            instant(rs, "expires_at"),
            rs.getString("reason"));

    private static final RowMapper<ApiEndpoint> API = (rs, n) -> new ApiEndpoint(
            rs.getString("api_id"), rs.getString("http_method"), rs.getString("path_pattern"),
            rs.getString("description"));

    // ---------- snapshot for the hot-path cache ----------

    public Snapshot loadSnapshot() {
        List<ApiEndpoint> apis = findApis();
        List<Rule> rules = jdbc.query("SELECT * FROM rate_limit_rule WHERE enabled = TRUE", RULE);
        Map<String, RuleOverride> overrides = new HashMap<>();
        for (RuleOverride o : findOverrides(null)) {
            overrides.put(Snapshot.overrideKey(o.ruleId(), o.dimension(), o.subjectValue()), o);
        }
        Map<String, Long> plans = new HashMap<>();
        for (SubjectPlanRecord sp : findSubjectPlans()) {
            plans.put(Snapshot.planKey(sp.dimension(), sp.subjectValue()), sp.planId());
        }
        return new Snapshot(apis, rules, overrides, plans);
    }

    // ---------- APIs ----------

    public List<ApiEndpoint> findApis() {
        return jdbc.query("SELECT * FROM api_endpoint ORDER BY api_id", API);
    }

    // ---------- rules ----------

    public List<Rule> findRules(String apiId) {
        if (apiId == null || apiId.isBlank()) {
            return jdbc.query("SELECT * FROM rate_limit_rule ORDER BY api_id, priority, rule_id", RULE);
        }
        return jdbc.query("SELECT * FROM rate_limit_rule WHERE api_id = ? ORDER BY priority, rule_id", RULE, apiId);
    }

    public Optional<Rule> findRule(long ruleId) {
        return jdbc.query("SELECT * FROM rate_limit_rule WHERE rule_id = ?", RULE, ruleId).stream().findFirst();
    }

    public long insertRule(RuleInput in, String user) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rate_limit_rule (api_id, plan_id, dimension, algorithm, limit_count, window_seconds,"
                            + " burst_capacity, priority, fail_mode, mode, enabled, version, updated_by)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,1,?)",
                    Statement.RETURN_GENERATED_KEYS);
            bindRule(ps, in);
            ps.setString(12, user);
            return ps;
        }, kh);
        return generatedId(kh, "rule_id");
    }

    /** Replaces a rule and bumps its version. Returns false when the rule does not exist. */
    public boolean updateRule(long ruleId, RuleInput in, String user) {
        return jdbc.update(
                "UPDATE rate_limit_rule SET api_id=?, plan_id=?, dimension=?, algorithm=?, limit_count=?,"
                        + " window_seconds=?, burst_capacity=?, priority=?, fail_mode=?, mode=?, enabled=?,"
                        + " version = version + 1, updated_by=?, updated_at=CURRENT_TIMESTAMP WHERE rule_id=?",
                in.apiId(), in.planId(), in.dimension().name(), in.algorithm().name(), in.limitCount(),
                in.windowSeconds(), in.burstCapacity(), in.priority(), in.failMode().name(), in.mode().name(),
                in.enabled(), user, ruleId) > 0;
    }

    public boolean deleteRule(long ruleId) {
        jdbc.update("DELETE FROM rate_limit_override WHERE rule_id = ?", ruleId);
        return jdbc.update("DELETE FROM rate_limit_rule WHERE rule_id = ?", ruleId) > 0;
    }

    private static void bindRule(PreparedStatement ps, RuleInput in) throws SQLException {
        ps.setString(1, in.apiId());
        if (in.planId() == null) {
            ps.setNull(2, java.sql.Types.BIGINT);
        } else {
            ps.setLong(2, in.planId());
        }
        ps.setString(3, in.dimension().name());
        ps.setString(4, in.algorithm().name());
        ps.setInt(5, in.limitCount());
        ps.setInt(6, in.windowSeconds());
        if (in.burstCapacity() == null) {
            ps.setNull(7, java.sql.Types.INTEGER);
        } else {
            ps.setInt(7, in.burstCapacity());
        }
        ps.setInt(8, in.priority());
        ps.setString(9, in.failMode().name());
        ps.setString(10, in.mode().name());
        ps.setBoolean(11, in.enabled());
    }

    // ---------- overrides ----------

    public List<RuleOverride> findOverrides(Long ruleId) {
        if (ruleId == null) {
            return jdbc.query("SELECT * FROM rate_limit_override ORDER BY override_id", OVERRIDE);
        }
        return jdbc.query("SELECT * FROM rate_limit_override WHERE rule_id = ? ORDER BY override_id", OVERRIDE,
                ruleId);
    }

    /** Creates or replaces the override for (rule, dimension, subject). */
    public long upsertOverride(long ruleId, Dimension dimension, String subject, Integer limit, Integer window,
                               boolean blocked, Instant expiresAt, String reason) {
        jdbc.update("DELETE FROM rate_limit_override WHERE rule_id = ? AND dimension = ? AND subject_value = ?",
                ruleId, dimension.name(), subject);
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rate_limit_override (rule_id, dimension, subject_value, limit_count, window_seconds,"
                            + " blocked, expires_at, reason) VALUES (?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, ruleId);
            ps.setString(2, dimension.name());
            ps.setString(3, subject);
            if (limit == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setInt(4, limit);
            }
            if (window == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, window);
            }
            ps.setBoolean(6, blocked);
            ps.setObject(7, odt(expiresAt));
            ps.setString(8, reason);
            return ps;
        }, kh);
        return generatedId(kh, "override_id");
    }

    public boolean deleteOverride(long overrideId) {
        return jdbc.update("DELETE FROM rate_limit_override WHERE override_id = ?", overrideId) > 0;
    }

    // ---------- plans ----------

    public List<PlanRecord> findPlans() {
        return jdbc.query("SELECT plan_id, name, description FROM rate_limit_plan ORDER BY plan_id",
                (rs, n) -> new PlanRecord(rs.getLong("plan_id"), rs.getString("name"), rs.getString("description")));
    }

    public long insertPlan(String name, String description) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO rate_limit_plan (name, description) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setString(2, description);
            return ps;
        }, kh);
        return generatedId(kh, "plan_id");
    }

    public List<SubjectPlanRecord> findSubjectPlans() {
        return jdbc.query(
                "SELECT s.dimension, s.subject_value, s.plan_id, p.name FROM subject_plan s"
                        + " JOIN rate_limit_plan p ON p.plan_id = s.plan_id ORDER BY s.dimension, s.subject_value",
                (rs, n) -> new SubjectPlanRecord(Dimension.valueOf(rs.getString("dimension")),
                        rs.getString("subject_value"), rs.getLong("plan_id"), rs.getString("name")));
    }

    public void assignPlan(Dimension dimension, String subject, long planId) {
        jdbc.update("DELETE FROM subject_plan WHERE dimension = ? AND subject_value = ?", dimension.name(), subject);
        jdbc.update("INSERT INTO subject_plan (dimension, subject_value, plan_id) VALUES (?,?,?)",
                dimension.name(), subject, planId);
    }

    public boolean unassignPlan(Dimension dimension, String subject) {
        return jdbc.update("DELETE FROM subject_plan WHERE dimension = ? AND subject_value = ?",
                dimension.name(), subject) > 0;
    }

    // ---------- events ----------

    public void insertEvent(String apiId, Long ruleId, String dimension, String subject, String decision) {
        jdbc.update("INSERT INTO rate_limit_event (occurred_at, api_id, rule_id, dimension, subject_value, decision)"
                + " VALUES (CURRENT_TIMESTAMP, ?,?,?,?,?)", apiId, ruleId, dimension, subject, decision);
    }

    public List<EventRecord> findEvents(int limit) {
        return jdbc.query("SELECT * FROM rate_limit_event ORDER BY event_id DESC LIMIT ?",
                (rs, n) -> new EventRecord(rs.getLong("event_id"), instant(rs, "occurred_at"), rs.getString("api_id"),
                        (Long) rs.getObject("rule_id"), rs.getString("dimension"), rs.getString("subject_value"),
                        rs.getString("decision")),
                limit);
    }

    private static long generatedId(KeyHolder kh, String column) {
        Map<String, Object> keys = kh.getKeys();
        if (keys != null) {
            for (Map.Entry<String, Object> e : keys.entrySet()) {
                if (e.getKey().equalsIgnoreCase(column) && e.getValue() instanceof Number n) {
                    return n.longValue();
                }
            }
        }
        Number n = kh.getKey();
        if (n == null) {
            throw new IllegalStateException("No generated key returned for " + column);
        }
        return n.longValue();
    }
}
