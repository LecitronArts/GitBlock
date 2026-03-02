package io.froststream.gitblock.checkout;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.froststream.gitblock.model.BlockChangeRecord;
import org.junit.jupiter.api.Test;

class ApplyPreconditionsTest {
    @Test
    void returnsAlreadyAtTargetWhenCurrentEqualsNewState() {
        BlockChangeRecord change =
                new BlockChangeRecord("world", 1, 2, 3, "minecraft:stone", "minecraft:glass");

        ApplyPreconditions.Decision decision =
                ApplyPreconditions.decide("minecraft:glass", change);

        assertEquals(ApplyPreconditions.Decision.ALREADY_AT_TARGET, decision);
    }

    @Test
    void returnsPreconditionFailedWhenCurrentDiffersFromOldAndNew() {
        BlockChangeRecord change =
                new BlockChangeRecord("world", 1, 2, 3, "minecraft:stone", "minecraft:glass");

        ApplyPreconditions.Decision decision =
                ApplyPreconditions.decide("minecraft:dirt", change);

        assertEquals(ApplyPreconditions.Decision.PRECONDITION_FAILED, decision);
    }

    @Test
    void returnsApplyWhenCurrentMatchesOldState() {
        BlockChangeRecord change =
                new BlockChangeRecord("world", 1, 2, 3, "minecraft:stone", "minecraft:glass");

        ApplyPreconditions.Decision decision =
                ApplyPreconditions.decide("minecraft:stone", change);

        assertEquals(ApplyPreconditions.Decision.APPLY, decision);
    }
}
