package dev.snip.analytics;

import dev.snip.cache.RedisGuard;
import dev.snip.cache.RedisUnavailableException;
import dev.snip.config.SnipProperties;
import dev.snip.metrics.SnipMetrics;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.domain.Range;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumer side of the click pipeline: drains the Redis Stream and writes to Postgres
 * in batches, off the request path entirely.
 *
 * <h2>Batching</h2>
 * 500 individual inserts is 500 network round trips (~2.5s); one multi-row insert of
 * 500 rows is a single round trip (~15ms). Cutting round trips is the single biggest
 * lever in bulk write performance, and it is why this drains on a timer rather than
 * writing per event.
 *
 * <h2>Consumer groups and delivery semantics</h2>
 * A Redis Stream consumer group tracks which entries each consumer has taken and which
 * it has acknowledged. Crash mid-batch and the unacked entries stay in the Pending
 * Entries List, so this consumer reclaims them on restart and other workers reclaim
 * them after an idle timeout. That gives <b>at-least-once</b> delivery.
 *
 * <h2>At-least-once means duplicates, so this consumer is idempotent</h2>
 * If the insert commits and the process dies before the ACK, those events come back and
 * would be double counted. Two standard answers exist: accept the small error because
 * it is only analytics, or make the consumer idempotent. <b>This implementation takes
 * the second</b>, because it costs almost nothing here: the Redis Stream entry id is
 * already a stable unique key that survives redelivery, so it goes in a unique index and
 * the insert is {@code ON CONFLICT DO NOTHING}. The denormalised {@code click_count} is
 * then incremented only for the rows the insert actually created, using
 * {@code RETURNING} — incrementing from the batch size instead would reintroduce the
 * double count that the unique index just removed.
 *
 * <h2>Redis Streams vs Kafka</h2>
 * Streams give consumer groups, persistence and replay inside a dependency this service
 * already has. Kafka adds partitioning across many consumers, much longer retention and
 * a far larger ecosystem, at the cost of running Kafka. Streams is the right call at
 * this size; the switch would be justified by needing multi-consumer fan-out or
 * retention measured in weeks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickConsumer {

    private static final String INSERT_SQL_PREFIX = """
            INSERT INTO clicks (event_id, link_id, clicked_at, country, referrer, device_type, browser, os)
            VALUES """;
    private static final String INSERT_SQL_SUFFIX = " ON CONFLICT DO NOTHING RETURNING link_id";

    private final StringRedisTemplate redis;
    private final RedisGuard guard;
    private final JdbcTemplate jdbc;
    private final GeoIpService geo;
    private final UserAgentParser userAgents;
    private final SnipProperties props;
    private final SnipMetrics metrics;

    private String consumerName;
    private volatile boolean groupReady;

    @PostConstruct
    void init() {
        // Unique per instance so each replica has its own entry in the group's PEL and
        // a crash is attributable to one consumer.
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "worker";
        }
        this.consumerName = host + "-" + ProcessHandle.current().pid();
        log.info("Click consumer '{}' on stream '{}'", consumerName, props.getAnalytics().getStream());
    }

    /**
     * Creates the consumer group if it does not exist. Retried on every drain because
     * Redis may not have been up at startup, and a FLUSHALL can remove the group under
     * a running app.
     */
    private boolean ensureGroup() {
        if (groupReady) {
            return true;
        }
        SnipProperties.Analytics cfg = props.getAnalytics();
        try {
            guard.run("clicks.ensureGroup", () -> {
                try {
                    // MKSTREAM equivalent: creates the stream too if it is absent.
                    redis.opsForStream().createGroup(cfg.getStream(), ReadOffset.from("0"), cfg.getConsumerGroup());
                } catch (RuntimeException e) {
                    if (!isBusyGroup(e)) {
                        throw e;
                    }
                    // group already exists, which is the normal case
                }
            });
            groupReady = true;
            return true;
        } catch (RedisUnavailableException e) {
            log.debug("Cannot create consumer group yet: {}", e.getMessage());
            return false;
        }
    }

    private static boolean isBusyGroup(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    @Scheduled(fixedDelayString = "${app.analytics.drain-interval-ms:1000}")
    public void drain() {
        if (!props.getAnalytics().isEnabled() || !ensureGroup()) {
            return;
        }
        try {
            // Our own unacked entries first. On restart after a crash this is where the
            // in-flight batch comes back from.
            processBatch(ReadOffset.from("0"));

            // Then new entries, looping while the stream keeps handing back full batches
            // so a backlog drains quickly instead of one batch per tick.
            for (int pass = 0; pass < 20; pass++) {
                int processed = processBatch(ReadOffset.lastConsumed());
                if (processed < props.getAnalytics().getBatchSize()) {
                    break;
                }
            }
        } catch (RedisUnavailableException e) {
            groupReady = false;
            log.debug("Redis unavailable during click drain: {}", e.getMessage());
        } catch (DataAccessException e) {
            // Leave the entries unacked; they will be redelivered and the insert is
            // idempotent, so retrying costs nothing but time.
            log.warn("Failed to persist click batch, entries stay pending: {}", e.getMessage());
        }
    }

    private int processBatch(ReadOffset offset) {
        SnipProperties.Analytics cfg = props.getAnalytics();

        List<MapRecord<String, Object, Object>> records = guard.call("clicks.read", () ->
                redis.opsForStream().read(
                        Consumer.from(cfg.getConsumerGroup(), consumerName),
                        StreamReadOptions.empty().count(cfg.getBatchSize()),
                        StreamOffset.create(cfg.getStream(), offset)));

        if (records == null || records.isEmpty()) {
            return 0;
        }

        persist(records);

        // ACK only after the write is durable. Acking first would turn a crash into
        // silent data loss instead of a redelivery.
        RecordId[] ids = records.stream().map(MapRecord::getId).toArray(RecordId[]::new);
        guard.tryRun("clicks.ack", () -> redis.opsForStream().acknowledge(cfg.getStream(), cfg.getConsumerGroup(), ids));

        return records.size();
    }

    private void persist(List<MapRecord<String, Object, Object>> records) {
        List<Object[]> rows = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            Object[] row = toRow(record);
            if (row != null) {
                rows.add(row);
            }
        }
        if (rows.isEmpty()) {
            return;
        }

        // One statement with N value tuples. 8 params per row, so the 500-row default
        // is 4000 bind parameters - comfortably under Postgres's 32767 limit, but the
        // reason batchSize is configurable.
        StringBuilder sql = new StringBuilder(INSERT_SQL_PREFIX);
        for (int i = 0; i < rows.size(); i++) {
            sql.append(i == 0 ? "" : ",").append("(?,?,?,?,?,?,?,?)");
        }
        sql.append(INSERT_SQL_SUFFIX);

        Object[] params = new Object[rows.size() * 8];
        int p = 0;
        for (Object[] row : rows) {
            System.arraycopy(row, 0, params, p, 8);
            p += 8;
        }

        // RETURNING gives back only the rows the insert actually created, so a
        // redelivered duplicate contributes nothing to the counters.
        List<Long> insertedLinkIds = jdbc.query(sql.toString(),
                (rs, rowNum) -> rs.getLong(1), params);

        if (insertedLinkIds.isEmpty()) {
            log.debug("Batch of {} click events was entirely duplicate; nothing to count", rows.size());
            return;
        }

        Map<Long, Long> counts = new LinkedHashMap<>();
        for (Long id : insertedLinkIds) {
            counts.merge(id, 1L, Long::sum);
        }
        bumpClickCounts(counts);
        metrics.clicksPersisted(insertedLinkIds.size());
        log.debug("Persisted {} click events ({} duplicates skipped)",
                insertedLinkIds.size(), rows.size() - insertedLinkIds.size());
    }

    /**
     * Applies every per-link increment in one statement by unnesting two parallel
     * arrays, rather than issuing one UPDATE per link.
     */
    private void bumpClickCounts(Map<Long, Long> counts) {
        StringBuilder ids = new StringBuilder("{");
        StringBuilder ns = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<Long, Long> e : counts.entrySet()) {
            if (!first) {
                ids.append(',');
                ns.append(',');
            }
            ids.append(e.getKey());
            ns.append(e.getValue());
            first = false;
        }
        ids.append('}');
        ns.append('}');

        jdbc.update("""
                UPDATE links SET click_count = links.click_count + c.n
                FROM (SELECT unnest(?::bigint[]) AS id, unnest(?::bigint[]) AS n) c
                WHERE links.id = c.id
                """, ids.toString(), ns.toString());
    }

    private Object[] toRow(MapRecord<String, Object, Object> record) {
        Map<Object, Object> v = record.getValue();
        String linkIdRaw = str(v.get("linkId"));
        if (linkIdRaw == null) {
            return null;
        }
        long linkId;
        long at;
        try {
            linkId = Long.parseLong(linkIdRaw);
            String atRaw = str(v.get("at"));
            at = atRaw == null ? System.currentTimeMillis() : Long.parseLong(atRaw);
        } catch (NumberFormatException e) {
            log.warn("Discarding malformed click event {}", record.getId());
            return null;
        }

        String ip = str(v.get("ip"));
        UserAgentParser.Parsed ua = userAgents.parse(str(v.get("userAgent")));
        String referrer = trimToNull(str(v.get("referrer")), 500);

        return new Object[]{
                record.getId().getValue(),          // event_id - stable across redelivery
                linkId,
                Timestamp.from(Instant.ofEpochMilli(at)),
                geo.countryOf(ip),
                referrer,
                ua.deviceType(),
                ua.browser(),
                ua.os()
        };
    }

    /**
     * Reclaims entries that another consumer took and never acknowledged - i.e. a worker
     * that died. Without this, that worker's in-flight batch sits in the PEL forever.
     */
    @Scheduled(fixedDelayString = "${app.analytics.reclaim-interval-ms:30000}")
    public void reclaimStale() {
        if (!props.getAnalytics().isEnabled() || !groupReady) {
            return;
        }
        SnipProperties.Analytics cfg = props.getAnalytics();
        try {
            PendingMessages pending = guard.call("clicks.pending", () ->
                    redis.opsForStream().pending(cfg.getStream(),
                            cfg.getConsumerGroup(), Range.unbounded(), cfg.getBatchSize()));

            if (pending == null || pending.isEmpty()) {
                metrics.streamPending(0);
                return;
            }
            metrics.streamPending(pending.size());

            Duration idleThreshold = cfg.getReclaimIdleAfter();
            List<RecordId> stale = new ArrayList<>();
            for (PendingMessage m : pending) {
                if (!m.getConsumerName().equals(consumerName)
                        && m.getElapsedTimeSinceLastDelivery().compareTo(idleThreshold) > 0) {
                    stale.add(m.getId());
                }
            }
            if (stale.isEmpty()) {
                return;
            }

            log.info("Reclaiming {} click events abandoned by a dead consumer", stale.size());
            // XCLAIM with a min-idle guard: if the original owner became live again and
            // acked in the meantime, the entry is no longer idle and we do not steal it.
            List<MapRecord<String, Object, Object>> claimed = guard.call("clicks.claim", () ->
                    redis.opsForStream().claim(cfg.getStream(), cfg.getConsumerGroup(), consumerName,
                            idleThreshold, stale.toArray(new RecordId[0])));

            if (claimed != null && !claimed.isEmpty()) {
                persist(claimed);
                RecordId[] ids = claimed.stream().map(MapRecord::getId).toArray(RecordId[]::new);
                guard.tryRun("clicks.ackReclaimed",
                        () -> redis.opsForStream().acknowledge(cfg.getStream(), cfg.getConsumerGroup(), ids));
            }
        } catch (RedisUnavailableException e) {
            log.debug("Redis unavailable during PEL reclaim");
        } catch (DataAccessException e) {
            log.warn("Failed to persist reclaimed clicks: {}", e.getMessage());
        }
    }

    /**
     * Caps the stream so a stalled consumer cannot grow it until Redis hits its memory
     * limit and starts evicting. Approximate trimming is used because it is far cheaper
     * and the exact length does not matter.
     */
    @Scheduled(fixedDelayString = "${app.analytics.trim-interval-ms:60000}")
    public void trimStream() {
        if (!props.getAnalytics().isEnabled()) {
            return;
        }
        SnipProperties.Analytics cfg = props.getAnalytics();
        guard.tryRun("clicks.trim",
                () -> redis.opsForStream().trim(cfg.getStream(), cfg.getMaxStreamLength(), true));
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString();
        return s.isEmpty() ? null : s;
    }

    private static String trimToNull(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Test seam: the map form used by the ad-hoc backfill in integration tests. */
    Map<String, String> describe() {
        Map<String, String> m = new HashMap<>();
        m.put("consumer", consumerName);
        m.put("stream", props.getAnalytics().getStream());
        m.put("group", props.getAnalytics().getConsumerGroup());
        return m;
    }
}
