package dev.snip.repository;

import dev.snip.domain.Link;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LinkRepository extends JpaRepository<Link, Long> {

    Optional<Link> findByShortCode(String shortCode);

    Optional<Link> findByShortCodeAndActiveTrue(String shortCode);

    boolean existsByShortCode(String shortCode);

    /** Dedupe lookup. {@code ownerKey} is nullable, so this needs the IS NULL form. */
    @Query("""
            SELECT l FROM Link l
            WHERE l.urlHash = :urlHash
              AND (:ownerKey IS NULL AND l.ownerKey IS NULL OR l.ownerKey = :ownerKey)
              AND l.active = TRUE
              AND (l.expiresAt IS NULL OR l.expiresAt > :now)
              AND l.passwordHash IS NULL
            ORDER BY l.createdAt DESC
            LIMIT 1
            """)
    Optional<Link> findLiveDuplicate(@Param("urlHash") String urlHash,
                                     @Param("ownerKey") String ownerKey,
                                     @Param("now") Instant now);

    @Query("""
            SELECT l FROM Link l
            WHERE l.ownerKey = :ownerKey AND l.active = TRUE
            ORDER BY l.createdAt DESC
            """)
    Page<Link> findByOwner(@Param("ownerKey") String ownerKey, Pageable pageable);

    Optional<Link> findByShortCodeAndOwnerKey(String shortCode, String ownerKey);

    /** Cache warming: the hottest links, which is where the hit ratio actually comes from. */
    @Query("""
            SELECT l FROM Link l
            WHERE l.active = TRUE AND (l.expiresAt IS NULL OR l.expiresAt > :now)
            ORDER BY l.clickCount DESC
            LIMIT :limit
            """)
    List<Link> findHottest(@Param("now") Instant now, @Param("limit") int limit);

    /** Bloom-filter rebuild: stream just the codes, no entity hydration. */
    @Query("SELECT l.shortCode FROM Link l WHERE l.active = TRUE")
    List<String> findAllActiveShortCodes();

    @Query("SELECT COUNT(l) FROM Link l WHERE l.active = TRUE")
    long countActive();

    /**
     * Sweeper for expired links. Deactivating rather than deleting keeps the click
     * history queryable and keeps the short code from being silently reused.
     */
    @Modifying
    @Query("""
            UPDATE Link l SET l.active = FALSE, l.updatedAt = :now
            WHERE l.active = TRUE AND l.expiresAt IS NOT NULL AND l.expiresAt <= :now
            """)
    int deactivateExpired(@Param("now") Instant now);

    @Query("SELECT l.shortCode FROM Link l WHERE l.active = TRUE AND l.expiresAt IS NOT NULL AND l.expiresAt <= :now")
    List<String> findExpiredShortCodes(@Param("now") Instant now);
}
