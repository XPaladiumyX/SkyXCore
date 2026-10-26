package net.skyxnetwork.core.hologram;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

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
        detach(event.getPlayer());
    }

    /**
     * BetterRTP's teleport is asynchronous: the nametag reattaches during the
     * chunk load, so the command-time detach is not enough. Detach again at
     * the actual PlayerTeleportEvent, immediately before the server moves the
     * player.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        detach(event.getPlayer());
    }

    private void detach(Player player) {
        for (Entity passenger : new ArrayList<>(player.getPassengers())) {
            if (passenger instanceof TextDisplay) player.removePassenger(passenger);
        }
    }
}
