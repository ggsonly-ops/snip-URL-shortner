package dev.snip.ratelimit;

import dev.snip.config.SnipProperties;
import dev.snip.metrics.SnipMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies the token bucket to every request that is not an internal endpoint.
 *
 * <p>Three separate scopes, because they cost wildly different amounts. A redirect is a
 * cache read; creating a link is validation plus a DNS lookup plus an insert plus a
 * cache write. Giving them one shared budget would either throttle redirects absurdly
 * early or leave the write path unprotected.
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final ClientIpResolver clientIps;
    private final SnipMetrics metrics;
    private final SnipProperties props;

    private enum Scope {REDIRECT, WRITE, READ}

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        // Actuator is scraped by Prometheus every 5s from inside the network; rate
        // limiting it would just break the monitoring that tells us we are being
        // rate limited.
        return path.startsWith("/actuator") || path.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        SnipProperties.RateLimit cfg = props.getRateLimit();
        String apiKey = req.getHeader("X-API-Key");
        boolean authenticated = apiKey != null && !apiKey.isBlank();
        String identity = authenticated ? "key:" + apiKey : "ip:" + clientIps.resolve(req);

        Scope scope = scopeOf(req);
        int capacity;
        double refillPerSecond;
        switch (scope) {
            case REDIRECT -> {
                capacity = cfg.getRedirectCapacity();
                refillPerSecond = cfg.getRedirectRefillPerMinute() / 60.0;
            }
            case WRITE -> {
                capacity = authenticated ? cfg.getAuthenticatedCapacity() : cfg.getAnonymousCapacity();
                refillPerSecond = (authenticated
                        ? cfg.getAuthenticatedRefillPerMinute()
                        : cfg.getAnonymousRefillPerMinute()) / 60.0;
            }
            default -> {
                capacity = authenticated ? cfg.getAuthenticatedCapacity() * 2 : cfg.getAnonymousCapacity() * 2;
                refillPerSecond = (authenticated
                        ? cfg.getAuthenticatedRefillPerMinute() * 2
                        : cfg.getAnonymousRefillPerMinute() * 2) / 60.0;
            }
        }

        RateLimitResult result = limiter.check(
                scope.name().toLowerCase() + ":" + identity, capacity, refillPerSecond);

        // Returning these lets a well-behaved client self-throttle instead of retrying
        // blindly into a wall. Every serious API does it.
        res.setHeader("X-RateLimit-Limit", String.valueOf(capacity));
        res.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, result.remaining())));
        res.setHeader("X-RateLimit-Scope", scope.name().toLowerCase());

        metrics.recordRateLimit(scope.name().toLowerCase(), result.allowed() ? "allowed" : "blocked");

        if (!result.allowed()) {
            long retryAfter = Math.max(1, result.retryAfterSeconds());
            res.setStatus(429);
            res.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.setCharacterEncoding("UTF-8");
            res.getWriter().write("""
                    {"error":"RATE_LIMIT_EXCEEDED","message":"Too many requests","retryAfterSeconds":%d}"""
                    .formatted(retryAfter));
            return;
        }

        chain.doFilter(req, res);
    }

    private static Scope scopeOf(HttpServletRequest req) {
        String path = req.getRequestURI();
        if (!path.startsWith("/api/")) {
            return Scope.REDIRECT;
        }
        return switch (req.getMethod()) {
            case "POST", "PUT", "PATCH", "DELETE" -> Scope.WRITE;
            default -> Scope.READ;
        };
    }
}
