package dev.snip.id;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Twitter-style Snowflake ID generator.
 *
 * <pre>
 *  1 bit    41 bits              10 bits        12 bits
 * ┌───┬──────────────────────┬─────────────┬──────────────┐
 * │ 0 │  timestamp (ms)      │ machine id  │  sequence    │
 * └───┴──────────────────────┴─────────────┴──────────────┘
 *  unused   ~69 years          1024 nodes    4096 per ms
 * </pre>
 *
 * <ul>
 *   <li><b>Sign bit unused</b> so the value stays a positive Java {@code long};
 *       Base62 encoding and numeric comparison both depend on that.</li>
 *   <li><b>41 bits of milliseconds</b> = 2^41 ms ≈ 69.7 years from the custom epoch.
 *       The epoch is 2025-01-01, not 1970, so the whole range is spent on the future.</li>
 *   <li><b>10 bits of machine id</b> = 1024 nodes, each of which must be distinct.</li>
 *   <li><b>12 bits of sequence</b> = 4096 ids per millisecond per node
 *       = 4.096M ids/second/node.</li>
 * </ul>
 *
 * <p>The property that makes this the right answer for a multi-instance service:
 * <b>no coordination</b>. There is no shared counter, no network round trip and no
 * lock outside this process. Uniqueness falls out of the structure — two nodes can
 * never collide because their machine-id bits differ, and within one node the
 * (timestamp, sequence) pair is unique by construction. Ids are also roughly
 * time-ordered, which gives good B-tree insert locality on the primary key.
 *
 * <p>Uses a {@link ReentrantLock} rather than {@code synchronized} so the generator
 * does not pin a carrier thread when the app runs on virtual threads.
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {

    /** 2025-01-01T00:00:00Z. */
    public static final long EPOCH = 1735689600000L;

    public static final long MACHINE_ID_BITS = 10L;
    public static final long SEQUENCE_BITS = 12L;

    public static final long MAX_MACHINE_ID = (1L << MACHINE_ID_BITS) - 1;   // 1023
    public static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;       // 4095

    public static final long MACHINE_SHIFT = SEQUENCE_BITS;                        // 12
    public static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + MACHINE_ID_BITS;    // 22

    /**
     * How far backwards the wall clock may jump before we refuse to generate.
     * Below this we simply wait the drift out; above it we fail loudly, because
     * silently emitting ids we have already issued is far worse than an outage.
     */
    private static final long MAX_TOLERATED_BACKWARD_DRIFT_MS = 5_000L;

    private final long machineId;
    private final LongSupplier clock;
    private final ReentrantLock lock = new ReentrantLock();

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    /**
     * {@code @Autowired} is required, not decorative: the package-private constructor
     * below exists so tests can drive a fake clock, and with two candidates Spring has
     * no way to choose.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SnowflakeIdGenerator(MachineIdProvider machineIdProvider) {
        this(machineIdProvider.machineId(), System::currentTimeMillis);
    }

    /** Visible for testing: lets a test drive a fake clock. */
    SnowflakeIdGenerator(long machineId, LongSupplier clock) {
        if (machineId < 0 || machineId > MAX_MACHINE_ID) {
            throw new IllegalArgumentException("machineId must be 0.." + MAX_MACHINE_ID + ", got " + machineId);
        }
        this.machineId = machineId;
        this.clock = clock;
        log.info("Snowflake generator ready: machineId={}, epoch={}, capacity={} ids/sec/node",
                machineId, EPOCH, (MAX_SEQUENCE + 1) * 1000);
    }

    public long machineId() {
        return machineId;
    }

    public long nextId() {
        lock.lock();
        try {
            long now = clock.getAsLong();

            if (now < lastTimestamp) {
                // The wall clock moved backwards: NTP step correction, VM live-migration,
                // or a host suspend/resume. Two-tier response.
                long drift = lastTimestamp - now;
                if (drift > MAX_TOLERATED_BACKWARD_DRIFT_MS) {
                    throw new ClockMovedBackwardsException(drift);
                }
                log.warn("Clock moved backwards by {}ms; waiting it out", drift);
                now = spinUntil(lastTimestamp);
            }

            if (now == lastTimestamp) {
                sequence = (sequence + 1) & MAX_SEQUENCE;
                if (sequence == 0) {
                    // All 4096 slots for this millisecond are used; roll to the next one.
                    now = spinUntil(lastTimestamp + 1);
                }
            } else {
                sequence = 0L;
            }

            lastTimestamp = now;

            long elapsed = now - EPOCH;
            if (elapsed < 0) {
                throw new IllegalStateException("System clock is before the Snowflake epoch (" + EPOCH + ")");
            }
            if (elapsed >= (1L << 41)) {
                throw new IllegalStateException("Snowflake timestamp range exhausted; the epoch needs rolling");
            }

            return (elapsed << TIMESTAMP_SHIFT)
                    | (machineId << MACHINE_SHIFT)
                    | sequence;
        } finally {
            lock.unlock();
        }
    }

    private long spinUntil(long target) {
        long ts = clock.getAsLong();
        while (ts < target) {
            Thread.onSpinWait();
            ts = clock.getAsLong();
        }
        return ts;
    }

    // -- decoding helpers, used by tests and the /api/links/{code}/inspect debug view --

    public static long timestampOf(long id) {
        return (id >>> TIMESTAMP_SHIFT) + EPOCH;
    }

    public static long machineIdOf(long id) {
        return (id >>> MACHINE_SHIFT) & MAX_MACHINE_ID;
    }

    public static long sequenceOf(long id) {
        return id & MAX_SEQUENCE;
    }

    /** Thrown when the clock jumped backwards far enough that waiting is not reasonable. */
    public static class ClockMovedBackwardsException extends IllegalStateException {
        public ClockMovedBackwardsException(long driftMillis) {
            super("Clock moved backwards by " + driftMillis + "ms; refusing to generate ids");
        }
    }
}
