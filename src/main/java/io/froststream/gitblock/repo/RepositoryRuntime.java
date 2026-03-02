package io.froststream.gitblock.repo;

import io.froststream.gitblock.checkpoint.CheckpointService;
import io.froststream.gitblock.commit.CommitTransitionService;
import io.froststream.gitblock.commit.CommitWorker;
import io.froststream.gitblock.diff.DirtyMap;
import io.froststream.gitblock.storage.SqliteStore;
import java.nio.file.Path;
import org.bukkit.plugin.java.JavaPlugin;

public final class RepositoryRuntime implements AutoCloseable {
    private final String repositoryName;
    private final Path repositoryRoot;
    private final Path conflictsDirectory;
    private final SqliteStore sqliteStore;
    private final DirtyMap dirtyMap;
    private final CommitWorker commitWorker;
    private final CheckpointService checkpointService;
    private final RepositoryStateService repositoryStateService;
    private final CommitTransitionService transitionService;

    public RepositoryRuntime(
            JavaPlugin plugin,
            String repositoryName,
            Path repositoryRoot,
            boolean sqliteWal,
            boolean sqliteSynchronousNormal,
            int sqliteBusyTimeoutMs,
            int checkpointEveryCommits,
            boolean includeMergeParentsInMergeBase,
            int transitionCacheSize,
            int patchCacheSize,
            Path legacyRepositoryStateFile) {
        this.repositoryName = repositoryName;
        this.repositoryRoot = repositoryRoot;
        this.conflictsDirectory = repositoryRoot.resolve("conflicts");
        this.sqliteStore =
                new SqliteStore(
                        repositoryRoot.resolve("gitblock.db"),
                        sqliteWal,
                        sqliteSynchronousNormal,
                        sqliteBusyTimeoutMs);
        this.dirtyMap = new DirtyMap();
        this.commitWorker = new CommitWorker(plugin, repositoryRoot, sqliteStore);
        this.checkpointService =
                new CheckpointService(
                        plugin,
                        commitWorker,
                        sqliteStore,
                        checkpointEveryCommits,
                        repositoryRoot);
        this.repositoryStateService =
                new RepositoryStateService(plugin, sqliteStore, repositoryName, legacyRepositoryStateFile);
        this.transitionService =
                new CommitTransitionService(
                        commitWorker,
                        includeMergeParentsInMergeBase,
                        transitionCacheSize,
                        patchCacheSize);
    }

    public String repositoryName() {
        return repositoryName;
    }

    public Path repositoryRoot() {
        return repositoryRoot;
    }

    public Path conflictsDirectory() {
        return conflictsDirectory;
    }

    public SqliteStore sqliteStore() {
        return sqliteStore;
    }

    public DirtyMap dirtyMap() {
        return dirtyMap;
    }

    public CommitWorker commitWorker() {
        return commitWorker;
    }

    public CheckpointService checkpointService() {
        return checkpointService;
    }

    public RepositoryStateService repositoryStateService() {
        return repositoryStateService;
    }

    public CommitTransitionService transitionService() {
        return transitionService;
    }

    public RepositoryState repositoryState() {
        return repositoryStateService.getState();
    }

    @Override
    public void close() {
        commitWorker.shutdown();
        sqliteStore.close();
    }
}
