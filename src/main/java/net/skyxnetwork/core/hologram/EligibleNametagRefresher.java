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
 * The plugin owns the actual nametag TextDisplays. Clearing a viewer from its
 * renderer caches makes its normal update task send fresh spawn/mount packets
 * on the next pass, which repairs clients (especially Bedrock) that stop
 * rendering or keep an old passenger position. No nametag entity is recreated
 * here, so this stays cheap even with a large player count.
 */
final class EligibleNametagRefresher {

    private final SkyXCore plugin;
    private Object renderer;
    private Method clearViewer;
    private boolean unavailable;

    EligibleNametagRefresher(SkyXCore plugin) {
        this.plugin = plugin;
    }

    void refresh(Player viewer) {
        if (unavailable || viewer == null || !viewer.isOnline()) return;
        if (renderer == null || clearViewer == null) resolve();
        if (renderer == null || clearViewer == null) return;

        try {
            clearViewer.invoke(renderer, viewer.getUniqueId());
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

            Method method = value.getClass().getMethod("clearViewer", UUID.class);
            method.setAccessible(true);
            renderer = value;
            clearViewer = method;
            plugin.getLogger().info("EligiusNametag refresh bridge active.");
        } catch (Throwable exception) {
            unavailable = true;
            plugin.getLogger().fine("EligiusNametag refresh bridge unavailable: " + exception.getMessage());
        }
    }
}
