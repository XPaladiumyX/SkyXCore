package net.skyxnetwork.core.core;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CoreSettings {

    public enum Source { MONEY, PLAYTIME, JOBS, STATS, GUILDS }

    public record DatabaseConfig(boolean enabled, String host, int port, String name,
                                 String user, String password, String tablePrefix,
                                 String table, String usersTable, String pointsTable,
                                 int connectTimeoutMs, int socketTimeoutMs) {
        public boolean complete() {
            return enabled && host != null && !host.isBlank() && name != null && !name.isBlank()
                    && user != null && !user.isBlank();
        }
    }

    public record Category(String id, String display, Material icon, Source source,
                           String stat, String format) {
    }

    public record GuildPowerWeights(double member, double pvp, double boss, double mob,
                                    double crop, double block, double playtime, double bank) {
    }

    public final String serverId;
    public final DatabaseConfig database;
    public final DatabaseConfig money;
    public final DatabaseConfig playtime;
    public final DatabaseConfig jobs;

    public final boolean statsEnabled;
    public final int statsFlushSeconds;
    public final boolean statsIgnoreCreative;
    public final Set<String> ignoredWorlds;
    public final int pvpKillCooldownSeconds;
    public final boolean pvpBlockSameIp;
    public final boolean ignoreSpawnerMobs;
    public final Set<Material> crops;
    public final Set<String> bosses;

    public final boolean leaderboardsEnabled;
    public final int pageSize;
    public final int leaderboardCommandCooldownSeconds;
    public final int leaderboardGuiClickDelayMs;

    public final boolean playerHologramsEnabled;
    public final double playerHologramHeight;
    public final int playerHologramUpdateTicks;
    public final int playerHologramMoveTicks;
    public final String playerHologramFormat;
    public final Set<String> playerHologramIgnoredWorlds;
    public final List<Category> categories;
    public final Map<String, Category> categoryById;

    public final boolean guildsEnabled;
    public final double guildCreateCost;
    public final int guildTagMin, guildTagMax, guildNameMin, guildNameMax;
    public final int guildMaxMembers;
    public final int guildInviteExpireSeconds;
    public final String guildChatFormat;
    public final boolean guildHomeEnabled;
    public final int guildHomeWarmupSeconds;
    public final int guildHomeCooldownSeconds;
    public final boolean guildBankEnabled;
    public final GuildPowerWeights guildPower;

    public final boolean seasonEnabled;
    public final String seasonCurrentId;
    public final int seasonLengthDays;
    public final boolean seasonAutoStart;
    public final Map<String, Map<Integer, List<String>>> seasonRewards;

    public final String guiTitleCategories;
    public final String guiTitleCategory;
    public final Material guiBorder;
    public final Material guiFiller;
    public final Material guiPrevious;
    public final Material guiNext;
    public final Material guiClose;

    public final Map<String, String> messages;

    private CoreSettings(FileConfiguration config) {
        serverId = config.getString("network.server-id", "survival");
        database = database(config, "database", "skyxcore");
        money = database(config, "sources.money", null);
        playtime = database(config, "sources.playtime", null);
        jobs = database(config, "sources.jobs", null);

        statsEnabled = config.getBoolean("stats.enabled", true);
        statsFlushSeconds = clamp(config.getInt("stats.flush-interval-seconds", 15), 5, 300);
        statsIgnoreCreative = config.getBoolean("stats.ignore-creative", true);
        ignoredWorlds = Set.copyOf(config.getStringList("stats.ignored-worlds"));
        pvpKillCooldownSeconds = clamp(config.getInt("stats.anti-farm.pvp-min-kill-interval-seconds", 300), 0, 86400);
        pvpBlockSameIp = config.getBoolean("stats.anti-farm.pvp-block-same-ip", true);
        ignoreSpawnerMobs = config.getBoolean("stats.anti-farm.ignore-spawner-mobs", true);

        Set<Material> cropMaterials = new LinkedHashSet<>();
        for (String name : config.getStringList("stats.crops")) {
            Material material = Material.matchMaterial(name);
            if (material != null) cropMaterials.add(material);
        }
        crops = Collections.unmodifiableSet(cropMaterials);
        bosses = Set.copyOf(config.getStringList("stats.bosses"));

        leaderboardsEnabled = config.getBoolean("leaderboards.enabled", true);
        pageSize = clamp(config.getInt("leaderboards.page-size", 10), 1, 10);
        leaderboardCommandCooldownSeconds = clamp(config.getInt("leaderboards.command-cooldown-seconds", 2), 0, 60);
        leaderboardGuiClickDelayMs = clamp(config.getInt("leaderboards.gui-click-delay-ms", 300), 0, 5000);

        playerHologramsEnabled = config.getBoolean("holograms.enabled", true);
        playerHologramHeight = Math.max(-2.0, Math.min(5.0, config.getDouble("holograms.height", 0.30)));
        playerHologramUpdateTicks = clamp(config.getInt("holograms.update-interval-ticks", 20), 5, 200);
        playerHologramMoveTicks = clamp(config.getInt("holograms.move-interval-ticks", 5), 1, 40);
        playerHologramFormat = config.getString("holograms.format",
                "&6%lighteconomy_money%₪ &8| &c%skyxcore_pvp_kills% PvP");
        playerHologramIgnoredWorlds = Set.copyOf(config.getStringList("holograms.ignored-worlds"));
        List<Category> parsedCategories = new ArrayList<>();
        ConfigurationSection categoriesSection = config.getConfigurationSection("leaderboards.categories");
        if (categoriesSection != null) {
            for (String id : categoriesSection.getKeys(false)) {
                ConfigurationSection section = categoriesSection.getConfigurationSection(id);
                if (section == null) continue;
                Source source;
                try {
                    source = Source.valueOf(section.getString("source", "STATS").toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    continue;
                }
                Material icon = Material.matchMaterial(section.getString("icon", "PAPER"));
                if (icon == null) icon = Material.PAPER;
                parsedCategories.add(new Category(
                        id.toLowerCase(Locale.ROOT),
                        section.getString("display", id),
                        icon, source,
                        section.getString("stat", "").toUpperCase(Locale.ROOT),
                        section.getString("format", "NUMBER").toUpperCase(Locale.ROOT)));
            }
        }
        categories = Collections.unmodifiableList(parsedCategories);
        Map<String, Category> byId = new LinkedHashMap<>();
        for (Category category : parsedCategories) byId.put(category.id(), category);
        categoryById = Collections.unmodifiableMap(byId);

        guildsEnabled = config.getBoolean("guilds.enabled", true);
        guildCreateCost = Math.max(0, config.getDouble("guilds.create-cost", 25000));
        guildTagMin = clamp(config.getInt("guilds.tag-min-length", 3), 2, 16);
        guildTagMax = Math.max(guildTagMin, config.getInt("guilds.tag-max-length", 8));
        guildNameMin = clamp(config.getInt("guilds.name-min-length", 3), 2, 32);
        guildNameMax = Math.max(guildNameMin, config.getInt("guilds.name-max-length", 24));
        guildMaxMembers = clamp(config.getInt("guilds.default-max-members", 16), 2, 100);
        guildInviteExpireSeconds = clamp(config.getInt("guilds.invite-expire-seconds", 120), 10, 3600);
        guildChatFormat = config.getString("guilds.chat-format", "&8[&d%tag%&8] &f%player%&7: &f%message%");
        guildHomeEnabled = config.getBoolean("guilds.home.enabled", true);
        guildHomeWarmupSeconds = clamp(config.getInt("guilds.home.warmup-seconds", 5), 0, 60);
        guildHomeCooldownSeconds = clamp(config.getInt("guilds.home.cooldown-seconds", 120), 0, 3600);
        guildBankEnabled = config.getBoolean("guilds.bank.enabled", true);
        guildPower = new GuildPowerWeights(
                config.getDouble("guilds.power.member-weight", 100),
                config.getDouble("guilds.power.pvp-weight", 10),
                config.getDouble("guilds.power.boss-weight", 0.01),
                config.getDouble("guilds.power.mob-weight", 0.2),
                config.getDouble("guilds.power.crop-weight", 0.05),
                config.getDouble("guilds.power.block-weight", 0.01),
                config.getDouble("guilds.power.playtime-weight", 2),
                config.getDouble("guilds.power.bank-weight", 0.001));

        seasonEnabled = config.getBoolean("season.enabled", true);
        seasonCurrentId = config.getString("season.current-id", "S6");
        seasonLengthDays = clamp(config.getInt("season.length-days", 180), 1, 3650);
        seasonAutoStart = config.getBoolean("season.auto-start", true);
        Map<String, Map<Integer, List<String>>> parsedRewards = new LinkedHashMap<>();
        ConfigurationSection rewardsSection = config.getConfigurationSection("season.rewards");
        if (rewardsSection != null) {
            for (String category : rewardsSection.getKeys(false)) {
                ConfigurationSection categorySection = rewardsSection.getConfigurationSection(category);
                if (categorySection == null) continue;
                Map<Integer, List<String>> ranks = new LinkedHashMap<>();
                for (String rankKey : categorySection.getKeys(false)) {
                    try {
                        int rank = Integer.parseInt(rankKey);
                        List<String> commands = categorySection.getStringList(rankKey);
                        if (!commands.isEmpty()) ranks.put(rank, List.copyOf(commands));
                    } catch (NumberFormatException ignored) {
                        // Ignore malformed rank keys.
                    }
                }
                parsedRewards.put(category.toLowerCase(Locale.ROOT), Collections.unmodifiableMap(ranks));
            }
        }
        seasonRewards = Collections.unmodifiableMap(parsedRewards);

        guiTitleCategories = config.getString("gui.title-categories", "&8SkyXCore &7» &fLeaderboards");
        guiTitleCategory = config.getString("gui.title-category", "&8%category% &7» &fTop");
        guiBorder = material(config.getString("gui.border", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        guiFiller = material(config.getString("gui.filler", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
        guiPrevious = material(config.getString("gui.previous", "ARROW"), Material.ARROW);
        guiNext = material(config.getString("gui.next", "ARROW"), Material.ARROW);
        guiClose = material(config.getString("gui.close", "BARRIER"), Material.BARRIER);

        Map<String, String> parsedMessages = new LinkedHashMap<>();
        ConfigurationSection messageSection = config.getConfigurationSection("messages");
        if (messageSection != null) {
            for (String key : messageSection.getKeys(false)) {
                String value = messageSection.getString(key);
                if (value != null) parsedMessages.put(key, value);
            }
        }
        messages = Collections.unmodifiableMap(parsedMessages);
    }

    public static CoreSettings load(Plugin plugin) {
        return new CoreSettings(plugin.getConfig());
    }

    public String message(String key) {
        return messages.getOrDefault(key, key);
    }

    public String message(String key, String... replacements) {
        String raw = message(key);
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            raw = raw.replace(replacements[index], replacements[index + 1] == null ? "" : replacements[index + 1]);
        }
        return raw;
    }

    public String format(double value, String format) {
        return switch (format == null ? "NUMBER" : format.toUpperCase(Locale.ROOT)) {
            case "MONEY" -> money(value);
            case "TIME" -> time((long) value);
            default -> number(value);
        };
    }

    public String money(double value) {
        DecimalFormat format = new DecimalFormat("#,###.##", DecimalFormatSymbols.getInstance(Locale.US));
        return format.format(value) + "₪";
    }

    public String number(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return new DecimalFormat("#,###", DecimalFormatSymbols.getInstance(Locale.US))
                    .format(Math.round(value));
        }
        return new DecimalFormat("#,###.##", DecimalFormatSymbols.getInstance(Locale.US)).format(value);
    }

    public String time(long seconds) {
        if (seconds <= 0) return "0m";
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        List<String> parts = new ArrayList<>(3);
        if (days > 0) parts.add(days + "d");
        if (hours > 0 || days > 0) parts.add(hours + "h");
        if (minutes > 0 || parts.isEmpty()) parts.add(minutes + "m");
        return String.join(" ", parts);
    }

    private static DatabaseConfig database(FileConfiguration config, String path, String defaultPrefix) {
        boolean enabled = config.getBoolean(path + ".enabled", true);
        String host = config.getString(path + ".host", "127.0.0.1");
        int port = clamp(config.getInt(path + ".port", 3306), 1, 65535);
        String name = config.getString(path + ".name", config.getString(path + ".database", ""));
        String user = config.getString(path + ".user", "");
        String password = config.getString(path + ".password", "");
        String prefix = config.getString(path + ".table-prefix", defaultPrefix == null ? "" : defaultPrefix);
        String table = config.getString(path + ".table", "");
        String usersTable = config.getString(path + ".users-table", "jobs_users");
        String pointsTable = config.getString(path + ".points-table", "jobs_points");
        int connect = clamp(config.getInt(path + ".connection-timeout-ms", 5000), 1000, 60000);
        int socket = clamp(config.getInt(path + ".socket-timeout-ms", 8000), 1000, 120000);
        return new DatabaseConfig(enabled, host, port, name, user, password,
                prefix == null ? "" : prefix, table == null ? "" : table,
                usersTable, pointsTable, connect, socket);
    }

    private static Material material(String name, Material fallback) {
        Material material = Material.matchMaterial(name == null ? "" : name);
        return material == null ? fallback : material;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
