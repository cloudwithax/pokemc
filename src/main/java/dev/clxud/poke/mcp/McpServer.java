package dev.clxud.poke.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.clxud.poke.PokeGenie;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * A minimal Model Context Protocol server that Poke connects to over the public
 * internet (via the user's tunnel). It speaks the HTTP+SSE transport Poke's docs
 * document — {@code GET /sse} opens an event stream, {@code POST /messages} feeds
 * JSON-RPC in — and also accepts single-shot Streamable-HTTP at {@code POST /mcp}
 * for the {@code npx poke tunnel} path. Every request must carry the bearer key
 * from {@link ApiKeyStore}; tool calls are dispatched to {@link PokeGenie}.
 */
public final class McpServer {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final int KEEPALIVE_SECONDS = 15;

    private final HttpServer server;
    private final ExecutorService pool;
    private final String apiKey;
    private final String serverVersion;
    private final PokeGenie genie;
    private final Logger logger;
    private final Map<String, SseSession> sessions = new ConcurrentHashMap<>();

    public McpServer(String bind, int port, String apiKey, String serverVersion, PokeGenie genie, Logger logger)
            throws IOException {
        this.apiKey = apiKey;
        this.serverVersion = serverVersion;
        this.genie = genie;
        this.logger = logger;
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "PokeMC-MCP");
            t.setDaemon(true);
            return t;
        });
        this.server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        this.server.setExecutor(pool);
        this.server.createContext("/sse", this::handleSse);
        this.server.createContext("/messages", this::handleMessages);
        this.server.createContext("/mcp", this::handleStreamable);
        this.server.createContext("/health", this::handleHealth);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        for (SseSession s : sessions.values()) {
            s.close();
        }
        sessions.clear();
        server.stop(0);
        pool.shutdownNow();
    }

    // ---- HTTP handlers -----------------------------------------------------

    /** Unauthenticated liveness probe — handy for verifying the tunnel reaches us. */
    private void handleHealth(HttpExchange ex) throws IOException {
        respond(ex, 200, "text/plain", "PokeMC MCP server is alive.");
    }

    /** Opens the SSE stream and tells the client where to POST its messages. */
    private void handleSse(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        if (!authorized(ex)) {
            respond(ex, 401, "text/plain", "Unauthorized");
            return;
        }
        String sessionId = UUID.randomUUID().toString();
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/event-stream");
        h.set("Cache-Control", "no-cache");
        h.set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);

        SseSession session = new SseSession(ex.getResponseBody());
        sessions.put(sessionId, session);
        logger.info("MCP SSE stream opened from " + ex.getRemoteAddress() + " (session " + sessionId + ")");
        try {
            session.event("endpoint", "/messages?sessionId=" + sessionId);
            while (!session.closed) {
                Thread.sleep(KEEPALIVE_SECONDS * 1000L);
                session.comment("keepalive");
            }
        } catch (IOException | InterruptedException disconnected) {
            // client went away or server stopping
        } finally {
            sessions.remove(sessionId);
            session.close();
            logger.fine("MCP SSE session closed: " + sessionId);
        }
    }

    /** Receives JSON-RPC for an open SSE session; responses go back over the stream. */
    private void handleMessages(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        if (!authorized(ex)) {
            respond(ex, 401, "text/plain", "Unauthorized");
            return;
        }
        String sessionId = queryParam(ex.getRequestURI().getRawQuery(), "sessionId");
        logger.info("MCP POST /messages from " + ex.getRemoteAddress() + " (session " + sessionId + ")");
        SseSession session = sessionId == null ? null : sessions.get(sessionId);

        List<JsonObject> requests;
        try {
            requests = asObjects(JsonParser.parseString(readBody(ex)));
        } catch (Exception e) {
            respond(ex, 400, "text/plain", "Bad JSON-RPC payload");
            return;
        }
        // Acknowledge the POST immediately; actual responses are pushed over SSE.
        respond(ex, 202, "text/plain", "");

        if (session == null) {
            logger.warning("MCP POST /messages with unknown sessionId=" + sessionId + " — dropping.");
            return;
        }
        for (JsonObject request : requests) {
            JsonObject response = dispatch(request);
            if (response != null) {
                try {
                    session.event("message", response.toString());
                } catch (IOException io) {
                    logger.fine("MCP SSE write failed: " + io.getMessage());
                }
            }
        }
    }

    /** Streamable-HTTP single-shot: dispatch and return the response inline. */
    private void handleStreamable(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "text/plain", "Method Not Allowed");
            return;
        }
        if (!authorized(ex)) {
            respond(ex, 401, "text/plain", "Unauthorized");
            return;
        }
        logger.info("MCP POST /mcp from " + ex.getRemoteAddress());
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(readBody(ex));
        } catch (Exception e) {
            respond(ex, 400, "text/plain", "Bad JSON-RPC payload");
            return;
        }
        JsonArray out = new JsonArray();
        for (JsonObject request : asObjects(parsed)) {
            JsonObject response = dispatch(request);
            if (response != null) {
                out.add(response);
            }
        }
        if (out.isEmpty()) {
            respond(ex, 202, "application/json", "");
            return;
        }
        respond(ex, 200, "application/json", parsed.isJsonArray() ? out.toString() : out.get(0).toString());
    }

    // ---- JSON-RPC dispatch -------------------------------------------------

    /** Returns the JSON-RPC response object, or null for notifications. */
    private JsonObject dispatch(JsonObject msg) {
        if (msg == null || !msg.has("method") || msg.get("method").isJsonNull()) {
            return null;
        }
        String method = msg.get("method").getAsString();
        JsonElement id = msg.get("id");
        boolean notification = id == null || id.isJsonNull();
        if ("tools/call".equals(method) && msg.has("params") && msg.get("params").isJsonObject()) {
            logger.info("MCP <- tools/call " + optString(msg.getAsJsonObject("params"), "name"));
        } else {
            logger.info("MCP <- " + method);
        }
        try {
            switch (method) {
                case "initialize":
                    return result(id, initializeResult());
                case "tools/list":
                    return result(id, toolsListResult());
                case "tools/call":
                    return result(id, toolsCallResult(msg.getAsJsonObject("params")));
                case "ping":
                    return result(id, new JsonObject());
                default:
                    if (notification || method.startsWith("notifications/")) {
                        return null;
                    }
                    return error(id, -32601, "Method not found: " + method);
            }
        } catch (Exception e) {
            logger.warning("MCP dispatch error for " + method + ": " + e.getMessage());
            return notification ? null : error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private JsonObject initializeResult() {
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        JsonObject info = new JsonObject();
        info.addProperty("name", "poke-mc");
        info.addProperty("version", serverVersion);
        JsonObject r = new JsonObject();
        r.addProperty("protocolVersion", PROTOCOL_VERSION);
        r.add("capabilities", caps);
        r.add("serverInfo", info);
        return r;
    }

    private JsonObject toolsListResult() {
        JsonObject r = new JsonObject();
        r.add("tools", ToolCatalog.tools());
        return r;
    }

    private JsonObject toolsCallResult(JsonObject params) {
        String name = params == null ? null : optString(params, "name");
        JsonObject args = params != null && params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments")
                : new JsonObject();
        String text = genie.dispatchTool(name, args);

        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text == null ? "" : text);
        JsonArray contentArr = new JsonArray();
        contentArr.add(content);
        JsonObject r = new JsonObject();
        r.add("content", contentArr);
        r.addProperty("isError", false);
        return r;
    }

    // ---- JSON-RPC envelope builders ---------------------------------------

    private static JsonObject result(JsonElement id, JsonObject result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id == null ? null : id);
        o.add("result", result);
        return o;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message);
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id == null ? null : id);
        o.add("error", err);
        return o;
    }

    // ---- low-level HTTP helpers -------------------------------------------

    private boolean authorized(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null) {
            return false;
        }
        return constantTimeEquals(header.trim(), "Bearer " + apiKey);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < x.length; i++) {
            diff |= x[i] ^ y[i];
        }
        return diff == 0;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } else {
            ex.close();
        }
    }

    private static List<JsonObject> asObjects(JsonElement parsed) {
        List<JsonObject> out = new ArrayList<>();
        if (parsed == null) {
            return out;
        }
        if (parsed.isJsonArray()) {
            for (JsonElement e : parsed.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    out.add(e.getAsJsonObject());
                }
            }
        } else if (parsed.isJsonObject()) {
            out.add(parsed.getAsJsonObject());
        }
        return out;
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }

    /** One SSE response stream; writes are synchronized so frames never interleave. */
    private static final class SseSession {
        private final OutputStream os;
        volatile boolean closed;

        SseSession(OutputStream os) {
            this.os = os;
        }

        synchronized void event(String event, String data) throws IOException {
            StringBuilder sb = new StringBuilder();
            if (event != null) {
                sb.append("event: ").append(event).append('\n');
            }
            for (String line : data.split("\n", -1)) {
                sb.append("data: ").append(line).append('\n');
            }
            sb.append('\n');
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        synchronized void comment(String text) throws IOException {
            os.write((": " + text + "\n\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        synchronized void close() {
            closed = true;
            try {
                os.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
