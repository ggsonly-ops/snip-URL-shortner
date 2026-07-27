package dev.snip.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Mints an API key.
 *
 * <p>There is deliberately no account system. The key <em>is</em> the credential: a
 * 256-bit random capability token that scopes which links you can manage and whose
 * analytics you can read. Nothing about it is stored server-side, so a lost key means
 * links that can no longer be managed.
 *
 * <p>That is a real limitation rather than a hidden one — it is called out in the README
 * too. It is the right shape for a service with no signup flow, and the upgrade path
 * (store a hash of the key, add scopes and revocation) is small and obvious.
 */
@RestController
@RequestMapping("/api/keys")
public class ApiKeyController {

    @PostMapping
    public Map<String, String> mint() {
        return Map.of(
                "apiKey", ApiKeys.mint(),
                "note", "Store this somewhere. It is a capability token, not an account: it is "
                        + "never written to the server and cannot be recovered.");
    }
}
