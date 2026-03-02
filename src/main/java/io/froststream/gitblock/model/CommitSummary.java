package io.froststream.untitled8.plotgit.model;

import java.time.Instant;
import java.util.UUID;

public record CommitSummary(
        String commitId,
        long commitNumber,
        Instant createdAt,
        UUID author,
        String message,
        int changeCount,
        String branchName) {}
