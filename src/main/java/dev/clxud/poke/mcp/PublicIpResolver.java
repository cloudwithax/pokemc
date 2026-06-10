package dev.clxud.poke.mcp;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Best-effort discovery of this host's own public IP, used to auto-include it in
 * the MCP allowlist. Hits a couple of popular, rarely-blocked echo services in
 * turn and returns the first plausible answer; callers fall back to loopback if
 * none respond. Timeouts are tight so a slow service can't stall server startup.
 */
public final class PublicIpResolver {

    // Ordered by popularity / reachability; each returns the caller's IP as plain text.
    private static final List<String> ENDPOINTS = List.of(
            "https://api.ipify.org",
            "https://checkip.amazonaws.com",
            "https://icanhazip.com");

    private static final Duration TIMEOUT = Duration.ofMillis(1500);

    private PublicIpResolver() {
    }

    /** The detected public IP, or empty if every endpoint failed. */
    public static Optional<String> detect(Logger logger) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        for (String url : ENDPOINTS) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(TIMEOUT).GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                String ip = resp.body() == null ? "" : resp.body().trim();
                if (resp.statusCode() == 200 && isIpLiteral(ip)) {
                    logger.info("Detected public IP " + ip + " via " + url + "; adding it to the MCP allowlist.");
                    return Optional.of(ip);
                }
            } catch (Exception e) {
                logger.fine("Public IP lookup via " + url + " failed: " + e.getMessage());
            }
        }
        logger.warning("Could not detect a public IP from any service; the allowlist falls back to loopback only.");
        return Optional.empty();
    }

    /** True only for a literal IPv4/IPv6 address — never a hostname (no DNS). */
    private static boolean isIpLiteral(String s) {
        if (s.isEmpty() || s.length() > 45) {
            return false;
        }
        boolean looksNumeric = s.matches("\\d{1,3}(\\.\\d{1,3}){3}") || s.contains(":");
        if (!looksNumeric) {
            return false;
        }
        try {
            InetAddress.getByName(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
