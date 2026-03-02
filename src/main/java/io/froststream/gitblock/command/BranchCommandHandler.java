package io.froststream.gitblock.command;

import io.froststream.gitblock.model.MergeConflict;
import io.froststream.gitblock.model.MergePlan;
import io.froststream.gitblock.model.BlockChangeRecord;
import io.froststream.gitblock.repo.RepositoryState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

public final class BranchCommandHandler {
    private final GitBlockCommandEnv env;
    private final TransitionCoordinator transitions;

    public BranchCommandHandler(GitBlockCommandEnv env, TransitionCoordinator transitions) {
        this.env = env;
        this.transitions = transitions;
    }

    public void handleBranch(CommandSender sender, String[] args) {
        GitBlockCommandEnv.MutationTicket ticket = env.tryAcquireMutation(sender, "create branch");
        if (ticket == null) {
            return;
        }
        RepositoryState state = env.requireInitialized(sender);
        if (state == null) {
            ticket.close();
            return;
        }
        if (args.length < 2) {
            env.send(sender, "branch.usage-branch");
            ticket.close();
            return;
        }
        String name = env.sanitizeBranchName(args[1]);
        if (name.isBlank()) {
            env.send(sender, "branch.invalid-name");
            ticket.close();
            return;
        }
        if (env.isReservedReferenceToken(name)) {
            env.send(sender, "branch.reserved-name", name);
            ticket.close();
            return;
        }
        if (state.hasBranch(name)) {
            env.send(sender, "branch.already-exists", name);
            ticket.close();
            return;
        }
        env.repositoryStateService().update(state.withNewBranch(name, state.activeCommitId()));
        env.send(sender, "branch.created", name, env.nullable(state.activeCommitId()));
        ticket.close();
    }

    public void handleBranches(CommandSender sender) {
        RepositoryState state = env.requireInitialized(sender);
        if (state == null) {
            return;
        }
        sender.sendMessage(env.tr(sender, "branch.list-title"));
        for (Map.Entry<String, String> entry : state.branchHeads().entrySet()) {
            String marker = entry.getKey().equals(state.currentBranch()) ? "*" : " ";
            sender.sendMessage(
                    env.tr(
                            sender,
                            "branch.list-line",
                            marker,
                            entry.getKey(),
                            env.nullable(entry.getValue())));
        }
    }

    public void handleSwitch(CommandSender sender, String[] args) {
        RepositoryState state = env.requireInitialized(sender);
        if (state == null) {
            return;
        }
        if (env.isApplyQueueBusy()) {
            env.send(sender, "branch.apply-queue-busy");
            return;
        }
        if (args.length < 2) {
            env.send(sender, "branch.usage-switch");
            return;
        }
        if (env.dirtyMap().size() > 0) {
            env.send(sender, "branch.working-tree-dirty");
            return;
        }

        String targetBranch = args[1];
        if (!state.hasBranch(targetBranch)) {
            env.send(sender, "branch.unknown-branch", targetBranch);
            return;
        }
        if (targetBranch.equals(state.currentBranch())) {
            env.send(sender, "branch.already-on-branch", targetBranch);
            return;
        }

        String targetCommit = state.headOf(targetBranch);
        transitions.runTransition(sender, state.activeCommitId(), targetCommit, true, false, targetBranch);
    }

    public void handleMerge(CommandSender sender, String[] args) {
        GitBlockCommandEnv.MutationTicket ticket = env.tryAcquireMutation(sender, "merge");
        if (ticket == null) {
            return;
        }
        RepositoryState state = env.requireInitialized(sender);
        if (state == null) {
            ticket.close();
            return;
        }
        if (env.isApplyQueueBusy()) {
            env.send(sender, "branch.apply-queue-busy");
            ticket.close();
            return;
        }
        if (env.dirtyMap().size() > 0) {
            env.send(sender, "branch.working-tree-dirty");
            ticket.close();
            return;
        }
        if (args.length < 2) {
            env.send(sender, "branch.usage-merge");
            ticket.close();
            return;
        }

        String theirsBranch = args[1];
        if (!state.hasBranch(theirsBranch)) {
            env.send(sender, "branch.unknown-branch", theirsBranch);
            ticket.close();
            return;
        }
        if (theirsBranch.equals(state.currentBranch())) {
            env.send(sender, "branch.merge-self");
            ticket.close();
            return;
        }

        String oursHead = state.headOf(state.currentBranch());
        String theirsHead = state.headOf(theirsBranch);
        if (theirsHead == null) {
            env.send(sender, "branch.target-empty", theirsBranch);
            ticket.close();
            return;
        }
        if (!Objects.equals(state.activeCommitId(), oursHead)) {
            env.send(sender, "branch.active-not-head");
            ticket.close();
            return;
        }
        if (oursHead == null) {
            env.send(sender, "branch.fast-forward-empty");
            transitions.runTransitionWithTicket(
                    sender, null, theirsHead, false, true, state.currentBranch(), ticket);
            return;
        }

        env.send(sender, "branch.planning-merge", theirsBranch, state.currentBranch());
        try {
            env.transitionService().planMerge(oursHead, theirsHead)
                    .whenComplete(
                            (mergePlan, throwable) ->
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
                                                                                    "branch.merge-failed",
                                                                                    env.rootMessage(throwable));
                                                                            ticket.close();
                                                                            return;
                                                                        }
                                                                        continueMerge(
                                                                                sender,
                                                                                state,
                                                                                theirsBranch,
                                                                                mergePlan,
                                                                                ticket);
                                                                    })));
        } catch (Throwable throwable) {
            env.send(sender, "branch.merge-failed-start", env.rootMessage(throwable));
            ticket.close();
        }
    }

    private void continueMerge(
            CommandSender sender,
            RepositoryState state,
            String theirsBranch,
            MergePlan mergePlan,
            GitBlockCommandEnv.MutationTicket ticket) {
        if (mergePlan.hasConflicts()) {
            Path conflictFile = writeConflictFile(state.repoName(), theirsBranch, mergePlan.conflicts());
            env.send(
                    sender,
                    "branch.conflicts-detected",
                    mergePlan.conflicts().size(),
                    conflictFile.getFileName());
            ticket.close();
            return;
        }
        if (mergePlan.applyChanges().isEmpty()) {
            env.send(sender, "branch.already-up-to-date");
            ticket.close();
            return;
        }

        String oursHead = state.activeCommitId();
        String theirsHead = state.headOf(theirsBranch);
        if (env.isApplyQueueBusy()) {
            env.send(sender, "branch.apply-queue-became-busy");
            ticket.close();
            return;
        }
        env.send(sender, "branch.applying-merged", mergePlan.applyChanges().size());
        List<BlockChangeRecord> rollbackChanges = env.reverseChanges(mergePlan.applyChanges());
        String jobId =
                env.enqueueApplyJob(
                        sender,
                        "merge apply",
                                mergePlan.applyChanges(),
                                summary ->
                                        env.runGuarded(
                                                sender,
                                                ticket,
                                                () -> {
                                                    if (summary.failed() > 0) {
                                                        env.dirtyMap().restoreAll(summary.appliedDelta());
                                                        env.send(
                                                                sender,
                                                                "branch.merge-apply-failed",
                                                                summary.failed());
                                                        ticket.close();
                                                        return;
                                                    }
                                                    env.commitWorker().submitCommit(
                                                                    "merge " + theirsBranch + " into " + state.currentBranch(),
                                                                    env.resolveAuthor(sender),
                                                                    state.currentBranch(),
                                                                    oursHead,
                                                                    theirsHead,
                                                                    mergePlan.applyChanges())
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
                                                                                                                    queueMergeCommitFailureRecovery(
                                                                                                                            sender,
                                                                                                                            theirsBranch,
                                                                                                                            rollbackChanges,
                                                                                                                            throwable,
                                                                                                                            ticket);
                                                                                                                    return;
                                                                                                                }
                                                                                                                RepositoryState latest =
                                                                                                                        env.repositoryStateService()
                                                                                                                                .getState();
                                                                                                                RepositoryState updated =
                                                                                                                        env.advanceBranchAfterAsyncCommit(
                                                                                                                                latest,
                                                                                                                                state.currentBranch(),
                                                                                                                                oursHead,
                                                                                                                                result.commitId());
                                                                                                                env.repositoryStateService()
                                                                                                                        .update(updated);
                                                                                                                env.checkpointService()
                                                                                                                        .maybeCreateCheckpoint(
                                                                                                                                result);
                                                                                                                if (updated == latest) {
                                                                                                                    env.markChangesDirty(
                                                                                                                            mergePlan.applyChanges());
                                                                                                                    env.send(
                                                                                                                            sender,
                                                                                                                            "branch.merge-commit-saved-dirty",
                                                                                                                            result.commitId());
                                                                                                                    ticket.close();
                                                                                                                    return;
                                                                                                                }
                                                                                                                env.send(
                                                                                                                        sender,
                                                                                                                        "branch.merge-commit-created",
                                                                                                                        result.commitId(),
                                                                                                                        result.changeCount());
                                                                                                                ticket.close();
                                                                                                            })));
                                                }));
        if (jobId == null) {
            ticket.close();
            return;
        }
        env.send(sender, "branch.merge-apply-queued", jobId);
    }

    private void queueMergeCommitFailureRecovery(
            CommandSender sender,
            String theirsBranch,
            List<BlockChangeRecord> recoveryChanges,
            Throwable throwable,
            GitBlockCommandEnv.MutationTicket ticket) {
        env.send(sender, "branch.merge-commit-failed", env.rootMessage(throwable));
        env.send(sender, "branch.merge-rollback-attempt");
        String recoveryJobId =
                env.enqueueApplyJob(
                        sender,
                        "merge rollback recovery",
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
                                                                "branch.merge-rollback-partial-fail",
                                                                summary.failed());
                                                        ticket.close();
                                                        return;
                                                    }
                                                    env.send(sender, "branch.merge-rollback-complete", theirsBranch);
                                                    ticket.close();
                                                }));
        if (recoveryJobId == null) {
            ticket.close();
            return;
        }
        env.send(sender, "branch.merge-rollback-queued", recoveryJobId, recoveryChanges.size());
    }

    private Path writeConflictFile(String repoName, String theirsBranch, List<MergeConflict> conflicts) {
        try {
            Files.createDirectories(env.conflictsDir());
            String fileName =
                    safeFileToken(repoName)
                            + "-"
                            + safeFileToken(theirsBranch)
                            + "-"
                            + Instant.now().toEpochMilli()
                            + ".conflicts";
            Path file = env.conflictsDir().resolve(fileName);
            List<String> lines = new ArrayList<>();
            lines.add("# GitBlock merge conflicts");
            lines.add("# theirs=" + theirsBranch);
            lines.add("# count=" + conflicts.size());
            for (MergeConflict conflict : conflicts) {
                lines.add(
                        conflict.world()
                                + "\t"
                                + conflict.x()
                                + "\t"
                                + conflict.y()
                                + "\t"
                                + conflict.z()
                                + "\t"
                                + conflict.baseState()
                                + "\t"
                                + conflict.oursState()
                                + "\t"
                                + conflict.theirsState());
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
            return file;
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to write conflict file", ioException);
        }
    }

    private String safeFileToken(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
