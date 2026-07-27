package dev.snip.id;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnowflakeIdGeneratorTest {

    private static SnowflakeIdGenerator generator(long machineId) {
        return new SnowflakeIdGenerator(machineId, System::currentTimeMillis);
    }

    @Test
    void idsArePositiveAndMonotonicOnOneThread() {
        SnowflakeIdGenerator gen = generator(7);
        long previous = -1;
        for (int i = 0; i < 100_000; i++) {
            long id = gen.nextId();
            assertThat(id).isPositive();
            assertThat(id).isGreaterThan(previous);
            previous = id;
        }
    }

    @Test
    void bitLayoutDecodesBackToItsParts() {
        SnowflakeIdGenerator gen = generator(511);
        long before = System.currentTimeMillis();
        long id = gen.nextId();
        long after = System.currentTimeMillis();

        assertThat(SnowflakeIdGenerator.machineIdOf(id)).isEqualTo(511);
        assertThat(SnowflakeIdGenerator.sequenceOf(id)).isBetween(0L, SnowflakeIdGenerator.MAX_SEQUENCE);
        assertThat(SnowflakeIdGenerator.timestampOf(id)).isBetween(before, after);
    }

    /** The property the whole scheme rests on: 8 threads, one node, zero duplicates. */
    @Test
    void isUniqueUnderEightConcurrentThreads() throws Exception {
        final int threads = 8;
        final int perThread = 25_000;
        SnowflakeIdGenerator gen = generator(3);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGun = new CountDownLatch(1);

        List<Callable<Set<Long>>> tasks = Collections.nCopies(threads, () -> {
            startGun.await();
            Set<Long> local = new HashSet<>(perThread * 2);
            for (int i = 0; i < perThread; i++) {
                local.add(gen.nextId());
            }
            return local;
        });

        List<Future<Set<Long>>> futures = tasks.stream().map(pool::submit).toList();
        startGun.countDown();

        Set<Long> all = ConcurrentHashMap.newKeySet();
        int total = 0;
        for (Future<Set<Long>> f : futures) {
            Set<Long> part = f.get(60, TimeUnit.SECONDS);
            assertThat(part).hasSize(perThread);   // no duplicates within a thread either
            total += part.size();
            all.addAll(part);
        }
        pool.shutdownNow();

        assertThat(all).as("every id across all threads must be distinct").hasSize(total);
        assertThat(total).isEqualTo(threads * perThread);
    }

    /** Different machine ids can never collide, which is what removes the need to coordinate. */
    @Test
    void differentMachineIdsNeverCollide() {
        SnowflakeIdGenerator a = generator(1);
        SnowflakeIdGenerator b = generator(2);

        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            assertThat(ids.add(a.nextId())).isTrue();
            assertThat(ids.add(b.nextId())).isTrue();
        }
    }

    @Test
    void rejectsOutOfRangeMachineIds() {
        assertThatThrownBy(() -> generator(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generator(1024)).isInstanceOf(IllegalArgumentException.class);
        assertThat(generator(1023).machineId()).isEqualTo(1023);
    }

    /**
     * Sequence exhaustion: pinning the clock to a single millisecond means the 4097th id
     * cannot be issued until the clock advances. The generator must spin, not wrap and
     * emit a duplicate.
     */
    @Test
    void waitsForTheNextMillisecondWhenTheSequenceIsExhausted() {
        AtomicLong fakeClock = new AtomicLong(SnowflakeIdGenerator.EPOCH + 1_000_000);
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(9, fakeClock::get);

        Set<Long> ids = new HashSet<>();
        for (int i = 0; i <= SnowflakeIdGenerator.MAX_SEQUENCE; i++) {
            assertThat(ids.add(gen.nextId())).as("id %d must be new", i).isTrue();
        }
        assertThat(ids).hasSize((int) SnowflakeIdGenerator.MAX_SEQUENCE + 1);

        // The 4097th would block forever on a frozen clock, so let the clock move and
        // confirm the generator picks up in the next millisecond rather than repeating.
        fakeClock.incrementAndGet();
        long next = gen.nextId();
        assertThat(ids).doesNotContain(next);
        assertThat(SnowflakeIdGenerator.sequenceOf(next)).isZero();
    }

    /**
     * Clock skew, tier one: a small backwards jump is waited out. NTP step corrections
     * and VM live-migration both do this.
     */
    @Test
    void waitsOutSmallBackwardClockDrift() {
        AtomicLong clock = new AtomicLong(SnowflakeIdGenerator.EPOCH + 5_000_000);
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(4, clock::get);

        long first = gen.nextId();
        clock.addAndGet(-50);        // clock steps back 50ms

        // The generator spins until the clock passes lastTimestamp again; simulate the
        // clock recovering from another thread.
        Thread ticker = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                clock.incrementAndGet();
                Thread.onSpinWait();
            }
        });
        ticker.start();

        long second = gen.nextId();
        assertThat(second).isGreaterThan(first);
    }

    /**
     * Clock skew, tier two: a large backwards jump fails loudly. Emitting ids that were
     * already issued would be silent data corruption, so refusing to generate is the
     * correct response.
     */
    @Test
    void refusesToGenerateAfterALargeBackwardClockJump() {
        AtomicLong clock = new AtomicLong(SnowflakeIdGenerator.EPOCH + 10_000_000);
        SnowflakeIdGenerator gen = new SnowflakeIdGenerator(4, clock::get);

        gen.nextId();
        clock.addAndGet(-60_000);    // a whole minute backwards

        assertThatThrownBy(gen::nextId)
                .isInstanceOf(SnowflakeIdGenerator.ClockMovedBackwardsException.class)
                .hasMessageContaining("60000ms");
    }

    /**
     * Pins down what Snowflake codes actually give you, and what they do not.
     *
     * <p>They are not walkable: there is no {@code /1}, {@code /2}, {@code /3} to iterate,
     * and the reachable keyspace is spread across 2^63 rather than 0..n, so you cannot
     * enumerate the service or read off how many links were created last week.
     *
     * <p>They are, however, <b>locally</b> predictable: two ids minted in the same
     * millisecond on the same node differ by exactly 1, so from one known code you can
     * guess its immediate neighbours. That is a real limitation of deriving codes from
     * Snowflake ids, it is asserted here rather than glossed over, and the README says
     * what would fix it (encrypt the id with a format-preserving permutation before
     * encoding, which keeps uniqueness while destroying adjacency).
     */
    @Test
    void codesAreNotWalkableButAreLocallyPredictable() {
        SnowflakeIdGenerator gen = generator(12);
        long first = gen.nextId();
        long second = gen.nextId();

        assertThat(Base62.encode(first)).isNotEqualTo(Base62.encode(second));
        // Nowhere near the origin: an attacker cannot start at 1 and walk.
        assertThat(first).isGreaterThan(1L << 55);
        // But consecutive within a node and millisecond, which is the honest caveat.
        assertThat(second - first).isEqualTo(1);
    }
}
