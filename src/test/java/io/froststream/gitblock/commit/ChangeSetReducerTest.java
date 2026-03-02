package io.froststream.gitblock.commit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.froststream.gitblock.model.BlockChangeRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChangeSetReducerTest {
    @Test
    void reduceMergesSequentialChangesOnSameLocation() {
        List<BlockChangeRecord> reduced =
                ChangeSetReducer.reduce(
                        List.of(
                                new BlockChangeRecord("world", 1, 2, 3, "minecraft:stone", "minecraft:dirt"),
                                new BlockChangeRecord("world", 1, 2, 3, "minecraft:dirt", "minecraft:glass")));

        assertEquals(1, reduced.size());
        BlockChangeRecord merged = reduced.get(0);
        assertEquals("minecraft:stone", merged.oldState());
        assertEquals("minecraft:glass", merged.newState());
    }

    @Test
    void reduceDropsCanceledChanges() {
        List<BlockChangeRecord> reduced =
                ChangeSetReducer.reduce(
                        List.of(
                                new BlockChangeRecord("world", 1, 2, 3, "minecraft:stone", "minecraft:dirt"),
                                new BlockChangeRecord("world", 1, 2, 3, "minecraft:dirt", "minecraft:stone")));

        assertTrue(reduced.isEmpty());
    }

    @Test
    void reduceKeepsEncounterOrderForDifferentLocations() {
        List<BlockChangeRecord> reduced =
                ChangeSetReducer.reduce(
                        List.of(
                                new BlockChangeRecord("world", 10, 64, 10, "a", "b"),
                                new BlockChangeRecord("world", 20, 64, 20, "c", "d"),
                                new BlockChangeRecord("world", 10, 64, 10, "b", "e")));

        assertEquals(2, reduced.size());
        assertEquals(10, reduced.get(0).x());
        assertEquals("a", reduced.get(0).oldState());
        assertEquals("e", reduced.get(0).newState());
        assertEquals(20, reduced.get(1).x());
    }
}
