package dev.clxud.poke.poke;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Thin client for Poke's outbound messaging API
 * ({@code POST /inbound/api-message}).
 *
 * <p>This endpoint is fire-and-forget: it forwards the message to the user's
 * Poke agent and returns only an acknowledgement. Poke's actual reasoning and
 * personality reach Minecraft NOT through this response, but by Poke calling
 * back into our MCP server (see {@code mcp.McpServer}). We use this client only
 * to nudge Poke — "a player wished X" or the startup self-test.
 */
public final class PokeClient implements PokeSender {

    private static final Gson GSON = new Gson();

    private final HttpClient http;
    private final String apiUrl;
    private final String apiKey;
    private final Duration timeout;

    public PokeClient(String apiUrl, String apiKey, int timeoutSeconds) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** Sends a message into the user's Poke. Throws if the API does not return 2xx. */
    public void send(String message) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("message", message);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Poke API returned HTTP " + response.statusCode() + ": "
                    + truncate(response.body(), 300));
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
