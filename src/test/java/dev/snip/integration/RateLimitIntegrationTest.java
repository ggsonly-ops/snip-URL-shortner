package dev.snip.integration;

import dev.snip.ratelimit.RateLimitResult;
import dev.snip.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token bucket, against a real Redis running the real Lua script. An in-memory fake
 * would not test the thing that actually matters here, which is atomicity.
 */
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.anonymous-capacity=5",
        // 5 tokens per minute: slow enough that refill does not muddy the assertions.
        "app.rate-limit.anonymous-refill-per-minute=5",
        "app.rate-limit.authenticated-capacity=10",
        "app.rate-limit.authenticated-refill-per-minute=10",
})
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    RateLimiter limiter;

    @Autowired
    StringRedisTemplate redis;

    private String bucket() {
        return "test-" + UUID.randomUUID();
    }

    @Test
    void allowsUpToCapacityThenDenies() {
        String key = bucket();
        // capacity 3, refilling at 1 token per minute so nothing refills mid-test
        for (int i = 0; i < 3; i++) {
            RateLimitResult r = limiter.check(key, 3, 1.0 / 60);
            assertThat(r.allowed()).as("request %d should be allowed", i + 1).isTrue();
        }

        RateLimitResult denied = limiter.check(key, 3, 1.0 / 60);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterMillis()).isPositive();
        assertThat(denied.retryAfterSeconds()).isPositive();
    }

    @Test
    void reportsRemainingTokensAccurately() {
        String key = bucket();
        assertThat(limiter.check(key, 5, 1.0 / 60).remaining()).isEqualTo(4);
        assertThat(limiter.check(key, 5, 1.0 / 60).remaining()).isEqualTo(3);
        assertThat(limiter.check(key, 5, 1.0 / 60).remaining()).isEqualTo(2);
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        String key = bucket();
        // 20 tokens/second: a full bucket of 2 refills in ~100ms.
        for (int i = 0; i < 2; i++) {
            assertThat(limiter.check(key, 2, 20).allowed()).isTrue();
        }
        assertThat(limiter.check(key, 2, 20).allowed()).isFalse();

        Thread.sleep(250);
        assertThat(limiter.check(key, 2, 20).allowed())
                .as("tokens should have refilled").isTrue();
    }

    /**
     * A bucket that has been idle for a long time must hold at most {@code capacity},
     * not "however many tokens would have accrued". Without the {@code math.min} in the
     * script, an idle client would come back able to fire an unbounded burst.
     *
     * <p>The refill rate is kept low relative to the time the burst itself takes: at
     * 1000 tokens/sec a token accrues every millisecond, so the loop would out-refill
     * itself and the test would measure round-trip latency rather than the clamp.
     */
    @Test
    void neverRefillsAboveCapacity() throws InterruptedException {
        String key = bucket();
        int capacity = 3;
        double refillPerSecond = 20;

        limiter.check(key, capacity, refillPerSecond);
        Thread.sleep(500);   // would accrue 10 tokens unclamped; capacity is 3

        int allowed = 0;
        for (int i = 0; i < 20; i++) {
            if (limiter.check(key, capacity, refillPerSecond).allowed()) {
                allowed++;
            }
        }

        // 3 from the clamped bucket, plus at most a token or so accrued during the loop.
        assertThat(allowed).isBetween(capacity, capacity + 1);
    }

    /**
     * The reason the bucket is a Lua script and not three Java round trips: with
     * read-compute-write split across calls, concurrent callers both read "1 token left"
     * and both proceed. Executed atomically, the count is exact no matter the
     * concurrency.
     */
    @Test
    void isExactUnderConcurrentCallers() throws Exception {
        String key = bucket();
        final int capacity = 50;
        final int callers = 200;

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch startGun = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();

        List<Callable<Void>> tasks = java.util.Collections.nCopies(callers, () -> {
            startGun.await();
            if (limiter.check(key, capacity, 0.001).allowed()) {
                allowed.incrementAndGet();
            }
            return null;
        });

        List<Future<Void>> futures = tasks.stream().map(pool::submit).toList();
        startGun.countDown();
        for (Future<Void> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(allowed.get())
                .as("exactly the bucket capacity may pass, no more and no fewer")
                .isEqualTo(capacity);
    }

    @Test
    void idleBucketsExpireSoMemoryDoesNotGrowForever() {
        String key = bucket();
        limiter.check(key, 10, 10);
        Long ttl = redis.getExpire("snip:rl:" + key, TimeUnit.SECONDS);

        assertThat(ttl).isPositive();
        // twice the time to refill from empty to full: 10 tokens / 10 per sec = 1s -> ~2s
        assertThat(ttl).isLessThanOrEqualTo(5);
    }

    @Test
    void theFilterReturns429WithRetryAfterAndRateLimitHeaders() {
        // The anonymous write bucket is 5 in this test's property overrides.
        HttpStatus lastStatus = null;
        ResponseEntity<Map> limited = null;

        for (int i = 0; i < 25; i++) {
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> res = rest.exchange(url("/api/links"), HttpMethod.POST,
                    new HttpEntity<>(Map.of("url", "https://example.com/rl/" + i), json(null)),
                    Map.class);
            lastStatus = HttpStatus.valueOf(res.getStatusCode().value());
            if (lastStatus == HttpStatus.TOO_MANY_REQUESTS) {
                limited = res;
                break;
            }
        }

        assertThat(lastStatus).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited).isNotNull();
        assertThat(limited.getBody().get("error")).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(limited.getBody().get("retryAfterSeconds")).isNotNull();

        // A well-behaved client can self-throttle from these instead of retrying blindly.
        assertThat(limited.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(limited.getHeaders().getFirst("X-RateLimit-Limit")).isNotNull();
        assertThat(limited.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(limited.getHeaders().getFirst("X-RateLimit-Scope")).isEqualTo("write");
    }

    @Test
    void successfulResponsesAlsoCarryRateLimitHeaders() {
        ResponseEntity<Map> res = rest.exchange(url("/api/status"), HttpMethod.GET,
                new HttpEntity<>(json(null)), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getFirst("X-RateLimit-Limit")).isNotNull();
        assertThat(res.getHeaders().getFirst("X-RateLimit-Remaining")).isNotNull();
    }

    @Test
    void actuatorIsNotRateLimited() {
        // Prometheus scrapes this every 5s from inside the network; throttling it would
        // break the monitoring that reports on throttling.
        ResponseEntity<String> res = rest.getForEntity(url("/actuator/health"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getHeaders().getFirst("X-RateLimit-Limit")).isNull();
    }

    @Test
    void differentIdentitiesGetSeparateBuckets() {
        String a = bucket();
        String b = bucket();

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.check(a, 3, 0.001).allowed()).isTrue();
        }
        assertThat(limiter.check(a, 3, 0.001).allowed()).isFalse();
        // b is untouched by a's exhaustion.
        assertThat(limiter.check(b, 3, 0.001).allowed()).isTrue();
    }
}
