package dev.clxud.poke;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-player boss-bar throbber shown while a wish is being granted or is
 * waiting in line. Each bar is shown only to its own player (Adventure boss bars
 * are per-viewer), and a single shared task animates them all: a spinning glyph
 * plus a pulsing bar for the active wish, a steady bar with a queue position for
 * those still waiting. All mutations hop to the main thread, so it is safe to
 * call from the async wish/MCP/sweep paths.
 */
final class WishHud {

    private static final char[] SPINNER = {'|', '/', '-', '\\'};

    private final Plugin plugin;
    private final Map<UUID, Throb> bars = new ConcurrentHashMap<>();
    private BukkitTask animator;

    WishHud(Plugin plugin) {
        this.plugin = plugin;
    }

    /** The genie is actively working this player's wish (pulsing bar). */
    void showWorking(UUID uuid) {
        update(uuid, "weaving your wish", -1);
    }

    /** This player is waiting behind the active wish, at a 1-based position. */
    void showQueued(UUID uuid, int position) {
        update(uuid, "waiting for the genie", position);
    }

    void remove(UUID uuid) {
        sync(() -> {
            Throb t = bars.remove(uuid);
            if (t != null) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.hideBossBar(t.bar);
                }
            }
            stopAnimatorIfIdle();
        });
    }

    void shutdown() {
        sync(() -> {
            for (UUID id : new ArrayList<>(bars.keySet())) {
                Throb t = bars.remove(id);
                Player p = Bukkit.getPlayer(id);
                if (p != null && t != null) {
                    p.hideBossBar(t.bar);
                }
            }
            stopAnimatorIfIdle();
        });
    }

    private void update(UUID uuid, String label, int position) {
        sync(() -> {
            Throb t = bars.get(uuid);
            if (t == null) {
                BossBar bar = BossBar.bossBar(Component.empty(), 0f,
                        BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_20);
                t = new Throb(bar);
                bars.put(uuid, t);
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.showBossBar(bar);
                }
            }
            t.label = label;
            t.position = position;
            if (animator == null) {
                animator = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 0L, 4L);
            }
            render(t);
        });
    }

    private void tick() {
        for (Throb t : bars.values()) {
            t.phase++;
            render(t);
        }
    }

    private void render(Throb t) {
        boolean queued = t.position > 0;
        char spin = SPINNER[Math.floorMod(t.phase, SPINNER.length)];
        float progress;
        if (queued) {
            progress = 1f;
        } else {
            int p = Math.floorMod(t.phase, 20);           // ~4s up-and-back at the 4-tick cadence
            progress = p < 10 ? p / 10f : (20 - p) / 10f;
        }
        t.bar.progress(Math.max(0f, Math.min(1f, progress)));
        t.bar.color(BossBar.Color.GREEN);

        Component tail = Component.text(t.label + (queued ? " — #" + t.position + " in line" : "..."),
                NamedTextColor.GRAY);
        Component name = Component.text(spin + " ", NamedTextColor.GREEN)
                .append(Component.text("Poke", NamedTextColor.GREEN))
                .append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                .append(tail);
        t.bar.name(name);
    }

    private void stopAnimatorIfIdle() {
        if (bars.isEmpty() && animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void sync(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }

    private static final class Throb {
        final BossBar bar;
        String label = "";
        int position = -1;   // >0 = queued at that spot; <=0 = actively working
        int phase;

        Throb(BossBar bar) {
            this.bar = bar;
        }
    }
}
