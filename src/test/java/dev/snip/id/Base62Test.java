package dev.snip.id;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62Test {

    @Test
    void encodesKnownValues() {
        assertThat(Base62.encode(0)).isEqualTo("0");
        assertThat(Base62.encode(1)).isEqualTo("1");
        assertThat(Base62.encode(10)).isEqualTo("A");
        assertThat(Base62.encode(61)).isEqualTo("z");
        assertThat(Base62.encode(62)).isEqualTo("10");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 61, 62, 3843, 1_000_000, Long.MAX_VALUE})
    void roundTrips(long value) {
        assertThat(Base62.decode(Base62.encode(value))).isEqualTo(value);
    }

    @Test
    void roundTripsRandomValues() {
        for (int i = 0; i < 100_000; i++) {
            long v = ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE);
            assertThat(Base62.decode(Base62.encode(v))).isEqualTo(v);
        }
    }

    @Test
    void encodesOnlyUrlSafeCharacters() {
        // The whole reason for base 62 over base 64: no +, / or = to percent-encode.
        for (int i = 0; i < 10_000; i++) {
            String encoded = Base62.encode(ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE));
            assertThat(encoded).matches("[0-9A-Za-z]+");
        }
    }

    @Test
    void aSnowflakeIdEncodesToElevenCharactersOrFewer() {
        // Snowflake ids are ~63-bit, which is 11 Base62 digits. Anything shorter needs
        // either a counter (enumerable) or randomness (collision checks).
        long typical = (System.currentTimeMillis() - SnowflakeIdGenerator.EPOCH) << 22;
        assertThat(Base62.encode(typical)).hasSizeLessThanOrEqualTo(11);
        assertThat(Base62.encode(Long.MAX_VALUE)).hasSize(11);
    }

    @Test
    void encodingIsInjective() {
        Set<String> seen = new HashSet<>();
        for (long v = 0; v < 50_000; v++) {
            assertThat(seen.add(Base62.encode(v))).as("duplicate encoding for %d", v).isTrue();
        }
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> Base62.encode(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62.decode("abc!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62.decode("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62.decode("zzzzzzzzzzzz")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isValidMatchesTheAlphabet() {
        assertThat(Base62.isValid("aZ09")).isTrue();
        assertThat(Base62.isValid("a-b")).isFalse();
        assertThat(Base62.isValid("héllo")).isFalse();
        assertThat(Base62.isValid("")).isFalse();
    }
}
