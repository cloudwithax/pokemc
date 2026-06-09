package dev.clxud.poke.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Loads, caches and persists {@link PlayerProfile}s under
 * {@code plugins/Clanker/memory/<uuid>.json}. All access happens on Clanker's
 * single worker thread, but methods are synchronized for safety.
 */
public final class PlayerMemory {

    private static final int MAX_INTERACTIONS = 50;
    private static final int MAX_NOTES = 30;

    private final File dir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final ConcurrentHashMap<UUID, PlayerProfile> cache = new ConcurrentHashMap<>();
    private final Logger logger;

    public PlayerMemory(File dataFolder, Logger logger) {
        this.dir = new File(dataFolder, "memory");
        this.logger = logger;
        if (!dir.exists() && !dir.mkdirs()) {
            logger.warning("Could not create Clanker memory directory at " + dir.getAbsolutePath());
        }
    }

    public synchronized PlayerProfile get(UUID id, String name) {
        PlayerProfile profile = cache.computeIfAbsent(id, this::loadOrCreate);
        profile.name = name;
        profile.lastSeen = System.currentTimeMillis();
        return profile;
    }

    private PlayerProfile loadOrCreate(UUID id) {
        File file = new File(dir, id + ".json");
        if (file.isFile()) {
            try {
                String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                PlayerProfile loaded = gson.fromJson(json, PlayerProfile.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (Exception e) {
                logger.warning("Failed to read memory for " + id + ": " + e.getMessage());
            }
        }
        PlayerProfile fresh = new PlayerProfile();
        fresh.uuid = id.toString();
        fresh.firstSeen = System.currentTimeMillis();
        return fresh;
    }

    public synchronized void save(PlayerProfile profile) {
        if (profile.uuid == null) {
            return;
        }
        // Trim history windows so files don't grow without bound.
        if (profile.interactions.size() > MAX_INTERACTIONS) {
            profile.interactions = profile.interactions.subList(
                    profile.interactions.size() - MAX_INTERACTIONS, profile.interactions.size());
        }
        if (profile.notes.size() > MAX_NOTES) {
            profile.notes = profile.notes.subList(profile.notes.size() - MAX_NOTES, profile.notes.size());
        }
        File file = new File(dir, profile.uuid + ".json");
        try {
            Files.writeString(file.toPath(), gson.toJson(profile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warning("Failed to save memory for " + profile.uuid + ": " + e.getMessage());
        }
    }
}
