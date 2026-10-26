package net.skyxnetwork.core.hologram;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.skyxnetwork.core.SkyXCore;
import net.skyxnetwork.core.core.CoreSettings;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player stats line above the real nametag.
 *
 * A TextDisplay is mounted as a passenger of the player, so it follows the
 * player with zero teleport tasks and zero interpolation jitter. It is hidden
 * from its own owner (first person and F5) and shown to everyone else.
 */
public final class PlayerHolograms implements Listener {

    private final SkyXCore plugin;
    private volatile CoreSettings settings;
    private final Map<UUID, TextDisplay> displays = new ConcurrentHashMap<>();
    private BukkitTask textTask;

    public PlayerHolograms(SkyXCore plugin, CoreSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        restartTask();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> spawn(player), 20L);
        }
    }

    public void stop() {
        HandlerList.unregisterAll(this);
        if (textTask != null) textTask.cancel();
        textTask = null;
        for (TextDisplay display : displays.values()) {
            if (display != null && display.isValid()) display.remove();
        }
        displays.clear();
    }

    public void updateSettings(CoreSettings settings) {
        this.settings = settings;
        stop();
        start();
    }

    private void restartTask() {
        if (textTask != null) textTask.cancel();
        if (!settings.playerHologramsEnabled) return;
        long ticks = Math.max(5L, settings.playerHologramUpdateTicks);
        textTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateTexts, ticks, ticks);
    }

    private void updateTexts() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (ignored(player)) {
                remove(player);
                continue;
            }
            TextDisplay display = displays.get(player.getUniqueId());
            if (display == null || !display.isValid()) {
                spawn(player);
                continue;
            }
            if (!player.getPassengers().contains(display)) {
                player.addPassenger(display);
            }
            display.text(LegacyComponentSerializer.legacySection().deserialize(text(player)));
            // TextDisplay is visible by default = false, so only other players see it.
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> spawn(player), 20L);
        // New viewers must be explicitly shown existing displays.
        for (Map.Entry<UUID, TextDisplay> entry : displays.entrySet()) {
            if (entry.getKey().equals(player.getUniqueId())) continue;
            TextDisplay display = entry.getValue();
            if (display != null && display.isValid()) player.showEntity(plugin, display);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer());
    }

    private void spawn(Player owner) {
        if (!settings.playerHologramsEnabled || ignored(owner)) return;
        TextDisplay old = displays.remove(owner.getUniqueId());
        if (old != null && old.isValid()) old.remove();

        TextDisplay display = owner.getWorld().spawn(owner.getLocation(), TextDisplay.class, entity -> {
            entity.setVisibleByDefault(false);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setSilent(true);
            entity.setBillboard(Display.Billboard.CENTER);
            entity.setAlignment(TextDisplay.TextAlignment.CENTER);
            entity.setShadowed(false);
            entity.setSeeThrough(false);
            entity.setDefaultBackground(false);
            entity.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            entity.setLineWidth(220);
            entity.setViewRange(1.0f);
            entity.setTransformation(new Transformation(
                    new Vector3f(0.0f, (float) settings.playerHologramHeight, 0.0f),
                    new Quaternionf(), new Vector3f(1.0f, 1.0f, 1.0f), new Quaternionf()));
            entity.text(LegacyComponentSerializer.legacySection().deserialize(text(owner)));
        });
        displays.put(owner.getUniqueId(), display);
        owner.addPassenger(display);
        owner.hideEntity(plugin, display);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(owner.getUniqueId())) {
                viewer.showEntity(plugin, display);
            }
        }
    }

    private void remove(Player owner) {
        TextDisplay display = displays.remove(owner.getUniqueId());
        if (display != null && display.isValid()) display.remove();
    }

    private boolean ignored(Player player) {
        return !settings.playerHologramsEnabled
                || settings.playerHologramIgnoredWorlds.contains(player.getWorld().getName());
    }

    private String text(Player player) {
        String text = settings.playerHologramFormat;
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                text = PlaceholderAPI.setPlaceholders(player, text);
            } catch (Throwable ignored) {
                // Keep the raw format if another expansion is unavailable.
            }
        }
        if (text.length() > 200) text = text.substring(0, 200);
        return plugin.color(text);
    }
}
