package dev.snip.cache;

import dev.snip.config.SnipProperties;
import dev.snip.domain.ResolvedLink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Redis primitives for the link cache. Serialisation, key naming and the stampede
 * lock live here; the policy (when to read, when to lock, when to give up) lives in
 * {@code LinkResolver}.
 *
 * <p>Values are stored as a packed string rather than JSON:
 * {@code <id> SEP <0|1 password> SEP <expiryMillis or "-"> SEP <url>} where SEP is
 * U+0001. A control character cannot legally appear in a URI, so the split is
 * unambiguous, and skipping a JSON parse keeps a few microseconds off every redirect.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LinkCache {

    private static final char SEP = 1;          // U+0001, illegal inside a URI
    /** Sentinel for "this code is known not to exist". Cannot collide with a real URL. */
    private static final String NEGATIVE = String.valueOf((char) 0);

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final SnipProperties props;

    public enum Status {HIT, NEGATIVE_HIT, MISS}

    public record Lookup(Status status, ResolvedLink link) {
        public static final Lookup MISS = new Lookup(Status.MISS, null);
        public static final Lookup NEGATIVE_RESULT = new Lookup(Status.NEGATIVE_HIT, null);

        public static Lookup hit(ResolvedLink link) {
            return new Lookup(Status.HIT, link);
        }

        public boolean isMiss() {
            return status == Status.MISS;
        }
    }

    private String key(String code) {
        return "snip:link:" + code;
    }

    private String lockKey(String code) {
        return "snip:lock:" + code;
    }

    /** @throws RedisUnavailableException if Redis cannot be reached */
    public Lookup get(String code) {
        String raw = guard.call("cache.get", () -> redis.opsForValue().get(key(code)));
        if (raw == null) {
            return Lookup.MISS;
        }
        if (NEGATIVE.equals(raw)) {
            return Lookup.NEGATIVE_RESULT;
        }
        ResolvedLink decoded = decode(raw);
        return decoded == null ? Lookup.MISS : Lookup.hit(decoded);
    }

    /**
     * Caches a resolved link. The TTL is clamped to the link's own remaining lifetime,
     * so an expiring link can never outlive its expiry inside the cache.
     */
    public void put(String code, ResolvedLink link) {
        Duration ttl = props.getCache().getTtl();
        if (link.expiresAt() != null) {
            Duration remaining = Duration.between(Instant.now(), link.expiresAt());
            if (remaining.isNegative() || remaining.isZero()) {
                return;
            }
            if (remaining.compareTo(ttl) < 0) {
                ttl = remaining;
            }
        }
        Duration finalTtl = ttl;
        guard.tryRun("cache.put", () -> redis.opsForValue().set(key(code), encode(link), finalTtl));
    }

    /**
     * Negative caching. Without it, every request for a code that does not exist is a
     * database query, so anyone hitting random codes bypasses the cache entirely - a
     * trivially exploitable amplification. The TTL is short because a code can start
     * existing at any moment.
     */
    public void putNegative(String code) {
        guard.tryRun("cache.putNegative",
                () -> redis.opsForValue().set(key(code), NEGATIVE, props.getCache().getNegativeTtl()));
    }

    /**
     * Invalidate rather than overwrite. If we wrote the new value and the surrounding
     * transaction then rolled back, the cache would hold data that was never committed;
     * deleting means the next read repopulates from whatever actually committed.
     */
    public void evict(String code) {
        guard.tryRun("cache.evict", () -> redis.delete(key(code)));
    }

    public void evictAll(List<String> codes) {
        if (codes.isEmpty()) {
            return;
        }
        guard.tryRun("cache.evictAll", () -> redis.delete(codes.stream().map(this::key).toList()));
    }

    /**
     * {@code SET key <token> NX PX 3000} - acquire-if-absent plus expiry in one atomic
     * command. Doing it as EXISTS then SET then EXPIRE is a race (two callers can both
     * see "absent"), and without the expiry a crashed holder leaves the lock held
     * forever and every later request for that code deadlocks behind it.
     *
     * @return true if this caller now owns the lock
     */
    public boolean acquireLock(String code, String token) {
        Boolean acquired = guard.callOrDefault("cache.lock",
                () -> redis.opsForValue().setIfAbsent(lockKey(code), token, props.getCache().getLockTtl()),
                Boolean.FALSE);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * Releases only if we still hold it. Compare-and-delete matters: if our work
     * overran the lock TTL the lock has already been handed to someone else, and a
     * blind DEL would release <em>their</em> lock.
     *
     * <p>The check and the delete are two commands here, so this is not strictly
     * atomic - the fully correct form is a small Lua script. The residual window is
     * one round trip and the cost of losing it is one extra database read, so it is
     * not worth a second script; a lock protecting something expensive would need one.
     */
    public void releaseLock(String code, String token) {
        guard.tryRun("cache.unlock", () -> {
            String holder = redis.opsForValue().get(lockKey(code));
            if (token.equals(holder)) {
                redis.delete(lockKey(code));
            }
        });
    }

    public boolean isUp() {
        return guard.isUp();
    }

    // -- codec ---------------------------------------------------------------

    static String encode(ResolvedLink link) {
        return new StringBuilder(link.longUrl().length() + 40)
                .append(link.id()).append(SEP)
                .append(link.passwordProtected() ? '1' : '0').append(SEP)
                .append(link.expiresAt() == null ? "-" : Long.toString(link.expiresAt().toEpochMilli())).append(SEP)
                .append(link.longUrl())
                .toString();
    }

    static ResolvedLink decode(String raw) {
        int a = raw.indexOf(SEP);
        int b = a < 0 ? -1 : raw.indexOf(SEP, a + 1);
        int c = b < 0 ? -1 : raw.indexOf(SEP, b + 1);
        if (a < 0 || b < 0 || c < 0) {
            log.warn("Discarding malformed cache entry");
            return null;
        }
        try {
            long id = Long.parseLong(raw, 0, a, 10);
            boolean protectedLink = raw.charAt(a + 1) == '1';
            String expiryPart = raw.substring(b + 1, c);
            Instant expiresAt = "-".equals(expiryPart) ? null : Instant.ofEpochMilli(Long.parseLong(expiryPart));
            return new ResolvedLink(id, raw.substring(c + 1), protectedLink, expiresAt);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            log.warn("Discarding undecodable cache entry");
            return null;
        }
    }
}
