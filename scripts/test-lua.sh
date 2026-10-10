#!/usr/bin/env bash
# Exercises the Lua scripts against a real Redis (default localhost:6379, override with REDIS_PORT).
# Usage: scripts/test-lua.sh   (needs redis-cli and a running redis-server)
set -u
PORT="${REDIS_PORT:-6379}"
DIR="$(cd "$(dirname "$0")/.." && pwd)/src/main/resources/lua"
R="redis-cli -p $PORT"
fail=0
run() { $R --eval "$DIR/$1" "${@:2}" | tr '\n' ' ' | sed 's/ *$//'; }
check() { # name expected actual
  if [ "$2" == "$3" ]; then echo "PASS  $1"; else echo "FAIL  $1  expected='$2' actual='$3'"; fail=1; fi
}
first() { echo "$1" | awk '{print $1}'; }

$R ping >/dev/null || { echo "Redis not reachable on port $PORT"; exit 2; }
$R flushdb >/dev/null

# --- token bucket: capacity 5, refill 1/s ---
allowed=0; for i in 1 2 3 4 5 6 7; do r=$(run token_bucket.lua tb:a , 5 1 1); [ "$(first "$r")" == "1" ] && allowed=$((allowed+1)); done
check "token bucket: 5 of 7 immediate requests pass" 5 $allowed
r=$(run token_bucket.lua tb:a , 5 1 1); retry=$(echo $r | awk '{print $3}')
[ "$retry" -gt 0 ] && check "token bucket: retryAfterMs > 0 when empty" ok ok || check "token bucket: retryAfterMs > 0 when empty" ok "retry=$retry"
sleep 1.2
check "token bucket: refilled after 1.2s" 1 "$(first "$(run token_bucket.lua tb:a , 5 1 1)")"

# --- token bucket atomicity under parallel load: capacity 50, near-zero refill, 200 parallel calls ---
$R del tb:par >/dev/null
for i in $(seq 1 200); do ( run token_bucket.lua tb:par , 50 0.0001 1 | awk '{print $1}' >> /tmp/rl_par_$$ ) & done; wait
okc=$(grep -c '^1$' /tmp/rl_par_$$); rm -f /tmp/rl_par_$$
check "token bucket: exactly 50 of 200 parallel requests pass" 50 $okc

# --- fixed window: 3 per 1000ms ---
a=0; for i in 1 2 3 4 5; do r=$(run fixed_window.lua fw:a , 3 1000); [ "$(first "$r")" == "1" ] && a=$((a+1)); done
check "fixed window: 3 of 5 pass" 3 $a
sleep 1.1
check "fixed window: resets after window" 1 "$(first "$(run fixed_window.lua fw:a , 3 1000)")"

# --- sliding window: 3 per 1000ms ---
a=0; for i in 1 2 3 4 5; do r=$(run sliding_window.lua "{sw:a}" , 3 1000); [ "$(first "$r")" == "1" ] && a=$((a+1)); done
check "sliding window: 3 of 5 pass" 3 $a
sleep 2.1
check "sliding window: passes again after 2 windows" 1 "$(first "$(run sliding_window.lua "{sw:a}" , 3 1000)")"

# --- leaky bucket: capacity 3, leak 2/s ---
a=0; for i in 1 2 3 4 5; do r=$(run leaky_bucket.lua lb:a , 3 2); [ "$(first "$r")" == "1" ] && a=$((a+1)); done
check "leaky bucket: 3 of 5 pass" 3 $a
sleep 0.7
check "leaky bucket: drains and accepts again" 1 "$(first "$(run leaky_bucket.lua lb:a , 3 2)")"

# --- concurrency: max 2, lease 1000ms ---
check "concurrency: first lease"  1 "$(first "$(run concurrency_acquire.lua cc:a , 2 1000 L1)")"
check "concurrency: second lease" 1 "$(first "$(run concurrency_acquire.lua cc:a , 2 1000 L2)")"
check "concurrency: third denied" 0 "$(first "$(run concurrency_acquire.lua cc:a , 2 1000 L3)")"
$R zrem cc:a L1 >/dev/null
check "concurrency: allowed after release" 1 "$(first "$(run concurrency_acquire.lua cc:a , 2 1000 L3)")"
sleep 1.2
check "concurrency: leases expire without release" 1 "$(first "$(run concurrency_acquire.lua cc:a , 2 1000 L4)")"

[ $fail -eq 0 ] && echo "ALL LUA TESTS PASSED" || echo "SOME LUA TESTS FAILED"
exit $fail
