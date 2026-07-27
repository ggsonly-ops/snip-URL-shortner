package dev.snip.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom application metrics, on top of what Actuator exposes for free.
 *
 * <p>Metric type matters and is a common follow-up question: a <b>counter</b> only
 * ever increases (total requests); a <b>gauge</b> moves in both directions (tokens
 * left, pending stream entries); a <b>timer/histogram</b> records a distribution so
 * percentiles can be computed.
 *
 * <p>The redirect timer publishes a percentile <em>histogram</em>, not pre-computed
 * percentiles, because you cannot average percentiles — the mean of three instances'
 * p95 is not the fleet p95. Exporting bucket counts lets Prometheus compute a true
 * fleet-wide quantile with {@code histogram_quantile()} over the summed buckets.
 */
@Component
@RequiredArgsConstructor
public class SnipMetrics {

    private final MeterRegistry registry;

    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong cacheUnavailable = new AtomicLong();
    private final AtomicLong streamPending = new AtomicLong();
    private final AtomicLong bloomCardinality = new AtomicLong();

    private Counter cacheHitCounter;
    private Counter cacheMissCounter;
    private Counter cacheUnavailableCounter;
    private Counter stampedeLockAcquired;
    private Counter stampedeLockWaited;
    private Counter stampedeLockFellThrough;
    private Counter bloomRejects;
    private Counter clicksPublished;
    private Counter clicksDropped;
    private Counter clicksPersisted;

    @PostConstruct
    void init() {
        cacheHitCounter = counter("snip.cache.result", "outcome", "hit");
        cacheMissCounter = counter("snip.cache.result", "outcome", "miss");
        cacheUnavailableCounter = counter("snip.cache.result", "outcome", "unavailable");

        stampedeLockAcquired = counter("snip.stampede.lock", "outcome", "acquired");
        stampedeLockWaited = counter("snip.stampede.lock", "outcome", "waited");
        stampedeLockFellThrough = counter("snip.stampede.lock", "outcome", "fell_through");

        bloomRejects = counter("snip.bloom.reject", "outcome", "definitely_absent");

        clicksPublished = counter("snip.clicks.published", "outcome", "ok");
        clicksDropped = counter("snip.clicks.published", "outcome", "dropped");
        clicksPersisted = counter("snip.clicks.persisted", "outcome", "ok");

        Gauge.builder("snip.cache.hit_ratio", this, SnipMetrics::hitRatio)
                .description("Redis cache hit ratio over the process lifetime (0..1)")
                .register(registry);

        Gauge.builder("snip.analytics.stream.pending", streamPending, AtomicLong::get)
                .description("Entries in the click stream's pending-entries list")
                .register(registry);

        Gauge.builder("snip.bloom.cardinality", bloomCardinality, AtomicLong::get)
                .description("Short codes inserted into the bloom filter")
                .register(registry);
    }

    private Counter counter(String name, String tagKey, String tagValue) {
        return Counter.builder(name).tag(tagKey, tagValue).register(registry);
    }

    // -- cache ---------------------------------------------------------------

    public void cacheHit() {
        cacheHits.incrementAndGet();
        cacheHitCounter.increment();
    }

    public void cacheMiss() {
        cacheMisses.incrementAndGet();
        cacheMissCounter.increment();
    }

    public void cacheUnavailable() {
        cacheUnavailable.incrementAndGet();
        cacheUnavailableCounter.increment();
    }

    public double hitRatio() {
        long hits = cacheHits.get();
        long total = hits + cacheMisses.get();
        return total == 0 ? 0.0 : (double) hits / total;
    }

    public long hits() {
        return cacheHits.get();
    }

    public long misses() {
        return cacheMisses.get();
    }

    public long unavailable() {
        return cacheUnavailable.get();
    }

    // -- stampede lock -------------------------------------------------------

    public void stampedeLockAcquired() {
        stampedeLockAcquired.increment();
    }

    public void stampedeLockWaited() {
        stampedeLockWaited.increment();
    }

    public void stampedeLockFellThrough() {
        stampedeLockFellThrough.increment();
    }

    // -- bloom ---------------------------------------------------------------

    public void bloomReject() {
        bloomRejects.increment();
    }

    public void bloomCardinality(long value) {
        bloomCardinality.set(value);
    }

    // -- analytics -----------------------------------------------------------

    public void clickPublished() {
        clicksPublished.increment();
    }

    public void clickDropped() {
        clicksDropped.increment();
    }

    public void clicksPersisted(int n) {
        clicksPersisted.increment(n);
    }

    public void streamPending(long n) {
        streamPending.set(n);
    }

    // -- request path --------------------------------------------------------

    /** outcome is one of: hit, miss, notfound, expired, protected, degraded. */
    public void recordRedirect(String outcome, long micros) {
        Timer.builder("snip.redirect")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry)
                .record(micros, TimeUnit.MICROSECONDS);
    }

    /** decision is allowed or blocked; scope is redirect, write or read. */
    public void recordRateLimit(String scope, String decision) {
        Counter.builder("snip.ratelimit.decision")
                .tag("scope", scope)
                .tag("decision", decision)
                .register(registry)
                .increment();
    }

    public void recordRedisCircuit(String state) {
        Counter.builder("snip.redis.circuit")
                .tag("state", state)
                .register(registry)
                .increment();
    }
}
