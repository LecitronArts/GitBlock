package io.froststream.gitblock.command;

import io.froststream.gitblock.checkpoint.CheckpointService;
import io.froststream.gitblock.checkout.ApplyScheduler;
import io.froststream.gitblock.checkout.ApplyEnqueueResult;
import io.froststream.gitblock.commit.CommitTransitionService;
import io.froststream.gitblock.commit.CommitWorker;
import io.froststream.gitblock.diff.DirtyMap;
import io.froststream.gitblock.i18n.I18nService;
import io.froststream.gitblock.model.ApplySummary;
import io.froststream.gitblock.model.BlockChangeRecord;
import io.froststream.gitblock.model.DirtyEntry;
import io.froststream.gitblock.model.LocationKey;
import io.froststream.gitblock.repo.RepositoryState;
import io.froststream.gitblock.repo.RepositoryStateService;
import io.froststream.gitblock.repo.SelectionService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class GitBlockCommandEnv {
    private final JavaPlugin plugin;
    private final DirtyMap dirtyMap;
    private final CommitWorker commitWorker;
    private final CommitTransitionService transitionService;
    private final ApplyScheduler applyScheduler;
    private final CheckpointService checkpointService;
    private final RepositoryStateService repositoryStateService;
    private final SelectionService selectionService;
    private final I18nService i18n;
    private final Path conflictsDir;
    private final String storageRepoName;
    private final boolean serializeMutations;
    private final String usePermission;
    private final String adminPermission;
    private final String jobsPermission;
    private final AtomicReference<String> activeMutation = new AtomicReference<>();

    public GitBlockCommandEnv(
            JavaPlugin plugin,
            DirtyMap dirtyMap,
            CommitWorker commitWorker,
            CommitTransitionService transitionService,
            ApplyScheduler applyScheduler,
            CheckpointService checkpointService,
            RepositoryStateService repositoryStateService,
            SelectionService selectionService,
            I18nService i18n,
            Path conflictsDir,
            String storageRepoName,
            boolean serializeMutations,
            String usePermission,
            String adminPermission,
            String jobsPermission) {
        this.plugin = plugin;
        this.dirtyMap = dirtyMap;
        this.commitWorker = commitWorker;
        this.transitionService = transitionService;
        this.applyScheduler = applyScheduler;
        this.checkpointService = checkpointService;
        this.repositoryStateService = repositoryStateService;
        this.selectionService = selectionService;
        this.i18n = i18n;
        this.conflictsDir = conflictsDir;
        this.storageRepoName = storageRepoName;
        this.serializeMutations = serializeMutations;
        this.usePermission = usePermission;
        this.adminPermission = adminPermission;
        this.jobsPermission = jobsPermission;
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public DirtyMap dirtyMap() {
        return dirtyMap;
    }

    public CommitWorker commitWorker() {
        return commitWorker;
    }

    public CommitTransitionService transitionService() {
        return transitionService;
    }

    public ApplyScheduler applyScheduler() {
        return applyScheduler;
    }

    public CheckpointService checkpointService() {
        return checkpointService;
    }

    public RepositoryStateService repositoryStateService() {
        return repositoryStateService;
    }

    public SelectionService selectionService() {
        return selectionService;
    }

    public I18nService i18n() {
        return i18n;
    }

    public Path conflictsDir() {
        return conflictsDir;
    }

    public String storageRepoName() {
        return storageRepoName;
    }

    public boolean serializeMutationsEnabled() {
        return serializeMutations;
    }

    public String usePermissionNode() {
        return usePermission;
    }

    public String adminPermissionNode() {
        return adminPermission;
    }

    public String jobsPermissionNode() {
        return jobsPermission;
    }

    public boolean hasUsePermission(CommandSender sender) {
        return sender.hasPermission(usePermission);
    }

    public boolean hasAdminPermission(CommandSender sender) {
        return sender.hasPermission(adminPermission);
    }

    public boolean hasJobsPermission(CommandSender sender) {
        return sender.hasPermission(jobsPermission);
    }

    public String activeMutationDescription() {
        return activeMutation.get();
    }

    public String tr(CommandSender sender, String key, Object... args) {
        return i18n.tr(sender, key, args);
    }

    public String trRaw(CommandSender sender, String key, Object... args) {
        return i18n.trRaw(sender, key, args);
    }

    public void send(CommandSender sender, String key, Object... args) {
        sender.sendMessage(tr(sender, key, args));
    }

    public MutationTicket tryAcquireMutation(CommandSender sender, String description) {
        if (!serializeMutations) {
            return MutationTicket.noOp();
        }
        String normalized = normalizeOperationDescription(description);
        String current = activeMutation.get();
        if (current != null) {
            send(sender, "common.another-operation", current);
            return null;
        }
        if (!activeMutation.compareAndSet(null, normalized)) {
            send(sender, "common.another-operation", nullable(activeMutation.get()));
            return null;
        }
        return new MutationTicket(this, normalized, false);
    }

    public RepositoryState requireInitialized(CommandSender sender) {
        RepositoryState state = repositoryStateService.getState();
        if (!state.initialized()) {
            send(sender, "common.repository-not-initialized");
            return null;
        }
        return state;
    }

    public String resolveCommitToken(String token, RepositoryState state) {
        if ("HEAD".equalsIgnoreCase(token)) {
            return state.activeCommitId();
        }
        if ("null".equalsIgnoreCase(token)) {
            return null;
        }
        if (state.hasBranch(token)) {
            return state.headOf(token);
        }
        if (commitWorker.hasCommit(token)) {
            return token;
        }
        return null;
    }

    public UUID resolveAuthor(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUniqueId();
        }
        return UUID.nameUUIDFromBytes(("console:" + sender.getName()).getBytes(StandardCharsets.UTF_8));
    }

    public List<BlockChangeRecord> toBlockChanges(Map<LocationKey, DirtyEntry> drained) {
        List<BlockChangeRecord> changes = new ArrayList<>(drained.size());
        for (Map.Entry<LocationKey, DirtyEntry> entry : drained.entrySet()) {
            LocationKey key = entry.getKey();
            DirtyEntry dirtyEntry = entry.getValue();
            changes.add(
                    new BlockChangeRecord(
                            key.world(),
                            key.x(),
                            key.y(),
                            key.z(),
                            dirtyEntry.originalState(),
                            dirtyEntry.latestState()));
        }
        return changes;
    }

    public List<BlockChangeRecord> reverseChanges(List<BlockChangeRecord> changes) {
        List<BlockChangeRecord> reversed = new ArrayList<>(changes.size());
        for (BlockChangeRecord change : changes) {
            reversed.add(
                    new BlockChangeRecord(
                            change.world(),
                            change.x(),
                            change.y(),
                            change.z(),
                            change.newState(),
                            change.oldState()));
        }
        return reversed;
    }

    public void markChangesDirty(List<BlockChangeRecord> changes) {
        for (BlockChangeRecord change : changes) {
            dirtyMap.recordChange(
                    new LocationKey(change.world(), change.x(), change.y(), change.z()),
                    change.oldState(),
                    change.newState());
        }
    }

    public void markRestorationFailuresDirty(
            List<BlockChangeRecord> restorationChanges, Map<LocationKey, DirtyEntry> appliedDelta) {
        markRestorationFailuresDirty(restorationChanges, appliedDelta, null);
    }

    public void markRestorationFailuresDirty(
            List<BlockChangeRecord> restorationChanges,
            Map<LocationKey, DirtyEntry> appliedDelta,
            Set<LocationKey> expectedRestoredKeys) {
        Set<LocationKey> restored = new HashSet<>(appliedDelta.keySet());
        Set<LocationKey> expected =
                expectedRestoredKeys == null ? null : Set.copyOf(expectedRestoredKeys);
        for (BlockChangeRecord change : restorationChanges) {
            LocationKey key = new LocationKey(change.world(), change.x(), change.y(), change.z());
            if (restored.contains(key)) {
                continue;
            }
            if (expected != null && !expected.contains(key)) {
                continue;
            }
            dirtyMap.recordChange(key, change.newState(), change.oldState());
        }
    }

    public String sanitizeRepoName(String raw) {
        String sanitized = raw.replaceAll("[^a-zA-Z0-9_\\-]", "");
        return sanitized.isBlank() ? "default" : sanitized.toLowerCase(Locale.ROOT);
    }

    public String sanitizeBranchName(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_\\-/]", "");
    }

    public boolean isReservedReferenceToken(String token) {
        if (token == null) {
            return false;
        }
        return "head".equalsIgnoreCase(token) || "null".equalsIgnoreCase(token);
    }

    public String formatLocation(Location location) {
        return location.getWorld().getName()
                + " "
                + location.getBlockX()
                + ","
                + location.getBlockY()
                + ","
                + location.getBlockZ();
    }

    public String nullable(String value) {
        return value == null ? "<none>" : value;
    }

    public boolean isApplyQueueBusy() {
        return applyScheduler.queuedJobs() > 0;
    }

    public String enqueueApplyJob(
            CommandSender sender,
            String operation,
            List<BlockChangeRecord> changes,
            Consumer<ApplySummary> onComplete) {
        ApplyEnqueueResult result = applyScheduler.enqueueJob(changes, onComplete);
        if (!result.accepted()) {
            send(sender, "common.failed-to-queue", operation, result.message());
            return null;
        }
        if (result.message() != null && !result.message().isBlank()) {
            sender.sendMessage(result.message());
        }
        return result.jobId();
    }

    public RepositoryState advanceBranchAfterAsyncCommit(
            RepositoryState latestState,
            String commitBranch,
            String expectedParentCommitId,
            String newCommitId) {
        if (!Objects.equals(latestState.headOf(commitBranch), expectedParentCommitId)) {
            return latestState;
        }
        RepositoryState updated = latestState.withBranchHead(commitBranch, newCommitId);
        if (commitBranch.equals(latestState.currentBranch())
                && Objects.equals(latestState.activeCommitId(), expectedParentCommitId)) {
            updated = updated.withCurrentBranch(commitBranch, newCommitId);
        }
        return updated;
    }

    public String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public void runGuarded(CommandSender sender, MutationTicket ticket, Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable throwable) {
            plugin.getLogger()
                    .log(Level.SEVERE, "Unhandled GitBlock operation error (" + nullable(activeMutation.get()) + ")", throwable);
            send(sender, "common.operation-failed", rootMessage(throwable));
            if (ticket != null) {
                ticket.close();
            }
        }
    }

    private void releaseMutation(String description) {
        activeMutation.compareAndSet(description, null);
    }

    private String normalizeOperationDescription(String description) {
        if (description == null) {
            return "operation";
        }
        String normalized = description.trim().replaceAll("\\s+", " ");
        return normalized.isBlank() ? "operation" : normalized;
    }

    public static final class MutationTicket implements AutoCloseable {
        private final GitBlockCommandEnv env;
        private final String description;
        private final boolean noOp;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private MutationTicket(GitBlockCommandEnv env, String description, boolean noOp) {
            this.env = env;
            this.description = description;
            this.noOp = noOp;
        }

        private static MutationTicket noOp() {
            return new MutationTicket(null, "", true);
        }

        @Override
        public void close() {
            if (noOp) {
                return;
            }
            if (closed.compareAndSet(false, true)) {
                env.releaseMutation(description);
            }
        }
    }
}
