package dev.clxud.poke;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Locale;

/**
 * Watches chat for messages that start with the trigger word ("poke ...") and
 * routes the rest to {@link PokeGenie}. If the genie isn't verified-reachable
 * yet (the startup self-test hasn't passed), the player is told it's asleep.
 */
public final class ChatListener implements Listener {

    private final PokeGenie genie;
    private final String trigger;

    public ChatListener(PokeGenie genie, String trigger) {
        this.genie = genie;
        this.trigger = trigger.toLowerCase(Locale.ROOT);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("poke.use")) {
            return;
        }

        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        String lower = message.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(trigger)) {
            return;
        }

        // Require the trigger to be its own word (so "pokemon" doesn't fire).
        String remainder = message.substring(trigger.length());
        if (!remainder.isEmpty() && !Character.isWhitespace(remainder.charAt(0))
                && remainder.charAt(0) != ',' && remainder.charAt(0) != ':' && remainder.charAt(0) != '!') {
            return;
        }

        String wish = remainder.replaceFirst("^[\\s,:!]+", "").trim();
        if (wish.isEmpty()) {
            player.sendMessage(Component.text("Poke » Speak your wish, mortal. \"poke <what you want>\".",
                    NamedTextColor.GOLD));
            return;
        }

        if (!genie.isReady()) {
            player.sendMessage(Component.text("Poke » The genie slumbers — its link to the beyond is not open. "
                    + "An admin must check the connection.", NamedTextColor.GOLD));
            return;
        }

        genie.submitWish(player, wish);
    }
}
