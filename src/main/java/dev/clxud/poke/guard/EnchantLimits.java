package dev.clxud.poke.guard;

import java.util.Map;

/** Vanilla maximum levels for survival-obtainable enchantments (MC 1.21.x). */
public final class EnchantLimits {

    private EnchantLimits() {
    }

    public static final Map<String, Integer> MAX = Map.ofEntries(
            // Armor
            Map.entry("protection", 4),
            Map.entry("fire_protection", 4),
            Map.entry("feather_falling", 4),
            Map.entry("blast_protection", 4),
            Map.entry("projectile_protection", 4),
            Map.entry("respiration", 3),
            Map.entry("aqua_affinity", 1),
            Map.entry("thorns", 3),
            Map.entry("depth_strider", 3),
            Map.entry("frost_walker", 2),
            Map.entry("binding_curse", 1),
            Map.entry("soul_speed", 3),
            Map.entry("swift_sneak", 3),
            // Swords / axes
            Map.entry("sharpness", 5),
            Map.entry("smite", 5),
            Map.entry("bane_of_arthropods", 5),
            Map.entry("knockback", 2),
            Map.entry("fire_aspect", 2),
            Map.entry("looting", 3),
            Map.entry("sweeping_edge", 3),
            Map.entry("sweeping", 3),
            // Tools
            Map.entry("efficiency", 5),
            Map.entry("silk_touch", 1),
            Map.entry("unbreaking", 3),
            Map.entry("fortune", 3),
            // Bow
            Map.entry("power", 5),
            Map.entry("punch", 2),
            Map.entry("flame", 1),
            Map.entry("infinity", 1),
            // Fishing rod
            Map.entry("luck_of_the_sea", 3),
            Map.entry("lure", 3),
            // Trident
            Map.entry("loyalty", 3),
            Map.entry("impaling", 5),
            Map.entry("riptide", 3),
            Map.entry("channeling", 1),
            // Crossbow
            Map.entry("multishot", 1),
            Map.entry("quick_charge", 3),
            Map.entry("piercing", 4),
            // Mace
            Map.entry("density", 5),
            Map.entry("breach", 4),
            Map.entry("wind_burst", 3),
            // Universal
            Map.entry("mending", 1),
            Map.entry("vanishing_curse", 1)
    );
}
