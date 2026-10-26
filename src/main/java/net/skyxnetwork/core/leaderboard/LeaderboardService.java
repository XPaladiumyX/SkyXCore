package net.skyxnetwork.core.leaderboard;

import net.skyxnetwork.core.SkyXCore;
import net.skyxnetwork.core.core.CoreSettings;
import org.bukkit.Bukkit;

import java.util.UUID;
import java.util.function.Consumer;

/** Runs blocking leaderboard queries off the server thread and returns on it. */
public final class LeaderboardService {

    private final SkyXCore plugin;
    private final LeaderboardRepository repository;
    private volatile CoreSettings settings;

    public LeaderboardService(SkyXCore plugin, LeaderboardRepository repository, CoreSettings settings) {
        this.plugin = plugin;
        this.repository = repository;
        this.settings = settings;
    }

    public void updateSettings(CoreSettings settings) {
        this.settings = settings;
        repository.updateSettings(settings);
    }

    public void fetch(CoreSettings.Category category, int page, UUID viewer,
                      Consumer<LeaderboardData.Page> callback) {
        int size = settings.pageSize;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            LeaderboardData.Page result = repository.fetch(category, page, size, viewer);
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
        });
    }
}
