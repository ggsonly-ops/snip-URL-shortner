package dev.snip.web;

import dev.snip.cache.RedisGuard;
import dev.snip.cache.ShortCodeBloomFilter;
import dev.snip.config.SnipProperties;
import dev.snip.id.SnowflakeIdGenerator;
import dev.snip.metrics.SnipMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single endpoint that says what this instance is doing right now.
 *
 * <p>Exists mostly for the demo: when Redis is stopped mid-load-test, this is what shows
 * the circuit breaker opening and the cache-hit ratio flatlining while redirects keep
 * being served from Postgres.
 */
@RestController
@RequestMapping("/api/status")
@RequiredArgsConstructor
public class StatusController {

    private final SnowflakeIdGenerator ids;
    private final RedisGuard redis;
    private final ShortCodeBloomFilter bloom;
    private final SnipMetrics metrics;
    private final SnipProperties props;

    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("enabled", props.getCache().isEnabled());
        cache.put("redisCircuit", redis.state().name());
        cache.put("hits", metrics.hits());
        cache.put("misses", metrics.misses());
        cache.put("unavailable", metrics.unavailable());
        cache.put("hitRatio", Math.round(metrics.hitRatio() * 10_000) / 10_000.0);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("machineId", ids.machineId());
        out.put("idsPerSecondPerNode", (SnowflakeIdGenerator.MAX_SEQUENCE + 1) * 1000);
        out.put("cache", cache);
        out.put("bloom", Map.of(
                "enabled", bloom.isEnabled(),
                "ready", bloom.isReady()));
        out.put("rateLimitEnabled", props.getRateLimit().isEnabled());
        out.put("analyticsEnabled", props.getAnalytics().isEnabled());
        return out;
    }
}
