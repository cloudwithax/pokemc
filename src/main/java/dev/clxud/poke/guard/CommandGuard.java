package dev.clxud.poke.guard;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hard safety layer between the genie and the server console. The AI is told the
 * rules in its system prompt, but this class is the part that actually enforces
 * them — defence in depth against prompt injection. Every command Poke wants to
 * run is checked here first; rejections come back with a reason so the genie can
 * find another (legal) technicality.
 *
 * <p><b>Allow-list, not deny-list.</b> A player fully controls the chat text that
 * becomes Poke's prompt, so Poke must be assumed jailbreakable — this guard is the
 * only real backstop. A deny-list ("block op/ban/...") is structurally incomplete:
 * any verb not enumerated (a new vanilla command, or ANY third-party plugin
 * command like {@code /lp}, {@code /pex}, {@code /eco}) would sail through and
 * could grant real OP. So only an explicit set of known-safe verbs is permitted;
 * everything else is denied by default. Admins who knowingly want more can add
 * verbs via {@code guard.extra-allowed-commands} in config.yml.
 */
public final class CommandGuard {

    /** Vanilla verbs the genie may run to grant item/cosmetic wishes. Anything else is denied. */
    private static final Set<String> BASE_ALLOWED = Set.of(
            "give", "item",            // hand out items (component/enchant/count-checked)
            "enchant",                 // enchant held item (vanilla caps enforced)
            "xp", "experience",        // grant experience
            "effect",                  // potion effects (harmful effects blocked below)
            "time", "weather",         // cosmetic world state ("make it day")
            "playsound", "particle",   // harmless flair (particle is lag-capped)
            "data",                    // read-only: only the "data get" form is allowed
            "summon",                  // spawn a plain entity (raw NBT is blocked below)
            "execute"                  // wrapper: the inner "run" command is re-checked
    );

    /**
     * Admin/creative-only item & block ids. Handing any of these out is an
     * "OP item" — forbidden. Checked only for verbs that place or give things.
     */
    private static final Set<String> ADMIN_ITEMS = Set.of(
            "command_block", "chain_command_block", "repeating_command_block", "command_block_minecart",
            "structure_block", "structure_void", "jigsaw", "barrier", "light", "debug_stick",
            "knowledge_book", "bedrock", "end_portal_frame", "end_gateway", "spawner", "trial_spawner",
            "budding_amethyst", "reinforced_deepslate", "petrified_oak_slab", "moving_piston",
            "nether_portal", "end_portal", "frosted_ice"
    );

    /** Allowed verbs that put items/entities into the world — get the admin-item scan. */
    private static final Set<String> ITEM_VERBS = Set.of("give", "item", "summon");

    /** Allowed verbs that get a "lag bomb" numeric sanity check on their coordinates/counts. */
    private static final Set<String> VOLUME_VERBS = Set.of("summon", "particle");

    /**
     * Entities that are effectively weapons/grief tools — blocked from /summon
     * even without NBT. Harmless mobs (cow, villager, ...) stay summonable.
     */
    private static final Set<String> DANGEROUS_ENTITIES = Set.of(
            "tnt", "primed_tnt", "tnt_minecart", "end_crystal", "fireball", "small_fireball",
            "dragon_fireball", "wither_skull", "wither", "ender_dragon", "falling_block",
            "area_effect_cloud", "evoker_fangs", "lightning_bolt"
    );

    /**
     * Status effects the genie may NOT apply — they harm or grief the target.
     * Beneficial effects (speed, regeneration, night_vision, ...) stay allowed.
     */
    private static final Set<String> HARMFUL_EFFECTS = Set.of(
            "instant_damage", "harming", "wither", "poison", "weakness", "slowness", "mining_fatigue",
            "nausea", "blindness", "hunger", "levitation", "bad_omen", "darkness", "unluck",
            "infested", "oozing", "weaving", "wind_charged", "trial_omen"
    );

    /** Any target selector: @a @e @p @r @s (and the verbose @e[...] forms). */
    private static final Pattern SELECTOR = Pattern.compile("@[a-zA-Z]");

    private static final Pattern COMPONENT_KEY = Pattern.compile("(?:minecraft:)?([a-zA-Z0-9_]+)\\s*=");
    private static final Pattern ENCHANT_PAIR =
            Pattern.compile("\"?(?:minecraft:)?([a-z_]+)\"?\\s*:\\s*(\\d+)");
    private static final Pattern INTEGER_TOKEN = Pattern.compile("[~^]?(-?\\d{1,12})\\b");

    private static final Set<String> ALLOWED_COMPONENTS = Set.of("enchantments", "stored_enchantments");
    private static final int VOLUME_LIMIT = 20_000;
    private static final int MAX_EFFECT_AMPLIFIER = 10;

    private final int maxGiveCount;
    /** Extra verbs an admin opted into via config, already namespace-stripped + lowercased. */
    private final Set<String> extraAllowed;

    public CommandGuard(int maxGiveCount) {
        this(maxGiveCount, Set.of());
    }

    public CommandGuard(int maxGiveCount, Set<String> extraAllowedVerbs) {
        this.maxGiveCount = maxGiveCount;
        Set<String> extras = new HashSet<>();
        if (extraAllowedVerbs != null) {
            for (String v : extraAllowedVerbs) {
                if (v != null && !v.isBlank()) {
                    extras.add(stripNamespace(v.trim().toLowerCase(Locale.ROOT)));
                }
            }
        }
        this.extraAllowed = Set.copyOf(extras);
    }

    public record Result(boolean allowed, String reason) {
        static Result ok() {
            return new Result(true, "");
        }

        static Result deny(String reason) {
            return new Result(false, reason);
        }
    }

    private boolean isAllowedVerb(String verb) {
        return BASE_ALLOWED.contains(verb) || extraAllowed.contains(verb);
    }

    public Result check(String rawCommand) {
        return check(rawCommand, null);
    }

    /**
     * @param rawCommand the command Poke wants to run.
     * @param wisher     the exact name of the player this wish is for, or null if
     *                   unknown. When non-null, every command must act on THAT
     *                   player by name — a command naming a different player is
     *                   refused. Broad/other targeting via @-selectors is always
     *                   refused, wisher known or not.
     */
    public Result check(String rawCommand, String wisher) {
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

        // Allow-list gate: anything not explicitly known-safe is refused.
        if (!isAllowedVerb(verb)) {
            return Result.deny("the '" + verb + "' command isn't on the genie's allow-list of safe commands "
                    + "(only item/cosmetic commands are permitted)");
        }

        // Target-selector ban: a player controls the wish, so the genie may only
        // act on that one player BY NAME. @a/@e/@p/@r/@s could hit everyone or
        // another player — refuse them outright (covers the whole command, incl.
        // anything wrapped in /execute).
        if (SELECTOR.matcher(command).find()) {
            return Result.deny("target the player by their exact name, not @-selectors like @a/@p/@s");
        }

        // /execute can wrap another command after "run" — guard that recursively.
        if (verb.equals("execute")) {
            int runIdx = indexOfWord(command.toLowerCase(Locale.ROOT), "run");
            if (runIdx >= 0) {
                String inner = command.substring(runIdx + 3).trim();
                if (!inner.isEmpty()) {
                    Result innerResult = check(inner, wisher);
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

        // /summon with raw NBT can spawn OP-geared item entities (e.g. a dropped
        // sharpness-255 sword) and bypass the give-path component checks entirely.
        // Only plain summons (no {...}) are permitted.
        if (verb.equals("summon")) {
            if (command.indexOf('{') >= 0) {
                return Result.deny("summoning with raw NBT ({...}) isn't allowed — it can spawn OP gear");
            }
            if (tokens.length >= 2) {
                String entity = stripNamespace(tokens[1].toLowerCase(Locale.ROOT));
                if (DANGEROUS_ENTITIES.contains(entity)) {
                    return Result.deny("'" + entity + "' is a dangerous entity to summon");
                }
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

        // /effect — block harmful effects and cap the amplifier.
        if (verb.equals("effect")) {
            Result effectResult = checkEffectCommand(tokens);
            if (!effectResult.allowed()) {
                return effectResult;
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

        // The explicit target argument (where the verb has one) must be the wisher.
        if (wisher != null) {
            Result targetResult = checkTarget(verb, tokens, wisher);
            if (!targetResult.allowed()) {
                return targetResult;
            }
        }

        return Result.ok();
    }

    /**
     * For verbs that name a player target, require that target to be the wisher.
     * Returns ok() for verbs with no player target (time/weather/summon/...).
     */
    private Result checkTarget(String verb, String[] tokens, String wisher) {
        int idx = targetIndex(verb, tokens);
        if (idx < 0 || idx >= tokens.length) {
            return Result.ok();
        }
        String target = tokens[idx];
        if (target.isEmpty()) {
            return Result.ok();
        }
        if (!target.equalsIgnoreCase(wisher)) {
            return Result.deny("this command would act on '" + target + "', but you may only act on "
                    + wisher + " — name them as the target");
        }
        return Result.ok();
    }

    /** Token index of the player-target argument for a verb, or -1 if it has none. */
    private static int targetIndex(String verb, String[] tokens) {
        switch (verb) {
            case "give":
            case "enchant":
                return 1;
            case "xp":
            case "experience":
                // xp <add|set|query> <target> ...
                return tokens.length > 2 ? 2 : -1;
            case "effect":
                // effect <give|clear> <target> ...
                return tokens.length > 2 ? 2 : -1;
            case "playsound":
                // playsound <sound> <source> <target> ...
                return tokens.length > 3 ? 3 : -1;
            case "data":
                // data get entity <target>
                return tokens.length > 3 && tokens[1].equalsIgnoreCase("get")
                        && tokens[2].equalsIgnoreCase("entity") ? 3 : -1;
            case "item":
                // item <replace|modify> entity <target> ...
                return tokens.length > 3 && tokens[2].equalsIgnoreCase("entity") ? 3 : -1;
            default:
                return -1;
        }
    }

    private Result checkEffectCommand(String[] tokens) {
        // effect give <target> <effect> [seconds] [amplifier] [hideParticles]
        // effect clear ... — always fine.
        if (tokens.length < 2 || !tokens[1].equalsIgnoreCase("give")) {
            return Result.ok();
        }
        if (tokens.length < 4) {
            return Result.ok();
        }
        String effect = stripNamespace(tokens[3].toLowerCase(Locale.ROOT));
        if (HARMFUL_EFFECTS.contains(effect)) {
            return Result.deny("the '" + effect + "' effect harms players and isn't allowed");
        }
        if (tokens.length >= 6) {
            try {
                int amplifier = Integer.parseInt(tokens[5]);
                if (amplifier > MAX_EFFECT_AMPLIFIER) {
                    return Result.deny("effect amplifier " + amplifier + " is too high (max " + MAX_EFFECT_AMPLIFIER + ")");
                }
            } catch (NumberFormatException ignored) {
                // non-numeric amplifier — let the server reject it
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
