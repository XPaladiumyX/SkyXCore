package net.skyxnetwork.core.hologram;

import net.skyxnetwork.core.SkyXCore;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * EligiusNametag creates plain TextDisplay entities without exposing text
 * shadow/background settings. Remove both so the second, shorter line does
 * not appear inside a common dark rectangle and the nametag stays clean.
 */
final class EligibleNametagStyler {

    private final SkyXCore plugin;
    private Map<?, ?> activeEntities;
    private boolean unavailable;

    EligibleNametagStyler(SkyXCore plugin) {
        this.plugin = plugin;
    }

    void styleAll() {
        if (unavailable) return;
        if (activeEntities == null) resolve();
        if (activeEntities == null) return;

        try {
            for (Object value : activeEntities.values()) {
                if (!(value instanceof List<?> lines)) continue;
                for (Object line : lines) {
                    if (!(line instanceof TextDisplay display) || !display.isValid()) continue;
                    if (display.isShadowed()) display.setShadowed(false);
                    if (display.isDefaultBackground()) {
                        display.setDefaultBackground(false);
                        display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    }
                }
            }
        } catch (Throwable exception) {
            unavailable = true;
            plugin.getLogger().fine("EligiusNametag style bridge disabled: " + exception.getMessage());
        }
    }

    private void resolve() {
        Plugin eligible = Bukkit.getPluginManager().getPlugin("EligiusNametag");
        if (eligible == null || !eligible.isEnabled()) return;

        try {
            Field rendererField = eligible.getClass().getDeclaredField("renderer");
            rendererField.setAccessible(true);
            Object renderer = rendererField.get(eligible);
            if (renderer == null) return;

            Field entitiesField = renderer.getClass().getDeclaredField("activeEntities");
            entitiesField.setAccessible(true);
            Object value = entitiesField.get(renderer);
            if (!(value instanceof Map<?, ?> map)) return;

            activeEntities = map;
            plugin.getLogger().info("EligiusNametag style bridge active.");
        } catch (Throwable exception) {
            unavailable = true;
            plugin.getLogger().fine("EligiusNametag style bridge unavailable: " + exception.getMessage());
        }
    }
}
