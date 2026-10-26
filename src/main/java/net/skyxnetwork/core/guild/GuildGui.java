package net.skyxnetwork.core.guild;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.skyxnetwork.core.SkyXCore;
import net.skyxnetwork.core.core.CoreSettings;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Player-friendly guild management GUI. All common actions can be performed
 * by clicking; chat prompts are only used for free-text fields (name/tag/description).
 */
public final class GuildGui implements Listener {

    private enum Section { NO_GUILD, MAIN, MEMBERS, MEMBER_ACTIONS, INVITES, BANK, HOME, SETTINGS, POWER }
    private enum InputType { CREATE, TAG, DESCRIPTION }

    private record PendingInput(InputType type, long expires) {
    }

    private final SkyXCore plugin;
    private final GuildService guilds;
    private volatile CoreSettings settings;
    private final Map<UUID, PendingInput> pendingInputs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> disbandConfirms = new ConcurrentHashMap<>();

    public GuildGui(SkyXCore plugin, GuildService guilds, CoreSettings settings) {
        this.plugin = plugin;
        this.guilds = guilds;
        this.settings = settings;
    }

    public void updateSettings(CoreSettings settings) {
        this.settings = settings;
    }

    public void open(Player player) {
        if (!settings.guildsEnabled) {
            player.sendMessage(plugin.prefixed("guild-none"));
            return;
        }
        async(() -> snapshot(player), snapshot -> {
            if (snapshot == null || snapshot.guild() == null) {
                openNoGuild(player);
            } else {
                openMain(player, snapshot);
            }
        });
    }

    private Snapshot snapshot(Player player) {
        Guild.Info guild = guilds.guildByPlayer(player.getUniqueId());
        if (guild == null) return null;
        Guild.Member member = guilds.member(player.getUniqueId());
        List<Guild.Member> members = guilds.members(guild.id());
        return new Snapshot(guild, member, members);
    }

    private void openNoGuild(Player player) {
        Holder holder = new Holder(Section.NO_GUILD, player.getUniqueId(), null, null,
                List.of(), null);
        Inventory inventory = Bukkit.createInventory(holder, 54, color("&8Guilds"));
        holder.inventory = inventory;
        fill(inventory);
        inventory.setItem(22, item(Material.WHITE_BANNER, "&d&lCreate a Guild",
                List.of("&7Create your own guild and start",
                        "&7building its power.",
                        "",
                        "&eClick to enter a name and tag."), false));
        inventory.setItem(49, item(Material.BARRIER, "&cClose", List.of(), false));
        player.openInventory(inventory);
    }

    private void openMain(Player player, Snapshot snapshot) {
        Holder holder = new Holder(Section.MAIN, player.getUniqueId(), snapshot.guild(),
                snapshot.actor(), snapshot.members(), null);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                color("&8" + snapshot.guild().name() + " &7» &dGuild"));
        holder.inventory = inventory;
        fill(inventory);

        Guild.Info guild = snapshot.guild();
        long online = snapshot.members().stream().filter(member -> Bukkit.getPlayer(member.uuid()) != null).count();
        inventory.setItem(4, guildBanner(guild, snapshot.members().size(), online));
        inventory.setItem(20, item(Material.PLAYER_HEAD, "&e&lMembers",
                List.of("&7View members, roles and online status.",
                        "&7Clicked: manage a member."), false));
        inventory.setItem(21, item(Material.PAPER, "&b&lInvitations",
                List.of("&7Invite online players.",
                        "&7Officers and leaders only."), false));
        inventory.setItem(22, item(Material.GOLD_INGOT, "&6&lGuild Bank",
                List.of("&7Balance: &e" + settings.money(guild.bank()),
                        "&7Deposit or withdraw money."), false));
        inventory.setItem(23, item(Material.RED_BED, "&d&lGuild Home",
                List.of("&7Set or teleport to the guild home.",
                        "&7Officers and leaders can set it."), false));
        inventory.setItem(24, item(Material.COMPARATOR, "&f&lSettings",
                List.of("&7Tag colors, description and access.",
                        "&7Leader only."), false));
        inventory.setItem(25, item(Material.NETHER_STAR, "&d&lTop Guilds",
                List.of("&7Open the guild leaderboard."), true));
        inventory.setItem(39, item(Material.BOOK, "&e&l? How to increase Power",
                List.of("&7Click to see how guild power works",
                        "&7and what your members should do."), true));

        if (snapshot.actor() != null && snapshot.actor().role() == Guild.Role.LEADER) {
            inventory.setItem(40, item(Material.BARRIER, "&c&lDisband Guild",
                    List.of("&7Click twice within 30 seconds",
                            "&7to permanently disband the guild."), false));
        } else {
            inventory.setItem(40, item(Material.OAK_DOOR, "&cLeave Guild",
                    List.of("&7Leave your current guild."), false));
        }
        inventory.setItem(49, item(Material.BARRIER, "&cClose", List.of(), false));
        player.openInventory(inventory);
    }

    private void openPowerHelp(Player player, Snapshot snapshot) {
        Holder holder = new Holder(Section.POWER, player.getUniqueId(), snapshot.guild(),
                snapshot.actor(), snapshot.members(), null);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                color("&8Guild Power &7» &eGuide"));
        holder.inventory = inventory;
        fill(inventory);
        CoreSettings.GuildPowerWeights weights = settings.guildPower;
        inventory.setItem(4, item(Material.EXPERIENCE_BOTTLE, "&e&lHow Guild Power Works",
                List.of("&7Power is recalculated every 5 minutes.",
                        "&7It is the sum of all member activity.",
                        "&7Higher power = higher guild rank."), true));
        inventory.setItem(20, item(Material.IRON_SWORD, "&cPvP Kills",
                List.of("&7Each PvP kill: &e+" + settings.number(weights.pvp()) + " power",
                        "&7Fight other players with your guild."), false));
        inventory.setItem(21, item(Material.DRAGON_HEAD, "&5Boss Damage",
                List.of("&7Each damage point: &e+" + settings.number(weights.boss()) + " power",
                        "&7Fight Ender Dragon, Wither and Warden."), false));
        inventory.setItem(22, item(Material.ROTTEN_FLESH, "&2Mob Kills",
                List.of("&7Each mob kill: &e+" + settings.number(weights.mob()) + " power"), false));
        inventory.setItem(23, item(Material.WHEAT, "&aCrops Broken",
                List.of("&7Each crop: &e+" + settings.number(weights.crop()) + " power"), false));
        inventory.setItem(24, item(Material.STONE, "&7Blocks Broken",
                List.of("&7Each block: &e+" + settings.number(weights.block()) + " power"), false));
        inventory.setItem(29, item(Material.CLOCK, "&9Playtime",
                List.of("&7Each hour: &e+" + settings.number(weights.playtime()) + " power"), false));
        inventory.setItem(30, item(Material.GOLD_INGOT, "&6Guild Bank",
                List.of("&7Each 1,000₪: &e+" + settings.number(weights.bank() * 1000) + " power",
                        "&7Deposit in /guild bank."), false));
        inventory.setItem(31, item(Material.PLAYER_HEAD, "&eMembers",
                List.of("&7Each member: &e+" + settings.number(weights.member()) + " power",
                        "&7Recruit active players."), false));
        inventory.setItem(40, item(Material.LIME_DYE, "&aFastest way",
                List.of("&7Play with your guild: PvP, bosses,",
                        "&7mobs, farming, mining and playtime.",
                        "&7Deposit money in the guild bank."), true));
        backAndClose(inventory);
        player.openInventory(inventory);
    }

    private void openMembers(Player player, Snapshot snapshot) {
        Holder holder = new Holder(Section.MEMBERS, player.getUniqueId(), snapshot.guild(),
                snapshot.actor(), snapshot.members(), null);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                color("&8" + snapshot.guild().name() + " &7» &eMembers"));
        holder.inventory = inventory;
        fill(inventory);
        int[] slots = innerSlots();
        for (int index = 0; index < snapshot.members().size() && index < slots.length; index++) {
            Guild.Member member = snapshot.members().get(index);
            Player online = Bukkit.getPlayer(member.uuid());
            String name = online == null ? member.name() : online.getName();
            ItemStack head = head(member.uuid(), "&e" + name,
                    List.of("&7Role: " + roleName(member.role()),
                            "&7Status: " + (online == null ? "&7Offline" : "&aOnline"),
                            "&7Joined: &f" + java.time.Instant.ofEpochMilli(member.joinedAt()),
                            "",
                            "&eClick to manage."));
            inventory.setItem(slots[index], head);
        }
        backAndClose(inventory);
        player.openInventory(inventory);
    }

    private void openMemberActions(Player player, Snapshot snapshot, Guild.Member target) {
        Holder holder = new Holder(Section.MEMBER_ACTIONS, player.getUniqueId(), snapshot.guild(),
                snapshot.actor(), snapshot.members(), target.uuid());
        Inventory inventory = Bukkit.createInventory(holder, 54,
                color("&8" + target.name() + " &7» &eMember"));
        holder.inventory = inventory;
        fill(inventory);
        inventory.setItem(4, head(target.uuid(), "&e" + target.name(),
                List.of("&7Role: " + roleName(target.role()),
                        "&7Guild: &f" + snapshot.guild().name())));
        boolean leader = snapshot.actor() != null && snapshot.actor().role() == Guild.Role.LEADER;
        boolean officer = snapshot.actor() != null && snapshot.actor().role().atLeast(Guild.Role.OFFICER);
        if (leader && target.role() == Guild.Role.MEMBER) {
            inventory.setItem(20, item(Material.LIME_DYE, "&aPromote to Officer", List.of(), false));
        }
        if (leader && target.role() == Guild.Role.OFFICER) {
            inventory.setItem(21, item(Material.GRAY_DYE, "&7Demote to Member", List.of(), false));
        }
        if (officer && target.role() != Guild.Role.LEADER
                && !(snapshot.actor().role() == Guild.Role.OFFICER && target.role() == Guild.Role.OFFICER)) {
            inventory.setItem(23, item(Material.REDSTONE, "&cKick from Guild", List.of(), false));
        }
        inventory.setItem(45, item(Material.ARROW, "&eBack", List.of(), false));
        inventory.setItem(49, item(Material.BARRIER, "&cClose", List.of(), false));
        player.openInventory(inventory);
    }

    private void openInvites(Player player, Snapshot snapshot) {
        List<UUID> candidates = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(player.getUniqueId())) continue;
            if (guilds.cachedMember(online.getUniqueId()) != null) continue;
            candidates.add(online.getUniqueId());
        }
        Holder holder = new Holder(Section.INVITES, player.getUniqueId(), snapshot.guild(),
                snapshot.actor(), snapshot.members(), null);
        holder.candidates = List.copyOf(candidates);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                color("&8" + snapshot.guild().name() + " &7» &bInvitations"));
        holder.inventory = inventory;
        fill(inventory);
        int[] slots = innerSlots();
        for (int index = 0; index < candidates.size() && index < slots.length; index++) {
            UUID uuid = candidates.get(index);
            Player online = Bukkit.getPlayer(uuid);
            if (online == null) continue;
            inventory.setItem(slots[index], head(uuid, "&b" + online.getName(),
                    List.of("&7Click to invite this player.")));
        }
        if (candidates.isEmpty()) {
            inventory.setItem(22, item(Material.GRAY_DYE, "&7No players available",
                    List.of("&7Everyone online is already in a guild."), false));
        }
        backAndClose(inventory);
        player.openInventory(inventory);
    }

    private void openBank(Player player, Snapshot snapshot) {
        Holder holder = new Holder(Section.BANK, player.getUniqueId(), snapshot.guild(),
                snapshot.actor(), snapshot.members(), null);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                color("&8" + snapshot.guild().name() + " &7» &6Bank"));
        holder.inventory = inventory;
        fill(inventory);
        inventory.setItem(4, item(Material.GOLD_BLOCK, "&6&lGuild Bank",
                List.of("&7Balance: &e" + settings.money(snapshot.guild().bank()),
                        "&7Leader and officers can withdraw."), false));
        long[] amounts = {1_000L, 10_000L, 100_000L};
        int[] depositSlots = {20, 21, 22};
        int[] withdrawSlots = {29, 30, 31};
        for (int index = 0; index < amounts.length; index++) {
            inventory.setItem(depositSlots[index], item(Material.EMERALD,
                    "&aDeposit " + settings.money(amounts[index]), List.of(), false));
            inventory.setItem(withdrawSlots[index], item(Material.REDSTONE,
                    "&cWithdraw " + settings.money(amounts[index]), List.of(), false));
        }
        backAndClose(inventory);
        player.openInventory(inventory);
    }

    private void openHome(Player player, Snapshot snapshot) {
        Holder holder = new Holder(Section.HOME, player.getUniqueId(), snapshot.guild(),
                snapshot.actor(), snapshot.members(), null);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                color("&8" + snapshot.guild().name() + " &7» &dHome"));
        holder.inventory = inventory;
        fill(inventory);
        boolean hasHome = snapshot.guild().homeWorld() != null;
        inventory.setItem(20, item(Material.RED_BED, "&d&lGuild Home",
                List.of(hasHome ? "&7A guild home is set." : "&7No guild home set.",
                        "&7Click to teleport."), hasHome));
        inventory.setItem(24, item(Material.ENDER_PEARL, "&e&lSet Home",
                List.of("&7Set the guild home to your position.",
                        "&7Leader and officers only."), false));
        backAndClose(inventory);
        player.openInventory(inventory);
    }

    private void openSettings(Player player, Snapshot snapshot) {
        Holder holder = new Holder(Section.SETTINGS, player.getUniqueId(), snapshot.guild(),
                snapshot.actor(), snapshot.members(), null);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                color("&8" + snapshot.guild().name() + " &7» &fSettings"));
        holder.inventory = inventory;
        fill(inventory);
        inventory.setItem(4, guildBanner(snapshot.guild(), snapshot.members().size(),
                snapshot.members().stream().filter(member -> Bukkit.getPlayer(member.uuid()) != null).count()));
        String[] colors = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f"};
        Material[] panes = {Material.BLACK_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE,
                Material.GREEN_STAINED_GLASS_PANE, Material.CYAN_STAINED_GLASS_PANE,
                Material.RED_STAINED_GLASS_PANE, Material.PURPLE_STAINED_GLASS_PANE,
                Material.ORANGE_STAINED_GLASS_PANE, Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                Material.GRAY_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                Material.LIME_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE,
                Material.RED_STAINED_GLASS_PANE, Material.PINK_STAINED_GLASS_PANE,
                Material.YELLOW_STAINED_GLASS_PANE, Material.WHITE_STAINED_GLASS_PANE};
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29};
        for (int index = 0; index < slots.length; index++) {
            inventory.setItem(slots[index], item(panes[index], "&eColor &" + colors[index],
                    List.of("&7Click to apply this color to the tag."), false));
        }
        inventory.setItem(31, item(Material.NAME_TAG, "&eEdit Tag",
                List.of("&7Current: &f" + ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', snapshot.guild().tag())),
                        "&7Click and type the new tag in chat."), false));
        inventory.setItem(32, item(Material.BOOK, "&eEdit Description",
                List.of("&7Current: &f" + (snapshot.guild().description() == null ? "" : snapshot.guild().description()),
                        "&7Click and type a description in chat."), false));
        inventory.setItem(33, item(snapshot.guild().open() ? Material.LIME_DYE : Material.GRAY_DYE,
                snapshot.guild().open() ? "&aOpen Guild" : "&7Invite Only",
                List.of("&7Click to toggle open/invite-only."), false));
        backAndClose(inventory);
        player.openInventory(inventory);
    }

    private ItemStack guildBanner(Guild.Info guild, int members, long online) {
        String visible = ChatColor.translateAlternateColorCodes('&', guild.tag());
        return item(Material.WHITE_BANNER, "&8[" + visible + "&8] &f" + guild.name(),
                List.of("&7Tag: &f" + ChatColor.stripColor(visible),
                        "&7Leader: &f" + plugin.getPlayerRegistry().name(guild.leader()),
                        "&7Members: &f" + members + " &7(&a" + online + " online&7)",
                        "&7Power: &d" + settings.number(guild.power()),
                        "&7Bank: &e" + settings.money(guild.bank()),
                        "&7Access: &f" + (guild.open() ? "Open" : "Invite only")), true);
    }

    private void backAndClose(Inventory inventory) {
        inventory.setItem(45, item(Material.ARROW, "&eBack", List.of(), false));
        inventory.setItem(49, item(Material.BARRIER, "&cClose", List.of(), false));
    }

    private int[] innerSlots() {
        return new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    }

    private String roleName(Guild.Role role) {
        return switch (role) {
            case LEADER -> "&6Leader";
            case OFFICER -> "&eOfficer";
            case MEMBER -> "&7Member";
        };
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        PendingInput input = pendingInputs.remove(event.getPlayer().getUniqueId());
        if (input == null) return;
        if (input.expires() < System.currentTimeMillis()) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin,
                    () -> event.getPlayer().sendMessage(plugin.prefixed("guild-usage")));
            return;
        }
        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            switch (input.type()) {
                case CREATE -> createFromInput(player, message);
                case TAG -> tagFromInput(player, message);
                case DESCRIPTION -> descriptionFromInput(player, message);
            }
        });
    }

    private void createFromInput(Player player, String message) {
        String[] parts = message.split("\\s+", 2);
        if (parts.length < 2) {
            player.sendMessage(plugin.prefixed("guild-create-usage"));
            open(player);
            return;
        }
        double cost = settings.guildCreateCost;
        if (cost > 0 && (!plugin.getEconomy().available() || !plugin.getEconomy().has(player, cost))) {
            player.sendMessage(plugin.prefixed("guild-create-cost", "%cost%", settings.money(cost)));
            open(player);
            return;
        }
        async(() -> guilds.create(parts[0], parts[1], player.getUniqueId()), outcome -> {
            if (!outcome.ok() || outcome.guild() == null) {
                player.sendMessage(plugin.prefixed(outcome.result() == GuildService.Result.NAME_TAKEN
                        ? "guild-name-taken" : "guild-invalid-name"));
                open(player);
                return;
            }
            if (cost > 0) plugin.getEconomy().withdraw(player, cost);
            player.sendMessage(plugin.prefixed("guild-created",
                    "%guild%", outcome.guild().name(),
                    "%cost%", settings.money(cost)));
            open(player);
        });
    }

    private void tagFromInput(Player player, String message) {
        async(() -> {
            Guild.Info guild = guilds.guildByPlayer(player.getUniqueId());
            Guild.Member member = guilds.member(player.getUniqueId());
            if (guild == null || member == null || member.role() != Guild.Role.LEADER) return null;
            return guilds.setTag(guild.id(), message);
        }, result -> {
            if (result == null) {
                player.sendMessage(plugin.prefixed("guild-leader-only"));
            } else if (result == GuildService.Result.NAME_TAKEN) {
                player.sendMessage(plugin.prefixed("guild-tag-taken"));
            } else if (result == GuildService.Result.OK) {
                player.sendMessage(plugin.prefixed("guild-tag-set", "%tag%", message));
            } else {
                player.sendMessage(plugin.prefixed("guild-tag-invalid"));
            }
            open(player);
        });
    }

    private void descriptionFromInput(Player player, String message) {
        async(() -> {
            Guild.Info guild = guilds.guildByPlayer(player.getUniqueId());
            Guild.Member member = guilds.member(player.getUniqueId());
            if (guild == null || member == null || !member.role().atLeast(Guild.Role.OFFICER)) return null;
            return guilds.setDescription(guild.id(), message);
        }, result -> {
            if (result == null) {
                player.sendMessage(plugin.prefixed("guild-admin-only"));
            } else {
                player.sendMessage(plugin.prefixed("guild-description-set"));
            }
            open(player);
        });
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;
        if (slot == 49) {
            player.closeInventory();
            return;
        }
        if (slot == 45 && holder.section != Section.MAIN && holder.section != Section.NO_GUILD) {
            openMainFromHolder(player, holder);
            return;
        }
        switch (holder.section) {
            case NO_GUILD -> {
                if (slot == 22) {
                    player.closeInventory();
                    pendingInputs.put(player.getUniqueId(),
                            new PendingInput(InputType.CREATE, System.currentTimeMillis() + 60_000L));
                    player.sendMessage(plugin.color("&eType the guild name and tag in chat: &f<name> <tag>"));
                    player.sendMessage(plugin.color("&7Example: &fMyGuild &b&lMY&r&7 (tag colors allowed)"));
                }
            }
            case MAIN -> handleMainClick(player, holder, slot);
            case MEMBERS -> handleMembersClick(player, holder, slot);
            case MEMBER_ACTIONS -> handleMemberActionClick(player, holder, slot);
            case INVITES -> handleInvitesClick(player, holder, slot);
            case BANK -> handleBankClick(player, holder, slot);
            case HOME -> handleHomeClick(player, holder, slot);
            case SETTINGS -> handleSettingsClick(player, holder, slot);
            case POWER -> {
                if (slot == 45) openMainFromHolder(player, holder);
            }
        }
    }

    private void handleMainClick(Player player, Holder holder, int slot) {
        switch (slot) {
            case 20 -> {
                async(() -> snapshot(player), snapshot -> {
                    if (snapshot != null) openMembers(player, snapshot);
                });
            }
            case 21 -> {
                async(() -> snapshot(player), snapshot -> {
                    if (snapshot != null) openInvites(player, snapshot);
                });
            }
            case 22 -> {
                async(() -> snapshot(player), snapshot -> {
                    if (snapshot != null) openBank(player, snapshot);
                });
            }
            case 23 -> {
                async(() -> snapshot(player), snapshot -> {
                    if (snapshot != null) openHome(player, snapshot);
                });
            }
            case 24 -> {
                async(() -> snapshot(player), snapshot -> {
                    if (snapshot == null) return;
                    if (snapshot.actor() == null || snapshot.actor().role() != Guild.Role.LEADER) {
                        player.sendMessage(plugin.prefixed("guild-leader-only"));
                        return;
                    }
                    openSettings(player, snapshot);
                });
            }
            case 25 -> {
                CoreSettings.Category category = settings.categoryById.get("guilds");
                if (category != null) {
                    player.closeInventory();
                    plugin.getLeaderboardGui().openCategory(player, category, 0);
                }
            }
            case 39 -> {
                async(() -> snapshot(player), snapshot -> {
                    if (snapshot != null) openPowerHelp(player, snapshot);
                });
            }
            case 40 -> {
                if (holder.actor != null && holder.actor.role() == Guild.Role.LEADER) {
                    disbandFromGui(player, holder);
                } else {
                    async(() -> guilds.leave(player.getUniqueId()), result -> {
                        if (result == GuildService.Result.LEADER_CANNOT_LEAVE) {
                            player.sendMessage(plugin.prefixed("guild-leader-only"));
                        } else {
                            player.sendMessage(plugin.prefixed("guild-left"));
                        }
                        player.closeInventory();
                    });
                }
            }
            default -> {
            }
        }
    }

    private void handleMembersClick(Player player, Holder holder, int slot) {
        int index = indexOf(innerSlots(), slot);
        if (index < 0 || index >= holder.members.size()) return;
        Guild.Member target = holder.members.get(index);
        if (target.uuid().equals(player.getUniqueId())) {
            player.sendMessage(plugin.color("&7That is you."));
            return;
        }
        openMemberActions(player, new Snapshot(holder.guild, holder.actor, holder.members), target);
    }

    private void handleMemberActionClick(Player player, Holder holder, int slot) {
        if (holder.target == null) return;
        Guild.Member target = holder.members.stream()
                .filter(member -> member.uuid().equals(holder.target)).findFirst().orElse(null);
        if (target == null) return;
        if (slot == 20) {
            async(() -> {
                if (holder.actor == null || holder.actor.role() != Guild.Role.LEADER) return null;
                return guilds.setRole(target.uuid(), Guild.Role.OFFICER);
            }, result -> {
                if (result == GuildService.Result.OK) {
                    player.sendMessage(plugin.prefixed("guild-promoted", "%player%", target.name()));
                    openMembers(player, new Snapshot(holder.guild, holder.actor, holder.members));
                } else {
                    player.sendMessage(plugin.prefixed("guild-leader-only"));
                }
            });
            return;
        }
        if (slot == 21) {
            async(() -> {
                if (holder.actor == null || holder.actor.role() != Guild.Role.LEADER) return null;
                return guilds.setRole(target.uuid(), Guild.Role.MEMBER);
            }, result -> {
                if (result == GuildService.Result.OK) {
                    player.sendMessage(plugin.prefixed("guild-demoted", "%player%", target.name()));
                    openMembers(player, new Snapshot(holder.guild, holder.actor, holder.members));
                } else {
                    player.sendMessage(plugin.prefixed("guild-leader-only"));
                }
            });
            return;
        }
        if (slot == 23) {
            async(() -> {
                if (holder.actor == null || !holder.actor.role().atLeast(Guild.Role.OFFICER)) return null;
                if (target.role() == Guild.Role.LEADER) return null;
                if (holder.actor.role() == Guild.Role.OFFICER && target.role() == Guild.Role.OFFICER) return null;
                return guilds.removeMember(target.uuid());
            }, result -> {
                if (result == GuildService.Result.OK) {
                    player.sendMessage(plugin.prefixed("guild-kicked", "%player%", target.name()));
                    Player online = Bukkit.getPlayer(target.uuid());
                    if (online != null) online.sendMessage(plugin.prefixed("guild-kick-target",
                            "%guild%", holder.guild.name()));
                    async(() -> snapshot(player), snapshot -> {
                        if (snapshot != null) openMembers(player, snapshot);
                    });
                } else {
                    player.sendMessage(plugin.prefixed("guild-admin-only"));
                }
            });
        }
    }

    private void handleInvitesClick(Player player, Holder holder, int slot) {
        int index = indexOf(innerSlots(), slot);
        if (index < 0 || index >= holder.candidates.size()) return;
        if (holder.actor == null || !holder.actor.role().atLeast(Guild.Role.OFFICER)) {
            player.sendMessage(plugin.prefixed("guild-admin-only"));
            return;
        }
        UUID target = holder.candidates.get(index);
        async(() -> guilds.invite(holder.guild.id(), target, player.getUniqueId()), result -> {
            if (result == GuildService.Result.OK) {
                Player online = Bukkit.getPlayer(target);
                player.sendMessage(plugin.prefixed("guild-invited",
                        "%player%", online == null ? target.toString() : online.getName()));
                if (online != null) online.sendMessage(plugin.prefixed("guild-invite-received",
                        "%guild%", holder.guild.name()));
            }
            player.closeInventory();
        });
    }

    private void handleBankClick(Player player, Holder holder, int slot) {
        long amount;
        boolean deposit;
        if (slot == 20) { amount = 1_000; deposit = true; }
        else if (slot == 21) { amount = 10_000; deposit = true; }
        else if (slot == 22) { amount = 100_000; deposit = true; }
        else if (slot == 29) { amount = 1_000; deposit = false; }
        else if (slot == 30) { amount = 10_000; deposit = false; }
        else if (slot == 31) { amount = 100_000; deposit = false; }
        else return;

        if (deposit) {
            if (!plugin.getEconomy().available() || !plugin.getEconomy().has(player, amount)) {
                player.sendMessage(plugin.prefixed("guild-bank-no-money"));
                return;
            }
            if (!plugin.getEconomy().withdraw(player, amount)) {
                player.sendMessage(plugin.prefixed("guild-bank-no-money"));
                return;
            }
            async(() -> guilds.depositBank(holder.guild.id(), amount), success -> {
                if (Boolean.TRUE.equals(success)) {
                    player.sendMessage(plugin.prefixed("guild-bank-deposited",
                            "%amount%", settings.money(amount)));
                    async(() -> snapshot(player), snapshot -> {
                        if (snapshot != null) openBank(player, snapshot);
                    });
                } else {
                    plugin.getEconomy().deposit(player, amount);
                    player.sendMessage(plugin.prefixed("guild-bank-no-funds"));
                }
            });
            return;
        }
        if (holder.actor == null || !holder.actor.role().atLeast(Guild.Role.OFFICER)) {
            player.sendMessage(plugin.prefixed("guild-bank-no-permission"));
            return;
        }
        async(() -> guilds.withdrawBank(holder.guild.id(), amount), success -> {
            if (!Boolean.TRUE.equals(success)) {
                player.sendMessage(plugin.prefixed("guild-bank-no-funds"));
                return;
            }
            if (!plugin.getEconomy().deposit(player, amount)) {
                Bukkit.getScheduler().runTaskAsynchronously(plugin,
                        () -> guilds.depositBank(holder.guild.id(), amount));
                player.sendMessage(plugin.prefixed("guild-bank-no-funds"));
                return;
            }
            player.sendMessage(plugin.prefixed("guild-bank-withdrew",
                    "%amount%", settings.money(amount)));
            async(() -> snapshot(player), snapshot -> {
                if (snapshot != null) openBank(player, snapshot);
            });
        });
    }

    private void handleHomeClick(Player player, Holder holder, int slot) {
        if (slot == 20) {
            Guild.Info guild = holder.guild;
            if (guild.homeWorld() == null) {
                player.sendMessage(plugin.prefixed("guild-home-none"));
                return;
            }
            World world = Bukkit.getWorld(guild.homeWorld());
            if (world == null || guild.homeX() == null || guild.homeY() == null || guild.homeZ() == null) {
                player.sendMessage(plugin.prefixed("guild-home-none"));
                return;
            }
            player.closeInventory();
            player.teleport(new Location(world, guild.homeX(), guild.homeY(), guild.homeZ(),
                    guild.homeYaw() == null ? 0 : guild.homeYaw(),
                    guild.homePitch() == null ? 0 : guild.homePitch()));
            player.sendMessage(plugin.prefixed("guild-home-teleported"));
            return;
        }
        if (slot == 24) {
            if (holder.actor == null || !holder.actor.role().atLeast(Guild.Role.OFFICER)) {
                player.sendMessage(plugin.prefixed("guild-admin-only"));
                return;
            }
            Location location = player.getLocation();
            async(() -> guilds.setHome(holder.guild.id(), location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch()), result -> {
                if (result == GuildService.Result.OK) {
                    player.sendMessage(plugin.prefixed("guild-home-set"));
                    player.closeInventory();
                } else {
                    player.sendMessage(plugin.prefixed("guild-admin-only"));
                }
            });
        }
    }

    private void handleSettingsClick(Player player, Holder holder, int slot) {
        if (holder.actor == null || holder.actor.role() != Guild.Role.LEADER) {
            player.sendMessage(plugin.prefixed("guild-leader-only"));
            return;
        }
        String[] colors = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "a", "b", "c", "d", "e", "f"};
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29};
        int index = indexOf(slots, slot);
        if (index >= 0) {
            String plain = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', holder.guild.tag()));
            async(() -> guilds.setTag(holder.guild.id(), "&" + colors[index] + plain), result -> {
                player.sendMessage(plugin.prefixed(result == GuildService.Result.OK
                        ? "guild-tag-set" : "guild-tag-invalid",
                        "%tag%", "&" + colors[index] + plain));
                async(() -> snapshot(player), snapshot -> {
                    if (snapshot != null) openSettings(player, snapshot);
                });
            });
            return;
        }
        if (slot == 31) {
            player.closeInventory();
            pendingInputs.put(player.getUniqueId(),
                    new PendingInput(InputType.TAG, System.currentTimeMillis() + 60_000L));
            player.sendMessage(plugin.color("&eType the new guild tag in chat (legacy colors allowed)."));
            return;
        }
        if (slot == 32) {
            player.closeInventory();
            pendingInputs.put(player.getUniqueId(),
                    new PendingInput(InputType.DESCRIPTION, System.currentTimeMillis() + 60_000L));
            player.sendMessage(plugin.color("&eType the guild description in chat."));
            return;
        }
        if (slot == 33) {
            async(() -> {
                guilds.setOpen(holder.guild.id(), !holder.guild.open());
                return !holder.guild.open();
            }, open -> {
                player.sendMessage(plugin.color(open ? "&aGuild is now open." : "&7Guild is now invite-only."));
                async(() -> snapshot(player), snapshot -> {
                    if (snapshot != null) openSettings(player, snapshot);
                });
            });
        }
    }

    private void disbandFromGui(Player player, Holder holder) {
        Long until = disbandConfirms.get(player.getUniqueId());
        if (until == null || until < System.currentTimeMillis()) {
            disbandConfirms.put(player.getUniqueId(), System.currentTimeMillis() + 30_000L);
            player.closeInventory();
            player.sendMessage(plugin.prefixed("guild-disband-warning", "%guild%", holder.guild.name()));
            return;
        }
        disbandConfirms.remove(player.getUniqueId());
        async(() -> guilds.disband(holder.guild.id()), result -> {
            if (result == GuildService.Result.OK) {
                player.sendMessage(plugin.prefixed("guild-disbanded", "%guild%", holder.guild.name()));
            } else {
                player.sendMessage(plugin.prefixed("guild-leader-only"));
            }
            player.closeInventory();
        });
    }

    private void openMainFromHolder(Player player, Holder holder) {
        async(() -> snapshot(player), snapshot -> {
            if (snapshot == null) openNoGuild(player);
            else openMain(player, snapshot);
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void fill(Inventory inventory) {
        ItemStack filler = item(settings.guiFiller, " ", List.of(), false);
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
    }

    private ItemStack item(Material material, String name, List<String> lore, boolean glint) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            List<String> colored = new ArrayList<>();
            for (String line : lore) colored.add(color(line));
            if (!colored.isEmpty()) meta.setLore(colored);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            if (glint) meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack head(UUID uuid, String name, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
            skull.setDisplayName(color(name));
            List<String> colored = new ArrayList<>();
            for (String line : lore) colored.add(color(line));
            skull.setLore(colored);
            skull.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(skull);
        }
        return item;
    }

    private String color(String value) {
        return plugin.color(value);
    }

    private int indexOf(int[] values, int value) {
        for (int index = 0; index < values.length; index++) if (values[index] == value) return index;
        return -1;
    }

    private <T> void async(Supplier<T> supplier, Consumer<T> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                T result = supplier.get();
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Guild GUI operation failed: " + exception.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> callback.accept(null));
            }
        });
    }

    private record Snapshot(Guild.Info guild, Guild.Member actor, List<Guild.Member> members) {
    }

    private static final class Holder implements InventoryHolder {
        private final Section section;
        private final UUID viewer;
        private final Guild.Info guild;
        private final Guild.Member actor;
        private final List<Guild.Member> members;
        private final UUID target;
        private List<UUID> candidates = List.of();
        private Inventory inventory;

        private Holder(Section section, UUID viewer, Guild.Info guild, Guild.Member actor,
                       List<Guild.Member> members, UUID target) {
            this.section = section;
            this.viewer = viewer;
            this.guild = guild;
            this.actor = actor;
            this.members = members;
            this.target = target;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
