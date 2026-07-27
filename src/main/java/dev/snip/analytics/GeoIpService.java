package dev.snip.analytics;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CountryResponse;
import dev.snip.config.SnipProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves a client IP to an ISO-3166 country code.
 *
 * <p>MaxMind's GeoLite2 database cannot be redistributed, so it is not in the repo.
 * Point {@code app.analytics.geoip-database} at a downloaded
 * {@code GeoLite2-Country.mmdb} and country data becomes real; leave it unset and
 * country stays NULL rather than guessed.
 *
 * <p>{@code app.analytics.geoip-demo-fallback} exists so the dashboard has something to
 * draw during a demo. It derives a country deterministically from the IP, which is
 * <em>synthetic data</em> — so it is off by default, and the analytics API flags it as
 * synthetic whenever it is on. Fabricated numbers that are not labelled as fabricated
 * are the thing to avoid here.
 */
@Slf4j
@Component
public class GeoIpService {

    private static final String[] DEMO_COUNTRIES = {
            "US", "IN", "GB", "DE", "BR", "JP", "CA", "FR", "AU", "NL", "SG", "ZA"};

    private final SnipProperties.Analytics cfg;
    private final DatabaseReader reader;

    /** Small bounded LRU. Click traffic repeats IPs heavily, so this saves most lookups. */
    private final Map<String, String> lookupCache = Collections.synchronizedMap(
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 10_000;
                }
            });

    public GeoIpService(SnipProperties props) {
        this.cfg = props.getAnalytics();
        this.reader = openDatabase(cfg.getGeoipDatabase());
    }

    private static DatabaseReader openDatabase(String path) {
        if (path == null || path.isBlank()) {
            log.info("No GeoIP database configured; click country will be null "
                    + "(set app.analytics.geoip-database to a GeoLite2-Country.mmdb to enable)");
            return null;
        }
        File file = new File(path);
        if (!file.isFile()) {
            log.warn("GeoIP database '{}' not found; country lookups disabled", path);
            return null;
        }
        try {
            DatabaseReader r = new DatabaseReader.Builder(file).withCache(new com.maxmind.db.CHMCache()).build();
            log.info("GeoIP database loaded from {}", path);
            return r;
        } catch (IOException e) {
            log.warn("Failed to open GeoIP database '{}': {}", path, e.toString());
            return null;
        }
    }

    /** True when country values are made up rather than looked up. Surfaced in the API. */
    public boolean isSynthetic() {
        return reader == null && cfg.isGeoipDemoFallback();
    }

    /** @return a two-letter country code, or null when it cannot be determined */
    public String countryOf(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        String cached = lookupCache.get(ip);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }

        String country = lookup(ip);
        lookupCache.put(ip, country == null ? "" : country);
        return country;
    }

    private String lookup(String ip) {
        if (reader == null) {
            return cfg.isGeoipDemoFallback() ? demoCountry(ip) : null;
        }
        try {
            CountryResponse response = reader.country(InetAddress.getByName(ip));
            return response.getCountry().getIsoCode();
        } catch (IOException | GeoIp2Exception | RuntimeException e) {
            // Address not in the database, or a private/invalid IP. Not worth a log line
            // per click.
            return null;
        }
    }

    private static String demoCountry(String ip) {
        int h = ip.hashCode();
        return DEMO_COUNTRIES[Math.floorMod(h, DEMO_COUNTRIES.length)];
    }

    @PreDestroy
    void close() {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
                // shutting down anyway
            }
        }
    }
}
