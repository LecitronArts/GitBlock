package io.froststream.gitblock.repo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class RepositoryRuntimeManager implements AutoCloseable {
    private final JavaPlugin plugin;
    private final Path reposRoot;
    private final boolean sqliteWal;
    private final boolean sqliteSynchronousNormal;
    private final int sqliteBusyTimeoutMs;
    private final int checkpointEveryCommits;
    private final boolean includeMergeParentsInMergeBase;
    private final int transitionCacheSize;
    private final int patchCacheSize;
    private final String legacyDefaultRepositoryName;
    private final Path legacyRepositoryStateFile;
    private final PlayerRepositoryStore playerRepositoryStore;
    private final Map<String, RepositoryRuntime> runtimesByName = new ConcurrentHashMap<>();

    public RepositoryRuntimeManager(
            JavaPlugin plugin,
            Path reposRoot,
            boolean sqliteWal,
            boolean sqliteSynchronousNormal,
            int sqliteBusyTimeoutMs,
            int checkpointEveryCommits,
            boolean includeMergeParentsInMergeBase,
            int transitionCacheSize,
            int patchCacheSize,
            String legacyDefaultRepositoryName,
            Path legacyRepositoryStateFile,
            PlayerRepositoryStore playerRepositoryStore) {
        this.plugin = plugin;
        this.reposRoot = reposRoot;
        this.sqliteWal = sqliteWal;
        this.sqliteSynchronousNormal = sqliteSynchronousNormal;
        this.sqliteBusyTimeoutMs = sqliteBusyTimeoutMs;
        this.checkpointEveryCommits = checkpointEveryCommits;
        this.includeMergeParentsInMergeBase = includeMergeParentsInMergeBase;
        this.transitionCacheSize = transitionCacheSize;
        this.patchCacheSize = patchCacheSize;
        this.legacyDefaultRepositoryName = normalizeRepositoryName(legacyDefaultRepositoryName);
        this.legacyRepositoryStateFile = legacyRepositoryStateFile;
        this.playerRepositoryStore = playerRepositoryStore;
    }

    public PlayerRepositoryStore playerRepositoryStore() {
        return playerRepositoryStore;
    }

    public RepositoryRuntime runtimeForRepository(String repositoryName) {
        String normalizedRepositoryName = normalizeRepositoryName(repositoryName);
        if (normalizedRepositoryName.isBlank()) {
            throw new IllegalArgumentException("Repository name is blank.");
        }
        return runtimesByName.computeIfAbsent(normalizedRepositoryName, this::createRuntime);
    }

    public RepositoryRuntime loadedRuntimeForRepository(String repositoryName) {
        String normalizedRepositoryName = normalizeRepositoryName(repositoryName);
        if (normalizedRepositoryName.isBlank()) {
            return null;
        }
        return runtimesByName.get(normalizedRepositoryName);
    }

    public RepositoryRuntime runtimeForSender(
            CommandSender sender,
            int maxRepositories,
            String defaultRepositoryBase) {
        if (sender instanceof Player player) {
            return runtimeForPlayer(player, maxRepositories, defaultRepositoryBase);
        }
        return runtimeForRepository(legacyDefaultRepositoryName);
    }

    public RepositoryRuntime runtimeForPlayer(
            Player player, int maxRepositories, String defaultRepositoryBase) {
        UUID playerId = player.getUniqueId();
        String activeRepositoryName = playerRepositoryStore.activeRepository(playerId);
        if (activeRepositoryName == null) {
            if (maxRepositories <= 0) {
                return null;
            }
            if (!playerRepositoryStore.repositoryExists(legacyDefaultRepositoryName)) {
                playerRepositoryStore.createRepository(playerId, legacyDefaultRepositoryName);
                playerRepositoryStore.setActiveRepository(playerId, legacyDefaultRepositoryName);
                activeRepositoryName = legacyDefaultRepositoryName;
            } else {
                activeRepositoryName =
                        playerRepositoryStore.ensurePersonalDefaultRepository(
                                playerId, normalizeRepositoryName(defaultRepositoryBase));
            }
        }
        return runtimeForRepository(activeRepositoryName);
    }

    public boolean playerCanCreateRepository(UUID playerId, int maxRepositories) {
        if (maxRepositories < 0) {
            return true;
        }
        return playerRepositoryStore.ownedRepositoryCount(playerId) < maxRepositories;
    }

    public RepositoryCreateStatus createRepositoryForPlayer(
            UUID playerId, String repositoryName, int maxRepositories) {
        String normalizedRepositoryName = normalizeRepositoryName(repositoryName);
        if (normalizedRepositoryName.isBlank()) {
            return RepositoryCreateStatus.INVALID_NAME;
        }
        if (playerRepositoryStore.isRepositoryOwner(playerId, normalizedRepositoryName)) {
            return RepositoryCreateStatus.ALREADY_EXISTS;
        }
        if (playerRepositoryStore.repositoryExists(normalizedRepositoryName)) {
            return RepositoryCreateStatus.NAME_TAKEN;
        }
        if (!playerCanCreateRepository(playerId, maxRepositories)) {
            return RepositoryCreateStatus.LIMIT_REACHED;
        }
        PlayerRepositoryStore.CreateRepositoryResult createResult =
                playerRepositoryStore.createRepository(playerId, normalizedRepositoryName);
        return switch (createResult) {
            case CREATED -> RepositoryCreateStatus.CREATED;
            case ALREADY_OWNED -> RepositoryCreateStatus.ALREADY_EXISTS;
            case NAME_TAKEN -> RepositoryCreateStatus.NAME_TAKEN;
            case INVALID_NAME -> RepositoryCreateStatus.INVALID_NAME;
        };
    }

    public boolean setActiveRepository(UUID playerId, String repositoryName) {
        return playerRepositoryStore.setActiveRepository(playerId, repositoryName);
    }

    public List<String> listOwnedRepositories(UUID playerId) {
        List<String> repositories = new ArrayList<>(playerRepositoryStore.listOwnedRepositories(playerId));
        repositories.sort(String.CASE_INSENSITIVE_ORDER);
        return repositories;
    }

    public int ownedRepositoryCount(UUID playerId) {
        return playerRepositoryStore.ownedRepositoryCount(playerId);
    }

    public String activeRepository(UUID playerId) {
        return playerRepositoryStore.activeRepository(playerId);
    }

    public boolean isRepositoryOwner(UUID playerId, String repositoryName) {
        return playerRepositoryStore.isRepositoryOwner(playerId, repositoryName);
    }

    public boolean repositoryExists(String repositoryName) {
        return playerRepositoryStore.repositoryExists(repositoryName);
    }

    public List<RepositoryRuntime> allLoadedRuntimes() {
        return runtimesByName.values().stream()
                .sorted(Comparator.comparing(RepositoryRuntime::repositoryName))
                .toList();
    }

    public List<RepositoryRuntime> runtimesTracking(String world, int x, int y, int z) {
        List<RepositoryRuntime> matches = new ArrayList<>();
        for (RepositoryRuntime runtime : runtimesByName.values()) {
            RepositoryState state = runtime.repositoryState();
            if (!state.initialized() || state.region() == null) {
                continue;
            }
            if (state.tracks(world, x, y, z)) {
                matches.add(runtime);
            }
        }
        return matches;
    }

    public void preloadKnownRepositories() {
        runtimeForRepository(legacyDefaultRepositoryName);
        for (String repositoryName : playerRepositoryStore.allKnownRepositories()) {
            runtimeForRepository(repositoryName);
        }
    }

    @Override
    public void close() {
        for (RepositoryRuntime runtime : allLoadedRuntimes()) {
            try {
                runtime.close();
            } catch (Exception exception) {
                plugin.getLogger()
                        .warning(
                                "Failed to close repository runtime '"
                                        + runtime.repositoryName()
                                        + "': "
                                        + exception.getMessage());
            }
        }
        runtimesByName.clear();
    }

    private RepositoryRuntime createRuntime(String repositoryName) {
        Path repositoryRoot = reposRoot.resolve(repositoryName);
        Path legacyStateFile =
                repositoryName.equals(legacyDefaultRepositoryName) ? legacyRepositoryStateFile : null;
        return new RepositoryRuntime(
                plugin,
                repositoryName,
                repositoryRoot,
                sqliteWal,
                sqliteSynchronousNormal,
                sqliteBusyTimeoutMs,
                checkpointEveryCommits,
                includeMergeParentsInMergeBase,
                transitionCacheSize,
                patchCacheSize,
                legacyStateFile);
    }

    private static String normalizeRepositoryName(String raw) {
        if (raw == null) {
            return "";
        }
        String sanitized = raw.trim().replaceAll("[^a-zA-Z0-9_\\-]", "");
        if (sanitized.isBlank()) {
            return "";
        }
        return sanitized.toLowerCase(Locale.ROOT);
    }

    public enum RepositoryCreateStatus {
        CREATED,
        ALREADY_EXISTS,
        NAME_TAKEN,
        LIMIT_REACHED,
        INVALID_NAME
    }
}
