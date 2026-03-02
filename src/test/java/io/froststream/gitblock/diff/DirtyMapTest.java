package io.froststream.gitblock.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.froststream.gitblock.model.DirtyEntry;
import io.froststream.gitblock.model.LocationKey;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DirtyMapTest {
    private static final LocationKey KEY = new LocationKey("world", 1, 64, 1);

    @Test
    void recordChangeMergesLatestState() {
        DirtyMap dirtyMap = new DirtyMap();

        dirtyMap.recordChange(KEY, "minecraft:stone", "minecraft:dirt");
        dirtyMap.recordChange(KEY, "minecraft:dirt", "minecraft:glass");

        Map<LocationKey, DirtyEntry> drained = dirtyMap.drainAll();
        DirtyEntry entry = drained.get(KEY);
        assertEquals(1, drained.size());
        assertEquals("minecraft:stone", entry.originalState());
        assertEquals("minecraft:glass", entry.latestState());
    }

    @Test
    void recordChangeDropsEntryWhenStateReturnsToOriginal() {
        DirtyMap dirtyMap = new DirtyMap();

        dirtyMap.recordChange(KEY, "minecraft:stone", "minecraft:dirt");
        dirtyMap.recordChange(KEY, "minecraft:dirt", "minecraft:stone");

        assertEquals(0, dirtyMap.size());
        assertTrue(dirtyMap.drainAll().isEmpty());
    }

    @Test
    void restoreAllMergesWithExistingEntries() {
        DirtyMap dirtyMap = new DirtyMap();

        dirtyMap.recordChange(KEY, "minecraft:stone", "minecraft:dirt");
        dirtyMap.restoreAll(Map.of(KEY, new DirtyEntry("minecraft:grass_block", "minecraft:glass")));

        DirtyEntry entry = dirtyMap.drainAll().get(KEY);
        assertEquals("minecraft:stone", entry.originalState());
        assertEquals("minecraft:glass", entry.latestState());
    }

    @Test
    void restoreAllDropsEntryWhenRecoveryRestoresOriginalState() {
        DirtyMap dirtyMap = new DirtyMap();

        dirtyMap.recordChange(KEY, "minecraft:stone", "minecraft:dirt");
        dirtyMap.restoreAll(Map.of(KEY, new DirtyEntry("minecraft:glass", "minecraft:stone")));

        assertEquals(0, dirtyMap.size());
    }
}
