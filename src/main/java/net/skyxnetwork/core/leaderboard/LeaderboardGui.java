package net.skyxnetwork.core.leaderboard;

import net.skyxnetwork.core.SkyXCore;
import net.skyxnetwork.core.core.CoreSettings;
import net.skyxnetwork.core.core.PlayerRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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
import java.util.UUID;

/** Category and paginated head-based leaderboard menus. */
public final class LeaderboardGui implements Listener {

    private static final int[] ENTRY_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final int VIEWER_SLOT = 40;
    private static final int PREVIOUS_SLOT = 45;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_SLOT = 53;

    private final SkyXCore plugin;
    private final LeaderboardService service;
    private final PlayerRegistry players;
    private volatile CoreSettings settings;

    public LeaderboardGui(SkyXCore plugin, LeaderboardService service,
                          PlayerRegistry players, CoreSettings settings) {
        this.plugin = plugin;
        this.service = service;
        this.players = players;
        this.settings = settings;
    }

    public void updateSettings(CoreSettings settings) {
        this.settings = settings;
    }

    public void openCategories(Player player) {
        Holder holder = new Holder(Holder.Type.CATEGORIES, player.getUniqueId(), null, 0);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                plugin.color(settings.guiTitleCategories));
        holder.inventory = inventory;
        ItemStack filler = pane(settings.guiFiller, " ", List.of(), false);
        for (int slot = 0; slot < 54; slot++) inventory.setItem(slot, filler);

        int index = 0;
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (CoreSettings.Category category : settings.categories) {
            if (index >= slots.length) break;
            List<String> lore = List.of(
                    plugin.color("&7Click to open this leaderboard."),
                    plugin.color("&8Category: &7" + category.id()));
            inventory.setItem(slots[index++], item(category.icon(),
                    "&e" + category.display(), lore, false));
        }
        inventory.setItem(CLOSE_SLOT, item(settings.guiClose,
                "&cClose", List.of(), false));
        player.openInventory(inventory);
    }

    public void openCategory(Player player, CoreSettings.Category category, int page) {
        player.sendMessage(plugin.prefixed("loading"));
        service.fetch(category, page, player.getUniqueId(), data -> {
            if (!player.isOnline()) return;
            openCategoryPage(player, category, data);
        });
    }

    private void openCategoryPage(Player player, CoreSettings.Category category,
                                  LeaderboardData.Page data) {
        Holder holder = new Holder(Holder.Type.CATEGORY, player.getUniqueId(), category.id(), data.page());
        String title = settings.guiTitleCategory.replace("%category%", category.display());
        Inventory inventory = Bukkit.createInventory(holder, 54, plugin.color(title));
        holder.inventory = inventory;

        ItemStack filler = pane(settings.guiFiller, " ", List.of(), false);
        for (int slot = 0; slot < 54; slot++) inventory.setItem(slot, filler);

        List<String> headerLore = new ArrayList<>();
        headerLore.add(plugin.color(settings.message("leaderboard-category",
                "%category%", category.display())));
        headerLore.add(plugin.color(settings.message("leaderboard-page",
                "%page%", Integer.toString(data.page() + 1),
                "%pages%", Integer.toString(data.pages()))));
        if (data.viewer() != null) {
            headerLore.add(plugin.color(settings.message("leaderboard-yours",
                    "%rank%", Integer.toString(data.viewer().rank()),
                    "%value%", settings.format(data.viewer().value(), category.format()))));
        } else {
            headerLore.add(plugin.color(settings.message("leaderboard-not-ranked")));
        }
        inventory.setItem(4, item(category.icon(), "&d&l" + category.display(), headerLore, false));

        boolean viewerOnPage = false;
        for (int index = 0; index < data.entries().size() && index < ENTRY_SLOTS.length; index++) {
            LeaderboardData.Entry entry = data.entries().get(index);
            if (data.viewer() != null && entry.rank() == data.viewer().rank()
                    && entry.name().equalsIgnoreCase(data.viewer().name())) {
                viewerOnPage = true;
            }
            inventory.setItem(ENTRY_SLOTS[index], entryItem(entry, category, false));
        }

        if (!viewerOnPage && data.viewer() != null) {
            inventory.setItem(VIEWER_SLOT, entryItem(data.viewer(), category, true));
        }

        if (data.page() > 0) {
            inventory.setItem(PREVIOUS_SLOT, item(settings.guiPrevious,
                    "&ePrevious page", List.of(), false));
        }
        if (data.page() < data.pages() - 1) {
            inventory.setItem(NEXT_SLOT, item(settings.guiNext,
                    "&eNext page", List.of(), false));
        }
        inventory.setItem(CLOSE_SLOT, item(settings.guiClose,
                "&cClose", List.of(), false));
        player.openInventory(inventory);
    }

    private ItemStack entryItem(LeaderboardData.Entry entry, CoreSettings.Category category,
                                boolean viewerRow) {
        Material material = entry.guild() ? Material.WHITE_BANNER : Material.PLAYER_HEAD;
        String color = viewerRow ? "&7" : "&e";
        String name = color + "#" + entry.rank() + " &f" + entry.name();
        List<String> lore = new ArrayList<>();
        lore.add(plugin.color("&8&m--------------------"));
        lore.add(plugin.color("&7" + (entry.guild() ? "Guild" : "Player") + ": &f" + entry.name()));
        lore.add(plugin.color("&7Value: &e" + settings.format(entry.value(), category.format())));
        lore.add(plugin.color("&7Rank: &e#" + entry.rank()));
        if (viewerRow) lore.add(plugin.color("&8Your position"));
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.color(name));
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            if (meta instanceof SkullMeta skull && entry.uuid() != null) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(entry.uuid()));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack item(Material material, String name, List<String> lore, boolean glint) {
        return pane(material, name, lore, glint);
    }

    private ItemStack pane(Material material, String name, List<String> lore, boolean glint) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.color(name));
            if (!lore.isEmpty()) meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            if (glint) meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            item.setItemMeta(meta);
        }
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        if (holder.type == Holder.Type.CATEGORIES) {
            if (slot == CLOSE_SLOT) {
                player.closeInventory();
                return;
            }
            int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
            int index = indexOf(slots, slot);
            if (index < 0 || index >= settings.categories.size()) return;
            openCategory(player, settings.categories.get(index), 0);
            return;
        }

        CoreSettings.Category category = settings.categoryById.get(holder.categoryId);
        if (category == null) {
            player.closeInventory();
            return;
        }
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == PREVIOUS_SLOT) {
            openCategory(player, category, Math.max(0, holder.page - 1));
            return;
        }
        if (slot == NEXT_SLOT) {
            openCategory(player, category, holder.page + 1);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private int indexOf(int[] values, int value) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == value) return index;
        }
        return -1;
    }

    private static final class Holder implements InventoryHolder {
        private enum Type { CATEGORIES, CATEGORY }

        private final Type type;
        private final UUID viewer;
        private final String categoryId;
        private final int page;
        private Inventory inventory;

        private Holder(Type type, UUID viewer, String categoryId, int page) {
            this.type = type;
            this.viewer = viewer;
            this.categoryId = categoryId;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
