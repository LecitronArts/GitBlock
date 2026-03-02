package io.froststream.gitblock.repo;

import io.froststream.gitblock.storage.SqliteStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class RepositoryStateService {
    private final JavaPlugin plugin;
    private final SqliteStore sqliteStore;
    private final Path legacyStateFile;
    private RepositoryState state;

    public RepositoryStateService(JavaPlugin plugin, SqliteStore sqliteStore) {
        this.plugin = plugin;
        this.sqliteStore = sqliteStore;
        this.legacyStateFile = plugin.getDataFolder().toPath().resolve("repo-state.yml");
        migrateLegacyIfNeeded();
        this.state = sqliteStore.loadRepositoryState();
    }

    public synchronized RepositoryState getState() {
        return state;
    }

    public synchronized RepositoryState initialize(String repoName, RepoRegion region) {
        this.state = RepositoryState.initialized(repoName, region);
        sqliteStore.saveRepositoryState(this.state);
        return state;
    }

    public synchronized RepositoryState update(RepositoryState newState) {
        this.state = newState;
        sqliteStore.saveRepositoryState(this.state);
        return state;
    }

    private void migrateLegacyIfNeeded() {
        if (sqliteStore.hasInitializedRepoState()) {
            return;
        }
        if (!Files.exists(legacyStateFile)) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(legacyStateFile.toFile());
        if (!yaml.getBoolean("initialized", false)) {
            return;
        }

        String repoName = yaml.getString("repo-name", "default");
        RepoRegion region =
                new RepoRegion(
                        yaml.getString("region.world", ""),
                        yaml.getInt("region.min-x"),
                        yaml.getInt("region.min-y"),
                        yaml.getInt("region.min-z"),
                        yaml.getInt("region.max-x"),
                        yaml.getInt("region.max-y"),
                        yaml.getInt("region.max-z"));
        RepositoryState migrated = RepositoryState.initialized(repoName, region);
        String currentBranch = yaml.getString("current-branch", "main");
        String activeCommitId = yaml.getString("active-commit-id", null);
        migrated = migrated.withCurrentBranch(currentBranch, activeCommitId);
        if (yaml.isConfigurationSection("branches")) {
            for (String key : yaml.getConfigurationSection("branches").getKeys(false)) {
                migrated = migrated.withBranchHead(key, yaml.getString("branches." + key, null));
            }
        }
        if (activeCommitId != null && !activeCommitId.isBlank()) {
            migrated = migrated.withActiveCommitOnly(activeCommitId);
        }
        sqliteStore.saveRepositoryState(migrated);
        plugin.getLogger().info("Migrated legacy repository state into SQLite.");
    }
}
