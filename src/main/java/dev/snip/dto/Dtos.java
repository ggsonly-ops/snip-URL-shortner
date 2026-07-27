package dev.snip.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.snip.domain.Link;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** Request and response payloads. Grouped in one file because each is a two-line record. */
public final class Dtos {

    private Dtos() {
    }

    // -- requests ------------------------------------------------------------

    public record CreateLinkRequest(
            @NotBlank(message = "url is required")
            @Size(max = 2048, message = "url must be at most 2048 characters")
            String url,

            @Pattern(regexp = "^[a-zA-Z0-9_-]{3,16}$",
                    message = "customAlias must be 3-16 characters: letters, digits, - or _")
            String customAlias,

            @Min(value = 1, message = "ttlDays must be at least 1")
            @Max(value = 3650, message = "ttlDays must be at most 3650")
            Integer ttlDays,

            @Size(min = 4, max = 128, message = "password must be 4-128 characters")
            String password
    ) {
    }

    public record UpdateLinkRequest(
            @Size(max = 2048) String url,
            @Min(1) @Max(3650) Integer ttlDays,
            Boolean active
    ) {
    }

    public record UnlockRequest(@NotBlank String password) {
    }

    // -- responses -----------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LinkResponse(
            String shortCode,
            String shortUrl,
            String longUrl,
            long clickCount,
            boolean passwordProtected,
            boolean active,
            Instant expiresAt,
            Instant createdAt,
            /** Set only when an identical URL already existed and was returned instead of a new row. */
            Boolean deduplicated,
            /** Snowflake internals, so the id scheme is inspectable rather than a claim in a README. */
            IdBreakdown id
    ) {
        public static LinkResponse from(Link link, String baseUrl, Boolean deduplicated) {
            return new LinkResponse(
                    link.getShortCode(),
                    baseUrl + "/" + link.getShortCode(),
                    link.getLongUrl(),
                    link.getClickCount(),
                    link.isPasswordProtected(),
                    link.isActive(),
                    link.getExpiresAt(),
                    link.getCreatedAt(),
                    deduplicated,
                    IdBreakdown.of(link.getId()));
        }
    }

    public record IdBreakdown(long raw, Instant timestamp, long machineId, long sequence) {
        public static IdBreakdown of(long id) {
            return new IdBreakdown(
                    id,
                    Instant.ofEpochMilli(dev.snip.id.SnowflakeIdGenerator.timestampOf(id)),
                    dev.snip.id.SnowflakeIdGenerator.machineIdOf(id),
                    dev.snip.id.SnowflakeIdGenerator.sequenceOf(id));
        }
    }

    public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
    }

    // -- analytics -----------------------------------------------------------

    public record DailyClicks(String day, long clicks) {
    }

    public record NamedCount(String name, long count) {
    }

    public record AnalyticsResponse(
            String shortCode,
            String longUrl,
            long totalClicks,
            int windowDays,
            List<DailyClicks> clicksPerDay,
            List<NamedCount> topCountries,
            List<NamedCount> topReferrers,
            List<NamedCount> devices,
            List<NamedCount> browsers,
            List<NamedCount> operatingSystems,
            /** True when country values are synthetic because no GeoIP database is configured. */
            boolean geoDataSynthetic
    ) {
    }

    // -- errors --------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(
            String error,
            String message,
            Long retryAfterSeconds,
            List<String> details
    ) {
        public static ErrorResponse of(String error, String message) {
            return new ErrorResponse(error, message, null, null);
        }
    }
}
