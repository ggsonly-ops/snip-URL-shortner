package dev.snip.id;

/**
 * Base62 codec.
 *
 * <p>62 = 10 digits + 26 uppercase + 26 lowercase, which is exactly the set of
 * URL-safe alphanumerics that survive a URL with no percent-encoding. Base64
 * would add {@code +} and {@code /} (both need escaping in a path segment) plus
 * {@code =} padding, so Base62 is the shortest URL-clean representation.
 *
 * <p>The alphabet is ordered digits &rarr; uppercase &rarr; lowercase so that the
 * lexicographic order of encoded strings of equal length matches numeric order.
 */
public final class Base62 {

    static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static final int BASE = ALPHABET.length();

    /** Reverse lookup table: char -> digit, or -1 if the char is not in the alphabet. */
    private static final int[] LOOKUP = new int[128];

    static {
        java.util.Arrays.fill(LOOKUP, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            LOOKUP[ALPHABET.charAt(i)] = i;
        }
    }

    private Base62() {
    }

    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Base62 cannot encode negative values: " + value);
        }
        if (value == 0) {
            return "0";
        }
        // A 63-bit value is at most 11 Base62 digits.
        char[] buf = new char[11];
        int pos = buf.length;
        while (value > 0) {
            buf[--pos] = ALPHABET.charAt((int) (value % BASE));
            value /= BASE;
        }
        return new String(buf, pos, buf.length - pos);
    }

    public static long decode(String s) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException("Base62 input must not be empty");
        }
        if (s.length() > 11) {
            throw new IllegalArgumentException("Base62 input too long for a long: " + s);
        }
        long value = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int digit = c < 128 ? LOOKUP[c] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("Invalid Base62 character: " + c);
            }
            long next = value * BASE + digit;
            if (next < value) {
                throw new IllegalArgumentException("Base62 input overflows a long: " + s);
            }
            value = next;
        }
        return value;
    }

    /** True if every character is in the Base62 alphabet. Used for cheap request-path filtering. */
    public static boolean isValid(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 128 || LOOKUP[c] < 0) {
                return false;
            }
        }
        return true;
    }
}
