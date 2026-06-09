package dev.clxud.poke.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Minimal {@code .env} reader so secrets (the Poke API key) can live in a
 * private, chmod-600 file instead of {@code config.yml}. Parses {@code KEY=VALUE}
 * lines, ignores blanks and {@code #} comments, tolerates a leading
 * {@code export }, and strips one layer of matching surrounding quotes.
 *
 * <p>Read once from the plugin's data folder; values are used only as a fallback
 * source for keys — a real OS environment variable always wins.
 */
public final class DotEnv {

    private DotEnv() {
    }

    public static Map<String, String> load(Path file, Logger logger) {
        Map<String, String> out = new HashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring("export ".length()).trim();
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = stripQuotes(line.substring(eq + 1).trim());
                if (!key.isEmpty()) {
                    out.put(key, value);
                }
            }
            logger.info("Loaded " + out.size() + " value(s) from .env at " + file);
        } catch (IOException e) {
            logger.warning("Could not read .env at " + file + ": " + e.getMessage());
        }
        return out;
    }

    private static String stripQuotes(String v) {
        if (v.length() >= 2
                && ((v.charAt(0) == '"' && v.endsWith("\""))
                || (v.charAt(0) == '\'' && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
