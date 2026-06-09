package dev.clxud.poke.stats;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * An immutable view of one player's Minecraft statistics at a moment in time.
 * Categories are stored without the {@code minecraft:} namespace (e.g. "custom",
 * "killed", "mined"); stat keys keep/gain the namespace (e.g. "minecraft:zombie").
 */
public final class StatsSnapshot {

    private final Map<String, Map<String, Long>> data;

    public StatsSnapshot(Map<String, Map<String, Long>> data) {
        this.data = data;
    }

    /** Current value of a statistic, or 0 if the player has never recorded it. */
    public long get(String category, String key) {
        if (category == null || key == null) {
            return 0L;
        }
        Map<String, Long> inner = data.getOrDefault(normCategory(category), Collections.emptyMap());
        return inner.getOrDefault(normKey(key), 0L);
    }

    private static String normCategory(String c) {
        c = c.trim().toLowerCase(Locale.ROOT);
        int colon = c.indexOf(':');
        return colon >= 0 ? c.substring(colon + 1) : c;
    }

    private static String normKey(String k) {
        k = k.trim().toLowerCase(Locale.ROOT);
        return k.indexOf(':') >= 0 ? k : "minecraft:" + k;
    }
}
