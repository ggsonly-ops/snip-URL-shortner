package dev.snip.service;

import dev.snip.cache.LinkCache;
import dev.snip.cache.RedisUnavailableException;
import dev.snip.cache.ShortCodeBloomFilter;
import dev.snip.config.SnipProperties;
import dev.snip.domain.Link;
import dev.snip.domain.ResolvedLink;
import dev.snip.metrics.SnipMetrics;
import dev.snip.repository.LinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The read path. Everything here is on the redirect hot path, so it is the part that
 * the load-test numbers actually measure.
 *
 * <pre>
 *         ┌── bloom says "definitely absent" ──────────────► 404, no I/O past Redis
 * GET ──► │
 *         ├── Redis hit ──────────────────────────────────► return
 *         ├── Redis negative hit ─────────────────────────► 404
 *         ├── Redis miss ──► [stampede lock] ──► Postgres ─► SETEX ──► return
 *         └── Redis down ──────────────────────► Postgres ─────────► return (degraded)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LinkResolver {

    private final LinkRepository repo;
    private final LinkCache cache;
    private final ShortCodeBloomFilter bloom;
    private final SnipMetrics metrics;
    private final SnipProperties props;

    /**
     * @param link    the resolved link, or null if there is nothing to redirect to
     * @param outcome how the answer was reached; becomes a metric tag
     */
    public record Resolution(ResolvedLink link, String outcome) {
        public static Resolution notFound(String outcome) {
            return new Resolution(null, outcome);
        }

        public boolean found() {
            return link != null;
        }

        public Optional<ResolvedLink> asOptional() {
            return Optional.ofNullable(link);
        }
    }

    public Optional<ResolvedLink> resolve(String code) {
        return resolveWithOutcome(code).asOptional();
    }

    public Resolution resolveWithOutcome(String code) {
        if (!props.getCache().isEnabled()) {
            // The load-test baseline runs in this mode so the "no cache" row of the
            // results table is a genuine measurement, not an estimate.
            return fromDatabase(code).map(l -> new Resolution(l, "nocache"))
                    .orElseGet(() -> Resolution.notFound("notfound"));
        }

        // 1. Bloom filter: one Redis command rules out almost every bogus code.
        //    Only trusted once a rebuild has completed, because an empty filter would
        //    otherwise report every real code as absent.
        if (bloom.isReady() && !bloom.mightContain(code)) {
            return Resolution.notFound("bloom_miss");
        }

        // 2. Cache.
        LinkCache.Lookup lookup;
        try {
            lookup = cache.get(code);
        } catch (RedisUnavailableException e) {
            // Graceful degradation: slower, but alive. A cache that becomes a single
            // point of failure is worse than no cache at all.
            metrics.cacheUnavailable();
            log.warn("Redis unavailable, serving {} straight from Postgres", code);
            return fromDatabase(code).map(l -> new Resolution(l, "degraded"))
                    .orElseGet(() -> Resolution.notFound("degraded_notfound"));
        }

        switch (lookup.status()) {
            case HIT -> {
                metrics.cacheHit();
                ResolvedLink link = lookup.link();
                if (link.isExpired(Instant.now())) {
                    // TTL clamping should prevent this, but a clock skew between the
                    // writer and Redis could let one through. Treat it as gone and
                    // clear the entry rather than serving a dead link.
                    cache.evict(code);
                    return Resolution.notFound("expired");
                }
                return new Resolution(link, "hit");
            }
            case NEGATIVE_HIT -> {
                metrics.cacheHit();
                return Resolution.notFound("negative_hit");
            }
            default -> {
                metrics.cacheMiss();
                return loadWithStampedeProtection(code);
            }
        }
    }

    /**
     * Cache stampede (thundering herd, dogpile): a hot link's entry expires and in the
     * next 50ms several thousand concurrent requests all miss and all query Postgres
     * for the identical row. A 20-connection pool saturates instantly, requests queue,
     * latency spikes, and a bad enough herd takes the database down.
     *
     * <p>Fix: a short distributed lock so exactly one request repopulates and the rest
     * wait briefly for the result.
     *
     * <p>Worth naming as an alternative: probabilistic early expiration (XFetch), where
     * each reader independently refreshes with a probability that rises as the TTL runs
     * out, so the entry is renewed <em>before</em> it expires and nobody ever misses.
     * That avoids the lock entirely at the cost of some redundant refreshes.
     */
    private Resolution loadWithStampedeProtection(String code) {
        String token = UUID.randomUUID().toString();

        if (cache.acquireLock(code, token)) {
            metrics.stampedeLockAcquired();
            try {
                Optional<ResolvedLink> loaded = fromDatabase(code);
                if (loaded.isPresent()) {
                    cache.put(code, loaded.get());
                    return new Resolution(loaded.get(), "miss");
                }
                cache.putNegative(code);
                return Resolution.notFound("notfound");
            } finally {
                cache.releaseLock(code, token);
            }
        }

        // Someone else holds the lock and is loading. Back off briefly and re-read.
        metrics.stampedeLockWaited();
        SnipProperties.Cache cfg = props.getCache();
        for (int i = 0; i < cfg.getLockMaxRetries(); i++) {
            if (!sleep(cfg.getLockBackoff().toMillis())) {
                break;
            }
            try {
                LinkCache.Lookup retry = cache.get(code);
                if (retry.status() == LinkCache.Status.HIT) {
                    return new Resolution(retry.link(), "miss_waited");
                }
                if (retry.status() == LinkCache.Status.NEGATIVE_HIT) {
                    return Resolution.notFound("notfound");
                }
            } catch (RedisUnavailableException e) {
                break;
            }
        }

        // The lock holder died, or is slower than our patience. Correctness beats cache
        // purity: a slow request is better than a failed one, so query the database
        // ourselves rather than erroring.
        metrics.stampedeLockFellThrough();
        return fromDatabase(code).map(l -> new Resolution(l, "miss_fellthrough"))
                .orElseGet(() -> Resolution.notFound("notfound"));
    }

    @Transactional(readOnly = true)
    public Optional<ResolvedLink> fromDatabase(String code) {
        Instant now = Instant.now();
        return repo.findByShortCodeAndActiveTrue(code)
                .filter(l -> l.isResolvable(now))
                .map(ResolvedLink::of);
    }

    /** Full entity load, used by the password challenge — never on the plain redirect path. */
    @Transactional(readOnly = true)
    public Optional<Link> loadEntity(String code) {
        return repo.findByShortCodeAndActiveTrue(code).filter(l -> l.isResolvable(Instant.now()));
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
