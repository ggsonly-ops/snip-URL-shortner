package dev.snip.service;

import dev.snip.config.SnipProperties;
import dev.snip.exception.InvalidUrlException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    private UrlValidator validator;

    /**
     * A stub resolver keeps these tests off the network: real DNS would make them slow,
     * flaky, and dependent on whatever the local resolver decides to return for a
     * nonexistent name.
     */
    private static final Map<String, String> DNS = Map.ofEntries(
            Map.entry("example.com", "93.184.216.34"),
            Map.entry("www.example.com", "93.184.216.34"),
            Map.entry("xn--bcher-kva.example", "93.184.216.35"),
            Map.entry("evil.test", "127.0.0.1"),
            Map.entry("internal.test", "10.1.2.3"),
            Map.entry("metadata.test", "169.254.169.254"),
            Map.entry("cgnat.test", "100.64.0.1"),
            Map.entry("ula6.test", "fd00::1"),
            Map.entry("mixed.test", "93.184.216.34"));

    @BeforeEach
    void setUp() {
        SnipProperties props = new SnipProperties();
        validator = new UrlValidator(props, host -> {
            if ("mixed.test".equals(host)) {
                // One public and one private record. A first-address-only check would
                // wave this through.
                return new InetAddress[]{
                        InetAddress.getByName("93.184.216.34"),
                        InetAddress.getByName("192.168.1.5")};
            }
            String ip = DNS.get(host);
            if (ip == null) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[]{InetAddress.getByName(ip)};
        });
    }

    // -- normalisation -------------------------------------------------------

    @Test
    void lowercasesSchemeAndHost() {
        assertThat(validator.validateAndNormalise("HTTP://Example.COM/Path"))
                .isEqualTo("http://example.com/Path");
    }

    @Test
    void dropsDefaultPorts() {
        assertThat(validator.validateAndNormalise("http://example.com:80/a")).isEqualTo("http://example.com/a");
        assertThat(validator.validateAndNormalise("https://example.com:443/a")).isEqualTo("https://example.com/a");
        assertThat(validator.validateAndNormalise("https://example.com:8443/a")).isEqualTo("https://example.com:8443/a");
    }

    @Test
    void stripsLoneTrailingSlashAndFragment() {
        assertThat(validator.validateAndNormalise("https://example.com/")).isEqualTo("https://example.com");
        assertThat(validator.validateAndNormalise("https://example.com/a#section"))
                .isEqualTo("https://example.com/a");
    }

    @Test
    void preservesPathCaseAndQuery() {
        // The path is case sensitive on most servers, so normalising it would break links.
        assertThat(validator.validateAndNormalise("https://example.com/Foo/Bar?b=2&a=1"))
                .isEqualTo("https://example.com/Foo/Bar?b=2&a=1");
    }

    @Test
    void addsHttpsToASchemelessHost() {
        assertThat(validator.validateAndNormalise("example.com/docs")).isEqualTo("https://example.com/docs");
    }

    @Test
    void convertsInternationalisedHostsToPunycode() {
        assertThat(validator.validateAndNormalise("https://bücher.example/x"))
                .isEqualTo("https://xn--bcher-kva.example/x");
    }

    // -- dedupe canonicalisation ---------------------------------------------

    @Test
    void canonicalFormSortsQueryParametersButTheStoredUrlDoesNot() {
        String a = validator.validateAndNormalise("https://example.com/p?b=2&a=1");
        String b = validator.validateAndNormalise("https://example.com/p?a=1&b=2");

        // Stored URLs keep the author's parameter order, because a few sites care.
        assertThat(a).isNotEqualTo(b);
        // But they hash identically, so dedupe still catches them.
        assertThat(validator.canonicalForHash(a)).isEqualTo(validator.canonicalForHash(b));
        assertThat(validator.sha256(validator.canonicalForHash(a)))
                .isEqualTo(validator.sha256(validator.canonicalForHash(b)));
    }

    @Test
    void equivalentUrlsHashIdentically() {
        String a = validator.validateAndNormalise("HTTP://Example.com:80/path/");
        String b = validator.validateAndNormalise("http://example.com/path/");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void sha256ProducesSixtyFourHexCharacters() {
        assertThat(validator.sha256("https://example.com")).hasSize(64).matches("[0-9a-f]{64}");
    }

    // -- SSRF guard ----------------------------------------------------------

    @Test
    void blocksLoopback() {
        assertThatThrownBy(() -> validator.validateAndNormalise("http://evil.test/x"))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("internal");
    }

    @Test
    void blocksPrivateRanges() {
        assertThatThrownBy(() -> validator.validateAndNormalise("http://internal.test/admin"))
                .isInstanceOf(InvalidUrlException.class);
    }

    /** The headline case: the cloud instance metadata endpoint. */
    @Test
    void blocksCloudInstanceMetadata() {
        assertThatThrownBy(() -> validator.validateAndNormalise(
                "http://metadata.test/latest/meta-data/iam/security-credentials/"))
                .isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> validator.validateAndNormalise("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void blocksCarrierGradeNatAndIpv6UniqueLocal() {
        assertThatThrownBy(() -> validator.validateAndNormalise("http://cgnat.test/"))
                .isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> validator.validateAndNormalise("http://ula6.test/"))
                .isInstanceOf(InvalidUrlException.class);
    }

    /** Checking only the first resolved address would let this through. */
    @Test
    void blocksAHostWithBothPublicAndPrivateRecords() {
        assertThatThrownBy(() -> validator.validateAndNormalise("http://mixed.test/"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost/x",
            "http://foo.localhost/x",
            "http://db.internal/x",
            "http://printer.local/x",
    })
    void blocksInternalHostnamesWithoutEvenResolving(String url) {
        assertThatThrownBy(() -> validator.validateAndNormalise(url))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void allowsOrdinaryPublicUrls() {
        assertThat(validator.validateAndNormalise("https://example.com/some/page?q=1"))
                .isEqualTo("https://example.com/some/page?q=1");
    }

    // -- scheme and shape ----------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD4=",
            "file:///etc/passwd",
            "ftp://example.com/x",
    })
    void rejectsNonHttpSchemes(String url) {
        assertThatThrownBy(() -> validator.validateAndNormalise(url))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void rejectsEmbeddedCredentials() {
        // Reads as one domain, resolves to another - a phishing primitive.
        assertThatThrownBy(() -> validator.validateAndNormalise("https://example.com@evil.test/"))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    void rejectsUnresolvableHosts() {
        assertThatThrownBy(() -> validator.validateAndNormalise("https://does-not-exist.invalid/"))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("resolved");
    }

    @Test
    void rejectsWhitespaceControlCharsAndOverlongUrls() {
        assertThatThrownBy(() -> validator.validateAndNormalise("https://example.com/a b"))
                .isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> validator.validateAndNormalise("https://example.com/a\nb"))
                .isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> validator.validateAndNormalise("https://example.com/" + "x".repeat(3000)))
                .isInstanceOf(InvalidUrlException.class);
        assertThatThrownBy(() -> validator.validateAndNormalise("   "))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    void ssrfGuardCanBeDisabledForLocalDevelopment() {
        SnipProperties props = new SnipProperties();
        props.getSecurity().setSsrfGuard(false);
        UrlValidator permissive = new UrlValidator(props, host -> new InetAddress[]{
                InetAddress.getByName("127.0.0.1")});

        assertThat(permissive.validateAndNormalise("http://localhost:3000/callback"))
                .isEqualTo("http://localhost:3000/callback");
    }
}
