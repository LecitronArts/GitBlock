package io.froststream.untitled8.plotgit.command;

import io.froststream.untitled8.plotgit.model.BlockChangeRecord;
import io.froststream.untitled8.plotgit.model.LocationKey;
import io.froststream.untitled8.plotgit.repo.RepoRegion;
import io.froststream.untitled8.plotgit.repo.RepositoryState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class BenchmarkCommandHandler {
    private static final int SNAPSHOT_BLOCKS_PER_TICK = 4000;
    private static final String SNAPSHOT_PREFIX = "snap-";

    private final PlotGitCommandEnv env;
    private final Map<String, SnapshotProcess> snapshotProcesses = new ConcurrentHashMap<>();

    public BenchmarkCommandHandler(PlotGitCommandEnv env) {
        this.env = env;
    }

    public void handleBench(CommandSender sender, String[] args) {
        if (!sender.hasPermission("plotgit.admin")) {
            env.send(sender, "common.no-permission-admin");
            return;
        }
        RepositoryState state = env.requireInitialized(sender);
        if (state == null) {
            return;
        }
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        if ("baseline".equals(sub)) {
            printBaseline(sender);
            return;
        }
        if ("run".equals(sub)) {
            runBenchmark(sender, state, args);
            return;
        }
        sendUsage(sender);
    }

    private void runBenchmark(CommandSender sender, RepositoryState state, String[] args) {
        PlotGitCommandEnv.MutationTicket ticket = env.tryAcquireMutation(sender, "benchmark run");
        if (ticket == null) {
            return;
        }
        if (env.isApplyQueueBusy()) {
            env.send(sender, "benchmark.apply-queue-busy");
            ticket.close();
            return;
        }

        RepoRegion region = state.region();
        int maxHeight = Math.max(1, region.maxY() - region.minY() + 1);
        int requestedHeight = parseInt(args, 2, 1);
        int height = Math.max(1, Math.min(maxHeight, requestedHeight));

        String blockA = parseBlockData(args, 3, "minecraft:stone", sender);
        if (blockA == null) {
            ticket.close();
            return;
        }
        String blockB = parseBlockData(args, 4, "minecraft:andesite", sender);
        if (blockB == null) {
            ticket.close();
            return;
        }

        long width = (long) region.maxX() - region.minX() + 1L;
        long depth = (long) region.maxZ() - region.minZ() + 1L;
        long totalLong = width * depth * height;
        boolean force = Arrays.stream(args).anyMatch("--force"::equalsIgnoreCase);
        boolean autoRollback = !Arrays.stream(args).anyMatch("--no-rollback"::equalsIgnoreCase);
        if (totalLong > 3_000_000L && !force) {
            env.send(sender, "benchmark.large-confirm", totalLong);
            ticket.close();
            return;
        }
        if (totalLong > Integer.MAX_VALUE) {
            env.send(sender, "benchmark.too-large");
            ticket.close();
            return;
        }

        World world = Bukkit.getWorld(region.world());
        if (world == null) {
            env.send(sender, "benchmark.world-not-loaded", region.world());
            ticket.close();
            return;
        }

        int total = (int) totalLong;
        String snapshotProcessId = newSnapshotProcessId();
        env.send(sender, "benchmark.snapshot-preparing", width, depth, height, total);
        env.send(sender, "benchmark.snapshot-queued", snapshotProcessId);

        try {
            prepareChanges(
                    sender,
                    world,
                    region,
                    height,
                    blockA,
                    blockB,
                    total,
                    snapshotProcessId,
                    ticket,
                    changes -> queueBenchmark(sender, state, width, depth, height, changes, autoRollback, ticket));
        } catch (Throwable throwable) {
            env.send(sender, "benchmark.start-failed", env.rootMessage(throwable));
            ticket.close();
        }
    }

    private void prepareChanges(
            CommandSender sender,
            World world,
            RepoRegion region,
            int height,
            String blockA,
            String blockB,
            int expectedSize,
            String snapshotProcessId,
            PlotGitCommandEnv.MutationTicket ticket,
            Consumer<List<BlockChangeRecord>> onReady) {
        List<BlockChangeRecord> changes = new ArrayList<>(expectedSize);
        Map<String, String> statePool = new HashMap<>();
        int startY = region.minY();
        int endY = Math.min(region.maxY(), startY + height - 1);
        CoordinateCursor cursor = new CoordinateCursor(region.minX(), startY, region.minZ());
        String canonicalWorld = canonicalize(statePool, region.world());
        String canonicalBlockA = canonicalize(statePool, blockA);
        String canonicalBlockB = canonicalize(statePool, blockB);

        BukkitRunnable snapshotRunnable =
                new BukkitRunnable() {
                    private int scannedBlocks;
                    private int nextProgressMilestone = 200_000;

                    @Override
                    public void run() {
                        int processedThisTick = 0;
                        while (processedThisTick < SNAPSHOT_BLOCKS_PER_TICK) {
                            if (cursor.y > endY) {
                                cancel();
                                snapshotProcesses.remove(snapshotProcessId);
                                env.runGuarded(sender, ticket, () -> onReady.accept(changes));
                                return;
                            }

                            int x = cursor.x;
                            int y = cursor.y;
                            int z = cursor.z;
                            String oldState =
                                    canonicalize(
                                            statePool,
                                            world.getBlockAt(x, y, z).getBlockData().getAsString());
                            String nextState = ((x + z + y) & 1) == 0 ? canonicalBlockA : canonicalBlockB;
                            if (!oldState.equals(nextState)) {
                                changes.add(
                                        new BlockChangeRecord(
                                                canonicalWorld,
                                                x,
                                                y,
                                                z,
                                                oldState,
                                                nextState));
                            }
                            advance(cursor, region, endY);
                            processedThisTick++;
                            scannedBlocks++;
                        }

                        while (scannedBlocks >= nextProgressMilestone) {
                            env.send(sender, "benchmark.snapshot-progress", scannedBlocks, expectedSize);
                            nextProgressMilestone += 200_000;
                        }
                    }
                };
        BukkitTask snapshotTask = snapshotRunnable.runTaskTimer(env.plugin(), 1L, 1L);
        snapshotProcesses.put(snapshotProcessId, new SnapshotProcess(snapshotTask, ticket, sender));
    }

    private void advance(CoordinateCursor cursor, RepoRegion region, int endY) {
        cursor.z++;
        if (cursor.z > region.maxZ()) {
            cursor.z = region.minZ();
            cursor.x++;
            if (cursor.x > region.maxX()) {
                cursor.x = region.minX();
                cursor.y++;
                if (cursor.y > endY) {
                    cursor.y = endY + 1;
                }
            }
        }
    }

    private void queueBenchmark(
            CommandSender sender,
            RepositoryState state,
            long width,
            long depth,
            int height,
            List<BlockChangeRecord> changes,
            boolean autoRollback,
            PlotGitCommandEnv.MutationTicket ticket) {
        String jobId =
                env.enqueueApplyJob(
                        sender,
                        "benchmark apply",
                                changes,
                                summary ->
                                        env.runGuarded(
                                                sender,
                                                ticket,
                                                () -> {
                                                    if (summary.failed() > 0) {
                                                        if (autoRollback) {
                                                            env.send(sender, "benchmark.apply-fail-rollback", summary.failed());
                                                        } else {
                                                            env.dirtyMap().restoreAll(summary.appliedDelta());
                                                            env.send(sender, "benchmark.apply-fail-dirty", summary.failed());
                                                        }
                                                    }
                                                    double blocksPerSecond =
                                                            summary.durationMillis() <= 0
                                                                    ? 0.0
                                                                    : (summary.applied() * 1000.0 / summary.durationMillis());
                                                    String resultLine =
                                                            String.format(
                                                                    Locale.ROOT,
                                                                    "BENCH_RESULT repo=%s size=%dx%dx%d total=%d applied=%d failed=%d duration_ms=%d blocks_per_sec=%.2f min_tps=%.2f avg_tps=%.2f",
                                                                    state.repoName(),
                                                                    width,
                                                                    depth,
                                                                    height,
                                                                    summary.total(),
                                                                    summary.applied(),
                                                                    summary.failed(),
                                                                    summary.durationMillis(),
                                                                    blocksPerSecond,
                                                                    summary.minTps(),
                                                                    summary.avgTps());
                                                    sender.sendMessage(resultLine);
                                                    env.plugin().getLogger().info(resultLine);
                                                    if (autoRollback) {
                                                        Set<LocationKey> expectedRestoredKeys =
                                                                summary.failed() == 0
                                                                        ? null
                                                                        : Set.copyOf(summary.appliedDelta().keySet());
                                                        queueRollback(
                                                                sender,
                                                                state,
                                                                width,
                                                                depth,
                                                                height,
                                                                changes,
                                                                expectedRestoredKeys,
                                                                ticket);
                                                        return;
                                                    }
                                                    if (summary.failed() > 0) {
                                                        env.send(sender, "benchmark.skip-commit-on-fail");
                                                        ticket.close();
                                                        return;
                                                    }
                                                    commitBenchmarkWithoutRollback(
                                                            sender, state, width, depth, height, changes, ticket);
                                                }));
        if (jobId == null) {
            ticket.close();
            return;
        }
        env.send(sender, "benchmark.job-queued", jobId, changes.size());
    }

    private void commitBenchmarkWithoutRollback(
            CommandSender sender,
            RepositoryState state,
            long width,
            long depth,
            int height,
            List<BlockChangeRecord> changes,
            PlotGitCommandEnv.MutationTicket ticket) {
        String branchName = state.currentBranch();
        String expectedParentCommitId = state.activeCommitId();
        String message =
                "benchmark no-rollback "
                        + width
                        + "x"
                        + depth
                        + "x"
                        + height;
        env.send(sender, "benchmark.persisting-commit");
        env.commitWorker().submitCommit(
                        message,
                        env.resolveAuthor(sender),
                        branchName,
                        expectedParentCommitId,
                        null,
                        changes)
                .whenComplete(
                        (result, throwable) ->
                                Bukkit.getScheduler()
                                        .runTask(
                                                env.plugin(),
                                                () ->
                                                        env.runGuarded(
                                                                sender,
                                                                ticket,
                                                                () -> {
                                                                    if (throwable != null) {
                                                                        queueBenchmarkCommitFailureRecovery(
                                                                                sender, changes, throwable, ticket);
                                                                        return;
                                                                    }
                                                                    RepositoryState latest =
                                                                            env.repositoryStateService().getState();
                                                                    RepositoryState updated =
                                                                            env.advanceBranchAfterAsyncCommit(
                                                                                    latest,
                                                                                    branchName,
                                                                                    expectedParentCommitId,
                                                                                    result.commitId());
                                                                    env.repositoryStateService().update(updated);
                                                                    env.checkpointService().maybeCreateCheckpoint(result);
                                                                    if (updated == latest) {
                                                                        env.markChangesDirty(changes);
                                                                        env.send(
                                                                                sender,
                                                                                "benchmark.commit-saved-dirty",
                                                                                result.commitId());
                                                                        ticket.close();
                                                                        return;
                                                                    }
                                                                    env.send(
                                                                            sender,
                                                                            "benchmark.committed-no-rollback",
                                                                            result.commitId(),
                                                                            result.changeCount());
                                                                    ticket.close();
                                                                })));
    }

    private void queueBenchmarkCommitFailureRecovery(
            CommandSender sender,
            List<BlockChangeRecord> forwardChanges,
            Throwable throwable,
            PlotGitCommandEnv.MutationTicket ticket) {
        env.send(sender, "benchmark.commit-failed", env.rootMessage(throwable));
        env.send(sender, "benchmark.commit-recovery-attempt");
        List<BlockChangeRecord> recoveryChanges = env.reverseChanges(forwardChanges);
        String recoveryJobId =
                env.enqueueApplyJob(
                        sender,
                        "benchmark rollback recovery",
                                recoveryChanges,
                                summary ->
                                        env.runGuarded(
                                                sender,
                                                ticket,
                                                () -> {
                                                    if (summary.failed() > 0) {
                                                        env.markRestorationFailuresDirty(recoveryChanges, summary.appliedDelta());
                                                        env.send(
                                                                sender,
                                                                "benchmark.recovery-failed-partial",
                                                                summary.failed());
                                                        ticket.close();
                                                        return;
                                                    }
                                                    env.send(sender, "benchmark.recovery-complete");
                                                    ticket.close();
                                                }));
        if (recoveryJobId == null) {
            ticket.close();
            return;
        }
        env.send(sender, "benchmark.recovery-queued", recoveryJobId, recoveryChanges.size());
    }

    private void queueRollback(
            CommandSender sender,
            RepositoryState state,
            long width,
            long depth,
            int height,
            List<BlockChangeRecord> forwardChanges,
            Set<LocationKey> expectedRestoredKeys,
            PlotGitCommandEnv.MutationTicket ticket) {
        List<BlockChangeRecord> rollbackChanges = env.reverseChanges(forwardChanges);
        String rollbackJobId =
                env.enqueueApplyJob(
                        sender,
                        "benchmark rollback apply",
                                rollbackChanges,
                                summary ->
                                        env.runGuarded(
                                                sender,
                                                ticket,
                                                () -> {
                                                    if (summary.failed() > 0) {
                                                        env.markRestorationFailuresDirty(
                                                                rollbackChanges,
                                                                summary.appliedDelta(),
                                                                expectedRestoredKeys);
                                                        env.send(sender, "benchmark.rollback-failed-dirty", summary.failed());
                                                    }
                                                    double blocksPerSecond =
                                                            summary.durationMillis() <= 0
                                                                    ? 0.0
                                                                    : (summary.applied() * 1000.0 / summary.durationMillis());
                                                    String resultLine =
                                                            String.format(
                                                                    Locale.ROOT,
                                                                    "BENCH_ROLLBACK_RESULT repo=%s size=%dx%dx%d total=%d applied=%d failed=%d duration_ms=%d blocks_per_sec=%.2f min_tps=%.2f avg_tps=%.2f",
                                                                    state.repoName(),
                                                                    width,
                                                                    depth,
                                                                    height,
                                                                    summary.total(),
                                                                    summary.applied(),
                                                                    summary.failed(),
                                                                    summary.durationMillis(),
                                                                    blocksPerSecond,
                                                                    summary.minTps(),
                                                                    summary.avgTps());
                                                    sender.sendMessage(resultLine);
                                                    env.plugin().getLogger().info(resultLine);
                                                    ticket.close();
                                                }));
        if (rollbackJobId == null) {
            ticket.close();
            return;
        }
        env.send(sender, "benchmark.rollback-queued", rollbackJobId, rollbackChanges.size());
    }

    private String parseBlockData(
            String[] args, int index, String defaultValue, CommandSender sender) {
        String raw = args.length > index ? args[index] : defaultValue;
        try {
            return Bukkit.createBlockData(raw).getAsString();
        } catch (IllegalArgumentException invalidData) {
            env.send(sender, "benchmark.invalid-block-data", raw);
            return null;
        }
    }

    private int parseInt(String[] args, int index, int defaultValue) {
        if (args.length <= index) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private void printBaseline(CommandSender sender) {
        env.send(sender, "benchmark.baseline-title");
        env.send(sender, "benchmark.baseline-target-failed");
        env.send(sender, "benchmark.baseline-target-min-tps");
        env.send(sender, "benchmark.baseline-target-avg-tps");
        env.send(sender, "benchmark.baseline-target-bps");
        env.send(sender, "benchmark.baseline-run");
        env.send(sender, "benchmark.baseline-default");
    }

    private void sendUsage(CommandSender sender) {
        env.send(sender, "benchmark.usage-title");
        env.send(sender, "benchmark.usage-run");
        env.send(sender, "benchmark.usage-baseline");
        env.send(sender, "benchmark.usage-no-rollback");
    }

    public List<String> activeSnapshotProcessIds() {
        return new ArrayList<>(snapshotProcesses.keySet());
    }

    public boolean cancelSnapshotProcess(String processId, CommandSender actor) {
        SnapshotProcess process = snapshotProcesses.remove(processId);
        if (process == null) {
            return false;
        }
        process.task.cancel();
        env.send(process.owner, "benchmark.snapshot-cancelled", processId, actor.getName());
        process.ticket.close();
        return true;
    }

    public int cancelAllSnapshotProcesses(CommandSender actor) {
        List<String> ids = List.copyOf(snapshotProcesses.keySet());
        int cancelled = 0;
        for (String processId : ids) {
            if (cancelSnapshotProcess(processId, actor)) {
                cancelled++;
            }
        }
        return cancelled;
    }

    private String newSnapshotProcessId() {
        return SNAPSHOT_PREFIX + UUID.randomUUID().toString().substring(0, 8);
    }

    private String canonicalize(Map<String, String> pool, String value) {
        String existing = pool.get(value);
        if (existing != null) {
            return existing;
        }
        pool.put(value, value);
        return value;
    }

    private static final class CoordinateCursor {
        private int x;
        private int y;
        private int z;

        private CoordinateCursor(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private record SnapshotProcess(
            BukkitTask task, PlotGitCommandEnv.MutationTicket ticket, CommandSender owner) {}
}
