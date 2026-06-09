package dev.clxud.poke.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Clanker's durable dossier on a single player, keyed by UUID. This is the
 * "memory graph": nodes are {@link Interaction}s (wishes and their outcomes),
 * augmented with a running tally of what's been conjured and freeform notes the
 * genie writes about the mortal. Serialized to JSON, one file per UUID.
 */
public final class PlayerProfile {

    public String uuid;
    public String name;
    public long firstSeen;
    public long lastSeen;
    public int totalWishes;
    public int grantedWishes;
    public int deniedWishes;

    /** item id -> total quantity Clanker has handed this player over all time. */
    public Map<String, Integer> itemTally = new LinkedHashMap<>();

    /** Chronological wish history (trimmed to a recent window on save). */
    public List<Interaction> interactions = new ArrayList<>();

    /** Freeform notes the genie chooses to remember (personality, grudges, jokes). */
    public List<String> notes = new ArrayList<>();

    public int questsAssigned;
    public int questsCompleted;

    /** The player's currently outstanding quest, or null if none. */
    public Quest activeQuest;

    public static final class Interaction {
        public long t;
        public String wish;
        public String outcome; // "granted" | "denied" | "quest assigned"
        public List<String> commands = new ArrayList<>();
        public String summary;
    }

    /**
     * A task Clanker has set the player. Verified against real statistics:
     * the quest is complete when (current stat value - baseline) >= amount.
     */
    public static final class Quest {
        public String description;          // flavour text shown to the player
        public String category;             // stats category, e.g. "killed", "custom", "mined"
        public String key;                  // stat key, e.g. "minecraft:zombie"
        public long amount;                 // required increase over baseline
        public long baseline;               // stat value when the quest was set
        public List<String> rewardCommands = new ArrayList<>();
        public long issuedAt;
    }

    public void addItems(String itemId, int count) {
        itemTally.merge(itemId, count, Integer::sum);
    }
}
