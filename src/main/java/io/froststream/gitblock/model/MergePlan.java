package io.froststream.untitled8.plotgit.model;

import java.util.List;

public record MergePlan(
        String baseCommitId,
        String oursCommitId,
        String theirsCommitId,
        List<BlockChangeRecord> applyChanges,
        List<MergeConflict> conflicts) {
    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }
}
