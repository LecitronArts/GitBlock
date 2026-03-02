package io.froststream.gitblock.commit;

import io.froststream.gitblock.model.BlockChangeRecord;
import io.froststream.gitblock.model.CommitMetadata;
import io.froststream.gitblock.model.DiffSummary;
import io.froststream.gitblock.model.MergeConflict;
import io.froststream.gitblock.model.MergePlan;
import io.froststream.gitblock.model.LocationKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

public final class CommitTransitionService {
    private final CommitWorker commitWorker;
    private final boolean includeMergeParentsInMergeBase;
    private final Map<TransitionKey, List<BlockChangeRecord>> transitionCache;
    private final Map<PatchKey, Map<LocationKey, BlockChangeRecord>> patchCache;

    public CommitTransitionService(
            CommitWorker commitWorker,
            boolean includeMergeParentsInMergeBase,
            int transitionCacheEntries,
            int patchCacheEntries) {
        this.commitWorker = commitWorker;
        this.includeMergeParentsInMergeBase = includeMergeParentsInMergeBase;
        this.transitionCache = newLruCache(Math.max(0, transitionCacheEntries));
        this.patchCache = newLruCache(Math.max(0, patchCacheEntries));
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
        TransitionKey key = new TransitionKey(fromCommitId, toCommitId);
        List<BlockChangeRecord> cached = cacheGet(transitionCache, key);
        if (cached != null) {
            return cached;
        }

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

        List<BlockChangeRecord> reduced = List.copyOf(reducer.toReducedList());
        cachePut(transitionCache, key, reduced);
        return reduced;
    }

    private Map<LocationKey, BlockChangeRecord> patchFromAncestorInternal(
            String ancestorExclusive, String targetInclusive) {
        PatchKey key = new PatchKey(ancestorExclusive, targetInclusive);
        Map<LocationKey, BlockChangeRecord> cached = cacheGet(patchCache, key);
        if (cached != null) {
            return cached;
        }

        List<CommitMetadata> path = commitWorker.pathFromAncestor(ancestorExclusive, targetInclusive);
        ChangeSetReducer.Accumulator reducer = new ChangeSetReducer.Accumulator();
        for (CommitMetadata commit : path) {
            reducer.acceptAll(commitWorker.readCommitChangesBlocking(commit.commitId()));
        }

        Map<LocationKey, BlockChangeRecord> patch = new LinkedHashMap<>();
        for (BlockChangeRecord reduced : reducer.toReducedList()) {
            patch.put(new LocationKey(reduced.world(), reduced.x(), reduced.y(), reduced.z()), reduced);
        }
        Map<LocationKey, BlockChangeRecord> immutablePatch = Map.copyOf(patch);
        cachePut(patchCache, key, immutablePatch);
        return immutablePatch;
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

    private static <K, V> Map<K, V> newLruCache(int maxEntries) {
        if (maxEntries <= 0) {
            return null;
        }
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        };
    }

    private static <K, V> V cacheGet(Map<K, V> cache, K key) {
        if (cache == null) {
            return null;
        }
        synchronized (cache) {
            return cache.get(key);
        }
    }

    private static <K, V> void cachePut(Map<K, V> cache, K key, V value) {
        if (cache == null) {
            return;
        }
        synchronized (cache) {
            cache.put(key, value);
        }
    }

    private record TransitionKey(String fromCommitId, String toCommitId) {}

    private record PatchKey(String ancestorExclusive, String targetInclusive) {}
}
