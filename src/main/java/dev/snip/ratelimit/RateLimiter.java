package dev.snip.ratelimit;

import dev.snip.cache.RedisGuard;
import dev.snip.cache.RedisUnavailableException;
import dev.snip.config.SnipProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Distributed token-bucket limiter backed by a Redis Lua script.
 *
 * <h2>Why token bucket, of the four options</h2>
 * <table>
 *   <caption>algorithm comparison</caption>
 *   <tr><td>Fixed window</td><td>one counter per (key, minute)</td>
 *       <td>boundary burst: 100 requests at 11:59:59 and 100 more at 12:00:01 are both
 *       "legal" and produce 200 in two seconds</td></tr>
 *   <tr><td>Sliding window log</td><td>timestamp per request</td>
 *       <td>exact, but memory grows with traffic</td></tr>
 *   <tr><td>Sliding window counter</td><td>weighted blend of two windows</td>
 *       <td>cheap and no boundary burst, but approximate</td></tr>
 *   <tr><td>Token bucket</td><td>bucket refills at rate r, one token per request</td>
 *       <td>allows a controlled burst, which here is a feature</td></tr>
 * </table>
 *
 * <p>Token bucket wins because legitimate users are bursty: someone pastes ten links at
 * once and then goes quiet for an hour. A strict per-second cap punishes that entirely
 * normal behaviour, while a bucket absorbs the burst up to capacity and still holds the
 * long-run average to the refill rate.
 *
 * <h2>Why the logic is in Lua and not here</h2>
 * A token bucket is a read-compute-write. In Java that is three round trips, and another
 * instance can interleave between them — two requests both read "1 token left" and both
 * proceed. Redis runs a Lua script as a single indivisible operation, so the whole
 * read-compute-write cannot be interleaved. That is the correctness argument and the
 * entire reason the script exists.
 *
 * <p>Because the bucket lives in Redis and not in a JVM, all instances share it: three
 * app replicas enforce one limit, not three. That is exactly what you want, and it is
 * why an in-process limiter (Guava/Bucket4j local) would be the wrong choice here.
 */
@Slf4j
@Component
public class RateLimiter {

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script;
    private final SnipProperties.RateLimit cfg;

    @SuppressWarnings("rawtypes")
    public RateLimiter(StringRedisTemplate redis,
                       RedisGuard guard,
                       RedisScript<List> tokenBucketScript,
                       SnipProperties props) {
        this.redis = redis;
        this.guard = guard;
        this.script = tokenBucketScript;
        this.cfg = props.getRateLimit();
    }

    /**
     * @param key             bucket identity, e.g. {@code redirect:ip:1.2.3.4}
     * @param capacity        maximum tokens the bucket holds (the burst allowance)
     * @param refillPerSecond sustained rate
     */
    public RateLimitResult check(String key, int capacity, double refillPerSecond) {
        if (!cfg.isEnabled()) {
            return RateLimitResult.allow(capacity);
        }
        try {
            @SuppressWarnings("unchecked")
            List<Long> result = (List<Long>) guard.call("ratelimit.check", () ->
                    redis.execute(script,
                            List.of("snip:rl:" + key),
                            String.valueOf(capacity),
                            String.valueOf(refillPerSecond),
                            String.valueOf(System.currentTimeMillis()),
                            "1"));

            if (result == null || result.size() < 3) {
                log.warn("Unexpected token bucket reply: {}", result);
                return RateLimitResult.degradedAllow(capacity);
            }
            boolean allowed = result.get(0) == 1L;
            return new RateLimitResult(allowed, result.get(1), result.get(2), false);

        } catch (RedisUnavailableException e) {
            // Fail open or fail closed is a genuine design decision, not an oversight.
            //
            // Fail open keeps the service available but removes the protection, so a
            // Redis outage plus an abusive client can reach Postgres unthrottled.
            // Fail closed protects the database but makes Redis a hard dependency of
            // every request - the cache becoming a single point of failure, which is
            // precisely what the rest of this service is built to avoid.
            //
            // The default here is fail open: redirects are the product, and Postgres
            // still has its own connection pool as a backstop. It is configurable
            // because the right answer differs for a write-heavy or billed API.
            if (cfg.isFailOpen()) {
                log.debug("Rate limiter degraded (Redis down), failing open for {}", key);
                return RateLimitResult.degradedAllow(capacity);
            }
            log.warn("Rate limiter degraded (Redis down), failing closed for {}", key);
            return RateLimitResult.degradedDeny();
        }
    }
}
