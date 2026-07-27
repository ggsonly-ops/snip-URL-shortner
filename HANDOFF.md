# Snip — Project Handoff

**Read this file completely before writing any code.**

This is a build-in-progress handoff. It records what has been built, what has been
verified and how, every trap already hit and solved, and exactly what remains. It is
written for an agent picking the project up cold with no memory of prior sessions.

- **Project root:** `/Users/aryanpancholi/Desktop/project 2`
- **Original spec:** `Project_2_Snip_URLShortener_Build_Guide.md` (the user has it; it is
  the source of truth for *intent*. Where this document and the guide differ, this
  document wins — the differences are deliberate and each one is explained below.)
- **Status:** backend complete and verified; frontend complete but not browser-verified;
  documentation not started.

---

## 1. TL;DR — where things stand

| Area | Status |
|---|---|
| Backend (all 16 features) | ✅ **Done and verified** |
| Database schema + migrations | ✅ Done, partitioned, verified |
| Unit + integration tests | ✅ **95 tests, all passing** |
| Docker / Nginx / 3-instance scaling | ✅ Done, verified running |
| Prometheus + Grafana | ✅ Done, scraping verified, dashboard provisioned |
| k6 load tests | ✅ Scripts done; **3 of 4 runs executed, real numbers captured** |
| React frontend | ⚠️ **Written and builds cleanly, never opened in a browser** |
| README.md | ❌ **Not written — this is the single biggest remaining item** |
| Architecture diagram / screenshots | ❌ Not done |
| CI workflow | ❌ Not done |

Roughly **85% complete**. What is left is mostly documentation and verification,
not new backend code.

---

## 2. Environment — machine-specific facts you must know

These cost real time to discover. Do not rediscover them.

### 2.1 Java: the system default is WRONG for this project

The system `java` is **JDK 26**. The project targets **Java 21**. Every Maven command
must pin `JAVA_HOME`:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

Without it the build may work but you are not testing what ships.

### 2.2 Docker: three separate traps

**(a) Testcontainers cannot talk to this Docker daemon without an API version pin.**

Docker Desktop 4.81 / Engine 29.6.1 reports `MinAPIVersion 1.40`. Testcontainers still
negotiates **v1.32**, which the daemon answers with a bare HTTP 400. Testcontainers
reports this as the extremely misleading *"Could not find a valid Docker environment"*
even though Docker is running perfectly and `docker ps` works.

Already fixed in `pom.xml` via Surefire:

```xml
<systemPropertyVariables>
  <api.version>${docker.api.version}</api.version>   <!-- 1.44 -->
</systemPropertyVariables>
```

**Do not remove this.** If integration tests suddenly claim Docker is missing, this is
why. Note that the `DOCKER_API_VERSION` env var does *not* work — it must be the
`api.version` **JVM system property**.

**(b) Host ports 5432, 6379 and 8080 are already occupied on this machine.**

- A **native Postgres** listens on `5432`
- Unrelated containers `minicrm-db`, `minicrm-redis`, `minicrm-api` hold `5434`, `6379`, `8080`

`docker compose up` with default ports **will fail**. Every host port is parameterised.
Use this export block for all local work (these are the ports everything below was
verified on):

```bash
export SNIP_HTTP_PORT=8090 SNIP_APP1_PORT=8091 SNIP_APP2_PORT=8092 SNIP_APP3_PORT=8093 \
       SNIP_DB_PORT=55432 SNIP_REDIS_PORT=56379 \
       SNIP_PROMETHEUS_PORT=9091 SNIP_GRAFANA_PORT=3002 \
       APP_BASE_URL=http://localhost:8090
```

Do **not** stop the user's `minicrm-*` containers. They belong to another project.

**(c) k6 is not installed natively.** Use the Compose profile:

```bash
docker compose --profile loadtest run --rm -e BASE_URL=http://nginx k6 run /scripts/redirect.js
```

### 2.3 Currently running

At handoff time the 3-instance stack is **up and healthy** with 1000 seeded links.
`loadtest/codes.json` contains those codes and is only valid while the `snip_pgdata`
volume survives. `docker compose down -v` invalidates it and requires a re-seed.

---

## 3. What this project is

A distributed URL shortener. Paste a long URL, get `http://host/aX9k2`, get redirected,
and every click is recorded asynchronously and surfaced as analytics.

**The product is not the point.** The point is the engineering around it: Snowflake IDs,
cache-aside with stampede protection, atomic Redis rate limiting, off-request-path
analytics, horizontal scaling, and measured load-test numbers. Every design decision is
commented *in the code* with the reasoning, because the project's purpose is to be
explainable in an interview.

**Stack:** Java 21 · Spring Boot 3.3.5 · PostgreSQL 16 · Redis 7 · Nginx · Docker ·
k6 · Prometheus + Grafana · React 18 + Vite.

---

## 4. Architecture as built

```
                       ┌──────────────┐
  client ───────────►  │    Nginx     │  least_conn, keepalive 32,
                       │   :80        │  per-IP limit_req edge shed
                       └──────┬───────┘
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
         ┌────────┐      ┌────────┐      ┌────────┐
         │ app1   │      │ app2   │      │ app3   │   machineId 1 / 2 / 3
         │ :8080  │      │ :8080  │      │ :8080  │   stateless
         └───┬────┘      └───┬────┘      └───┬────┘
             └───────┬───────┴───────┬───────┘
                     ▼               ▼
              ┌────────────┐   ┌──────────────┐
              │   Redis    │   │  PostgreSQL  │
              │ cache      │   │  links       │
              │ rate limit │   │  clicks      │  RANGE partitioned by month
              │ bloom      │   └──────────────┘
              │ stream     │          ▲
              └─────┬──────┘          │
                    └── batch consumer (in-app, @Scheduled) ──┘
```

Prometheus scrapes all three app instances at `/actuator/prometheus`; Grafana reads
Prometheus.

---

## 5. Complete file inventory

### Backend — main (41 Java files)

| Path | Purpose |
|---|---|
| `SnipApplication.java` | Entry point; `@EnableScheduling` |
| **id/** | |
| `Base62.java` | Codec. Digits→upper→lower alphabet, table-driven decode, overflow-checked |
| `SnowflakeIdGenerator.java` | 41/10/12 bit layout, `ReentrantLock` (not `synchronized`, so virtual threads don't pin), two-tier clock-skew handling |
| `MachineIdProvider.java` | Explicit `app.machine-id`, falls back to hostname hash % 1024 |
| **domain/** | |
| `Link.java` | JPA entity. `@Id` with **no** `@GeneratedValue` — app assigns the id |
| `ResolvedLink.java` | Slim record actually cached (id, url, passwordProtected, expiresAt) |
| **cache/** | |
| `LinkCache.java` | Key naming, packed codec, NX PX stampede lock, TTL clamping, negative sentinel |
| `RedisGuard.java` | **Single place Redis failure is handled.** Classifies transport failures, Resilience4j circuit breaker |
| `RedisUnavailableException.java` | Distinguishes "cache miss" from "cache down" |
| `ShortCodeBloomFilter.java` | Redis bit-array bloom, FNV-1a + Kirsch-Mitzenmacher, readiness-gated |
| **service/** | |
| `LinkResolver.java` | The read hot path: bloom → cache → stampede-protected DB load |
| `LinkService.java` | Create/list/update/delete/unlock, dedupe, reserved aliases, after-commit cache writes |
| `UrlValidator.java` | Validation, normalisation, canonical-for-hash, **SSRF guard**, punycode |
| `MaintenanceJobs.java` | Cache warming, bloom rebuild, expiry sweep, partition pre-creation |
| **ratelimit/** | |
| `RateLimiter.java` | Executes the Lua token bucket; fail-open policy |
| `RateLimitFilter.java` | `@Order(1)`, three scopes (redirect/write/read), `X-RateLimit-*` headers |
| `ClientIpResolver.java` | `X-Forwarded-For` **only from trusted proxy CIDRs** |
| `RateLimitResult.java` | Result record |
| **analytics/** | |
| `ClickEventPublisher.java` | Fire-and-forget XADD, never breaks a redirect |
| `ClickConsumer.java` | Consumer group, batch multi-row insert, **idempotent** via stream id, PEL reclaim, stream trim |
| `AnalyticsService.java` | `generate_series` gap-fill, top-N by dimension (column allow-list) |
| `GeoIpService.java` | MaxMind if present, honest null otherwise, labelled synthetic demo mode |
| `UserAgentParser.java` | yauaa, lazily initialised, 3 fields only |
| **web/** | |
| `RedirectController.java` | 302 hot path, password challenge (HTML + JSON) |
| `LinkController.java` | CRUD + analytics + QR |
| `ApiKeyController.java` / `ApiKeys.java` | Capability-token minting |
| `StatusController.java` | Live cache/circuit/bloom state — powers the degradation demo |
| `GlobalExceptionHandler.java` | Typed error envelope, HTML 404 page for browsers |
| `QrCodeService.java` | ZXing PNG |
| **config/** | `SnipProperties` (all tunables), `RedisConfig` (Lua script beans, BCrypt), `WebConfig` (CORS, scheduler pool) |
| **metrics/** | `SnipMetrics` — hit ratio gauge, redirect timer with histogram, stampede/bloom/click counters |

### Backend — resources

| Path | Notes |
|---|---|
| `application.yml` | Everything env-overridable |
| `db/migration/V1__init.sql` | links + partitioned clicks + `ensure_click_partition()` plpgsql helper + 16 seeded monthly partitions |
| `scripts/token_bucket.lua` | The atomic read-compute-write |
| `scripts/bloom_add.lua`, `bloom_check.lua` | k bits in one round trip |

### Tests (9 files, 95 tests, all passing)

| File | Tests | Covers |
|---|---|---|
| `Base62Test` | 14 | Round-trip incl. 100k random, URL-safety, injectivity |
| `SnowflakeIdGeneratorTest` | 9 | **8-thread × 25k uniqueness**, bit layout, sequence exhaustion, both clock-skew tiers |
| `UrlValidatorTest` | 27 | Normalisation, SSRF (metadata, private, CGNAT, IPv6 ULA, multi-A-record), scheme rejection |
| `LinkCacheCodecTest` | 5 | Packed codec round-trip, corrupt-entry tolerance |
| `LinkLifecycleIntegrationTest` | 16 | Create/redirect/dedupe/alias/TTL/password/ownership/QR |
| `CacheIntegrationTest` | 7 | Hit/miss, negative caching, invalidate-not-update, **32-thread stampede** |
| `RateLimitIntegrationTest` | 10 | Capacity, refill, clamp, **200-caller atomicity**, 429 headers |
| `AnalyticsIntegrationTest` | 7 | Full pipeline, **idempotent redelivery**, partitioning |
| `AbstractIntegrationTest` | — | Testcontainers base (skips cleanly when Docker absent) |

### Infra / ops / frontend

`Dockerfile` (multi-stage, non-root, `MaxRAMPercentage`), `docker-compose.yml`,
`docker-compose.scale.yml`, `ops/Dockerfile.web`, `ops/nginx.conf.template`,
`ops/prometheus.yml`, `ops/grafana/**` (datasource pinned `uid: snip-prom`, 13-panel
dashboard), `loadtest/{redirect.js,create.js,seed.mjs,run-matrix.sh}`,
`frontend/**` (React 18 + Vite + Recharts), `.env.example`, `.gitignore`, `.dockerignore`.

---

## 6. Design decisions already made — do not silently reverse these

Each of these is a considered choice with the reasoning written into the code comments.
If you change one, update the comment and the README together.

1. **One Snowflake id per link, used as both PK and code source.** The guide's sample
   calls `nextId()` twice; that is a bug (burns an id, decouples code from row id).
   `Base62.decode(shortCode) == link.id` is asserted by a test.
2. **302, never 301.** Analytics is the product; a 301 makes repeat clicks invisible and
   freezes the destination.
3. **Cache writes happen after commit**, via `TransactionSynchronizationManager`, not
   inline. Inline writes would leave the cache holding rolled-back data.
4. **Invalidate, don't update** on edit. Residual CDC race is documented in code.
5. **Stored URL keeps the author's query-param order; only the dedupe *hash* sorts them.**
   Sorting the stored URL would break signature-bearing URLs.
6. **`VARCHAR(64)` not `CHAR(64)` for `url_hash`** — deviation from the guide. `CHAR`
   maps to `bpchar` and fails Hibernate schema validation, and blank-padding comparison
   is a trap for zero benefit.
7. **Idempotent click consumer** (guide offered a choice). The Redis stream entry id is
   already unique and stable across redelivery, so it is a unique index +
   `ON CONFLICT DO NOTHING`, and counters increment only from `RETURNING` rows.
   ⚠️ The unique index is `(event_id, clicked_at)` because a partitioned table's unique
   index must contain the partition key — so idempotency depends on `clicked_at` coming
   from the event, not `NOW()`. It does.
8. **Rate limiter fails open** when Redis is down (configurable). Redirects are the
   product; Postgres still has its pool as a backstop.
9. **Bloom filter is readiness-gated.** An empty filter would 404 every real link, so it
   is bypassed entirely until a full rebuild stamps a marker.
10. **Three named app services, not `--scale app=3`.** `--scale` gives every replica the
    same `APP_MACHINE_ID` → colliding IDs. Explained at length in `docker-compose.scale.yml`.
11. **No `spring-boot-starter-security`** — only `spring-security-crypto` for BCrypt,
    to get the encoder without the whole filter chain.
12. **API keys are unstored capability tokens.** Honest limitation, must be stated in the
    README (a lost key means unmanageable links).

---

## 7. Bugs already found and fixed — do not reintroduce

These were all discovered the hard way. Each would silently break the build.

| # | Symptom | Cause | Fix |
|---|---|---|---|
| 1 | Context fails: `NoClassDefFoundError: GenericObjectPoolConfig` | `spring.data.redis.lettuce.pool` enabled without `commons-pool2` | Added `org.apache.commons:commons-pool2` to `pom.xml` |
| 2 | `No default constructor found` for `SnowflakeIdGenerator` / `UrlValidator` | Two constructors (prod + test seam); Spring can't choose | `@Autowired` on the public constructor of both |
| 3 | Hibernate `Schema-validation: wrong column type ... found [bpchar]` | `CHAR(64)` vs mapped `varchar` | Migration uses `VARCHAR(64)` |
| 4 | `Could not find a valid Docker environment` (Docker healthy) | Testcontainers negotiates API v1.32; Engine 29 requires ≥1.40 | `api.version` system property in Surefire |
| 5 | Nginx: `directive "server" has no opening "{"` | The template's **comment** mentioned `${SNIP_UPSTREAM}`; envsubst expanded it there too | Never write the var in `${...}` form in comments |
| 6 | `ProtocolException: Invalid HTTP method: PATCH` + `HttpRetryException` in tests | `HttpURLConnection` can't do PATCH or read 401 bodies | Tests use `JdkClientHttpRequestFactory` |
| 7 | Rate-limit test's own `@TestPropertySource` ignored | `@DynamicPropertySource` **outranks** `@TestPropertySource` | Shared defaults moved to `application-integrationtest.yml` |
| 8 | Load test: 99.6% errors | Nginx per-IP `limit_req` at 200 r/s; k6 is one source IP | `SNIP_EDGE_RATE` / `SNIP_EDGE_BURST` parameterised; raised by `run-matrix.sh` |
| 9 | Seeding 1000 links took 9 minutes | Write bucket is 100/min — the limiter working correctly | `APP_AUTH_CAPACITY`/`APP_AUTH_REFILL` env vars; raised during seeding |
| 10 | IDN URLs rejected as "URL must include a host" | `java.net.URI` (RFC 2396) returns null host for non-ASCII authority | `punycodeAuthority()` runs **before** parsing |
| 11 | Test asserted 11-char codes, got 10 | A Snowflake id only Base62-encodes to 11 chars ~6 years after the epoch | Assert `{10,11}` |

---

## 8. Verified state — what was actually proven, and how

### 8.1 Tests

```
Tests run: 95, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
```

Includes the full Testcontainers suite (real Postgres 16 + real Redis 7).

### 8.2 Manually verified against the running stack

- ✅ Create → 201, normalised URL (`HTTPS://Example.com:443/Docs/Guide/?b=2&a=1#frag`
  → `https://example.com/Docs/Guide/?b=2&a=1`), Snowflake breakdown returned
- ✅ Redirect → 302 + `Location` + `Cache-Control: no-cache`
- ✅ Dedupe → reordered query params returned the same code with `deduplicated: true`
- ✅ **SSRF guard** → `169.254.169.254`, `10.0.0.5`, `javascript:`, embedded credentials all rejected with correct messages
- ✅ Reserved alias `api` → 409
- ✅ **Rate limiter** → 15×201 then 429 with `Retry-After: 3`, `X-RateLimit-Remaining: 0`, `X-RateLimit-Scope: write`
- ✅ **Async analytics** → 17 clicks drained in ~1s; browsers `{Safari 12, Chrome 5}`,
  devices `{Phone 12, Desktop 5}`, OS `{iOS 12, Windows NT 5}`, referrers correct,
  8 gap-filled points for a 7-day window
- ✅ **Graceful degradation** → `docker compose stop redis`: **30/30 redirects still 302**,
  circuit `CLOSED → OPEN`, 30 degraded reads counted; after restart circuit returned to `CLOSED`
- ✅ **3 instances** → machineIds 1/2/3 distinct; `least_conn` spread `{1:14, 2:7, 3:9}` over 30 requests
- ✅ **Prometheus** → all 3 app targets `up`, `snip_cache_hit_ratio` present as 3 series
- ✅ **Grafana** → dashboard `snip-overview` provisioned and discoverable

### 8.3 Load test numbers — REAL, measured on this machine

Profile: ramp 200 VUs → hold → spike 1000 VUs → sustain → ramp down (4m30s),
Zipf-distributed over 1000 seeded links, `redirects: 0`.

| Configuration | Throughput | p50 | p95 | p99 | max | Errors | Cache hit |
|---|---|---|---|---|---|---|---|
| 1 instance, **no cache** | 13,318 req/s | 45.02 ms | 110.73 ms | 165.79 ms | 1182.60 ms | 0.00% | — |
| 1 instance, **Redis cache** | 16,384 req/s | 37.73 ms | 84.82 ms | 127.61 ms | 604.97 ms | 0.00% | **99.98%** |
| 3 instances, Redis cache | 8,357 req/s | 17.38 ms | 268.65 ms | 528.04 ms | — | 0.00% | ~100% |

Raw k6 JSON is in `results/redirect-{1-nocache,2-cache,3-scaled}.json`.

**⚠️ These numbers contain two anomalies. The README must explain them honestly — do
NOT quietly replace them with the guide's illustrative figures.**

**Anomaly 1 — the cache gain is 1.23×, not the ~3× the guide illustrates.**
The working set is 1000 links. Postgres holds all of them in `shared_buffers` (256MB),
so the "no cache" baseline was never disk-bound — it was already an in-memory lookup
over a local socket. Redis therefore saves a JDBC round trip and pool contention, not
disk I/O. To produce a baseline where the cache matters the way the guide implies, seed
far more links (100k+) so the working set exceeds `shared_buffers`, and/or lower
`shared_buffers`. **Either re-run it that way and report the new number, or state this
caveat plainly.** The second option is perfectly respectable and more honest than a
number you cannot defend.

**Anomaly 2 — 3 instances were *slower* in throughput than 1 (8.4k vs 16.4k req/s),
though p50 more than halved (17ms vs 38ms).**
Everything runs on one laptop: 3 JVMs + Postgres + Redis + Prometheus + Grafana + k6 +
unrelated `minicrm-*` containers all contend for the same cores. Scaling out on a single
host adds context-switching and memory pressure, it does not add capacity. Lower p50 with
worse tail latency is exactly the signature of CPU oversubscription. This is a *good*
result to be able to explain — it is the honest version of "sub-linear scaling" the guide
asks about, and the correct next step is to state that a real measurement needs one host
per instance.

**Not yet run:** `loadtest/create.js` (the write path). Run it and add a row.

---

## 9. REMAINING WORK

Ordered by importance. Items 1–3 are what "finished" means.

### 🔴 1. Write `README.md` — the single biggest gap

This is Part M of the guide and the most valuable remaining artifact. It must contain:

- **Headline** with the real measured numbers (§8.3), not invented ones
- **Quick start** — `docker compose up -d --build`, plus the port-override note from §2.2
- **Performance section** — the results table verbatim, *plus* both anomaly explanations
  from §8.3 written out as prose. The guide is explicit that explaining an anomaly beats
  reciting a clean result.
- **Reproduce** — `./loadtest/run-matrix.sh`
- **Architecture** — a Mermaid diagram (see §4 for the shape)
- **API reference** — endpoints table. Source of truth is the controllers:
  `POST /api/keys`, `POST /api/links`, `GET /api/links`, `GET /api/links/{code}`,
  `PATCH /api/links/{code}`, `DELETE /api/links/{code}`,
  `GET /api/links/{code}/analytics?days=N`, `GET /api/links/{code}/qr?size=N`,
  `POST /api/links/{code}/unlock`, `GET /api/status`, `GET /{code}`, `POST /{code}`
- **Engineering decisions table** — mine §6, one row each with the rejected alternative
- **Known limitations** — the guide calls this "a power move". Must include:
  - API keys are unstored capability tokens; losing one loses access to the links
  - Single Redis is a SPOF for rate limiting (Sentinel/Cluster would fix it)
  - Postgres connection pool is the next bottleneck (~60 connections at 3 instances) →
    PgBouncer → read replicas → shard by code hash
  - SSRF check resolves DNS once at creation; a DNS-rebinding attacker could still move
    the address afterwards, so any future server-side fetch must re-validate
  - Snowflake-derived codes are **not walkable but are locally predictable** — two ids
    minted in the same millisecond on the same node differ by 1. Fix would be a
    format-preserving permutation of the id before Base62. (`SnowflakeIdGeneratorTest`
    asserts this honestly; do not claim non-enumerability without the caveat.)
  - Partitions are pre-created by an in-app job, not `pg_partman`
  - No CDN; edge-caching redirects would beat every application optimisation here
  - Load tests ran on one host — see anomaly 2
  - GeoIP needs a user-supplied MaxMind DB; demo fallback is synthetic and labelled

### 🔴 2. Verify the frontend in a browser

It compiles (`npm run build` → 837 modules, clean) and is served by Nginx at
`http://localhost:8090`, but **no one has actually looked at it**. Check:

- Shorten form → result card, QR image renders, copy button
- **429 handling** — set `APP_ANON_CAPACITY=3` and confirm the UI shows the friendly
  "Rate limited, try again in Ns" message. The guide calls this out specifically.
- My links → list, inline edit, delete
- Analytics page → line chart + bar charts (use a link with real clicks)
- `StatusBar` footer → stop Redis, confirm the circuit pill flips to `OPEN` live
- SPA routing: `/` and `/app/links` must serve `index.html`, while `/{code}` must reach
  the backend. This split is in `ops/nginx.conf.template` and is worth double-checking.

### 🟡 3. Screenshots + `docs/`

- Grafana dashboard **during the 1000-VU spike** — the guide says this is the single most
  persuasive image for the README. Grafana is at `http://localhost:3002` (anonymous
  viewer enabled, or admin/admin).
- Frontend screenshots
- Save to `docs/` and reference from the README.

### 🟡 4. Run the write-path load test

```bash
docker compose --profile loadtest run --rm -e BASE_URL=http://nginx -e RUN_LABEL=4-create k6 run /scripts/create.js
```

Add a row to the README. Expect roughly an order of magnitude less throughput than the
read path — validation + DNS + insert + cache write. That contrast is the point.

### 🟢 5. CI workflow

`.github/workflows/ci.yml`: checkout → JDK 21 → `mvn verify` → frontend build. Docker is
available on GitHub runners, so the Testcontainers suite will run. Add the badge to the
README.

### 🟢 6. Optional improvements

- Re-run the baseline with 100k+ seeded links so the cache result is more representative
  (see anomaly 1). Raise `APP_AUTH_CAPACITY` first or seeding takes hours.
- `git init` + first commit — **the project is not currently a git repository.**
- Consider a `Makefile` for the common commands.

---

## 10. Command reference

Always export the block from §2.2 first.

```bash
# --- build & test ---
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
mvn -B test                       # 95 tests; needs Docker for the integration suite
mvn -B package -DskipTests

# --- run the stack ---
docker compose up -d --build                                    # 1 instance
docker compose -f docker-compose.yml -f docker-compose.scale.yml up -d --build   # 3 instances
docker compose logs -f app1
docker compose down          # keep data
docker compose down -v       # wipe data (invalidates loadtest/codes.json)

# --- smoke test ---
KEY=$(curl -fsS -X POST http://localhost:8090/api/keys | python3 -c "import sys,json;print(json.load(sys.stdin)['apiKey'])")
curl -fsS -X POST http://localhost:8090/api/links \
     -H 'Content-Type: application/json' -H "X-API-Key: $KEY" \
     -d '{"url":"https://example.com/hello"}'
curl -s -o /dev/null -D - http://localhost:8090/<code>
curl -fsS http://localhost:8090/api/status

# --- load tests ---
BASE_URL=http://localhost:8090 COUNT=1000 node loadtest/seed.mjs
docker compose --profile loadtest run --rm -e BASE_URL=http://nginx -e RUN_LABEL=x k6 run /scripts/redirect.js
./loadtest/run-matrix.sh          # full 3-run matrix, exports the right limits itself

# --- frontend dev ---
cd frontend && npm install && npm run dev     # :5173, proxies /api to :8080

# --- observability ---
open http://localhost:3002        # Grafana (admin/admin)
open http://localhost:9091        # Prometheus
curl http://localhost:8091/actuator/prometheus | grep snip_
```

---

## 11. Gotchas for whoever picks this up

1. **Never run `mvn` without `JAVA_HOME` pinned to 21.**
2. **Never write `${SNIP_...}` inside a comment in `ops/nginx.conf.template`.**
3. **Don't add properties to `AbstractIntegrationTest`'s `@DynamicPropertySource`** unless
   they genuinely depend on the containers — it outranks subclass `@TestPropertySource`.
4. **The default rate limits are sized for humans, not load tests.** If a load test shows
   mass 429s or 503s, you forgot to raise `APP_*_CAPACITY` / `SNIP_EDGE_RATE`.
5. **`loadtest/codes.json` is tied to the current DB volume.** Re-seed after `down -v`.
6. **The code comments are a deliverable, not clutter.** This project's value is being
   explainable; the reasoning is written next to the code deliberately. Keep that style.
7. **Report measured numbers honestly.** Two anomalies are documented in §8.3 — explaining
   them is worth more than a clean fake table, and the user will be asked about them.
8. **The stack is currently running.** Check `docker compose ps` before assuming a clean slate.
