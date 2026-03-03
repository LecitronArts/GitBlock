package io.froststream.gitblock.command;

import io.froststream.gitblock.checkout.ApplyJobStatus;
import io.froststream.gitblock.repo.RepoRegion;
import io.froststream.gitblock.repo.RepositoryRuntime;
import io.froststream.gitblock.repo.RepositoryRuntimeManager;
import io.froststream.gitblock.repo.RepositoryState;
import io.froststream.gitblock.repo.SelectionService.SelectedRegion;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class RepositoryCommandHandler {
    private final GitBlockCommandEnv env;

    public RepositoryCommandHandler(GitBlockCommandEnv env) {
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
        GitBlockCommandEnv.MutationTicket ticket = env.tryAcquireMutation(sender, "initialize repository");
        if (ticket == null) {
            return;
        }
        try {
            RepositoryRuntime runtime = env.runtime(sender);
            if (runtime == null) {
                return;
            }
            if (sender instanceof Player player && args.length > 1) {
                String requestedRepositoryName = env.sanitizeRepoName(args[1]);
                if (requestedRepositoryName.isBlank()) {
                    env.send(sender, "repository.repo-invalid-name");
                    return;
                }
                runtime = resolveOrCreateRepositoryForInit(player, requestedRepositoryName);
                if (runtime == null) {
                    return;
                }
            }

            RepositoryState state = runtime.repositoryStateService().getState();
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
            RepositoryState initialized =
                    runtime.repositoryStateService().initialize(runtime.repositoryName(), region);
            sender.sendMessage(env.tr(sender, "repository.init-success-title"));
            sender.sendMessage(env.tr(sender, "repository.init-success-name", initialized.repoName()));
            sender.sendMessage(env.tr(sender, "repository.init-success-region", describeRegion(initialized.region())));
            sender.sendMessage(
                    env.tr(
                            sender,
                            "repository.init-success-volume",
                            initialized.region().volume()));
        } finally {
            ticket.close();
        }
    }

    public void handleStatus(CommandSender sender) {
        RepositoryRuntime runtime = env.runtime(sender);
        if (runtime == null) {
            return;
        }
        RepositoryState state = runtime.repositoryStateService().getState();
        sender.sendMessage(env.tr(sender, "repository.status-title"));
        sender.sendMessage(env.tr(sender, "repository.status-repo-initialized", state.initialized()));
        sender.sendMessage(env.tr(sender, "repository.status-repo", runtime.repositoryName()));
        if (sender instanceof Player player) {
            int owned = env.ownedRepositories(player);
            int max = env.maxRepositories(sender);
            sender.sendMessage(env.tr(sender, "repository.status-repo-usage", owned, max));
        }
        if (state.initialized()) {
            sender.sendMessage(env.tr(sender, "repository.status-region", describeRegion(state.region())));
            sender.sendMessage(env.tr(sender, "repository.status-current-branch", state.currentBranch()));
            sender.sendMessage(env.tr(sender, "repository.status-active-commit", env.nullable(state.activeCommitId())));
            sender.sendMessage(
                    env.tr(
                            sender,
                            "repository.status-branch-head",
                            env.nullable(state.headOf(state.currentBranch()))));
        }
        sender.sendMessage(env.tr(sender, "repository.status-dirty-blocks", runtime.dirtyMap().size()));
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

    public void handleRepo(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            env.send(sender, "common.player-only");
            return;
        }
        if (args.length < 2) {
            sendRepoUsage(sender);
            return;
        }
        String sub = args[1].toLowerCase();
        switch (sub) {
            case "list" -> handleRepoList(player);
            case "create" -> handleRepoCreate(player, args);
            case "use", "switch" -> handleRepoUse(player, args);
            default -> sendRepoUsage(sender);
        }
    }

    public void handleCommitMessage(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            env.send(sender, "common.player-only");
            return;
        }
        if (args.length < 2) {
            sendCommitMessageUsage(sender);
            return;
        }
        String sub = args[1].toLowerCase();
        switch (sub) {
            case "show" -> {
                String custom = env.playerCommitMessageTemplate(player);
                if (custom == null) {
                    env.send(
                            sender,
                            "repository.commitmsg-show-default",
                            env.defaultCommitMessageTemplate());
                } else {
                    env.send(sender, "repository.commitmsg-show-custom", custom);
                }
            }
            case "set" -> {
                if (args.length < 3) {
                    sendCommitMessageUsage(sender);
                    return;
                }
                String template = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
                if (template.isBlank()) {
                    env.send(sender, "repository.commitmsg-invalid");
                    return;
                }
                env.setCommitMessageTemplate(player, template);
                env.send(sender, "repository.commitmsg-set", template);
            }
            case "reset" -> {
                env.clearCommitMessageTemplate(player);
                env.send(
                        sender,
                        "repository.commitmsg-reset",
                        env.defaultCommitMessageTemplate());
            }
            default -> sendCommitMessageUsage(sender);
        }
    }

    private RepositoryRuntime resolveOrCreateRepositoryForInit(
            Player player, String requestedRepositoryName) {
        if (env.isRepositoryOwner(player, requestedRepositoryName)) {
            env.useRepository(player, requestedRepositoryName);
            return env.runtimeManager().runtimeForRepository(requestedRepositoryName);
        }
        if (env.repositoryExists(requestedRepositoryName)) {
            env.send(player, "repository.repo-name-taken", requestedRepositoryName);
            return null;
        }
        RepositoryRuntimeManager.RepositoryCreateStatus createStatus =
                env.createRepositoryForPlayer(player, requestedRepositoryName);
        if (createStatus == RepositoryRuntimeManager.RepositoryCreateStatus.LIMIT_REACHED) {
            env.send(
                    player,
                    "repository.repo-limit-reached",
                    env.ownedRepositories(player),
                    env.maxRepositories(player));
            return null;
        }
        if (createStatus == RepositoryRuntimeManager.RepositoryCreateStatus.INVALID_NAME) {
            env.send(player, "repository.repo-invalid-name");
            return null;
        }
        if (createStatus == RepositoryRuntimeManager.RepositoryCreateStatus.NAME_TAKEN) {
            env.send(player, "repository.repo-name-taken", requestedRepositoryName);
            return null;
        }
        env.useRepository(player, requestedRepositoryName);
        env.send(player, "repository.repo-created", requestedRepositoryName);
        return env.runtimeManager().runtimeForRepository(requestedRepositoryName);
    }

    private void handleRepoList(Player player) {
        RepositoryRuntime activeRuntime = env.runtime(player);
        if (activeRuntime == null) {
            return;
        }
        String activeRepository = activeRuntime.repositoryName();
        List<String> repositories = env.listOwnedRepositories(player);
        env.send(player, "repository.repo-list-title");
        for (String repositoryName : repositories) {
            RepositoryState state =
                    env.runtimeManager()
                            .runtimeForRepository(repositoryName)
                            .repositoryStateService()
                            .getState();
            String marker = repositoryName.equals(activeRepository) ? "*" : " ";
            env.send(
                    player,
                    "repository.repo-list-line",
                    marker,
                    repositoryName,
                    state.initialized());
        }
        env.send(
                player,
                "repository.repo-list-summary",
                repositories.size(),
                env.maxRepositories(player));
    }

    private void handleRepoCreate(Player player, String[] args) {
        if (args.length < 3) {
            sendRepoUsage(player);
            return;
        }
        String repositoryName = env.sanitizeRepoName(args[2]);
        if (repositoryName.isBlank()) {
            env.send(player, "repository.repo-invalid-name");
            return;
        }
        RepositoryRuntimeManager.RepositoryCreateStatus createStatus =
                env.createRepositoryForPlayer(player, repositoryName);
        switch (createStatus) {
            case CREATED -> {
                env.useRepository(player, repositoryName);
                env.send(player, "repository.repo-created", repositoryName);
                env.send(player, "repository.repo-used", repositoryName);
            }
            case ALREADY_EXISTS -> {
                env.useRepository(player, repositoryName);
                env.send(player, "repository.repo-already-owned", repositoryName);
                env.send(player, "repository.repo-used", repositoryName);
            }
            case NAME_TAKEN -> env.send(player, "repository.repo-name-taken", repositoryName);
            case LIMIT_REACHED ->
                    env.send(
                            player,
                            "repository.repo-limit-reached",
                            env.ownedRepositories(player),
                            env.maxRepositories(player));
            case INVALID_NAME -> env.send(player, "repository.repo-invalid-name");
        }
    }

    private void handleRepoUse(Player player, String[] args) {
        if (args.length < 3) {
            sendRepoUsage(player);
            return;
        }
        String repositoryName = env.sanitizeRepoName(args[2]);
        if (repositoryName.isBlank()) {
            env.send(player, "repository.repo-invalid-name");
            return;
        }
        if (!env.useRepository(player, repositoryName)) {
            env.send(player, "repository.repo-use-denied", repositoryName);
            return;
        }
        env.send(player, "repository.repo-used", repositoryName);
    }

    private void sendRepoUsage(CommandSender sender) {
        env.send(sender, "repository.repo-usage-list");
        env.send(sender, "repository.repo-usage-create");
        env.send(sender, "repository.repo-usage-use");
    }

    private void sendCommitMessageUsage(CommandSender sender) {
        env.send(sender, "repository.commitmsg-usage-show");
        env.send(sender, "repository.commitmsg-usage-set");
        env.send(sender, "repository.commitmsg-usage-reset");
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
