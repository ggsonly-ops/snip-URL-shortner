package dev.snip.ratelimit;

/**
 * @param allowed         whether the request may proceed
 * @param remaining       tokens left in the bucket after this request
 * @param retryAfterMillis how long until enough tokens exist, 0 when allowed
 * @param degraded        true when Redis was unreachable and the decision was a policy
 *                        default rather than a real measurement
 */
public record RateLimitResult(boolean allowed, long remaining, long retryAfterMillis, boolean degraded) {

    public static RateLimitResult allow(long remaining) {
        return new RateLimitResult(true, remaining, 0, false);
    }

    public static RateLimitResult degradedAllow(long capacity) {
        return new RateLimitResult(true, capacity, 0, true);
    }

    public static RateLimitResult degradedDeny() {
        return new RateLimitResult(false, 0, 1000, true);
    }

    public long retryAfterSeconds() {
        return (long) Math.ceil(retryAfterMillis / 1000.0);
    }
}
