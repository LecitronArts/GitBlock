package io.froststream.gitblock.storage;

import io.froststream.gitblock.model.CommitMetadata;
import io.froststream.gitblock.repo.RepoRegion;
import io.froststream.gitblock.repo.RepositoryState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SqliteStore {
    private final String jdbcUrl;

    public SqliteStore(Path dbPath) {
        try {
            Path parent = dbPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to create database directory", ioException);
        }
        this.jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        initSchema();
    }

    public synchronized RepositoryState loadRepositoryState() {
        Map<String, String> meta = loadMetaMap();
        if (!Boolean.parseBoolean(meta.getOrDefault("initialized", "false"))) {
            return RepositoryState.uninitialized();
        }

        String repoName = meta.getOrDefault("repo-name", "default");
        RepoRegion region =
                new RepoRegion(
                        meta.getOrDefault("region.world", ""),
                        parseInt(meta.get("region.min-x")),
                        parseInt(meta.get("region.min-y")),
                        parseInt(meta.get("region.min-z")),
                        parseInt(meta.get("region.max-x")),
                        parseInt(meta.get("region.max-y")),
                        parseInt(meta.get("region.max-z")));

        String currentBranch = meta.getOrDefault("current-branch", "main");
        String activeCommitId = normalizeNullable(meta.get("active-commit-id"));
        Map<String, String> branches = loadBranchHeads();
        if (branches.isEmpty()) {
            branches.put("main", null);
        }

        RepositoryState state =
                RepositoryState.initialized(repoName, region).withCurrentBranch(currentBranch, activeCommitId);
        for (Map.Entry<String, String> entry : branches.entrySet()) {
            state = state.withBranchHead(entry.getKey(), entry.getValue());
        }
        if (activeCommitId != null) {
            state = state.withActiveCommitOnly(activeCommitId);
        }
        return state;
    }

    public synchronized void saveRepositoryState(RepositoryState state) {
        withConnection(
                connection -> {
                    connection.setAutoCommit(false);
                    try {
                        upsertMeta(connection, "initialized", Boolean.toString(state.initialized()));
                        upsertMeta(connection, "repo-name", state.repoName());
                        upsertMeta(connection, "current-branch", state.currentBranch());
                        upsertMeta(connection, "active-commit-id", nullable(state.activeCommitId()));
                        if (state.initialized() && state.region() != null) {
                            RepoRegion region = state.region();
                            upsertMeta(connection, "region.world", region.world());
                            upsertMeta(connection, "region.min-x", Integer.toString(region.minX()));
                            upsertMeta(connection, "region.min-y", Integer.toString(region.minY()));
                            upsertMeta(connection, "region.min-z", Integer.toString(region.minZ()));
                            upsertMeta(connection, "region.max-x", Integer.toString(region.maxX()));
                            upsertMeta(connection, "region.max-y", Integer.toString(region.maxY()));
                            upsertMeta(connection, "region.max-z", Integer.toString(region.maxZ()));
                        }

                        try (PreparedStatement deleteBranches =
                                        connection.prepareStatement("DELETE FROM branches");
                                PreparedStatement insertBranch =
                                        connection.prepareStatement(
                                                "INSERT INTO branches(name, head_commit_id) VALUES(?, ?)")) {
                            deleteBranches.executeUpdate();
                            for (Map.Entry<String, String> entry : state.branchHeads().entrySet()) {
                                insertBranch.setString(1, entry.getKey());
                                insertBranch.setString(2, entry.getValue());
                                insertBranch.addBatch();
                            }
                            insertBranch.executeBatch();
                        }
                        connection.commit();
                    } catch (Exception exception) {
                        connection.rollback();
                        throw exception;
                    } finally {
                        connection.setAutoCommit(true);
                    }
                    return null;
                });
    }

    public synchronized List<CommitMetadata> loadCommitsOrdered() {
        return withConnection(
                connection -> {
                    List<CommitMetadata> commits = new ArrayList<>();
                    try (PreparedStatement statement =
                                    connection.prepareStatement(
                                            "SELECT commit_id, commit_number, created_at, author_uuid, message, change_count, object_file_name, parent_commit_id, merge_parent_commit_id, branch_name FROM commits ORDER BY commit_number ASC");
                            ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            commits.add(
                                    new CommitMetadata(
                                            resultSet.getString("commit_id"),
                                            resultSet.getLong("commit_number"),
                                            Instant.ofEpochMilli(resultSet.getLong("created_at")),
                                            UUID.fromString(resultSet.getString("author_uuid")),
                                            resultSet.getString("message"),
                                            resultSet.getInt("change_count"),
                                            resultSet.getString("object_file_name"),
                                            resultSet.getString("parent_commit_id"),
                                            resultSet.getString("merge_parent_commit_id"),
                                            resultSet.getString("branch_name")));
                        }
                    }
                    return commits;
                });
    }

    public synchronized void insertCommit(CommitMetadata metadata) {
        withConnection(
                connection -> {
                    try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "INSERT INTO commits(commit_id, commit_number, created_at, author_uuid, message, change_count, object_file_name, parent_commit_id, merge_parent_commit_id, branch_name) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                        statement.setString(1, metadata.commitId());
                        statement.setLong(2, metadata.commitNumber());
                        statement.setLong(3, metadata.createdAt().toEpochMilli());
                        statement.setString(4, metadata.author().toString());
                        statement.setString(5, metadata.message());
                        statement.setInt(6, metadata.changeCount());
                        statement.setString(7, metadata.objectFileName());
                        statement.setString(8, metadata.parentCommitId());
                        statement.setString(9, metadata.mergeParentCommitId());
                        statement.setString(10, metadata.branchName());
                        statement.executeUpdate();
                    }
                    return null;
                });
    }

    public synchronized Map<String, String> loadCheckpointFiles() {
        return withConnection(
                connection -> {
                    Map<String, String> map = new LinkedHashMap<>();
                    try (PreparedStatement statement =
                                    connection.prepareStatement(
                                            "SELECT commit_id, file_name FROM checkpoints ORDER BY created_at ASC");
                            ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            map.put(resultSet.getString("commit_id"), resultSet.getString("file_name"));
                        }
                    }
                    return map;
                });
    }

    public synchronized void insertCheckpoint(
            String commitId, String fileName, long createdAtMillis, String mode, int entryCount) {
        withConnection(
                connection -> {
                    try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "INSERT OR REPLACE INTO checkpoints(commit_id, file_name, created_at, mode, entry_count) VALUES(?, ?, ?, ?, ?)")) {
                        statement.setString(1, commitId);
                        statement.setString(2, fileName);
                        statement.setLong(3, createdAtMillis);
                        statement.setString(4, mode);
                        statement.setInt(5, entryCount);
                        statement.executeUpdate();
                    }
                    return null;
                });
    }

    public synchronized boolean hasAnyCommitRows() {
        return withConnection(
                connection -> {
                    try (PreparedStatement statement =
                                    connection.prepareStatement("SELECT 1 FROM commits LIMIT 1");
                            ResultSet resultSet = statement.executeQuery()) {
                        return resultSet.next();
                    }
                });
    }

    public synchronized boolean hasAnyCheckpointRows() {
        return withConnection(
                connection -> {
                    try (PreparedStatement statement =
                                    connection.prepareStatement("SELECT 1 FROM checkpoints LIMIT 1");
                            ResultSet resultSet = statement.executeQuery()) {
                        return resultSet.next();
                    }
                });
    }

    public synchronized boolean hasInitializedRepoState() {
        return Boolean.parseBoolean(loadMetaMap().getOrDefault("initialized", "false"));
    }

    private void initSchema() {
        withConnection(
                connection -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate(
                                "CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                        statement.executeUpdate(
                                "CREATE TABLE IF NOT EXISTS branches(name TEXT PRIMARY KEY, head_commit_id TEXT NULL)");
                        statement.executeUpdate(
                                "CREATE TABLE IF NOT EXISTS commits(commit_id TEXT PRIMARY KEY, commit_number INTEGER NOT NULL, created_at INTEGER NOT NULL, author_uuid TEXT NOT NULL, message TEXT NOT NULL, change_count INTEGER NOT NULL, object_file_name TEXT NOT NULL, parent_commit_id TEXT NULL, merge_parent_commit_id TEXT NULL, branch_name TEXT NOT NULL)");
                        statement.executeUpdate(
                                "CREATE TABLE IF NOT EXISTS checkpoints(commit_id TEXT PRIMARY KEY, file_name TEXT NOT NULL, created_at INTEGER NOT NULL, mode TEXT NOT NULL, entry_count INTEGER NOT NULL)");
                        statement.executeUpdate(
                                "CREATE INDEX IF NOT EXISTS idx_commits_number ON commits(commit_number)");
                        statement.executeUpdate(
                                "CREATE INDEX IF NOT EXISTS idx_commits_parent ON commits(parent_commit_id)");
                    }
                    return null;
                });
    }

    private Map<String, String> loadMetaMap() {
        return withConnection(
                connection -> {
                    Map<String, String> map = new HashMap<>();
                    try (PreparedStatement statement =
                                    connection.prepareStatement("SELECT key, value FROM meta");
                            ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            map.put(resultSet.getString("key"), resultSet.getString("value"));
                        }
                    }
                    return map;
                });
    }

    private Map<String, String> loadBranchHeads() {
        return withConnection(
                connection -> {
                    Map<String, String> branches = new LinkedHashMap<>();
                    try (PreparedStatement statement =
                                    connection.prepareStatement(
                                            "SELECT name, head_commit_id FROM branches ORDER BY name ASC");
                            ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            branches.put(resultSet.getString("name"), resultSet.getString("head_commit_id"));
                        }
                    }
                    return branches;
                });
    }

    private void upsertMeta(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            statement.setString(1, key);
            statement.setString(2, value == null ? "" : value);
            statement.executeUpdate();
        }
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Integer.parseInt(value);
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private <T> T withConnection(SqlFunction<Connection, T> function) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            return function.apply(connection);
        } catch (SQLException sqlException) {
            throw new IllegalStateException("SQLite operation failed", sqlException);
        } catch (Exception exception) {
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("SQLite operation failed", exception);
        }
    }

    @FunctionalInterface
    private interface SqlFunction<T, R> {
        R apply(T value) throws Exception;
    }
}
