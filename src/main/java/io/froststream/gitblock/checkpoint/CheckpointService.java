package io.froststream.untitled8.plotgit.checkpoint;

import io.froststream.untitled8.plotgit.commit.CommitWorker;
import io.froststream.untitled8.plotgit.model.BlockChangeRecord;
import io.froststream.untitled8.plotgit.model.CommitMetadata;
import io.froststream.untitled8.plotgit.model.CommitResult;
import io.froststream.untitled8.plotgit.model.LocationKey;
import io.froststream.untitled8.plotgit.storage.SqliteStore;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

public final class CheckpointService {
    private static final String SNAPSHOT_HEADER = "PGS1";
    private static final String AIR_STATE = Material.AIR.createBlockData().getAsString();
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

    private final JavaPlugin plugin;
    private final CommitWorker commitWorker;
    private final SqliteStore sqliteStore;
    private final int everyCommits;
    private final Path snapshotDir;
    private final Path legacyCheckpointIndex;
    private final Map<String, String> snapshotFileByCommit = new ConcurrentHashMap<>();
    private final Set<String> checkpointsInFlight = ConcurrentHashMap.newKeySet();

    public CheckpointService(
            JavaPlugin plugin,
            CommitWorker commitWorker,
            SqliteStore sqliteStore,
            int everyCommits,
            Path repoRoot) {
        this.plugin = plugin;
        this.commitWorker = commitWorker;
        this.sqliteStore = sqliteStore;
        this.everyCommits = Math.max(1, everyCommits);
        this.snapshotDir = repoRoot.resolve("snapshots");
        this.legacyCheckpointIndex = repoRoot.resolve("checkpoints.log");
        initStorage();
        migrateLegacyIfNeeded();
        snapshotFileByCommit.putAll(sqliteStore.loadCheckpointFiles());
    }

    public void maybeCreateCheckpoint(CommitResult commitResult) {
        if (commitResult.commitNumber() % everyCommits != 0) {
            return;
        }
        createCheckpointAsync(commitResult.commitId(), "auto");
    }

    public void forceCheckpoint(String commitId) {
        createCheckpointAsync(commitId, "manual");
    }

    public boolean hasCheckpoint(String commitId) {
        return snapshotFileByCommit.containsKey(commitId);
    }

    public void createCheckpointAsync(String commitId, String mode) {
        if (!checkpointsInFlight.add(commitId)) {
            return;
        }
        Bukkit.getScheduler()
                .runTaskAsynchronously(
                        plugin,
                        () -> {
                            try {
                                createCheckpoint(commitId, mode);
                            } catch (Exception exception) {
                                plugin.getLogger()
                                        .warning(
                                                "Failed to create checkpoint for "
                                                        + commitId
                                                        + ": "
                                                        + exception.getMessage());
                            } finally {
                                checkpointsInFlight.remove(commitId);
                            }
                        });
    }

    public Map<LocationKey, String> loadSnapshotState(String commitId) {
        String fileName = snapshotFileByCommit.get(commitId);
        if (fileName == null) {
            return Map.of();
        }

        Path snapshotFile = snapshotDir.resolve(fileName);
        Map<LocationKey, String> state = new LinkedHashMap<>();
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                new GZIPInputStream(Files.newInputStream(snapshotFile)),
                                StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (!SNAPSHOT_HEADER.equals(header)) {
                throw new IllegalStateException("Unsupported snapshot header: " + header);
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", 5);
                if (parts.length != 5) {
                    continue;
                }
                state.put(
                        new LocationKey(
                                parts[0],
                                Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2]),
                                Integer.parseInt(parts[3])),
                        decode(parts[4]));
            }
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to load checkpoint snapshot " + snapshotFile, ioException);
        }
        return state;
    }

    public String findNearestCheckpointAncestor(String commitId) {
        String cursor = commitId;
        while (cursor != null) {
            if (snapshotFileByCommit.containsKey(cursor)) {
                return cursor;
            }
            CommitMetadata metadata = commitWorker.getCommit(cursor);
            if (metadata == null) {
                return null;
            }
            cursor = metadata.parentCommitId();
        }
        return null;
    }

    private void createCheckpoint(String commitId, String mode) {
        if (!commitWorker.hasCommit(commitId)) {
            throw new IllegalArgumentException("Unknown commit for checkpoint: " + commitId);
        }

        String baseCheckpoint = findNearestCheckpointAncestor(commitId);
        Map<LocationKey, String> state =
                baseCheckpoint == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(loadSnapshotState(baseCheckpoint));

        List<CommitMetadata> path = commitWorker.pathFromAncestor(baseCheckpoint, commitId);
        for (CommitMetadata commit : path) {
            for (BlockChangeRecord change : commitWorker.readCommitChangesBlocking(commit.commitId())) {
                LocationKey key = new LocationKey(change.world(), change.x(), change.y(), change.z());
                if (isAir(change.newState())) {
                    state.remove(key);
                } else {
                    state.put(key, change.newState());
                }
            }
        }

        String fileName = commitId + ".pgs";
        Path snapshotFile = snapshotDir.resolve(fileName);
        writeSnapshot(snapshotFile, state);
        snapshotFileByCommit.put(commitId, fileName);
        sqliteStore.insertCheckpoint(commitId, fileName, Instant.now().toEpochMilli(), mode, state.size());
    }

    private void writeSnapshot(Path snapshotFile, Map<LocationKey, String> state) {
        Path tempFile = null;
        try {
            tempFile =
                    Files.createTempFile(
                            snapshotDir,
                            snapshotFile.getFileName().toString() + ".",
                            ".tmp-" + UUID.randomUUID());
            try (BufferedWriter writer =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    new GZIPOutputStream(Files.newOutputStream(tempFile)),
                                    StandardCharsets.UTF_8))) {
                writer.write(SNAPSHOT_HEADER);
                writer.newLine();
                for (Map.Entry<LocationKey, String> entry : state.entrySet()) {
                    LocationKey key = entry.getKey();
                    writer.write(key.world());
                    writer.write('\t');
                    writer.write(Integer.toString(key.x()));
                    writer.write('\t');
                    writer.write(Integer.toString(key.y()));
                    writer.write('\t');
                    writer.write(Integer.toString(key.z()));
                    writer.write('\t');
                    writer.write(encode(entry.getValue()));
                    writer.newLine();
                }
            }

            moveSnapshotAtomically(tempFile, snapshotFile);
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to write checkpoint snapshot " + snapshotFile, ioException);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best-effort cleanup for abandoned temp files.
                }
            }
        }
    }

    private void moveSnapshotAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void initStorage() {
        try {
            Files.createDirectories(snapshotDir);
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to initialize checkpoint storage", ioException);
        }
    }

    private void migrateLegacyIfNeeded() {
        if (sqliteStore.hasAnyCheckpointRows()) {
            return;
        }
        if (!Files.exists(legacyCheckpointIndex)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(legacyCheckpointIndex, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\|");
                if (parts.length >= 5) {
                    String commitId = parts[0];
                    String fileName = parts[1];
                    long createdAt = parseLong(parts[2], Instant.now().toEpochMilli());
                    String mode = parts[3];
                    int entryCount = parseInt(parts[4], 0);
                    sqliteStore.insertCheckpoint(commitId, fileName, createdAt, mode, entryCount);
                } else if (parts.length == 4) {
                    String commitId = parts[0];
                    String fileName = commitId + ".pgs";
                    long createdAt = parseLong(parts[1], Instant.now().toEpochMilli());
                    int entryCount = parseInt(parts[2], 0);
                    String mode = parts[3];
                    sqliteStore.insertCheckpoint(commitId, fileName, createdAt, mode, entryCount);
                } else if (parts.length >= 1) {
                    String commitId = parts[0];
                    String fileName = commitId + ".pgs";
                    sqliteStore.insertCheckpoint(
                            commitId, fileName, Instant.now().toEpochMilli(), "legacy", 0);
                }
            }
            plugin.getLogger().info("Migrated legacy checkpoints index into SQLite.");
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to read legacy checkpoint index", ioException);
        }
    }

    private boolean isAir(String blockData) {
        return AIR_STATE.equals(blockData) || blockData.startsWith("minecraft:air");
    }

    private static String encode(String value) {
        return BASE64_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(BASE64_DECODER.decode(value), StandardCharsets.UTF_8);
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
