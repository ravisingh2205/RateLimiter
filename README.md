# Rate Limiter Platform

A configurable, multi-dimension, distributed rate limiter for a Spring Boot application.
Limits are **data in a database, not code**: change a limit, add a plan, block an abusive caller or
trial a new rule in shadow mode without a deploy.

* Limits on **IP, employee (`empId`), department (`deptId`), API key, or global**, any combination per API
* **Five algorithms**: token bucket, fixed window, sliding window, leaky bucket, concurrency limiter
* Applies to **every `/api/**` request** through one servlet filter; unknown paths still get a default IP rule
* **Plan-aware**: a department on `PREMIUM` gets different limits than one on `FREE`
* **Per-subject overrides** (raise a cap, hard-block, with expiry)
* **Fail-open / fail-closed per rule** when Redis is down, **shadow mode** for safe rollout
* IETF-style `RateLimit-*` headers, `429` + `Retry-After`, Micrometer/Prometheus metrics, async audit trail

## Quick start (no Docker, no Redis, no PostgreSQL)

Needs JDK 17+ and Maven 3.9+.

```bash
mvn spring-boot:run          # in-memory limiter + in-memory H2 (PostgreSQL mode), seeded with example rules
# in another terminal
scripts/demo.sh              # runs one scenario per pattern and prints what happened
```

* Swagger UI: http://localhost:8080/swagger-ui.html
* H2 data is created and seeded by Flyway on every start (`src/main/resources/db/migration`)
* Admin API token (demo): `X-Admin-Token: changeme`

## Production-like run (Redis + PostgreSQL)

```bash
docker compose up -d postgres redis
mvn spring-boot:run -Dspring-boot.run.profiles=infra
# or everything in containers:
docker compose up --build
```

The `infra` profile sets `ratelimit.store=redis` (shared atomic counters via Lua) and uses PostgreSQL for rules.
Run two instances on different ports (`-Dserver.port=8081`) and hit both: the limit is shared.

## The five example APIs

The controllers contain no rate limiting code. Rules live in `V2__seed.sql`.

| # | API | Rules (all must pass) | Pattern | On Redis outage |
|---|---|---|---|---|
| 1 | `POST /api/auth/login` | per **IP** 20 / 5 min, per **username** (`X-Emp-Id`) 5 / 5 min | Sliding window | **fail-closed** |
| 2 | `GET /api/employees` | per **employee** burst 20 (refill 100/min), per **department** burst 200 (FREE 30, PREMIUM 1000) | Token bucket | fail-open |
| 3 | `POST /api/payments` | per employee burst 5 (20/min), per dept 500/min, **global** 5000/min, max **3 in flight** per employee | Token bucket + concurrency | **fail-closed** |
| 4 | `POST /api/reports/export` | max **2 concurrent** per department, **50 per day** per department | Concurrency + fixed window | fail-open |
| 5 | `POST /api/notifications/send` | per department queue 20 draining 10/s, global 100 draining 50/s | Leaky bucket | fail-open |
| * | every API | per IP burst 200 (1200/min) safety net | Token bucket | fail-open |

Try it by hand:

```bash
# employees: 25 rapid calls, the first ~20 pass, then 429 with Retry-After
for i in $(seq 25); do curl -s -o /dev/null -w "%{http_code} " localhost:8080/api/employees -H 'X-Emp-Id: E1' -H 'X-Dept-Id: D1'; done; echo

# one denied response in full
curl -i localhost:8080/api/employees -H 'X-Emp-Id: E1' -H 'X-Dept-Id: D1'
```

Caller identity in the demo comes from headers `X-Emp-Id`, `X-Dept-Id`, `X-Api-Key` (and the socket IP).
**In a real app take `empId`/`deptId` from the authenticated principal (JWT claims), never from client headers.**
Edit `IdentityResolver` only.

## Architecture

```
Client
  |
  v
CDN / WAF            coarse IP limits, DDoS (outside this project)
  |
  v
RateLimitFilter  (/api/**)
  1. IdentityResolver        ip, empId, deptId, apiKey
  2. Snapshot (in memory)    api_endpoint -> apiId; rules + overrides + plans, never a DB call per request
  3. RateLimitEngine         every applicable rule must pass; concurrency rules last
  4. LimiterStore            Redis (one Lua call per rule, atomic, Redis TIME) | in-memory
  5. allow -> controller + RateLimit-* headers ; deny -> 429 + Retry-After ; store down -> per-rule fail mode
  |
  +--> DecisionListener --> Micrometer counters (/actuator/prometheus)
                        \-> async writer -> rate_limit_event table   (swap for Kafka in production)

PostgreSQL = source of truth for config:  api_endpoint, rate_limit_rule, rate_limit_plan, subject_plan,
                                          rate_limit_override, rate_limit_event
Redis      = runtime counters/buckets (keys have TTLs):  rl:{api}:{dimension}:{subject}:{algorithm}
```

### How a request is resolved

1. `method + path` -> `apiId` (from `api_endpoint`, Ant-style patterns); unknown -> `UNKNOWN`
2. Rules for that `apiId` **plus** rules with `api_id = '*'`
3. Plan of the caller (department first, then employee, then API key). Per (api, dimension, algorithm) a rule for the caller's plan beats the default rule
4. Rules whose dimension the caller has no value for are skipped (anonymous callers have no `empId`; the IP rule still applies)
5. Overrides applied (new limit, or hard block, until `expires_at`)
6. Rate rules run first, concurrency rules last, so a slot is only taken if everything else passed; a slot taken earlier is released if a later rule denies

### Algorithm cheat sheet

| Algorithm | `limit_count` / `window_seconds` / `burst_capacity` mean | Use it for |
|---|---|---|
| `TOKEN_BUCKET` | refill `limit/window` per second, capacity = `burst_capacity` (or limit) | user-facing APIs, bursts OK |
| `SLIDING_WINDOW` | `limit` per rolling `window` (weighted two-window counter) | login, anything needing no boundary burst |
| `FIXED_WINDOW` | `limit` per `window`, resets at the boundary | daily quotas, cheap coarse limits |
| `LEAKY_BUCKET` | drains `limit/window` per second, depth = `burst_capacity` | protect a slow downstream (SMS/email provider) |
| `CONCURRENCY` | max `limit` in flight, `window_seconds` = lease TTL | expensive calls: exports, reports, uploads |

## Admin API (`X-Admin-Token` required)

```
GET    /admin/rate-limits/apis
GET    /admin/rate-limits/rules?apiId=payments.create
POST   /admin/rate-limits/rules                  create a rule
PUT    /admin/rate-limits/rules/{id}             replace (bumps version)
DELETE /admin/rate-limits/rules/{id}
GET    /admin/rate-limits/overrides?ruleId=
POST   /admin/rate-limits/overrides              { ruleId, dimension, subjectValue, limitCount?, windowSeconds?, blocked?, expiresAt?, reason? }
DELETE /admin/rate-limits/overrides/{id}
GET    /admin/rate-limits/plans         POST /admin/rate-limits/plans
GET    /admin/rate-limits/subject-plans PUT  /admin/rate-limits/subject-plans   { dimension, subjectValue, planId }
POST   /admin/rate-limits/simulate               which rules WOULD apply to { method, path, ip, empId, deptId, apiKey }
GET    /admin/rate-limits/events?limit=50        recent denials
POST   /admin/rate-limits/refresh
```

Example: trial a stricter limit in **shadow mode** first, then enforce it:

```bash
curl -X POST localhost:8080/admin/rate-limits/rules -H 'X-Admin-Token: changeme' -H 'Content-Type: application/json' -d '{
  "apiId":"employees.list","dimension":"DEPT","algorithm":"TOKEN_BUCKET",
  "limitCount":600,"windowSeconds":60,"burstCapacity":60,"mode":"SHADOW"}'
# watch /admin/rate-limits/events for SHADOW_DENIED, then PUT the same body with "mode":"ENFORCE"
```

## Response contract

Allowed responses (tightest rule shown):
```
RateLimit-Limit: 20
RateLimit-Remaining: 17
RateLimit-Reset: 12          seconds until the limit fully recovers
RateLimit-Policy: 100;w=60
```
Denied:
```
HTTP/1.1 429 Too Many Requests
Retry-After: 12
{"error":"RATE_LIMIT_EXCEEDED","message":"Too many requests for EMP 'E1' on employees.list (TOKEN_BUCKET, limit 100 per 60s)",
 "apiId":"employees.list","dimension":"EMP","algorithm":"TOKEN_BUCKET","limit":100,"windowSeconds":60,
 "ruleId":4,"retryAfterSeconds":12,"traceId":"..."}
```
`error` is `RATE_LIMIT_EXCEEDED`, `BLOCKED` (override), or `RATE_LIMITER_UNAVAILABLE` (503, fail-closed rule while Redis is down).

## Tests

```bash
mvn test                                  # unit + integration; Redis store tests run only if Redis is on localhost:6379
REDIS_PORT=6399 scripts/test-lua.sh       # exercises the Lua scripts directly with redis-cli (no Maven needed)
```

* `engine/*Test`, `store/InMemoryLimiterStoreTest`: algorithms, rule resolution, plans, overrides, shadow mode, fail modes, concurrency slot release, atomicity under 200 threads
* `store/RedisLimiterStoreTest`: same behaviours through the real Lua scripts
* `RateLimitIntegrationTest`: full HTTP flow for all five APIs, admin API, overrides, shadow events

## Production notes and known trade-offs

* **No refund across rules.** If rule A passes (token spent) and rule B denies, A's token stays spent. Standard and cheap; the alternative is a multi-key Lua script, which does not work across Redis Cluster slots.
* **Redis Cluster**: each Lua call touches one key (sliding window uses a `{hash tag}`), so it is cluster-safe. A single very hot subject (one giant department) lives on one shard; if that becomes a problem, add a local pre-fetch of tokens per node.
* **Config propagation**: each node refreshes on admin writes (this node) and on a timer (`ratelimit.rule-refresh-millis`). For instant multi-node propagation publish a `rules_changed` message (Redis pub/sub or Kafka) from `AdminController` and call `RuleCache.refresh()` in the subscriber.
* **Audit events**: `EventWriter` inserts asynchronously with a bounded queue that drops on overflow. At real volume replace it with a Kafka producer and batch-insert in a consumer; partition `rate_limit_event` by day and expire old partitions.
* **Metrics tags** are `api, dimension, algorithm, outcome` only. Never tag with ip/empId (unbounded cardinality).
* **Admin security**: the shared token is a placeholder. Put `/admin/**` behind your real authn/authz.
* **Behind a load balancer** set `ratelimit.trust-forwarded-for=true` only if the proxy overwrites `X-Forwarded-For`; otherwise clients can spoof their IP.
* **Multi-region**: give each region its own limiter and a share of the global quota; synchronous cross-region counters are too slow.
* **Clients** should honour `Retry-After` and retry with exponential backoff and jitter; pair POST retries with an `Idempotency-Key` (see the payments endpoint).

## Adding a sixth API

1. `INSERT INTO api_endpoint (api_id, http_method, path_pattern, ...)` (a new Flyway migration, or the admin API for rules)
2. Add rows to `rate_limit_rule` (or `POST /admin/rate-limits/rules`). Until you do, the wildcard IP rule already protects it.
3. No code change in the filter or the engine.

## Layout

```
src/main/java/com/example/ratelimiter
  engine/   RateLimitEngine, Snapshot, Decision, PathMatcher   (pure Java, no Spring)
  store/    LimiterStore, InMemoryLimiterStore, RedisLimiterStore
  model/    Rule, RuleOverride, ResolvedRule, Identity, enums
  repo/     RuleRepository (JdbcTemplate), RuleCache (snapshot refresh)
  web/      RateLimitFilter, IdentityResolver
  admin/    AdminController + request DTOs
  events/   ObservabilityListener (metrics), EventWriter (async audit)
  demo/     the five sample APIs
  config/   wiring, properties, async executor, admin token interceptor
src/main/resources/lua/        the five atomic scripts
src/main/resources/db/migration/   schema + seed
```
