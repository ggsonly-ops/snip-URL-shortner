package dev.snip.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "app")
public class SnipProperties {

    /** Base URL used to build the short link returned to clients, e.g. https://sn.ip */
    private String baseUrl = "http://localhost:8080";

    /** Snowflake machine id; -1 means "derive from hostname". */
    private long machineId = -1;

    private final Cache cache = new Cache();
    private final RateLimit rateLimit = new RateLimit();
    private final Analytics analytics = new Analytics();
    private final Bloom bloom = new Bloom();
    private final Security security = new Security();

    @Getter
    @Setter
    public static class Security {
        /**
         * Refuse to shorten URLs that resolve to private, loopback or link-local
         * addresses. Turning this off is only ever right for local development against
         * a service on localhost.
         */
        private boolean ssrfGuard = true;
        /** Refuse URLs whose host does not resolve at all. */
        private boolean rejectUnresolvableHosts = true;
        /** Maximum accepted URL length. */
        private int maxUrlLength = 2048;
    }

    @Getter
    @Setter
    public static class Cache {
        /** Master switch. The load-test matrix flips this to produce the before/after table. */
        private boolean enabled = true;
        /** How long a resolved link stays cached. */
        private Duration ttl = Duration.ofHours(24);
        /** How long a "this code does not exist" sentinel stays cached. Shorter, because
         *  a code can come into existence at any moment. */
        private Duration negativeTtl = Duration.ofMinutes(5);
        /** Lifetime of the stampede lock. Must exceed a slow DB read but stay short. */
        private Duration lockTtl = Duration.ofSeconds(3);
        /** How long a waiter backs off before re-reading the cache. */
        private Duration lockBackoff = Duration.ofMillis(20);
        /** How many times a waiter re-reads before giving up and querying the DB itself. */
        private int lockMaxRetries = 10;
        /** Warm the cache with the hottest links at startup. */
        private boolean warmOnStartup = true;
        private int warmSize = 1000;
        /** Consecutive Redis failures before the circuit opens. */
        private int circuitFailureThreshold = 5;
        /** How long the circuit stays open before probing Redis again. */
        private Duration circuitOpenDuration = Duration.ofSeconds(30);
    }

    @Getter
    @Setter
    public static class RateLimit {
        private boolean enabled = true;
        /** Bucket capacity for requests carrying a valid X-API-Key. */
        private int authenticatedCapacity = 100;
        /** Sustained refill for authenticated callers, per minute. */
        private double authenticatedRefillPerMinute = 100;
        /** Bucket capacity for anonymous callers, keyed by client IP. */
        private int anonymousCapacity = 20;
        private double anonymousRefillPerMinute = 20;
        /** Redirects are the product; they get a far larger budget than writes. */
        private int redirectCapacity = 600;
        private double redirectRefillPerMinute = 600;
        /**
         * What to do when Redis is unreachable. true = fail open (serve the request,
         * lose the protection); false = fail closed (503, protect the database).
         * We fail open: availability of redirects matters more than perfect limiting,
         * and Postgres is still shielded by its own connection pool.
         */
        private boolean failOpen = true;
        /**
         * Trust X-Forwarded-For only when the immediate peer is one of these CIDRs.
         * Clients can forge the header, so an untrusted peer's value is ignored.
         */
        private String[] trustedProxies = {"127.0.0.1/32", "::1/128", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"};
    }

    @Getter
    @Setter
    public static class Analytics {
        private boolean enabled = true;
        private String stream = "snip:clicks";
        private String consumerGroup = "analytics";
        /** Rows drained from the stream per pass. */
        private int batchSize = 500;
        /** Cap the stream length so a stalled consumer cannot exhaust Redis memory. */
        private long maxStreamLength = 1_000_000L;
        /** Reclaim entries pending longer than this from a dead consumer. */
        private Duration reclaimIdleAfter = Duration.ofSeconds(60);
        /** Path to a MaxMind GeoLite2-Country.mmdb. Absent = country stays NULL. */
        private String geoipDatabase = "";
        /**
         * Demo only. With no GeoIP database, derive a country deterministically from
         * the client IP so the dashboard has something to draw. Off by default because
         * the data is synthetic, and the API labels it as such.
         */
        private boolean geoipDemoFallback = false;
    }

    @Getter
    @Setter
    public static class Bloom {
        private boolean enabled = true;
        /** Bit-array size. 8M bits = 1MB in Redis; at 100k codes that is ~0.0001% false positives. */
        private long bits = 8_388_608L;
        private int hashes = 7;
        private String key = "snip:bloom:codes";
    }
}
