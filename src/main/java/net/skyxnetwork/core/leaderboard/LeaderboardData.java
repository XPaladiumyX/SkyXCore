package net.skyxnetwork.core.leaderboard;

import java.util.List;
import java.util.UUID;

public final class LeaderboardData {

    public record Entry(UUID uuid, String name, double value, int rank,
                        boolean guild, int guildId) {
    }

    public record Page(List<Entry> entries, int total, int page, int pageSize,
                       Entry viewer) {
        public int pages() {
            return Math.max(1, (int) Math.ceil(total / (double) Math.max(1, pageSize)));
        }
    }

    private LeaderboardData() {
    }
}
