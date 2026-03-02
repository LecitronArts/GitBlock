package io.froststream.gitblock.command;

import io.froststream.gitblock.model.CommitSummary;
import io.froststream.gitblock.repo.RepositoryState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public final class GitBlockCommand implements CommandExecutor, TabCompleter {
    private final GitBlockCommandEnv env;
    private final RepositoryCommandHandler repository;
    private final HistoryCommandHandler history;
    private final BranchCommandHandler branch;
    private final AdminCommandHandler admin;
    private final BenchmarkCommandHandler benchmark;
    private final GitBlockChestMenu menu;

    public GitBlockCommand(
            GitBlockCommandEnv env,
            RepositoryCommandHandler repository,
            HistoryCommandHandler history,
            BranchCommandHandler branch,
            AdminCommandHandler admin,
            BenchmarkCommandHandler benchmark,
            GitBlockChestMenu menu) {
        this.env = env;
        this.repository = repository;
        this.history = history;
        this.branch = branch;
        this.admin = admin;
        this.benchmark = benchmark;
        this.menu = menu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gitblock.use")) {
            env.send(sender, "common.no-permission-use");
            return true;
        }

        String subCommand = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (subCommand) {
            case "help" -> sendHelp(sender, label);
            case "pos1" -> repository.handlePos(sender, true);
            case "pos2" -> repository.handlePos(sender, false);
            case "init" -> repository.handleInit(sender, args);
            case "status" -> repository.handleStatus(sender);
            case "commit" -> history.handleCommit(sender, args);
            case "log" -> history.handleLog(sender, args);
            case "checkout" -> history.handleCheckout(sender, args);
            case "diff" -> history.handleDiff(sender, args);
            case "revert" -> history.handleRevert(sender, args);
            case "branch" -> branch.handleBranch(sender, args);
            case "branches" -> branch.handleBranches(sender);
            case "switch" -> branch.handleSwitch(sender, args);
            case "merge" -> branch.handleMerge(sender, args);
            case "checkpoint" -> admin.handleCheckpoint(sender, args);
            case "jobs" -> admin.handleJobs(sender);
            case "cancel" -> admin.handleCancel(sender, args);
            case "bench" -> benchmark.handleBench(sender, args);
            case "menu", "gui" -> menu.open(sender);
            default -> env.send(sender, "common.unknown-subcommand", label);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        RepositoryState state = env.repositoryStateService().getState();
        if (args.length == 1) {
            return filterPrefix(
                    List.of(
                            "help",
                            "pos1",
                            "pos2",
                            "init",
                            "status",
                            "commit",
                            "log",
                            "checkout",
                            "branch",
                            "branches",
                            "switch",
                            "merge",
                            "diff",
                            "revert",
                            "checkpoint",
                            "jobs",
                            "cancel",
                            "bench",
                            "menu"),
                    args[0]);
        }
        if (args.length == 2 && ("switch".equalsIgnoreCase(args[0]) || "merge".equalsIgnoreCase(args[0]))) {
            return filterPrefix(new ArrayList<>(state.branchHeads().keySet()), args[1]);
        }
        if (args.length == 2 && ("checkout".equalsIgnoreCase(args[0]) || "revert".equalsIgnoreCase(args[0]))) {
            return filterPrefix(
                    env.commitWorker().tail(20).stream()
                            .map(CommitSummary::commitId)
                            .collect(Collectors.toList()),
                    args[1]);
        }
        if (args.length == 2 && "log".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("10", "20", "50"), args[1]);
        }
        if (args.length == 2 && "checkpoint".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("now"), args[1]);
        }
        if (args.length == 2 && "cancel".equalsIgnoreCase(args[0])) {
            return filterPrefix(admin.cancelTargets(), args[1]);
        }
        if (args.length == 2 && "bench".equalsIgnoreCase(args[0])) {
            return filterPrefix(List.of("run", "baseline"), args[1]);
        }
        if (args.length == 3 && "bench".equalsIgnoreCase(args[0]) && "run".equalsIgnoreCase(args[1])) {
            return filterPrefix(List.of("1", "2", "4"), args[2]);
        }
        if (args.length <= 3 && "diff".equalsIgnoreCase(args[0])) {
            List<String> values = new ArrayList<>(state.branchHeads().keySet());
            values.add("HEAD");
            values.addAll(
                    env.commitWorker().tail(20).stream()
                            .map(CommitSummary::commitId)
                            .collect(Collectors.toSet()));
            return filterPrefix(values, args[args.length - 1]);
        }
        return Collections.emptyList();
    }

    private List<String> filterPrefix(List<String> values, String prefix) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lowerPrefix))
                .sorted()
                .collect(Collectors.toList());
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(env.tr(sender, "help.title"));
        sender.sendMessage(env.tr(sender, "help.line-pos1", label));
        sender.sendMessage(env.tr(sender, "help.line-pos2", label));
        sender.sendMessage(env.tr(sender, "help.line-init", label));
        sender.sendMessage(env.tr(sender, "help.line-status", label));
        sender.sendMessage(env.tr(sender, "help.line-commit", label));
        sender.sendMessage(env.tr(sender, "help.line-log", label));
        sender.sendMessage(env.tr(sender, "help.line-checkout", label));
        sender.sendMessage(env.tr(sender, "help.line-branch", label));
        sender.sendMessage(env.tr(sender, "help.line-branches", label));
        sender.sendMessage(env.tr(sender, "help.line-switch", label));
        sender.sendMessage(env.tr(sender, "help.line-merge", label));
        sender.sendMessage(env.tr(sender, "help.line-diff", label));
        sender.sendMessage(env.tr(sender, "help.line-revert", label));
        sender.sendMessage(env.tr(sender, "help.line-jobs", label));
        sender.sendMessage(env.tr(sender, "help.line-cancel", label));
        sender.sendMessage(env.tr(sender, "help.line-bench", label));
        sender.sendMessage(env.tr(sender, "help.line-checkpoint", label));
        sender.sendMessage(env.tr(sender, "help.line-menu", label));
    }
}
