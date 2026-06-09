package dev.clxud.poke.telegram;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.clxud.poke.poke.PokeSender;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Self-contained Telegram bridge backed by bundled TDLib JSON runtime.
 *
 * <p>The plugin ships a tiny stdio helper binary plus {@code libtdjson.so} in
 * its resources. At runtime we extract those files to the plugin data folder,
 * start the helper process, and talk to TDLib over newline-delimited JSON.
 *
 * <p>When this bridge is enabled it becomes the primary transport to Poke:
 * wishes are sent as Telegram messages to {@code @interaction_poke_bot}, and
 * replies from that chat are routed back into Minecraft.
 */
public final class TdTelegramBridge implements PokeSender, AutoCloseable {

    public interface PokeReplyHandler {
        void onPokeReply(String text);
    }

    private static final JsonParser PARSER = new JsonParser();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final String RUNTIME_VERSION = "linux-x86_64-v1";
    private static final String TDJSON_SONAME = "libtdjson.so.1.8.64";
    // E.164: a leading +, a non-zero country-code digit, then up to 14 more digits.
    private static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{1,14}");

    private final Path dataFolder;
    private final Path phoneFile;
    private final String applicationVersion;
    private final int apiId;
    private final String apiHash;
    private final String botUsername;
    private final PokeReplyHandler replyHandler;
    private final Logger logger;

    // Login secrets. Seeded from config.yml but also settable live via the
    // /poke link|code|password console commands, so first-time linking never
    // requires editing config or restarting.
    private volatile String phoneNumber;
    private volatile String authCode;
    private volatile String authPassword;

    private final ConcurrentHashMap<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    // The linked account's own identifiers (name/handle/phone), fetched on link,
    // so the genie can scrub them from anything Poke says back. See PrivacyGuard.
    private final Set<String> privacyTokens = ConcurrentHashMap.newKeySet();

    private volatile String authorizationState = "unknown";
    private volatile long botChatId;

    private Process process;
    private BufferedWriter writer;
    private Thread readerThread;

    public TdTelegramBridge(Path dataFolder,
                            String applicationVersion,
                            int apiId,
                            String apiHash,
                            String phoneNumber,
                            String authCode,
                            String authPassword,
                            String botUsername,
                            PokeReplyHandler replyHandler,
                            Logger logger) {
        this.dataFolder = Objects.requireNonNull(dataFolder);
        this.phoneFile = dataFolder.resolve("telegram-phone");
        this.applicationVersion = Objects.requireNonNull(applicationVersion);
        this.apiId = apiId;
        this.apiHash = Objects.requireNonNull(apiHash);
        this.phoneNumber = phoneNumber == null ? "" : phoneNumber.trim();
        this.authCode = authCode == null ? "" : authCode.trim();
        this.authPassword = authPassword == null ? "" : authPassword;
        this.botUsername = Objects.requireNonNull(botUsername);
        this.replyHandler = Objects.requireNonNull(replyHandler);
        this.logger = Objects.requireNonNull(logger);
    }

    /**
     * Launches the TDLib helper and lets authorization proceed asynchronously.
     *
     * <p>Returns immediately — it never blocks on (or fails for) a missing login
     * code. As TDLib pushes {@code updateAuthorizationState}, the reader thread
     * drives the flow in {@link #advanceAuth}: known secrets are submitted
     * automatically, and anything still needed is requested from the admin via a
     * console prompt ({@code /poke link|code|password}). Once a session exists on
     * disk, restarts skip straight to ready with no prompts at all.
     */
    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        Path runtimeDir = extractBundledRuntime();
        startProcess(runtimeDir);

        // Keep TDLib logs quiet unless something fatal happens. Fire-and-forget:
        // TDLib pushes the initial authorization state on its own.
        writeJson(requestOf("setLogVerbosityLevel").with("new_verbosity_level", 1).build());
        logger.info("Telegram bridge starting; authorizing @" + botUsername + "...");
    }

    @Override
    public void send(String message) throws Exception {
        if (!running.get()) {
            throw new IllegalStateException("Telegram bridge is not running.");
        }
        if (botChatId == 0) {
            throw new IllegalStateException("Telegram bot chat is not resolved yet.");
        }

        JsonObject text = requestOf("formattedText")
                .with("text", message)
                .build();
        JsonObject inputMessage = requestOf("inputMessageText")
                .with("text", text)
                .build();
        JsonObject sendMessage = requestOf("sendMessage")
                .with("chat_id", botChatId)
                .with("input_message_content", inputMessage)
                .build();

        request(sendMessage, REQUEST_TIMEOUT);
        logger.info("Telegram -> @" + botUsername + ": " + truncate(message, 160));
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            if (process != null && process.isAlive()) {
                writeJson(requestOf("close").build());
            }
        } catch (Exception ignored) {
            // best effort
        }

        pending.forEach((k, v) -> v.completeExceptionally(new IllegalStateException("Telegram bridge closed")));
        pending.clear();

        if (process != null) {
            process.destroy();
        }
        logger.info("Telegram bridge stopped.");
    }

    public long getBotChatId() {
        return botChatId;
    }

    public String getBotUsername() {
        return botUsername;
    }

    /**
     * Reacts to a single TDLib authorization state. Called from the reader thread
     * (on pushed {@code updateAuthorizationState}) and from the submit* commands;
     * {@code synchronized} so those never interleave. Outbound calls are
     * fire-and-forget — TDLib answers with the next state, which lands back here.
     */
    private synchronized void advanceAuth(String stateType) {
        authorizationState = stateType;
        try {
            switch (stateType) {
                case "authorizationStateWaitTdlibParameters" -> sendTdlibParameters();
                case "authorizationStateWaitEncryptionKey" ->
                        writeJson(requestOf("checkDatabaseEncryptionKey").with("encryption_key", "").build());
                case "authorizationStateWaitPhoneNumber" -> {
                    String phone = !phoneNumber.isBlank() ? phoneNumber : readPersistedPhone();
                    if (!phone.isBlank()) {
                        phoneNumber = phone;
                        writeJson(requestOf("setAuthenticationPhoneNumber").with("phone_number", phone).build());
                    } else {
                        prompt("Telegram needs the account phone number — run:  /poke link <+phone>");
                    }
                }
                case "authorizationStateWaitCode" -> {
                    if (!authCode.isBlank()) {
                        String code = authCode;
                        authCode = ""; // single-use; a wrong code re-emits this state
                        writeJson(requestOf("checkAuthenticationCode").with("code", code).build());
                    } else {
                        prompt("Telegram sent a login code to your app — run:  /poke code <code>");
                    }
                }
                case "authorizationStateWaitPassword" -> {
                    if (!authPassword.isBlank()) {
                        String password = authPassword;
                        authPassword = ""; // single-use; a wrong password re-emits this state
                        writeJson(requestOf("checkAuthenticationPassword").with("password", password).build());
                    } else {
                        prompt("Telegram needs your cloud (2FA) password — run:  /poke password <password>");
                    }
                }
                case "authorizationStateReady" -> onReady();
                case "authorizationStateWaitOtherDeviceConfirmation" ->
                        prompt("Telegram wants QR/other-device confirmation, which this bridge can't do. "
                                + "Disable QR login for this account, then re-link with /poke link <+phone>.");
                case "authorizationStateLoggingOut", "authorizationStateClosing", "authorizationStateClosed" ->
                        logger.warning("Telegram authorization state: " + stateType);
                default -> { /* transient states (e.g. WaitRegistration) — ignore */ }
            }
        } catch (IOException e) {
            logger.warning("Telegram auth step failed at " + stateType + ": " + e.getMessage());
        }
    }

    private void sendTdlibParameters() throws IOException {
        Path sessionDir = dataFolder.resolve("telegram-session");
        Files.createDirectories(sessionDir.resolve("data"));
        Files.createDirectories(sessionDir.resolve("files"));

        writeJson(requestOf("setTdlibParameters")
                .with("database_directory", sessionDir.resolve("data").toAbsolutePath().toString())
                .with("files_directory", sessionDir.resolve("files").toAbsolutePath().toString())
                .with("use_test_dc", false)
                .with("use_file_database", true)
                .with("use_chat_info_database", true)
                .with("use_message_database", true)
                .with("use_secret_chats", false)
                .with("system_language_code", "en")
                .with("device_model", "PokeMC")
                .with("system_version", System.getProperty("os.name", "Linux"))
                .with("application_version", applicationVersion)
                .with("api_id", apiId)
                .with("api_hash", apiHash)
                .build());
    }

    /** Resolve the bot chat off the reader thread (it uses blocking {@link #request}). */
    private void onReady() {
        if (botChatId != 0) {
            return; // already resolved this run
        }
        persistPhone(phoneNumber);
        logger.info("Telegram account linked. Resolving @" + botUsername + "...");
        Thread t = new Thread(() -> {
            try {
                resolveBotChat();
                fetchSelfIdentity();
                logger.info("Telegram bridge ready. Bot chat id = " + botChatId + " for @" + botUsername);
            } catch (Exception e) {
                logger.warning("Telegram linked but could not resolve @" + botUsername + ": " + e.getMessage());
            }
        }, "PokeMC-TelegramResolve");
        t.setDaemon(true);
        t.start();
    }

    /** The linked account's own identifiers, for {@code PrivacyGuard} to scrub from replies. */
    public Set<String> privacyTokens() {
        return privacyTokens;
    }

    /** Pulls the logged-in account's own name/handle/phone so we can redact them later. */
    private void fetchSelfIdentity() {
        addPrivacyToken(phoneNumber);
        try {
            JsonObject me = request(requestOf("getMe").build(), REQUEST_TIMEOUT);
            addPrivacyToken(optString(me, "first_name"));
            addPrivacyToken(optString(me, "last_name"));
            String phone = optString(me, "phone_number");
            addPrivacyToken(phone);
            if (!phone.isBlank()) {
                addPrivacyToken("+" + phone);
            }
            if (me.has("usernames") && me.get("usernames").isJsonObject()) {
                JsonObject usernames = me.getAsJsonObject("usernames");
                addPrivacyToken(optString(usernames, "editable_username"));
                if (usernames.has("active_usernames") && usernames.get("active_usernames").isJsonArray()) {
                    usernames.getAsJsonArray("active_usernames")
                            .forEach(el -> addPrivacyToken(el.getAsString()));
                }
            }
            addPrivacyToken(optString(me, "username")); // legacy single-username field
        } catch (Exception e) {
            logger.fine("Could not fetch self identity for the privacy guard: " + e.getMessage());
        }
    }

    private void addPrivacyToken(String value) {
        if (value != null && !value.isBlank()) {
            privacyTokens.add(value.trim());
        }
    }

    // ---- interactive linking (driven by /poke link|code|password) ----------

    /** Sets the phone and submits it if TDLib is waiting for one. */
    public synchronized String submitPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "Usage: /poke link <+phone>  (E.164, e.g. +15551234567)";
        }
        // Tolerate common separators, then require strict E.164 — otherwise no-op.
        String normalized = phone.trim().replaceAll("[\\s().-]", "");
        if (!E164.matcher(normalized).matches()) {
            return "That isn't a valid international number. Use E.164 format — a + and country "
                    + "code followed by digits, e.g. /poke link +15551234567.";
        }
        this.phoneNumber = normalized;
        persistPhone(this.phoneNumber);
        if (!running.get()) {
            return "Telegram bridge is not running.";
        }
        try {
            writeJson(requestOf("setAuthenticationPhoneNumber").with("phone_number", this.phoneNumber).build());
        } catch (IOException e) {
            return "Could not send phone number to Telegram: " + e.getMessage();
        }
        return "Sent " + this.phoneNumber + " to Telegram. Watch for the login code, then run /poke code <code>.";
    }

    /** Submits a login code; only meaningful while TDLib waits for one. */
    public synchronized String submitCode(String code) {
        if (code == null || code.isBlank()) {
            return "Usage: /poke code <code>";
        }
        if (!running.get()) {
            return "Telegram bridge is not running.";
        }
        if (!"authorizationStateWaitCode".equals(authorizationState)) {
            return "Telegram isn't waiting for a login code right now (" + friendlyState() + ").";
        }
        this.authCode = "";
        try {
            writeJson(requestOf("checkAuthenticationCode").with("code", code.trim()).build());
        } catch (IOException e) {
            return "Could not send the code to Telegram: " + e.getMessage();
        }
        return "Submitted login code to Telegram...";
    }

    /** Submits a cloud (2FA) password; only meaningful while TDLib waits for one. */
    public synchronized String submitPassword(String password) {
        if (password == null || password.isBlank()) {
            return "Usage: /poke password <password>";
        }
        if (!running.get()) {
            return "Telegram bridge is not running.";
        }
        if (!"authorizationStateWaitPassword".equals(authorizationState)) {
            return "Telegram isn't waiting for a 2FA password right now (" + friendlyState() + ").";
        }
        this.authPassword = "";
        try {
            writeJson(requestOf("checkAuthenticationPassword").with("password", password).build());
        } catch (IOException e) {
            return "Could not send the password to Telegram: " + e.getMessage();
        }
        return "Submitted 2FA password to Telegram...";
    }

    /** True once the account is linked and the bot chat is resolved. */
    public boolean isLinked() {
        return running.get() && botChatId != 0;
    }

    /** Short, admin-facing description of where linking stands. */
    public String friendlyState() {
        if (botChatId != 0) {
            return "linked";
        }
        return switch (authorizationState) {
            case "authorizationStateWaitPhoneNumber" -> "needs phone — /poke link <+phone>";
            case "authorizationStateWaitCode" -> "needs login code — /poke code <code>";
            case "authorizationStateWaitPassword" -> "needs 2FA password — /poke password <password>";
            case "authorizationStateReady" -> "linked, resolving bot chat...";
            default -> "authorizing (" + authorizationState + ")";
        };
    }

    private void prompt(String message) {
        String line = "------------------------------------------------------------";
        logger.warning(line);
        logger.warning("[Telegram] " + message);
        logger.warning(line);
    }

    /**
     * Stores the linked phone (chmod 600) so a future session wipe can re-link
     * from console without re-editing config. Best-effort; never fatal.
     */
    private void persistPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return;
        }
        try {
            Files.writeString(phoneFile, phone);
            phoneFile.toFile().setReadable(false, false);
            phoneFile.toFile().setReadable(true, true);
            phoneFile.toFile().setWritable(false, false);
            phoneFile.toFile().setWritable(true, true);
        } catch (IOException e) {
            logger.fine("Could not persist Telegram phone: " + e.getMessage());
        }
    }

    private String readPersistedPhone() {
        try {
            return Files.isRegularFile(phoneFile) ? Files.readString(phoneFile).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void resolveBotChat() throws Exception {
        JsonObject chat = request(requestOf("searchPublicChat").with("username", botUsername).build(), REQUEST_TIMEOUT);
        if ("error".equals(typeOf(chat))) {
            throw new IllegalStateException("Could not resolve @" + botUsername + ": " + optString(chat, "message"));
        }
        this.botChatId = optLong(chat, "id");
        if (botChatId == 0) {
            throw new IllegalStateException("TDLib returned no chat id for @" + botUsername + ".");
        }
        request(requestOf("openChat").with("chat_id", botChatId).build(), REQUEST_TIMEOUT);
    }

    private void startProcess(Path runtimeDir) throws IOException {
        Path executable = runtimeDir.resolve("tdjson-bridge");
        ProcessBuilder pb = new ProcessBuilder(executable.toAbsolutePath().toString());
        pb.directory(runtimeDir.toFile());
        String existingLd = pb.environment().getOrDefault("LD_LIBRARY_PATH", "");
        String newLd = runtimeDir.toAbsolutePath() + (existingLd.isBlank() ? "" : ":" + existingLd);
        pb.environment().put("LD_LIBRARY_PATH", newLd);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        process = pb.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        readerThread = new Thread(() -> readLoop(reader), "PokeMC-TelegramBridge");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void readLoop(BufferedReader reader) {
        try {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                handleIncomingLine(line);
            }
        } catch (IOException e) {
            if (running.get()) {
                logger.warning("Telegram bridge reader died: " + e.getMessage());
            }
        } finally {
            running.set(false);
        }
    }

    private void handleIncomingLine(String line) {
        JsonObject obj = PARSER.parse(line).getAsJsonObject();

        if (obj.has("@extra")) {
            String extra = obj.get("@extra").getAsString();
            CompletableFuture<JsonObject> future = pending.remove(extra);
            if (future != null) {
                future.complete(obj);
                return;
            }
        }

        String type = typeOf(obj);
        if ("updateAuthorizationState".equals(type)) {
            JsonObject state = obj.getAsJsonObject("authorization_state");
            advanceAuth(typeOf(state));
            return;
        }

        if ("updateNewMessage".equals(type)) {
            JsonObject message = obj.getAsJsonObject("message");
            if (message == null) {
                return;
            }
            long chatId = optLong(message, "chat_id");
            if (chatId != botChatId) {
                return;
            }
            if (message.has("is_outgoing") && message.get("is_outgoing").getAsBoolean()) {
                return;
            }
            String text = extractText(message.getAsJsonObject("content"));
            if (text == null || text.isBlank()) {
                return;
            }
            logger.info("Telegram <- @" + botUsername + ": " + truncate(text, 160));
            replyHandler.onPokeReply(text);
        }
    }

    private String extractText(JsonObject content) {
        if (content == null) {
            return null;
        }
        String type = typeOf(content);
        if ("messageText".equals(type)) {
            JsonObject text = content.getAsJsonObject("text");
            return text == null ? null : optString(text, "text");
        }
        if (content.has("caption") && content.get("caption").isJsonObject()) {
            return optString(content.getAsJsonObject("caption"), "text");
        }
        return null;
    }

    private JsonObject request(JsonObject request, Duration timeout) throws Exception {
        String extra = UUID.randomUUID().toString();
        request.addProperty("@extra", extra);
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(extra, future);
        writeJson(request);
        try {
            JsonObject response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if ("error".equals(typeOf(response))) {
                throw new IllegalStateException("TDLib error " + optLong(response, "code") + ": " + optString(response, "message"));
            }
            return response;
        } catch (TimeoutException e) {
            pending.remove(extra);
            throw new IllegalStateException("Timed out waiting for TDLib response to " + typeOf(request), e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("TDLib request failed", e.getCause());
        }
    }

    private synchronized void writeJson(JsonObject request) throws IOException {
        writer.write(request.toString());
        writer.write('\n');
        writer.flush();
    }

    private Path extractBundledRuntime() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (!os.contains("linux") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
            throw new IllegalStateException("Bundled Telegram runtime currently supports only Linux x86_64. Found: " + os + " / " + arch);
        }

        Path runtimeDir = dataFolder.resolve("telegram-runtime").resolve(RUNTIME_VERSION);
        Files.createDirectories(runtimeDir);
        extractResource("/native/linux-x86_64/tdjson-bridge", runtimeDir.resolve("tdjson-bridge"), true);
        Path libPath = runtimeDir.resolve("libtdjson.so");
        extractResource("/native/linux-x86_64/libtdjson.so", libPath, false);
        // tdjson-bridge links against TDLib's SONAME, so make sure that exact
        // filename exists alongside the plain .so we bundle in the jar.
        Files.copy(libPath, runtimeDir.resolve(TDJSON_SONAME), StandardCopyOption.REPLACE_EXISTING);
        return runtimeDir;
    }

    private void extractResource(String resource, Path target, boolean executable) throws IOException {
        try (InputStream in = TdTelegramBridge.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Bundled Telegram runtime resource missing: " + resource);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (executable) {
            target.toFile().setExecutable(true, true);
        }
    }

    private static String typeOf(JsonObject obj) {
        return obj == null || !obj.has("@type") ? "" : obj.get("@type").getAsString();
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return "";
        }
        JsonElement value = obj.get(key);
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    private static long optLong(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return obj.get(key).getAsLong();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static Builder requestOf(String type) {
        return new Builder(type);
    }

    private static final class Builder {
        private final JsonObject obj = new JsonObject();

        private Builder(String type) {
            obj.addProperty("@type", type);
        }

        private Builder with(String key, String value) {
            obj.addProperty(key, value);
            return this;
        }

        private Builder with(String key, boolean value) {
            obj.addProperty(key, value);
            return this;
        }

        private Builder with(String key, int value) {
            obj.addProperty(key, value);
            return this;
        }

        private Builder with(String key, long value) {
            obj.addProperty(key, value);
            return this;
        }

        private Builder with(String key, JsonObject value) {
            obj.add(key, value);
            return this;
        }

        private JsonObject build() {
            return obj;
        }
    }
}
