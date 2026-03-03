package io.froststream.gitblock.diff;

import io.froststream.gitblock.model.LocationKey;
import io.froststream.gitblock.repo.RepositoryRuntime;
import io.froststream.gitblock.repo.RepositoryRuntimeManager;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

public final class DirtyTrackingListener implements Listener {
    private static final String AIR_BLOCK_DATA = Material.AIR.createBlockData().getAsString();

    private final RepositoryRuntimeManager runtimeManager;
    private final TrackingGate trackingGate;

    public DirtyTrackingListener(
            RepositoryRuntimeManager runtimeManager, TrackingGate trackingGate) {
        this.runtimeManager = runtimeManager;
        this.trackingGate = trackingGate;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        String oldState = event.getBlockReplacedState().getBlockData().getAsString();
        String newState = block.getBlockData().getAsString();
        record(block, oldState, newState);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockMultiPlace(BlockMultiPlaceEvent event) {
        for (BlockState previousState : event.getReplacedBlockStates()) {
            Block block = previousState.getLocation().getBlock();
            String oldState = previousState.getBlockData().getAsString();
            String newState = block.getBlockData().getAsString();
            record(block, oldState, newState);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        String oldState = block.getBlockData().getAsString();
        record(block, oldState, AIR_BLOCK_DATA);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block target = event.getBlock().getRelative(event.getBlockFace());
        String oldState = target.getBlockData().getAsString();
        String newState =
                switch (event.getBucket()) {
                    case LAVA_BUCKET -> Material.LAVA.createBlockData().getAsString();
                    case POWDER_SNOW_BUCKET -> Material.POWDER_SNOW.createBlockData().getAsString();
                    default -> Material.WATER.createBlockData().getAsString();
                };
        record(target, oldState, newState);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Block block = event.getBlock();
        String oldState = block.getBlockData().getAsString();
        record(block, oldState, AIR_BLOCK_DATA);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            String oldState = block.getBlockData().getAsString();
            record(block, oldState, AIR_BLOCK_DATA);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            String oldState = block.getBlockData().getAsString();
            record(block, oldState, AIR_BLOCK_DATA);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        List<Block> moved = event.getBlocks();
        for (int i = moved.size() - 1; i >= 0; i--) {
            Block source = moved.get(i);
            Block destination = source.getRelative(event.getDirection());
            String sourceState = source.getBlockData().getAsString();
            record(source, sourceState, AIR_BLOCK_DATA);
            String destinationOldState = destination.getBlockData().getAsString();
            record(destination, destinationOldState, sourceState);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        List<Block> moved = event.getBlocks();
        for (Block source : moved) {
            Block destination = source.getRelative(event.getDirection().getOppositeFace());
            String sourceState = source.getBlockData().getAsString();
            record(source, sourceState, AIR_BLOCK_DATA);
            String destinationOldState = destination.getBlockData().getAsString();
            record(destination, destinationOldState, sourceState);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        Block source = event.getBlock();
        Block destination = event.getToBlock();
        String oldState = destination.getBlockData().getAsString();
        String newState = source.getBlockData().getAsString();
        record(destination, oldState, newState);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        String oldState = block.getBlockData().getAsString();
        String newState = event.getTo().createBlockData().getAsString();
        record(block, oldState, newState);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        Block block = event.getBlock();
        String oldState = block.getBlockData().getAsString();
        String newState = event.getNewState().getBlockData().getAsString();
        record(block, oldState, newState);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        Block block = event.getBlock();
        String oldState = block.getBlockData().getAsString();
        String newState = event.getNewState().getBlockData().getAsString();
        record(block, oldState, newState);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        Block block = event.getBlock();
        String oldState = block.getBlockData().getAsString();
        String newState = event.getNewState().getBlockData().getAsString();
        record(block, oldState, newState);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        Block block = event.getBlock();
        String oldState = block.getBlockData().getAsString();
        String newState = event.getNewState().getBlockData().getAsString();
        record(block, oldState, newState);
    }

    private void record(Block block, String oldState, String newState) {
        String worldName = block.getWorld().getName();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        LocationKey key = new LocationKey(worldName, x, y, z);
        if (trackingGate.isSuppressed(key)) {
            return;
        }
        List<RepositoryRuntime> runtimes = runtimeManager.runtimesTracking(worldName, x, y, z);
        if (runtimes.isEmpty()) {
            return;
        }
        for (RepositoryRuntime runtime : runtimes) {
            runtime.dirtyMap().recordChange(key, oldState, newState);
        }
    }
}
