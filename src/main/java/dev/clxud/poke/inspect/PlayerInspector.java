package dev.clxud.poke.inspect;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Reads a player's live inventory and vitals through the Bukkit API instead of
 * parsing {@code data get} NBT over RCON (which truncates large inventories).
 *
 * <p>Bukkit inventory/entity access must happen on the main thread, so every
 * read is bounced there via the scheduler and awaited from Clanker's worker
 * thread.
 */
public final class PlayerInspector {

    private final Plugin plugin;

    public PlayerInspector(Plugin plugin) {
        this.plugin = plugin;
    }

    private <T> T sync(Callable<T> task, T fallback) {
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, task).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** A complete, human-readable summary of everything the player is carrying. */
    public String describeInventory(UUID uuid) {
        return sync(() -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) {
                return "Player is offline; cannot inspect inventory.";
            }
            Map<String, Integer> agg = new LinkedHashMap<>();
            add(agg, p.getInventory().getStorageContents());
            add(agg, p.getInventory().getArmorContents());
            add(agg, new ItemStack[]{p.getInventory().getItemInOffHand()});

            if (agg.isEmpty()) {
                return "Inventory is completely empty (including armour and off-hand).";
            }
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : agg.entrySet()) {
                parts.add(e.getKey() + " x" + e.getValue());
                if (parts.size() >= 40) {
                    parts.add("...");
                    break;
                }
            }
            return p.getName() + " is carrying: " + String.join(", ", parts) + ".";
        }, "Could not read inventory (server busy).");
    }

    /** How many of a given item the player currently holds (-1 if id is invalid). */
    public long countItem(UUID uuid, String itemId) {
        Material material = Material.matchMaterial(itemId);
        if (material == null) {
            return -1L;
        }
        return sync(() -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) {
                return 0L;
            }
            long total = 0;
            for (ItemStack it : p.getInventory().getContents()) {
                if (it != null && it.getType() == material) {
                    total += it.getAmount();
                }
            }
            return total;
        }, 0L);
    }

    /** Health / xp / position, read straight from the live entity. */
    public String describeVital(UUID uuid, String kind) {
        return sync(() -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) {
                return "Player is offline.";
            }
            return switch (kind) {
                case "health" -> String.format("%.1f health (of 20), food %d/20",
                        p.getHealth(), p.getFoodLevel());
                case "xp" -> p.getLevel() + " levels (total xp points " + p.getTotalExperience() + ")";
                case "position" -> {
                    Location l = p.getLocation();
                    yield String.format("%s at x=%.0f y=%.0f z=%.0f",
                            l.getWorld() == null ? "?" : l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
                }
                default -> "unknown vital";
            };
        }, "Could not read player state (server busy).");
    }

    private static void add(Map<String, Integer> agg, ItemStack[] items) {
        if (items == null) {
            return;
        }
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir()) {
                continue;
            }
            agg.merge(label(it), it.getAmount(), Integer::sum);
        }
    }

    private static String label(ItemStack it) {
        StringBuilder sb = new StringBuilder(it.getType().getKey().getKey());
        Map<Enchantment, Integer> enchants = it.getEnchantments();
        if (!enchants.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
                parts.add(e.getKey().getKey().getKey() + " " + e.getValue());
            }
            sb.append(" [").append(String.join(", ", parts)).append("]");
        }
        return sb.toString();
    }
}
