package net.skyxnetwork.core.season;

import net.skyxnetwork.core.SkyXCore;
import net.skyxnetwork.core.core.CoreSettings;
import net.skyxnetwork.core.core.Database;
import net.skyxnetwork.core.leaderboard.LeaderboardData;
import net.skyxnetwork.core.leaderboard.LeaderboardRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

/** Owns the active leaderboard season and the season lifecycle. */
public final class SeasonService implements Listener {

    private final SkyXCore plugin;
    private final Database database;
    private volatile CoreSettings settings;
    private volatile String currentSeasonId;
    private volatile long currentEndsAt;

    public SeasonService(SkyXCore plugin, Database database, CoreSettings settings) {
        this.plugin = plugin;
        this.database = database;
        this.settings = settings;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        ensureSeason();
    }

    public void updateSettings(CoreSettings settings) {
        this.settings = settings;
    }

    public String currentSeasonId() {
        return currentSeasonId;
    }

    private void ensureSeason() {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM " + database.table("seasons")
                             + " WHERE status='ACTIVE' ORDER BY started_at DESC LIMIT 1")) {
            statement.setQueryTimeout(5);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    currentSeasonId = result.getString(1);
                    currentEndsAt = seasonEnds(currentSeasonId);
                    return;
                }
            }
            if (!settings.seasonEnabled || !settings.seasonAutoStart) return;
            createSeason(settings.seasonCurrentId, false);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not initialize leaderboard season: " + exception.getMessage());
        }
    }

    public boolean startSeason(String id) {
        return createSeason(id, true);
    }

    private boolean createSeason(String id, boolean closeExisting) {
        String season = id == null ? "" : id.trim().toUpperCase(Locale.ROOT);
        if (!season.matches("[A-Z0-9_-]{1,32}")) return false;
        long now = System.currentTimeMillis();
        long ends = now + Math.max(1, settings.seasonLengthDays) * 86_400_000L;
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement close = connection.prepareStatement(
                    "UPDATE " + database.table("seasons") + " SET status='ENDED' WHERE status='ACTIVE'");
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO " + database.table("seasons")
                                 + " (id,started_at,ends_at,status) VALUES (?,?,?,'ACTIVE') "
                                 + "ON DUPLICATE KEY UPDATE started_at=VALUES(started_at), "
                                 + "ends_at=VALUES(ends_at), status='ACTIVE'")) {
                if (closeExisting) close.executeUpdate();
                insert.setString(1, season);
                insert.setLong(2, now);
                insert.setLong(3, ends);
                insert.executeUpdate();
                try (PreparedStatement clearStats = connection.prepareStatement(
                        "DELETE FROM " + database.table("stats") + " WHERE season_id=?");
                     PreparedStatement clearResults = connection.prepareStatement(
                             "DELETE FROM " + database.table("season_results") + " WHERE season_id=?");
                     PreparedStatement clearRewards = connection.prepareStatement(
                             "DELETE FROM " + database.table("season_rewards") + " WHERE season_id=?")) {
                    clearStats.setString(1, season);
                    clearStats.executeUpdate();
                    clearResults.setString(1, season);
                    clearResults.executeUpdate();
                    clearRewards.setString(1, season);
                    clearRewards.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
            currentSeasonId = season;
            currentEndsAt = ends;
            plugin.getLogger().info("Leaderboard season " + season + " started.");
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not start season " + season + ": " + exception.getMessage());
            return false;
        }
    }

    public long currentEndsAt() {
        return currentEndsAt;
    }

    /**
     * Captures the top 5 of every category, stores the results and queues the
     * configured reward commands for delivery on the player's next join.
     */
    public boolean finalizeSeason(LeaderboardRepository repository) {
        String season = currentSeasonId;
        if (season == null) return false;
        long started = System.currentTimeMillis();
        try (Connection connection = database.open()) {
            for (CoreSettings.Category category : settings.categories) {
                LeaderboardData.Page page = repository.fetch(category, 0, 5, null);
                int rank = 1;
                for (LeaderboardData.Entry entry : page.entries()) {
                    if (rank > 5) break;
                    insertResult(connection, season, category.id(), rank, entry);
                    List<String> commands = settings.seasonRewards
                            .getOrDefault(category.id(), java.util.Map.of()).getOrDefault(rank, List.of());
                    if (!commands.isEmpty() && entry.uuid() != null) {
                        insertReward(connection, season, category.id(), rank, entry.uuid(), commands);
                    }
                    rank++;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + database.table("seasons") + " SET status='ENDED' WHERE id=? AND status='ACTIVE'")) {
                statement.setString(1, season);
                statement.executeUpdate();
            }
            currentSeasonId = null;
            currentEndsAt = 0;
            plugin.getLogger().info("Finalized season " + season + " in "
                    + (System.currentTimeMillis() - started) + "ms.");
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not finalize season " + season + ": " + exception.getMessage());
            return false;
        }
    }

    private void insertResult(Connection connection, String season, String category, int rank,
                              LeaderboardData.Entry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + database.table("season_results")
                        + " (season_id,category,rank_position,uuid,name,value,guild_id) VALUES (?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE uuid=VALUES(uuid),name=VALUES(name),"
                        + "value=VALUES(value),guild_id=VALUES(guild_id)")) {
            statement.setString(1, season);
            statement.setString(2, category);
            statement.setInt(3, rank);
            statement.setString(4, entry.uuid() == null ? null : entry.uuid().toString());
            statement.setString(5, entry.name());
            statement.setDouble(6, entry.value());
            if (entry.guild()) statement.setInt(7, entry.guildId());
            else statement.setNull(7, java.sql.Types.INTEGER);
            statement.executeUpdate();
        }
    }

    private void insertReward(Connection connection, String season, String category, int rank,
                              java.util.UUID uuid, List<String> commands) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + database.table("season_rewards")
                        + " (season_id,category,rank_position,uuid,commands,claimed) VALUES (?,?,?,?,?,0) "
                        + "ON DUPLICATE KEY UPDATE commands=VALUES(commands),claimed=0")) {
            statement.setString(1, season);
            statement.setString(2, category);
            statement.setInt(3, rank);
            statement.setString(4, uuid.toString());
            statement.setString(5, String.join("\n", commands));
            statement.executeUpdate();
        }
    }

    private long seasonEnds(String id) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT ends_at FROM " + database.table("seasons") + " WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        } catch (SQLException exception) {
            return 0;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin,
                () -> processPendingRewards(player), 40L);
    }

    private void processPendingRewards(Player player) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT season_id,category,rank_position,commands FROM " + database.table("season_rewards")
                             + " WHERE uuid=? AND claimed=0 ORDER BY season_id,category,rank_position")) {
            statement.setQueryTimeout(8);
            statement.setString(1, player.getUniqueId().toString());
            try (ResultSet result = statement.executeQuery()) {
                List<String> commands = new java.util.ArrayList<>();
                List<String> keys = new java.util.ArrayList<>();
                while (result.next()) {
                    keys.add(result.getString(1) + "|" + result.getString(2) + "|" + result.getInt(3));
                    String raw = result.getString(4);
                    if (raw != null) commands.addAll(List.of(raw.split("\n")));
                }
                if (commands.isEmpty()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    for (String raw : commands) {
                        String command = raw.replace("%player%", player.getName())
                                .replace("%uuid%", player.getUniqueId().toString());
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                    }
                    for (String key : keys) {
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> markClaimed(key));
                    }
                    player.sendMessage(plugin.prefixed("season-reward-claimed"));
                });
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not process pending season rewards: " + exception.getMessage());
        }
    }

    private void markClaimed(String key) {
        String[] parts = key.split("\\|");
        if (parts.length != 3) return;
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + database.table("season_rewards")
                             + " SET claimed=1 WHERE season_id=? AND category=? AND rank_position=?")) {
            statement.setString(1, parts[0]);
            statement.setString(2, parts[1]);
            statement.setInt(3, Integer.parseInt(parts[2]));
            statement.executeUpdate();
        } catch (SQLException | NumberFormatException exception) {
            plugin.getLogger().fine("Could not mark season reward claimed: " + exception.getMessage());
        }
    }
}
