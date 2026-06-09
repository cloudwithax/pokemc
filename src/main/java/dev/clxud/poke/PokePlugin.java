package dev.clxud.poke;

import dev.clxud.poke.guard.CommandGuard;
import dev.clxud.poke.inspect.PlayerInspector;
import dev.clxud.poke.mcp.ApiKeyStore;
import dev.clxud.poke.mcp.McpServer;
import dev.clxud.poke.memory.PlayerMemory;
import dev.clxud.poke.poke.PokeClient;
import dev.clxud.poke.poke.PokeSender;
import dev.clxud.poke.rcon.RconClient;
import dev.clxud.poke.stats.StatsReader;
import dev.clxud.poke.telegram.TdTelegramBridge;
import dev.clxud.poke.util.DotEnv;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * PokeMC — a Minecraft genie admin, powered by Poke, via the Poke API and a
 * built-in MCP server.
 *
 * <p>Optionally also maintains a Telegram user-account bridge (TDLib) that
 * watches for replies from {@code @interaction_poke_bot} in the real Telegram
 * thread and forwards them in-game, so the conversation works even when Poke
 * doesn't call back through the MCP server.
 */
public final class PokePlugin extends JavaPlugin {

  // PokeMC's bundled Telegram app credentials (my.telegram.org). Shipping these
  // is standard for public TDLib clients; they identify the app, not any user.
  private static final String DEFAULT_TELEGRAM_API_ID = "37133535";
  private static final String DEFAULT_TELEGRAM_API_HASH = "90b926611ef158e2945788b792ed74f6";

  private RconClient rcon;
  private McpServer mcp;
  private PokeGenie genie;
  private TdTelegramBridge telegram;
  private String mcpKey;
    private int mcpPort;
    private boolean requireConfirmation;
    private Map<String, String> dotenv;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!setup()) {
            return;
        }
        getServer().getPluginManager().registerEvents(
                new ChatListener(genie, getConfig().getString("trigger", "poke")), this);
        startProbe();
    }

    private boolean setup() {
        FileConfiguration config = getConfig();
        this.dotenv = DotEnv.load(getDataFolder().toPath().resolve(".env"), getLogger());

        ConfigurationSection tg = config.getConfigurationSection("telegram");
        String telegramEnabledValue = firstNonBlank(
                System.getenv("TELEGRAM_ENABLED"),
                dotenv.get("TELEGRAM_ENABLED"),
                tg != null && tg.getBoolean("enabled", false) ? "true" : "");
        boolean telegramEnabled = telegramEnabledValue.equalsIgnoreCase("true")
                || telegramEnabledValue.equalsIgnoreCase("1")
                || telegramEnabledValue.equalsIgnoreCase("yes");

        String pokeKey = resolvePokeKey(config);
        if (!telegramEnabled && (pokeKey == null || pokeKey.isBlank())) {
            getLogger().severe("No Poke API key configured. Set poke.api-key in config.yml or the "
                    + "POKE_API_KEY environment variable. Disabling PokeMC.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        this.rcon = new RconClient(
                config.getString("rcon.host", "127.0.0.1"),
                config.getInt("rcon.port", 25575),
                config.getString("rcon.password", "changeme"));

        CommandGuard guard = new CommandGuard(config.getInt("max-give-count", 1024));
        PlayerMemory memory = new PlayerMemory(getDataFolder(), getLogger());

        File worldFolder = getServer().getWorlds().isEmpty()
                ? new File(getServer().getWorldContainer(), "world")
                : getServer().getWorlds().get(0).getWorldFolder();
        StatsReader stats = new StatsReader(worldFolder, rcon, getLogger());
        PlayerInspector inspector = new PlayerInspector(this);

        PokeSender pokeSender;
        AtomicReference<PokeGenie> genieRef = new AtomicReference<>();
        if (telegramEnabled) {
            // App credentials default to PokeMC's bundled Telegram app (like most
            // shipped TDLib clients), so a fresh install only needs enabled: true
            // and the console link flow. Override via env/.env/config if you'd
            // rather use your own from https://my.telegram.org.
            String apiIdStr = firstNonBlank(
                    System.getenv("TELEGRAM_API_ID"),
                    dotenv.get("TELEGRAM_API_ID"),
                    tg.getString("api-id", ""),
                    DEFAULT_TELEGRAM_API_ID);
            int apiId = apiIdStr.isBlank() ? 0 : Integer.parseInt(apiIdStr);
            String apiHash = firstNonBlank(
                    System.getenv("TELEGRAM_API_HASH"),
                    dotenv.get("TELEGRAM_API_HASH"),
                    tg.getString("api-hash", ""),
                    DEFAULT_TELEGRAM_API_HASH);
            String phoneNumber = firstNonBlank(
                    System.getenv("TELEGRAM_PHONE_NUMBER"),
                    dotenv.get("TELEGRAM_PHONE_NUMBER"),
                    tg.getString("phone-number", ""));
            String authCode = firstNonBlank(
                    System.getenv("TELEGRAM_AUTH_CODE"),
                    dotenv.get("TELEGRAM_AUTH_CODE"),
                    tg.getString("auth-code", ""));
            String authPassword = firstNonBlank(
                    System.getenv("TELEGRAM_AUTH_PASSWORD"),
                    dotenv.get("TELEGRAM_AUTH_PASSWORD"),
                    tg.getString("auth-password", ""));
            String botUsername = tg.getString("bot-username", "interaction_poke_bot");

            if (apiId <= 0 || apiHash.isBlank()) {
                getLogger().severe("Telegram bridge is enabled, but TELEGRAM_API_ID / TELEGRAM_API_HASH are missing.");
                getServer().getPluginManager().disablePlugin(this);
                return false;
            }
            // A phone number is NOT required up front: if none is configured (and
            // no prior session/persisted phone exists), the bridge waits and asks
            // the admin to run /poke link <+phone> at the console.

            this.telegram = new TdTelegramBridge(
                    getDataFolder().toPath(),
                    getPluginMeta().getVersion(),
                    apiId,
                    apiHash,
                    phoneNumber,
                    authCode,
                    authPassword,
                    botUsername,
                    text -> {
                        PokeGenie g = genieRef.get();
                        if (g != null) {
                            g.handleTelegramReply(text);
                        }
                    },
                    getLogger());
            pokeSender = telegram;
        } else {
            pokeSender = new PokeClient(
                    config.getString("poke.api-url", "https://poke.com/api/v1/inbound/api-message"),
                    pokeKey,
                    config.getInt("poke.timeout-seconds", 30));
        }

        this.genie = new PokeGenie(this, pokeSender, rcon, guard, memory, stats, inspector,
                config.getString("display-name", "Poke"),
                config.getInt("poke.wish-timeout-seconds", 300),
                getLogger());
        genieRef.set(genie);

        String mcpConfigured = firstNonBlank(
                System.getenv("MCP_API_KEY"),
                dotenv.get("MCP_API_KEY"),
                config.getString("mcp.api-key", ""));
        this.mcpKey = ApiKeyStore.resolve(
                mcpConfigured,
                getDataFolder().toPath().resolve("mcp-api-key.txt"),
                getLogger());
        this.mcpPort = config.getInt("mcp.port", 4053);
        try {
            this.mcp = new McpServer(
                    config.getString("mcp.bind", "0.0.0.0"), mcpPort, mcpKey,
                    getPluginMeta().getVersion(), genie, getLogger());
            this.mcp.start();
            getLogger().info("MCP server listening on " + config.getString("mcp.bind", "0.0.0.0")
                    + ":" + mcpPort + " (paths: /sse and /mcp).");
        } catch (IOException e) {
            getLogger().severe("Could not start the MCP server on port " + mcpPort + ": " + e.getMessage()
                    + ". Disabling PokeMC.");
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }

        // Poke's API path is slow and it doesn't always call the meta confirm_tools
        // tool, so by default the genie starts AWAKE (we know the link works once
        // registered) and the self-test is confirmation-only. Set require-confirmation
        // true to restore strict gating (dormant until Poke calls back).
        this.requireConfirmation = config.getBoolean("mcp.require-confirmation", false);
        genie.setReady(!requireConfirmation);

        // Auto-finalise wishes Poke never replied to.
        getServer().getScheduler().runTaskTimerAsynchronously(this, genie::sweep, 20L * 30, 20L * 30);

        if (telegramEnabled) {
            try {
                telegram.start();
                getLogger().info("Telegram bridge started — linking @" + telegram.getBotUsername()
                        + " (" + telegram.friendlyState() + "). Check /poke status.");
            } catch (Exception e) {
                getLogger().severe("Could not start bundled Telegram bridge: " + e.getMessage());
                getServer().getPluginManager().disablePlugin(this);
                return false;
            }
        }
        return true;
    }

    /** Runs the reachability self-test off the main thread. */
    private void startProbe() {
        int timeout = getConfig().getInt("poke.probe-timeout-seconds", 180);
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            CompletableFuture<Set<String>> probe = genie.armProbe();
            try {
                genie.sendProbe();
            } catch (Exception e) {
                getLogger().severe("Could not send the startup self-test to Poke: " + e.getMessage());
                failProbe();
                return;
            }
            getLogger().info("Sent startup self-test to Poke; waiting up to " + timeout
                    + "s for it to call back through the MCP server...");
            try {
                Set<String> seen = probe.get(timeout, TimeUnit.SECONDS);
                genie.setReady(true);
                getLogger().info("Poke connection VERIFIED — the genie is awake. Tools Poke reported: " + seen);
            } catch (TimeoutException te) {
                failProbe();
            } catch (Exception e) {
                getLogger().warning("Startup self-test failed: " + e.getMessage());
                failProbe();
            }
        });
    }

    private void failProbe() {
        if (!requireConfirmation) {
            // Optimistic mode: a missed self-test is almost always just Poke's
            // latency (it can take >2-3 min, or skip the meta tool entirely). The
            // genie stays AWAKE and will work on the next real wish.
            getLogger().info("Startup self-test got no callback in the window — leaving the genie AWAKE "
                    + "(Poke is just slow). It will confirm on the first real tool call. Set "
                    + "mcp.require-confirmation: true for strict gating.");
            return;
        }
        genie.setReady(false);
        String line = "============================================================";
        getLogger().warning(line);
        getLogger().warning("PokeMC's startup self-test did not get a callback (strict mode) — genie DORMANT.");
        getLogger().warning("This can just be Poke's latency; it wakes automatically on the first tool call.");
        getLogger().warning("If it stays dormant, verify Poke can reach the MCP server:");
        getLogger().warning("  Public endpoint : <your tunnel>/sse   (must be registered in Poke)");
        getLogger().warning("  MCP API key     : " + mcpKey);
        getLogger().warning("                    (also saved at " + getDataFolder() + "/mcp-api-key.txt)");
        getLogger().warning("  Re-test with /poke retry, or just send a wish and wait ~2 min.");
        getLogger().warning(line);
    }

    private String resolvePokeKey(FileConfiguration config) {
        // Precedence: real OS env var > .env file > config.yml.
        return firstNonBlank(
                System.getenv("POKE_API_KEY"),
                dotenv.get("POKE_API_KEY"),
                config.getString("poke.api-key", ""));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

@Override
public void onDisable() {
  if (telegram != null) {
    try { telegram.close(); } catch (Exception e) { getLogger().warning("Telegram bridge close failed: " + e.getMessage()); }
  }
  if (mcp != null) {
    mcp.stop();
  }
  if (genie != null) {
    genie.sweep();
  }
  if (rcon != null) {
    rcon.close();
  }
  getLogger().info("PokeMC returns to its lamp.");
}

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!command.getName().equalsIgnoreCase("poke")) {
            return false;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.GOLD + "PokeMC " + ChatColor.GRAY + "v" + getPluginMeta().getVersion()
                    + " — usage: /poke <status|retry|reload|link|code|password>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "status" -> {
                boolean ready = genie != null && genie.isReady();
                sender.sendMessage(ChatColor.GOLD + "PokeMC " + ChatColor.GRAY + "— genie "
                        + (ready ? ChatColor.GREEN + "AWAKE" : ChatColor.RED + "DORMANT")
                        + ChatColor.GRAY + ", MCP on port " + mcpPort
                        + ", wishes in flight: " + (genie == null ? "n/a" : genie.pendingCount()));
                if (telegram != null) {
                    boolean linked = telegram.isLinked();
                    sender.sendMessage(ChatColor.GOLD + "Telegram " + ChatColor.GRAY + "@"
                            + telegram.getBotUsername() + " — "
                            + (linked ? ChatColor.GREEN + "LINKED" : ChatColor.YELLOW + telegram.friendlyState()));
                }
            }
            case "link" -> {
                if (telegram == null) {
                    sender.sendMessage(ChatColor.RED + "Telegram bridge is not enabled (telegram.enabled: false).");
                } else {
                    sender.sendMessage(ChatColor.GOLD + telegram.submitPhone(args.length > 1 ? args[1] : null));
                }
            }
            case "code" -> {
                if (telegram == null) {
                    sender.sendMessage(ChatColor.RED + "Telegram bridge is not enabled (telegram.enabled: false).");
                } else {
                    sender.sendMessage(ChatColor.GOLD + telegram.submitCode(args.length > 1 ? args[1] : null));
                }
            }
            case "password" -> {
                if (telegram == null) {
                    sender.sendMessage(ChatColor.RED + "Telegram bridge is not enabled (telegram.enabled: false).");
                } else {
                    sender.sendMessage(ChatColor.GOLD + telegram.submitPassword(args.length > 1 ? args[1] : null));
                }
            }
            case "retry" -> {
                if (genie == null) {
                    sender.sendMessage(ChatColor.RED + "PokeMC is not set up.");
                } else {
                    sender.sendMessage(ChatColor.GOLD + "Re-running the Poke connection self-test...");
                    startProbe();
                }
            }
            case "reload" -> {
                if (mcp != null) {
                    mcp.stop();
                }
                if (rcon != null) {
                    rcon.close();
                }
                reloadConfig();
                if (setup()) {
                    startProbe();
                    sender.sendMessage(ChatColor.GREEN + "PokeMC reloaded; re-testing the connection.");
                } else {
                    sender.sendMessage(ChatColor.RED + "PokeMC reload failed — check console.");
                }
            }
            default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use /poke <status|retry|reload|link|code|password>");
        }
        return true;
    }
}
