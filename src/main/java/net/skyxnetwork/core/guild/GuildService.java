package net.skyxnetwork.core.guild;

import net.skyxnetwork.core.SkyXCore;
import net.skyxnetwork.core.core.CoreSettings;
import net.skyxnetwork.core.core.Database;
import net.skyxnetwork.core.core.PlayerRegistry;
import net.skyxnetwork.core.season.SeasonService;
import org.bukkit.ChatColor;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Synchronous JDBC guild operations. Commands call these methods from an
 * asynchronous scheduler task and return to the main thread only to message
 * the player or open a GUI.
 */
public final class GuildService {

    public enum Result {
        OK, NOT_FOUND, ALREADY_IN_GUILD, NOT_IN_GUILD, NAME_TAKEN, INVALID,
        FULL, NO_INVITE, LEADER_CANNOT_LEAVE, NOT_LEADER, NOT_OFFICER, DISBANDED
    }

    public record Outcome(Result result, Guild.Info guild) {
        public boolean ok() {
            return result == Result.OK;
        }
    }

    private final SkyXCore plugin;
    private final Database database;
    private final PlayerRegistry players;
    private final SeasonService seasons;
    private volatile CoreSettings settings;
    private volatile Map<UUID, Guild.Info> tagCache = Map.of();
    private volatile Map<UUID, Guild.Member> memberCache = Map.of();

    public GuildService(SkyXCore plugin, Database database, PlayerRegistry players,
                        SeasonService seasons, CoreSettings settings) {
        this.plugin = plugin;
        this.database = database;
        this.players = players;
        this.seasons = seasons;
        this.settings = settings;
    }

    public void start() {
        refreshTagCacheAsync();
    }

    public void updateSettings(CoreSettings settings) {
        this.settings = settings;
    }

    /** Cached guild tag/name for PlaceholderAPI and chat/TAB formatting. */
    public String tag(UUID uuid) {
        Guild.Info guild = tagCache.get(uuid);
        return guild == null ? "" : "&8[" + guild.tag() + "&8]";
    }

    public String plainTag(UUID uuid) {
        Guild.Info guild = tagCache.get(uuid);
        return guild == null ? "" : ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', guild.tag()));
    }

    public String tagMini(UUID uuid) {
        Guild.Info guild = tagCache.get(uuid);
        if (guild == null) return "";
        return mini("&8[" + guild.tag() + "&8]");
    }

    public String guildName(UUID uuid) {
        Guild.Info guild = tagCache.get(uuid);
        return guild == null ? "" : guild.name();
    }

    public double guildPower(UUID uuid) {
        Guild.Info guild = tagCache.get(uuid);
        return guild == null ? 0 : guild.power();
    }

    public void refreshTagCacheAsync() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::refreshTagCache);
    }

    private void refreshTagCache() {
        Map<UUID, Guild.Info> tags = new HashMap<>();
        Map<UUID, Guild.Member> members = new HashMap<>();
        for (Guild.Info guild : allGuilds()) {
            for (Guild.Member member : members(guild.id())) {
                tags.put(member.uuid(), guild);
                members.put(member.uuid(), member);
            }
        }
        tagCache = Map.copyOf(tags);
        memberCache = Map.copyOf(members);
    }

    public Guild.Info cachedGuild(UUID uuid) {
        return tagCache.get(uuid);
    }

    public Guild.Member cachedMember(UUID uuid) {
        return memberCache.get(uuid);
    }

    private String mini(String legacy) {
        String value = ChatColor.translateAlternateColorCodes('&', legacy);
        value = value.replace("\u00a70", "<black>").replace("\u00a71", "<dark_blue>")
                .replace("\u00a72", "<dark_green>").replace("\u00a73", "<dark_aqua>")
                .replace("\u00a74", "<dark_red>").replace("\u00a75", "<dark_purple>")
                .replace("\u00a76", "<gold>").replace("\u00a77", "<gray>")
                .replace("\u00a78", "<dark_gray>").replace("\u00a79", "<blue>")
                .replace("\u00a7a", "<green>").replace("\u00a7b", "<aqua>")
                .replace("\u00a7c", "<red>").replace("\u00a7d", "<light_purple>")
                .replace("\u00a7e", "<yellow>").replace("\u00a7f", "<white>")
                .replace("\u00a7l", "<bold>").replace("\u00a7o", "<italic>")
                .replace("\u00a7n", "<underlined>").replace("\u00a7m", "<strikethrough>")
                .replace("\u00a7r", "<reset>");
        return value + "<reset>";
    }

    public Guild.Info guildByPlayer(UUID uuid) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT g.* FROM " + database.table("guilds") + " g JOIN "
                             + database.table("guild_members") + " m ON m.guild_id=g.id WHERE m.uuid=?")) {
            statement.setQueryTimeout(5);
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? guild(result) : null;
            }
        } catch (SQLException exception) {
            return null;
        }
    }

    public Guild.Info guildByNameOrTag(String name) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM " + database.table("guilds")
                             + " WHERE LOWER(name)=LOWER(?) OR LOWER(tag)=LOWER(?) LIMIT 1")) {
            statement.setQueryTimeout(5);
            statement.setString(1, name);
            statement.setString(2, name);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? guild(result) : null;
            }
        } catch (SQLException exception) {
            return null;
        }
    }

    public Guild.Member member(UUID uuid) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT m.guild_id,m.uuid,COALESCE(p.name,'') AS name,m.role,m.joined_at,m.contribution "
                             + "FROM " + database.table("guild_members") + " m LEFT JOIN "
                             + database.table("players") + " p ON p.uuid=m.uuid WHERE m.uuid=?")) {
            statement.setQueryTimeout(5);
            statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return member(result);
            }
        } catch (SQLException exception) {
            return null;
        }
    }

    public List<Guild.Member> members(int guildId) {
        List<Guild.Member> members = new ArrayList<>();
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT m.guild_id,m.uuid,COALESCE(p.name,'') AS name,m.role,m.joined_at,m.contribution "
                             + "FROM " + database.table("guild_members") + " m LEFT JOIN "
                             + database.table("players") + " p ON p.uuid=m.uuid WHERE m.guild_id=? "
                             + "ORDER BY m.role ASC, m.joined_at ASC")) {
            statement.setQueryTimeout(5);
            statement.setInt(1, guildId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) members.add(member(result));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not list guild members: " + exception.getMessage());
        }
        return members;
    }

    public Outcome create(String name, String tag, UUID leader) {
        String cleanName = name == null ? "" : name.trim();
        String cleanTag = tag == null ? "" : tag.trim();
        String visibleTag = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', cleanTag));
        if (!cleanName.matches("[A-Za-z0-9_ ]{" + settings.guildNameMin + "," + settings.guildNameMax + "}")
                || !visibleTag.matches("[A-Za-z0-9]{" + settings.guildTagMin + "," + settings.guildTagMax + "}")
                || cleanTag.length() > 32) {
            return new Outcome(Result.INVALID, null);
        }
        for (Guild.Info existing : allGuilds()) {
            String existingTag = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', existing.tag()));
            if (existingTag.equalsIgnoreCase(visibleTag)) return new Outcome(Result.NAME_TAKEN, null);
        }
        if (guildByPlayer(leader) != null) return new Outcome(Result.ALREADY_IN_GUILD, null);
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + database.table("guilds")
                            + " (name,tag,leader_uuid,description,created_at,bank,power,open,max_members) "
                            + "VALUES (?,?,?,?,?,0,?,0,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, cleanName);
                insert.setString(2, cleanTag.toUpperCase(Locale.ROOT));
                insert.setString(3, leader.toString());
                insert.setString(4, "");
                insert.setLong(5, System.currentTimeMillis());
                insert.setDouble(6, settings.guildPower.member());
                insert.setInt(7, settings.guildMaxMembers);
                insert.executeUpdate();
                int id;
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) {
                        connection.rollback();
                        return new Outcome(Result.INVALID, null);
                    }
                    id = keys.getInt(1);
                }
                try (PreparedStatement member = connection.prepareStatement(
                        "INSERT INTO " + database.table("guild_members")
                                + " (guild_id,uuid,role,joined_at,contribution) VALUES (?,?,?,?,0)")) {
                    member.setInt(1, id);
                    member.setString(2, leader.toString());
                    member.setString(3, Guild.Role.LEADER.name());
                    member.setLong(4, System.currentTimeMillis());
                    member.executeUpdate();
                }
                connection.commit();
                refreshTagCacheAsync();
                return new Outcome(Result.OK, guildById(id));
            } catch (SQLException exception) {
                connection.rollback();
                if (exception.getMessage() != null && exception.getMessage().toLowerCase().contains("duplicate")) {
                    return new Outcome(Result.NAME_TAKEN, null);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not create guild: " + exception.getMessage());
            return new Outcome(Result.INVALID, null);
        }
    }

    public Guild.Info guildById(int id) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM " + database.table("guilds") + " WHERE id=?")) {
            statement.setQueryTimeout(5);
            statement.setInt(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? guild(result) : null;
            }
        } catch (SQLException exception) {
            return null;
        }
    }

    public Result invite(int guildId, UUID target, UUID inviter) {
        try (Connection connection = database.open();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM " + database.table("guild_invites") + " WHERE uuid=?");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO " + database.table("guild_invites")
                             + " (guild_id,uuid,invited_by,expires) VALUES (?,?,?,?)")) {
            delete.setString(1, target.toString());
            delete.executeUpdate();
            insert.setInt(1, guildId);
            insert.setString(2, target.toString());
            insert.setString(3, inviter.toString());
            insert.setLong(4, System.currentTimeMillis() + settings.guildInviteExpireSeconds * 1000L);
            insert.executeUpdate();
            return Result.OK;
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not invite player: " + exception.getMessage());
            return Result.INVALID;
        }
    }

    public List<Guild.Invite> invites(UUID uuid) {
        List<Guild.Invite> invites = new ArrayList<>();
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT i.guild_id,g.name,i.uuid,i.invited_by,i.expires FROM "
                             + database.table("guild_invites") + " i JOIN " + database.table("guilds")
                             + " g ON g.id=i.guild_id WHERE i.uuid=? AND i.expires>?")) {
            statement.setQueryTimeout(5);
            statement.setString(1, uuid.toString());
            statement.setLong(2, System.currentTimeMillis());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    invites.add(new Guild.Invite(result.getInt(1), result.getString(2),
                            uuid(result.getString(3)), uuid(result.getString(4)), result.getLong(5)));
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not list invites: " + exception.getMessage());
        }
        return invites;
    }

    public Outcome accept(UUID player, String guildName) {
        Guild.Info guild = guildByNameOrTag(guildName);
        if (guild == null) return new Outcome(Result.NOT_FOUND, null);
        if (guildByPlayer(player) != null) return new Outcome(Result.ALREADY_IN_GUILD, guild);
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement invite = connection.prepareStatement(
                    "SELECT expires FROM " + database.table("guild_invites")
                            + " WHERE guild_id=? AND uuid=?");
                 PreparedStatement count = connection.prepareStatement(
                         "SELECT COUNT(*) FROM " + database.table("guild_members") + " WHERE guild_id=?")) {
                invite.setInt(1, guild.id());
                invite.setString(2, player.toString());
                long expires = 0;
                boolean invited = false;
                try (ResultSet result = invite.executeQuery()) {
                    if (result.next()) {
                        invited = true;
                        expires = result.getLong(1);
                    }
                }
                if (!invited && !guild.open()) {
                    connection.rollback();
                    return new Outcome(Result.NO_INVITE, guild);
                }
                if (invited && expires < System.currentTimeMillis()) {
                    connection.rollback();
                    return new Outcome(Result.NO_INVITE, guild);
                }
                count.setInt(1, guild.id());
                try (ResultSet result = count.executeQuery()) {
                    int members = result.next() ? result.getInt(1) : 0;
                    if (members >= guild.maxMembers()) {
                        connection.rollback();
                        return new Outcome(Result.FULL, guild);
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + database.table("guild_members")
                                + " (guild_id,uuid,role,joined_at,contribution) VALUES (?,?,'MEMBER',?,0)");
                     PreparedStatement remove = connection.prepareStatement(
                             "DELETE FROM " + database.table("guild_invites") + " WHERE uuid=?")) {
                    insert.setInt(1, guild.id());
                    insert.setString(2, player.toString());
                    insert.setLong(3, System.currentTimeMillis());
                    insert.executeUpdate();
                    remove.setString(1, player.toString());
                    remove.executeUpdate();
                }
                connection.commit();
                refreshTagCacheAsync();
                return new Outcome(Result.OK, guild);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not accept guild invite: " + exception.getMessage());
            return new Outcome(Result.INVALID, guild);
        }
    }

    public Result leave(UUID player) {
        Guild.Member member = member(player);
        if (member == null) return Result.NOT_IN_GUILD;
        if (member.role() == Guild.Role.LEADER) return Result.LEADER_CANNOT_LEAVE;
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM " + database.table("guild_members") + " WHERE uuid=?")) {
            statement.setString(1, player.toString());
            statement.executeUpdate();
            refreshTagCacheAsync();
            return Result.OK;
        } catch (SQLException exception) {
            return Result.INVALID;
        }
    }

    public Result removeMember(UUID target) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM " + database.table("guild_members") + " WHERE uuid=?")) {
            statement.setString(1, target.toString());
            statement.executeUpdate();
            refreshTagCacheAsync();
            return Result.OK;
        } catch (SQLException exception) {
            return Result.INVALID;
        }
    }

    public Result setRole(UUID target, Guild.Role role) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + database.table("guild_members") + " SET role=? WHERE uuid=?")) {
            statement.setString(1, role.name());
            statement.setString(2, target.toString());
            statement.executeUpdate();
            refreshTagCacheAsync();
            return Result.OK;
        } catch (SQLException exception) {
            return Result.INVALID;
        }
    }

    public Result disband(int guildId) {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement invites = connection.prepareStatement(
                    "DELETE FROM " + database.table("guild_invites") + " WHERE guild_id=?");
                 PreparedStatement members = connection.prepareStatement(
                         "DELETE FROM " + database.table("guild_members") + " WHERE guild_id=?");
                 PreparedStatement guild = connection.prepareStatement(
                         "DELETE FROM " + database.table("guilds") + " WHERE id=?")) {
                invites.setInt(1, guildId);
                invites.executeUpdate();
                members.setInt(1, guildId);
                members.executeUpdate();
                guild.setInt(1, guildId);
                guild.executeUpdate();
                connection.commit();
                refreshTagCacheAsync();
                return Result.OK;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            return Result.INVALID;
        }
    }

    public Result setDescription(int guildId, String description) {
        String clean = description == null ? "" : description.trim();
        if (clean.length() > 120) clean = clean.substring(0, 120);
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + database.table("guilds") + " SET description=? WHERE id=?")) {
            statement.setString(1, clean);
            statement.setInt(2, guildId);
            statement.executeUpdate();
            refreshTagCacheAsync();
            return Result.OK;
        } catch (SQLException exception) {
            return Result.INVALID;
        }
    }

    public Result setTag(int guildId, String rawTag) {
        String cleanTag = rawTag == null ? "" : rawTag.trim();
        String visible = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', cleanTag));
        if (!visible.matches("[A-Za-z0-9]{" + settings.guildTagMin + "," + settings.guildTagMax + "}")
                || cleanTag.length() > 32) return Result.INVALID;
        for (Guild.Info existing : allGuilds()) {
            if (existing.id() == guildId) continue;
            String existingTag = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', existing.tag()));
            if (existingTag.equalsIgnoreCase(visible)) return Result.NAME_TAKEN;
        }
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + database.table("guilds") + " SET tag=? WHERE id=?")) {
            statement.setString(1, cleanTag);
            statement.setInt(2, guildId);
            statement.executeUpdate();
            refreshTagCacheAsync();
            return Result.OK;
        } catch (SQLException exception) {
            return Result.INVALID;
        }
    }

    public Result setHome(int guildId, String world, double x, double y, double z, float yaw, float pitch) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + database.table("guilds") + " SET home_world=?,home_x=?,home_y=?,"
                             + "home_z=?,home_yaw=?,home_pitch=? WHERE id=?")) {
            statement.setString(1, world);
            statement.setDouble(2, x);
            statement.setDouble(3, y);
            statement.setDouble(4, z);
            statement.setFloat(5, yaw);
            statement.setFloat(6, pitch);
            statement.setInt(7, guildId);
            statement.executeUpdate();
            return Result.OK;
        } catch (SQLException exception) {
            return Result.INVALID;
        }
    }

    public double bank(int guildId) {
        Guild.Info guild = guildById(guildId);
        return guild == null ? 0 : guild.bank();
    }

    public boolean depositBank(int guildId, double amount) {
        if (amount <= 0) return false;
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + database.table("guilds") + " SET bank=bank+? WHERE id=?")) {
            statement.setDouble(1, amount);
            statement.setInt(2, guildId);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            return false;
        }
    }

    public boolean withdrawBank(int guildId, double amount) {
        if (amount <= 0) return false;
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + database.table("guilds") + " SET bank=bank-? WHERE id=? AND bank>=?")) {
            statement.setDouble(1, amount);
            statement.setInt(2, guildId);
            statement.setDouble(3, amount);
            return statement.executeUpdate() > 0;
        } catch (SQLException exception) {
            return false;
        }
    }

    public void setOpen(int guildId, boolean open) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + database.table("guilds") + " SET open=? WHERE id=?")) {
            statement.setBoolean(1, open);
            statement.setInt(2, guildId);
            statement.executeUpdate();
        } catch (SQLException ignored) {
        }
    }

    /**
     * Recalculates every guild's cached power from member stats and playtime.
     * Run asynchronously.
     */
    public void recalculatePowers() {
        String season = seasons.currentSeasonId();
        if (season == null) return;
        List<Guild.Info> guilds = allGuilds();
        if (guilds.isEmpty()) return;
        Map<String, Map<String, Long>> stats = statsByPlayer(season);
        Map<String, Long> playtime = playtimeByPlayer();
        for (Guild.Info guild : guilds) {
            double power = 0;
            List<Guild.Member> members = members(guild.id());
            for (Guild.Member member : members) {
                power += settings.guildPower.member();
                Map<String, Long> values = stats.getOrDefault(member.uuid().toString(), Map.of());
                power += values.getOrDefault("PVP_KILLS", 0L) * settings.guildPower.pvp();
                power += values.getOrDefault("BOSS_DAMAGE", 0L) * settings.guildPower.boss();
                power += values.getOrDefault("MOB_KILLS", 0L) * settings.guildPower.mob();
                power += values.getOrDefault("CROPS_BROKEN", 0L) * settings.guildPower.crop();
                power += values.getOrDefault("BLOCKS_BROKEN", 0L) * settings.guildPower.block();
                power += (playtime.getOrDefault(member.uuid().toString(), 0L) / 3600.0)
                        * settings.guildPower.playtime();
            }
            power += guild.bank() * settings.guildPower.bank();
            setPower(guild.id(), power);
        }
    }

    public List<Guild.Info> allGuilds() {
        List<Guild.Info> guilds = new ArrayList<>();
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM " + database.table("guilds"))) {
            statement.setQueryTimeout(8);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) guilds.add(guild(result));
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not list guilds: " + exception.getMessage());
        }
        return guilds;
    }

    private void setPower(int guildId, double power) {
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + database.table("guilds") + " SET power=? WHERE id=?")) {
            statement.setDouble(1, power);
            statement.setInt(2, guildId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().fine("Could not update guild power: " + exception.getMessage());
        }
    }

    private Map<String, Map<String, Long>> statsByPlayer(String season) {
        Map<String, Map<String, Long>> result = new HashMap<>();
        try (Connection connection = database.open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT uuid,category,value FROM " + database.table("stats") + " WHERE season_id=?")) {
            statement.setQueryTimeout(8);
            statement.setString(1, season);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.computeIfAbsent(rows.getString(1), ignored -> new HashMap<>())
                            .put(rows.getString(2), rows.getLong(3));
                }
            }
        } catch (SQLException exception) {
            plugin.getLogger().fine("Could not calculate guild stats: " + exception.getMessage());
        }
        return result;
    }

    private Map<String, Long> playtimeByPlayer() {
        Map<String, Long> result = new HashMap<>();
        if (!settings.playtime.complete()) return result;
        String table = Database.quote(settings.playtime.table().isBlank()
                ? "skyx_playtime_players" : settings.playtime.table());
        try (Connection connection = Database.open(settings.playtime);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT uuid,SUM(playtime) FROM " + table + " GROUP BY uuid")) {
            statement.setQueryTimeout(8);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.put(rows.getString(1), rows.getLong(2));
            }
        } catch (SQLException exception) {
            plugin.getLogger().fine("Could not read guild playtime: " + exception.getMessage());
        }
        return result;
    }

    private Guild.Info guild(ResultSet result) throws SQLException {
        return new Guild.Info(
                result.getInt("id"),
                result.getString("name"),
                result.getString("tag"),
                uuid(result.getString("leader_uuid")),
                result.getString("description"),
                result.getLong("created_at"),
                result.getDouble("bank"),
                result.getDouble("power"),
                result.getBoolean("open"),
                result.getInt("max_members"),
                result.getString("home_world"),
                nullableDouble(result, "home_x"),
                nullableDouble(result, "home_y"),
                nullableDouble(result, "home_z"),
                nullableFloat(result, "home_yaw"),
                nullableFloat(result, "home_pitch"));
    }

    private Guild.Member member(ResultSet result) throws SQLException {
        return new Guild.Member(result.getInt("guild_id"), uuid(result.getString("uuid")),
                result.getString("name"), Guild.Role.parse(result.getString("role")),
                result.getLong("joined_at"), result.getDouble("contribution"));
    }

    private Double nullableDouble(ResultSet result, String column) throws SQLException {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }

    private Float nullableFloat(ResultSet result, String column) throws SQLException {
        float value = result.getFloat(column);
        return result.wasNull() ? null : value;
    }

    private UUID uuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public PlayerRegistry players() {
        return players;
    }

    public CoreSettings settings() {
        return settings;
    }
}
