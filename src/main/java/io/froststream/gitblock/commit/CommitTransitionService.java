package io.froststream.untitled8.plotgit.commit;

import io.froststream.untitled8.plotgit.model.BlockChangeRecord;
import io.froststream.untitled8.plotgit.model.CommitMetadata;
import io.froststream.untitled8.plotgit.model.DiffSummary;
import io.froststream.untitled8.plotgit.model.MergeConflict;
import io.froststream.untitled8.plotgit.model.MergePlan;
import io.froststream.untitled8.plotgit.model.LocationKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

public final class CommitTransitionService {
    private final CommitWorker commitWorker;
    private final boolean includeMergeParentsInMergeBase;

    public CommitTransitionService(
            CommitWorker commitWorker, boolean includeMergeParentsInMergeBase) {
        this.commitWorker = commitWorker;
        this.includeMergeParentsInMergeBase = includeMergeParentsInMergeBase;
    }

    public CompletableFuture<List<BlockChangeRecord>> buildTransition(String fromCommitId, String toCommitId) {
        return submitTransitionQuery(() -> buildTransitionInternal(fromCommitId, toCommitId));
    }

    public CompletableFuture<DiffSummary> diff(String fromCommitId, String toCommitId, int sampleLimit) {
        int safeSampleLimit = Math.max(0, sampleLimit);
        return buildTransition(fromCommitId, toCommitId)
                .thenApply(
                        changes -> {
                            List<BlockChangeRecord> reduced = ChangeSetReducer.reduce(changes);
                            List<BlockChangeRecord> samples =
                                    reduced.size() <= safeSampleLimit
                                            ? reduced
                                            : reduced.subList(0, safeSampleLimit);
                            return new DiffSummary(fromCommitId, toCommitId, reduced.size(), samples);
                        });
    }

    public CompletableFuture<MergePlan> planMerge(String oursCommitId, String theirsCommitId) {
        return submitTransitionQuery(() -> planMergeInternal(oursCommitId, theirsCommitId));
    }

    public CompletableFuture<Map<LocationKey, BlockChangeRecord>> patchFromAncestor(
            String ancestorExclusive, String targetInclusive) {
        return submitTransitionQuery(() -> patchFromAncestorInternal(ancestorExclusive, targetInclusive));
    }

    private <T> CompletableFuture<T> submitTransitionQuery(Supplier<T> supplier) {
        try {
            return CompletableFuture.supplyAsync(supplier, commitWorker.transitionExecutor());
        } catch (RejectedExecutionException rejected) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(
                            "transition queue is full, please retry shortly",
                            rejected));
        }
    }

    private List<BlockChangeRecord> buildTransitionInternal(String fromCommitId, String toCommitId) {
        if (fromCommitId == null && toCommitId == null) {
            return List.of();
        }
        if (fromCommitId != null && !commitWorker.hasCommit(fromCommitId)) {
            throw new IllegalArgumentException("Unknown from commit: " + fromCommitId);
        }
        if (toCommitId != null && !commitWorker.hasCommit(toCommitId)) {
            throw new IllegalArgumentException("Unknown target commit: " + toCommitId);
        }

        String lca = resolveCommonAncestor(fromCommitId, toCommitId);
        ChangeSetReducer.Accumulator reducer = new ChangeSetReducer.Accumulator();

        List<CommitMetadata> undoPath = commitWorker.pathDownToAncestor(fromCommitId, lca);
        for (CommitMetadata commit : undoPath) {
            List<BlockChangeRecord> commitChanges = commitWorker.readCommitChangesBlocking(commit.commitId());
            for (BlockChangeRecord change : commitChanges) {
                reducer.accept(
                        new BlockChangeRecord(
                                change.world(),
                                change.x(),
                                change.y(),
                                change.z(),
                                change.newState(),
                                change.oldState()));
            }
        }

        List<CommitMetadata> redoPath = commitWorker.pathFromAncestor(lca, toCommitId);
        for (CommitMetadata commit : redoPath) {
            reducer.acceptAll(commitWorker.readCommitChangesBlocking(commit.commitId()));
        }

        return reducer.toReducedList();
    }

    private Map<LocationKey, BlockChangeRecord> patchFromAncestorInternal(
            String ancestorExclusive, String targetInclusive) {
        List<CommitMetadata> path = commitWorker.pathFromAncestor(ancestorExclusive, targetInclusive);
        ChangeSetReducer.Accumulator reducer = new ChangeSetReducer.Accumulator();
        for (CommitMetadata commit : path) {
            reducer.acceptAll(commitWorker.readCommitChangesBlocking(commit.commitId()));
        }

        Map<LocationKey, BlockChangeRecord> patch = new LinkedHashMap<>();
        for (BlockChangeRecord reduced : reducer.toReducedList()) {
            patch.put(new LocationKey(reduced.world(), reduced.x(), reduced.y(), reduced.z()), reduced);
        }
        return patch;
    }

    private MergePlan planMergeInternal(String oursCommitId, String theirsCommitId) {
        if (oursCommitId == null || theirsCommitId == null) {
            throw new IllegalArgumentException("Both ours and theirs commits are required for merge.");
        }
        if (oursCommitId.equals(theirsCommitId)) {
            return new MergePlan(oursCommitId, oursCommitId, theirsCommitId, List.of(), List.of());
        }

        String base = resolveCommonAncestor(oursCommitId, theirsCommitId);
        Map<LocationKey, BlockChangeRecord> oursPatch = patchFromAncestorInternal(base, oursCommitId);
        Map<LocationKey, BlockChangeRecord> theirsPatch = patchFromAncestorInternal(base, theirsCommitId);

        List<BlockChangeRecord> applyChanges = new ArrayList<>();
        List<MergeConflict> conflicts = new ArrayList<>();

        for (Map.Entry<LocationKey, BlockChangeRecord> entry : theirsPatch.entrySet()) {
            LocationKey key = entry.getKey();
            BlockChangeRecord theirs = entry.getValue();
            BlockChangeRecord ours = oursPatch.get(key);

            if (ours == null) {
                applyChanges.add(theirs);
                continue;
            }

            if (ours.newState().equals(theirs.newState())) {
                continue;
            }

            conflicts.add(
                    new MergeConflict(
                            key.world(),
                            key.x(),
                            key.y(),
                            key.z(),
                            theirs.oldState(),
                            ours.newState(),
                            theirs.newState()));
        }

        return new MergePlan(
                base,
                oursCommitId,
                theirsCommitId,
                ChangeSetReducer.reduce(applyChanges),
                conflicts);
    }

    private String resolveCommonAncestor(String firstCommitId, String secondCommitId) {
        if (!includeMergeParentsInMergeBase) {
            return commitWorker.findCommonAncestor(firstCommitId, secondCommitId, false);
        }
        String candidate = commitWorker.findCommonAncestor(firstCommitId, secondCommitId, true);
        if (candidate == null) {
            return null;
        }
        if (commitWorker.isFirstParentAncestor(candidate, firstCommitId)
                && commitWorker.isFirstParentAncestor(candidate, secondCommitId)) {
            return candidate;
        }
        return commitWorker.findCommonAncestor(firstCommitId, secondCommitId, false);
    }
}
