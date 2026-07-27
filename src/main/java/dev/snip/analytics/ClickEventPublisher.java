package dev.snip.analytics;

import dev.snip.cache.RedisGuard;
import dev.snip.config.SnipProperties;
import dev.snip.metrics.SnipMetrics;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Producer side of the click pipeline.
 *
 * <p><b>The principle: never do slow work on the request path.</b> A redirect should be
 * a cache read and a 302 — target under 10ms. Writing the analytics row synchronously
 * would add a database insert (5-20ms) to every single redirect, doubling or tripling
 * the latency of the product's core operation for data that nobody reads in real time.
 * An XADD is roughly 0.2ms and never touches Postgres.
 *
 * <p><b>The catch block is a design decision, not laziness.</b> Analytics is best-effort;
 * redirects are the product. If Redis is down users still get where they are going and
 * we lose some click data. That priority ordering is deliberate and stated.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickEventPublisher {

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final SnipProperties props;
    private final SnipMetrics metrics;

    public void publishAsync(long linkId, HttpServletRequest req, String clientIp) {
        if (!props.getAnalytics().isEnabled()) {
            return;
        }

        Map<String, String> event = new HashMap<>(8);
        event.put("linkId", Long.toString(linkId));
        event.put("at", Long.toString(System.currentTimeMillis()));
        event.put("ip", Objects.toString(clientIp, ""));
        event.put("referrer", truncate(req.getHeader("Referer"), 500));
        event.put("userAgent", truncate(req.getHeader("User-Agent"), 512));

        boolean ok = guard.tryRun("clicks.publish", () ->
                redis.opsForStream().add(
                        StreamRecords.mapBacked(event).withStreamKey(props.getAnalytics().getStream())));

        if (ok) {
            metrics.clickPublished();
        } else {
            metrics.clickDropped();
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
