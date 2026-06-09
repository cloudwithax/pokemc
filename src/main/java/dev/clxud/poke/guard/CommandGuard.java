package dev.clxud.poke.guard;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hard safety layer between Clanker and the server console. The AI is told the
 * rules in its system prompt, but this class is the part that actually enforces
 * them — defence in depth. Every command the agent wants to run is checked here
 * first; rejections come back with a reason so the genie can find another
 * (legal) technicality.
 */
public final class CommandGuard {

    /** Verbs Clanker may never run, in any form. */
    private static final Set<String> BLOCKED_VERBS = Set.of(
            // Operator / server lifecycle
            "op", "deop", "stop", "restart", "reload", "rl",
            // Moderation powers
            "ban", "ban-ip", "banip", "banlist", "pardon", "pardon-ip", "pardonip", "kick", "whitelist",
            // Persistence / internals
            "save-off", "save-on", "save-all", "saveoff", "saveon", "saveall",
            "debug", "perf", "jfr", "spark", "datapack", "function", "schedule",
            "forceload", "setidletimeout", "publish",
            // God-mode equivalents
            "gamemode", "defaultgamemode", "gamerule", "attribute",
            // Plugin / server introspection & control
            "plugins", "pl", "version", "ver", "timings", "mspt", "tps"
    );

    /**
     * Admin/creative-only item & block ids. Handing any of these out is an
     * "OP item" — forbidden. Checked only for verbs that place or give things,
     * to avoid false positives in chat/text commands.
     */
    private static final Set<String> ADMIN_ITEMS = Set.of(
            "command_block", "chain_command_block", "repeating_command_block", "command_block_minecart",
            "structure_block", "structure_void", "jigsaw", "barrier", "light", "debug_stick",
            "knowledge_book", "bedrock", "end_portal_frame", "end_gateway", "spawner", "trial_spawner",
            "budding_amethyst", "reinforced_deepslate", "petrified_oak_slab", "moving_piston",
            "nether_portal", "end_portal", "frosted_ice"
    );

    private static final Set<String> ITEM_VERBS = Set.of(
            "give", "item", "setblock", "fill", "clone", "summon", "loot", "replaceitem", "place"
    );

    /** Verbs that get a "lag bomb" numeric sanity check on their coordinates/counts. */
    private static final Set<String> VOLUME_VERBS = Set.of("fill", "clone", "particle", "summon");

    private static final Pattern COMPONENT_KEY = Pattern.compile("(?:minecraft:)?([a-zA-Z0-9_]+)\\s*=");
    private static final Pattern ENCHANT_PAIR =
            Pattern.compile("\"?(?:minecraft:)?([a-z_]+)\"?\\s*:\\s*(\\d+)");
    private static final Pattern INTEGER_TOKEN = Pattern.compile("[~^]?(-?\\d{1,12})\\b");

    private static final Set<String> ALLOWED_COMPONENTS = Set.of("enchantments", "stored_enchantments");
    private static final int VOLUME_LIMIT = 20_000;

    private final int maxGiveCount;

    public CommandGuard(int maxGiveCount) {
        this.maxGiveCount = maxGiveCount;
    }

    public record Result(boolean allowed, String reason) {
        static Result ok() {
            return new Result(true, "");
        }

        static Result deny(String reason) {
            return new Result(false, reason);
        }
    }

    public Result check(String rawCommand) {
        if (rawCommand == null) {
            return Result.deny("empty command");
        }
        String command = rawCommand.trim();
        while (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isEmpty()) {
            return Result.deny("empty command");
        }

        String[] tokens = command.split("\\s+");
        String verb = stripNamespace(tokens[0].toLowerCase(Locale.ROOT));

        if (BLOCKED_VERBS.contains(verb)) {
            return Result.deny("the '" + verb + "' command is off-limits (operator / dangerous / god-mode power)");
        }

        // /execute can wrap another command after "run" — guard that recursively.
        if (verb.equals("execute")) {
            int runIdx = indexOfWord(command.toLowerCase(Locale.ROOT), "run");
            if (runIdx >= 0) {
                String inner = command.substring(runIdx + 3).trim();
                if (!inner.isEmpty()) {
                    Result innerResult = check(inner);
                    if (!innerResult.allowed()) {
                        return Result.deny("execute -> " + innerResult.reason());
                    }
                }
            }
        }

        // /data can read OR write NBT — only the read-only "data get" form is safe.
        // (data merge/modify could inject OP gear or edit player attributes.)
        if (verb.equals("data")) {
            if (tokens.length < 2 || !tokens[1].equalsIgnoreCase("get")) {
                return Result.deny("only the read-only 'data get' form is allowed, not 'data "
                        + (tokens.length > 1 ? tokens[1] : "") + "'");
            }
        }

        String argLine = command.substring(tokens[0].length()).trim();

        // Admin item/block scan, scoped to item/placement verbs.
        if (ITEM_VERBS.contains(verb)) {
            for (String token : argLine.split("[\\s\\[\\]{},=:\"]+")) {
                String id = stripNamespace(token.toLowerCase(Locale.ROOT));
                if (ADMIN_ITEMS.contains(id)) {
                    return Result.deny("'" + id + "' is an admin-only item/block — not something a mortal gets");
                }
            }
        }

        // Lag-bomb / absurd-number guard.
        if (VOLUME_VERBS.contains(verb)) {
            Matcher m = INTEGER_TOKEN.matcher(argLine);
            while (m.find()) {
                long n = Math.abs(Long.parseLong(m.group(1)));
                if (n > VOLUME_LIMIT) {
                    return Result.deny("that would lag/crash the server (number " + m.group(1) + " is too extreme)");
                }
            }
        }

        // /enchant <target> <enchant> [level] — enforce vanilla caps.
        if (verb.equals("enchant")) {
            Result enchantResult = checkEnchantCommand(tokens);
            if (!enchantResult.allowed()) {
                return enchantResult;
            }
        }

        // give / item — validate any item components.
        if (verb.equals("give") || verb.equals("item")) {
            Result itemResult = checkItemComponents(argLine);
            if (!itemResult.allowed()) {
                return itemResult;
            }
            Result countResult = checkGiveCount(argLine);
            if (!countResult.allowed()) {
                return countResult;
            }
        }

        return Result.ok();
    }

    private Result checkEnchantCommand(String[] tokens) {
        // enchant <target> <enchantment> [level]
        if (tokens.length < 3) {
            return Result.ok();
        }
        String enchant = stripNamespace(tokens[2].toLowerCase(Locale.ROOT));
        int level = 1;
        if (tokens.length >= 4) {
            try {
                level = Integer.parseInt(tokens[3]);
            } catch (NumberFormatException ignored) {
                return Result.ok();
            }
        }
        return validateEnchant(enchant, level);
    }

    private Result checkItemComponents(String argLine) {
        int open = argLine.indexOf('[');
        // Reject legacy raw-NBT item form (e.g. stick{...}) — those are custom/OP items.
        int brace = argLine.indexOf('{');
        if (brace >= 0 && (open < 0 || brace < open)) {
            return Result.deny("raw NBT items aren't allowed — only items obtainable in vanilla survival");
        }
        if (open < 0) {
            return Result.ok();
        }
        int close = argLine.lastIndexOf(']');
        if (close <= open) {
            return Result.ok();
        }
        String components = argLine.substring(open, close + 1);

        Matcher keys = COMPONENT_KEY.matcher(components);
        boolean sawEnchantComponent = false;
        while (keys.find()) {
            String key = key(keys.group(1));
            if (!ALLOWED_COMPONENTS.contains(key)) {
                return Result.deny("custom item component '" + key
                        + "' isn't a vanilla enchant — no OP gear");
            }
            sawEnchantComponent = true;
        }

        if (sawEnchantComponent) {
            Matcher pairs = ENCHANT_PAIR.matcher(components);
            while (pairs.find()) {
                String enchant = pairs.group(1);
                if (ALLOWED_COMPONENTS.contains(enchant)) {
                    continue; // the component key itself, not an enchant entry
                }
                int level = Integer.parseInt(pairs.group(2));
                Result r = validateEnchant(enchant, level);
                if (!r.allowed()) {
                    return r;
                }
            }
        }
        return Result.ok();
    }

    private Result checkGiveCount(String argLine) {
        String[] parts = argLine.split("\\s+");
        if (parts.length == 0) {
            return Result.ok();
        }
        String last = parts[parts.length - 1];
        if (last.matches("\\d+")) {
            long count = Long.parseLong(last);
            if (count > maxGiveCount) {
                return Result.deny("count " + count + " exceeds the limit of " + maxGiveCount);
            }
        }
        return Result.ok();
    }

    private Result validateEnchant(String enchant, int level) {
        Integer max = EnchantLimits.MAX.get(enchant);
        if (max == null) {
            return Result.deny("'" + enchant + "' isn't a recognised vanilla enchantment");
        }
        if (level > max) {
            return Result.deny(enchant + " level " + level + " exceeds the vanilla maximum of " + max);
        }
        if (level < 1) {
            return Result.deny("enchant level must be at least 1");
        }
        return Result.ok();
    }

    private static String key(String s) {
        return stripNamespace(s.toLowerCase(Locale.ROOT));
    }

    private static String stripNamespace(String s) {
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }

    /** Finds the index of a standalone word (surrounded by whitespace/boundaries). */
    private static int indexOfWord(String haystack, String word) {
        Matcher m = Pattern.compile("(?<=\\s|^)" + Pattern.quote(word) + "(?=\\s|$)").matcher(haystack);
        return m.find() ? m.start() : -1;
    }
}
