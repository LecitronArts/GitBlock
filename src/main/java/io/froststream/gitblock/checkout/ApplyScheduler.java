package io.froststream.gitblock.checkout;

import io.froststream.gitblock.diff.TrackingGate;
import io.froststream.gitblock.model.ApplySummary;
import io.froststream.gitblock.model.BlockChangeRecord;
import io.froststream.gitblock.model.DirtyEntry;
import io.froststream.gitblock.model.LocationKey;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class ApplyScheduler {
    private static final int MIN_BLOCKS_PER_TICK = 100;
    private final JavaPlugin plugin;
    private final TrackingGate trackingGate;
    private final int baseMaxBlocksPerTick;
    private final long baseTickBudgetNanos;
    private final int maxQueuedJobs;
    private final ApplyQueueOverflowPolicy queueOverflowPolicy;
    private final int maxChunkUnloadsPerTick;
    private final Map<String, BlockData> blockDataCache;
    private final Queue<ApplyJob> jobs = new ConcurrentLinkedQueue<>();
    private final Queue<ChunkUnloadRequest> pendingChunkUnloads = new ConcurrentLinkedQueue<>();
    private final BukkitTask workerTask;
    private boolean countHeadAsPending;

    public ApplyScheduler(
            JavaPlugin plugin,
            TrackingGate trackingGate,
            int maxBlocksPerTick,
            long tickBudgetNanos,
            int maxQueuedJobs,
            ApplyQueueOverflowPolicy queueOverflowPolicy,
            int blockDataCacheSize,
            int maxChunkUnloadsPerTick) {
        this.plugin = plugin;
        this.trackingGate = trackingGate;
        this.baseMaxBlocksPerTick = Math.max(MIN_BLOCKS_PER_TICK, maxBlocksPerTick);
        this.baseTickBudgetNanos = Math.max(1L, tickBudgetNanos);
        this.maxQueuedJobs = Math.max(1, maxQueuedJobs);
        this.queueOverflowPolicy =
                queueOverflowPolicy == null ? ApplyQueueOverflowPolicy.REJECT_NEW : queueOverflowPolicy;
        this.blockDataCache = newBlockDataCache(Math.max(0, blockDataCacheSize));
        this.maxChunkUnloadsPerTick = Math.max(1, maxChunkUnloadsPerTick);
        this.workerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public ApplyEnqueueResult enqueueJob(List<BlockChangeRecord> changes, Consumer<ApplySummary> onComplete) {
        List<String> droppedJobIds = new ArrayList<>();
        int pending = pendingJobs();
        while (pending >= maxQueuedJobs) {
            if (queueOverflowPolicy != ApplyQueueOverflowPolicy.DROP_OLDEST_PENDING) {
                return ApplyEnqueueResult.rejected(queueFullMessage(pending));
            }
            ApplyJob dropped = removeOldestPendingJob();
            if (dropped == null) {
                return ApplyEnqueueResult.rejected(queueFullMessage(pending));
            }
            droppedJobIds.add(dropped.jobId);
            completeCanceledJob(dropped);
            pending = pendingJobs();
        }
        List<BlockChangeRecord> normalized = normalize(changes);
        String jobId = UUID.randomUUID().toString().substring(0, 8);
        jobs.add(new ApplyJob(jobId, normalized, onComplete));
        if (!droppedJobIds.isEmpty()) {
            return ApplyEnqueueResult.accepted(
                    jobId,
                    "Apply queue was full; dropped "
                            + droppedJobIds.size()
                            + " oldest pending job(s): "
                            + String.join(", ", droppedJobIds)
                            + " due to overflow policy "
                            + queueOverflowPolicy.configName()
                            + ".");
        }
        return ApplyEnqueueResult.accepted(jobId, null);
    }

    public String enqueue(List<BlockChangeRecord> changes, Consumer<ApplySummary> onComplete) {
        ApplyEnqueueResult result = enqueueJob(changes, onComplete);
        return result.accepted() ? result.jobId() : null;
    }

    public int queuedJobs() {
        return jobs.size();
    }

    public List<String> jobIds() {
        return jobs.stream().map(job -> job.jobId).collect(Collectors.toList());
    }

    public int maxQueuedJobs() {
        return maxQueuedJobs;
    }

    public String queueOverflowPolicyName() {
        return queueOverflowPolicy.configName();
    }

    public ApplyJobStatus currentJobStatus() {
        ApplyJob current = jobs.peek();
        if (current == null) {
            return null;
        }
        return new ApplyJobStatus(
                current.jobId,
                current.total,
                current.applied + current.failed,
                current.applied,
                current.failed,
                System.currentTimeMillis() - current.startedAtMillis);
    }

    public int cancelAll() {
        int cancelled = 0;
        ApplyJob job;
        while ((job = jobs.poll()) != null) {
            cancelled++;
            boolean previousCountHeadAsPending = countHeadAsPending;
            countHeadAsPending = true;
            try {
                completeCanceledJob(job);
            } finally {
                countHeadAsPending = previousCountHeadAsPending;
            }
        }
        return cancelled;
    }

    public boolean cancelJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return false;
        }
        for (ApplyJob job : jobs) {
            if (!job.jobId.equals(jobId)) {
                continue;
            }
            boolean removingHead = job == jobs.peek();
            if (jobs.remove(job)) {
                boolean previousCountHeadAsPending = countHeadAsPending;
                if (removingHead) {
                    countHeadAsPending = true;
                }
                try {
                    completeCanceledJob(job);
                } finally {
                    countHeadAsPending = previousCountHeadAsPending;
                }
                return true;
            }
        }
        return false;
    }

    public void shutdown() {
        workerTask.cancel();
        cancelAll();
        drainPendingChunkUnloads(Integer.MAX_VALUE);
    }

    private void tick() {
        drainPendingChunkUnloads(maxChunkUnloadsPerTick);
        ApplyJob currentJob = jobs.peek();
        if (currentJob == null) {
            return;
        }

        double tickTps = readCurrentTps();
        currentJob.samples++;
        currentJob.tpsSum += tickTps;
        currentJob.minTps = Math.min(currentJob.minTps, tickTps);
        Budget budget = dynamicBudgetForTps(tickTps);
        long tickStart = System.nanoTime();
        int processedThisTick = 0;
        while (processedThisTick < budget.maxBlocks && currentJob.nextIndex < currentJob.total) {
            if (System.nanoTime() - tickStart >= budget.budgetNanos) {
                break;
            }

            int changeIndex = currentJob.nextIndex++;
            BlockChangeRecord change = currentJob.changes.get(changeIndex);
            processedThisTick++;
            ApplyOutcome outcome = applyChange(currentJob, change);
            if (outcome == ApplyOutcome.APPLIED) {
                currentJob.applied++;
                currentJob.appliedIndexes.set(changeIndex);
                continue;
            }
            if (outcome == ApplyOutcome.ALREADY_AT_TARGET) {
                currentJob.applied++;
                continue;
            }
            currentJob.failed++;
        }

        if (currentJob.nextIndex >= currentJob.total) {
            jobs.poll();
            countHeadAsPending = true;
            try {
                if (currentJob.onComplete != null) {
                    double avgTps =
                            currentJob.samples == 0 ? readCurrentTps() : (currentJob.tpsSum / currentJob.samples);
                    currentJob.onComplete.accept(
                            new ApplySummary(
                                    currentJob.total,
                                    currentJob.applied,
                                    currentJob.failed,
                                    System.currentTimeMillis() - currentJob.startedAtMillis,
                                    currentJob.minTps,
                                    avgTps,
                                    snapshotDelta(currentJob, currentJob.failed)));
                }
            } finally {
                releaseLoadedChunks(currentJob);
                countHeadAsPending = false;
            }
        }
    }

    private Budget dynamicBudgetForTps(double tps) {
        double safeTps = tps <= 0 ? 20.0 : tps;
        double multiplier;
        if (safeTps >= 19.5) {
            multiplier = 1.0;
        } else if (safeTps >= 19.0) {
            multiplier = 0.8;
        } else if (safeTps >= 18.0) {
            multiplier = 0.6;
        } else if (safeTps >= 17.0) {
            multiplier = 0.4;
        } else {
            multiplier = 0.25;
        }
        int maxBlocks = Math.max(MIN_BLOCKS_PER_TICK, (int) Math.floor(baseMaxBlocksPerTick * multiplier));
        long budgetNanos = Math.max(500_000L, (long) Math.floor(baseTickBudgetNanos * multiplier));
        return new Budget(maxBlocks, budgetNanos);
    }

    private double readCurrentTps() {
        double tps = 20.0;
        try {
            double[] tpsArray = Bukkit.getTPS();
            if (tpsArray != null && tpsArray.length > 0 && tpsArray[0] > 0) {
                tps = tpsArray[0];
            }
        } catch (Throwable ignored) {
            tps = 20.0;
        }
        return tps;
    }

    private List<BlockChangeRecord> normalize(List<BlockChangeRecord> input) {
        List<BlockChangeRecord> normalized = new ArrayList<>(input);
        normalized.sort(
                Comparator.comparing(BlockChangeRecord::world)
                        .thenComparingInt(record -> record.x() >> 4)
                        .thenComparingInt(record -> record.z() >> 4)
                        .thenComparingInt(BlockChangeRecord::y)
                        .thenComparingInt(BlockChangeRecord::x)
                        .thenComparingInt(BlockChangeRecord::z));
        return normalized;
    }

    private ApplyOutcome applyChange(ApplyJob job, BlockChangeRecord change) {
        World world = Bukkit.getWorld(change.world());
        if (world == null) {
            return ApplyOutcome.FAILED;
        }

        int chunkX = change.x() >> 4;
        int chunkZ = change.z() >> 4;
        if (!ensureChunkLoaded(job, world, chunkX, chunkZ)) {
            return ApplyOutcome.FAILED;
        }

        Block block = world.getBlockAt(change.x(), change.y(), change.z());
        ApplyPreconditions.Decision decision =
                ApplyPreconditions.decide(block.getBlockData().getAsString(), change);
        if (decision == ApplyPreconditions.Decision.ALREADY_AT_TARGET) {
            return ApplyOutcome.ALREADY_AT_TARGET;
        }
        if (decision == ApplyPreconditions.Decision.PRECONDITION_FAILED) {
            return ApplyOutcome.FAILED;
        }
        LocationKey key = new LocationKey(change.world(), change.x(), change.y(), change.z());
        trackingGate.suppress(key);
        try {
            block.setBlockData(resolveBlockData(change.newState()), false);
            return ApplyOutcome.APPLIED;
        } catch (IllegalArgumentException invalidState) {
            plugin.getLogger()
                    .warning(
                            "Invalid block state during apply at "
                                    + change.world()
                                    + " "
                                    + change.x()
                                    + ","
                                    + change.y()
                                    + ","
                                    + change.z()
                                    + ": "
                                    + invalidState.getMessage());
            return ApplyOutcome.FAILED;
        } finally {
            trackingGate.resume(key);
        }
    }

    private boolean ensureChunkLoaded(ApplyJob job, World world, int chunkX, int chunkZ) {
        long chunkKey = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
        Set<Long> knownWorldChunks =
                job.loadedChunksByWorld.computeIfAbsent(world.getName(), ignored -> new HashSet<>());
        if (knownWorldChunks.contains(chunkKey)) {
            if (world.isChunkLoaded(chunkX, chunkZ)) {
                return true;
            }
            if (!world.loadChunk(chunkX, chunkZ, false)) {
                return false;
            }
            job.loadedChunksByJob
                    .computeIfAbsent(world.getName(), ignored -> new HashSet<>())
                    .add(chunkKey);
            return true;
        }

        boolean alreadyLoaded = world.isChunkLoaded(chunkX, chunkZ);
        if (!alreadyLoaded && !world.loadChunk(chunkX, chunkZ, false)) {
            return false;
        }
        knownWorldChunks.add(chunkKey);
        if (!alreadyLoaded) {
            job.loadedChunksByJob
                    .computeIfAbsent(world.getName(), ignored -> new HashSet<>())
                    .add(chunkKey);
        }
        return true;
    }

    private Map<LocationKey, DirtyEntry> snapshotDelta(ApplyJob job, int failed) {
        if (failed == 0 || job.applied == 0) {
            return Map.of();
        }
        Map<LocationKey, DirtyEntry> delta = new HashMap<>();
        for (int index = job.appliedIndexes.nextSetBit(0);
                index >= 0;
                index = job.appliedIndexes.nextSetBit(index + 1)) {
            BlockChangeRecord change = job.changes.get(index);
            if (change.oldState().equals(change.newState())) {
                continue;
            }
            LocationKey key = new LocationKey(change.world(), change.x(), change.y(), change.z());
            DirtyEntry existing = delta.get(key);
            if (existing == null) {
                delta.put(key, new DirtyEntry(change.oldState(), change.newState()));
                continue;
            }
            if (existing.originalState().equals(change.newState())) {
                delta.remove(key);
                continue;
            }
            delta.put(key, new DirtyEntry(existing.originalState(), change.newState()));
        }
        if (delta.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(delta);
    }

    private void completeCanceledJob(ApplyJob job) {
        try {
            if (job.onComplete != null) {
                int processed = job.applied + job.failed;
                int canceledRemainder = Math.max(0, job.total - processed);
                int failed = job.failed + canceledRemainder;
                double avgTps = job.samples == 0 ? readCurrentTps() : (job.tpsSum / job.samples);
                job.onComplete.accept(
                        new ApplySummary(
                                job.total,
                                job.applied,
                                failed,
                                System.currentTimeMillis() - job.startedAtMillis,
                                job.minTps,
                                avgTps,
                                snapshotDelta(job, failed)));
            }
        } finally {
            releaseLoadedChunks(job);
        }
    }

    private BlockData resolveBlockData(String encodedBlockData) {
        if (blockDataCache.isEmpty()) {
            return Bukkit.createBlockData(encodedBlockData);
        }
        BlockData cached = blockDataCache.get(encodedBlockData);
        if (cached != null) {
            return cached.clone();
        }
        BlockData parsed = Bukkit.createBlockData(encodedBlockData);
        blockDataCache.put(encodedBlockData, parsed);
        return parsed.clone();
    }

    private void releaseLoadedChunks(ApplyJob job) {
        for (Map.Entry<String, Set<Long>> entry : job.loadedChunksByJob.entrySet()) {
            for (long chunkKey : entry.getValue()) {
                int chunkX = (int) (chunkKey >> 32);
                int chunkZ = (int) chunkKey;
                pendingChunkUnloads.add(new ChunkUnloadRequest(entry.getKey(), chunkX, chunkZ));
            }
        }
    }

    private void drainPendingChunkUnloads(int maxDrain) {
        int safeMaxDrain = Math.max(1, maxDrain);
        for (int drained = 0; drained < safeMaxDrain; drained++) {
            ChunkUnloadRequest request = pendingChunkUnloads.poll();
            if (request == null) {
                return;
            }
            World world = Bukkit.getWorld(request.worldName());
            if (world == null) {
                continue;
            }
            if (world.isChunkLoaded(request.chunkX(), request.chunkZ())) {
                world.unloadChunkRequest(request.chunkX(), request.chunkZ());
            }
        }
    }

    private static Map<String, BlockData> newBlockDataCache(int maxEntries) {
        if (maxEntries <= 0) {
            return Map.of();
        }
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BlockData> eldest) {
                return size() > maxEntries;
            }
        };
    }

    private int pendingJobs() {
        int size = jobs.size();
        if (size == 0) {
            return 0;
        }
        return countHeadAsPending ? size : Math.max(0, size - 1);
    }

    private ApplyJob removeOldestPendingJob() {
        int headSkip = countHeadAsPending ? 0 : 1;
        int index = 0;
        for (ApplyJob job : jobs) {
            if (index++ < headSkip) {
                continue;
            }
            if (jobs.remove(job)) {
                return job;
            }
        }
        return null;
    }

    private String queueFullMessage(int pending) {
        return "queue full (pending="
                + pending
                + "/"
                + maxQueuedJobs
                + ", policy="
                + queueOverflowPolicy.configName()
                + ")";
    }

    private record Budget(int maxBlocks, long budgetNanos) {}

    private record ChunkUnloadRequest(String worldName, int chunkX, int chunkZ) {}

    private enum ApplyOutcome {
        APPLIED,
        ALREADY_AT_TARGET,
        FAILED
    }

    private static final class ApplyJob {
        private final String jobId;
        private final List<BlockChangeRecord> changes;
        private final Consumer<ApplySummary> onComplete;
        private final long startedAtMillis;
        private final Map<String, Set<Long>> loadedChunksByWorld = new HashMap<>();
        private final Map<String, Set<Long>> loadedChunksByJob = new HashMap<>();
        private final BitSet appliedIndexes = new BitSet();
        private final int total;
        private int nextIndex;
        private int samples;
        private double tpsSum;
        private double minTps = 20.0;
        private int applied;
        private int failed;

        private ApplyJob(String jobId, List<BlockChangeRecord> changes, Consumer<ApplySummary> onComplete) {
            this.jobId = jobId;
            this.changes = changes;
            this.total = changes.size();
            this.onComplete = onComplete;
            this.startedAtMillis = System.currentTimeMillis();
        }
    }
}
