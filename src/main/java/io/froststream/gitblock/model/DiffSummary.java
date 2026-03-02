package io.froststream.gitblock.model;

import java.util.List;

public record DiffSummary(
        String fromCommitId,
        String toCommitId,
        int changedBlocks,
        List<BlockChangeRecord> samples) {}
