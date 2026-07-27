package dev.snip.service;

import dev.snip.config.SnipProperties;
import dev.snip.exception.InvalidUrlException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation and normalisation of submitted URLs.
 *
 * <p>More important than it looks: this is where the security holes are.
 *
 * <p><b>The SSRF guard is the part worth explaining.</b> Without it, someone shortens
 * {@code http://169.254.169.254/latest/meta-data/iam/security-credentials/}. If any
 * part of the system ever fetches a shortened URL server-side — to render a preview
 * card, check liveness, grab a favicon, run a safe-browsing check — that request comes
 * from inside the trust boundary and hands back the instance's cloud credentials. The
 * same trick reaches internal admin panels on 10.0.0.0/8. Blocking at creation time
 * closes the class of attack before any such feature exists.
 *
 * <p><b>The residual weakness</b>, which is worth volunteering rather than hiding: this
 * resolves DNS once, at creation. An attacker controlling the domain can return a public
 * address now and a private one later (DNS rebinding), so any future server-side fetch
 * must re-validate the address it actually connects to, not trust this check.
 *
 * <p><b>Normalisation matters</b> because {@code HTTP://Example.com:80/path} and
 * {@code http://example.com/path} are the same resource. Normalising before hashing is
 * what makes dedupe work: the same URL from the same owner returns the existing code
 * instead of creating another row.
 */
@Slf4j
@Component
public class UrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** Hostnames that resolve inside a container or a corporate network. */
    private static final Set<String> BLOCKED_HOST_SUFFIXES =
            Set.of(".localhost", ".local", ".internal", ".localdomain", ".home.arpa");

    private static final Set<String> BLOCKED_HOSTS =
            Set.of("localhost", "metadata.google.internal", "instance-data");

    /** Looks like a bare host (optionally with a path) that just forgot its scheme. */
    private static final Pattern SCHEMELESS =
            Pattern.compile("^[\\w.-]+\\.[a-zA-Z]{2,}(?::\\d+)?(?:[/?#].*)?$");

    /** Control characters and whitespace have no business inside a URL. */
    private static final Pattern ILLEGAL_CHARS = Pattern.compile("[\\p{Cntrl}\\s]");

    /** Seam for tests: real DNS by default. */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final SnipProperties.Security cfg;
    private final HostResolver resolver;

    /** {@code @Autowired} disambiguates from the package-private test constructor below. */
    @org.springframework.beans.factory.annotation.Autowired
    public UrlValidator(SnipProperties props) {
        this(props, InetAddress::getAllByName);
    }

    UrlValidator(SnipProperties props, HostResolver resolver) {
        this.cfg = props.getSecurity();
        this.resolver = resolver;
    }

    /**
     * Validates a submitted URL and returns the normalised form that gets stored and
     * redirected to. Throws {@link InvalidUrlException} on anything we refuse.
     */
    public String validateAndNormalise(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidUrlException("URL must not be empty");
        }
        String trimmed = raw.trim();

        if (trimmed.length() > cfg.getMaxUrlLength()) {
            throw new InvalidUrlException("URL exceeds " + cfg.getMaxUrlLength() + " characters");
        }
        if (ILLEGAL_CHARS.matcher(trimmed).find()) {
            throw new InvalidUrlException("URL must not contain whitespace or control characters");
        }

        // Convenience: people paste "example.com/x". Assume https rather than reject.
        if (!trimmed.contains("://") && SCHEMELESS.matcher(trimmed).matches()) {
            trimmed = "https://" + trimmed;
        }

        // java.net.URI follows RFC 2396, where a non-ASCII authority is simply illegal:
        // it parses without complaint and then reports a null host. Converting the
        // authority to punycode before parsing is what makes internationalised domains
        // work at all, rather than being rejected as "URL must include a host".
        trimmed = punycodeAuthority(trimmed);

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("Malformed URL");
        }

        if (uri.getScheme() == null || !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new InvalidUrlException("Only http and https are supported");
        }
        if (uri.getHost() == null) {
            throw new InvalidUrlException("URL must include a host");
        }
        if (uri.getUserInfo() != null) {
            // http://trusted-looking.com@evil.example/ reads as one domain and resolves
            // to another. Refuse rather than try to render it safely.
            throw new InvalidUrlException("URLs with embedded credentials are not accepted");
        }
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new InvalidUrlException("Invalid port");
        }

        String host = asciiHost(uri.getHost());
        assertNotInternal(host);

        return normalise(uri, host);
    }

    /**
     * Rewrites a non-ASCII authority to punycode before the URL is handed to
     * {@link URI}, which follows RFC 2396 and treats any non-ASCII authority as absent
     * rather than as an error. Left alone, {@code https://bücher.example/x} parses
     * "successfully" with a null host.
     *
     * <p>Only the host is converted: userinfo and port are carried across untouched, and
     * an IPv6 literal in brackets is left exactly as written.
     */
    private static String punycodeAuthority(String url) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url;
        }
        int authStart = schemeEnd + 3;
        int authEnd = url.length();
        for (int i = authStart; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                authEnd = i;
                break;
            }
        }

        String authority = url.substring(authStart, authEnd);
        if (isAscii(authority)) {
            return url;
        }

        int at = authority.lastIndexOf('@');
        String userInfo = at >= 0 ? authority.substring(0, at + 1) : "";
        String hostPort = at >= 0 ? authority.substring(at + 1) : authority;

        String host;
        String port = "";
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) {
                throw new InvalidUrlException("Malformed URL");
            }
            host = hostPort.substring(0, close + 1);
            port = hostPort.substring(close + 1);
        } else {
            int colon = hostPort.lastIndexOf(':');
            if (colon >= 0) {
                host = hostPort.substring(0, colon);
                port = hostPort.substring(colon);
            } else {
                host = hostPort;
            }
        }

        String asciiHost;
        try {
            asciiHost = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            throw new InvalidUrlException("Invalid host name");
        }

        return url.substring(0, authStart) + userInfo + asciiHost + port + url.substring(authEnd);
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    /** Converts a unicode/IDN host to its punycode form, so dedupe cannot be fooled by homographs. */
    private String asciiHost(String host) {
        String stripped = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
        try {
            return IDN.toASCII(stripped, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new InvalidUrlException("Invalid host name");
        }
    }

    /** SSRF guard: refuse anything that resolves inside our own network. */
    void assertNotInternal(String host) {
        if (!cfg.isSsrfGuard()) {
            return;
        }

        String lower = host.toLowerCase(Locale.ROOT);
        if (BLOCKED_HOSTS.contains(lower) || BLOCKED_HOST_SUFFIXES.stream().anyMatch(lower::endsWith)) {
            throw new InvalidUrlException("Internal hostnames are not allowed");
        }

        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            if (cfg.isRejectUnresolvableHosts()) {
                throw new InvalidUrlException("Host cannot be resolved");
            }
            return;
        }

        // Check *every* address, not just the first. A host with one public and one
        // private A record would sail through a first-only check.
        for (InetAddress addr : addresses) {
            if (isInternal(addr)) {
                throw new InvalidUrlException("URLs pointing at internal addresses are not allowed");
            }
        }
    }

    static boolean isInternal(InetAddress addr) {
        if (addr.isLoopbackAddress()          // 127.0.0.0/8, ::1
                || addr.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || addr.isLinkLocalAddress()  // 169.254/16, fe80::/10
                || addr.isAnyLocalAddress()   // 0.0.0.0, ::
                || addr.isMulticastAddress()) {
            return true;
        }

        if (addr instanceof Inet4Address) {
            byte[] b = addr.getAddress();
            int o1 = b[0] & 0xff;
            int o2 = b[1] & 0xff;

            // Cloud instance metadata. The single most-abused SSRF target there is.
            if (o1 == 169 && o2 == 254) {
                return true;
            }
            // 100.64.0.0/10 carrier-grade NAT
            if (o1 == 100 && o2 >= 64 && o2 <= 127) {
                return true;
            }
            // 192.0.0.0/24 IETF protocol assignments, 192.0.2.0/24 TEST-NET-1
            if (o1 == 192 && o2 == 0) {
                return true;
            }
            // 198.18.0.0/15 benchmarking, 198.51.100.0/24 TEST-NET-2
            if (o1 == 198 && (o2 == 18 || o2 == 19 || o2 == 51)) {
                return true;
            }
            // 203.0.113.0/24 TEST-NET-3
            if (o1 == 203 && o2 == 0 && (b[2] & 0xff) == 113) {
                return true;
            }
            // 240.0.0.0/4 reserved, plus 255.255.255.255
            return o1 >= 240;
        }

        if (addr instanceof Inet6Address) {
            byte[] b = addr.getAddress();
            // fc00::/7 unique local addresses - the IPv6 equivalent of 10/8, and not
            // covered by isSiteLocalAddress() (which only knows the deprecated fec0::/10).
            if ((b[0] & 0xfe) == 0xfc) {
                return true;
            }
            // ::ffff:a.b.c.d IPv4-mapped: re-check as IPv4 so the mapping is not a bypass.
            if (((Inet6Address) addr).isIPv4CompatibleAddress()) {
                return true;
            }
        }

        return false;
    }

    /**
     * The form we store and redirect to. Conservative on purpose: it only changes
     * things that are guaranteed not to change which resource is fetched.
     */
    private String normalise(URI uri, String asciiHost) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort();

        boolean defaultPort = port == -1
                || (scheme.equals("http") && port == 80)
                || (scheme.equals("https") && port == 443);
        String portPart = defaultPort ? "" : ":" + port;

        String hostPart = asciiHost.indexOf(':') >= 0 ? "[" + asciiHost + "]" : asciiHost;

        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.equals("/")) {
            path = "";           // a lone trailing slash on the root is noise
        }

        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();

        // Fragments are never sent to the server, so they cannot affect what is fetched
        // and only bloat the stored value. Dropped.
        return scheme + "://" + hostPart + portPart + path + query;
    }

    /**
     * The form used for the dedupe hash only — never stored, never redirected to.
     *
     * <p>Query parameter order is not semantically significant for the overwhelming
     * majority of sites, but it <em>is</em> significant for a few (repeated keys,
     * signature-bearing URLs). Sorting only for the hash gets the dedupe benefit with
     * zero risk of altering the URL the user actually gets sent to.
     */
    public String canonicalForHash(String normalisedUrl) {
        int q = normalisedUrl.indexOf('?');
        if (q < 0) {
            return normalisedUrl;
        }
        String base = normalisedUrl.substring(0, q);
        String query = normalisedUrl.substring(q + 1);
        if (query.isEmpty()) {
            return base;
        }
        List<String> params = new ArrayList<>(List.of(query.split("&")));
        params.sort(Comparator.naturalOrder());
        return base + "?" + String.join("&", params);
    }

    public String sha256(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JLS and is missing", e);
        }
    }
}
