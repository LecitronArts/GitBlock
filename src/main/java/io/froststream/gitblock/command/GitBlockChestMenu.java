package io.froststream.gitblock.command;

import io.froststream.gitblock.repo.RepositoryRuntime;
import io.froststream.gitblock.repo.RepositoryState;
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
        RepositoryRuntime runtime = env.runtime(player);
        if (runtime == null) {
            return;
        }
        RepositoryState state = runtime.repositoryStateService().getState();
        int dirtyCount = runtime.dirtyMap().size();
        String quickCommitMessage = env.resolveCommitMessage(player, runtime, dirtyCount);
        String customTemplate = env.playerCommitMessageTemplate(player);

        MenuHolder holder = new MenuHolder(quickCommitMessage);
        Inventory inventory =
                Bukkit.createInventory(holder, GitBlockMenuModel.MENU_SIZE, env.trRaw(player, "menu.title"));
        holder.bind(inventory);

        fillBackground(inventory);
        inventory.setItem(
                GitBlockMenuModel.SLOT_REPOS,
                actionItem(
                        player,
                        Material.BARREL,
                        "menu.item.repos",
                        List.of(
                                env.trRaw(player, "menu.dynamic.repo", runtime.repositoryName()),
                                env.trRaw(
                                        player,
                                        "menu.dynamic.repo-usage",
                                        env.ownedRepositories(player),
                                        env.maxRepositories(player)))));
        inventory.setItem(
                GitBlockMenuModel.SLOT_STATUS,
                actionItem(
                        player,
                        Material.PAPER,
                        "menu.item.status",
                        List.of(
                                env.trRaw(player, "menu.dynamic.repo", runtime.repositoryName()),
                                env.trRaw(player, "menu.dynamic.branch", state.currentBranch()),
                                env.trRaw(
                                        player,
                                        "menu.dynamic.head",
                                        state.activeCommitId() == null ? env.trRaw(player, "common.none") : state.activeCommitId()))));
        inventory.setItem(
                GitBlockMenuModel.SLOT_LOG,
                actionItem(
                        player,
                        Material.BOOK,
                        "menu.item.log",
                        List.of(
                                env.trRaw(
                                        player,
                                        "menu.dynamic.latest",
                                        latestCommit(runtime, player)))));
        inventory.setItem(
                GitBlockMenuModel.SLOT_BRANCHES,
                actionItem(
                        player,
                        Material.CHEST,
                        "menu.item.branches",
                        List.of(
                                env.trRaw(player, "menu.dynamic.branches", state.branchHeads().size()),
                                env.trRaw(player, "menu.dynamic.branch", state.currentBranch()))));
        inventory.setItem(
                GitBlockMenuModel.SLOT_COMMIT,
                actionItem(
                        player,
                        Material.EMERALD_BLOCK,
                        "menu.item.commit",
                        List.of(
                                env.trRaw(player, "menu.dynamic.dirty", dirtyCount),
                                env.trRaw(player, "menu.dynamic.quick-message", quickCommitMessage))));
        inventory.setItem(
                GitBlockMenuModel.SLOT_JOBS,
                actionItem(
                        player,
                        Material.CLOCK,
                        "menu.item.jobs",
                        List.of(env.trRaw(player, "menu.dynamic.queued-jobs", env.applyScheduler().queuedJobs()))));
        inventory.setItem(
                GitBlockMenuModel.SLOT_CHECKPOINT,
                actionItem(
                        player,
                        Material.ENDER_CHEST,
                        "menu.item.checkpoint",
                        List.of(
                                env.trRaw(
                                        player,
                                        "menu.dynamic.head",
                                        state.activeCommitId() == null ? env.trRaw(player, "common.none") : state.activeCommitId()))));
        inventory.setItem(
                GitBlockMenuModel.SLOT_COMMIT_MESSAGE,
                actionItem(
                        player,
                        Material.NAME_TAG,
                        "menu.item.commit-message",
                        List.of(
                                env.trRaw(
                                        player,
                                        "menu.dynamic.commit-template-mode",
                                        customTemplate == null
                                                ? env.trRaw(player, "menu.dynamic.template-default")
                                                : env.trRaw(player, "menu.dynamic.template-custom")),
                                env.trRaw(
                                        player,
                                        "menu.dynamic.commit-template-preview",
                                        customTemplate == null
                                                ? env.defaultCommitMessageTemplate()
                                                : customTemplate))));
        inventory.setItem(
                GitBlockMenuModel.SLOT_CHECKOUT_HELP,
                actionItem(
                        player,
                        Material.COMPASS,
                        "menu.item.checkout",
                        List.of(env.trRaw(player, "menu.dynamic.head", env.nullable(state.activeCommitId())))));
        inventory.setItem(
                GitBlockMenuModel.SLOT_DIFF_HELP,
                actionItem(
                        player,
                        Material.SPYGLASS,
                        "menu.item.diff",
                        List.of(env.trRaw(player, "menu.dynamic.branch", state.currentBranch()))));
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
        return actionItem(player, material, keyPrefix, List.of());
    }

    private ItemStack actionItem(
            Player player, Material material, String keyPrefix, List<String> extraLoreLines) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        String nameKey = keyPrefix + ".name";
        String lore1Key = keyPrefix + ".lore1";
        String lore2Key = keyPrefix + ".lore2";

        meta.setDisplayName(env.trRaw(player, nameKey));
        List<String> lore = new ArrayList<>(2 + extraLoreLines.size());
        String lore1 = env.trRaw(player, lore1Key);
        String lore2 = env.trRaw(player, lore2Key);
        if (!lore1.equals(lore1Key)) {
            lore.add(lore1);
        }
        if (!lore2.equals(lore2Key)) {
            lore.add(lore2);
        }
        for (String line : extraLoreLines) {
            if (line != null && !line.isBlank()) {
                lore.add(line);
            }
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

    private String latestCommit(RepositoryRuntime runtime, Player player) {
        List<io.froststream.gitblock.model.CommitSummary> commits = runtime.commitWorker().tail(1);
        if (commits.isEmpty()) {
            return env.trRaw(player, "common.none");
        }
        return commits.get(0).commitId();
    }

    private enum MenuAction {
        REPOS(GitBlockMenuModel.SLOT_REPOS) {
            @Override
            void execute(GitBlockChestMenu menu, Player player) {
                menu.runCommand(player, "gitblock repo list");
            }
        },
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
                RepositoryRuntime runtime = menu.env.runtime(player);
                if (runtime == null) {
                    return;
                }
                RepositoryState state = runtime.repositoryStateService().getState();
                if (!state.initialized()) {
                    menu.env.send(player, "menu.dynamic.uninitialized");
                    return;
                }
                if (menu.env.isApplyQueueBusy()) {
                    menu.env.send(player, "menu.dynamic.queue-busy");
                    return;
                }
                if (runtime.dirtyMap().size() == 0) {
                    menu.env.send(player, "menu.dynamic.no-dirty");
                    return;
                }
                String message =
                        menu.env.resolveCommitMessage(player, runtime, runtime.dirtyMap().size());
                menu.runCommand(player, "gitblock commit " + message);
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
                if (!menu.env.hasAdminPermission(player)) {
                    menu.env.send(player, "menu.admin-required");
                    return;
                }
                menu.runCommand(player, "gitblock checkpoint now");
            }
        },
        COMMIT_MESSAGE(GitBlockMenuModel.SLOT_COMMIT_MESSAGE) {
            @Override
            void execute(GitBlockChestMenu menu, Player player) {
                menu.runCommand(player, "gitblock commitmsg show");
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
                Map.ofEntries(
                        Map.entry(GitBlockMenuModel.SLOT_REPOS, REPOS),
                        Map.entry(GitBlockMenuModel.SLOT_STATUS, STATUS),
                        Map.entry(GitBlockMenuModel.SLOT_LOG, LOG),
                        Map.entry(GitBlockMenuModel.SLOT_BRANCHES, BRANCHES),
                        Map.entry(GitBlockMenuModel.SLOT_COMMIT, COMMIT),
                        Map.entry(GitBlockMenuModel.SLOT_JOBS, JOBS),
                        Map.entry(GitBlockMenuModel.SLOT_CHECKPOINT, CHECKPOINT),
                        Map.entry(GitBlockMenuModel.SLOT_COMMIT_MESSAGE, COMMIT_MESSAGE),
                        Map.entry(GitBlockMenuModel.SLOT_CHECKOUT_HELP, CHECKOUT_HELP),
                        Map.entry(GitBlockMenuModel.SLOT_DIFF_HELP, DIFF_HELP),
                        Map.entry(GitBlockMenuModel.SLOT_CLOSE, CLOSE));

        MenuAction(int slot) {}

        abstract void execute(GitBlockChestMenu menu, Player player);
    }

    private static final class MenuHolder implements InventoryHolder {
        private final String quickCommitMessage;
        private Inventory inventory;

        private MenuHolder(String quickCommitMessage) {
            this.quickCommitMessage = quickCommitMessage;
        }

        private void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        private String quickCommitMessage() {
            return quickCommitMessage;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
