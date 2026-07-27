package dev.snip.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "links")
@Getter
@Setter
@NoArgsConstructor
public class Link {

    /**
     * Snowflake id assigned by the application. {@code @Id} with no
     * {@code @GeneratedValue} — the app is the authority, not a sequence.
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "short_code", nullable = false, unique = true, length = 16, updatable = false)
    private String shortCode;

    @Column(name = "long_url", nullable = false, columnDefinition = "text")
    private String longUrl;

    @Column(name = "url_hash", nullable = false, length = 64)
    private String urlHash;

    @Column(name = "owner_key", length = 64, updatable = false)
    private String ownerKey;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "click_count", nullable = false)
    private long clickCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public boolean isPasswordProtected() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /** Live = active and not past its TTL. Only live links resolve. */
    public boolean isResolvable(Instant now) {
        return active && !isExpired(now);
    }
}
