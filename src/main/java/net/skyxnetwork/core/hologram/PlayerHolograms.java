package net.skyxnetwork.core.hologram;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.skyxnetwork.core.SkyXCore;
import net.skyxnetwork.core.core.CoreSettings;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Per-player stat line above the nametag.
 *
 * This deliberately uses ordinary world TextDisplays, not passengers and not
 * client-side mount packets. Both approaches eventually conflicted with the
 * TAB/Geyser nametag pipeline. The display is moved only on the server thread,
 * one tick at a time, and client-side teleport interpolation makes Java smooth.
 */
public final class PlayerHolograms implements Listener {

    private static final int REFRESH_BATCH_SIZE = 4;

    private final SkyXCore plugin;
    private volatile CoreSettings settings;
    private final Map<UUID, PlayerDisplays> displays = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> bedrockCache = new ConcurrentHashMap<>();
    private final Queue<UUID> refreshQueue = new ConcurrentLinkedQueue<>();
    private final Class<?> gsitApiClass;
    private final Method gsitIsSitting;
    private BukkitTask textTask;
    private BukkitTask visibilityTask;
    private BukkitTask refreshTask;
    private BukkitTask refreshDrainTask;

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
        refreshQueue.clear();
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
        if (settings.playerHologramsEnabled) {
            long textTicks = Math.max(5L, settings.playerHologramUpdateTicks);
            textTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickText, textTicks, textTicks);
            visibilityTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickVisibility, 5L, 5L);
        }
        if (settings.playerHologramsEnabled || settings.playerHologramRefreshEligible) {
            long refreshTicks = Math.max(100L, settings.playerHologramRefreshSeconds * 20L);
            refreshTask = Bukkit.getScheduler().runTaskTimer(plugin,
                    this::queueRefresh, refreshTicks, refreshTicks);
            refreshDrainTask = Bukkit.getScheduler().runTaskTimer(plugin,
                    this::drainRefresh, 1L, 1L);
        }
    }

    private void cancelTasks() {
        if (textTask != null) textTask.cancel();
        if (visibilityTask != null) visibilityTask.cancel();
        if (refreshTask != null) refreshTask.cancel();
        if (refreshDrainTask != null) refreshDrainTask.cancel();
        textTask = null;
        visibilityTask = null;
        refreshTask = null;
        refreshDrainTask = null;
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

    private void tickVisibility() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerDisplays pd = displays.get(player.getUniqueId());
            if (pd != null && pd.valid(player)) updateVisibility(player, pd);
        }
    }

    /**
     * Starts a soft, staggered refresh cycle. Recreating a handful of displays
     * per tick avoids a large packet/entity spike when many players are online.
     */
    private void queueRefresh() {
        refreshQueue.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshQueue.add(player.getUniqueId());
        }
    }

    private void drainRefresh() {
        for (int index = 0; index < REFRESH_BATCH_SIZE; index++) {
            UUID id = refreshQueue.poll();
            if (id == null) return;
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) continue;
            if (settings.playerHologramsEnabled && !ignored(player)) {
                PlayerDisplays pd = displays.get(player.getUniqueId());
                if (pd != null && pd.valid(player)) updateText(player, pd);
                else spawn(player);
            }
            if (settings.playerHologramRefreshEligible && hasBedrockViewerNearby(player)) {
                refreshBedrockNametag(player);
            }
        }
    }

    private boolean hasBedrockViewerNearby(Player owner) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(owner.getUniqueId()) || !isBedrock(viewer)) continue;
            if (canSee(viewer, owner)) return true;
        }
        return false;
    }

    /**
     * Geyser refreshes stale TextDisplay passenger positions when the target's
     * entity state changes. Luck is not a Bedrock effect, so Water Breathing is
     * used as a harmless, hidden state update with no particles or HUD icon.
     */
    private void refreshBedrockNametag(Player player) {
        if (player.isDead() || player.getHealth() <= 0.0) return;
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.WATER_BREATHING, 20, 0, true, false, false));
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
            spawn(player);
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

        TextDisplay javaDisplay = create(owner, settings.playerHologramHeight);
        ArmorStand bedrockAnchor = createAnchor(owner);
        TextDisplay bedrockDisplay = create(owner, settings.playerHologramBedrockHeight);
        PlayerDisplays pd = new PlayerDisplays(owner.getUniqueId(), javaDisplay, bedrockAnchor, bedrockDisplay);
        pd.hidden = true;
        displays.put(owner.getUniqueId(), pd);
        updateText(owner, pd);

        owner.hideEntity(plugin, javaDisplay);
        owner.hideEntity(plugin, bedrockAnchor);
        owner.hideEntity(plugin, bedrockDisplay);
        updateVisibility(owner, pd);
    }

    private TextDisplay create(Player owner, double height) {
        return owner.getWorld().spawn(owner.getLocation(), TextDisplay.class, entity -> {
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
            entity.setTransformation(new Transformation(
                    new Vector3f(0.0f, (float) height, 0.0f),
                    new Quaternionf(), new Vector3f(1.0f, 1.0f, 1.0f), new Quaternionf()));
        });
    }

    private ArmorStand createAnchor(Player owner) {
        return owner.getWorld().spawn(owner.getLocation(), ArmorStand.class, entity -> {
            entity.setVisibleByDefault(false);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setGravity(false);
            entity.setSilent(true);
            entity.setMarker(true);
            entity.setInvisible(true);
            entity.setSmall(true);
            entity.setBasePlate(false);
            entity.setArms(false);
        });
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
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getUniqueId().equals(pd.ownerId)) continue;
            if (canSee(viewer, owner)) show(viewer, pd);
            else hide(viewer, pd);
        }
    }

    private void show(Player viewer, PlayerDisplays pd) {
        if (pd.hidden || viewer.getUniqueId().equals(pd.ownerId)) return;
        Player owner = Bukkit.getPlayer(pd.ownerId);
        if (owner == null || !canSee(viewer, owner)) {
            hide(viewer, pd);
            return;
        }
        try {
            Location location = owner.getLocation();
            if (pd.javaDisplay != null && pd.javaDisplay.isValid()) pd.javaDisplay.teleport(location);
            if (pd.bedrockAnchor != null && pd.bedrockAnchor.isValid()) pd.bedrockAnchor.teleport(location);
            if (pd.bedrockDisplay != null && pd.bedrockDisplay.isValid()) pd.bedrockDisplay.teleport(location);
        } catch (Throwable ignored) {
            // The display will be respawned on the next tick if its world unloaded.
        }
        if (isBedrock(viewer)) {
            if (pd.bedrockAnchor != null && pd.bedrockAnchor.isValid()) viewer.showEntity(plugin, pd.bedrockAnchor);
            if (pd.bedrockDisplay != null && pd.bedrockDisplay.isValid()) viewer.showEntity(plugin, pd.bedrockDisplay);
        } else if (pd.javaDisplay != null && pd.javaDisplay.isValid()) {
            viewer.showEntity(plugin, pd.javaDisplay);
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!viewer.isOnline() || pd.hidden || owner == null || !pd.valid(owner)
                    || viewer.getWorld() != owner.getWorld()) return;
            if (isBedrock(viewer)) {
                mount(viewer, owner, pd.bedrockAnchor);
                mount(viewer, pd.bedrockAnchor, pd.bedrockDisplay);
            } else {
                mount(viewer, owner, pd.javaDisplay);
            }
        });
    }

    private boolean canSee(Player viewer, Player owner) {
        if (viewer.getUniqueId().equals(owner.getUniqueId())) return false;
        if (viewer.getWorld() != owner.getWorld()) return false;
        double maxDistance = settings.playerHologramViewDistance;
        return viewer.getLocation().distanceSquared(owner.getLocation()) <= maxDistance * maxDistance;
    }

    private void hide(Player viewer, PlayerDisplays pd) {
        if (pd.javaDisplay != null && pd.javaDisplay.isValid()) viewer.hideEntity(plugin, pd.javaDisplay);
        if (pd.bedrockAnchor != null && pd.bedrockAnchor.isValid()) viewer.hideEntity(plugin, pd.bedrockAnchor);
        if (pd.bedrockDisplay != null && pd.bedrockDisplay.isValid()) viewer.hideEntity(plugin, pd.bedrockDisplay);
    }

    private void mount(Player viewer, Entity vehicle, Entity passenger) {
        if (vehicle == null || passenger == null || !passenger.isValid()) return;
        try {
            PacketContainer packet = new PacketContainer(PacketType.Play.Server.MOUNT);
            packet.getIntegers().write(0, vehicle.getEntityId());
            packet.getIntegerArrays().write(0, new int[]{passenger.getEntityId()});
            ProtocolLibrary.getProtocolManager().sendServerPacket(viewer, packet);
        } catch (Throwable exception) {
            plugin.getLogger().warning("Could not send hologram mount packet: " + exception.getMessage());
        }
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
        private final ArmorStand bedrockAnchor;
        private final TextDisplay bedrockDisplay;
        private Component text;
        private volatile boolean hidden;

        private PlayerDisplays(UUID ownerId, TextDisplay javaDisplay, ArmorStand bedrockAnchor,
                               TextDisplay bedrockDisplay) {
            this.ownerId = ownerId;
            this.javaDisplay = javaDisplay;
            this.bedrockAnchor = bedrockAnchor;
            this.bedrockDisplay = bedrockDisplay;
        }

        private boolean valid(Player owner) {
            return javaDisplay != null && bedrockAnchor != null && bedrockDisplay != null
                    && javaDisplay.isValid() && bedrockAnchor.isValid() && bedrockDisplay.isValid()
                    && sameWorld(owner);
        }

        private boolean sameWorld(Player owner) {
            return javaDisplay != null && bedrockAnchor != null && bedrockDisplay != null
                    && javaDisplay.getWorld() == owner.getWorld()
                    && bedrockAnchor.getWorld() == owner.getWorld()
                    && bedrockDisplay.getWorld() == owner.getWorld();
        }

        private void remove() {
            if (javaDisplay != null && javaDisplay.isValid()) javaDisplay.remove();
            if (bedrockDisplay != null && bedrockDisplay.isValid()) bedrockDisplay.remove();
            if (bedrockAnchor != null && bedrockAnchor.isValid()) bedrockAnchor.remove();
        }
    }
}
