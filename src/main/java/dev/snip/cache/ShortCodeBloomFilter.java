package dev.snip.cache;

import dev.snip.config.SnipProperties;
import dev.snip.metrics.SnipMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bloom filter over every live short code, held as a Redis bit array.
 *
 * <p><b>What it buys.</b> A crawler or a scanner hitting random codes produces a flood
 * of lookups for things that do not exist. Negative caching already stops those from
 * reaching Postgres twice, but each distinct bad code still costs one database read
 * and one cache write. The bloom filter answers "definitely not here" for almost all
 * of them in a single Redis command against ~1MB of memory, whatever the table size.
 *
 * <p><b>Why it is safe.</b> A bloom filter has <em>no false negatives</em>: if a code
 * was inserted, membership always returns true. A false positive just means we fall
 * through to the normal lookup path and find nothing, so correctness never depends on
 * the filter being right — only performance does.
 *
 * <p><b>The failure mode that would break it</b>, and how it is handled. If the filter
 * were empty (Redis flushed, first boot) while links existed, every real code would be
 * reported absent and we would 404 valid links. So the filter is only consulted once a
 * full rebuild has completed and stamped a readiness marker alongside the bit array;
 * if the marker is missing the filter is bypassed entirely and a background check
 * rebuilds it. Both keys live and die together in Redis, so they cannot disagree.
 *
 * <p>Deletions are not supported (clearing bits would create false negatives for other
 * members). A deleted code simply stays a false positive until the next rebuild, which
 * costs one wasted lookup.
 */
@Slf4j
@Component
public class ShortCodeBloomFilter {

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final SnipMetrics metrics;
    private final RedisScript<Long> addScript;
    private final RedisScript<Long> checkScript;
    private final SnipProperties.Bloom cfg;

    private final String bitsKey;
    private final String readyKey;

    public ShortCodeBloomFilter(StringRedisTemplate redis,
                                RedisGuard guard,
                                SnipMetrics metrics,
                                RedisScript<Long> bloomAddScript,
                                RedisScript<Long> bloomCheckScript,
                                SnipProperties props) {
        this.redis = redis;
        this.guard = guard;
        this.metrics = metrics;
        this.addScript = bloomAddScript;
        this.checkScript = bloomCheckScript;
        this.cfg = props.getBloom();
        this.bitsKey = cfg.getKey();
        this.readyKey = cfg.getKey() + ":ready";
    }

    public boolean isEnabled() {
        return cfg.isEnabled();
    }

    /** True once a full rebuild has completed and the marker is still present in Redis. */
    public boolean isReady() {
        if (!cfg.isEnabled()) {
            return false;
        }
        return Boolean.TRUE.equals(guard.callOrDefault("bloom.ready",
                () -> redis.hasKey(readyKey), Boolean.FALSE));
    }

    /**
     * @return false only when the code is <em>definitely</em> absent. true means
     * "maybe present, go and look properly" — including whenever the filter is
     * disabled, not ready, or Redis is unreachable, so a broken filter degrades to
     * simply not being used.
     */
    public boolean mightContain(String code) {
        if (!cfg.isEnabled()) {
            return true;
        }
        Long present = guard.callOrDefault("bloom.check",
                () -> redis.execute(checkScript, List.of(bitsKey), offsets(code)), 1L);
        boolean maybe = present == null || present == 1L;
        if (!maybe) {
            metrics.bloomReject();
        }
        return maybe;
    }

    public void add(String code) {
        if (!cfg.isEnabled()) {
            return;
        }
        guard.tryRun("bloom.add", () -> redis.execute(addScript, List.of(bitsKey), offsets(code)));
    }

    /** Bulk load used by the rebuild. Chunked so no single pipeline gets enormous. */
    public void addAll(List<String> codes) {
        if (!cfg.isEnabled() || codes.isEmpty()) {
            return;
        }
        for (String code : codes) {
            add(code);
        }
    }

    /** Stamps the readiness marker. Called only after a rebuild has loaded every live code. */
    public void markReady(long cardinality) {
        if (!cfg.isEnabled()) {
            return;
        }
        guard.tryRun("bloom.markReady", () -> redis.opsForValue().set(readyKey, String.valueOf(cardinality)));
        metrics.bloomCardinality(cardinality);
    }

    public void clear() {
        guard.tryRun("bloom.clear", () -> redis.delete(List.of(bitsKey, readyKey)));
    }

    /**
     * Derives k bit positions from two 64-bit hashes (Kirsch-Mitzenmacher):
     * {@code h_i = h1 + i*h2}. Gives k independent-enough positions for the price of
     * two hash computations rather than k.
     */
    private Object[] offsets(String code) {
        byte[] data = code.getBytes(StandardCharsets.UTF_8);
        long h1 = fnv1a64(data, 0xcbf29ce484222325L);
        long h2 = fnv1a64(data, 0x9e3779b97f4a7c15L) | 1L;   // odd, so it never degenerates to 0

        List<Object> out = new ArrayList<>(cfg.getHashes());
        for (int i = 0; i < cfg.getHashes(); i++) {
            long combined = h1 + (long) i * h2;
            out.add(Long.toString(Math.floorMod(combined, cfg.getBits())));
        }
        return out.toArray();
    }

    private static long fnv1a64(byte[] data, long seed) {
        long hash = seed;
        for (byte b : data) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
