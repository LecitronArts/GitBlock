package io.froststream.gitblock.model;

import java.time.Instant;

public record CommitResult(
        String commitId,
        long commitNumber,
        int changeCount,
        String message,
        Instant createdAt,
        String branchName,
        String parentCommitId,
        String mergeParentCommitId) {}
