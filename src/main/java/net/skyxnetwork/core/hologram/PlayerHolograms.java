package net.skyxnetwork.core.hologram;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.skyxnetwork.core.SkyXCore;
import net.skyxnetwork.core.core.CoreSettings;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player stat line above the vanilla nametag.
 *
 * The displays are deliberately NOT passengers. A passenger display inherits
 * the vehicle's head pose on some clients (especially through Geyser), which
 * makes the line move when the player looks up/down and gives Bedrock an extra
 * vertical mount offset. We instead place a normal TextDisplay at an absolute
 * eye-height offset and move it only when the player actually moves. The text
 * then behaves like a second, independent nametag and stays at a fixed height.
 *
 * Java and Bedrock get separate display entities so their height can be tuned
 * independently without sending different metadata to the same entity.
 */
public final class PlayerHolograms implements Listener {

    private final SkyXCore plugin;
    private volatile CoreSettings settings;
    private final Map<UUID, PlayerDisplays> displays = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> bedrockCache = new ConcurrentHashMap<>();
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
        for (PlayerDisplays pd : displays.values()) {
            pd.remove();
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
        textTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, ticks, ticks);
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (ignored(player)) {
                remove(player);
                continue;
            }
            PlayerDisplays pd = displays.get(player.getUniqueId());
            if (pd == null || !pd.valid(player)) {
                spawn(player);
                continue;
            }
            updateLocation(player, pd);
            updateText(player, pd);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return; // Looking around must not move the hologram.
        }
        PlayerDisplays pd = displays.get(event.getPlayer().getUniqueId());
        if (pd != null) updateLocation(event.getPlayer(), pd);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            spawn(player);
            for (Map.Entry<UUID, PlayerDisplays> entry : displays.entrySet()) {
                if (!entry.getKey().equals(player.getUniqueId())) {
                    show(player, entry.getValue());
                }
            }
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bedrockCache.remove(event.getPlayer().getUniqueId());
        remove(event.getPlayer());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = event.getPlayer();
            PlayerDisplays pd = displays.get(player.getUniqueId());
            if (pd == null) return;
            if (!pd.sameWorld(player)) {
                spawn(player);
            } else {
                updateLocation(player, pd);
            }
        }, 1L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = event.getPlayer();
            if (displays.containsKey(player.getUniqueId())) {
                spawn(player);
            }
            for (PlayerDisplays pd : displays.values()) {
                if (!pd.ownerId.equals(player.getUniqueId())) show(player, pd);
            }
        }, 2L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = event.getPlayer();
            if (displays.containsKey(player.getUniqueId())) spawn(player);
        }, 2L);
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PlayerDisplays pd = displays.get(event.getPlayer().getUniqueId());
            if (pd != null) updateLocation(event.getPlayer(), pd);
        }, 1L);
    }

    private void spawn(Player owner) {
        if (!settings.playerHologramsEnabled || ignored(owner)) return;
        remove(owner);

        Location eye = owner.getEyeLocation();
        Location javaLocation = eye.clone().add(0, settings.playerHologramHeight, 0);
        Location bedrockLocation = eye.clone().add(0, settings.playerHologramBedrockHeight, 0);
        TextDisplay javaDisplay = create(owner, javaLocation);
        TextDisplay bedrockDisplay = create(owner, bedrockLocation);
        PlayerDisplays pd = new PlayerDisplays(owner.getUniqueId(), javaDisplay, bedrockDisplay);
        displays.put(owner.getUniqueId(), pd);
        updateText(owner, pd);

        owner.hideEntity(plugin, javaDisplay);
        owner.hideEntity(plugin, bedrockDisplay);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(owner.getUniqueId())) {
                show(viewer, pd);
            }
        }
    }

    private TextDisplay create(Player owner, Location location) {
        location.setYaw(0.0f);
        location.setPitch(0.0f);
        TextDisplay display = owner.getWorld().spawn(location, TextDisplay.class, entity -> {
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
            entity.setTeleportDuration(0);
        });
        return display;
    }

    private void updateLocation(Player player, PlayerDisplays pd) {
        if (pd.javaDisplay == null || pd.bedrockDisplay == null) return;
        if (!pd.sameWorld(player)) {
            spawn(player);
            return;
        }
        Location eye = player.getEyeLocation();
        move(pd.javaDisplay, eye.clone().add(0, settings.playerHologramHeight, 0));
        move(pd.bedrockDisplay, eye.clone().add(0, settings.playerHologramBedrockHeight, 0));
    }

    private void move(TextDisplay display, Location target) {
        if (display == null || !display.isValid()) return;
        if (display.getWorld() != target.getWorld()) {
            display.teleport(target);
            return;
        }
        if (display.getLocation().distanceSquared(target) > 1.0E-5D) {
            display.teleport(target);
        }
    }

    private void updateText(Player player, PlayerDisplays pd) {
        Component rendered = LegacyComponentSerializer.legacySection().deserialize(text(player));
        if (rendered.equals(pd.text)) return;
        pd.text = rendered;
        if (pd.javaDisplay != null && pd.javaDisplay.isValid()) pd.javaDisplay.text(rendered);
        if (pd.bedrockDisplay != null && pd.bedrockDisplay.isValid()) pd.bedrockDisplay.text(rendered);
    }

    private void show(Player viewer, PlayerDisplays pd) {
        if (viewer.getUniqueId().equals(pd.ownerId)) return;
        TextDisplay target = isBedrock(viewer) ? pd.bedrockDisplay : pd.javaDisplay;
        if (target != null && target.isValid() && target.getWorld() == viewer.getWorld()) {
            viewer.showEntity(plugin, target);
        }
    }

    private boolean isBedrock(Player player) {
        return bedrockCache.computeIfAbsent(player.getUniqueId(), uuid -> {
            try {
                org.bukkit.plugin.Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
                if (floodgate == null) return false;
                // Floodgate is a separate plugin classloader; ask its loader for the API class.
                Class<?> apiClass = floodgate.getClass().getClassLoader()
                        .loadClass("org.geysermc.floodgate.api.FloodgateApi");
                Object api = apiClass.getMethod("getInstance").invoke(null);
                Object result = apiClass.getMethod("isFloodgatePlayer", UUID.class).invoke(api, uuid);
                return Boolean.TRUE.equals(result);
            } catch (Throwable ignored) {
                return false;
            }
        });
    }

    private void remove(Player owner) {
        PlayerDisplays pd = displays.remove(owner.getUniqueId());
        if (pd != null) pd.remove();
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

    private static final class PlayerDisplays {
        private final UUID ownerId;
        private final TextDisplay javaDisplay;
        private final TextDisplay bedrockDisplay;
        private Component text;

        private PlayerDisplays(UUID ownerId, TextDisplay javaDisplay, TextDisplay bedrockDisplay) {
            this.ownerId = ownerId;
            this.javaDisplay = javaDisplay;
            this.bedrockDisplay = bedrockDisplay;
        }

        private boolean valid(Player owner) {
            return javaDisplay != null && bedrockDisplay != null
                    && javaDisplay.isValid() && bedrockDisplay.isValid()
                    && sameWorld(owner);
        }

        private boolean sameWorld(Player owner) {
            return javaDisplay != null && bedrockDisplay != null
                    && javaDisplay.getWorld() == owner.getWorld()
                    && bedrockDisplay.getWorld() == owner.getWorld();
        }

        private void remove() {
            if (javaDisplay != null && javaDisplay.isValid()) javaDisplay.remove();
            if (bedrockDisplay != null && bedrockDisplay.isValid()) bedrockDisplay.remove();
        }
    }
}
