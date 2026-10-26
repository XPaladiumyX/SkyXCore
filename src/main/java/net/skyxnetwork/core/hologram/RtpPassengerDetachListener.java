package net.skyxnetwork.core.hologram;

import net.skyxnetwork.core.SkyXCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BetterRTP performs its destination chunk load asynchronously and then uses
 * Player#teleportAsync. On this Paper build that updates the server-side
 * position but can leave the client at its old position until a later packet
 * burst. Manual /tp is synchronous and reliable.
 *
 * For a short window after /rtp, cancel the async teleport event and replay it
 * on the next tick with the synchronous Bukkit teleport. The destination is
 * the exact Location selected by BetterRTP, so its queue, safety checks, GUI
 * and cooldown remain in charge.
 */
public final class RtpPassengerDetachListener implements Listener {
    private static final long RTP_RESYNC_WINDOW_MS = 8_000L;

    private final SkyXCore plugin;
    private final Map<UUID, Long> pendingRtpTeleports = new ConcurrentHashMap<>();
    private final Set<UUID> replayingTeleport = ConcurrentHashMap.newKeySet();

    public RtpPassengerDetachListener(SkyXCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRtpCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().trim().toLowerCase();
        if (!isTrackedTeleportCommand(message)) return;
        pendingRtpTeleports.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + RTP_RESYNC_WINDOW_MS);
        detach(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        detach(player);

        if (event.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN || event.isCancelled()) return;
        if (replayingTeleport.contains(id)) return;

        Long until = pendingRtpTeleports.get(id);
        if (until == null) return;
        if (until < System.currentTimeMillis()) {
            pendingRtpTeleports.remove(id);
            return;
        }
        Location to = event.getTo();
        if (to == null) return;

        pendingRtpTeleports.remove(id);
        event.setCancelled(true);
        Location target = to.clone();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            replayingTeleport.add(id);
            try {
                detach(player);
                player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
            } finally {
                replayingTeleport.remove(id);
            }
        });
    }

    private boolean isTrackedTeleportCommand(String message) {
        return message.equals("/rtp") || message.startsWith("/rtp ")
                || message.equals("/hub") || message.startsWith("/hub ")
                || message.equals("/spawn") || message.startsWith("/spawn ")
                || message.equals("/lobby") || message.startsWith("/lobby ");
    }

    private void detach(Player player) {
        for (Entity passenger : new ArrayList<>(player.getPassengers())) {
            if (passenger instanceof TextDisplay) player.removePassenger(passenger);
        }
    }
}
