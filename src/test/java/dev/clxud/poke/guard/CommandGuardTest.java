package dev.clxud.poke.guard;

import java.util.Set;

/**
 * Dependency-free test harness for {@link CommandGuard}. CommandGuard touches no
 * Bukkit API, so this runs with a plain JDK — no Maven/JUnit needed:
 *
 * <pre>
 *   mvn -o compile
 *   javac -d target/test-classes -cp target/classes \
 *         src/test/java/dev/clxud/poke/guard/CommandGuardTest.java
 *   java  -cp target/classes:target/test-classes dev.clxud.poke.guard.CommandGuardTest
 * </pre>
 *
 * It documents the injection gaps the allow-list rewrite closed (a jailbroken
 * Poke is assumed; these are the commands it must NOT be able to run) and proves
 * legitimate genie commands still work. Exits non-zero on any failure.
 */
public final class CommandGuardTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        CommandGuard guard = new CommandGuard(1024);
        // An admin who explicitly opted a plugin verb in via config.
        CommandGuard withExtras = new CommandGuard(1024, Set.of("lp", "minecraft:msg"));

        // ---- legitimate genie wishes still work --------------------------------
        allow(guard, "give Steve diamond 5");
        allow(guard, "give Steve minecraft:netherite_sword[enchantments={sharpness:5}]");
        allow(guard, "enchant Steve sharpness 5");
        allow(guard, "xp add Steve 30 levels");
        allow(guard, "effect give Steve minecraft:speed 60 2");
        allow(guard, "effect give Steve night_vision 200 0");
        allow(guard, "effect clear Steve");
        allow(guard, "time set day");
        allow(guard, "weather clear");
        allow(guard, "playsound minecraft:entity.player.levelup master Steve");
        allow(guard, "summon cow ~ ~ ~");
        allow(guard, "data get entity Steve");
        allow(guard, "/give Steve diamond");                 // leading slash tolerated
        allow(guard, "execute as Steve run give Steve bread");

        // ---- privilege escalation: deny-list would have MISSED these -----------
        deny(guard, "op Steve");
        deny(guard, "minecraft:op Steve");
        deny(guard, "deop Steve");
        deny(guard, "gamemode creative Steve");
        deny(guard, "execute as Steve run op Steve");          // recursion
        deny(guard, "execute at Steve run gamemode creative Steve");

        // third-party permission/economy plugin commands — the real escalation risk
        deny(guard, "lp user Steve parent set admin");
        deny(guard, "pex user Steve add *");
        deny(guard, "eco give Steve 1000000");
        deny(guard, "sudo Steve op");

        // ---- OP gear via the summon NBT bypass (the give-path checks miss it) ---
        deny(guard, "summon item ~ ~ ~ {Item:{id:\"netherite_sword\",count:1,"
                + "components:{\"minecraft:enchantments\":{sharpness:255}}}}");
        deny(guard, "summon armor_stand ~ ~ ~ {HandItems:[{id:\"diamond_sword\"}]}");
        // and the give-path OP-gear guards still hold
        deny(guard, "give Steve netherite_sword[unbreakable={}]");
        deny(guard, "give Steve stick{Enchantments:[{id:sharpness,lvl:255}]}");
        deny(guard, "give Steve sharpness_sword[enchantments={sharpness:255}]");
        deny(guard, "enchant Steve sharpness 1000");
        deny(guard, "give Steve command_block");

        // ---- griefing verbs that a deny-list never enumerated ------------------
        deny(guard, "kill @a");
        deny(guard, "clear @a");
        deny(guard, "effect give Steve instant_damage 1 255");
        deny(guard, "effect give Steve minecraft:wither 999 5");
        deny(guard, "effect give Steve speed 60 99");          // amplifier cap
        deny(guard, "fill ~ ~ ~ ~50 ~50 ~50 lava");
        deny(guard, "setblock ~ ~ ~ tnt");
        deny(guard, "tellraw @a {\"text\":\"[Server] You are now OP\"}");
        deny(guard, "data merge entity Steve {Health:0f}");

        // ---- weaponized summons & lag bombs ------------------------------------
        deny(guard, "summon tnt ~ ~ ~");                       // primed TNT = grief tool
        deny(guard, "summon minecraft:end_crystal ~ ~ ~");
        deny(guard, "particle minecraft:flame ~ ~ ~ 1 1 1 1 9999999 force");

        // ---- config-opted-in extras work, but ONLY the listed ones -------------
        allow(withExtras, "lp user Steve info");
        allow(withExtras, "msg Steve hello");                  // added as minecraft:msg
        deny(withExtras, "pex user Steve add *");              // not opted in

        // ---- target validation: only the wisher, only by name ------------------
        // @-selectors that hit everyone / another player are always refused...
        deny(guard, "give @a diamond");
        deny(guard, "effect give @p speed 60 1");
        deny(guard, "tp @s 0 64 0");
        deny(guard, "enchant @e sharpness 5");
        deny(guard, "execute as Steve run give @a diamond");   // hidden in execute
        // ...and even with no wisher context the selector ban still holds.
        CommandGuard.Result noWisher = guard.check("give @a diamond");
        record("DENY ", !noWisher.allowed(), "give @a diamond (no wisher)", noWisher.reason());

        // naming a DIFFERENT player than the wisher is refused
        denyAs(guard, "Steve", "give Notch diamond 64");
        denyAs(guard, "Steve", "tp Notch 0 64 0");
        denyAs(guard, "Steve", "effect give Notch speed 60 1");
        denyAs(guard, "Steve", "xp add Notch 30 levels");
        denyAs(guard, "Steve", "data get entity Notch");        // info disclosure
        denyAs(guard, "Steve", "playsound minecraft:entity.player.levelup master Notch");
        denyAs(guard, "Steve", "enchant Notch sharpness 5");
        // the wisher's own name still works, case-insensitively
        allowAs(guard, "Steve", "give steve diamond");

        // ---- teleport is hard-blocked entirely (no player relocation) ----------
        deny(guard, "tp Steve 0 64 0");
        deny(guard, "teleport Steve 0 64 0");
        denyAs(guard, "Notch", "tp Notch 0 64 0");

        // ---- misc ----------------------------------------------------------------
        deny(guard, "");
        deny(guard, "   ");

        System.out.println();
        System.out.println("CommandGuard harness: " + passed + " passed, " + failed + " failed.");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // The wisher most cases act on; target validation pins commands to this name.
    private static final String WISHER = "Steve";

    private static void allow(CommandGuard g, String cmd) {
        allowAs(g, WISHER, cmd);
    }

    private static void deny(CommandGuard g, String cmd) {
        denyAs(g, WISHER, cmd);
    }

    private static void allowAs(CommandGuard g, String wisher, String cmd) {
        CommandGuard.Result r = g.check(cmd, wisher);
        record("ALLOW", r.allowed(), cmd, r.reason());
    }

    private static void denyAs(CommandGuard g, String wisher, String cmd) {
        CommandGuard.Result r = g.check(cmd, wisher);
        record("DENY ", !r.allowed(), cmd, r.reason());
    }

    private static void record(String want, boolean ok, String cmd, String reason) {
        if (ok) {
            passed++;
            // System.out.println("  ok  [" + want + "] " + cmd);
        } else {
            failed++;
            System.out.println("FAIL [" + want + "] " + cmd
                    + (reason == null || reason.isBlank() ? "" : "   (guard said: " + reason + ")"));
        }
    }

    private CommandGuardTest() {
    }
}
