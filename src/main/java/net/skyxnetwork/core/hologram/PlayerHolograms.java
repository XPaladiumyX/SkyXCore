package net.skyxnetwork.core.hologram;

import me.clip.placeholderapi.PlaceholderAPI;
import net.skyxnetwork.core.SkyXCore;
import net.skyxnetwork.core.core.CoreSettings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A real per-player line above the in-game nametag. TAB cannot render a
 * per-player line natively on every client, so this uses a marker ArmorStand
 * with a visible custom name. It is compatible with Java and Bedrock clients.
 */
public final class PlayerHolograms implements Listener {

    private final SkyXCore plugin;
    private volatile CoreSettings settings;
    private final Map<UUID, ArmorStand> stands = new ConcurrentHashMap<>();
    private BukkitTask task;
    private long tick;

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
        if (task != null) task.cancel();
        task = null;
        for (ArmorStand stand : stands.values()) {
            if (stand != null && stand.isValid()) stand.remove();
        }
        stands.clear();
    }

    public void updateSettings(CoreSettings settings) {
        this.settings = settings;
        stop();
        start();
    }

    public void restartTask() {
        if (task != null) task.cancel();
        if (!settings.playerHologramsEnabled) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick,
                settings.playerHologramMoveTicks, settings.playerHologramMoveTicks);
    }

    private void tick() {
        tick++;
        boolean updateText = tick % Math.max(1, settings.playerHologramUpdateTicks
                / Math.max(1, settings.playerHologramMoveTicks)) == 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (ignored(player)) {
                remove(player);
                continue;
            }
            ArmorStand stand = stands.get(player.getUniqueId());
            if (stand == null || !stand.isValid()) {
                spawn(player);
                stand = stands.get(player.getUniqueId());
                if (stand == null) continue;
            }
            Location target = player.getLocation().add(0, settings.playerHologramHeight, 0);
            stand.teleport(target);
            stand.setFireTicks(0);
            if (updateText) {
                stand.setCustomName(text(player));
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> spawn(event.getPlayer()), 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer());
    }

    private void spawn(Player player) {
        if (!settings.playerHologramsEnabled || ignored(player)) return;
        ArmorStand existing = stands.get(player.getUniqueId());
        if (existing != null && existing.isValid()) return;
        Location location = player.getLocation().add(0, settings.playerHologramHeight, 0);
        World world = location.getWorld();
        if (world == null) return;
        ArmorStand stand = world.spawn(location, ArmorStand.class, entity -> {
            entity.setInvisible(true);
            entity.setMarker(true);
            entity.setSmall(true);
            entity.setGravity(false);
            entity.setBasePlate(false);
            entity.setArms(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
            entity.setPersistent(false);
            entity.setCustomNameVisible(true);
            entity.setCanPickupItems(false);
            entity.setCollidable(false);
            entity.setCustomName(text(player));
        });
        stands.put(player.getUniqueId(), stand);
    }

    private void remove(Player player) {
        ArmorStand stand = stands.remove(player.getUniqueId());
        if (stand != null && stand.isValid()) stand.remove();
    }

    private boolean ignored(Player player) {
        return !settings.playerHologramsEnabled || settings.playerHologramIgnoredWorlds.contains(player.getWorld().getName());
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
