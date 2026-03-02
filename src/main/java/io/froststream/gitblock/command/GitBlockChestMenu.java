package io.froststream.gitblock.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class GitBlockChestMenu implements Listener {
    private final GitBlockCommandEnv env;

    public GitBlockChestMenu(GitBlockCommandEnv env) {
        this.env = env;
    }

    public void open(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            env.send(sender, "common.player-only");
            return;
        }
        open(player);
    }

    public void open(Player player) {
        MenuHolder holder = new MenuHolder();
        Inventory inventory =
                Bukkit.createInventory(holder, GitBlockMenuModel.MENU_SIZE, env.trRaw(player, "menu.title"));
        holder.bind(inventory);

        fillBackground(inventory);
        inventory.setItem(GitBlockMenuModel.SLOT_STATUS, actionItem(player, Material.PAPER, "menu.item.status"));
        inventory.setItem(GitBlockMenuModel.SLOT_LOG, actionItem(player, Material.BOOK, "menu.item.log"));
        inventory.setItem(
                GitBlockMenuModel.SLOT_BRANCHES, actionItem(player, Material.CHEST, "menu.item.branches"));
        inventory.setItem(
                GitBlockMenuModel.SLOT_COMMIT, actionItem(player, Material.EMERALD_BLOCK, "menu.item.commit"));
        inventory.setItem(GitBlockMenuModel.SLOT_JOBS, actionItem(player, Material.CLOCK, "menu.item.jobs"));
        inventory.setItem(
                GitBlockMenuModel.SLOT_CHECKPOINT,
                actionItem(player, Material.ENDER_CHEST, "menu.item.checkpoint"));
        inventory.setItem(
                GitBlockMenuModel.SLOT_CHECKOUT_HELP,
                actionItem(player, Material.COMPASS, "menu.item.checkout"));
        inventory.setItem(
                GitBlockMenuModel.SLOT_DIFF_HELP, actionItem(player, Material.SPYGLASS, "menu.item.diff"));
        inventory.setItem(
                GitBlockMenuModel.SLOT_CLOSE, actionItem(player, Material.BARRIER, "menu.item.close"));

        player.openInventory(inventory);
        env.send(player, "menu.opened");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder)) {
            return;
        }

        // GitBlock menu is a control panel, not a storage container.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= top.getSize()) {
            return;
        }
        if (!GitBlockMenuModel.isInteractiveSlot(rawSlot)) {
            return;
        }

        MenuAction action = MenuAction.BY_SLOT.get(rawSlot);
        if (action == null) {
            return;
        }
        action.execute(this, player);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder)) {
            return;
        }
        event.setCancelled(true);
    }

    private void fillBackground(Inventory inventory) {
        ItemStack panel = decorativePanel(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < GitBlockMenuModel.MENU_SIZE; slot++) {
            inventory.setItem(slot, panel);
        }
        inventory.setItem(
                GitBlockMenuModel.SLOT_DECORATIVE_1, decorativePanel(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " "));
        inventory.setItem(
                GitBlockMenuModel.SLOT_DECORATIVE_2, decorativePanel(Material.BLUE_STAINED_GLASS_PANE, " "));
        inventory.setItem(
                GitBlockMenuModel.SLOT_DECORATIVE_3, decorativePanel(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " "));
        inventory.setItem(
                GitBlockMenuModel.SLOT_DECORATIVE_4, decorativePanel(Material.BLUE_STAINED_GLASS_PANE, " "));
        inventory.setItem(
                GitBlockMenuModel.SLOT_DECORATIVE_5, decorativePanel(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " "));
        inventory.setItem(
                GitBlockMenuModel.SLOT_DECORATIVE_6, decorativePanel(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " "));
        inventory.setItem(
                GitBlockMenuModel.SLOT_DECORATIVE_7, decorativePanel(Material.BLUE_STAINED_GLASS_PANE, " "));
        inventory.setItem(
                GitBlockMenuModel.SLOT_DECORATIVE_8, decorativePanel(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " "));
        inventory.setItem(
                GitBlockMenuModel.SLOT_DECORATIVE_9, decorativePanel(Material.BLUE_STAINED_GLASS_PANE, " "));
        inventory.setItem(
                GitBlockMenuModel.SLOT_DECORATIVE_10, decorativePanel(Material.LIGHT_BLUE_STAINED_GLASS_PANE, " "));
    }

    private ItemStack decorativePanel(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private ItemStack actionItem(Player player, Material material, String keyPrefix) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        String nameKey = keyPrefix + ".name";
        String lore1Key = keyPrefix + ".lore1";
        String lore2Key = keyPrefix + ".lore2";

        meta.setDisplayName(env.trRaw(player, nameKey));
        List<String> lore = new ArrayList<>(2);
        String lore1 = env.trRaw(player, lore1Key);
        String lore2 = env.trRaw(player, lore2Key);
        if (!lore1.equals(lore1Key)) {
            lore.add(lore1);
        }
        if (!lore2.equals(lore2Key)) {
            lore.add(lore2);
        }
        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private void runCommand(Player player, String command) {
        player.closeInventory();
        env.send(player, "menu.click-run", command);
        Bukkit.getScheduler()
                .runTask(
                        env.plugin(),
                        () -> player.performCommand(command.startsWith("/") ? command.substring(1) : command));
    }

    private enum MenuAction {
        STATUS(GitBlockMenuModel.SLOT_STATUS) {
            @Override
            void execute(GitBlockChestMenu menu, Player player) {
                menu.runCommand(player, "gitblock status");
            }
        },
        LOG(GitBlockMenuModel.SLOT_LOG) {
            @Override
            void execute(GitBlockChestMenu menu, Player player) {
                menu.runCommand(player, "gitblock log 10");
            }
        },
        BRANCHES(GitBlockMenuModel.SLOT_BRANCHES) {
            @Override
            void execute(GitBlockChestMenu menu, Player player) {
                menu.runCommand(player, "gitblock branches");
            }
        },
        COMMIT(GitBlockMenuModel.SLOT_COMMIT) {
            @Override
            void execute(GitBlockChestMenu menu, Player player) {
                menu.runCommand(player, "gitblock commit gui quick commit");
            }
        },
        JOBS(GitBlockMenuModel.SLOT_JOBS) {
            @Override
            void execute(GitBlockChestMenu menu, Player player) {
                menu.runCommand(player, "gitblock jobs");
            }
        },
        CHECKPOINT(GitBlockMenuModel.SLOT_CHECKPOINT) {
            @Override
            void execute(GitBlockChestMenu menu, Player player) {
                if (!player.hasPermission("gitblock.admin")) {
                    menu.env.send(player, "menu.admin-required");
                    return;
                }
                menu.runCommand(player, "gitblock checkpoint now");
            }
        },
        CHECKOUT_HELP(GitBlockMenuModel.SLOT_CHECKOUT_HELP) {
            @Override
            void execute(GitBlockChestMenu menu, Player player) {
                menu.env.send(player, "menu.checkout-tip");
            }
        },
        DIFF_HELP(GitBlockMenuModel.SLOT_DIFF_HELP) {
            @Override
            void execute(GitBlockChestMenu menu, Player player) {
                menu.env.send(player, "menu.diff-tip");
            }
        },
        CLOSE(GitBlockMenuModel.SLOT_CLOSE) {
            @Override
            void execute(GitBlockChestMenu menu, Player player) {
                player.closeInventory();
            }
        };

        private static final Map<Integer, MenuAction> BY_SLOT =
                Map.of(
                        GitBlockMenuModel.SLOT_STATUS, STATUS,
                        GitBlockMenuModel.SLOT_LOG, LOG,
                        GitBlockMenuModel.SLOT_BRANCHES, BRANCHES,
                        GitBlockMenuModel.SLOT_COMMIT, COMMIT,
                        GitBlockMenuModel.SLOT_JOBS, JOBS,
                        GitBlockMenuModel.SLOT_CHECKPOINT, CHECKPOINT,
                        GitBlockMenuModel.SLOT_CHECKOUT_HELP, CHECKOUT_HELP,
                        GitBlockMenuModel.SLOT_DIFF_HELP, DIFF_HELP,
                        GitBlockMenuModel.SLOT_CLOSE, CLOSE);

        MenuAction(int slot) {}

        abstract void execute(GitBlockChestMenu menu, Player player);
    }

    private static final class MenuHolder implements InventoryHolder {
        private Inventory inventory;

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
