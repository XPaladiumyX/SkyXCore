package net.skyxnetwork.core.season;

import net.skyxnetwork.core.SkyXCore;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SeasonCommand implements CommandExecutor, TabCompleter {

    private final SkyXCore plugin;
    private final Map<String, PendingAction> pending = new ConcurrentHashMap<>();

    private record PendingAction(String action, String argument, long expires) {
    }

    public SeasonCommand(SkyXCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(plugin.message("admin-usage"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadCore();
                sender.sendMessage(plugin.prefixed("reloaded"));
                return true;
            }
            case "status" -> {
                sender.sendMessage(plugin.prefixed("status",
                        "%server%", plugin.getSettings().serverId,
                        "%db%", plugin.getDatabase().ping() ? "connected" : "unavailable",
                        "%stats%", plugin.getSettings().statsEnabled ? "enabled" : "disabled",
                        "%guilds%", plugin.getSettings().guildsEnabled ? "enabled" : "disabled"));
                sender.sendMessage(plugin.prefixed("season-usage"));
                return true;
            }
            case "season" -> {
                season(sender, args);
                return true;
            }
            default -> {
                sender.sendMessage(plugin.message("admin-usage"));
                return true;
            }
        }
    }

    private void season(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.message("season-usage"));
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("status")) {
            String current = plugin.getSeasonService().currentSeasonId();
            if (current == null) {
                sender.sendMessage(plugin.prefixed("season-none"));
            } else {
                String ends = plugin.getSeasonService().currentEndsAt() <= 0 ? "unknown"
                        : java.time.Instant.ofEpochMilli(plugin.getSeasonService().currentEndsAt()).toString();
                sender.sendMessage(plugin.prefixed("status",
                        "%server%", plugin.getSettings().serverId,
                        "%db%", plugin.getDatabase().ping() ? "connected" : "unavailable",
                        "%stats%", "season " + current,
                        "%guilds%", "ends " + ends));
            }
            return;
        }
        if (action.equals("start")) {
            if (!sender.hasPermission("skyxcore.admin.season")) {
                sender.sendMessage(plugin.message("no-permission"));
                return;
            }
            String id = args.length >= 3 ? args[2] : plugin.getSettings().seasonCurrentId;
            if (args.length >= 4 && args[3].equalsIgnoreCase("confirm")) {
                PendingAction pendingAction = pending.remove(key(sender));
                if (pendingAction == null || pendingAction.expires < System.currentTimeMillis()
                        || !pendingAction.action.equals("start")) {
                    sender.sendMessage(plugin.message("season-usage"));
                    return;
                }
                if (plugin.getSeasonService().startSeason(pendingAction.argument)) {
                    String ends = plugin.getSeasonService().currentEndsAt() <= 0 ? "unknown"
                            : java.time.Instant.ofEpochMilli(plugin.getSeasonService().currentEndsAt()).toString();
                    sender.sendMessage(plugin.prefixed("season-started",
                            "%season%", pendingAction.argument.toUpperCase(Locale.ROOT),
                            "%ends%", ends));
                } else {
                    sender.sendMessage(plugin.message("season-usage"));
                }
                return;
            }
            pending.put(key(sender), new PendingAction("start", id, System.currentTimeMillis() + 30_000L));
            sender.sendMessage(plugin.prefixed("season-confirm", "%action%", "start"));
            return;
        }
        if (action.equals("end")) {
            if (!sender.hasPermission("skyxcore.admin.season")) {
                sender.sendMessage(plugin.message("no-permission"));
                return;
            }
            if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                pending.put(key(sender), new PendingAction("end", "", System.currentTimeMillis() + 30_000L));
                sender.sendMessage(plugin.prefixed("season-confirm", "%action%", "end"));
                return;
            }
            PendingAction pendingAction = pending.remove(key(sender));
            if (pendingAction == null || pendingAction.expires < System.currentTimeMillis()
                    || !pendingAction.action.equals("end")) {
                sender.sendMessage(plugin.message("season-usage"));
                return;
            }
            String season = plugin.getSeasonService().currentSeasonId();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                boolean ended = plugin.getSeasonService().finalizeSeason(plugin.getLeaderboardRepository());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ended) sender.sendMessage(plugin.prefixed("season-ended", "%season%", season));
                    else sender.sendMessage(plugin.prefixed("season-none"));
                });
            });
            return;
        }
        sender.sendMessage(plugin.message("season-usage"));
    }

    private String key(CommandSender sender) {
        return sender instanceof org.bukkit.entity.Player player
                ? player.getUniqueId().toString() : "console";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            String prefix = args[0].toLowerCase();
            for (String option : List.of("reload", "status", "season")) {
                if (option.startsWith(prefix)) result.add(option);
            }
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("season")) {
            return List.of("status", "start", "end");
        }
        return List.of();
    }
}
