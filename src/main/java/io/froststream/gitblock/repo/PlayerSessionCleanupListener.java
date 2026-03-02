package io.froststream.gitblock.repo;

import io.froststream.gitblock.command.HistoryCommandHandler;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerSessionCleanupListener implements Listener {
    private final SelectionService selectionService;
    private final HistoryCommandHandler historyCommandHandler;

    public PlayerSessionCleanupListener(
            SelectionService selectionService, HistoryCommandHandler historyCommandHandler) {
        this.selectionService = selectionService;
        this.historyCommandHandler = historyCommandHandler;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer());
    }

    @EventHandler
    public void onPlayerKick(PlayerKickEvent event) {
        cleanup(event.getPlayer());
    }

    private void cleanup(Player player) {
        selectionService.clear(player.getUniqueId());
        historyCommandHandler.clearDiffCooldown(player.getName());
    }
}
