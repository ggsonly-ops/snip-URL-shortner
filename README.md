# Snip — Distributed URL Shortener

Snip is a high-performance, distributed URL shortener built to demonstrate scalable engineering patterns. It features Snowflake IDs, cache-aside with stampede protection, atomic Redis rate limiting, and asynchronous click analytics.

## 🚀 Quick Start

```bash
# 1. Export required port mappings to avoid host conflicts
export SNIP_HTTP_PORT=8090 SNIP_APP1_PORT=8091 SNIP_APP2_PORT=8092 SNIP_APP3_PORT=8093 \
       SNIP_DB_PORT=55432 SNIP_REDIS_PORT=56379 \
       SNIP_PROMETHEUS_PORT=9091 SNIP_GRAFANA_PORT=3002 \
       APP_BASE_URL=http://localhost:8090

# 2. Start the 3-instance scaled stack
docker compose -f docker-compose.yml -f docker-compose.scale.yml up -d --build
```
The frontend will be available at `http://localhost:8090`.
Grafana (for metrics) will be at `http://localhost:3002` (admin/admin).

## 📊 Performance & Load Testing

The system is designed to handle thousands of requests per second. Real load test results (1000 VUs, Zipf distribution) run on a single host (3 app instances, PostgreSQL, Redis, Nginx, Prometheus, Grafana, and k6 running concurrently on one machine):

| Configuration | Throughput | p50 | p95 | p99 |
|---|---|---|---|---|
| **Read: 1 instance, no cache** | 13,318 req/s | 45.02 ms | 110.73 ms | 165.79 ms |
| **Read: 1 instance, Redis** | 16,384 req/s | 37.73 ms | 84.82 ms | 127.61 ms |
| **Read: 3 instances, Redis** | 8,357 req/s | 17.38 ms | 268.65 ms | 528.04 ms |
| **Write: 3 instances, Redis** | 4,726 req/s | 4.89 ms | 10.54 ms | 19.72 ms |

**Important Anomalies Explained:**
1. **Cache Gain (~1.23x instead of ~3x)**: The read test was run with a 1,000-link working set. PostgreSQL easily fit these rows into `shared_buffers` (256MB), making the "no cache" baseline essentially an in-memory lookup over a local socket. Redis here saves JDBC connection pool contention and serialization overhead, not disk I/O. A larger dataset (e.g., 100k+ links) exceeding `shared_buffers` would demonstrate a more dramatic difference.
2. **Sub-linear Scaling on Single Host**: 3 instances processed fewer requests per second (8.3k vs 16.4k) than 1 instance, despite the median latency (p50) dropping by half (17ms vs 38ms). This is the classic signature of single-host CPU oversubscription. Running 3 JVMs plus all auxiliary services on the same laptop causes context-switching and memory pressure to dominate throughput capacity. Proper scaling requires one physical or virtual host per instance.

### Reproducing Load Tests
```bash
./loadtest/run-matrix.sh
```

## 🏗 Architecture

```mermaid
graph TD
    client([Client]) -->|least_conn, keepalive| Nginx[Nginx :80]
    
    Nginx --> App1[App1 :8080]
    Nginx --> App2[App2 :8080]
    Nginx --> App3[App3 :8080]
    
    App1 --> Redis[(Redis)]
    App2 --> Redis
    App3 --> Redis
    
    App1 --> Postgres[(PostgreSQL)]
    App2 --> Postgres
    App3 --> Postgres
    
    Redis -.->|@Scheduled Batch Consumer| Postgres
```
*Prometheus scrapes `/actuator/prometheus` on all app instances, visualized by Grafana.*

## 🔌 API Reference

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/keys` | Mint a new API key (capability token) |
| `POST` | `/api/links` | Create a new shortlink |
| `GET`  | `/api/links` | List all links for the current API key |
| `GET`  | `/api/links/{code}` | Get details for a specific link |
| `PATCH`| `/api/links/{code}` | Update a link (e.g., target URL) |
| `DELETE`| `/api/links/{code}` | Delete a shortlink |
| `GET`  | `/api/links/{code}/analytics` | Retrieve click analytics (time series & dimensions) |
| `GET`  | `/api/links/{code}/qr` | Generate a QR code PNG for the link |
| `POST` | `/api/links/{code}/unlock` | Unlock a password-protected link |
| `GET`  | `/api/status` | Live system health (cache, circuit breaker, bloom filter) |
| `GET`  | `/{code}` | Redirect to the original URL (302) |
| `POST` | `/{code}` | Submit password for protected redirect |

## 🛠 Engineering Decisions

| Feature / Pattern | Decision | Reasoning |
|---|---|---|
| **Primary Key** | Snowflake ID | A single Snowflake ID serves as both the database PK and the source for the Base62 short code, ensuring uniqueness without burning IDs or requiring an extra sequence column. |
| **Redirection** | 302 Found | Analytics is a core feature. A 301 Moved Permanently would cache the destination in browsers, making repeat clicks invisible to the system. |
| **Cache Writing** | After Commit | Cache updates run via `TransactionSynchronizationManager` after the DB transaction commits. Inline writes would risk leaving the cache holding rolled-back data on failure. |
| **Click Processing** | Async Stream | Clicks are published to a Redis Stream and processed in bulk. The stream consumer uses `ON CONFLICT DO NOTHING` for idempotency using the stream ID as a uniqueness constraint. |
| **Rate Limiting** | Fail-Open | The token bucket rate limiter fails open if Redis is unreachable. Redirects are prioritized, falling back to the DB connection pool as a natural backstop. |

## ⚠️ Known Limitations
- **API Keys are unstored capability tokens:** If you lose your API key, you lose access to manage your links.
- **Single Redis SPOF:** Redis is a Single Point of Failure for rate-limiting and caching. Sentinel or Cluster mode would mitigate this.
- **Database Bottleneck:** At 3 instances, the PostgreSQL connection pool (~60 connections) becomes the next bottleneck. Real-world scaling would require PgBouncer and read replicas.
- **DNS Rebinding:** SSRF checks resolve DNS at creation time, but an attacker could alter DNS records later.
- **Snowflake Enumerability:** Snowflake IDs minted in the same millisecond on the same node differ by 1, making them locally predictable. A format-preserving permutation could solve this.
- **Partitions:** Monthly click partitions are pre-created by an in-app job, rather than `pg_partman`.
- **GeoIP Fallback:** A real MaxMind DB is required for accurate GeoIP; the default fallback is synthetic for demonstration purposes.
