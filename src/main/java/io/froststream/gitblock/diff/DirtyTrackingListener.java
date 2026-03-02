package io.froststream.gitblock.diff;

import io.froststream.gitblock.model.LocationKey;
import io.froststream.gitblock.repo.RepositoryState;
import io.froststream.gitblock.repo.RepositoryStateService;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

public final class DirtyTrackingListener implements Listener {
    private static final String AIR_BLOCK_DATA = Material.AIR.createBlockData().getAsString();

    private final DirtyMap dirtyMap;
    private final RepositoryStateService repositoryStateService;
    private final TrackingGate trackingGate;

    public DirtyTrackingListener(
            DirtyMap dirtyMap, RepositoryStateService repositoryStateService, TrackingGate trackingGate) {
        this.dirtyMap = dirtyMap;
        this.repositoryStateService = repositoryStateService;
        this.trackingGate = trackingGate;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        RepositoryState state = trackableState();
        if (state == null) {
            return;
        }
        Block block = event.getBlockPlaced();
        if (!state.tracks(block)) {
            return;
        }
        String oldState = event.getBlockReplacedState().getBlockData().getAsString();
        String newState = block.getBlockData().getAsString();
        record(state, block, oldState, newState);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockMultiPlace(BlockMultiPlaceEvent event) {
        RepositoryState state = trackableState();
        if (state == null) {
            return;
        }
        for (BlockState previousState : event.getReplacedBlockStates()) {
            Block block = previousState.getLocation().getBlock();
            if (!state.tracks(block)) {
                continue;
            }
            String oldState = previousState.getBlockData().getAsString();
            String newState = block.getBlockData().getAsString();
            record(state, block, oldState, newState);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        RepositoryState state = trackableState();
        if (state == null) {
            return;
        }
        Block block = event.getBlock();
        if (!state.tracks(block)) {
            return;
        }
        String oldState = block.getBlockData().getAsString();
        record(state, block, oldState, AIR_BLOCK_DATA);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        RepositoryState state = trackableState();
        if (state == null) {
            return;
        }
        Block target = event.getBlock().getRelative(event.getBlockFace());
        if (!state.tracks(target)) {
            return;
        }
        String oldState = target.getBlockData().getAsString();
        String newState =
                switch (event.getBucket()) {
                    case LAVA_BUCKET -> Material.LAVA.createBlockData().getAsString();
                    case POWDER_SNOW_BUCKET -> Material.POWDER_SNOW.createBlockData().getAsString();
                    default -> Material.WATER.createBlockData().getAsString();
                };
        record(state, target, oldState, newState);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        RepositoryState state = trackableState();
        if (state == null) {
            return;
        }
        Block block = event.getBlock();
        if (!state.tracks(block)) {
            return;
        }
        String oldState = block.getBlockData().getAsString();
        record(state, block, oldState, AIR_BLOCK_DATA);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        RepositoryState state = trackableState();
        if (state == null) {
            return;
        }
        for (Block block : event.blockList()) {
            if (!state.tracks(block)) {
                continue;
            }
            String oldState = block.getBlockData().getAsString();
            record(state, block, oldState, AIR_BLOCK_DATA);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        RepositoryState state = trackableState();
        if (state == null) {
            return;
        }
        for (Block block : event.blockList()) {
            if (!state.tracks(block)) {
                continue;
            }
            String oldState = block.getBlockData().getAsString();
            record(state, block, oldState, AIR_BLOCK_DATA);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        RepositoryState state = trackableState();
        if (state == null) {
            return;
        }
        List<Block> moved = event.getBlocks();
        for (int i = moved.size() - 1; i >= 0; i--) {
            Block source = moved.get(i);
            Block destination = source.getRelative(event.getDirection());
            boolean trackSource = state.tracks(source);
            boolean trackDestination = state.tracks(destination);
            if (!trackSource && !trackDestination) {
                continue;
            }
            String sourceState = source.getBlockData().getAsString();
            if (trackSource) {
                record(state, source, sourceState, AIR_BLOCK_DATA);
            }
            if (trackDestination) {
                String destinationOldState = destination.getBlockData().getAsString();
                record(state, destination, destinationOldState, sourceState);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        RepositoryState state = trackableState();
        if (state == null) {
            return;
        }
        List<Block> moved = event.getBlocks();
        for (Block source : moved) {
            Block destination = source.getRelative(event.getDirection().getOppositeFace());
            boolean trackSource = state.tracks(source);
            boolean trackDestination = state.tracks(destination);
            if (!trackSource && !trackDestination) {
                continue;
            }
            String sourceState = source.getBlockData().getAsString();
            if (trackSource) {
                record(state, source, sourceState, AIR_BLOCK_DATA);
            }
            if (trackDestination) {
                String destinationOldState = destination.getBlockData().getAsString();
                record(state, destination, destinationOldState, sourceState);
            }
        }
    }

    private RepositoryState trackableState() {
        if (trackingGate.isSuppressed()) {
            return null;
        }
        RepositoryState state = repositoryStateService.getState();
        if (!state.initialized() || state.region() == null) {
            return null;
        }
        return state;
    }

    private void record(RepositoryState state, Block block, String oldState, String newState) {
        if (!state.tracks(block)) {
            return;
        }
        dirtyMap.recordChange(
                new LocationKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()),
                oldState,
                newState);
    }
}
