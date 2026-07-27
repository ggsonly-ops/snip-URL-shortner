package dev.snip.web;

import java.security.SecureRandom;
import java.util.Base64;

final class ApiKeys {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    static String mint() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "snip_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Treats a blank header as absent, so " " does not become a distinct owner. */
    static String normalise(String apiKey) {
        if (apiKey == null) {
            return null;
        }
        String trimmed = apiKey.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
