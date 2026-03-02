package io.froststream.untitled8.plotgit.command;

import io.froststream.untitled8.plotgit.checkout.ApplyJobStatus;
import io.froststream.untitled8.plotgit.repo.RepoRegion;
import io.froststream.untitled8.plotgit.repo.RepositoryState;
import io.froststream.untitled8.plotgit.repo.SelectionService.SelectedRegion;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class RepositoryCommandHandler {
    private final PlotGitCommandEnv env;

    public RepositoryCommandHandler(PlotGitCommandEnv env) {
        this.env = env;
    }

    public void handlePos(CommandSender sender, boolean first) {
        if (!(sender instanceof Player player)) {
            env.send(sender, "repository.player-only-pos");
            return;
        }
        Location location = player.getLocation().toBlockLocation();
        if (first) {
            env.selectionService().setPos1(player, location);
            env.send(sender, "repository.pos1-set", env.formatLocation(location));
        } else {
            env.selectionService().setPos2(player, location);
            env.send(sender, "repository.pos2-set", env.formatLocation(location));
        }
    }

    public void handleInit(CommandSender sender, String[] args) {
        PlotGitCommandEnv.MutationTicket ticket = env.tryAcquireMutation(sender, "initialize repository");
        if (ticket == null) {
            return;
        }
        try {
            RepositoryState state = env.repositoryStateService().getState();
            if (state.initialized()) {
                env.send(sender, "repository.init-already", describeRegion(state.region()));
                return;
            }
            if (!(sender instanceof Player player)) {
                env.send(sender, "repository.init-player-only");
                return;
            }

            SelectedRegion selection = env.selectionService().get(player);
            if (!selection.complete()) {
                env.send(sender, "repository.init-selection-required");
                return;
            }
            if (!selection.pos1().getWorld().getUID().equals(selection.pos2().getWorld().getUID())) {
                env.send(sender, "repository.init-world-mismatch");
                return;
            }
            RepoRegion region =
                    RepoRegion.fromLocations(
                            selection.pos1().getWorld().getName(), selection.pos1(), selection.pos2());

            String repoName = env.storageRepoName();
            if (args.length > 1) {
                String requested = env.sanitizeRepoName(args[1]);
                if (!requested.equals(repoName)) {
                    env.send(sender, "repository.init-ignore-repo-name", requested, repoName);
                }
            }
            RepositoryState initialized = env.repositoryStateService().initialize(repoName, region);
            sender.sendMessage(env.tr(sender, "repository.init-success-title"));
            sender.sendMessage(env.tr(sender, "repository.init-success-name", initialized.repoName()));
            sender.sendMessage(env.tr(sender, "repository.init-success-region", describeRegion(initialized.region())));
            sender.sendMessage(
                    env.tr(sender, "repository.init-success-volume", initialized.region().volume()));
        } finally {
            ticket.close();
        }
    }

    public void handleStatus(CommandSender sender) {
        RepositoryState state = env.repositoryStateService().getState();
        sender.sendMessage(env.tr(sender, "repository.status-title"));
        sender.sendMessage(env.tr(sender, "repository.status-repo-initialized", state.initialized()));
        if (state.initialized()) {
            sender.sendMessage(env.tr(sender, "repository.status-repo", state.repoName()));
            sender.sendMessage(env.tr(sender, "repository.status-region", describeRegion(state.region())));
            sender.sendMessage(env.tr(sender, "repository.status-current-branch", state.currentBranch()));
            sender.sendMessage(env.tr(sender, "repository.status-active-commit", env.nullable(state.activeCommitId())));
            sender.sendMessage(
                    env.tr(
                            sender,
                            "repository.status-branch-head",
                            env.nullable(state.headOf(state.currentBranch()))));
        }
        sender.sendMessage(env.tr(sender, "repository.status-dirty-blocks", env.dirtyMap().size()));
        if (env.serializeMutationsEnabled()) {
            sender.sendMessage(
                    env.tr(
                            sender,
                            "repository.status-active-operation",
                            env.nullable(env.activeMutationDescription())));
        }
        ApplyJobStatus job = env.applyScheduler().currentJobStatus();
        if (job == null) {
            sender.sendMessage(env.tr(sender, "repository.status-apply-idle"));
        } else {
            sender.sendMessage(
                    env.tr(
                            sender,
                            "repository.status-apply-running",
                            job.jobId(),
                            job.processed(),
                            job.total(),
                            job.failed()));
        }
        sender.sendMessage(env.tr(sender, "repository.status-queued-jobs", env.applyScheduler().queuedJobs()));
    }

    private String describeRegion(RepoRegion region) {
        return region.world()
                + " ["
                + region.minX()
                + ","
                + region.minY()
                + ","
                + region.minZ()
                + "] -> ["
                + region.maxX()
                + ","
                + region.maxY()
                + ","
                + region.maxZ()
                + "]";
    }
}
