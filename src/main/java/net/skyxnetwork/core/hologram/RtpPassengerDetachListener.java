package net.skyxnetwork.core.hologram;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.ArrayList;

/**
 * EligiusNametag keeps a TextDisplay mounted on each player. BetterRTP calls
 * Player#teleport while that passenger is present; in this setup the teleport
 * can be reported as successful without actually moving the client.
 *
 * Detach the TextDisplay just before BetterRTP handles /rtp. Eligius will
 * reattach it automatically on its next refresh.
 */
public final class RtpPassengerDetachListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRtpCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().trim().toLowerCase();
        if (!message.equals("/rtp") && !message.startsWith("/rtp ")) return;
        Player player = event.getPlayer();
        for (Entity passenger : new ArrayList<>(player.getPassengers())) {
            if (passenger instanceof TextDisplay) player.removePassenger(passenger);
        }
    }
}
