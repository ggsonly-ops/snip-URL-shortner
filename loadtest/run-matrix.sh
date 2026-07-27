#!/usr/bin/env bash
#
# Runs the three configurations that produce the README results table, in order,
# restarting the stack between them so each run starts from a clean cache.
#
#   ./loadtest/run-matrix.sh
#
# Prerequisites: docker, and either k6 on the PATH or the compose k6 profile (used
# automatically when k6 is not installed).

set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://localhost}"
SEED_COUNT="${SEED_COUNT:-1000}"

# Rate limits are raised for the whole matrix, deliberately and on the record.
#
# The production defaults (600 redirects/min, 100 writes/min per identity) are sized
# for a human. A load test drives thousands of requests per second from one or two
# source IPs, so with the defaults in place the numbers below would be measuring the
# token bucket rather than the service. The limiter is still enabled and still
# enforcing - it is just sized for the traffic shape being generated.
#
# The rate limiter has its own dedicated coverage in RateLimitIntegrationTest.
export APP_REDIRECT_CAPACITY="${APP_REDIRECT_CAPACITY:-5000000}"
export APP_REDIRECT_REFILL="${APP_REDIRECT_REFILL:-30000000}"
export APP_AUTH_CAPACITY="${APP_AUTH_CAPACITY:-5000000}"
export APP_AUTH_REFILL="${APP_AUTH_REFILL:-30000000}"
export APP_ANON_CAPACITY="${APP_ANON_CAPACITY:-5000000}"
export APP_ANON_REFILL="${APP_ANON_REFILL:-30000000}"

# Same reasoning for Nginx's own per-IP limit_req zone: k6 is a single source IP.
export SNIP_EDGE_RATE="${SNIP_EDGE_RATE:-100000r/s}"
export SNIP_EDGE_BURST="${SNIP_EDGE_BURST:-200000}"
RESULTS_DIR="results"
mkdir -p "$RESULTS_DIR"

SCALE_FILES=(-f docker-compose.yml -f docker-compose.scale.yml)

have_k6() { command -v k6 >/dev/null 2>&1; }

run_k6() {
  local script="$1" label="$2"
  if have_k6; then
    RUN_LABEL="$label" k6 run -e BASE_URL="$BASE_URL" -e RUN_LABEL="$label" \
      "loadtest/$script" | tee "$RESULTS_DIR/$label.txt"
  else
    docker compose --profile loadtest run --rm \
      -e BASE_URL=http://nginx -e RUN_LABEL="$label" \
      k6 run "/scripts/$script" | tee "$RESULTS_DIR/$label.txt"
  fi
}

wait_for_health() {
  echo "  waiting for the stack to be healthy..."
  for _ in $(seq 1 90); do
    if curl -fsS "$BASE_URL/actuator/health/readiness" 2>/dev/null | grep -q UP; then
      echo "  up"
      return 0
    fi
    sleep 2
  done
  echo "  stack did not become healthy in time" >&2
  exit 1
}

capture_status() {
  curl -fsS "$BASE_URL/api/status" > "$RESULTS_DIR/$1-status.json" 2>/dev/null || true
}

banner() {
  echo
  echo "═══════════════════════════════════════════════════════"
  echo " $1"
  echo "═══════════════════════════════════════════════════════"
}

# ---------------------------------------------------------------------------
banner "1/3  baseline: 1 instance, cache DISABLED"
# ---------------------------------------------------------------------------
docker compose down -v >/dev/null 2>&1 || true
APP_CACHE_ENABLED=false APP_BLOOM_ENABLED=false docker compose up -d --build
wait_for_health
echo "  seeding $SEED_COUNT links..."
BASE_URL="$BASE_URL" COUNT="$SEED_COUNT" node loadtest/seed.mjs
run_k6 redirect.js 1-nocache
capture_status 1-nocache

# ---------------------------------------------------------------------------
banner "2/3  1 instance, Redis cache-aside ENABLED"
# ---------------------------------------------------------------------------
docker compose down >/dev/null 2>&1 || true
docker compose up -d
wait_for_health
run_k6 redirect.js 2-cache
capture_status 2-cache

# ---------------------------------------------------------------------------
banner "3/3  3 instances behind Nginx, cache enabled"
# ---------------------------------------------------------------------------
docker compose "${SCALE_FILES[@]}" up -d --build
wait_for_health
run_k6 redirect.js 3-scaled
capture_status 3-scaled

# ---------------------------------------------------------------------------
banner "write path (3 instances)"
# ---------------------------------------------------------------------------
run_k6 create.js 4-create

banner "done"
echo "Raw output and summaries are in ./$RESULTS_DIR"
echo "Fill the README table from these; do not reuse the illustrative numbers."
