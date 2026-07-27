package dev.snip.cache;

import dev.snip.domain.ResolvedLink;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache value is a hand-packed string rather than JSON, so it needs its own tests:
 * a codec bug here would corrupt every redirect rather than failing loudly.
 */
class LinkCacheCodecTest {

    @Test
    void roundTripsAPlainLink() {
        ResolvedLink link = new ResolvedLink(1234567890123L, "https://example.com/a/b?c=1", false, null);
        assertThat(LinkCache.decode(LinkCache.encode(link))).isEqualTo(link);
    }

    @Test
    void roundTripsAProtectedExpiringLink() {
        Instant expiry = Instant.ofEpochMilli(1893456000000L);
        ResolvedLink link = new ResolvedLink(42L, "https://example.com/secret", true, expiry);
        ResolvedLink decoded = LinkCache.decode(LinkCache.encode(link));

        assertThat(decoded).isEqualTo(link);
        assertThat(decoded.passwordProtected()).isTrue();
        assertThat(decoded.expiresAt()).isEqualTo(expiry);
    }

    @Test
    void survivesUrlsContainingTheCharactersUsedInTheFormat() {
        // Colons, hyphens, digits and query separators all appear in the packed layout;
        // the separator is U+0001 precisely because it cannot appear in a URI.
        String awkward = "https://example.com:8443/a-1?x=-&y=1&z=--";
        ResolvedLink link = new ResolvedLink(9L, awkward, false, null);
        assertThat(LinkCache.decode(LinkCache.encode(link)).longUrl()).isEqualTo(awkward);
    }

    @Test
    void returnsNullForCorruptEntriesRatherThanThrowing() {
        // A malformed entry must degrade to a cache miss, not a 500 on the hot path.
        assertThat(LinkCache.decode("garbage")).isNull();
        assertThat(LinkCache.decode("")).isNull();
        assertThat(LinkCache.decode("not-a-number1-https://x.example")).isNull();
    }

    @Test
    void encodedFormIsCompact() {
        ResolvedLink link = new ResolvedLink(7239104857293L, "https://example.com/x", false, null);
        String encoded = LinkCache.encode(link);
        // id + two flags + three separators on top of the URL, and nothing else.
        assertThat(encoded.length()).isEqualTo(link.longUrl().length() + 13 + 1 + 1 + 3);
    }
}
