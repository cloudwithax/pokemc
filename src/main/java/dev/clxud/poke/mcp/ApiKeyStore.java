package dev.clxud.poke.mcp;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates the bearer key Poke must present to call our MCP server. The key
 * itself lives in config.yml (the single source of configuration); this class
 * only mints a fresh one when none is set yet.
 */
public final class ApiKeyStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyStore() {
    }

    /** A fresh, URL-safe MCP bearer key, e.g. {@code pmc_...}. */
    public static String generate() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return "pmc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
