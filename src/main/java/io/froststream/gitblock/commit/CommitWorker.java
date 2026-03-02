package io.froststream.untitled8.plotgit.commit;

import io.froststream.untitled8.plotgit.model.BlockChangeRecord;
import io.froststream.untitled8.plotgit.model.CommitMetadata;
import io.froststream.untitled8.plotgit.model.CommitResult;
import io.froststream.untitled8.plotgit.model.CommitSummary;
import io.froststream.untitled8.plotgit.storage.SqliteStore;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.plugin.java.JavaPlugin;

public final class CommitWorker {
    private static final String OBJECT_HEADER = "PGD1";
    private static final String LOG_PREFIX = "v2";
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();
    private static final int QUERY_THREADS = 2;
    private static final int QUERY_QUEUE_CAPACITY = 128;
    private static final AtomicInteger QUERY_THREAD_SEQ = new AtomicInteger(0);

    private final Object stateLock = new Object();
    private final JavaPlugin plugin;
    private final SqliteStore sqliteStore;
    private final Path objectsDir;
    private final Path legacyCommitsLog;
    private final ExecutorService commitExecutor;
    private final ExecutorService queryExecutor;
    private final List<CommitMetadata> commitOrder = new ArrayList<>();
    private final Map<String, CommitMetadata> commitsById = new HashMap<>();

    private long commitCounter;

    public CommitWorker(JavaPlugin plugin, Path repoRoot, SqliteStore sqliteStore) {
        this.plugin = plugin;
        this.sqliteStore = sqliteStore;
        this.objectsDir = repoRoot.resolve("objects");
        this.legacyCommitsLog = repoRoot.resolve("commits.log");
        this.commitExecutor = newSingleThreadExecutor("plotgit-commit-writer");
        this.queryExecutor =
                newBoundedThreadPool(
                        QUERY_THREADS, QUERY_QUEUE_CAPACITY, "plotgit-commit-query");
        initStorage();
        migrateLegacyIfNeeded();
        loadIndexFromDatabase();
    }

    public CompletableFuture<CommitResult> submitCommit(
            String message,
            UUID author,
            String branchName,
            String parentCommitId,
            String mergeParentCommitId,
            List<BlockChangeRecord> changes) {
        List<BlockChangeRecord> snapshot = changes == null ? List.of() : List.copyOf(changes);
        String safeMessage = sanitizeMessage(message);
        return CompletableFuture.supplyAsync(
                () ->
                        writeCommitInternal(
                                safeMessage,
                                author,
                                branchName,
                                parentCommitId,
                                mergeParentCommitId,
                                snapshot),
                commitExecutor);
    }

    public CompletableFuture<List<BlockChangeRecord>> readCommitChanges(String commitId) {
        return CompletableFuture.supplyAsync(() -> readCommitChangesInternal(commitId), queryExecutor);
    }

    public List<BlockChangeRecord> readCommitChangesBlocking(String commitId) {
        return readCommitChangesInternal(commitId);
    }

    public List<CommitSummary> tail(int limit) {
        int clampedLimit = Math.max(1, limit);
        synchronized (stateLock) {
            int start = Math.max(0, commitOrder.size() - clampedLimit);
            List<CommitSummary> latest = new ArrayList<>();
            for (int i = commitOrder.size() - 1; i >= start; i--) {
                CommitMetadata metadata = commitOrder.get(i);
                latest.add(
                        new CommitSummary(
                                metadata.commitId(),
                                metadata.commitNumber(),
                                metadata.createdAt(),
                                metadata.author(),
                                metadata.message(),
                                metadata.changeCount(),
                                metadata.branchName()));
            }
            return latest;
        }
    }

    public CommitMetadata getCommit(String commitId) {
        synchronized (stateLock) {
            return commitsById.get(commitId);
        }
    }

    public boolean hasCommit(String commitId) {
        if (commitId == null) {
            return false;
        }
        synchronized (stateLock) {
            return commitsById.containsKey(commitId);
        }
    }

    public String findCommonAncestor(String firstCommitId, String secondCommitId) {
        return findCommonAncestor(firstCommitId, secondCommitId, false);
    }

    public String findCommonAncestor(
            String firstCommitId, String secondCommitId, boolean includeMergeParent) {
        if (firstCommitId == null || secondCommitId == null) {
            return null;
        }
        if (firstCommitId.equals(secondCommitId)) {
            return firstCommitId;
        }
        synchronized (stateLock) {
            Map<String, Integer> distanceFromFirst =
                    computeAncestorDistance(firstCommitId, includeMergeParent);
            if (distanceFromFirst.isEmpty()) {
                return null;
            }
            Map<String, Integer> distanceFromSecond =
                    computeAncestorDistance(secondCommitId, includeMergeParent);
            if (distanceFromSecond.isEmpty()) {
                return null;
            }

            String best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (Map.Entry<String, Integer> entry : distanceFromFirst.entrySet()) {
                Integer secondDistance = distanceFromSecond.get(entry.getKey());
                if (secondDistance == null) {
                    continue;
                }
                int totalDistance = entry.getValue() + secondDistance;
                if (totalDistance < bestDistance
                        || (totalDistance == bestDistance && preferAncestor(entry.getKey(), best))) {
                    bestDistance = totalDistance;
                    best = entry.getKey();
                }
            }
            return best;
        }
    }

    private boolean preferAncestor(String candidate, String currentBest) {
        if (currentBest == null) {
            return true;
        }
        return candidate.compareTo(currentBest) < 0;
    }

    public boolean isFirstParentAncestor(String ancestorCommitId, String targetCommitId) {
        if (ancestorCommitId == null) {
            return true;
        }
        if (targetCommitId == null) {
            return false;
        }
        synchronized (stateLock) {
            String cursor = targetCommitId;
            while (cursor != null) {
                if (ancestorCommitId.equals(cursor)) {
                    return true;
                }
                CommitMetadata metadata = commitsById.get(cursor);
                if (metadata == null) {
                    return false;
                }
                cursor = metadata.parentCommitId();
            }
            return false;
        }
    }

    public List<CommitMetadata> pathFromAncestor(String ancestorExclusive, String targetInclusive) {
        if (targetInclusive == null) {
            return List.of();
        }
        synchronized (stateLock) {
            List<CommitMetadata> reversed = new ArrayList<>();
            String cursor = targetInclusive;
            while (cursor != null && !cursor.equals(ancestorExclusive)) {
                CommitMetadata commit = commitsById.get(cursor);
                if (commit == null) {
                    throw new IllegalArgumentException("Unknown commit id in path: " + cursor);
                }
                reversed.add(commit);
                cursor = commit.parentCommitId();
            }
            if (ancestorExclusive != null && cursor == null) {
                throw new IllegalArgumentException(
                        "Commit " + ancestorExclusive + " is not ancestor of " + targetInclusive);
            }
            List<CommitMetadata> ordered = new ArrayList<>(reversed.size());
            for (int i = reversed.size() - 1; i >= 0; i--) {
                ordered.add(reversed.get(i));
            }
            return ordered;
        }
    }

    public List<CommitMetadata> pathDownToAncestor(String startInclusive, String ancestorExclusive) {
        if (startInclusive == null) {
            return List.of();
        }
        synchronized (stateLock) {
            List<CommitMetadata> ordered = new ArrayList<>();
            String cursor = startInclusive;
            while (cursor != null && !cursor.equals(ancestorExclusive)) {
                CommitMetadata commit = commitsById.get(cursor);
                if (commit == null) {
                    throw new IllegalArgumentException("Unknown commit id in path: " + cursor);
                }
                ordered.add(commit);
                cursor = commit.parentCommitId();
            }
            if (ancestorExclusive != null && cursor == null) {
                throw new IllegalArgumentException(
                        "Commit " + ancestorExclusive + " is not ancestor of " + startInclusive);
            }
            return ordered;
        }
    }

    public void shutdown() {
        shutdownExecutor(queryExecutor);
        shutdownExecutor(commitExecutor);
    }

    Executor transitionExecutor() {
        return queryExecutor;
    }

    private CommitResult writeCommitInternal(
            String message,
            UUID author,
            String branchName,
            String parentCommitId,
            String mergeParentCommitId,
            List<BlockChangeRecord> changes) {
        Instant now = Instant.now();
        String commitId = generateCommitId(now);
        Path objectFile = objectsDir.resolve(commitId + ".pgd");
        synchronized (stateLock) {
            if (parentCommitId != null && !commitsById.containsKey(parentCommitId)) {
                throw new IllegalArgumentException("Unknown parent commit: " + parentCommitId);
            }
            if (mergeParentCommitId != null && !commitsById.containsKey(mergeParentCommitId)) {
                throw new IllegalArgumentException("Unknown merge parent commit: " + mergeParentCommitId);
            }
        }

        CommitMetadata metadata;
        boolean persisted = false;
        try {
            writeObjectFile(objectFile, changes);
            synchronized (stateLock) {
                long commitNumber = ++commitCounter;
                metadata =
                        new CommitMetadata(
                                commitId,
                                commitNumber,
                                now,
                                author,
                                message,
                                changes.size(),
                                objectFile.getFileName().toString(),
                                parentCommitId,
                                mergeParentCommitId,
                                branchName);

                sqliteStore.insertCommit(metadata);
                commitOrder.add(metadata);
                commitsById.put(commitId, metadata);
            }
            persisted = true;
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to write commit object " + objectFile, ioException);
        } finally {
            if (!persisted) {
                try {
                    Files.deleteIfExists(objectFile);
                } catch (IOException cleanupException) {
                    plugin.getLogger()
                            .warning(
                                    "Failed to cleanup orphan commit object "
                                            + objectFile
                                            + ": "
                                            + cleanupException.getMessage());
                }
            }
        }

        return new CommitResult(
                metadata.commitId(),
                metadata.commitNumber(),
                metadata.changeCount(),
                metadata.message(),
                metadata.createdAt(),
                metadata.branchName(),
                metadata.parentCommitId(),
                metadata.mergeParentCommitId());
    }

    private List<BlockChangeRecord> readCommitChangesInternal(String commitId) {
        CommitMetadata metadata = getCommit(commitId);
        Path objectPath =
                metadata == null
                        ? objectsDir.resolve(commitId + ".pgd")
                        : objectsDir.resolve(metadata.objectFileName());
        if (!Files.exists(objectPath)) {
            throw new IllegalArgumentException("Unknown commit id: " + commitId);
        }

        try {
            return readObjectFile(objectPath);
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to read commit object " + objectPath, ioException);
        }
    }

    private void initStorage() {
        try {
            Files.createDirectories(objectsDir);
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to initialize PlotGit storage", ioException);
        }
    }

    private void migrateLegacyIfNeeded() {
        if (sqliteStore.hasAnyCommitRows()) {
            return;
        }
        if (!Files.exists(legacyCommitsLog)) {
            return;
        }

        List<CommitMetadata> legacyCommits = new ArrayList<>();
        String fallbackParent = null;
        try (BufferedReader reader = Files.newBufferedReader(legacyCommitsLog, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                CommitMetadata metadata;
                if (line.startsWith(LOG_PREFIX + "|")) {
                    metadata = parseV2Line(line);
                } else {
                    metadata = parseLegacyLine(line, fallbackParent);
                }
                fallbackParent = metadata.commitId();
                legacyCommits.add(metadata);
            }
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to read legacy commit index", ioException);
        }

        for (CommitMetadata metadata : legacyCommits) {
            sqliteStore.insertCommit(metadata);
        }
        if (!legacyCommits.isEmpty()) {
            plugin.getLogger().info("Migrated " + legacyCommits.size() + " commits from legacy log to SQLite.");
        }
    }

    private void loadIndexFromDatabase() {
        List<CommitMetadata> loadedOrder = sqliteStore.loadCommitsOrdered();
        Map<String, CommitMetadata> loadedById = new HashMap<>();
        long loadedCounter = 0L;
        for (CommitMetadata metadata : loadedOrder) {
            loadedById.put(metadata.commitId(), metadata);
            loadedCounter = Math.max(loadedCounter, metadata.commitNumber());
        }

        synchronized (stateLock) {
            commitOrder.clear();
            commitOrder.addAll(loadedOrder);
            commitsById.clear();
            commitsById.putAll(loadedById);
            commitCounter = loadedCounter;
        }

        if (!loadedOrder.isEmpty()) {
            plugin.getLogger().info("Loaded " + loadedOrder.size() + " PlotGit commits from SQLite.");
        }
    }

    private Map<String, Integer> computeAncestorDistance(String startCommitId, boolean includeMergeParent) {
        Map<String, Integer> distance = new HashMap<>();
        Deque<NodeDistance> queue = new ArrayDeque<>();
        queue.add(new NodeDistance(startCommitId, 0));

        while (!queue.isEmpty()) {
            NodeDistance current = queue.removeFirst();
            if (current.commitId == null || distance.containsKey(current.commitId)) {
                continue;
            }
            CommitMetadata metadata = commitsById.get(current.commitId);
            if (metadata == null) {
                continue;
            }
            distance.put(current.commitId, current.distance);
            enqueueParent(queue, metadata.parentCommitId(), current.distance + 1);
            if (includeMergeParent) {
                enqueueParent(queue, metadata.mergeParentCommitId(), current.distance + 1);
            }
        }

        return distance;
    }

    private void enqueueParent(Deque<NodeDistance> queue, String parentCommitId, int distance) {
        if (parentCommitId != null) {
            queue.addLast(new NodeDistance(parentCommitId, distance));
        }
    }

    private void writeObjectFile(Path objectFile, List<BlockChangeRecord> changes) throws IOException {
        try (BufferedWriter writer =
                new BufferedWriter(
                        new OutputStreamWriter(
                                new GZIPOutputStream(Files.newOutputStream(objectFile)),
                                StandardCharsets.UTF_8))) {
            writer.write(OBJECT_HEADER);
            writer.newLine();
            for (BlockChangeRecord change : changes) {
                writer.write(change.world());
                writer.write('\t');
                writer.write(Integer.toString(change.x()));
                writer.write('\t');
                writer.write(Integer.toString(change.y()));
                writer.write('\t');
                writer.write(Integer.toString(change.z()));
                writer.write('\t');
                writer.write(encode(change.oldState()));
                writer.write('\t');
                writer.write(encode(change.newState()));
                writer.newLine();
            }
        }
    }

    private List<BlockChangeRecord> readObjectFile(Path objectFile) throws IOException {
        List<BlockChangeRecord> changes = new ArrayList<>();
        Map<String, String> stringPool = new HashMap<>();
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                new GZIPInputStream(Files.newInputStream(objectFile)),
                                StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (!OBJECT_HEADER.equals(header)) {
                throw new IllegalStateException("Unsupported commit object header: " + header);
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", 6);
                if (parts.length != 6) {
                    continue;
                }
                String world = canonicalize(stringPool, parts[0]);
                String oldState = canonicalize(stringPool, decode(parts[4]));
                String newState = canonicalize(stringPool, decode(parts[5]));
                changes.add(
                        new BlockChangeRecord(
                                world,
                                Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2]),
                                Integer.parseInt(parts[3]),
                                oldState,
                                newState));
            }
        }
        return changes;
    }

    private String sanitizeMessage(String message) {
        String normalized = message == null ? "" : message.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            normalized = "manual commit";
        }
        if (normalized.length() > 120) {
            return normalized.substring(0, 120);
        }
        return normalized;
    }

    private String generateCommitId(Instant now) {
        String millisHex = Long.toHexString(now.toEpochMilli());
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return millisHex + "-" + suffix;
    }

    private CommitMetadata parseV2Line(String line) {
        String[] parts = line.split("\\|", 11);
        if (parts.length != 11) {
            throw new IllegalStateException("Corrupted v2 commit log line: " + line);
        }
        return new CommitMetadata(
                parts[1],
                Long.parseLong(parts[2]),
                Instant.ofEpochMilli(Long.parseLong(parts[3])),
                UUID.fromString(parts[4]),
                decode(parts[6]),
                Integer.parseInt(parts[5]),
                parts[7],
                denull(parts[8]),
                denull(parts[9]),
                denull(parts[10]));
    }

    private CommitMetadata parseLegacyLine(String line, String fallbackParent) {
        String[] parts = line.split("\\|", 7);
        if (parts.length != 7) {
            throw new IllegalStateException("Corrupted legacy commit log line: " + line);
        }
        return new CommitMetadata(
                parts[0],
                Long.parseLong(parts[1]),
                Instant.ofEpochMilli(Long.parseLong(parts[2])),
                UUID.fromString(parts[3]),
                decode(parts[5]),
                Integer.parseInt(parts[4]),
                parts[6],
                fallbackParent,
                null,
                "main");
    }

    private static String denull(String value) {
        return "-".equals(value) ? null : value;
    }

    private static String encode(String value) {
        return BASE64_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(BASE64_DECODER.decode(value), StandardCharsets.UTF_8);
    }

    private static String canonicalize(Map<String, String> pool, String value) {
        String existing = pool.get(value);
        if (existing != null) {
            return existing;
        }
        pool.put(value, value);
        return value;
    }

    private static ExecutorService newSingleThreadExecutor(String threadName) {
        return Executors.newSingleThreadExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, threadName);
                    thread.setDaemon(true);
                    return thread;
                });
    }

    private static ExecutorService newBoundedThreadPool(
            int size, int queueCapacity, String threadNamePrefix) {
        int safeSize = Math.max(1, size);
        int safeCapacity = Math.max(1, queueCapacity);
        return new ThreadPoolExecutor(
                safeSize,
                safeSize,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(safeCapacity),
                runnable -> {
                    Thread thread =
                            new Thread(
                                    runnable,
                                    threadNamePrefix + "-" + QUERY_THREAD_SEQ.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private record NodeDistance(String commitId, int distance) {}
}
