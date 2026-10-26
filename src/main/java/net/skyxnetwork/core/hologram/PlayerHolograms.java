package net.skyxnetwork.core.hologram;

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
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player stat line above the vanilla nametag.
 *
 * Passenger entities are the only way to follow a moving player with no
 * server-side position task and no interpolation delay. The Java display is
 * mounted directly on the player. The Bedrock display is mounted on an
 * invisible marker armor stand: Geyser handles a nested passenger much more
 * consistently, and the marker has no head rotation, so looking up/down does
 * not move the line.
 *
 * The marker is only shown to Bedrock viewers, together with the Bedrock text
 * display. The Java display is only shown to Java viewers. The owner never sees
 * either display.
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
        for (PlayerDisplays pd : displays.values()) pd.remove();
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
            pd.ensurePassengers(player);
            updateText(player, pd);
        }
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
        if (event.getFrom().getWorld() == event.getTo().getWorld()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> spawn(event.getPlayer()), 1L);
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

    private void spawn(Player owner) {
        if (!settings.playerHologramsEnabled || ignored(owner)) return;
        remove(owner);

        TextDisplay javaDisplay = create(owner, settings.playerHologramHeight);
        owner.addPassenger(javaDisplay);

        ArmorStand bedrockAnchor = createAnchor(owner);
        owner.addPassenger(bedrockAnchor);
        TextDisplay bedrockDisplay = create(owner, settings.playerHologramBedrockHeight);
        bedrockAnchor.addPassenger(bedrockDisplay);

        PlayerDisplays pd = new PlayerDisplays(owner.getUniqueId(), javaDisplay, bedrockAnchor, bedrockDisplay);
        displays.put(owner.getUniqueId(), pd);
        updateText(owner, pd);

        owner.hideEntity(plugin, javaDisplay);
        owner.hideEntity(plugin, bedrockAnchor);
        owner.hideEntity(plugin, bedrockDisplay);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(owner.getUniqueId())) show(viewer, pd);
        }
    }

    private TextDisplay create(Player owner, double height) {
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
            entity.setTeleportDuration(0);
            entity.setTransformation(new Transformation(
                    new Vector3f(0.0f, (float) height, 0.0f),
                    new Quaternionf(), new Vector3f(1.0f, 1.0f, 1.0f), new Quaternionf()));
        });
        return display;
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

    private void show(Player viewer, PlayerDisplays pd) {
        if (viewer.getUniqueId().equals(pd.ownerId)) return;
        if (isBedrock(viewer)) {
            if (pd.bedrockAnchor != null && pd.bedrockAnchor.isValid()
                    && pd.bedrockAnchor.getWorld() == viewer.getWorld()) {
                viewer.showEntity(plugin, pd.bedrockAnchor);
            }
            if (pd.bedrockDisplay != null && pd.bedrockDisplay.isValid()
                    && pd.bedrockDisplay.getWorld() == viewer.getWorld()) {
                viewer.showEntity(plugin, pd.bedrockDisplay);
            }
        } else if (pd.javaDisplay != null && pd.javaDisplay.isValid()
                && pd.javaDisplay.getWorld() == viewer.getWorld()) {
            viewer.showEntity(plugin, pd.javaDisplay);
        }
    }

    private boolean isBedrock(Player player) {
        return bedrockCache.computeIfAbsent(player.getUniqueId(), uuid -> {
            try {
                org.bukkit.plugin.Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
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
                    && javaDisplay.getWorld() == owner.getWorld()
                    && bedrockAnchor.getWorld() == owner.getWorld()
                    && bedrockDisplay.getWorld() == owner.getWorld();
        }

        private void ensurePassengers(Player owner) {
            if (!owner.getPassengers().contains(javaDisplay)) owner.addPassenger(javaDisplay);
            if (!owner.getPassengers().contains(bedrockAnchor)) owner.addPassenger(bedrockAnchor);
            if (!bedrockAnchor.getPassengers().contains(bedrockDisplay)) bedrockAnchor.addPassenger(bedrockDisplay);
        }

        private void remove() {
            if (javaDisplay != null && javaDisplay.isValid()) javaDisplay.remove();
            if (bedrockDisplay != null && bedrockDisplay.isValid()) bedrockDisplay.remove();
            if (bedrockAnchor != null && bedrockAnchor.isValid()) bedrockAnchor.remove();
        }
    }
}
