package io.froststream.gitblock;

import io.froststream.gitblock.checkpoint.CheckpointService;
import io.froststream.gitblock.checkout.ApplyScheduler;
import io.froststream.gitblock.checkout.ApplyQueueOverflowPolicy;
import io.froststream.gitblock.command.AdminCommandHandler;
import io.froststream.gitblock.command.BenchmarkCommandHandler;
import io.froststream.gitblock.command.BranchCommandHandler;
import io.froststream.gitblock.command.HistoryCommandHandler;
import io.froststream.gitblock.command.GitBlockCommand;
import io.froststream.gitblock.command.GitBlockChestMenu;
import io.froststream.gitblock.command.GitBlockCommandEnv;
import io.froststream.gitblock.command.RepositoryCommandHandler;
import io.froststream.gitblock.command.TransitionCoordinator;
import io.froststream.gitblock.commit.CommitTransitionService;
import io.froststream.gitblock.commit.CommitWorker;
import io.froststream.gitblock.diff.DirtyMap;
import io.froststream.gitblock.diff.DirtyTrackingListener;
import io.froststream.gitblock.diff.TrackingGate;
import io.froststream.gitblock.i18n.I18nService;
import io.froststream.gitblock.repo.PlayerSessionCleanupListener;
import io.froststream.gitblock.repo.RepositoryStateService;
import io.froststream.gitblock.repo.SelectionService;
import io.froststream.gitblock.storage.SqliteStore;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class GitBlock extends JavaPlugin {
    private DirtyMap dirtyMap;
    private CommitWorker commitWorker;
    private ApplyScheduler applyScheduler;
    private CheckpointService checkpointService;
    private TrackingGate trackingGate;
    private SqliteStore sqliteStore;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        I18nService i18nService =
                new I18nService(
                        this,
                        getConfig().getString("i18n.default-locale", "en_us"),
                        getConfig().getString("i18n.fallback-locale", "en_us"),
                        getConfig().getString("i18n.force-locale", ""),
                        getConfig().getString("i18n.prefix", "&8[&bGitBlock&8]&r "));
        i18nService.load();

        String configuredRepoName = getConfig().getString("repo-name", "default");
        String repoName = sanitizeRepoName(configuredRepoName);
        if (!repoName.equals(configuredRepoName)) {
            getLogger()
                    .warning(
                            "Config repo-name '"
                                    + configuredRepoName
                                    + "' contains unsupported characters; using sanitized name '"
                                    + repoName
                                    + "'.");
        }
        int maxBlocksPerTick = Math.max(100, getConfig().getInt("apply.max-blocks-per-tick", 1500));
        long tickBudgetMs = Math.max(1L, getConfig().getLong("apply.tick-budget-ms", 2L));
        int maxQueuedJobs = Math.max(1, getConfig().getInt("apply.max-queued-jobs", 8));
        int blockDataCacheSize = Math.max(0, getConfig().getInt("apply.blockdata-cache-size", 2048));
        int maxChunkUnloadsPerTick = Math.max(1, getConfig().getInt("apply.max-chunk-unloads-per-tick", 64));
        String queueOverflowPolicyRaw = getConfig().getString("apply.queue-overflow-policy", "reject-new");
        String normalizedQueueOverflowPolicy =
                queueOverflowPolicyRaw == null ? "reject-new" : queueOverflowPolicyRaw.trim().toLowerCase();
        ApplyQueueOverflowPolicy queueOverflowPolicy =
                ApplyQueueOverflowPolicy.fromConfig(queueOverflowPolicyRaw);
        if (!queueOverflowPolicy.configName().equals(normalizedQueueOverflowPolicy)) {
            getLogger()
                    .warning(
                            "Unknown apply.queue-overflow-policy '"
                                    + queueOverflowPolicyRaw
                                    + "', falling back to "
                                    + queueOverflowPolicy.configName()
                                    + ".");
        }
        int checkpointEveryCommits = Math.max(1, getConfig().getInt("checkpoints.every-commits", 20));
        boolean serializeMutations = getConfig().getBoolean("operations.serialize-mutations", true);
        String usePermission =
                normalizePermissionNode(
                        getConfig().getString("permissions.use", "gitblock.use"),
                        "gitblock.use");
        String adminPermission =
                normalizePermissionNode(
                        getConfig().getString("permissions.admin", "gitblock.admin"),
                        "gitblock.admin");
        String jobsPermission =
                normalizePermissionNode(
                        getConfig().getString("permissions.jobs-view", adminPermission),
                        adminPermission);
        boolean includeMergeParentsInMergeBase = parseMergeBaseMode();
        int transitionCacheSize = Math.max(0, getConfig().getInt("history.transition-cache-size", 64));
        int patchCacheSize = Math.max(0, getConfig().getInt("history.patch-cache-size", 64));
        boolean sqliteWal = getConfig().getBoolean("storage.sqlite.wal", true);
        boolean sqliteSynchronousNormal =
                getConfig().getBoolean("storage.sqlite.synchronous-normal", true);
        int sqliteBusyTimeoutMs =
                Math.max(0, getConfig().getInt("storage.sqlite.busy-timeout-ms", 5000));
        Path repoRoot = getDataFolder().toPath().resolve("repos").resolve(repoName);
        Path conflictsDir = repoRoot.resolve("conflicts");
        Path sqliteFile = repoRoot.resolve("gitblock.db");
        this.sqliteStore =
                new SqliteStore(
                        sqliteFile,
                        sqliteWal,
                        sqliteSynchronousNormal,
                        sqliteBusyTimeoutMs);

        this.dirtyMap = new DirtyMap();
        this.trackingGate = new TrackingGate();
        this.commitWorker = new CommitWorker(this, repoRoot, sqliteStore);
        this.applyScheduler =
                new ApplyScheduler(
                        this,
                        trackingGate,
                        maxBlocksPerTick,
                        TimeUnit.MILLISECONDS.toNanos(tickBudgetMs),
                        maxQueuedJobs,
                        queueOverflowPolicy,
                        blockDataCacheSize,
                        maxChunkUnloadsPerTick);
        this.checkpointService =
                new CheckpointService(this, commitWorker, sqliteStore, checkpointEveryCommits, repoRoot);

        RepositoryStateService repositoryStateService = new RepositoryStateService(this, sqliteStore);
        SelectionService selectionService = new SelectionService();
        CommitTransitionService transitionService =
                new CommitTransitionService(
                        commitWorker,
                        includeMergeParentsInMergeBase,
                        transitionCacheSize,
                        patchCacheSize);

        GitBlockCommandEnv env =
                new GitBlockCommandEnv(
                        this,
                        dirtyMap,
                        commitWorker,
                        transitionService,
                        applyScheduler,
                        checkpointService,
                        repositoryStateService,
                        selectionService,
                        i18nService,
                        conflictsDir,
                        repoName,
                        serializeMutations,
                        usePermission,
                        adminPermission,
                        jobsPermission);

        TransitionCoordinator transitionCoordinator = new TransitionCoordinator(env);
        RepositoryCommandHandler repositoryHandler = new RepositoryCommandHandler(env);
        HistoryCommandHandler historyHandler = new HistoryCommandHandler(env, transitionCoordinator);
        BranchCommandHandler branchHandler = new BranchCommandHandler(env, transitionCoordinator);
        BenchmarkCommandHandler benchmarkHandler = new BenchmarkCommandHandler(env);
        AdminCommandHandler adminHandler = new AdminCommandHandler(env, benchmarkHandler);
        GitBlockChestMenu chestMenu = new GitBlockChestMenu(env);

        GitBlockCommand GitBlockCommand =
                new GitBlockCommand(
                        env,
                        repositoryHandler,
                        historyHandler,
                        branchHandler,
                        adminHandler,
                        benchmarkHandler,
                        chestMenu);
        PluginCommand command = getCommand("gitblock");
        if (command == null) {
            getLogger().severe("Command /gitblock is missing from plugin.yml, disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setPermission(usePermission);
        command.setExecutor(GitBlockCommand);
        command.setTabCompleter(GitBlockCommand);

        getServer()
                .getPluginManager()
                .registerEvents(new DirtyTrackingListener(dirtyMap, repositoryStateService, trackingGate), this);
        getServer().getPluginManager().registerEvents(chestMenu, this);
        getServer()
                .getPluginManager()
                .registerEvents(
                        new PlayerSessionCleanupListener(selectionService, historyHandler),
                        this);
        getLogger().info("Mutating operations serialization: " + (serializeMutations ? "enabled" : "disabled"));
        getLogger()
                .info(
                        "Apply queue policy: pendingLimit="
                                + maxQueuedJobs
                                + ", overflow="
                                + applyScheduler.queueOverflowPolicyName());
        getLogger().info("GitBlock skeleton enabled. Use /gitblock help.");

    }

    @Override
    public void onDisable() {
        if (applyScheduler != null) {
            applyScheduler.shutdown();
        }
        if (commitWorker != null) {
            commitWorker.shutdown();
        }
        if (sqliteStore != null) {
            try {
                sqliteStore.close();
            } catch (Exception exception) {
                getLogger().log(Level.WARNING, "Failed to close SQLite store cleanly.", exception);
            }
        }
    }

    private boolean parseMergeBaseMode() {
        String rawMode = getConfig().getString("history.merge-base-mode", "first-parent");
        if ("all-parents".equalsIgnoreCase(rawMode)) {
            getLogger().info("Using merge-base mode: all-parents");
            return true;
        }
        if (!"first-parent".equalsIgnoreCase(rawMode)) {
            getLogger()
                    .warning(
                            "Unknown history.merge-base-mode '"
                                    + rawMode
                                    + "', falling back to first-parent.");
        }
        getLogger().info("Using merge-base mode: first-parent");
        return false;
    }

    private static String normalizePermissionNode(String raw, String fallback) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static String sanitizeRepoName(String raw) {
        String normalized = raw == null ? "default" : raw;
        String sanitized = normalized.replaceAll("[^a-zA-Z0-9_\\-]", "");
        if (sanitized.isBlank()) {
            return "default";
        }
        return sanitized.toLowerCase();
    }
}
