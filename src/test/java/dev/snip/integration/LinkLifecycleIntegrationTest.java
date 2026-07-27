package dev.snip.integration;

import dev.snip.cache.LinkCache;
import dev.snip.id.Base62;
import dev.snip.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LinkLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    LinkCache cache;

    private String key() {
        return "snip_test_" + UUID.randomUUID();
    }

    @Test
    void createsAndRedirects() {
        String target = "https://example.com/articles/hello-world";
        ResponseEntity<Map<String, Object>> created = createLink(target, key());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String code = (String) created.getBody().get("shortCode");
        assertThat(code).isNotBlank();
        // Generated codes are Base62 of a Snowflake id. The length is 10 today and
        // becomes 11 once (now - EPOCH) << 22 crosses 62^10, which happens about six
        // years after the 2025-01-01 epoch. Asserting a hard 11 would be a test that
        // starts failing on a calendar date.
        assertThat(code).matches("[0-9A-Za-z]{10,11}");
        assertThat(created.getBody().get("longUrl")).isEqualTo(target);

        ResponseEntity<Void> redirect = noFollow()
                .getForEntity(url("/" + code), Void.class);

        assertThat(redirect.getStatusCode()).isEqualTo(HttpStatus.FOUND);   // 302, not 301
        assertThat(redirect.getHeaders().getLocation()).hasToString(target);
        assertThat(redirect.getHeaders().getCacheControl()).contains("no-cache");
    }

    @Test
    void theGeneratedCodeDecodesBackToTheRowId() {
        ResponseEntity<Map<String, Object>> created = createLink("https://example.com/decode-me", key());
        String code = (String) created.getBody().get("shortCode");

        @SuppressWarnings("unchecked")
        Map<String, Object> id = (Map<String, Object>) created.getBody().get("id");
        long raw = ((Number) id.get("raw")).longValue();

        // One Snowflake id serves as both the primary key and the source of the code.
        assertThat(Base62.decode(code)).isEqualTo(raw);
        assertThat(SnowflakeIdGenerator.machineIdOf(raw)).isEqualTo(1);
    }

    @Test
    void normalisesTheStoredUrl() {
        ResponseEntity<Map<String, Object>> created =
                createLink("HTTPS://Example.com:443/Path/?a=1#frag", key());
        assertThat(created.getBody().get("longUrl")).isEqualTo("https://example.com/Path/?a=1");
    }

    @Test
    void deduplicatesTheSameUrlForTheSameOwner() {
        String apiKey = key();
        String target = "https://example.com/dedupe-me?b=2&a=1";

        ResponseEntity<Map<String, Object>> first = createLink(target, apiKey);
        // Different parameter order, same resource: the canonical hash matches.
        ResponseEntity<Map<String, Object>> second = createLink("https://example.com/dedupe-me?a=1&b=2", apiKey);

        assertThat(second.getBody().get("shortCode")).isEqualTo(first.getBody().get("shortCode"));
        assertThat(second.getBody().get("deduplicated")).isEqualTo(true);
    }

    @Test
    void doesNotDeduplicateAcrossDifferentOwners() {
        String target = "https://example.com/shared-target";
        ResponseEntity<Map<String, Object>> mine = createLink(target, key());
        ResponseEntity<Map<String, Object>> theirs = createLink(target, key());

        assertThat(theirs.getBody().get("shortCode")).isNotEqualTo(mine.getBody().get("shortCode"));
    }

    @Test
    void honoursCustomAliases() {
        String alias = "my-link-" + Integer.toHexString(new java.util.Random().nextInt(1 << 16));
        ResponseEntity<Map<String, Object>> created =
                createLink(Map.of("url", "https://example.com/aliased", "customAlias", alias), key());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("shortCode")).isEqualTo(alias);

        assertThat(noFollow().getForEntity(url("/" + alias), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.FOUND);
    }

    @Test
    void rejectsATakenAlias() {
        String alias = "taken-" + Integer.toHexString(new java.util.Random().nextInt(1 << 16));
        createLink(Map.of("url", "https://example.com/one", "customAlias", alias), key());

        ResponseEntity<Map<String, Object>> second =
                createLink(Map.of("url", "https://example.com/two", "customAlias", alias), key());

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("error")).isEqualTo("ALIAS_UNAVAILABLE");
    }

    @Test
    void rejectsReservedAliasesThatWouldShadowOurOwnRoutes() {
        for (String reserved : new String[]{"api", "actuator", "admin", "health"}) {
            ResponseEntity<Map<String, Object>> res =
                    createLink(Map.of("url", "https://example.com/x", "customAlias", reserved), key());
            assertThat(res.getStatusCode())
                    .as("alias '%s' must be reserved", reserved)
                    .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Test
    void rejectsInvalidUrlsAndAliases() {
        assertThat(createLink("javascript:alert(1)", key()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createLink("not a url", key()).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createLink(Map.of("url", "https://example.com/x", "customAlias", "no"), key())
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void returns404ForAnUnknownCode() {
        ResponseEntity<Void> res = noFollow().getForEntity(url("/doesnotexist"), Void.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listsUpdatesAndDeletesOwnedLinks() {
        String apiKey = key();
        String code = (String) createLink("https://example.com/manage-me", apiKey).getBody().get("shortCode");

        // list
        ResponseEntity<Map> list = rest.exchange(url("/api/links?page=0&size=20"), HttpMethod.GET,
                new HttpEntity<>(json(apiKey)), Map.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) list.getBody().get("totalItems")).isGreaterThanOrEqualTo(1);

        // update the target
        ResponseEntity<Map> updated = rest.exchange(url("/api/links/" + code), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("url", "https://example.com/moved"), json(apiKey)), Map.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().get("longUrl")).isEqualTo("https://example.com/moved");

        // the redirect must follow the update, which only happens if the cache was
        // invalidated on write - this is the regression test for "invalidate, don't update"
        assertThat(noFollow().getForEntity(url("/" + code), Void.class).getHeaders().getLocation())
                .hasToString("https://example.com/moved");

        // delete
        ResponseEntity<Void> deleted = rest.exchange(url("/api/links/" + code), HttpMethod.DELETE,
                new HttpEntity<>(json(apiKey)), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(noFollow().getForEntity(url("/" + code), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refusesToLetOneKeyManageAnotherKeysLink() {
        String owner = key();
        String code = (String) createLink("https://example.com/private", owner).getBody().get("shortCode");

        ResponseEntity<Map> res = rest.exchange(url("/api/links/" + code), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("url", "https://example.com/hijacked"), json(key())), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void expiredLinksStopResolving() {
        String apiKey = key();
        String code = (String) createLink(Map.of(
                "url", "https://example.com/short-lived", "ttlDays", 1), apiKey).getBody().get("shortCode");

        assertThat(noFollow().getForEntity(url("/" + code), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.FOUND);

        // Move the expiry into the past directly, then clear the cache entry so the next
        // read goes to Postgres. Waiting a day is not a test strategy.
        redis.delete("snip:link:" + code);
        expireNow(code);

        assertThat(noFollow().getForEntity(url("/" + code), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void expireNow(String code) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource());
        jdbc.update("UPDATE links SET expires_at = NOW() - INTERVAL '1 hour' WHERE short_code = ?", code);
    }

    @Autowired
    javax.sql.DataSource dataSource;

    private javax.sql.DataSource dataSource() {
        return dataSource;
    }

    @Test
    void passwordProtectedLinksChallengeBeforeRedirecting() {
        String apiKey = key();
        String code = (String) createLink(Map.of(
                        "url", "https://example.com/secret-doc",
                        "password", "hunter2"), apiKey)
                .getBody().get("shortCode");

        // A bare GET must not redirect.
        HttpHeaders acceptHtml = new HttpHeaders();
        acceptHtml.set("Accept", "text/html");
        ResponseEntity<String> challenge = noFollow().exchange(url("/" + code), HttpMethod.GET,
                new HttpEntity<>(acceptHtml), String.class);
        assertThat(challenge.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(challenge.getBody()).contains("password");

        // Wrong password: still challenged.
        ResponseEntity<Map> wrong = rest.exchange(url("/api/links/" + code + "/unlock"), HttpMethod.POST,
                new HttpEntity<>(Map.of("password", "wrong"), json(null)), Map.class);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // Right password: the target comes back.
        ResponseEntity<Map> ok = rest.exchange(url("/api/links/" + code + "/unlock"), HttpMethod.POST,
                new HttpEntity<>(Map.of("password", "hunter2"), json(null)), Map.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("longUrl")).isEqualTo("https://example.com/secret-doc");
    }

    @Test
    void servesAQrCode() {
        String code = (String) createLink("https://example.com/qr", key()).getBody().get("shortCode");
        ResponseEntity<byte[]> png = rest.getForEntity(url("/api/links/" + code + "/qr?size=128"), byte[].class);

        assertThat(png.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(png.getBody()).isNotEmpty();
        // PNG magic number.
        assertThat(png.getBody()[0] & 0xff).isEqualTo(0x89);
        assertThat(new String(png.getBody(), 1, 3)).isEqualTo("PNG");
    }

    @Test
    void mintsApiKeys() {
        ResponseEntity<Map> res = rest.postForEntity(url("/api/keys"), null, Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) res.getBody().get("apiKey")).startsWith("snip_").hasSizeGreaterThan(40);
    }
}
