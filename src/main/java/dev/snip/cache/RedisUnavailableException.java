package dev.snip.cache;

/**
 * Raised by {@link RedisGuard} when a Redis operation could not be completed because
 * Redis is unreachable, timed out, or the circuit breaker is open.
 *
 * <p>This is deliberately distinct from "the key was not there". Callers must be able
 * to tell a cache miss (go read the database and repopulate) apart from a cache outage
 * (go read the database and do <em>not</em> try to repopulate).
 */
public class RedisUnavailableException extends RuntimeException {

    private final String operation;

    public RedisUnavailableException(String operation, Throwable cause) {
        super("Redis unavailable during '" + operation + "': " + cause.getClass().getSimpleName(), cause);
        this.operation = operation;
    }

    public RedisUnavailableException(String operation, String reason) {
        super("Redis unavailable during '" + operation + "': " + reason);
        this.operation = operation;
    }

    public String operation() {
        return operation;
    }
}
