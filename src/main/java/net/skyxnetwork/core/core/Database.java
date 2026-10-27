package net.skyxnetwork.core.core;

import net.skyxnetwork.core.SkyXCore;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Own SkyXCore tables plus the shared MySQL connection helper used by the
 * read-only external leaderboard sources.
 */
public final class Database {

    private final SkyXCore plugin;
    private final CoreSettings settings;

    public Database(SkyXCore plugin, CoreSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    public CoreSettings.DatabaseConfig config() {
        return settings.database;
    }

    public Connection open() throws SQLException {
        return open(settings.database);
    }

    public static Connection open(CoreSettings.DatabaseConfig config) throws SQLException {
        String url = "jdbc:mysql://" + config.host() + ":" + config.port() + "/" + config.name()
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=UTC"
                + "&connectTimeout=" + config.connectTimeoutMs()
                + "&socketTimeout=" + config.socketTimeoutMs();
        return DriverManager.getConnection(url, config.user(), config.password());
    }

    public String table(String suffix) {
        return quote(settings.database.tablePrefix() + "_" + suffix);
    }

    public String prefix() {
        return settings.database.tablePrefix();
    }

    public void ensureTables() throws SQLException {
        String players = table("players");
        String stats = table("stats");
        String seasons = table("seasons");
        String guilds = table("guilds");
        String members = table("guild_members");
        String invites = table("guild_invites");
        String results = table("season_results");
        String rewards = table("season_rewards");

        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            statement.execute("CREATE TABLE IF NOT EXISTS " + players + " ("
                    + "uuid CHAR(36) NOT NULL PRIMARY KEY,"
                    + "name VARCHAR(48) NULL,"
                    + "last_seen BIGINT NOT NULL DEFAULT 0"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");

            statement.execute("CREATE TABLE IF NOT EXISTS " + stats + " ("
                    + "season_id VARCHAR(32) NOT NULL,"
                    + "uuid CHAR(36) NOT NULL,"
                    + "category VARCHAR(32) NOT NULL,"
                    + "value BIGINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (season_id, uuid, category),"
                    + "KEY idx_stats_rank (season_id, category, value)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");

            statement.execute("CREATE TABLE IF NOT EXISTS " + seasons + " ("
                    + "id VARCHAR(32) NOT NULL PRIMARY KEY,"
                    + "started_at BIGINT NOT NULL,"
                    + "ends_at BIGINT NOT NULL,"
                    + "status VARCHAR(16) NOT NULL"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");

            statement.execute("CREATE TABLE IF NOT EXISTS " + guilds + " ("
                    + "id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "name VARCHAR(32) NOT NULL UNIQUE,"
                    + "tag VARCHAR(16) NOT NULL UNIQUE,"
                    + "leader_uuid CHAR(36) NOT NULL,"
                    + "description VARCHAR(128) NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "bank DOUBLE NOT NULL DEFAULT 0,"
                    + "power DOUBLE NOT NULL DEFAULT 0,"
                    + "open BOOLEAN NOT NULL DEFAULT 0,"
                    + "max_members INT NOT NULL DEFAULT 16,"
                    + "home_world VARCHAR(64) NULL,"
                    + "home_x DOUBLE NULL,"
                    + "home_y DOUBLE NULL,"
                    + "home_z DOUBLE NULL,"
                    + "home_yaw FLOAT NULL,"
                    + "home_pitch FLOAT NULL"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");

            statement.execute("CREATE TABLE IF NOT EXISTS " + members + " ("
                    + "guild_id INT NOT NULL,"
                    + "uuid CHAR(36) NOT NULL PRIMARY KEY,"
                    + "role VARCHAR(16) NOT NULL,"
                    + "joined_at BIGINT NOT NULL,"
                    + "contribution DOUBLE NOT NULL DEFAULT 0,"
                    + "KEY idx_guild_members (guild_id)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");

            statement.execute("CREATE TABLE IF NOT EXISTS " + invites + " ("
                    + "guild_id INT NOT NULL,"
                    + "uuid CHAR(36) NOT NULL,"
                    + "invited_by CHAR(36) NOT NULL,"
                    + "expires BIGINT NOT NULL,"
                    + "PRIMARY KEY (guild_id, uuid),"
                    + "KEY idx_guild_invites_uuid (uuid)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");

            statement.execute("CREATE TABLE IF NOT EXISTS " + results + " ("
                    + "season_id VARCHAR(32) NOT NULL,"
                    + "category VARCHAR(32) NOT NULL,"
                    + "rank_position INT NOT NULL,"
                    + "uuid CHAR(36) NULL,"
                    + "name VARCHAR(64) NULL,"
                    + "value DOUBLE NOT NULL DEFAULT 0,"
                    + "guild_id INT NULL,"
                    + "PRIMARY KEY (season_id, category, rank_position)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");

            statement.execute("CREATE TABLE IF NOT EXISTS " + rewards + " ("
                    + "season_id VARCHAR(32) NOT NULL,"
                    + "category VARCHAR(32) NOT NULL,"
                    + "rank_position INT NOT NULL,"
                    + "uuid CHAR(36) NULL,"
                    + "commands TEXT NULL,"
                    + "claimed BOOLEAN NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (season_id, category, rank_position)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
            statement.execute("ALTER TABLE " + guilds + " MODIFY tag VARCHAR(32) NOT NULL;");
            addColumn(connection, guilds, "friendly_fire", "BOOLEAN NOT NULL DEFAULT 0");
            addColumn(connection, guilds, "bank_withdraw_role", "VARCHAR(16) NOT NULL DEFAULT 'OFFICER'");
            addColumn(connection, guilds, "invite_role", "VARCHAR(16) NOT NULL DEFAULT 'OFFICER'");
            addColumn(connection, guilds, "home_role", "VARCHAR(16) NOT NULL DEFAULT 'MEMBER'");
        }
    }

    private static void addColumn(Connection connection, String table, String column, String definition)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition + ";");
        } catch (SQLException exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase();
            if (exception.getErrorCode() != 1060 && !message.contains("duplicate column")
                    && !message.contains("already exists")) {
                throw exception;
            }
        }
    }

    public boolean ping() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(3);
            try (ResultSet result = statement.executeQuery("SELECT 1")) {
                return result.next();
            }
        } catch (SQLException exception) {
            return false;
        }
    }

    public void rememberPlayer(String uuid, String name) {
        if (uuid == null || name == null) return;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO " + table("players") + " (uuid,name,last_seen) VALUES (?,?,?) "
                             + "ON DUPLICATE KEY UPDATE name=VALUES(name), last_seen=VALUES(last_seen)")) {
            statement.setQueryTimeout(3);
            statement.setString(1, uuid);
            statement.setString(2, name);
            statement.setLong(3, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().fine("Could not remember player " + name + ": " + exception.getMessage());
        }
    }

    public static String quote(String identifier) {
        if (identifier == null || !identifier.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("Unsafe SQL identifier: " + identifier);
        }
        return "`" + identifier + "`";
    }
}
