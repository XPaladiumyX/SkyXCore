package net.skyxnetwork.core.leaderboard;

import net.skyxnetwork.core.SkyXCore;
import net.skyxnetwork.core.core.CoreSettings;
import net.skyxnetwork.core.core.Database;
import net.skyxnetwork.core.core.PlayerRegistry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Executes leaderboard queries. This class performs blocking JDBC work and must
 * only be called from an asynchronous scheduler task.
 */
public final class LeaderboardRepository {

    private final SkyXCore plugin;
    private final Database database;
    private final PlayerRegistry players;
    private volatile CoreSettings settings;

    public LeaderboardRepository(SkyXCore plugin, Database database, PlayerRegistry players,
                                 CoreSettings settings) {
        this.plugin = plugin;
        this.database = database;
        this.players = players;
        this.settings = settings;
    }

    public void updateSettings(CoreSettings settings) {
        this.settings = settings;
    }

    public LeaderboardData.Page fetch(CoreSettings.Category category, int requestedPage,
                                      int pageSize, UUID viewer) {
        int page = Math.max(0, requestedPage);
        try {
            return switch (category.source()) {
                case MONEY -> money(category, page, pageSize, viewer);
                case PLAYTIME -> playtime(category, page, pageSize, viewer);
                case JOBS -> jobs(category, page, pageSize, viewer);
                case GUILDS -> guilds(category, page, pageSize, viewer);
                case STATS -> stats(category, page, pageSize, viewer);
            };
        } catch (SQLException exception) {
            plugin.getLogger().warning("Leaderboard query failed for " + category.id()
                    + ": " + exception.getMessage());
            return new LeaderboardData.Page(List.of(), 0, 0, pageSize, null);
        }
    }

    private LeaderboardData.Page money(CoreSettings.Category category, int page, int pageSize,
                                       UUID viewer) throws SQLException {
        CoreSettings.DatabaseConfig source = settings.money;
        if (!source.complete()) return empty(pageSize);
        String table = Database.quote(source.table().isBlank() ? "MoneyTable" : source.table());
        long total;
        try (Connection connection = Database.open(source);
             PreparedStatement count = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE isPlayer=1")) {
            count.setQueryTimeout(8);
            try (ResultSet result = count.executeQuery()) {
                total = result.next() ? result.getLong(1) : 0;
            }
            page = clampPage(page, pageSize, total);
            List<LeaderboardData.Entry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid,name,money FROM " + table
                            + " WHERE isPlayer=1 ORDER BY money DESC LIMIT ? OFFSET ?")) {
                statement.setQueryTimeout(8);
                statement.setInt(1, pageSize);
                statement.setInt(2, page * pageSize);
                try (ResultSet result = statement.executeQuery()) {
                    int rank = page * pageSize + 1;
                    while (result.next()) {
                        UUID uuid = uuid(result.getString(1));
                        if (uuid == null) continue;
                        entries.add(new LeaderboardData.Entry(uuid, name(result.getString(2), uuid),
                                result.getDouble(3), rank++, false, 0));
                    }
                }
            }
            LeaderboardData.Entry viewerEntry = null;
            if (viewer != null) {
                try (PreparedStatement own = connection.prepareStatement(
                        "SELECT name,money FROM " + table + " WHERE uuid=? AND isPlayer=1")) {
                    own.setQueryTimeout(5);
                    own.setString(1, viewer.toString());
                    try (ResultSet result = own.executeQuery()) {
                        if (result.next()) {
                            double value = result.getDouble(2);
                            int rank = rankOf(connection, "SELECT COUNT(*)+1 FROM " + table
                                    + " WHERE isPlayer=1 AND money > ?", value);
                            viewerEntry = new LeaderboardData.Entry(viewer,
                                    name(result.getString(1), viewer), value, rank, false, 0);
                        }
                    }
                }
            }
            return new LeaderboardData.Page(entries, (int) total, page, pageSize, viewerEntry);
        }
    }

    private LeaderboardData.Page playtime(CoreSettings.Category category, int page, int pageSize,
                                          UUID viewer) throws SQLException {
        CoreSettings.DatabaseConfig source = settings.playtime;
        if (!source.complete()) return empty(pageSize);
        String table = Database.quote(source.table().isBlank() ? "skyx_playtime_players" : source.table());
        String grouped = "SELECT uuid, MAX(player_name) AS name, SUM(playtime) AS value FROM "
                + table + " GROUP BY uuid";
        long total;
        try (Connection connection = Database.open(source)) {
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM (" + grouped + ") t")) {
                count.setQueryTimeout(8);
                try (ResultSet result = count.executeQuery()) {
                    total = result.next() ? result.getLong(1) : 0;
                }
            }
            page = clampPage(page, pageSize, total);
            List<LeaderboardData.Entry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid,name,value FROM (" + grouped + ") t "
                            + "ORDER BY value DESC LIMIT ? OFFSET ?")) {
                statement.setQueryTimeout(8);
                statement.setInt(1, pageSize);
                statement.setInt(2, page * pageSize);
                try (ResultSet result = statement.executeQuery()) {
                    int rank = page * pageSize + 1;
                    while (result.next()) {
                        UUID uuid = uuid(result.getString(1));
                        if (uuid == null) continue;
                        entries.add(new LeaderboardData.Entry(uuid, name(result.getString(2), uuid),
                                result.getDouble(3), rank++, false, 0));
                    }
                }
            }
            LeaderboardData.Entry viewerEntry = null;
            if (viewer != null) {
                try (PreparedStatement own = connection.prepareStatement(
                        "SELECT MAX(player_name) AS name, SUM(playtime) AS value FROM " + table
                                + " WHERE uuid=? GROUP BY uuid")) {
                    own.setQueryTimeout(5);
                    own.setString(1, viewer.toString());
                    try (ResultSet result = own.executeQuery()) {
                        if (result.next()) {
                            double value = result.getDouble(2);
                            int rank = rankOf(connection, "SELECT COUNT(*)+1 FROM (" + grouped
                                    + ") t WHERE t.value > ?", value);
                            viewerEntry = new LeaderboardData.Entry(viewer,
                                    name(result.getString(1), viewer), value, rank, false, 0);
                        }
                    }
                }
            }
            return new LeaderboardData.Page(entries, (int) total, page, pageSize, viewerEntry);
        }
    }

    private LeaderboardData.Page jobs(CoreSettings.Category category, int page, int pageSize,
                                      UUID viewer) throws SQLException {
        CoreSettings.DatabaseConfig source = settings.jobs;
        if (!source.complete()) return empty(pageSize);
        String users = Database.quote(source.usersTable());
        String points = Database.quote(source.pointsTable());
        String grouped = "SELECT u.player_uuid AS uuid,u.username AS name,p.totalpoints AS value "
                + "FROM " + users + " u JOIN " + points + " p ON p.userid=u.id";
        try (Connection connection = Database.open(source)) {
            long total;
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + points)) {
                count.setQueryTimeout(8);
                try (ResultSet result = count.executeQuery()) {
                    total = result.next() ? result.getLong(1) : 0;
                }
            }
            page = clampPage(page, pageSize, total);
            List<LeaderboardData.Entry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid,name,value FROM (" + grouped + ") t "
                            + "ORDER BY value DESC LIMIT ? OFFSET ?")) {
                statement.setQueryTimeout(8);
                statement.setInt(1, pageSize);
                statement.setInt(2, page * pageSize);
                try (ResultSet result = statement.executeQuery()) {
                    int rank = page * pageSize + 1;
                    while (result.next()) {
                        UUID uuid = uuid(result.getString(1));
                        if (uuid == null) continue;
                        entries.add(new LeaderboardData.Entry(uuid, name(result.getString(2), uuid),
                                result.getDouble(3), rank++, false, 0));
                    }
                }
            }
            LeaderboardData.Entry viewerEntry = null;
            if (viewer != null) {
                try (PreparedStatement own = connection.prepareStatement(
                        "SELECT t.name,t.value FROM (" + grouped + ") t WHERE t.uuid=?")) {
                    own.setQueryTimeout(5);
                    own.setString(1, viewer.toString());
                    try (ResultSet result = own.executeQuery()) {
                        if (result.next()) {
                            double value = result.getDouble(2);
                            int rank = rankOf(connection, "SELECT COUNT(*)+1 FROM (" + grouped
                                    + ") t WHERE t.value > ?", value);
                            viewerEntry = new LeaderboardData.Entry(viewer,
                                    name(result.getString(1), viewer), value, rank, false, 0);
                        }
                    }
                }
            }
            return new LeaderboardData.Page(entries, (int) total, page, pageSize, viewerEntry);
        }
    }

    private LeaderboardData.Page stats(CoreSettings.Category category, int page, int pageSize,
                                       UUID viewer) throws SQLException {
        String season = databaseSeason();
        if (season == null || category.stat().isBlank()) return empty(pageSize);
        String stats = database.table("stats");
        String playersTable = database.table("players");
        String joined = "SELECT s.uuid,COALESCE(p.name,'') AS name,s.value FROM " + stats
                + " s LEFT JOIN " + playersTable + " p ON p.uuid=s.uuid WHERE s.season_id=? AND s.category=?";
        try (Connection connection = database.open()) {
            long total;
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + stats + " WHERE season_id=? AND category=?")) {
                count.setQueryTimeout(8);
                count.setString(1, season);
                count.setString(2, category.stat());
                try (ResultSet result = count.executeQuery()) {
                    total = result.next() ? result.getLong(1) : 0;
                }
            }
            page = clampPage(page, pageSize, total);
            List<LeaderboardData.Entry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    joined + " ORDER BY s.value DESC LIMIT ? OFFSET ?")) {
                statement.setQueryTimeout(8);
                statement.setString(1, season);
                statement.setString(2, category.stat());
                statement.setInt(3, pageSize);
                statement.setInt(4, page * pageSize);
                try (ResultSet result = statement.executeQuery()) {
                    int rank = page * pageSize + 1;
                    while (result.next()) {
                        UUID uuid = uuid(result.getString(1));
                        if (uuid == null) continue;
                        entries.add(new LeaderboardData.Entry(uuid, name(result.getString(2), uuid),
                                result.getDouble(3), rank++, false, 0));
                    }
                }
            }
            LeaderboardData.Entry viewerEntry = null;
            if (viewer != null) {
                try (PreparedStatement own = connection.prepareStatement(
                        "SELECT s.value,COALESCE(p.name,'') FROM " + stats + " s LEFT JOIN "
                                + playersTable + " p ON p.uuid=s.uuid WHERE s.season_id=? AND s.category=? "
                                + "AND s.uuid=?")) {
                    own.setQueryTimeout(5);
                    own.setString(1, season);
                    own.setString(2, category.stat());
                    own.setString(3, viewer.toString());
                    try (ResultSet result = own.executeQuery()) {
                        if (result.next()) {
                            double value = result.getDouble(1);
                            int rank = rankOf(connection, "SELECT COUNT(*)+1 FROM " + stats
                                    + " WHERE season_id=? AND category=? AND value > ?",
                                    season, category.stat(), value);
                            viewerEntry = new LeaderboardData.Entry(viewer,
                                    name(result.getString(2), viewer), value, rank, false, 0);
                        }
                    }
                }
            }
            return new LeaderboardData.Page(entries, (int) total, page, pageSize, viewerEntry);
        }
    }

    private LeaderboardData.Page guilds(CoreSettings.Category category, int page, int pageSize,
                                        UUID viewer) throws SQLException {
        String guilds = database.table("guilds");
        String members = database.table("guild_members");
        try (Connection connection = database.open()) {
            long total;
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + guilds)) {
                count.setQueryTimeout(8);
                try (ResultSet result = count.executeQuery()) {
                    total = result.next() ? result.getLong(1) : 0;
                }
            }
            page = clampPage(page, pageSize, total);
            List<LeaderboardData.Entry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id,name,tag,leader_uuid,power FROM " + guilds
                            + " ORDER BY power DESC LIMIT ? OFFSET ?")) {
                statement.setQueryTimeout(8);
                statement.setInt(1, pageSize);
                statement.setInt(2, page * pageSize);
                try (ResultSet result = statement.executeQuery()) {
                    int rank = page * pageSize + 1;
                    while (result.next()) {
                        UUID leader = uuid(result.getString(4));
                        entries.add(new LeaderboardData.Entry(leader,
                                result.getString(2), result.getDouble(5), rank++,
                                true, result.getInt(1)));
                    }
                }
            }
            LeaderboardData.Entry viewerEntry = null;
            if (viewer != null) {
                try (PreparedStatement own = connection.prepareStatement(
                        "SELECT g.id,g.name,g.leader_uuid,g.power FROM " + members + " m JOIN "
                                + guilds + " g ON g.id=m.guild_id WHERE m.uuid=?")) {
                    own.setQueryTimeout(5);
                    own.setString(1, viewer.toString());
                    try (ResultSet result = own.executeQuery()) {
                        if (result.next()) {
                            double value = result.getDouble(4);
                            int rank = rankOf(connection, "SELECT COUNT(*)+1 FROM " + guilds
                                    + " WHERE power > ?", value);
                            viewerEntry = new LeaderboardData.Entry(uuid(result.getString(3)),
                                    result.getString(2), value, rank, true, result.getInt(1));
                        }
                    }
                }
            }
            return new LeaderboardData.Page(entries, (int) total, page, pageSize, viewerEntry);
        }
    }

    private String databaseSeason() {
        // SeasonService is authoritative, but the repository only needs the
        // active season row to avoid a circular dependency.
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM " + database.table("seasons")
                             + " WHERE status='ACTIVE' ORDER BY started_at DESC LIMIT 1")) {
            statement.setQueryTimeout(5);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        } catch (SQLException exception) {
            return null;
        }
    }

    private int rankOf(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    private int clampPage(int page, int pageSize, long total) {
        int pages = Math.max(1, (int) Math.ceil(total / (double) Math.max(1, pageSize)));
        return Math.max(0, Math.min(page, pages - 1));
    }

    private LeaderboardData.Page empty(int pageSize) {
        return new LeaderboardData.Page(List.of(), 0, 0, pageSize, null);
    }

    private String name(String value, UUID uuid) {
        if (value != null && !value.isBlank()) return value;
        return players.name(uuid);
    }

    private UUID uuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

}
