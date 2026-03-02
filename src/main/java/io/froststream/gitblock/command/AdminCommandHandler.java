package io.froststream.gitblock.command;

import io.froststream.gitblock.checkout.ApplyJobStatus;
import io.froststream.gitblock.repo.RepositoryState;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;

public final class AdminCommandHandler {
    private final GitBlockCommandEnv env;
    private final BenchmarkCommandHandler benchmark;

    public AdminCommandHandler(GitBlockCommandEnv env, BenchmarkCommandHandler benchmark) {
        this.env = env;
        this.benchmark = benchmark;
    }

    public void handleCheckpoint(CommandSender sender, String[] args) {
        if (!sender.hasPermission("gitblock.admin")) {
            env.send(sender, "common.no-permission-admin");
            return;
        }
        RepositoryState state = env.requireInitialized(sender);
        if (state == null) {
            return;
        }
        if (args.length < 2 || !"now".equalsIgnoreCase(args[1])) {
            env.send(sender, "admin.checkpoint.usage");
            return;
        }
        if (state.activeCommitId() == null) {
            env.send(sender, "admin.checkpoint.no-active");
            return;
        }
        env.checkpointService().forceCheckpoint(state.activeCommitId());
        env.send(sender, "admin.checkpoint.requested", state.activeCommitId());
    }

    public void handleJobs(CommandSender sender) {
        ApplyJobStatus status = env.applyScheduler().currentJobStatus();
        if (status == null) {
            env.send(sender, "admin.jobs.no-active-apply");
        } else {
            env.send(
                    sender,
                    "admin.jobs.active-apply",
                    status.jobId(),
                    status.processed(),
                    status.total(),
                    status.applied(),
                    status.failed(),
                    status.durationMillis());
        }
        List<String> applyJobIds = env.applyScheduler().jobIds();
        String none = env.trRaw(sender, "common.none");
        env.send(
                sender,
                "admin.jobs.apply-jobs",
                applyJobIds.isEmpty() ? none : String.join(", ", applyJobIds));
        env.send(
                sender,
                "admin.jobs.apply-policy",
                env.applyScheduler().maxQueuedJobs(),
                env.applyScheduler().queueOverflowPolicyName());
        List<String> snapshotIds = benchmark.activeSnapshotProcessIds();
        env.send(
                sender,
                "admin.jobs.snapshot-processes",
                snapshotIds.isEmpty() ? none : String.join(", ", snapshotIds));
    }

    public void handleCancel(CommandSender sender, String[] args) {
        if (!sender.hasPermission("gitblock.admin")) {
            env.send(sender, "common.no-permission-admin");
            return;
        }
        String target = args.length > 1 ? args[1] : "all";
        if ("all".equalsIgnoreCase(target)) {
            int cancelledJobs = env.applyScheduler().cancelAll();
            int cancelledSnapshots = benchmark.cancelAllSnapshotProcesses(sender);
            env.send(sender, "admin.cancel.all", cancelledJobs, cancelledSnapshots);
            return;
        }

        if (env.applyScheduler().cancelJob(target)) {
            env.send(sender, "admin.cancel.apply", target);
            return;
        }
        if (benchmark.cancelSnapshotProcess(target, sender)) {
            env.send(sender, "admin.cancel.snapshot", target);
            return;
        }
        env.send(sender, "admin.cancel.not-found", target);
    }

    public List<String> cancelTargets() {
        List<String> targets = new ArrayList<>();
        targets.add("all");
        targets.addAll(env.applyScheduler().jobIds());
        targets.addAll(benchmark.activeSnapshotProcessIds());
        return targets;
    }
}
