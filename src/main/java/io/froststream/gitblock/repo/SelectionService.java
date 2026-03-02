package io.froststream.gitblock.repo;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class SelectionService {
    private final Map<UUID, Location> pos1ByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Location> pos2ByPlayer = new ConcurrentHashMap<>();

    public void setPos1(Player player, Location location) {
        pos1ByPlayer.put(player.getUniqueId(), location.toBlockLocation());
    }

    public void setPos2(Player player, Location location) {
        pos2ByPlayer.put(player.getUniqueId(), location.toBlockLocation());
    }

    public SelectedRegion get(Player player) {
        return new SelectedRegion(
                pos1ByPlayer.get(player.getUniqueId()),
                pos2ByPlayer.get(player.getUniqueId()));
    }

    public void clear(Player player) {
        if (player == null) {
            return;
        }
        clear(player.getUniqueId());
    }

    public void clear(UUID playerId) {
        if (playerId == null) {
            return;
        }
        pos1ByPlayer.remove(playerId);
        pos2ByPlayer.remove(playerId);
    }

    public record SelectedRegion(Location pos1, Location pos2) {
        public boolean complete() {
            return pos1 != null && pos2 != null;
        }
    }
}
