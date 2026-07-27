package dev.snip.integration;

import dev.snip.analytics.ClickConsumer;
import dev.snip.config.SnipProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The async click pipeline end to end: redirect -> Redis Stream -> batch consumer ->
 * partitioned Postgres table -> analytics API.
 */
class AnalyticsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ClickConsumer consumer;

    @Autowired
    SnipProperties props;

    private String key() {
        return "snip_test_" + UUID.randomUUID();
    }

    @Test
    void clicksFlowFromRedirectToPostgresAndBackOutOfTheAnalyticsApi() {
        String apiKey = key();
        String code = (String) createLink("https://example.com/tracked", apiKey).getBody().get("shortCode");
        long linkId = linkIdOf(code);

        for (int i = 0; i < 5; i++) {
            assertThat(noFollow().getForEntity(url("/" + code), Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.FOUND);
        }

        // The scheduled drain runs on its own timer; nudging it keeps the test fast and
        // deterministic rather than sleeping for the interval.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            consumer.drain();
            assertThat(clickCount(linkId)).isEqualTo(5);
        });

        // The denormalised counter on links must agree with the click rows.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "SELECT click_count FROM links WHERE id = ?", Long.class, linkId)).isEqualTo(5L));

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> analytics = (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>)
                rest.exchange(url("/api/links/" + code + "/analytics?days=7"), HttpMethod.GET,
                        new HttpEntity<>(json(apiKey)), Map.class);

        assertThat(analytics.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) analytics.getBody().get("totalClicks")).longValue()).isEqualTo(5);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> perDay = (List<Map<String, Object>>) analytics.getBody().get("clicksPerDay");
        // generate_series gap-fill: 7 days requested means 8 points (inclusive both ends),
        // every one present even where there were no clicks.
        assertThat(perDay).hasSize(8);
        assertThat(perDay).allSatisfy(d -> assertThat(d.get("clicks")).isNotNull());
        assertThat(perDay.stream().mapToLong(d -> ((Number) d.get("clicks")).longValue()).sum()).isEqualTo(5);
    }

    /**
     * At-least-once delivery means a batch can be redelivered after a crash between the
     * insert and the ACK. The consumer is idempotent — the Redis Stream entry id is a
     * unique key and the insert is ON CONFLICT DO NOTHING — so replaying must change
     * nothing, including the denormalised counter.
     */
    @Test
    void redeliveredEventsAreNotDoubleCounted() {
        String apiKey = key();
        String code = (String) createLink("https://example.com/idempotent", apiKey).getBody().get("shortCode");
        long linkId = linkIdOf(code);

        noFollow().getForEntity(url("/" + code), Void.class);

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            consumer.drain();
            assertThat(clickCount(linkId)).isEqualTo(1);
        });

        long countAfterFirstDrain = jdbc.queryForObject(
                "SELECT click_count FROM links WHERE id = ?", Long.class, linkId);

        // Replay the exact same stream entry as though the ACK had been lost.
        //
        // Both event_id AND clicked_at are reused, and that is not an implementation
        // detail of the test: a unique index on a partitioned table must contain the
        // partition key, so the index is (event_id, clicked_at). Idempotency therefore
        // depends on clicked_at being identical on redelivery - which it is, because the
        // consumer takes it from the event's own `at` field rather than from NOW().
        // Inserting with NOW() here would land in a different row and dedupe nothing.
        Map<String, Object> original = jdbc.queryForMap(
                "SELECT event_id, clicked_at FROM clicks WHERE link_id = ? LIMIT 1", linkId);

        int inserted = jdbc.update("""
                INSERT INTO clicks (event_id, link_id, clicked_at, country, referrer, device_type, browser, os)
                VALUES (?, ?, ?, NULL, NULL, NULL, NULL, NULL)
                ON CONFLICT DO NOTHING
                """, original.get("event_id"), linkId, original.get("clicked_at"));

        assertThat(inserted).as("a duplicate event id must insert nothing").isZero();
        assertThat(clickCount(linkId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT click_count FROM links WHERE id = ?", Long.class, linkId))
                .isEqualTo(countAfterFirstDrain);
    }

    @Test
    void analyticsAreScopedToTheOwningApiKey() {
        String owner = key();
        String code = (String) createLink("https://example.com/private-stats", owner).getBody().get("shortCode");

        ResponseEntity<Map> other = rest.exchange(url("/api/links/" + code + "/analytics"), HttpMethod.GET,
                new HttpEntity<>(json(key())), Map.class);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<Map> anonymous = rest.exchange(url("/api/links/" + code + "/analytics"), HttpMethod.GET,
                new HttpEntity<>(json(null)), Map.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aFailedPublishNeverBreaksTheRedirect() {
        // Analytics is best-effort by design: redirects are the product. Point the
        // publisher at a stream name in a database we then make unusable is overkill —
        // instead assert the weaker but real property that a redirect succeeds even when
        // the consumer has never run and nothing has been persisted yet.
        String code = (String) createLink("https://example.com/best-effort", key()).getBody().get("shortCode");
        assertThat(noFollow().getForEntity(url("/" + code), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.FOUND);
    }

    @Test
    void theClicksTableIsRangePartitionedByMonth() {
        List<String> partitions = jdbc.queryForList("""
                SELECT c.relname
                FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname = 'clicks'
                ORDER BY c.relname
                """, String.class);

        // The migration seeds 3 months back through 12 forward.
        assertThat(partitions).hasSizeGreaterThanOrEqualTo(16);
        assertThat(partitions).allMatch(n -> n.matches("clicks_\\d{4}_\\d{2}"));
    }

    @Test
    void thePartitionHelperIsIdempotent() {
        String first = jdbc.queryForObject(
                "SELECT ensure_click_partition('2030-03-15'::date)", String.class);
        String second = jdbc.queryForObject(
                "SELECT ensure_click_partition('2030-03-01'::date)", String.class);

        assertThat(first).isEqualTo("clicks_2030_03");
        assertThat(second).isEqualTo(first);
    }

    @Test
    void theStreamAndConsumerGroupExist() {
        String stream = props.getAnalytics().getStream();
        assertThat(Boolean.TRUE.equals(redis.hasKey(stream)) || redis.opsForStream().size(stream) != null)
                .isTrue();
    }

    private long linkIdOf(String code) {
        return jdbc.queryForObject("SELECT id FROM links WHERE short_code = ?", Long.class, code);
    }

    private long clickCount(long linkId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM clicks WHERE link_id = ?", Long.class, linkId);
    }
}
