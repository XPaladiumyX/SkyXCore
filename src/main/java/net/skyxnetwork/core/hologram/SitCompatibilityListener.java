package net.skyxnetwork.core.hologram;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;

import java.util.ArrayList;

/**
 * GSit's player-sit handler requires the clicked player's top passenger to be
 * a Player. The native nametag renderer puts a TextDisplay passenger on every
 * player, so GSit would otherwise refuse to sit.
 *
 * Remove only the TextDisplay passengers just before GSit's HIGHEST-priority
 * handler runs. The nametag renderer automatically re-attaches them on its
 * next update.
 */
public final class SitCompatibilityListener implements Listener {

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!(clicked instanceof Player target)) return;
        for (Entity passenger : new ArrayList<>(target.getPassengers())) {
            if (passenger instanceof TextDisplay) target.removePassenger(passenger);
        }
    }
}
