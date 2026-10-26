package net.skyxnetwork.core;

import net.skyxnetwork.core.core.CoreSettings;
import net.skyxnetwork.core.core.Database;
import net.skyxnetwork.core.core.PlayerRegistry;
import net.skyxnetwork.core.guild.EconomyHook;
import net.skyxnetwork.core.guild.GuildCommand;
import net.skyxnetwork.core.guild.GuildService;
import net.skyxnetwork.core.leaderboard.LeaderboardCommand;
import net.skyxnetwork.core.leaderboard.LeaderboardGui;
import net.skyxnetwork.core.leaderboard.LeaderboardRepository;
import net.skyxnetwork.core.leaderboard.LeaderboardService;
import net.skyxnetwork.core.season.SeasonCommand;
import net.skyxnetwork.core.season.SeasonService;
import net.skyxnetwork.core.stats.StatsService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;

public final class SkyXCore extends JavaPlugin {

    private volatile CoreSettings settings;
    private Database database;
    private PlayerRegistry playerRegistry;
    private SeasonService seasonService;
    private StatsService statsService;
    private LeaderboardRepository leaderboardRepository;
    private LeaderboardService leaderboardService;
    private LeaderboardGui leaderboardGui;
    private GuildService guildService;
    private EconomyHook economy;
    private BukkitTask powerTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = CoreSettings.load(this);
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException exception) {
            getLogger().severe("MySQL driver not found. Disabling SkyXCore.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        database = new Database(this, settings);
        try {
            database.ensureTables();
        } catch (SQLException exception) {
            getLogger().severe("Could not initialize SkyXCore tables: " + exception.getMessage());
        }

        playerRegistry = new PlayerRegistry(this, database);
        playerRegistry.start();
        seasonService = new SeasonService(this, database, settings);
        seasonService.start();
        statsService = new StatsService(this, database, playerRegistry, seasonService, settings);
        statsService.start();
        leaderboardRepository = new LeaderboardRepository(this, database, playerRegistry, settings);
        leaderboardService = new LeaderboardService(this, leaderboardRepository, settings);
        leaderboardGui = new LeaderboardGui(this, leaderboardService, playerRegistry, settings);
        getServer().getPluginManager().registerEvents(leaderboardGui, this);
        guildService = new GuildService(this, database, playerRegistry, seasonService, settings);
        economy = new EconomyHook();

        registerCommands();
        startPowerTask();

        getLogger().info("SkyXCore enabled (server=" + settings.serverId
                + ", database=" + settings.database.name()
                + ", categories=" + settings.categories.size() + ").");
    }

    @Override
    public void onDisable() {
        if (powerTask != null) powerTask.cancel();
        if (statsService != null) statsService.stop();
        getLogger().info("SkyXCore disabled.");
    }

    private void registerCommands() {
        if (getCommand("leaderboard") != null) {
            getCommand("leaderboard").setExecutor(new LeaderboardCommand(this));
        }
        if (getCommand("guild") != null) {
            GuildCommand command = new GuildCommand(this);
            getCommand("guild").setExecutor(command);
            getCommand("guild").setTabCompleter(command);
        }
        if (getCommand("skyxcore") != null) {
            SeasonCommand command = new SeasonCommand(this);
            getCommand("skyxcore").setExecutor(command);
            getCommand("skyxcore").setTabCompleter(command);
        }
    }

    private void startPowerTask() {
        if (powerTask != null) powerTask.cancel();
        powerTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this,
                () -> guildService.recalculatePowers(), 200L, 20L * 300L);
    }

    public void reloadCore() {
        reloadConfig();
        CoreSettings fresh = CoreSettings.load(this);
        settings = fresh;
        statsService.updateSettings(fresh);
        statsService.scheduleFlush();
        leaderboardService.updateSettings(fresh);
        leaderboardGui.updateSettings(fresh);
        guildService.updateSettings(fresh);
        seasonService.updateSettings(fresh);
        getLogger().info("SkyXCore configuration reloaded.");
    }

    public String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    public String message(String key, String... replacements) {
        String raw = settings.message(key);
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            raw = raw.replace(replacements[index], replacements[index + 1] == null ? "" : replacements[index + 1]);
        }
        return color(raw);
    }

    public String prefixed(String key, String... replacements) {
        return color(settings.message("prefix")) + message(key, replacements);
    }

    public CoreSettings getSettings() {
        return settings;
    }

    public Database getDatabase() {
        return database;
    }

    public PlayerRegistry getPlayerRegistry() {
        return playerRegistry;
    }

    public SeasonService getSeasonService() {
        return seasonService;
    }

    public StatsService getStatsService() {
        return statsService;
    }

    public LeaderboardRepository getLeaderboardRepository() {
        return leaderboardRepository;
    }

    public LeaderboardService getLeaderboardService() {
        return leaderboardService;
    }

    public LeaderboardGui getLeaderboardGui() {
        return leaderboardGui;
    }

    public GuildService getGuildService() {
        return guildService;
    }

    public EconomyHook getEconomy() {
        return economy;
    }

    public void send(CommandSender sender, String key, String... replacements) {
        sender.sendMessage(prefixed(key, replacements));
    }
}
