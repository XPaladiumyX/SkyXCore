package net.skyxnetwork.core.leaderboard;

import net.skyxnetwork.core.SkyXCore;
import net.skyxnetwork.core.core.CoreSettings;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class LeaderboardCommand implements CommandExecutor, TabCompleter {

    private final SkyXCore plugin;

    public LeaderboardCommand(SkyXCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("player-only"));
            return true;
        }
        if (!plugin.getSettings().leaderboardsEnabled) {
            player.sendMessage(plugin.prefixed("leaderboard-empty"));
            return true;
        }
        if (args.length == 0) {
            plugin.getLeaderboardGui().openCategories(player);
            return true;
        }
        CoreSettings.Category category = plugin.getSettings().categoryById.get(args[0].toLowerCase());
        if (category == null) {
            plugin.getLeaderboardGui().openCategories(player);
            return true;
        }
        int page = 1;
        if (args.length >= 2) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ignored) {
                page = 1;
            }
        }
        plugin.getLeaderboardGui().openCategory(player, category, page - 1);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> result = new ArrayList<>();
            for (CoreSettings.Category category : plugin.getSettings().categories) {
                if (category.id().startsWith(prefix)) result.add(category.id());
            }
            return result;
        }
        return List.of();
    }
}
