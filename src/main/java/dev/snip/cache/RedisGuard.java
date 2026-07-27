package dev.snip.cache;

import dev.snip.config.SnipProperties;
import dev.snip.metrics.SnipMetrics;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The single place where "Redis might be down" is handled.
 *
 * <p>A cache that turns into a single point of failure is worse than no cache, so
 * every Redis call in the application goes through here. Two things happen:
 *
 * <ol>
 *   <li><b>Classification.</b> Connection failures and timeouts become
 *       {@link RedisUnavailableException}; anything else (a bad Lua script, a type
 *       error) is a real bug and propagates untouched rather than being silently
 *       swallowed as "cache down".</li>
 *   <li><b>Circuit breaking.</b> Without it, every request pays the full connection
 *       timeout while Redis is down — the cache outage becomes a latency outage. After
 *       a handful of failures the breaker opens and calls fail instantly for 30s, then
 *       a few probe calls decide whether to close it again.</li>
 * </ol>
 */
@Slf4j
@Component
public class RedisGuard {

    private final CircuitBreaker breaker;
    private final SnipMetrics metrics;

    public RedisGuard(SnipProperties props, SnipMetrics metrics) {
        this.metrics = metrics;
        SnipProperties.Cache cfg = props.getCache();
        this.breaker = CircuitBreaker.of("redis", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(Math.max(10, cfg.getCircuitFailureThreshold() * 2))
                .minimumNumberOfCalls(cfg.getCircuitFailureThreshold())
                .failureRateThreshold(50f)
                .waitDurationInOpenState(cfg.getCircuitOpenDuration())
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // Only transport-level failures count against the breaker.
                .recordExceptions(RedisUnavailableException.class)
                .build());

        breaker.getEventPublisher().onStateTransition(e -> {
            log.warn("Redis circuit breaker: {} -> {}",
                    e.getStateTransition().getFromState(), e.getStateTransition().getToState());
            metrics.recordRedisCircuit(e.getStateTransition().getToState().name());
        });
    }

    /** True when the breaker would currently let a call through. */
    public boolean isUp() {
        return breaker.getState() != CircuitBreaker.State.OPEN
                && breaker.getState() != CircuitBreaker.State.FORCED_OPEN;
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }

    /**
     * Runs a Redis operation. Throws {@link RedisUnavailableException} if Redis is
     * unreachable or the circuit is open; any other exception propagates as-is.
     */
    public <T> T call(String operation, Supplier<T> action) {
        try {
            return breaker.executeSupplier(() -> {
                try {
                    return action.get();
                } catch (RuntimeException e) {
                    if (isTransportFailure(e)) {
                        throw new RedisUnavailableException(operation, e);
                    }
                    throw e;
                }
            });
        } catch (CallNotPermittedException e) {
            throw new RedisUnavailableException(operation, "circuit breaker is open");
        }
    }

    public void run(String operation, Runnable action) {
        call(operation, () -> {
            action.run();
            return null;
        });
    }

    /** Best-effort variant for paths where a cache failure must never surface: returns the fallback. */
    public <T> T callOrDefault(String operation, Supplier<T> action, T fallback) {
        try {
            return call(operation, action);
        } catch (RedisUnavailableException e) {
            log.debug("Redis unavailable during {}, using fallback", operation);
            return fallback;
        }
    }

    /** Best-effort fire-and-forget. Returns false if the write did not happen. */
    public boolean tryRun(String operation, Runnable action) {
        try {
            run(operation, action);
            return true;
        } catch (RedisUnavailableException e) {
            log.debug("Redis unavailable during {}, dropping write", operation);
            return false;
        }
    }

    /**
     * Distinguishes "Redis is unreachable" from "Redis said no".
     * The distinction is what keeps a scripting bug from being reported as an outage.
     */
    private static boolean isTransportFailure(RuntimeException e) {
        if (e instanceof RedisConnectionFailureException
                || e instanceof QueryTimeoutException
                || e instanceof DataAccessResourceFailureException) {
            return true;
        }
        if (e instanceof RedisSystemException) {
            // Lettuce wraps connection loss and command timeouts in here too.
            for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
                if (t instanceof IOException
                        || t instanceof SocketException
                        || t instanceof TimeoutException
                        || t instanceof io.lettuce.core.RedisConnectionException
                        || t instanceof io.lettuce.core.RedisCommandTimeoutException) {
                    return true;
                }
            }
        }
        return false;
    }

    void recordUnavailable() {
        metrics.cacheUnavailable();
    }
}
