package io.froststream.gitblock.command;

import io.froststream.gitblock.model.BlockChangeRecord;
import io.froststream.gitblock.model.CommitSummary;
import io.froststream.gitblock.model.DiffSummary;
import io.froststream.gitblock.model.DirtyEntry;
import io.froststream.gitblock.model.LocationKey;
import io.froststream.gitblock.repo.RepositoryState;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

public final class HistoryCommandHandler {
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final GitBlockCommandEnv env;
    private final TransitionCoordinator transitions;
    private final Map<String, Long> diffCooldownUntilBySender = new ConcurrentHashMap<>();

    public HistoryCommandHandler(GitBlockCommandEnv env, TransitionCoordinator transitions) {
        this.env = env;
        this.transitions = transitions;
    }

    public void handleCommit(CommandSender sender, String[] args) {
        GitBlockCommandEnv.MutationTicket ticket = env.tryAcquireMutation(sender, "commit");
        if (ticket == null) {
            return;
        }
        RepositoryState state = env.requireInitialized(sender);
        if (state == null) {
            ticket.close();
            return;
        }
        if (env.isApplyQueueBusy()) {
            env.send(sender, "history.commit-apply-busy");
            ticket.close();
            return;
        }

        Map<LocationKey, DirtyEntry> drained = env.dirtyMap().drainAll();
        if (drained.isEmpty()) {
            env.send(sender, "history.commit-no-dirty");
            ticket.close();
            return;
        }

        String message =
                args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "manual commit";
        UUID author = env.resolveAuthor(sender);
        String commitBranch = state.currentBranch();
        String expectedParentCommitId = state.activeCommitId();
        List<BlockChangeRecord> changes = env.toBlockChanges(drained);
        env.send(sender, "history.commit-start", changes.size());

        try {
            env.commitWorker().submitCommit(
                            message,
                            author,
                            commitBranch,
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
                                                                            env.dirtyMap().restoreAll(drained);
                                                                            env.send(
                                                                                    sender,
                                                                                    "history.commit-failed-restored",
                                                                                    env.rootMessage(throwable));
                                                                        ticket.close();
                                                                        return;
                                                                    }
                                                                    RepositoryState current =
                                                                            env.repositoryStateService().getState();
                                                                    RepositoryState updated =
                                                                            env.advanceBranchAfterAsyncCommit(
                                                                                    current,
                                                                                    commitBranch,
                                                                                    expectedParentCommitId,
                                                                                    result.commitId());
                                                                    env.repositoryStateService().update(updated);
                                                                    env.checkpointService().maybeCreateCheckpoint(result);
                                                                    if (updated == current) {
                                                                        env.dirtyMap().restoreAll(drained);
                                                                        env.send(
                                                                                sender,
                                                                                "history.commit-saved-dirty",
                                                                                result.commitId());
                                                                        ticket.close();
                                                                        return;
                                                                    }
                                                                    env.send(
                                                                            sender,
                                                                            "history.commit-saved",
                                                                            result.commitId(),
                                                                            result.commitNumber(),
                                                                            result.changeCount());
                                                                    ticket.close();
                                                                    })));
        } catch (Throwable throwable) {
            env.dirtyMap().restoreAll(drained);
            env.send(sender, "history.commit-start-failed", env.rootMessage(throwable));
            ticket.close();
        }
    }

    public void handleLog(CommandSender sender, String[] args) {
        int limit = 10;
        if (args.length > 1) {
            try {
                limit = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
                env.send(sender, "history.log-invalid-limit");
            }
        }

        List<CommitSummary> summaries = env.commitWorker().tail(limit);
        if (summaries.isEmpty()) {
            env.send(sender, "history.log-empty");
            return;
        }

        sender.sendMessage(env.tr(sender, "history.log-title"));
        for (CommitSummary summary : summaries) {
            sender.sendMessage(
                    env.tr(
                            sender,
                            "history.log-line",
                            summary.commitId(),
                            summary.commitNumber(),
                            summary.branchName(),
                            TIME_FORMATTER.format(summary.createdAt()),
                            summary.changeCount(),
                            summary.message()));
        }
    }

    public void handleCheckout(CommandSender sender, String[] args) {
        RepositoryState state = env.requireInitialized(sender);
        if (state == null) {
            return;
        }
        if (env.isApplyQueueBusy()) {
            env.send(sender, "history.apply-queue-busy");
            return;
        }
        if (env.dirtyMap().size() > 0) {
            env.send(sender, "history.checkout-working-tree-dirty");
            return;
        }
        if (args.length < 2) {
            env.send(sender, "history.usage-checkout");
            return;
        }

        String targetCommitId = env.resolveCommitToken(args[1], state);
        if (targetCommitId == null && !"null".equalsIgnoreCase(args[1])) {
            env.send(sender, "history.unknown-commit-or-branch", args[1]);
            return;
        }
        transitions.runTransition(
                sender,
                state.activeCommitId(),
                targetCommitId,
                false,
                false,
                state.currentBranch());
    }

    public void handleDiff(CommandSender sender, String[] args) {
        RepositoryState state = env.requireInitialized(sender);
        if (state == null) {
            return;
        }
        if (args.length < 3) {
            env.send(sender, "history.usage-diff");
            return;
        }

        String fromCommit = env.resolveCommitToken(args[1], state);
        String toCommit = env.resolveCommitToken(args[2], state);
        if (fromCommit == null && !"null".equalsIgnoreCase(args[1])) {
            env.send(sender, "history.unknown-from-token", args[1]);
            return;
        }
        if (toCommit == null && !"null".equalsIgnoreCase(args[2])) {
            env.send(sender, "history.unknown-to-token", args[2]);
            return;
        }
        if (!tryAcquireDiffSlot(sender)) {
            return;
        }

        env.transitionService().diff(fromCommit, toCommit, 10)
                .whenComplete(
                        (diff, throwable) ->
                                Bukkit.getScheduler()
                                        .runTask(
                                                env.plugin(),
                                                () -> {
                                                    if (throwable != null) {
                                                        env.send(sender, "history.diff-failed", env.rootMessage(throwable));
                                                        return;
                                                    }
                                                    printDiff(sender, diff);
                                                }));
    }

    public void handleRevert(CommandSender sender, String[] args) {
        GitBlockCommandEnv.MutationTicket ticket = env.tryAcquireMutation(sender, "revert");
        if (ticket == null) {
            return;
        }
        RepositoryState state = env.requireInitialized(sender);
        if (state == null) {
            ticket.close();
            return;
        }
        if (env.isApplyQueueBusy()) {
            env.send(sender, "history.apply-queue-busy");
            ticket.close();
            return;
        }
        if (env.dirtyMap().size() > 0) {
            env.send(sender, "history.revert-working-tree-dirty");
            ticket.close();
            return;
        }
        if (args.length < 2) {
            env.send(sender, "history.usage-revert");
            ticket.close();
            return;
        }
        String commitId = args[1];
        if (!env.commitWorker().hasCommit(commitId)) {
            env.send(sender, "history.unknown-commit", commitId);
            ticket.close();
            return;
        }

        try {
            env.commitWorker().readCommitChanges(commitId)
                    .whenComplete(
                            (changes, throwable) ->
                                    Bukkit.getScheduler()
                                            .runTask(
                                                    env.plugin(),
                                                    () ->
                                                            env.runGuarded(
                                                                    sender,
                                                                    ticket,
                                                                    () -> {
                                                                        if (throwable != null) {
                                                                            env.send(
                                                                                    sender,
                                                                                    "history.read-commit-failed",
                                                                                    env.rootMessage(throwable));
                                                                            ticket.close();
                                                                            return;
                                                                        }
                                                                        queueRevert(sender, state, commitId, changes, ticket);
                                                                    })));
        } catch (Throwable throwable) {
            env.send(sender, "history.revert-start-failed", env.rootMessage(throwable));
            ticket.close();
        }
    }

    private void queueRevert(
            CommandSender sender,
            RepositoryState state,
            String commitId,
            List<BlockChangeRecord> changes,
            GitBlockCommandEnv.MutationTicket ticket) {
        if (env.isApplyQueueBusy()) {
            env.send(sender, "history.apply-queue-became-busy");
            ticket.close();
            return;
        }
        List<BlockChangeRecord> reverse = env.reverseChanges(changes);
        String revertBranch = state.currentBranch();
        String expectedParentCommitId = state.activeCommitId();
        String jobId =
                env.enqueueApplyJob(
                        sender,
                        "revert apply",
                                reverse,
                                summary ->
                                        env.runGuarded(
                                                sender,
                                                ticket,
                                                () -> {
                                                    if (summary.failed() > 0) {
                                                        env.dirtyMap().restoreAll(summary.appliedDelta());
                                                        env.send(sender, "history.revert-failed-during-apply");
                                                        ticket.close();
                                                        return;
                                                    }
                                                    env.commitWorker().submitCommit(
                                                                    "revert " + commitId,
                                                                    env.resolveAuthor(sender),
                                                                    revertBranch,
                                                                    expectedParentCommitId,
                                                                    null,
                                                                    reverse)
                                                            .whenComplete(
                                                                    (result, commitThrowable) ->
                                                                            Bukkit.getScheduler()
                                                                                    .runTask(
                                                                                            env.plugin(),
                                                                                            () ->
                                                                                                    env.runGuarded(
                                                                                                            sender,
                                                                                                            ticket,
                                                                                                            () -> {
                                                                                                                if (commitThrowable != null) {
                                                                                                                    queueRevertCommitFailureRecovery(
                                                                                                                            sender,
                                                                                                                            commitId,
                                                                                                                            changes,
                                                                                                                            commitThrowable,
                                                                                                                            ticket);
                                                                                                                    return;
                                                                                                                }
                                                                                                                RepositoryState latest =
                                                                                                                        env.repositoryStateService()
                                                                                                                                .getState();
                                                                                                                RepositoryState updated =
                                                                                                                        env.advanceBranchAfterAsyncCommit(
                                                                                                                                latest,
                                                                                                                                revertBranch,
                                                                                                                                expectedParentCommitId,
                                                                                                                                result.commitId());
                                                                                                                env.repositoryStateService()
                                                                                                                        .update(updated);
                                                                                                                env.checkpointService()
                                                                                                                        .maybeCreateCheckpoint(
                                                                                                                                result);
                                                                                                                if (updated == latest) {
                                                                                                                    env.markChangesDirty(
                                                                                                                            reverse);
                                                                                                                    env.send(
                                                                                                                            sender,
                                                                                                                            "history.revert-commit-saved-dirty",
                                                                                                                            result.commitId());
                                                                                                                    ticket.close();
                                                                                                                    return;
                                                                                                                }
                                                                                                                env.send(
                                                                                                                        sender,
                                                                                                                        "history.revert-committed",
                                                                                                                        result.commitId());
                                                                                                                ticket.close();
                                                                                                            })));
                                                }));
        if (jobId == null) {
            ticket.close();
            return;
        }
        env.send(sender, "history.revert-apply-queued", jobId, reverse.size());
    }

    private void queueRevertCommitFailureRecovery(
            CommandSender sender,
            String commitId,
            List<BlockChangeRecord> recoveryChanges,
            Throwable commitThrowable,
            GitBlockCommandEnv.MutationTicket ticket) {
        env.send(sender, "history.revert-commit-failed", env.rootMessage(commitThrowable));
        env.send(sender, "history.revert-recovery-attempt");
        String recoveryJobId =
                env.enqueueApplyJob(
                        sender,
                        "revert recovery apply",
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
                                                                "history.revert-recovery-failed",
                                                                summary.failed());
                                                        ticket.close();
                                                        return;
                                                    }
                                                    env.send(sender, "history.revert-recovery-complete", commitId);
                                                    ticket.close();
                                                }));
        if (recoveryJobId == null) {
            ticket.close();
            return;
        }
        env.send(sender, "history.revert-recovery-queued", recoveryJobId, recoveryChanges.size());
    }

    private void printDiff(CommandSender sender, DiffSummary diff) {
        sender.sendMessage(
                env.tr(
                        sender,
                        "history.diff-title",
                        env.nullable(diff.fromCommitId()),
                        env.nullable(diff.toCommitId()),
                        diff.changedBlocks()));
        for (BlockChangeRecord sample : diff.samples()) {
            sender.sendMessage(
                    env.tr(
                            sender,
                            "history.diff-line",
                            sample.world(),
                            sample.x(),
                            sample.y(),
                            sample.z(),
                            shortenState(sample.oldState()),
                            shortenState(sample.newState())));
        }
    }

    private String shortenState(String blockData) {
        if (blockData.length() <= 42) {
            return blockData;
        }
        return blockData.substring(0, 42) + "...";
    }

    private boolean tryAcquireDiffSlot(CommandSender sender) {
        long cooldownMillis =
                Math.max(0L, env.plugin().getConfig().getLong("operations.diff-cooldown-ms", 1000L));
        if (cooldownMillis == 0L) {
            return true;
        }
        long now = System.currentTimeMillis();
        String senderKey = sender.getName().toLowerCase(Locale.ROOT);
        Long availableAt = diffCooldownUntilBySender.get(senderKey);
        if (availableAt != null && now < availableAt) {
            env.send(sender, "history.diff-cooldown", (availableAt - now));
            return false;
        }
        diffCooldownUntilBySender.put(senderKey, now + cooldownMillis);
        return true;
    }
}
