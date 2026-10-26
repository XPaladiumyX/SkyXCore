package net.skyxnetwork.core.guild;

import net.skyxnetwork.core.SkyXCore;
import net.skyxnetwork.core.core.CoreSettings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class GuildCommand implements CommandExecutor, TabCompleter {

    private final SkyXCore plugin;
    private final Map<UUID, Long> disbandConfirms = new ConcurrentHashMap<>();
    private final Map<UUID, Long> homeCooldowns = new ConcurrentHashMap<>();

    public GuildCommand(SkyXCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("player-only"));
            return true;
        }
        if (!plugin.getSettings().guildsEnabled) {
            player.sendMessage(plugin.prefixed("guild-none"));
            return true;
        }
        if (args.length == 0) {
            info(player, player.getName());
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help", "?" -> {
                player.sendMessage(plugin.prefixed("guild-usage"));
                return true;
            }
            case "create" -> {
                if (args.length < 3) {
                    player.sendMessage(plugin.prefixed("guild-create-usage"));
                    return true;
                }
                create(player, args[1], args[2]);
                return true;
            }
            case "invite" -> {
                if (args.length < 2) {
                    player.sendMessage(plugin.prefixed("guild-invite-usage"));
                    return true;
                }
                invite(player, args[1]);
                return true;
            }
            case "accept", "join" -> {
                if (args.length < 2) {
                    player.sendMessage(plugin.prefixed("guild-usage"));
                    return true;
                }
                accept(player, args[1]);
                return true;
            }
            case "leave" -> {
                leave(player);
                return true;
            }
            case "kick" -> {
                if (args.length < 2) {
                    player.sendMessage(plugin.prefixed("guild-usage"));
                    return true;
                }
                kick(player, args[1]);
                return true;
            }
            case "promote", "demote" -> {
                if (args.length < 2) {
                    player.sendMessage(plugin.prefixed("guild-usage"));
                    return true;
                }
                role(player, args[1], args[0].equalsIgnoreCase("promote"));
                return true;
            }
            case "disband" -> {
                disband(player, args);
                return true;
            }
            case "chat", "c" -> {
                if (args.length < 2) {
                    player.sendMessage(plugin.prefixed("guild-chat-usage"));
                    return true;
                }
                chat(player, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
                return true;
            }
            case "sethome" -> {
                sethome(player);
                return true;
            }
            case "home" -> {
                home(player);
                return true;
            }
            case "bank" -> {
                bank(player, args);
                return true;
            }
            case "info" -> {
                info(player, args.length >= 2 ? args[1] : player.getName());
                return true;
            }
            case "top" -> {
                CoreSettings.Category category = plugin.getSettings().categoryById.get("guilds");
                if (category == null) {
                    player.sendMessage(plugin.prefixed("guild-not-found"));
                } else {
                    plugin.getLeaderboardGui().openCategory(player, category, 0);
                }
                return true;
            }
            default -> {
                player.sendMessage(plugin.prefixed("guild-usage"));
                return true;
            }
        }
    }

    private void create(Player player, String name, String tag) {
        double cost = plugin.getSettings().guildCreateCost;
        if (cost > 0 && (!plugin.getEconomy().available() || !plugin.getEconomy().has(player, cost))) {
            player.sendMessage(plugin.prefixed("guild-create-cost", "%cost%", plugin.getSettings().money(cost)));
            return;
        }
        async(() -> plugin.getGuildService().create(name, tag, player.getUniqueId()), outcome -> {
            if (outcome.result() == GuildService.Result.ALREADY_IN_GUILD) {
                player.sendMessage(plugin.prefixed("guild-already-in"));
                return;
            }
            if (outcome.result() == GuildService.Result.NAME_TAKEN) {
                player.sendMessage(plugin.prefixed("guild-name-taken"));
                return;
            }
            if (!outcome.ok() || outcome.guild() == null) {
                player.sendMessage(plugin.prefixed("guild-invalid-name"));
                return;
            }
            if (cost > 0) plugin.getEconomy().withdraw(player, cost);
            player.sendMessage(plugin.prefixed("guild-created",
                    "%guild%", outcome.guild().name(),
                    "%cost%", plugin.getSettings().money(cost)));
        });
    }

    private void invite(Player player, String targetName) {
        Player target = findOnline(targetName);
        if (target == null) {
            player.sendMessage(plugin.prefixed("player-not-found"));
            return;
        }
        async(() -> {
            Guild.Info guild = plugin.getGuildService().guildByPlayer(player.getUniqueId());
            Guild.Member member = plugin.getGuildService().member(player.getUniqueId());
            if (guild == null || member == null) return null;
            if (!member.role().atLeast(Guild.Role.OFFICER)) return null;
            return new InviteResult(guild, plugin.getGuildService().invite(guild.id(),
                    target.getUniqueId(), player.getUniqueId()));
        }, result -> {
            if (result == null || result.result() != GuildService.Result.OK) {
                player.sendMessage(plugin.prefixed("guild-admin-only"));
                return;
            }
            player.sendMessage(plugin.prefixed("guild-invited", "%player%", target.getName()));
            target.sendMessage(plugin.prefixed("guild-invite-received", "%guild%", result.guild().name()));
        });
    }

    private void accept(Player player, String guildName) {
        async(() -> plugin.getGuildService().accept(player.getUniqueId(), guildName), outcome -> {
            if (outcome.result() == GuildService.Result.NO_INVITE) {
                player.sendMessage(plugin.prefixed("guild-invite-expired"));
                return;
            }
            if (outcome.result() == GuildService.Result.FULL) {
                player.sendMessage(plugin.prefixed("guild-full"));
                return;
            }
            if (outcome.result() == GuildService.Result.ALREADY_IN_GUILD) {
                player.sendMessage(plugin.prefixed("guild-already-in"));
                return;
            }
            if (!outcome.ok() || outcome.guild() == null) {
                player.sendMessage(plugin.prefixed("guild-not-found"));
                return;
            }
            player.sendMessage(plugin.prefixed("guild-accepted", "%guild%", outcome.guild().name()));
            broadcastGuild(outcome.guild().id(), plugin.prefixed("guild-joined", "%player%", player.getName()));
        });
    }

    private void leave(Player player) {
        async(() -> {
            Guild.Info guild = plugin.getGuildService().guildByPlayer(player.getUniqueId());
            GuildService.Result result = plugin.getGuildService().leave(player.getUniqueId());
            return new LeaveResult(guild, result);
        }, result -> {
            if (result.result() == GuildService.Result.NOT_IN_GUILD) {
                player.sendMessage(plugin.prefixed("guild-none"));
            } else if (result.result() == GuildService.Result.LEADER_CANNOT_LEAVE) {
                player.sendMessage(plugin.prefixed("guild-leader-only"));
            } else {
                player.sendMessage(plugin.prefixed("guild-left"));
                if (result.guild() != null) {
                    broadcastGuild(result.guild().id(),
                            plugin.prefixed("guild-member-left", "%player%", player.getName()));
                }
            }
        });
    }

    private void kick(Player player, String targetName) {
        Player target = findOnline(targetName);
        if (target == null) {
            player.sendMessage(plugin.prefixed("player-not-found"));
            return;
        }
        async(() -> {
            Guild.Info guild = plugin.getGuildService().guildByPlayer(player.getUniqueId());
            Guild.Member actor = plugin.getGuildService().member(player.getUniqueId());
            Guild.Member victim = plugin.getGuildService().member(target.getUniqueId());
            if (guild == null || actor == null || victim == null || victim.guildId() != guild.id()) return null;
            if (actor.role() == Guild.Role.MEMBER || victim.role() == Guild.Role.LEADER) return null;
            if (actor.role() == Guild.Role.OFFICER && victim.role() == Guild.Role.OFFICER) return null;
            GuildService.Result result = plugin.getGuildService().removeMember(target.getUniqueId());
            return new KickResult(guild, result);
        }, result -> {
            if (result == null || result.result() != GuildService.Result.OK) {
                player.sendMessage(plugin.prefixed("guild-admin-only"));
                return;
            }
            player.sendMessage(plugin.prefixed("guild-kicked", "%player%", target.getName()));
            target.sendMessage(plugin.prefixed("guild-kick-target", "%guild%", result.guild().name()));
            broadcastGuild(result.guild().id(),
                    plugin.prefixed("guild-member-left", "%player%", target.getName()));
        });
    }

    private void role(Player player, String targetName, boolean promote) {
        Player target = findOnline(targetName);
        if (target == null) {
            player.sendMessage(plugin.prefixed("player-not-found"));
            return;
        }
        async(() -> {
            Guild.Info guild = plugin.getGuildService().guildByPlayer(player.getUniqueId());
            Guild.Member actor = plugin.getGuildService().member(player.getUniqueId());
            Guild.Member victim = plugin.getGuildService().member(target.getUniqueId());
            if (guild == null || actor == null || victim == null || victim.guildId() != guild.id()) return null;
            if (actor.role() != Guild.Role.LEADER) return null;
            if (victim.role() == Guild.Role.LEADER) return null;
            Guild.Role next = promote
                    ? (victim.role() == Guild.Role.MEMBER ? Guild.Role.OFFICER : victim.role())
                    : (victim.role() == Guild.Role.OFFICER ? Guild.Role.MEMBER : victim.role());
            if (next == victim.role()) return null;
            GuildService.Result result = plugin.getGuildService().setRole(target.getUniqueId(), next);
            return new RoleResult(guild, result, next);
        }, result -> {
            if (result == null || result.result() != GuildService.Result.OK) {
                player.sendMessage(plugin.prefixed("guild-admin-only"));
                return;
            }
            player.sendMessage(plugin.prefixed(promote ? "guild-promoted" : "guild-demoted",
                    "%player%", target.getName()));
            target.sendMessage(plugin.prefixed(promote ? "guild-promoted" : "guild-demoted",
                    "%player%", target.getName()));
        });
    }

    private void disband(Player player, String[] args) {
        UUID uuid = player.getUniqueId();
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            async(() -> plugin.getGuildService().guildByPlayer(uuid), guild -> {
                if (guild == null) {
                    player.sendMessage(plugin.prefixed("guild-none"));
                    return;
                }
                disbandConfirms.put(uuid, System.currentTimeMillis() + 30_000L);
                player.sendMessage(plugin.prefixed("guild-disband-warning", "%guild%", guild.name()));
            });
            return;
        }
        Long until = disbandConfirms.remove(uuid);
        if (until == null || until < System.currentTimeMillis()) {
            player.sendMessage(plugin.prefixed("guild-usage"));
            return;
        }
        async(() -> {
            Guild.Info guild = plugin.getGuildService().guildByPlayer(uuid);
            Guild.Member member = plugin.getGuildService().member(uuid);
            if (guild == null || member == null || member.role() != Guild.Role.LEADER) return null;
            GuildService.Result result = plugin.getGuildService().disband(guild.id());
            return new DisbandResult(guild, result);
        }, result -> {
            if (result == null || result.result() != GuildService.Result.OK) {
                player.sendMessage(plugin.prefixed("guild-leader-only"));
                return;
            }
            player.sendMessage(plugin.prefixed("guild-disbanded", "%guild%", result.guild().name()));
        });
    }

    private void chat(Player player, String message) {
        async(() -> {
            Guild.Info guild = plugin.getGuildService().guildByPlayer(player.getUniqueId());
            if (guild == null) return null;
            List<Guild.Member> members = plugin.getGuildService().members(guild.id());
            return new ChatResult(guild, members);
        }, result -> {
            if (result == null) {
                player.sendMessage(plugin.prefixed("guild-chat-not-in"));
                return;
            }
            String line = plugin.getSettings().guildChatFormat
                    .replace("%tag%", result.guild().tag())
                    .replace("%player%", player.getName())
                    .replace("%message%", message);
            for (Guild.Member member : result.members()) {
                Player online = Bukkit.getPlayer(member.uuid());
                if (online != null) online.sendMessage(plugin.color(line));
            }
        });
    }

    private void sethome(Player player) {
        async(() -> {
            Guild.Info guild = plugin.getGuildService().guildByPlayer(player.getUniqueId());
            Guild.Member member = plugin.getGuildService().member(player.getUniqueId());
            if (guild == null || member == null || !member.role().atLeast(Guild.Role.OFFICER)) return null;
            Location location = player.getLocation();
            return new HomeResult(guild, plugin.getGuildService().setHome(guild.id(),
                    location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch()));
        }, result -> {
            if (result == null || result.result() != GuildService.Result.OK) {
                player.sendMessage(plugin.prefixed("guild-admin-only"));
            } else {
                player.sendMessage(plugin.prefixed("guild-home-set"));
            }
        });
    }

    private void home(Player player) {
        long now = System.currentTimeMillis();
        Long cooldown = homeCooldowns.get(player.getUniqueId());
        if (cooldown != null && cooldown > now) {
            player.sendMessage(plugin.prefixed("guild-home-warmup",
                    "%seconds%", Long.toString((cooldown - now) / 1000)));
            return;
        }
        async(() -> plugin.getGuildService().guildByPlayer(player.getUniqueId()), guild -> {
            if (guild == null) {
                player.sendMessage(plugin.prefixed("guild-none"));
                return;
            }
            if (!plugin.getSettings().guildHomeEnabled || guild.homeWorld() == null) {
                player.sendMessage(plugin.prefixed("guild-home-none"));
                return;
            }
            World world = Bukkit.getWorld(guild.homeWorld());
            if (world == null || guild.homeX() == null || guild.homeY() == null || guild.homeZ() == null) {
                player.sendMessage(plugin.prefixed("guild-home-none"));
                return;
            }
            Location target = new Location(world, guild.homeX(), guild.homeY(), guild.homeZ(),
                    guild.homeYaw() == null ? 0 : guild.homeYaw(),
                    guild.homePitch() == null ? 0 : guild.homePitch());
            int warmup = plugin.getSettings().guildHomeWarmupSeconds;
            if (warmup <= 0) {
                player.teleport(target);
                homeCooldowns.put(player.getUniqueId(),
                        now + plugin.getSettings().guildHomeCooldownSeconds * 1000L);
                player.sendMessage(plugin.prefixed("guild-home-teleported"));
                return;
            }
            player.sendMessage(plugin.prefixed("guild-home-warmup", "%seconds%", Integer.toString(warmup)));
            Location start = player.getLocation().clone();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) return;
                if (!player.getWorld().equals(start.getWorld())
                        || player.getLocation().distanceSquared(start) > 4) {
                    player.sendMessage(plugin.prefixed("guild-home-moved"));
                    return;
                }
                player.teleport(target);
                homeCooldowns.put(player.getUniqueId(),
                        System.currentTimeMillis() + plugin.getSettings().guildHomeCooldownSeconds * 1000L);
                player.sendMessage(plugin.prefixed("guild-home-teleported"));
            }, warmup * 20L);
        });
    }

    private void bank(Player player, String[] args) {
        if (!plugin.getSettings().guildBankEnabled) {
            player.sendMessage(plugin.prefixed("guild-bank-disabled"));
            return;
        }
        if (args.length >= 2 && !args[1].equalsIgnoreCase("balance")
                && !args[1].equalsIgnoreCase("deposit")
                && !args[1].equalsIgnoreCase("withdraw")) {
            player.sendMessage(plugin.prefixed("guild-usage"));
            return;
        }
        double amount = 0;
        boolean deposit = args.length >= 2 && args[1].equalsIgnoreCase("deposit");
        boolean withdraw = args.length >= 2 && args[1].equalsIgnoreCase("withdraw");
        if (deposit || withdraw) {
            if (args.length < 3) {
                player.sendMessage(plugin.prefixed("guild-usage"));
                return;
            }
            try {
                amount = Double.parseDouble(args[2]);
            } catch (NumberFormatException exception) {
                player.sendMessage(plugin.prefixed("guild-usage"));
                return;
            }
            if (amount <= 0) {
                player.sendMessage(plugin.prefixed("guild-usage"));
                return;
            }
        }
        final double finalAmount = amount;
        async(() -> {
            Guild.Info guild = plugin.getGuildService().guildByPlayer(player.getUniqueId());
            Guild.Member member = plugin.getGuildService().member(player.getUniqueId());
            return guild == null ? null : new BankContext(guild, member);
        }, context -> {
            if (context == null) {
                player.sendMessage(plugin.prefixed("guild-none"));
                return;
            }
            Guild.Info guild = context.guild();
            if (!deposit && !withdraw) {
                player.sendMessage(plugin.prefixed("guild-bank-balance",
                        "%balance%", plugin.getSettings().money(guild.bank())));
                return;
            }
            if (deposit) {
                if (!plugin.getEconomy().available() || !plugin.getEconomy().has(player, finalAmount)) {
                    player.sendMessage(plugin.prefixed("guild-bank-no-money"));
                    return;
                }
                if (!plugin.getEconomy().withdraw(player, finalAmount)) {
                    player.sendMessage(plugin.prefixed("guild-bank-no-money"));
                    return;
                }
                async(() -> plugin.getGuildService().depositBank(guild.id(), finalAmount), ok -> {
                    if (Boolean.TRUE.equals(ok)) {
                        player.sendMessage(plugin.prefixed("guild-bank-deposited",
                                "%amount%", plugin.getSettings().money(finalAmount)));
                    } else {
                        plugin.getEconomy().deposit(player, finalAmount);
                        player.sendMessage(plugin.prefixed("guild-bank-no-funds"));
                    }
                });
                return;
            }
            if (context.member() == null || !context.member().role().atLeast(Guild.Role.OFFICER)) {
                player.sendMessage(plugin.prefixed("guild-bank-no-permission"));
                return;
            }
            async(() -> plugin.getGuildService().withdrawBank(guild.id(), finalAmount), ok -> {
                if (!Boolean.TRUE.equals(ok)) {
                    player.sendMessage(plugin.prefixed("guild-bank-no-funds"));
                    return;
                }
                if (!plugin.getEconomy().deposit(player, finalAmount)) {
                    Bukkit.getScheduler().runTaskAsynchronously(plugin,
                            () -> plugin.getGuildService().depositBank(guild.id(), finalAmount));
                    player.sendMessage(plugin.prefixed("guild-bank-no-funds"));
                } else {
                    player.sendMessage(plugin.prefixed("guild-bank-withdrew",
                            "%amount%", plugin.getSettings().money(finalAmount)));
                }
            });
        });
    }

    private void info(Player player, String name) {
        async(() -> {
            Guild.Info guild = plugin.getGuildService().guildByPlayer(player.getUniqueId());
            if (name != null && !name.equalsIgnoreCase(player.getName())) {
                guild = plugin.getGuildService().guildByNameOrTag(name);
            }
            if (guild == null) return null;
            List<Guild.Member> members = plugin.getGuildService().members(guild.id());
            return new InfoResult(guild, members);
        }, result -> {
            if (result == null) {
                player.sendMessage(plugin.prefixed("guild-not-found"));
                return;
            }
            Guild.Info guild = result.guild();
            player.sendMessage(plugin.message("guild-info",
                    "%guild%", guild.name(),
                    "%tag%", guild.tag(),
                    "%leader%", plugin.getPlayerRegistry().name(guild.leader()),
                    "%members%", Integer.toString(result.members().size()),
                    "%max%", Integer.toString(guild.maxMembers()),
                    "%power%", plugin.getSettings().number(guild.power()),
                    "%bank%", plugin.getSettings().money(guild.bank())));
        });
    }

    private void broadcastGuild(int guildId, String message) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Guild.Member> members = plugin.getGuildService().members(guildId);
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (Guild.Member member : members) {
                    Player online = Bukkit.getPlayer(member.uuid());
                    if (online != null) online.sendMessage(message);
                }
            });
        });
    }

    private Player findOnline(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;
        for (Player online : Bukkit.getOnlinePlayers()) {
            String current = online.getName();
            if (current.length() > 1 && (current.charAt(0) == '*' || current.charAt(0) == '.')) {
                if (current.substring(1).equalsIgnoreCase(name)) return online;
            }
        }
        return null;
    }

    private <T> void async(Supplier<T> supplier, Consumer<T> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                T result = supplier.get();
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Guild operation failed: " + exception.getMessage());
                Bukkit.getScheduler().runTask(plugin,
                        () -> callback.accept(null));
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> result = new ArrayList<>();
            for (String value : List.of("help", "create", "invite", "accept", "leave", "kick",
                    "promote", "demote", "disband", "chat", "sethome", "home", "bank", "info", "top")) {
                if (value.startsWith(prefix)) result.add(value);
            }
            return result;
        }
        if (args.length == 2 && List.of("invite", "kick", "promote", "demote").contains(args[0].toLowerCase())) {
            String prefix = args[1].toLowerCase();
            List<String> result = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(prefix)) result.add(player.getName());
            }
            return result;
        }
        return List.of();
    }

    private record BankContext(Guild.Info guild, Guild.Member member) {
    }

    private record InviteResult(Guild.Info guild, GuildService.Result result) {
    }

    private record LeaveResult(Guild.Info guild, GuildService.Result result) {
    }

    private record KickResult(Guild.Info guild, GuildService.Result result) {
    }

    private record RoleResult(Guild.Info guild, GuildService.Result result, Guild.Role role) {
    }

    private record DisbandResult(Guild.Info guild, GuildService.Result result) {
    }

    private record ChatResult(Guild.Info guild, List<Guild.Member> members) {
    }

    private record HomeResult(Guild.Info guild, GuildService.Result result) {
    }

    private record InfoResult(Guild.Info guild, List<Guild.Member> members) {
    }
}
