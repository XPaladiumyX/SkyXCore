# SkyXCore

Competitive core for **SkyXNetwork**: player statistics, leaderboards, guilds and seasons.

SkyXCore is designed for **Paper 1.21+**, including the current **1.21.8** and **26.1.2** servers. It uses only stable Paper/Bukkit APIs and no NMS, so it can be adapted to newer releases. Java 21 is required.

## Modules

### Leaderboards
- `/leaderboard` / `/leadb` opens a category menu.
- Categories are configurable and currently include Money, Playtime, Guilds, PvP Kills, Crops Broken, Mob Kills, Blocks Broken, Boss Damage and Job Points.
- Every category uses paginated player heads (or guild banners), with the top 10 visible per page by default and unlimited page navigation.
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
- Guilds are shared across Survival and Farmlands through MySQL.

### Seasons
- Configurable season length (currently 6 months).
- `/skyxcore season start` and `/skyxcore season end` with confirmation.
- Top 5 per category is captured into `skyxcore_season_results`.
- Configurable reward commands per category/rank are queued in `skyxcore_season_rewards` and delivered when the player joins.
- Stats are scoped by season; starting a season clears that season's old stats/results/rewards.

### Hot configuration
`/skyxcore reload` reloads categories, messages, anti-farm rules, power weights, rewards and module switches without restarting the server. Database connection changes still require a restart.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/leaderboard [category] [page]` | `skyxcore.use` | Open leaderboards. Aliases: `/leadb`, `/lb`, `/tops`. |
| `/guild ...` | `skyxcore.guild` | Guild commands. Aliases: `/guilds`, `/g`. |
| `/guild chat <message>` | `skyxcore.guild` | Guild chat. |
| `/guild bank balance|deposit|withdraw` | `skyxcore.guild` | Guild bank. |
| `/skyxcore reload` | `skyxcore.admin` | Reload config. |
| `/skyxcore status` | `skyxcore.admin` | Database/module status. |
| `/skyxcore season start <id> confirm` | `skyxcore.admin.season` | Start a season. |
| `/skyxcore season end confirm` | `skyxcore.admin.season` | End the season and queue top 5 rewards. |

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
