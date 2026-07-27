package dev.snip.analytics;

import dev.snip.domain.Link;
import dev.snip.dto.Dtos.AnalyticsResponse;
import dev.snip.dto.Dtos.DailyClicks;
import dev.snip.dto.Dtos.NamedCount;
import dev.snip.exception.ForbiddenException;
import dev.snip.exception.LinkNotFoundException;
import dev.snip.repository.LinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final int MAX_WINDOW_DAYS = 365;
    private static final int TOP_N = 10;

    private final LinkRepository repo;
    private final JdbcTemplate jdbc;
    private final GeoIpService geo;

    @Transactional(readOnly = true)
    public AnalyticsResponse analytics(String code, int days, String apiKey) {
        Link link = repo.findByShortCode(code).orElseThrow(() -> new LinkNotFoundException(code));

        // Click history is not public: anyone could otherwise read the traffic of any
        // link they happen to know. Anonymous links have no owner and so have no
        // readable analytics.
        if (link.getOwnerKey() == null) {
            throw new ForbiddenException("Anonymous links have no readable analytics; create with an X-API-Key");
        }
        if (!link.getOwnerKey().equals(apiKey)) {
            throw new ForbiddenException("This link belongs to a different API key");
        }

        int window = Math.min(Math.max(days, 1), MAX_WINDOW_DAYS);
        long linkId = link.getId();

        return new AnalyticsResponse(
                link.getShortCode(),
                link.getLongUrl(),
                link.getClickCount(),
                window,
                clicksPerDay(linkId, window),
                topBy("country", linkId, window),
                topBy("referrer", linkId, window),
                topBy("device_type", linkId, window),
                topBy("browser", linkId, window),
                topBy("os", linkId, window),
                geo.isSynthetic());
    }

    /**
     * Clicks per day, gap-filled with generate_series.
     *
     * <p>Without the gap-fill, days with zero clicks are simply absent from the result
     * and a line chart draws a straight segment between the surrounding points — which
     * reads as "steady traffic" when the truth is "no traffic at all".
     *
     * <p>The {@code clicked_at >=} predicate is what lets Postgres prune partitions:
     * a 30-day window touches one or two monthly partitions instead of the whole table.
     */
    private List<DailyClicks> clicksPerDay(long linkId, int days) {
        return jdbc.query("""
                SELECT to_char(d.day, 'YYYY-MM-DD') AS day, COALESCE(c.clicks, 0) AS clicks
                FROM generate_series(
                        CURRENT_DATE - make_interval(days => ?),
                        CURRENT_DATE,
                        INTERVAL '1 day') AS d(day)
                LEFT JOIN (
                    SELECT date_trunc('day', clicked_at) AS day, COUNT(*) AS clicks
                    FROM clicks
                    WHERE link_id = ?
                      AND clicked_at >= CURRENT_DATE - make_interval(days => ?)
                    GROUP BY 1
                ) c ON c.day = d.day
                ORDER BY d.day
                """,
                (rs, i) -> new DailyClicks(rs.getString("day"), rs.getLong("clicks")),
                days, linkId, days);
    }

    /**
     * Top N values of one dimension. The column name is chosen from a fixed allow-list
     * rather than interpolated from user input — it cannot be a bind parameter, so the
     * allow-list is what keeps this from being a SQL injection.
     */
    private List<NamedCount> topBy(String column, long linkId, int days) {
        String col = switch (column) {
            case "country", "referrer", "device_type", "browser", "os" -> column;
            default -> throw new IllegalArgumentException("Unsupported analytics dimension: " + column);
        };

        return jdbc.query("""
                SELECT COALESCE(NULLIF(%s, ''), 'Unknown') AS name, COUNT(*) AS n
                FROM clicks
                WHERE link_id = ? AND clicked_at >= CURRENT_DATE - make_interval(days => ?)
                GROUP BY 1
                ORDER BY n DESC, name ASC
                LIMIT %d
                """.formatted(col, TOP_N),
                (rs, i) -> new NamedCount(rs.getString("name"), rs.getLong("n")),
                linkId, days);
    }
}
