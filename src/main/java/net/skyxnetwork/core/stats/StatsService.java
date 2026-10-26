package net.skyxnetwork.core.stats;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import net.skyxnetwork.core.SkyXCore;
import net.skyxnetwork.core.core.CoreSettings;
import net.skyxnetwork.core.core.Database;
import net.skyxnetwork.core.core.PlayerRegistry;
import net.skyxnetwork.core.season.SeasonService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks competitive statistics locally. Writes are batched and asynchronous,
 * never on the server thread.
 */
public final class StatsService implements Listener {

    public static final String PVP_KILLS = "PVP_KILLS";
    public static final String MOB_KILLS = "MOB_KILLS";
    public static final String CROPS_BROKEN = "CROPS_BROKEN";
    public static final String BLOCKS_BROKEN = "BLOCKS_BROKEN";
    public static final String BOSS_DAMAGE = "BOSS_DAMAGE";

    private final SkyXCore plugin;
    private final Database database;
    private final PlayerRegistry players;
    private final SeasonService seasons;
    private volatile CoreSettings settings;

    private final Map<UUID, Map<String, Long>> pending = new ConcurrentHashMap<>();
    private final Map<String, Long> pvpCooldowns = new ConcurrentHashMap<>();
    private BukkitTask flushTask;

    public StatsService(SkyXCore plugin, Database database, PlayerRegistry players,
                        SeasonService seasons, CoreSettings settings) {
        this.plugin = plugin;
        this.database = database;
        this.players = players;
        this.seasons = seasons;
        this.settings = settings;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        scheduleFlush();
    }

    public void stop() {
        HandlerList.unregisterAll(this);
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        flushSync();
        pending.clear();
    }

    public void updateSettings(CoreSettings settings) {
        this.settings = settings;
    }

    public void scheduleFlush() {
        if (flushTask != null) flushTask.cancel();
        long ticks = Math.max(100L, settings.statsFlushSeconds * 20L);
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushAsync, ticks, ticks);
    }

    private String seasonId() {
        return seasons.currentSeasonId();
    }

    private void add(Player player, String category, long amount) {
        if (player == null || amount <= 0) return;
        CoreSettings current = settings;
        if (!current.statsEnabled) return;
        if (current.statsIgnoreCreative && player.getGameMode() == GameMode.CREATIVE) return;
        if (current.ignoredWorlds.contains(player.getWorld().getName())) return;
        String season = seasonId();
        if (season == null || season.isBlank()) return;
        pending.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                .merge(category, amount, Long::sum);
        players.remember(player.getUniqueId(), player.getName());
    }

    private Map<UUID, Map<String, Long>> drain() {
        Map<UUID, Map<String, Long>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<UUID, Map<String, Long>> entry : pending.entrySet()) {
            Map<String, Long> values = new HashMap<>();
            for (String category : new ArrayList<>(entry.getValue().keySet())) {
                Long value = entry.getValue().remove(category);
                if (value != null && value > 0) values.put(category, value);
            }
            if (!values.isEmpty()) snapshot.put(entry.getKey(), values);
        }
        return snapshot;
    }

    private void flushAsync() {
        Map<UUID, Map<String, Long>> snapshot = drain();
        if (snapshot.isEmpty()) return;
        try {
            write(snapshot);
        } catch (SQLException exception) {
            mergeBack(snapshot);
            plugin.getLogger().warning("Could not flush SkyXCore stats: " + exception.getMessage());
        }
    }

    private void flushSync() {
        Map<UUID, Map<String, Long>> snapshot = drain();
        if (snapshot.isEmpty()) return;
        try {
            write(snapshot);
        } catch (SQLException exception) {
            mergeBack(snapshot);
            plugin.getLogger().warning("Could not save SkyXCore stats on shutdown: " + exception.getMessage());
        }
    }

    private void mergeBack(Map<UUID, Map<String, Long>> snapshot) {
        for (Map.Entry<UUID, Map<String, Long>> entry : snapshot.entrySet()) {
            Map<String, Long> target = pending.computeIfAbsent(entry.getKey(), ignored -> new ConcurrentHashMap<>());
            for (Map.Entry<String, Long> value : entry.getValue().entrySet()) {
                target.merge(value.getKey(), value.getValue(), Long::sum);
            }
        }
    }

    private void write(Map<UUID, Map<String, Long>> snapshot) throws SQLException {
        String season = seasonId();
        if (season == null || season.isBlank()) return;
        String sql = "INSERT INTO " + database.table("stats")
                + " (season_id,uuid,category,value) VALUES (?,?,?,?) "
                + "ON DUPLICATE KEY UPDATE value=value+VALUES(value)";
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(15);
            for (Map.Entry<UUID, Map<String, Long>> player : snapshot.entrySet()) {
                for (Map.Entry<String, Long> value : player.getValue().entrySet()) {
                    if (value.getValue() == null || value.getValue() <= 0) continue;
                    statement.setString(1, season);
                    statement.setString(2, player.getKey().toString());
                    statement.setString(3, value.getKey());
                    statement.setLong(4, value.getValue());
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) return;
        if (settings.pvpBlockSameIp && sameIp(killer, victim)) return;
        String key = killer.getUniqueId() + ":" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = pvpCooldowns.get(key);
        if (last != null && now - last < settings.pvpKillCooldownSeconds * 1000L) return;
        pvpCooldowns.put(key, now);
        add(killer, PVP_KILLS, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player) return;
        Player killer = entity.getKiller();
        if (killer == null) return;
        if (settings.ignoreSpawnerMobs
                && entity.getEntitySpawnReason() == org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.SPAWNER) {
            return;
        }
        add(killer, MOB_KILLS, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        add(player, BLOCKS_BROKEN, 1);
        Material type = event.getBlock().getType();
        if (settings.crops.contains(type)) {
            add(player, CROPS_BROKEN, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDamage(EntityDamageByEntityEvent event) {
        Entity entity = event.getEntity();
        if (!settings.bosses.contains(entity.getType().name())) return;
        Player player = resolvePlayer(event.getDamager());
        if (player == null) return;
        long damage = Math.max(1L, Math.round(event.getFinalDamage()));
        add(player, BOSS_DAMAGE, damage);
    }

    private Player resolvePlayer(Entity entity) {
        if (entity instanceof Player player) return player;
        if (entity instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) return player;
        }
        return null;
    }

    private boolean sameIp(Player first, Player second) {
        if (first.getAddress() == null || second.getAddress() == null) return false;
        return first.getAddress().getAddress().getHostAddress()
                .equals(second.getAddress().getAddress().getHostAddress());
    }

    public Map<String, Long> current(UUID uuid) {
        Map<String, Long> values = new HashMap<>();
        Map<String, Long> pendingValues = pending.get(uuid);
        if (pendingValues != null) values.putAll(pendingValues);
        try {
            String season = seasonId();
            if (season == null) return values;
            try (Connection connection = database.open();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT category,value FROM " + database.table("stats")
                                 + " WHERE season_id=? AND uuid=?")) {
                statement.setQueryTimeout(5);
                statement.setString(1, season);
                statement.setString(2, uuid.toString());
                try (var result = statement.executeQuery()) {
                    while (result.next()) {
                        values.merge(result.getString(1), result.getLong(2), Long::sum);
                    }
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().fine("Could not read stats for " + uuid + ": " + exception.getMessage());
        }
        return values;
    }

    public long value(UUID uuid, String category) {
        return current(uuid).getOrDefault(category, 0L);
    }

    public StatsService self() {
        return this;
    }
}
