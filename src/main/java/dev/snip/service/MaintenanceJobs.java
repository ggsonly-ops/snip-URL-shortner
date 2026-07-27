package dev.snip.service;

import dev.snip.cache.LinkCache;
import dev.snip.cache.ShortCodeBloomFilter;
import dev.snip.config.SnipProperties;
import dev.snip.domain.ResolvedLink;
import dev.snip.repository.LinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Background upkeep: cache warming, bloom rebuilds, expiry sweeping and partition
 * pre-creation. None of it is on the request path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaintenanceJobs {

    private final LinkRepository repo;
    private final LinkCache cache;
    private final ShortCodeBloomFilter bloom;
    private final JdbcTemplate jdbc;
    private final SnipProperties props;

    /**
     * Cache warming.
     *
     * <p>Without it, the first minute after every deploy is 100% cache misses, so p99
     * spikes at exactly the moment traffic comes back. Loading the hottest links first
     * matters because link traffic is heavily power-law distributed — a small number of
     * links account for most requests, so a thousand rows buys most of the hit ratio.
     *
     * <p>Runs asynchronously so a slow warm never delays the instance becoming healthy
     * and joining the load balancer.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void warmCache() {
        if (!props.getCache().isEnabled() || !props.getCache().isWarmOnStartup()) {
            return;
        }
        try {
            long start = System.currentTimeMillis();
            List<dev.snip.domain.Link> hottest =
                    repo.findHottest(Instant.now(), props.getCache().getWarmSize());
            hottest.forEach(l -> cache.put(l.getShortCode(), ResolvedLink.of(l)));
            log.info("Warmed cache with {} links in {}ms", hottest.size(), System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            log.warn("Cache warming failed (continuing anyway): {}", e.toString());
        }
    }

    /**
     * Rebuilds the bloom filter whenever its readiness marker is missing — first boot,
     * a Redis restart, a FLUSHALL. Until the rebuild completes the filter is bypassed,
     * so a missing filter costs performance and never correctness.
     */
    @Scheduled(initialDelayString = "${app.bloom.rebuild-initial-delay-ms:5000}",
            fixedDelayString = "${app.bloom.rebuild-check-interval-ms:60000}")
    public void rebuildBloomIfNeeded() {
        if (!bloom.isEnabled() || !cache.isUp() || bloom.isReady()) {
            return;
        }
        try {
            long start = System.currentTimeMillis();
            List<String> codes = repo.findAllActiveShortCodes();
            bloom.addAll(codes);
            bloom.markReady(codes.size());
            log.info("Rebuilt short-code bloom filter with {} codes in {}ms",
                    codes.size(), System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            log.warn("Bloom filter rebuild failed, will retry: {}", e.toString());
        }
    }

    /**
     * Deactivates links whose TTL has passed. Resolution already filters on expiry, so
     * this is housekeeping rather than correctness — but it keeps the partial indexes
     * small and lets the cache entries be dropped promptly.
     */
    @Scheduled(fixedDelayString = "${app.expiry-sweep-interval-ms:300000}")
    @Transactional
    public void sweepExpired() {
        Instant now = Instant.now();
        List<String> codes = repo.findExpiredShortCodes(now);
        if (codes.isEmpty()) {
            return;
        }
        int updated = repo.deactivateExpired(now);
        cache.evictAll(codes);
        log.info("Expired {} links", updated);
    }

    /**
     * Keeps a rolling window of future monthly partitions on the clicks table.
     *
     * <p>This is the operational risk that partitioning creates and that is worth naming
     * before anyone asks: with no partition covering the current month, every click
     * INSERT fails at midnight on the 1st. Production would use pg_partman; a scheduled
     * job is the same idea with one fewer extension to install.
     */
    @Scheduled(initialDelay = 10_000, fixedDelayString = "${app.partition-check-interval-ms:21600000}")
    public void ensureFuturePartitions() {
        LocalDate month = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        try {
            for (int i = 0; i <= 12; i++) {
                jdbc.queryForObject("SELECT ensure_click_partition(?::date)", String.class,
                        month.plusMonths(i).toString());
            }
            log.debug("Click partitions verified through {}", month.plusMonths(12));
        } catch (RuntimeException e) {
            log.error("Failed to ensure click partitions - click inserts may fail at month end", e);
        }
    }
}
