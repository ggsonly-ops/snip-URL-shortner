package dev.snip.web;

import dev.snip.analytics.ClickEventPublisher;
import dev.snip.domain.ResolvedLink;
import dev.snip.exception.LinkNotFoundException;
import dev.snip.exception.PasswordRequiredException;
import dev.snip.metrics.SnipMetrics;
import dev.snip.ratelimit.ClientIpResolver;
import dev.snip.service.LinkResolver;
import dev.snip.service.LinkService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * The hot path. Everything about this controller is shaped by wanting a redirect to be
 * a cache read and a 302, and nothing else.
 *
 * <h2>302, not 301 — the classic question, answered</h2>
 * <table>
 *   <caption>redirect status comparison</caption>
 *   <tr><th></th><th>301 Moved Permanently</th><th>302 Found</th></tr>
 *   <tr><td>Browser caches it</td><td>yes, aggressively and often indefinitely</td><td>no</td></tr>
 *   <tr><td>Second click reaches us</td><td><b>no</b></td><td>yes</td></tr>
 *   <tr><td>Analytics</td><td>first click per browser only</td><td>every click</td></tr>
 *   <tr><td>Server load</td><td>much lower</td><td>higher</td></tr>
 *   <tr><td>Target can change later</td><td>effectively no</td><td>yes</td></tr>
 * </table>
 *
 * <p>302, because the entire value of the product is click analytics and a 301 makes
 * every subsequent click invisible. It also freezes the destination: edit a link and
 * browsers holding the cached 301 keep going to the old target forever. 301 would be
 * right if the priority were minimising cost and links were immutable. 307 is the
 * related one worth knowing — like 302, but it guarantees the HTTP method is not
 * rewritten on the redirect.
 *
 * <p>The {@code Cache-Control: no-cache} matters too: without it an intermediate proxy
 * may cache even a 302 and the same analytics blind spot reappears.
 */
@RestController
@RequiredArgsConstructor
public class RedirectController {

    /**
     * Alias grammar is 3-16 of [A-Za-z0-9_-]; generated codes are 11 Base62 chars.
     * Constraining it in the mapping means /api, /actuator and the SPA routes never
     * even reach this handler.
     */
    private static final String CODE = "/{code:[a-zA-Z0-9_-]{3,16}}";

    private final LinkResolver resolver;
    private final LinkService linkService;
    private final ClickEventPublisher clicks;
    private final ClientIpResolver clientIps;
    private final SnipMetrics metrics;

    @GetMapping(CODE)
    public ResponseEntity<?> redirect(@PathVariable String code, HttpServletRequest request) {
        long start = System.nanoTime();
        LinkResolver.Resolution resolution = resolver.resolveWithOutcome(code);

        if (!resolution.found()) {
            record(resolution.outcome(), start);
            throw new LinkNotFoundException(code);
        }

        ResolvedLink link = resolution.link();

        if (link.passwordProtected()) {
            // No click is recorded here: the user has not reached the destination yet.
            record("protected", start);
            return passwordChallenge(code, false);
        }

        // Fire and forget. This is the only work between resolving and responding, and
        // it is a single ~0.2ms XADD that can fail without affecting the response.
        clicks.publishAsync(link.id(), request, clientIps.resolve(request));

        record(resolution.outcome(), start);
        return redirectTo(link.longUrl());
    }

    /** Form post from the challenge page. */
    @PostMapping(value = CODE, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<?> unlockForm(@PathVariable String code,
                                        @RequestParam(name = "password", required = false) String password,
                                        HttpServletRequest request) {
        try {
            String target = linkService.unlock(code, password);
            clicks.publishAsync(resolver.resolve(code).map(ResolvedLink::id).orElse(0L),
                    request, clientIps.resolve(request));
            return redirectTo(target);
        } catch (PasswordRequiredException e) {
            return passwordChallenge(code, true);
        }
    }

    /** JSON equivalent, for the SPA and for API clients. */
    @PostMapping(value = "/api/links/{code}/unlock", consumes = MediaType.APPLICATION_JSON_VALUE)
    public java.util.Map<String, String> unlockJson(@PathVariable String code,
                                                    @RequestBody dev.snip.dto.Dtos.UnlockRequest req,
                                                    HttpServletRequest request) {
        String target = linkService.unlock(code, req.password());
        clicks.publishAsync(resolver.resolve(code).map(ResolvedLink::id).orElse(0L),
                request, clientIps.resolve(request));
        return java.util.Map.of("longUrl", target);
    }

    private ResponseEntity<Void> redirectTo(String longUrl) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(longUrl))
                .cacheControl(CacheControl.noCache())
                .build();
    }

    /**
     * Served as HTML so a plain browser hitting a protected link gets something usable,
     * with 401 so an API client gets a status it can branch on.
     */
    private ResponseEntity<String> passwordChallenge(String code, boolean failed) {
        String safeCode = escape(code);
        String message = failed
                ? "<p class=\"err\">That password was not correct.</p>"
                : "<p class=\"hint\">This link is password protected.</p>";

        String html = """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Protected link</title>
                <style>
                  :root{color-scheme:light dark}
                  body{font:16px/1.5 system-ui,sans-serif;display:grid;place-items:center;
                       min-height:100vh;margin:0;background:Canvas;color:CanvasText}
                  form{max-width:22rem;width:90%%;padding:2rem;border:1px solid color-mix(in srgb,CanvasText 20%%,transparent);
                       border-radius:12px}
                  h1{font-size:1.1rem;margin:0 0 .5rem}
                  input,button{width:100%%;padding:.6rem .7rem;font:inherit;border-radius:8px;
                       border:1px solid color-mix(in srgb,CanvasText 25%%,transparent);box-sizing:border-box}
                  button{margin-top:.75rem;cursor:pointer;background:CanvasText;color:Canvas;border:0}
                  .err{color:#c0392b}.hint{opacity:.7}
                  p{margin:0 0 1rem;font-size:.9rem}
                </style></head>
                <body>
                  <form method="post" action="/%s">
                    <h1>snip/%s</h1>
                    %s
                    <input type="password" name="password" placeholder="Password" autofocus required>
                    <button type="submit">Open link</button>
                  </form>
                </body></html>
                """.formatted(safeCode, safeCode, message);

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(html);
    }

    private void record(String outcome, long startNanos) {
        metrics.recordRedirect(outcome, TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startNanos));
    }

    /**
     * The path variable is already constrained to [A-Za-z0-9_-] by the mapping, so this
     * cannot currently do anything. It is here because the constraint and the template
     * are far apart, and a future loosening of the regex should not silently become an
     * XSS.
     */
    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
