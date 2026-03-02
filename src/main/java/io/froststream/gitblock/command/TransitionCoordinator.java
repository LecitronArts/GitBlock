package io.froststream.gitblock.command;

import io.froststream.gitblock.repo.RepositoryState;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

public final class TransitionCoordinator {
    private final GitBlockCommandEnv env;

    public TransitionCoordinator(GitBlockCommandEnv env) {
        this.env = env;
    }

    public void runTransition(
            CommandSender sender,
            String fromCommit,
            String targetCommit,
            boolean switchBranchAfterSuccess,
            boolean advanceTargetBranchHeadAfterSuccess,
            String targetBranchName) {
        GitBlockCommandEnv.MutationTicket ticket =
                env.tryAcquireMutation(
                        sender,
                        "transition "
                                + env.nullable(fromCommit)
                                + " -> "
                                + env.nullable(targetCommit));
        if (ticket == null) {
            return;
        }
        runTransitionWithTicket(
                sender,
                fromCommit,
                targetCommit,
                switchBranchAfterSuccess,
                advanceTargetBranchHeadAfterSuccess,
                targetBranchName,
                ticket);
    }

    public void runTransitionWithTicket(
            CommandSender sender,
            String fromCommit,
            String targetCommit,
            boolean switchBranchAfterSuccess,
            boolean advanceTargetBranchHeadAfterSuccess,
            String targetBranchName,
            GitBlockCommandEnv.MutationTicket ticket) {
        if (env.isApplyQueueBusy()) {
            env.send(sender, "transition.apply-queue-busy");
            ticket.close();
            return;
        }
        env.send(
                sender,
                "transition.computing",
                env.nullable(fromCommit),
                env.nullable(targetCommit));
        try {
            env.transitionService().buildTransition(fromCommit, targetCommit)
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
                                                                                    "transition.checkout-failed",
                                                                                    env.rootMessage(throwable));
                                                                        ticket.close();
                                                                        return;
                                                                    }
                                                                    if (changes.isEmpty()) {
                                                                        RepositoryState latest =
                                                                                env.repositoryStateService()
                                                                                        .getState();
                                                                        RepositoryState updated =
                                                                                latest.withActiveCommitOnly(targetCommit);
                                                                        if (switchBranchAfterSuccess) {
                                                                            updated =
                                                                                    updated.withCurrentBranch(
                                                                                            targetBranchName,
                                                                                            targetCommit);
                                                                        }
                                                                        if (advanceTargetBranchHeadAfterSuccess) {
                                                                            updated =
                                                                                    updated.withBranchHead(
                                                                                            targetBranchName,
                                                                                            targetCommit);
                                                                        }
                                                                        env.repositoryStateService().update(updated);
                                                                        env.send(sender, "transition.already-target");
                                                                        ticket.close();
                                                                        return;
                                                                    }
                                                                    if (env.isApplyQueueBusy()) {
                                                                        env.send(
                                                                                sender,
                                                                                "transition.apply-queue-became-busy");
                                                                        ticket.close();
                                                                        return;
                                                                    }

                                                                    String jobId =
                                                                            env.enqueueApplyJob(
                                                                                    sender,
                                                                                    "transition apply",
                                                                                            changes,
                                                                                            summary ->
                                                                                                    env.runGuarded(
                                                                                                            sender,
                                                                                                            ticket,
                                                                                                            () -> {
                                                                                                                if (summary.failed() > 0) {
                                                                                                                    env.dirtyMap()
                                                                                                                            .restoreAll(
                                                                                                                                    summary.appliedDelta());
                                                                                                                    env.send(
                                                                                                                            sender,
                                                                                                                            "transition.finished-with-failures",
                                                                                                                            summary.failed());
                                                                                                                    ticket.close();
                                                                                                                    return;
                                                                                                                }
                                                                                                                RepositoryState latest =
                                                                                                                        env.repositoryStateService()
                                                                                                                                .getState();
                                                                                                                RepositoryState updated =
                                                                                                                        latest.withActiveCommitOnly(
                                                                                                                                targetCommit);
                                                                                                                if (switchBranchAfterSuccess) {
                                                                                                                    updated =
                                                                                                                            updated.withCurrentBranch(
                                                                                                                                    targetBranchName,
                                                                                                                                    targetCommit);
                                                                                                                }
                                                                                                                if (advanceTargetBranchHeadAfterSuccess) {
                                                                                                                    updated =
                                                                                                                            updated.withBranchHead(
                                                                                                                                    targetBranchName,
                                                                                                                                    targetCommit);
                                                                                                                }
                                                                                                                env.repositoryStateService()
                                                                                                                        .update(updated);
                                                                                                                env.send(
                                                                                                                        sender,
                                                                                                                        "transition.complete",
                                                                                                                        env.nullable(
                                                                                                                                fromCommit),
                                                                                                                        env.nullable(
                                                                                                                                targetCommit),
                                                                                                                        summary.applied());
                                                                                                                ticket.close();
                                                                                                            }));
                                                                    if (jobId == null) {
                                                                        ticket.close();
                                                                        return;
                                                                    }
                                                                    env.send(
                                                                            sender,
                                                                            "transition.job-queued",
                                                                            jobId,
                                                                            changes.size());
                                                                    })));
        } catch (Throwable throwable) {
            env.send(sender, "transition.failed-start", env.rootMessage(throwable));
            ticket.close();
        }
    }
}
