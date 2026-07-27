package dev.snip.service;

import dev.snip.cache.LinkCache;
import dev.snip.cache.ShortCodeBloomFilter;
import dev.snip.config.SnipProperties;
import dev.snip.domain.Link;
import dev.snip.domain.ResolvedLink;
import dev.snip.dto.Dtos.CreateLinkRequest;
import dev.snip.dto.Dtos.LinkResponse;
import dev.snip.dto.Dtos.PageResponse;
import dev.snip.dto.Dtos.UpdateLinkRequest;
import dev.snip.exception.AliasUnavailableException;
import dev.snip.exception.ForbiddenException;
import dev.snip.exception.LinkNotFoundException;
import dev.snip.exception.PasswordRequiredException;
import dev.snip.id.Base62;
import dev.snip.id.SnowflakeIdGenerator;
import dev.snip.repository.LinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LinkService {

    /**
     * Aliases that would shadow our own routes. Without this list a user registers the
     * alias {@code api} and takes over the API namespace — a one-line bug with an
     * embarrassing consequence.
     */
    private static final Set<String> RESERVED = Set.of(
            "api", "admin", "login", "signup", "signin", "logout", "static", "assets",
            "health", "metrics", "actuator", "docs", "swagger-ui", "favicon.ico",
            "robots.txt", "app", "dashboard", "analytics", "unlock", "index", "about",
            "terms", "privacy", "settings", "new", "edit", "delete");

    private final LinkRepository repo;
    private final LinkResolver resolver;
    private final UrlValidator urlValidator;
    private final SnowflakeIdGenerator idGenerator;
    private final LinkCache cache;
    private final ShortCodeBloomFilter bloom;
    private final PasswordEncoder passwordEncoder;
    private final SnipProperties props;

    // -- create --------------------------------------------------------------

    @Transactional
    public LinkResponse create(CreateLinkRequest req, String apiKey) {
        String normalised = urlValidator.validateAndNormalise(req.url());
        String urlHash = urlValidator.sha256(urlValidator.canonicalForHash(normalised));
        Instant now = Instant.now();

        // Dedupe: the same URL from the same owner returns the link that already exists.
        // Skipped when the caller asked for a specific alias, a TTL or a password, since
        // those make it a materially different link even if the target matches.
        boolean plainRequest = req.customAlias() == null && req.ttlDays() == null && req.password() == null;
        if (plainRequest) {
            Optional<Link> existing = repo.findLiveDuplicate(urlHash, apiKey, now);
            if (existing.isPresent()) {
                return LinkResponse.from(existing.get(), props.getBaseUrl(), Boolean.TRUE);
            }
        }

        // One Snowflake id, used both as the primary key and (when no alias was asked
        // for) as the source of the short code. Generating two ids here would burn an
        // id per link for nothing and make the code and the row id unrelated.
        long id = idGenerator.nextId();

        String code;
        if (req.customAlias() != null) {
            String alias = req.customAlias();
            if (RESERVED.contains(alias.toLowerCase(Locale.ROOT))) {
                throw new AliasUnavailableException("'" + alias + "' is reserved");
            }
            if (repo.existsByShortCode(alias)) {
                throw new AliasUnavailableException("Alias '" + alias + "' is already taken");
            }
            code = alias;
        } else {
            code = Base62.encode(id);
        }

        Link link = new Link();
        link.setId(id);
        link.setShortCode(code);
        link.setLongUrl(normalised);
        link.setUrlHash(urlHash);
        link.setOwnerKey(apiKey);
        link.setActive(true);
        link.setCreatedAt(now);
        link.setUpdatedAt(now);
        if (req.ttlDays() != null) {
            link.setExpiresAt(now.plus(req.ttlDays(), ChronoUnit.DAYS));
        }
        if (req.password() != null && !req.password().isBlank()) {
            link.setPasswordHash(passwordEncoder.encode(req.password()));
        }

        try {
            repo.saveAndFlush(link);
        } catch (DataIntegrityViolationException e) {
            // existsByShortCode() followed by save() is a check-then-act race: two
            // concurrent requests can both pass the check before either inserts. The
            // unique constraint in Postgres is the real guarantee; the pre-check only
            // exists to produce a nicer error most of the time.
            // Validate for UX, constrain for correctness.
            log.debug("Unique violation inserting code {}", code);
            throw new AliasUnavailableException("Alias '" + code + "' is already taken");
        }

        // Populate the cache only once the transaction has actually committed. Writing
        // inside the transaction would leave the cache holding a row that a rollback
        // then erased.
        afterCommit(() -> {
            cache.put(code, ResolvedLink.of(link));
            bloom.add(code);
        });

        return LinkResponse.from(link, props.getBaseUrl(), null);
    }

    // -- read ----------------------------------------------------------------

    @Transactional(readOnly = true)
    public LinkResponse get(String code, String apiKey) {
        Link link = repo.findByShortCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        requireOwner(link, apiKey);
        return LinkResponse.from(link, props.getBaseUrl(), null);
    }

    @Transactional(readOnly = true)
    public PageResponse<LinkResponse> list(String apiKey, int page, int size) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ForbiddenException("An X-API-Key is required to list links");
        }
        Page<Link> result = repo.findByOwner(apiKey, PageRequest.of(page, Math.min(size, 100)));
        List<LinkResponse> items = result.getContent().stream()
                .map(l -> LinkResponse.from(l, props.getBaseUrl(), null))
                .toList();
        return new PageResponse<>(items, page, size, result.getTotalElements(), result.getTotalPages());
    }

    // -- update / delete -----------------------------------------------------

    @Transactional
    public LinkResponse update(String code, UpdateLinkRequest req, String apiKey) {
        Link link = repo.findByShortCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        requireOwner(link, apiKey);

        if (req.url() != null && !req.url().isBlank()) {
            String normalised = urlValidator.validateAndNormalise(req.url());
            link.setLongUrl(normalised);
            link.setUrlHash(urlValidator.sha256(urlValidator.canonicalForHash(normalised)));
        }
        if (req.ttlDays() != null) {
            link.setExpiresAt(Instant.now().plus(req.ttlDays(), ChronoUnit.DAYS));
        }
        if (req.active() != null) {
            link.setActive(req.active());
        }
        link.setUpdatedAt(Instant.now());
        repo.save(link);

        // Invalidate, do not overwrite.
        //
        // There is still a small race here worth naming: a concurrent reader can load
        // the old row, our delete runs, and then that reader writes its now-stale value
        // into the cache. Production answers are a delayed double-delete, or driving
        // invalidation from the database's change log (CDC/Debezium) so cache updates
        // are ordered by the commit log rather than by application timing.
        afterCommit(() -> cache.evict(code));

        return LinkResponse.from(link, props.getBaseUrl(), null);
    }

    /**
     * Soft delete. The row stays so click history remains queryable and the short code
     * is never silently handed to a different destination.
     */
    @Transactional
    public void delete(String code, String apiKey) {
        Link link = repo.findByShortCode(code).orElseThrow(() -> new LinkNotFoundException(code));
        requireOwner(link, apiKey);
        link.setActive(false);
        link.setUpdatedAt(Instant.now());
        repo.save(link);

        // The bloom filter cannot un-set bits without creating false negatives for other
        // members, so this code stays a false positive until the next rebuild. That costs
        // one wasted lookup, which then hits the negative cache. Acceptable.
        afterCommit(() -> {
            cache.evict(code);
            cache.putNegative(code);
        });
    }

    // -- password-protected links --------------------------------------------

    /**
     * Verifies the password for a protected link and returns its target.
     * BCrypt verification is deliberately slow, which is why it lives here and not on
     * the plain redirect path.
     */
    @Transactional(readOnly = true)
    public String unlock(String code, String password) {
        Link link = resolver.loadEntity(code).orElseThrow(() -> new LinkNotFoundException(code));
        if (!link.isPasswordProtected()) {
            return link.getLongUrl();
        }
        if (password == null || !passwordEncoder.matches(password, link.getPasswordHash())) {
            throw new PasswordRequiredException(code, password != null);
        }
        return link.getLongUrl();
    }

    // -- helpers -------------------------------------------------------------

    private void requireOwner(Link link, String apiKey) {
        if (link.getOwnerKey() == null) {
            // Anonymous links have no owner, so nobody can claim management rights over
            // them. Creating with an X-API-Key is what makes a link manageable.
            throw new ForbiddenException("Anonymous links cannot be managed; create with an X-API-Key");
        }
        if (!link.getOwnerKey().equals(apiKey)) {
            throw new ForbiddenException("This link belongs to a different API key");
        }
    }

    /** Runs after the surrounding transaction commits, or immediately if there is none. */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
