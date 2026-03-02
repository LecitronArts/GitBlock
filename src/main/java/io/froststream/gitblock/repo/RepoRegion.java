package io.froststream.untitled8.plotgit.repo;

import org.bukkit.Location;
import org.bukkit.block.Block;

public record RepoRegion(
        String world,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ) {
    public static RepoRegion fromLocations(String world, Location first, Location second) {
        return new RepoRegion(
                world,
                Math.min(first.getBlockX(), second.getBlockX()),
                Math.min(first.getBlockY(), second.getBlockY()),
                Math.min(first.getBlockZ(), second.getBlockZ()),
                Math.max(first.getBlockX(), second.getBlockX()),
                Math.max(first.getBlockY(), second.getBlockY()),
                Math.max(first.getBlockZ(), second.getBlockZ()));
    }

    public boolean contains(Block block) {
        return contains(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public boolean contains(String blockWorld, int x, int y, int z) {
        if (!world.equals(blockWorld)) {
            return false;
        }
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public long volume() {
        long sizeX = (long) maxX - minX + 1L;
        long sizeY = (long) maxY - minY + 1L;
        long sizeZ = (long) maxZ - minZ + 1L;
        return sizeX * sizeY * sizeZ;
    }
}
