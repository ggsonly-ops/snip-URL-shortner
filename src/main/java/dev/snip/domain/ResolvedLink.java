package dev.snip.domain;

import java.time.Instant;

/**
 * The minimum a redirect needs to know, and therefore exactly what gets cached.
 *
 * <p>Deliberately not the JPA entity: the hot path should not hydrate a managed
 * entity, and caching a fat object wastes Redis memory and serialisation time on
 * fields (owner, url hash, click count) that the redirect never reads.
 *
 * @param id                 Snowflake id, carried so the click event can name the link
 *                           without a second lookup
 * @param longUrl            destination
 * @param passwordProtected  when true the redirect must challenge before sending the
 *                           user on; the hash itself is never cached
 * @param expiresAt          null for links that never expire
 */
public record ResolvedLink(long id, String longUrl, boolean passwordProtected, Instant expiresAt) {

    public static ResolvedLink of(Link link) {
        return new ResolvedLink(link.getId(), link.getLongUrl(), link.isPasswordProtected(), link.getExpiresAt());
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }
}
