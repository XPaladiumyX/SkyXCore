package net.skyxnetwork.core.hologram;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.skyxnetwork.core.SkyXCore;
import net.skyxnetwork.core.core.CoreSettings;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player stat line above the nametag.
 *
 * This deliberately uses ordinary world TextDisplays, not passengers and not
 * client-side mount packets. Both approaches eventually conflicted with the
 * TAB/Geyser nametag pipeline. The display is moved only on the server thread,
 * one tick at a time, and client-side teleport interpolation makes Java smooth.
 */
public final class PlayerHolograms implements Listener {

    private final SkyXCore plugin;
    private volatile CoreSettings settings;
    private final Map<UUID, PlayerDisplays> displays = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> bedrockCache = new ConcurrentHashMap<>();
    private final Class<?> gsitApiClass;
    private final Method gsitIsSitting;
    private BukkitTask textTask;
    private BukkitTask positionTask;
    private BukkitTask visibilityTask;

    public PlayerHolograms(SkyXCore plugin, CoreSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        Class<?> apiClass = null;
        Method isSitting = null;
        Plugin gsit = Bukkit.getPluginManager().getPlugin("GSit");
        if (gsit != null) {
            try {
                apiClass = gsit.getClass().getClassLoader().loadClass("dev.geco.gsit.api.GSitAPI");
                isSitting = apiClass.getMethod("isSitting", LivingEntity.class);
            } catch (Throwable ignored) {
                apiClass = null;
                isSitting = null;
            }
        }
        this.gsitApiClass = apiClass;
        this.gsitIsSitting = isSitting;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        restartTasks();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> spawn(player), 20L);
        }
    }

    public void stop() {
        HandlerList.unregisterAll(this);
        cancelTasks();
        for (PlayerDisplays pd : displays.values()) pd.remove();
        displays.clear();
    }

    public void updateSettings(CoreSettings settings) {
        this.settings = settings;
        stop();
        start();
    }

    private void restartTasks() {
        cancelTasks();
        if (!settings.playerHologramsEnabled) return;
        long textTicks = Math.max(5L, settings.playerHologramUpdateTicks);
        textTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickText, textTicks, textTicks);
        positionTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPosition, 1L, 1L);
        visibilityTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickVisibility, 5L, 5L);
    }

    private void cancelTasks() {
        if (textTask != null) textTask.cancel();
        if (positionTask != null) positionTask.cancel();
        if (visibilityTask != null) visibilityTask.cancel();
        textTask = null;
        positionTask = null;
        visibilityTask = null;
    }

    private void tickText() {
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
            updateText(player, pd);
        }
    }

    private void tickPosition() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerDisplays pd = displays.get(player.getUniqueId());
            if (pd != null && !pd.hidden && pd.valid(player)) updateLocation(player, pd);
        }
    }

    private void tickVisibility() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerDisplays pd = displays.get(player.getUniqueId());
            if (pd != null && pd.valid(player)) updateVisibility(player, pd);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            spawn(player);
            for (Map.Entry<UUID, PlayerDisplays> entry : displays.entrySet()) {
                if (!entry.getKey().equals(player.getUniqueId())) show(player, entry.getValue());
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
        if (event.isCancelled()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = event.getPlayer();
            PlayerDisplays pd = displays.get(player.getUniqueId());
            if (pd == null) return;
            if (!pd.sameWorld(player)) spawn(player);
            else updateLocation(player, pd);
        }, 1L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = event.getPlayer();
            if (displays.containsKey(player.getUniqueId())) spawn(player);
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
    public void onDeath(PlayerDeathEvent event) {
        PlayerDisplays pd = displays.get(event.getEntity().getUniqueId());
        if (pd != null) {
            pd.hidden = true;
            hideLine(pd);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        PlayerDisplays pd = displays.get(event.getPlayer().getUniqueId());
        if (pd == null) return;
        pd.hidden = event.isSneaking();
        if (pd.hidden) hideLine(pd);
        else showLine(event.getPlayer(), pd);
    }

    private void spawn(Player owner) {
        if (!settings.playerHologramsEnabled || ignored(owner)) return;
        remove(owner);

        Location javaLocation = target(owner, settings.playerHologramHeight);
        Location bedrockLocation = target(owner, settings.playerHologramBedrockHeight);
        TextDisplay javaDisplay = create(owner, javaLocation);
        TextDisplay bedrockDisplay = create(owner, bedrockLocation);
        PlayerDisplays pd = new PlayerDisplays(owner.getUniqueId(), javaDisplay, bedrockDisplay);
        pd.hidden = true;
        displays.put(owner.getUniqueId(), pd);
        updateText(owner, pd);

        owner.hideEntity(plugin, javaDisplay);
        owner.hideEntity(plugin, bedrockDisplay);
        updateVisibility(owner, pd);
    }

    private Location target(Player owner, double height) {
        Location eye = owner.getEyeLocation();
        Location target = eye.clone().add(0, height, 0);
        target.setYaw(0.0f);
        target.setPitch(0.0f);
        return target;
    }

    private TextDisplay create(Player owner, Location location) {
        return owner.getWorld().spawn(location, TextDisplay.class, entity -> {
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
            entity.setTeleportDuration(1);
            entity.setTransformation(new Transformation(
                    new Vector3f(), new Quaternionf(), new Vector3f(1.0f, 1.0f, 1.0f), new Quaternionf()));
        });
    }

    private void updateLocation(Player owner, PlayerDisplays pd) {
        if (!pd.sameWorld(owner)) return;
        Location javaTarget = target(owner, settings.playerHologramHeight);
        Location bedrockTarget = target(owner, settings.playerHologramBedrockHeight);
        move(pd.javaDisplay, javaTarget);
        move(pd.bedrockDisplay, bedrockTarget);
    }

    private void move(TextDisplay display, Location target) {
        if (display == null || !display.isValid()) return;
        if (display.getWorld() != target.getWorld()) {
            display.teleport(target);
            return;
        }
        if (display.getLocation().distanceSquared(target) > 1.0E-4D) {
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

    private void updateVisibility(Player owner, PlayerDisplays pd) {
        boolean hidden = owner.isDead() || owner.isSneaking() || owner.isInsideVehicle() || isSitting(owner);
        if (pd.hidden == hidden) return;
        pd.hidden = hidden;
        if (hidden) hideLine(pd);
        else showLine(owner, pd);
    }

    private boolean isSitting(Player player) {
        if (gsitApiClass == null || gsitIsSitting == null) return false;
        try {
            return Boolean.TRUE.equals(gsitIsSitting.invoke(null, player));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void hideLine(PlayerDisplays pd) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(pd.ownerId)) continue;
            hide(viewer, pd);
        }
    }

    private void showLine(Player owner, PlayerDisplays pd) {
        if (pd.hidden) return;
        updateLocation(owner, pd);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(pd.ownerId)) show(viewer, pd);
        }
    }

    private void show(Player viewer, PlayerDisplays pd) {
        if (pd.hidden || viewer.getUniqueId().equals(pd.ownerId)) return;
        Player owner = Bukkit.getPlayer(pd.ownerId);
        if (owner == null || viewer.getWorld() != owner.getWorld()) return;
        updateLocation(owner, pd);
        if (isBedrock(viewer)) {
            if (pd.bedrockDisplay != null && pd.bedrockDisplay.isValid()) {
                viewer.showEntity(plugin, pd.bedrockDisplay);
            }
        } else if (pd.javaDisplay != null && pd.javaDisplay.isValid()) {
            viewer.showEntity(plugin, pd.javaDisplay);
        }
    }

    private void hide(Player viewer, PlayerDisplays pd) {
        if (pd.javaDisplay != null && pd.javaDisplay.isValid()) viewer.hideEntity(plugin, pd.javaDisplay);
        if (pd.bedrockDisplay != null && pd.bedrockDisplay.isValid()) viewer.hideEntity(plugin, pd.bedrockDisplay);
    }

    private boolean isBedrock(Player player) {
        return bedrockCache.computeIfAbsent(player.getUniqueId(), uuid -> {
            try {
                Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
                if (floodgate == null) return false;
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
        private volatile boolean hidden;

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
