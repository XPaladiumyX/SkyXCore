package net.skyxnetwork.core.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.skyxnetwork.core.SkyXCore;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Placeholders used by LPC, TAB and other display plugins.
 * Only cached values are read here; no synchronous SQL is performed.
 */
public final class SkyXCoreExpansion extends PlaceholderExpansion {

    private final SkyXCore plugin;

    public SkyXCoreExpansion(SkyXCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "skyxcore";
    }

    @Override
    public @NotNull String getAuthor() {
        return "SkyXNetwork";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null) return null;
        String key = params.toLowerCase(Locale.ROOT);
        if (key.equals("season")) return plugin.getSeasonService().currentSeasonId() == null
                ? "" : plugin.getSeasonService().currentSeasonId();

        if (player == null) return "";
        if (key.equals("guild_tag") || key.equals("guildtag")) {
            return plugin.getGuildService().tag(player.getUniqueId());
        }
        if (key.equals("guild_tag_plain") || key.equals("guildtagplain")) {
            return plugin.getGuildService().plainTag(player.getUniqueId());
        }
        if (key.equals("guild_tag_mini") || key.equals("guildtagmini")) {
            return plugin.getGuildService().tagMini(player.getUniqueId());
        }
        if (key.equals("guild_name") || key.equals("guildname")) {
            return plugin.getGuildService().guildName(player.getUniqueId());
        }
        if (key.equals("guild_power") || key.equals("guildpower")) {
            return plugin.getSettings().number(plugin.getGuildService().guildPower(player.getUniqueId()));
        }
        if (key.equals("pvp_kills") || key.equals("pvp")) {
            return Long.toString(plugin.getStatsService().cachedValue(player.getUniqueId(), "PVP_KILLS"));
        }
        if (key.equals("mob_kills") || key.equals("mobs")) {
            return Long.toString(plugin.getStatsService().cachedValue(player.getUniqueId(), "MOB_KILLS"));
        }
        if (key.equals("crops_broken") || key.equals("crops")) {
            return Long.toString(plugin.getStatsService().cachedValue(player.getUniqueId(), "CROPS_BROKEN"));
        }
        if (key.equals("blocks_broken") || key.equals("blocks")) {
            return Long.toString(plugin.getStatsService().cachedValue(player.getUniqueId(), "BLOCKS_BROKEN"));
        }
        if (key.equals("boss_damage") || key.equals("boss")) {
            return Long.toString(plugin.getStatsService().cachedValue(player.getUniqueId(), "BOSS_DAMAGE"));
        }
        return null;
    }
}
