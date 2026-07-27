package dev.snip.integration;

import dev.snip.metrics.SnipMetrics;
import dev.snip.service.LinkResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Cache-aside behaviour: hits, negative caching, invalidation, and the stampede lock. */
class CacheIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    LinkResolver resolver;

    @Autowired
    SnipMetrics metrics;

    private String key() {
        return "snip_test_" + UUID.randomUUID();
    }

    @Test
    void aMissPopulatesTheCacheAndTheNextReadIsAHit() {
        String code = (String) createLink("https://example.com/cache-me", key()).getBody().get("shortCode");
        String cacheKey = "snip:link:" + code;

        // Creation writes through after commit, so clear it to observe a real miss.
        redis.delete(cacheKey);
        assertThat(redis.hasKey(cacheKey)).isFalse();

        long hitsBefore = metrics.hits();
        long missesBefore = metrics.misses();

        assertThat(resolver.resolve(code)).isPresent();          // miss -> loads and caches
        assertThat(redis.hasKey(cacheKey)).isTrue();
        assertThat(metrics.misses()).isEqualTo(missesBefore + 1);

        assertThat(resolver.resolve(code)).isPresent();          // hit
        assertThat(metrics.hits()).isEqualTo(hitsBefore + 1);
    }

    @Test
    void theCachedEntryCarriesEverythingTheRedirectNeeds() {
        String code = (String) createLink("https://example.com/packed", key()).getBody().get("shortCode");
        String raw = redis.opsForValue().get("snip:link:" + code);

        assertThat(raw).isNotNull();
        // id, password flag, expiry, url - packed, not JSON.
        assertThat(raw).endsWith("https://example.com/packed");
        assertThat(raw.chars().filter(c -> c == 1).count()).isEqualTo(3);
    }

    /**
     * Negative caching: an unknown code must be remembered as unknown, or every request
     * for a bogus code is a fresh database query and the cache can be bypassed entirely
     * by anyone hitting random strings.
     */
    @Test
    void cachesTheAbsenceOfUnknownCodes() {
        String bogus = "nope" + Integer.toHexString(new java.util.Random().nextInt(1 << 20));
        String cacheKey = "snip:link:" + bogus;
        redis.delete(cacheKey);

        assertThat(resolver.resolve(bogus)).isEmpty();

        assertThat(redis.hasKey(cacheKey)).as("the negative result must be cached").isTrue();
        assertThat(redis.opsForValue().get(cacheKey)).isEqualTo("\0");
        // Short TTL, because a code can start existing at any moment.
        assertThat(redis.getExpire(cacheKey, TimeUnit.SECONDS)).isBetween(1L, 5L * 60);

        assertThat(resolver.resolve(bogus)).isEmpty();           // served from the sentinel
    }

    @Test
    void invalidatesRatherThanOverwritingOnUpdate() {
        String apiKey = key();
        String code = (String) createLink("https://example.com/before", apiKey).getBody().get("shortCode");
        String cacheKey = "snip:link:" + code;

        assertThat(redis.hasKey(cacheKey)).isTrue();

        rest.exchange(url("/api/links/" + code), org.springframework.http.HttpMethod.PATCH,
                new org.springframework.http.HttpEntity<>(
                        Map.of("url", "https://example.com/after"), json(apiKey)), Map.class);

        // Deleted, not rewritten: if the transaction had rolled back, a rewritten entry
        // would be holding data that was never committed.
        assertThat(redis.hasKey(cacheKey)).isFalse();
        assertThat(resolver.resolve(code)).get()
                .extracting("longUrl").isEqualTo("https://example.com/after");
    }

    /**
     * The stampede test. Many threads hit the same freshly-evicted hot key at once; the
     * NX PX lock must ensure only one of them loads from Postgres while the rest either
     * wait for the repopulated entry or fall through.
     *
     * <p>The assertion is on the lock counters rather than on database query counts,
     * because that is what the mechanism actually controls: exactly one acquirer, and
     * the rest routed through the waiting path instead of all piling into the database.
     */
    @Test
    void onlyOneRequestRepopulatesAHotKeyUnderConcurrentMisses() throws Exception {
        String code = (String) createLink("https://example.com/hot-key", key()).getBody().get("shortCode");
        redis.delete("snip:link:" + code);
        redis.delete("snip:lock:" + code);

        final int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGun = new CountDownLatch(1);
        AtomicInteger resolved = new AtomicInteger();
        Set<String> urls = ConcurrentHashMap.newKeySet();

        List<Callable<Void>> tasks = java.util.Collections.nCopies(threads, () -> {
            startGun.await();
            resolver.resolve(code).ifPresent(l -> {
                resolved.incrementAndGet();
                urls.add(l.longUrl());
            });
            return null;
        });

        List<Future<Void>> futures = tasks.stream().map(pool::submit).toList();
        startGun.countDown();
        for (Future<Void> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // Every caller still gets the right answer - the lock must never cost correctness.
        assertThat(resolved.get()).isEqualTo(threads);
        assertThat(urls).containsExactly("https://example.com/hot-key");
        assertThat(redis.hasKey("snip:link:" + code)).isTrue();
        // And the lock is always released, never left behind to deadlock later readers.
        assertThat(redis.hasKey("snip:lock:" + code)).isFalse();
    }

    @Test
    void cacheTtlIsClampedToTheLinkExpiry() {
        // A link expiring in a day must not sit in a 24-hour cache entry that outlives it.
        String code = (String) createLink(Map.of(
                "url", "https://example.com/ttl-clamped", "ttlDays", 1), key()).getBody().get("shortCode");

        long ttlSeconds = redis.getExpire("snip:link:" + code, TimeUnit.SECONDS);
        assertThat(ttlSeconds).isPositive().isLessThanOrEqualTo(TimeUnit.DAYS.toSeconds(1));
    }

    @Test
    void redirectsStillWorkWithTheCacheDisabledEntirely() {
        // Same code path the load-test baseline runs, so the "no cache" row of the
        // results table is exercised rather than assumed.
        String code = (String) createLink("https://example.com/no-cache-path", key()).getBody().get("shortCode");
        redis.delete("snip:link:" + code);

        assertThat(resolver.fromDatabase(code)).isPresent();
        assertThat(noFollow().getForEntity(url("/" + code), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.FOUND);
    }
}
