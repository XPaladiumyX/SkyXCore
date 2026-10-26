# SkyXCore

Competitive core for **SkyXNetwork**: player statistics, leaderboards, guilds and seasons.

SkyXCore is designed for **Paper 1.21+**, including the current **1.21.8** and **26.1.2** servers. It uses only stable Paper/Bukkit APIs and no NMS, so it can be adapted to newer releases. Java 21 is required.

## Modules

### Leaderboards
- `/leaderboard` / `/leadb` opens a category menu.
- Categories are configurable and currently include Money, Playtime, Guilds, PvP Kills, Crops Broken, Mob Kills, Blocks Broken, Boss Damage and Job Points.
- Categories are centered on a 3x3 menu; top pages use two centered rows of five heads, a back button and unlimited page navigation.
- `/leadb` has a 2-second command cooldown and a 300 ms GUI click throttle to prevent query flooding.
- The viewer's own rank is shown in the header and as a grey head when it is outside the current page.
- Money, Playtime and Job Points are read directly from their existing MySQL databases.
- PvP kills, mob kills, crops, blocks and boss damage are tracked by SkyXCore from Bukkit events.

### Statistics
- Async, batched MySQL writes (no per-event database calls).
- Anti-farm options: same-IP PvP blocking, per-victim cooldown, ignored worlds, spawner-mob filtering.
- Boss damage is tracked for configurable entity types (Ender Dragon, Wither, Warden and future custom bosses).
- Season-scoped statistics.

### Guilds
- Create, invite, accept, leave, kick, promote/demote, disband.
- Guild chat, home, bank and roles.
- Power calculation with configurable weights for members, PvP, bosses, mobs, crops, blocks, playtime and bank.
- Top guilds category in the leaderboard GUI.
- Player-friendly guild GUI (`/guild`) with members, invites, bank, home, settings, tag colors, top and leave/disband actions.
- Guild tag prefixes in chat and TAB; legacy color codes are supported in tags.
- A `?` guide in the guild GUI explains every power source and how to increase power.
- Two passenger TextDisplays show Money and PvP kills above the in-game nametag. Java viewers see a display mounted directly on the player; Bedrock viewers see a display mounted on an invisible marker armor stand. The Bedrock marker isolates Geyser from player head rotation, and each platform has its own height setting.
- Guilds are shared across Survival and Farmlands through MySQL.

### Seasons
- Configurable season length (currently 6 months).
- `/skyxcore season start` and `/skyxcore season end` with confirmation.
- Top 5 per category is captured into `skyxcore_season_results`.
- Configurable reward commands per category/rank are queued in `skyxcore_season_rewards` and delivered when the player joins.
- Stats are scoped by season; starting a season clears that season's old stats/results/rewards. It does **not** reset economy, playtime, Jobs or worlds — those must be reset by the existing season/reset scripts. For a normal season change, run `/skyxcore season end confirm` first (captures top 5 and queues rewards), reset the other systems separately, then run `/skyxcore season start <new-id> confirm`.

### Hot configuration
`/skyxcore reload` reloads categories, messages, anti-farm rules, power weights, rewards and module switches without restarting the server. Database connection changes still require a restart.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/leaderboard [category] [page]` | `skyxcore.use` | Open leaderboards. Aliases: `/leadb`, `/lb`, `/tops`. |
| `/guild` | `skyxcore.guild` | Open the guild GUI. Aliases: `/guilds`, `/g`. |
| `/guild ...` | `skyxcore.guild` | Text command fallbacks for creation, invites, bank, etc. |
| `/guild chat <message>` | `skyxcore.guild` | Guild chat. |
| `/guild bank balance|deposit|withdraw` | `skyxcore.guild` | Guild bank. |
| `/skyxcore reload` | `skyxcore.admin` | Reload config. |
| `/skyxcore status` | `skyxcore.admin` | Database/module status. |
| `/skyxcore season start <id> confirm` | `skyxcore.admin.season` | Start a season. |
| `/skyxcore season end confirm` | `skyxcore.admin.season` | End the season and queue top 5 rewards. |

## Display integration

- `/leadb` has a configurable command cooldown (2 seconds by default; admins bypass it).
- The Survival sidebar is a compact 10-line layout using a new animated wave divider.
- The in-game per-player line is the only above-nametag stats display; TAB handles guild tags in the list/nametag. Both instances are passengers, so they follow the player with no movement task, and Java/Bedrock use separate height settings.
- LPC reads `%skyxcore_guild_tag%`/`%skyxcore_guild_tag_mini%` for chat.
- TAB reads `%skyxcore_guild_tag%` for the tablist prefix and nametag prefix.
- Available SkyXCore PAPI placeholders: `%skyxcore_guild_tag%`, `_plain`, `_mini`, `_name`, `_power`, `%skyxcore_pvp_kills%`, `%skyxcore_mob_kills%`, `%skyxcore_crops_broken%`, `%skyxcore_blocks_broken%`, `%skyxcore_boss_damage%` and `%skyxcore_season%`.
- The above-head line is configured under `holograms:` (`height` for Java, `bedrock-height` for Geyser/Bedrock) and can be disabled per world.

## Configuration

The repository `config.yml` is a **template**. Never commit real database credentials. On each server:

- Keep the same `database.name` and `database.table-prefix` for Survival and Farmlands.
- Set a unique `network.server-id`.
- Fill the own database and the read-only `sources.money`, `sources.playtime` and `sources.jobs` sections.
- The default deployment uses the existing `s29_playtime` database with the `skyxcore_` prefix, so no new database account is required.

The plugin creates these tables automatically:

`skyxcore_players`, `skyxcore_stats`, `skyxcore_seasons`, `skyxcore_guilds`, `skyxcore_guild_members`, `skyxcore_guild_invites`, `skyxcore_season_results`, `skyxcore_season_rewards`.

## Architecture notes

- MySQL connector is loaded through the Paper `libraries` section.
- Leaderboard queries and guild operations are executed asynchronously; the main thread only renders and opens inventories.
- Stats are accumulated in memory and flushed in batches.
- No blocking Mojang name lookups are used on the main thread.
- Guild bank and entry costs use Vault when available.
- Java and Bedrock/Floodgate players use the same UUID flow.

## Build

```bash
mvn clean package
```

The jar is written to `target/SkyXCore-0.1.0.jar`.

## Current scope

This is the first functional core. Future additions can be hot-loaded as modules or new configurable categories:

- More leaderboard categories.
- Guild upgrades, wars/territories, alliances and seasons.
- PlaceholderAPI top placeholders.
- Season auto-end scheduler.
- More custom boss types.

## Repository hygiene

Never commit:

- real `config.yml` credentials;
- server logs or world data;
- generated `target/` artifacts.
