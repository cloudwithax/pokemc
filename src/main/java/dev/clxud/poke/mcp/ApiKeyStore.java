package dev.clxud.poke.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Manages the bearer key Poke must present to call our MCP server.
 *
 * <p>Precedence: an explicit value in config wins; otherwise a key is loaded
 * from (or, on first run, generated into) a file under the plugin's data folder.
 * The generated file is locked to {@code 0600} so it isn't world-readable — this
 * is the "randomly generated api key stored on disk safely" requirement.
 */
public final class ApiKeyStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeyStore() {
    }

    /** Returns the configured key, or the persisted/just-generated one at {@code file}. */
    public static String resolve(String configured, Path file, Logger logger) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        try {
            if (Files.isRegularFile(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!existing.isBlank()) {
                    return existing;
                }
            }
        } catch (IOException e) {
            logger.warning("Could not read MCP key file " + file + ": " + e.getMessage());
        }

        String key = generate();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, key + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            harden(file, logger);
            logger.info("Generated a new MCP API key and stored it at " + file);
        } catch (IOException e) {
            logger.warning("Could not persist MCP key file " + file + ": " + e.getMessage()
                    + " — using an in-memory key for this session only.");
        }
        return key;
    }

    private static String generate() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return "pmc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** Best-effort chmod 600; silently skipped on non-POSIX filesystems. */
    private static void harden(Path file, Logger logger) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException e) {
            logger.fine("Could not restrict permissions on the MCP key file: " + e.getMessage());
        }
    }
}
