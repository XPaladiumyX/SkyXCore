package net.skyxnetwork.core.hologram;

import net.skyxnetwork.core.SkyXCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Soft-refresh bridge for EligiusNametag.
 *
 * The plugin owns the actual nametag TextDisplays. Recreating a target's two
 * displays is the only reliable way to repair a Bedrock client that keeps the
 * old passenger position; its normal update pass recreates them immediately.
 * The work is staggered by PlayerHolograms, so only a few targets are touched
 * per tick.
 */
final class EligibleNametagRefresher {

    private final SkyXCore plugin;
    private Object renderer;
    private Method destroyNametag;
    private boolean unavailable;

    EligibleNametagRefresher(SkyXCore plugin) {
        this.plugin = plugin;
    }

    void refresh(Player target) {
        if (unavailable || target == null || !target.isOnline()) return;
        if (renderer == null || destroyNametag == null) resolve();
        if (renderer == null || destroyNametag == null) return;

        try {
            // Destroying the two TextDisplays makes EligiusNametag create fresh
            // entities on its next 0.5s pass. A visibility-cache reset alone is
            // not enough on Bedrock: the client keeps the stale passenger
            // position until the entity is actually replaced.
            destroyNametag.invoke(renderer, target.getUniqueId());
        } catch (Throwable exception) {
            unavailable = true;
            plugin.getLogger().fine("EligiusNametag refresh bridge disabled: " + exception.getMessage());
        }
    }

    private void resolve() {
        Plugin eligible = Bukkit.getPluginManager().getPlugin("EligiusNametag");
        if (eligible == null || !eligible.isEnabled()) return;

        try {
            Field rendererField = eligible.getClass().getDeclaredField("renderer");
            rendererField.setAccessible(true);
            Object value = rendererField.get(eligible);
            if (value == null) return;

            Method method = value.getClass().getMethod("destroyNametag", UUID.class);
            method.setAccessible(true);
            renderer = value;
            destroyNametag = method;
            plugin.getLogger().info("EligiusNametag refresh bridge active.");
        } catch (Throwable exception) {
            unavailable = true;
            plugin.getLogger().fine("EligiusNametag refresh bridge unavailable: " + exception.getMessage());
        }
    }
}
