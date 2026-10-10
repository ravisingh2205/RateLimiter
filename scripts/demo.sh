#!/usr/bin/env bash
# Walks through every rate limiting pattern against a running instance.
#   BASE=http://localhost:8080 ADMIN_TOKEN=changeme scripts/demo.sh
set -u
BASE="${BASE:-http://localhost:8080}"
TOKEN="${ADMIN_TOKEN:-changeme}"

code() { curl -s -o /dev/null -w "%{http_code}\n" "$@"; }
tally() { sort | uniq -c | awk '{printf "    HTTP %s x %s\n", $2, $1}'; }
title() { printf "\n\033[1m%s\033[0m\n" "$1"; }

title "1. POST /api/auth/login  (sliding window: 5 per 5 min per username, fail-closed)"
echo "  8 wrong-password attempts for username 'alice-$$':"
for i in $(seq 1 8); do
  code -X POST "$BASE/api/auth/login" -H "Content-Type: application/json" -H "X-Emp-Id: alice-$$" \
       -d '{"username":"alice","password":"wrong"}'
done | tally
echo "  expected: 5 x 401 (checked, wrong password) then 3 x 429 (limiter stops the brute force)"

title "2. GET /api/employees  (token bucket: burst 20 per employee, refill 100/min)"
echo "  25 rapid requests as employee 'emp-$$' in department 'D1' (FREE plan):"
for i in $(seq 1 25); do code "$BASE/api/employees" -H "X-Emp-Id: emp-$$" -H "X-Dept-Id: D1"; done | tally
echo "  expected: about 20-21 x 200, rest 429. Another employee is not affected:"
code "$BASE/api/employees" -H "X-Emp-Id: other-$$" -H "X-Dept-Id: D1" | sed 's/^/    HTTP /'
echo "  response headers on a normal call:"
curl -s -D - -o /dev/null "$BASE/api/employees" -H "X-Emp-Id: hdr-$$" -H "X-Dept-Id: D7" | grep -i '^ratelimit' | sed 's/^/    /'

title "2b. Plan-based limit: FREE department D1 (burst 30) vs PREMIUM department D7 (burst 1000)"
for dept in D1 D7; do
  echo "  45 requests spread over 5 employees in $dept:"
  for i in $(seq 1 45); do code "$BASE/api/employees" -H "X-Emp-Id: plan-$dept-$$-$((i % 5))" -H "X-Dept-Id: $dept"; done | tally
done

title "3. POST /api/payments  (token bucket 5 burst/employee + max 3 in flight, fail-closed)"
echo "  8 requests as employee 'pay-$$':"
for i in $(seq 1 8); do
  code -X POST "$BASE/api/payments" -H "Content-Type: application/json" -H "X-Emp-Id: pay-$$" -H "X-Dept-Id: D2" \
       -H "Idempotency-Key: demo-$$-$i" -d '{"amount":100,"currency":"INR"}'
done | tally
echo "  expected: 5 x 201 then 3 x 429"

title "4. POST /api/reports/export  (max 2 concurrent per department, 50 per day)"
echo "  3 simultaneous 3-second exports for department 'rep-$$':"
( for i in 1 2 3; do
    code -X POST "$BASE/api/reports/export?durationMs=3000" -H "X-Emp-Id: r$i" -H "X-Dept-Id: rep-$$" &
  done; wait ) | tally
echo "  expected: 2 x 200 and 1 x 429 (the third found no free slot)"

title "5. POST /api/notifications/send  (leaky bucket: queue depth 20, drains 10/s per department)"
echo "  35 rapid sends for department 'notif-$$':"
for i in $(seq 1 35); do
  code -X POST "$BASE/api/notifications/send" -H "Content-Type: application/json" -H "X-Dept-Id: notif-$$" -d '{"channel":"SMS"}'
done | tally
echo "  expected: about 20-23 x 202, rest 429"

title "Admin: simulate which rules apply (consumes no quota)"
curl -s -X POST "$BASE/admin/rate-limits/simulate" -H "X-Admin-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"method":"POST","path":"/api/payments","empId":"E1","deptId":"D7","ip":"1.2.3.4"}' | head -c 600; echo " ..."

title "Admin: block one employee with an override, then lift it"
RULE_ID=$(curl -s "$BASE/admin/rate-limits/rules?apiId=employees.list" -H "X-Admin-Token: $TOKEN" \
  | tr '{' '\n' | grep '"dimension":"EMP"' | sed -E 's/.*"ruleId":([0-9]+).*/\1/' | head -1)
echo "  employees.list EMP rule id: $RULE_ID"
OV=$(curl -s -X POST "$BASE/admin/rate-limits/overrides" -H "X-Admin-Token: $TOKEN" -H "Content-Type: application/json" \
  -d "{\"ruleId\":$RULE_ID,\"dimension\":\"EMP\",\"subjectValue\":\"mallory\",\"blocked\":true,\"reason\":\"demo\"}" \
  | sed -E 's/.*"overrideId":([0-9]+).*/\1/')
printf "  mallory while blocked:  "; code "$BASE/api/employees" -H "X-Emp-Id: mallory" -H "X-Dept-Id: D2"
curl -s -X DELETE "$BASE/admin/rate-limits/overrides/$OV" -H "X-Admin-Token: $TOKEN" -o /dev/null
printf "  mallory after unblock:  "; code "$BASE/api/employees" -H "X-Emp-Id: mallory" -H "X-Dept-Id: D2"

title "Recent denials recorded (async) and metrics"
sleep 1
curl -s "$BASE/admin/rate-limits/events?limit=5" -H "X-Admin-Token: $TOKEN" | head -c 700; echo " ..."
echo "  Prometheus counters: $BASE/actuator/prometheus  (look for ratelimit_decisions_total)"
echo "  Swagger UI:          $BASE/swagger-ui.html"
