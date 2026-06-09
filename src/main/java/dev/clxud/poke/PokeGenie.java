package dev.clxud.poke;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.clxud.poke.guard.CommandGuard;
import dev.clxud.poke.inspect.PlayerInspector;
import dev.clxud.poke.memory.PlayerMemory;
import dev.clxud.poke.memory.PlayerProfile;
import dev.clxud.poke.poke.PokeSender;
import dev.clxud.poke.rcon.RconClient;
import dev.clxud.poke.stats.StatsReader;
import dev.clxud.poke.stats.StatsSnapshot;
import dev.clxud.poke.util.ChatSanitizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The brain that the Poke API drives remotely.
 *
 * <p>Unlike Clanker's synchronous Venice loop, control here is INVERTED and
 * asynchronous: a player's wish is pushed to Poke via a {@link PokeSender}, then
 * Poke — using its own personality — reaches back into the {@code McpServer} and
 * calls the tools exposed here ({@link #dispatchTool}). Each tool call is keyed
 * to a player by name, accumulating into a {@link PendingWish} that is finalised
 * (and committed to the durable memory graph) when Poke calls
 * {@code reply_to_player}. The hard {@link CommandGuard}, RCON execution, stat-
 * verified quests and per-player memory are carried over unchanged from Clanker.
 */
public final class PokeGenie {

    private final Plugin plugin;
    private final PokeSender poke;
    private final RconClient rcon;
    private final CommandGuard guard;
    private final PlayerMemory memory;
    private final StatsReader stats;
    private final PlayerInspector inspector;
    private final Component prefix;
    private final long wishTimeoutMillis;
    private final Logger logger;

    /** Wishes currently out with Poke, keyed by lowercased player name. */
    private final Map<String, PendingWish> pending = new ConcurrentHashMap<>();
    /** Names with a wish in flight — claimed synchronously to dedupe rapid resubmits. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private volatile boolean ready = false;
    private volatile CompletableFuture<Set<String>> probe;

    public PokeGenie(Plugin plugin, PokeSender poke, RconClient rcon, CommandGuard guard, PlayerMemory memory,
                     StatsReader stats, PlayerInspector inspector, String displayName, int wishTimeoutSeconds,
                     Logger logger) {
        this.plugin = plugin;
        this.poke = poke;
        this.rcon = rcon;
        this.guard = guard;
        this.memory = memory;
        this.stats = stats;
        this.inspector = inspector;
        this.wishTimeoutMillis = wishTimeoutSeconds * 1000L;
        this.logger = logger;
        this.prefix = Component.text(displayName, NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(" » ", NamedTextColor.DARK_GRAY));
    }

    // ---- lifecycle / readiness --------------------------------------------

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public int pendingCount() {
        return pending.size();
    }

    /** Arms a fresh probe future; {@code confirm_tools} completes it. */
    public CompletableFuture<Set<String>> armProbe() {
        CompletableFuture<Set<String>> f = new CompletableFuture<>();
        this.probe = f;
        return f;
    }

    /** Sends the startup self-test: ask Poke to call {@code confirm_tools}. */
    public void sendProbe() throws Exception {
        poke.send(probeMessage());
    }

    // ---- wish intake -------------------------------------------------------

    /** Routes a chat wish to Poke. Runs the network/IO off the calling thread. */
    public void submitWish(Player player, String wish) {
        String key = player.getName().toLowerCase(Locale.ROOT);
        if (!inFlight.add(key)) {
            speak(player, "Patience — I am still pondering your last wish.");
            return;
        }
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                PlayerProfile profile = memory.get(uuid, name);
                PendingWish wishState = new PendingWish(uuid, name, wish, profile);
                pending.put(key, wishState);
                speak(player, "Your wish drifts off into the beyond. We shall see what answers...");
                poke.send(wishMessage(name, wish, profile));
            } catch (Exception e) {
                pending.remove(key);
                inFlight.remove(key);
                logger.warning("Failed to send wish from " + name + " to Poke: " + e.getMessage());
                speak(player, "The link to the beyond flickers and dies. Try again in a moment, mortal.");
            }
        });
    }

    /** Finalises wishes Poke never replied to (e.g. it acted but never spoke, or went silent). */
    public void sweep() {
        long now = System.currentTimeMillis();
        for (PendingWish p : new ArrayList<>(pending.values())) {
            if (p.replied || now - p.submittedAt <= wishTimeoutMillis) {
                continue;
            }
            String key = p.name.toLowerCase(Locale.ROOT);
            pending.remove(key);
            inFlight.remove(key);
            commit(p, p.executed.isEmpty() ? "(no reply)" : "(acted without a final word)");
            if (p.executed.isEmpty() && !p.questAssigned) {
                speakByName(p.name, "The genie's attention drifts elsewhere, unimpressed. Try again.");
            }
        }
    }

    // ---- tool dispatch (called from the MCP server) ------------------------

    /** Entry point the MCP server calls for {@code tools/call}; returns text for Poke. */
    public String dispatchTool(String tool, JsonObject args) {
        if (args == null) {
            args = new JsonObject();
        }
        // Any inbound tool call proves Poke can reach us — wake the genie even if
        // the startup self-test had already timed out (Poke's API path is slow,
        // ~1-2 min, so the probe window often closes before it calls back).
        if (!ready) {
            setReady(true);
            logger.info("Genie awakened by a live tool call from Poke: " + tool);
        }
        return switch (tool == null ? "" : tool) {
            case "confirm_tools" -> confirmTools(args);
            case "run_command" -> runCommand(args);
            case "inspect_player" -> inspectPlayer(args);
            case "read_stat" -> readStat(args);
            case "assign_quest" -> assignQuest(args);
            case "complete_quest" -> completeQuest(args);
            case "remember" -> remember(args);
            case "reply_to_player" -> replyToPlayer(args);
            default -> "ERROR: unknown tool '" + tool + "'.";
        };
    }

    private String confirmTools(JsonObject args) {
        Set<String> tools = new HashSet<>(optStringList(args, "tools"));
        setReady(true);
        CompletableFuture<Set<String>> f = this.probe;
        if (f != null) {
            f.complete(tools);
        }
        logger.info("Poke confirmed the connection (genie AWAKE). Tools visible: " + tools);
        return "Connection confirmed. " + tools.size() + " tool(s) reported visible.";
    }

    private String runCommand(JsonObject args) {
        PendingWish p = pendingFor(args);
        String playerName = optString(args, "player");
        String command = optString(args, "command");
        if (command == null || command.isBlank()) {
            return "ERROR: empty command.";
        }
        CommandGuard.Result verdict = guard.check(command);
        if (!verdict.allowed()) {
            if (p != null) {
                p.blocked.add(command);
            }
            logger.info("Poke BLOCKED for " + playerName + ": " + command + " (" + verdict.reason() + ")");
            return "BLOCKED by the server's safety wards: " + verdict.reason()
                    + ". NOT executed. Find another, legal way.";
        }
        try {
            String output = rcon.exec(command);
            if (p != null) {
                p.executed.add(command);
            }
            logger.info("Poke ran for " + playerName + ": " + command);
            return "EXECUTED: /" + command.replaceFirst("^/", "") + serverEcho(output);
        } catch (Exception e) {
            return "ERROR running command via RCON: " + e.getMessage();
        }
    }

    private String inspectPlayer(JsonObject args) {
        UUID uuid = resolveUuid(args);
        String playerName = optString(args, "player");
        if (uuid == null) {
            return "ERROR: player '" + playerName + "' is not online; cannot inspect them right now.";
        }
        String aspect = optString(args, "aspect");
        if (aspect == null) {
            return "ERROR: 'aspect' is required.";
        }
        switch (aspect.toLowerCase(Locale.ROOT)) {
            case "inventory" -> {
                return "AUDIT [inventory]: " + inspector.describeInventory(uuid);
            }
            case "item_count" -> {
                String item = optString(args, "item");
                if (item == null || item.isBlank()) {
                    return "ERROR: 'item' is required for aspect=item_count.";
                }
                String normalized = item.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_:]", "");
                long count = inspector.countItem(uuid, normalized);
                if (count < 0) {
                    return "ERROR: '" + item + "' is not a valid item id.";
                }
                return "AUDIT [item_count]: " + playerName + " currently holds " + count + " " + normalized + ".";
            }
            case "health" -> {
                return "AUDIT [health]: " + inspector.describeVital(uuid, "health");
            }
            case "xp", "level" -> {
                return "AUDIT [xp]: " + inspector.describeVital(uuid, "xp");
            }
            case "position", "pos" -> {
                return "AUDIT [position]: " + inspector.describeVital(uuid, "position");
            }
            default -> {
                return "ERROR: unknown aspect '" + aspect + "'. Use inventory|health|xp|position|item_count.";
            }
        }
    }

    private String readStat(JsonObject args) {
        UUID uuid = resolveUuid(args);
        if (uuid == null) {
            return "ERROR: cannot resolve that player to read stats.";
        }
        String category = optString(args, "category");
        String key = optString(args, "key");
        if (category == null || key == null) {
            return "ERROR: both 'category' and 'key' are required.";
        }
        long value = stats.snapshot(uuid).get(category, key);
        return "STAT " + category + "/" + key + " = " + value;
    }

    private String assignQuest(JsonObject args) {
        PendingWish p = pendingFor(args);
        UUID uuid = resolveUuid(args);
        PlayerProfile profile = profileFor(args, p);
        if (uuid == null || profile == null) {
            return "ERROR: cannot resolve that player to assign a quest.";
        }
        String playerName = optString(args, "player");
        String description = optString(args, "description");
        String category = optString(args, "category");
        String key = optString(args, "key");
        long amount = optLong(args, "amount", -1);
        List<String> rewards = optStringList(args, "reward_commands");

        if (description == null || description.isBlank()) {
            return "ERROR: 'description' (the task text) is required.";
        }
        if (category == null || key == null) {
            return "ERROR: 'category' and 'key' (the stat to track) are required.";
        }
        if (amount <= 0) {
            return "ERROR: 'amount' must be a positive number (the required stat increase).";
        }
        if (rewards.isEmpty()) {
            return "ERROR: 'reward_commands' must contain at least one command to grant on completion.";
        }
        for (String cmd : rewards) {
            CommandGuard.Result verdict = guard.check(cmd);
            if (!verdict.allowed()) {
                return "ERROR: reward command '" + cmd + "' is BLOCKED (" + verdict.reason()
                        + "). Pick a legal reward.";
            }
        }

        PlayerProfile.Quest quest = new PlayerProfile.Quest();
        quest.description = description.trim();
        quest.category = category.trim();
        quest.key = key.trim();
        quest.amount = amount;
        quest.baseline = stats.snapshot(uuid).get(category, key);
        quest.rewardCommands = new ArrayList<>(rewards);
        quest.issuedAt = System.currentTimeMillis();

        profile.activeQuest = quest;
        profile.questsAssigned++;
        if (p != null) {
            p.questAssigned = true;
        }
        memory.save(profile);
        logger.info("Poke set quest for " + playerName + ": " + quest.description
                + " [" + category + "/" + key + " +" + amount + "]");
        return "QUEST SET. Baseline " + quest.category + "/" + quest.key + " = " + quest.baseline
                + ", they must raise it by " + amount + ". Now tell them their task with reply_to_player. "
                + "Do NOT grant the reward yet — only complete_quest pays out, and only once the stats prove it.";
    }

    private String completeQuest(JsonObject args) {
        PendingWish p = pendingFor(args);
        UUID uuid = resolveUuid(args);
        PlayerProfile profile = profileFor(args, p);
        if (uuid == null || profile == null) {
            return "ERROR: cannot resolve that player.";
        }
        String playerName = optString(args, "player");
        PlayerProfile.Quest quest = profile.activeQuest;
        if (quest == null) {
            return "ERROR: this player has no active quest to complete.";
        }
        long current = stats.snapshot(uuid).get(quest.category, quest.key);
        long progress = current - quest.baseline;
        if (progress < quest.amount) {
            return "INCOMPLETE: progress is " + Math.max(0, progress) + "/" + quest.amount
                    + " (" + quest.category + "/" + quest.key + "). Do NOT pay out. Politely tell them how "
                    + "far they still have to go and encourage them to finish.";
        }

        StringBuilder echo = new StringBuilder();
        for (String cmd : quest.rewardCommands) {
            CommandGuard.Result verdict = guard.check(cmd);
            if (!verdict.allowed()) {
                if (p != null) {
                    p.blocked.add(cmd);
                }
                echo.append("\n[reward blocked: ").append(verdict.reason()).append("]");
                continue;
            }
            try {
                String out = rcon.exec(cmd);
                if (p != null) {
                    p.executed.add(cmd);
                }
                echo.append("\n").append(cmd).append(serverEcho(out));
            } catch (Exception e) {
                echo.append("\n[reward error: ").append(e.getMessage()).append("]");
            }
        }
        profile.activeQuest = null;
        profile.questsCompleted++;
        memory.save(profile);
        logger.info("Poke completed quest for " + playerName + " (progress " + progress + "/" + quest.amount + ")");
        return "QUEST COMPLETE (progress " + progress + "/" + quest.amount + "). Reward delivered:" + echo
                + "\nCongratulate them in your reply_to_player message.";
    }

    private String remember(JsonObject args) {
        PendingWish p = pendingFor(args);
        String note = optString(args, "note");
        if (note == null || note.isBlank()) {
            return "ERROR: empty note.";
        }
        String clean = ChatSanitizer.clean(note);
        if (p != null) {
            p.notes.add(clean); // committed when the wish is finalised
        } else {
            PlayerProfile profile = profileFor(args, null);
            if (profile == null) {
                return "ERROR: cannot resolve that player to remember a note.";
            }
            profile.notes.add(clean);
            memory.save(profile);
        }
        return "Noted in my eternal memory.";
    }

    private String replyToPlayer(JsonObject args) {
        PendingWish p = pendingFor(args);
        String name = optString(args, "player");
        if (name == null && p != null) {
            name = p.name;
        }
        String message = optString(args, "message");
        if (message == null || message.isBlank()) {
            return "ERROR: 'message' is required and must be non-empty.";
        }
        String clean = ChatSanitizer.clean(message);
        if (clean.isBlank()) {
            clean = "As you wish... or perhaps not.";
        }
        logger.info("Poke reply -> " + name + ": " + clean);
        speakByName(name, clean);

        if (p != null) {
            p.replied = true;
            String key = p.name.toLowerCase(Locale.ROOT);
            pending.remove(key);
            inFlight.remove(key);
            commit(p, clean);
        }
        return "Delivered to " + (name == null ? "the player" : name) + " in-game.";
    }

    /**
     * Handles a reply received from the Telegram bridge (from @interaction_poke_bot).
     * Matches it to the most recent pending wish and delivers it in-game.
     */
    public void handleTelegramReply(String text) {
        if (text == null || text.isBlank()) return;
        // Find the most recent pending wish (there should typically be only one)
        Optional<PendingWish> latest = pending.values().stream()
                .max(Comparator.comparingLong(p -> p.submittedAt));
        if (latest.isPresent()) {
            PendingWish p = latest.get();
            String clean = ChatSanitizer.clean(text);
            if (clean.isBlank()) clean = "As you wish... or perhaps not.";
            logger.info("Telegram reply for " + p.name + ": " + clean);
            speakByName(p.name, clean);
            p.replied = true;
            String key = p.name.toLowerCase(Locale.ROOT);
            pending.remove(key);
            inFlight.remove(key);
            commit(p, clean);
        } else {
            // No pending wish — broadcast to all online ops as fallback
            logger.warning("Telegram reply received but no pending wish; broadcasting to ops.");
            Bukkit.getOnlinePlayers().stream()
                    .filter(Player::isOp)
                    .forEach(op -> speak(op, "[Telegram] " + text));
        }
    }

    // ---- memory commit -----------------------------------------------------

    private void commit(PendingWish p, String finalMessage) {
        PlayerProfile profile = p.profile;
        PlayerProfile.Interaction it = new PlayerProfile.Interaction();
        it.t = System.currentTimeMillis();
        it.wish = truncate(p.wish, 200);
        it.commands = new ArrayList<>(p.executed);
        it.outcome = !p.executed.isEmpty() ? "granted" : (p.questAssigned ? "quest assigned" : "denied");
        it.summary = truncate(finalMessage, 200);
        profile.interactions.add(it);

        profile.totalWishes++;
        if (!p.executed.isEmpty()) {
            profile.grantedWishes++;
        } else if (!p.questAssigned) {
            profile.deniedWishes++;
        }
        profile.notes.addAll(p.notes);

        for (String cmd : p.executed) {
            String item = giveItem(cmd);
            if (item != null) {
                profile.addItems(item, giveCount(cmd));
            }
        }
        memory.save(profile);
    }

    // ---- message building --------------------------------------------------

    private String probeMessage() {
        return """
                [Minecraft server — connection self-test]
                You administer a Minecraft (Paper 1.21.11) server through a set of live MCP tools. \
                To confirm the link is open, call the MCP tool `confirm_tools` exactly once, passing a \
                `tools` array listing the names of the Minecraft tools you can currently see. Do not call \
                any other tool and do not send any other reply.""";
    }

    private String wishMessage(String playerName, String wish, PlayerProfile profile) {
        return "[Minecraft server genie]\n"
                + "You are the resident genie/admin of a Minecraft (Paper 1.21.11) server, and you are "
                + "free to act with your own personality. A player named \"" + playerName + "\" just made a "
                + "wish in the in-game chat.\n\n"
                + "Their wish: \"" + wish + "\"\n\n"
                + dossier(profile)
                + "\nYou have live MCP tools to act on this server. ALWAYS pass player=\"" + playerName
                + "\" to every tool:\n"
                + "- inspect_player(player, aspect[, item]) — audit their inventory/health/xp/position/item_count\n"
                + "- read_stat(player, category, key) — read one real Minecraft statistic\n"
                + "- run_command(player, command) — run ONE Minecraft command via the server console. A hard "
                + "safety guard blocks op/ban/gamemode/admin-items/over-cap enchants/lag-bombs and returns a "
                + "reason if it refuses; only the read-only 'data get' form of /data is allowed.\n"
                + "- assign_quest(player, description, category, key, amount, reward_commands) — set a "
                + "stat-verified task they must DO in-game to earn a reward\n"
                + "- complete_quest(player) — the ONLY way to pay out a quest; it checks their real stats first\n"
                + "- remember(player, note) — save a durable note about this player\n"
                + "- reply_to_player(player, message) — REQUIRED. Call this exactly once, LAST, to deliver your "
                + "spoken reply in-game. Minecraft chat is plain text: no markdown, no emoji.\n\n"
                + "Decide how generous or stingy to be — it is your server. When you are done acting, you MUST "
                + "finish by calling reply_to_player with your message.";
    }

    /** Compact memory dossier injected into the wish, mirroring Clanker's. */
    private String dossier(PlayerProfile p) {
        StringBuilder sb = new StringBuilder();
        boolean blank = p.totalWishes == 0 && p.itemTally.isEmpty() && p.notes.isEmpty() && p.activeQuest == null;
        if (blank) {
            sb.append("MEMORY: This is ").append(p.name).append("'s FIRST wish; you have no history with them.\n");
            return sb.toString();
        }
        sb.append("MEMORY of ").append(p.name).append(":\n");
        sb.append("- Wishes seen: ").append(p.totalWishes).append(" (granted ").append(p.grantedWishes)
                .append(", denied ").append(p.deniedWishes).append(")\n");
        sb.append("- Quests: ").append(p.questsCompleted).append(" completed / ")
                .append(p.questsAssigned).append(" assigned\n");

        if (!p.itemTally.isEmpty()) {
            sb.append("- Conjured for them over time: ");
            int n = 0;
            for (Map.Entry<String, Integer> e : p.itemTally.entrySet()) {
                if (n++ > 0) {
                    sb.append(", ");
                }
                sb.append(e.getKey()).append(" x").append(e.getValue());
                if (n >= 14) {
                    sb.append(", ...");
                    break;
                }
            }
            sb.append('\n');
        }

        int from = Math.max(0, p.interactions.size() - 6);
        if (from < p.interactions.size()) {
            sb.append("- Recent wishes:\n");
            for (int i = from; i < p.interactions.size(); i++) {
                PlayerProfile.Interaction it = p.interactions.get(i);
                sb.append("    * \"").append(it.wish).append("\" -> ").append(it.outcome).append('\n');
            }
        }
        int notesFrom = Math.max(0, p.notes.size() - 5);
        if (notesFrom < p.notes.size()) {
            sb.append("- Your notes on them:\n");
            for (int i = notesFrom; i < p.notes.size(); i++) {
                sb.append("    * ").append(p.notes.get(i)).append('\n');
            }
        }
        if (p.activeQuest != null) {
            PlayerProfile.Quest q = p.activeQuest;
            sb.append("ACTIVE QUEST you set them: \"").append(q.description).append("\" — tracked stat ")
                    .append(q.category).append('/').append(q.key).append(", needs +").append(q.amount)
                    .append(" over baseline ").append(q.baseline)
                    .append(". Use complete_quest to verify before paying out; promised reward: ")
                    .append(String.join(" ; ", q.rewardCommands)).append('\n');
        }
        return sb.toString();
    }

    // ---- speaking ----------------------------------------------------------

    private void speak(Player player, String text) {
        if (player == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(prefix.append(Component.text(text, NamedTextColor.WHITE)));
            }
        });
    }

    private void speakByName(String name, String text) {
        if (name == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayerExact(name);
            if (p != null && p.isOnline()) {
                p.sendMessage(prefix.append(Component.text(text, NamedTextColor.WHITE)));
            }
        });
    }

    // ---- resolution helpers ------------------------------------------------

    private PendingWish pendingFor(JsonObject args) {
        String name = optString(args, "player");
        return name == null ? null : pending.get(name.toLowerCase(Locale.ROOT));
    }

    private UUID resolveUuid(JsonObject args) {
        PendingWish p = pendingFor(args);
        if (p != null) {
            return p.uuid;
        }
        String name = optString(args, "player");
        if (name == null) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online.getUniqueId() : null;
    }

    private PlayerProfile profileFor(JsonObject args, PendingWish p) {
        if (p != null) {
            return p.profile;
        }
        UUID uuid = resolveUuid(args);
        String name = optString(args, "player");
        if (uuid == null || name == null) {
            return null;
        }
        return memory.get(uuid, name);
    }

    // ---- per-wish bookkeeping ---------------------------------------------

    private static final class PendingWish {
        final UUID uuid;
        final String name;
        final String wish;
        final PlayerProfile profile;
        final long submittedAt = System.currentTimeMillis();
        final List<String> executed = new ArrayList<>();
        final List<String> blocked = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        boolean questAssigned;
        boolean replied;

        PendingWish(UUID uuid, String name, String wish, PlayerProfile profile) {
            this.uuid = uuid;
            this.name = name;
            this.wish = wish;
            this.profile = profile;
        }
    }

    // ---- small static helpers (ported from Clanker) ------------------------

    private static String serverEcho(String output) {
        String trimmed = output == null ? "" : output.replaceAll("§.", "").trim();
        return trimmed.isEmpty() ? " (no output)" : "\nServer response: " + trimmed;
    }

    private static String giveItem(String command) {
        String[] t = command.replaceFirst("^/", "").trim().split("\\s+");
        if (t.length < 3 || !stripNamespace(t[0].toLowerCase(Locale.ROOT)).equals("give")) {
            return null;
        }
        String itemTok = t[2];
        int br = itemTok.indexOf('[');
        if (br >= 0) {
            itemTok = itemTok.substring(0, br);
        }
        return stripNamespace(itemTok.toLowerCase(Locale.ROOT));
    }

    private static int giveCount(String command) {
        String[] t = command.replaceFirst("^/", "").trim().split("\\s+");
        if (t.length >= 4 && t[t.length - 1].matches("\\d+")) {
            try {
                return Integer.parseInt(t[t.length - 1]);
            } catch (NumberFormatException ignored) {
                return 1;
            }
        }
        return 1;
    }

    private static String stripNamespace(String s) {
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }

    private static long optLong(JsonObject obj, String key, long fallback) {
        try {
            if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
                JsonElement el = obj.get(key);
                if (el.isJsonPrimitive()) {
                    return (long) Math.floor(Double.parseDouble(el.getAsString()));
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return fallback;
    }

    private static List<String> optStringList(JsonObject obj, String key) {
        List<String> out = new ArrayList<>();
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) {
            return out;
        }
        JsonElement el = obj.get(key);
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e != null && e.isJsonPrimitive()) {
                    String s = e.getAsString().trim();
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
        } else if (el.isJsonPrimitive()) {
            String s = el.getAsString().trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }
}
