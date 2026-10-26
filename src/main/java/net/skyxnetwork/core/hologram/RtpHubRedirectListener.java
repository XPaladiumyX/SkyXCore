package net.skyxnetwork.core.hologram;

import net.skyxnetwork.core.SkyXCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * BetterRTP's no-argument command does not honour the world_hub override in
 * this setup. Execute the normal world-target form from the hub instead.
 */
public final class RtpHubRedirectListener implements Listener {

    private final SkyXCore plugin;

    public RtpHubRedirectListener(SkyXCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().trim();
        if (!message.equalsIgnoreCase("/rtp")) return;
        Player player = event.getPlayer();
        if (!player.getWorld().getName().equalsIgnoreCase("world_hub")) return;
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                Bukkit.dispatchCommand(player, "rtp world world");
            }
        });
    }
}
