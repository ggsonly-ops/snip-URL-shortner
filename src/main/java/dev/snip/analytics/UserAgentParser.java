package dev.snip.analytics;

import lombok.extern.slf4j.Slf4j;
import nl.basjes.parse.useragent.UserAgent;
import nl.basjes.parse.useragent.UserAgentAnalyzer;
import org.springframework.stereotype.Component;

/**
 * Wraps yauaa to turn a User-Agent header into (device class, browser, OS).
 *
 * <p>Two practical notes. yauaa's analyzer takes a couple of seconds to build its rule
 * set, so it is created lazily on first use — the batch consumer pays it once, and app
 * startup (and therefore the health check that gates a rolling deploy) does not.
 * It is also restricted to the three fields we actually store, because asking for the
 * full field set is several times slower per parse.
 */
@Slf4j
@Component
public class UserAgentParser {

    public record Parsed(String deviceType, String browser, String os) {
        static final Parsed UNKNOWN = new Parsed(null, null, null);
    }

    private volatile UserAgentAnalyzer analyzer;

    private UserAgentAnalyzer analyzer() {
        UserAgentAnalyzer local = analyzer;
        if (local == null) {
            synchronized (this) {
                local = analyzer;
                if (local == null) {
                    long start = System.currentTimeMillis();
                    local = UserAgentAnalyzer.newBuilder()
                            .withField(UserAgent.DEVICE_CLASS)
                            .withField(UserAgent.AGENT_NAME)
                            .withField(UserAgent.OPERATING_SYSTEM_NAME)
                            .withCache(10_000)
                            .hideMatcherLoadStats()
                            .build();
                    analyzer = local;
                    log.info("User-agent analyzer initialised in {}ms", System.currentTimeMillis() - start);
                }
            }
        }
        return local;
    }

    public Parsed parse(String userAgentHeader) {
        if (userAgentHeader == null || userAgentHeader.isBlank()) {
            return Parsed.UNKNOWN;
        }
        try {
            UserAgent ua = analyzer().parse(userAgentHeader);
            return new Parsed(
                    clean(ua.getValue(UserAgent.DEVICE_CLASS), 20),
                    clean(ua.getValue(UserAgent.AGENT_NAME), 50),
                    clean(ua.getValue(UserAgent.OPERATING_SYSTEM_NAME), 50));
        } catch (RuntimeException e) {
            log.debug("User-agent parse failed: {}", e.toString());
            return Parsed.UNKNOWN;
        }
    }

    private static String clean(String value, int max) {
        if (value == null || value.isBlank() || "Unknown".equalsIgnoreCase(value) || "??".equals(value)) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
