package net.skyxnetwork.core.core;

import net.skyxnetwork.core.SkyXCore;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps UUID -> last known name and persists it asynchronously. */
public final class PlayerRegistry implements Listener {

    private final SkyXCore plugin;
    private final Database database;
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    public PlayerRegistry(SkyXCore plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player player : Bukkit.getOnlinePlayers()) {
            remember(player.getUniqueId(), player.getName());
        }
    }

    public void remember(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) return;
        String previous = names.put(uuid, name);
        if (name.equals(previous)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                () -> database.rememberPlayer(uuid.toString(), name));
    }

    public String name(UUID uuid) {
        if (uuid == null) return "Unknown";
        String name = names.get(uuid);
        if (name != null) return name;
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            names.put(uuid, player.getName());
            return player.getName();
        }
        return uuid.toString().substring(0, 8);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        remember(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }
}
