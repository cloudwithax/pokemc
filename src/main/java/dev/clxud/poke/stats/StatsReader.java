package dev.clxud.poke.stats;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.clxud.poke.rcon.RconClient;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Reads a player's vanilla statistics from {@code <world>/stats/<uuid>.json}.
 *
 * <p>Online players' stats live in memory and lag the on-disk file, so we issue
 * a {@code save-all} over RCON first to flush them. This runs on Clanker's
 * worker thread (file IO + a blocking RCON round-trip) — never the main thread.
 */
public final class StatsReader {

    private final File statsDir;
    private final RconClient rcon;
    private final Logger logger;

    public StatsReader(File worldFolder, RconClient rcon, Logger logger) {
        this.statsDir = new File(worldFolder, "stats");
        this.rcon = rcon;
        this.logger = logger;
    }

    /** Flushes player data to disk, then parses and returns the stat snapshot. */
    public StatsSnapshot snapshot(UUID uuid) {
        try {
            rcon.exec("save-all");
        } catch (Exception e) {
            logger.fine("Clanker: save-all before stat read failed: " + e.getMessage());
        }

        File file = new File(statsDir, uuid + ".json");
        Map<String, Map<String, Long>> data = new HashMap<>();
        if (file.isFile()) {
            try {
                data = parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                logger.warning("Clanker: failed to read stats for " + uuid + ": " + e.getMessage());
            }
        }
        return new StatsSnapshot(data);
    }

    /** Parses a vanilla stats file into {category(no ns) -> {key(lowercased) -> value}}. */
    public static Map<String, Map<String, Long>> parse(String json) {
        Map<String, Map<String, Long>> data = new HashMap<>();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("stats") || !root.get("stats").isJsonObject()) {
            return data;
        }
        JsonObject stats = root.getAsJsonObject("stats");
        for (Map.Entry<String, com.google.gson.JsonElement> cat : stats.entrySet()) {
            if (!cat.getValue().isJsonObject()) {
                continue;
            }
            Map<String, Long> inner = new HashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> kv : cat.getValue().getAsJsonObject().entrySet()) {
                try {
                    inner.put(kv.getKey().toLowerCase(Locale.ROOT), kv.getValue().getAsLong());
                } catch (Exception ignored) {
                    // skip non-numeric entries
                }
            }
            data.put(stripNamespace(cat.getKey()), inner);
        }
        return data;
    }

    private static String stripNamespace(String s) {
        s = s.toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }
}
