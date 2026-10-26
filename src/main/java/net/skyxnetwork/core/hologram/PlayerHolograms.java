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
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Packet-mounted player holograms.
 *
 * The display entities are normal world entities on the server, but the
 * passenger relationship is sent only to selected viewers with a MOUNT packet.
 * This gives the client the exact follow behaviour of a passenger (no movement
 * task and no interpolation delay) without putting a server-side passenger on
 * the player. Server teleports, /spawn, /hub, /rtp and other plugins are
 * therefore completely unaffected.
 *
 * Java viewers receive a display mounted directly on the player. Bedrock
 * viewers receive a display mounted on an invisible marker armor stand, which
 * avoids Geyser's player-head rotation offset. The owner never receives either
 * mount packet. Sneaking and death hide the line for everyone.
 */
public final class PlayerHolograms implements Listener {

    private final SkyXCore plugin;
    private volatile CoreSettings settings;
    private final Map<UUID, PlayerDisplays> displays = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> bedrockCache = new ConcurrentHashMap<>();
    private final boolean protocolLib;
    private static final Set<String> TELEPORT_COMMANDS = Set.of(
            "tp", "teleport", "spawn", "hub", "lobby", "rtp", "tpa", "tpahere",
            "tpaccept", "warp", "home", "back", "wild", "randomtp", "is", "island",
            "visit", "server");
    private final Class<?> gsitApiClass;
    private final Method gsitIsSitting;
    private BukkitTask textTask;
    private BukkitTask visibilityTask;

    public PlayerHolograms(SkyXCore plugin, CoreSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.protocolLib = Bukkit.getPluginManager().isPluginEnabled("ProtocolLib");
        if (!protocolLib) {
            plugin.getLogger().warning("ProtocolLib is not installed; player stat holograms are disabled.");
        }
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
        restartTask();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> spawn(player), 20L);
        }
    }

    public void stop() {
        HandlerList.unregisterAll(this);
        if (textTask != null) textTask.cancel();
        if (visibilityTask != null) visibilityTask.cancel();
        textTask = null;
        visibilityTask = null;
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
        if (!settings.playerHologramsEnabled || !protocolLib) return;
        long ticks = Math.max(5L, settings.playerHologramUpdateTicks);
        textTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, ticks, ticks);
        visibilityTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickVisibility, 5L, 5L);
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
            updateVisibility(player, pd);
            updateText(player, pd);
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

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerDisplays pd = displays.get(event.getPlayer().getUniqueId());
        if (pd != null && pd.javaDisplay != null && pd.javaDisplay.isValid()) {
            // A server-side passenger must be removed before the teleport.
            pd.javaDisplay.remove();
        }
        if (event.isCancelled()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> spawn(event.getPlayer()), 2L);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) spawn(event.getPlayer());
        }, 2L);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleportCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (!message.startsWith("/")) return;
        String[] parts = message.substring(1).toLowerCase(Locale.ROOT).split("\\s+");
        if (parts.length == 0) return;
        String command = parts[0];
        int colon = command.indexOf(':');
        if (colon >= 0) command = command.substring(colon + 1);
        boolean teleport = TELEPORT_COMMANDS.contains(command)
                || (command.equals("guild") && parts.length > 1 && parts[1].equals("home"));
        if (!teleport) return;
        PlayerDisplays pd = displays.get(event.getPlayer().getUniqueId());
        if (pd != null && pd.javaDisplay != null && pd.javaDisplay.isValid()) {
            pd.javaDisplay.remove();
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) spawn(event.getPlayer());
        }, 40L);
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
        if (pd.hidden) {
            hideLine(pd);
        } else if (!event.getPlayer().isDead()) {
            showLine(event.getPlayer(), pd);
        }
    }

    private void spawn(Player owner) {
        if (!settings.playerHologramsEnabled || !protocolLib || ignored(owner)) return;
        remove(owner);

        TextDisplay javaDisplay = create(owner, settings.playerHologramHeight);
        // The Java display is always a real passenger. The command and
        // teleport guards remove it before any teleport, so this does not
        // block /spawn, /hub, /rtp or plugin teleports.
        owner.addPassenger(javaDisplay);
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

    private void updateText(Player player, PlayerDisplays pd) {
        Component rendered = LegacyComponentSerializer.legacySection().deserialize(text(player));
        if (rendered.equals(pd.text)) return;
        pd.text = rendered;
        if (pd.javaDisplay != null && pd.javaDisplay.isValid()) pd.javaDisplay.text(rendered);
        if (pd.bedrockDisplay != null && pd.bedrockDisplay.isValid()) pd.bedrockDisplay.text(rendered);
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
            if (!viewer.getUniqueId().equals(pd.ownerId)) show(viewer, pd);
        }
    }

    private void refreshVisibility(PlayerDisplays pd) {
        if (pd.hidden) return;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.getUniqueId().equals(pd.ownerId)) show(viewer, pd);
        }
    }

    private void show(Player viewer, PlayerDisplays pd) {
        if (pd.hidden || viewer.getUniqueId().equals(pd.ownerId)) return;
        Player owner = Bukkit.getPlayer(pd.ownerId);
        if (owner == null || viewer.getWorld() != owner.getWorld()) return;
        // The Java display is a real passenger; teleporting a server-side
        // passenger dismounts it, which left it stuck at the spawn point.
        // Only the Bedrock entities need the fallback position sync.
        try {
            Location location = owner.getLocation();
            if (pd.bedrockAnchor != null && pd.bedrockAnchor.isValid()) pd.bedrockAnchor.teleport(location);
            if (pd.bedrockDisplay != null && pd.bedrockDisplay.isValid()) pd.bedrockDisplay.teleport(location);
        } catch (Throwable ignored) {
            // A display in an unloading world will respawn on the next tick.
        }
        if (isBedrock(viewer)) {
            if (pd.bedrockAnchor != null && pd.bedrockAnchor.isValid()) {
                viewer.showEntity(plugin, pd.bedrockAnchor);
            }
            if (pd.bedrockDisplay != null && pd.bedrockDisplay.isValid()) {
                viewer.showEntity(plugin, pd.bedrockDisplay);
            }
            mount(viewer, owner, pd.bedrockAnchor);
            mount(viewer, pd.bedrockAnchor, pd.bedrockDisplay);
        } else if (pd.javaDisplay != null && pd.javaDisplay.isValid()) {
            viewer.showEntity(plugin, pd.javaDisplay);
        }
    }

    private void hide(Player viewer, PlayerDisplays pd) {
        if (pd.javaDisplay != null && pd.javaDisplay.isValid()) viewer.hideEntity(plugin, pd.javaDisplay);
        if (pd.bedrockAnchor != null && pd.bedrockAnchor.isValid()) viewer.hideEntity(plugin, pd.bedrockAnchor);
        if (pd.bedrockDisplay != null && pd.bedrockDisplay.isValid()) viewer.hideEntity(plugin, pd.bedrockDisplay);
    }

    private void mount(Player viewer, Entity vehicle, Entity passenger) {
        if (!protocolLib || vehicle == null || passenger == null || !passenger.isValid()) return;
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
                || !protocolLib
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
