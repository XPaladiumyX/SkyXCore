package net.skyxnetwork.core.hologram;

import net.skyxnetwork.core.SkyXCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** A player who was dead when they disconnected can remain stuck on the death screen after reconnecting. */
public final class RespawnOnJoinListener implements Listener {
    private final SkyXCore plugin;

    public RespawnOnJoinListener(SkyXCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (!player.isDead() && player.getHealth() > 0) return;
            player.spigot().respawn();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline() || player.isDead() || player.getHealth() <= 0) return;
                World hub = Bukkit.getWorld("world_hub");
                if (hub == null) return;
                player.teleport(new Location(hub, 0.5024127502440502, 213.0, 0.5108881590330241,
                        0.1975976f, 2.634317f), PlayerTeleportEvent.TeleportCause.PLUGIN);
            }, 5L);
        }, 20L);
    }
}
