package io.froststream.untitled8.plotgit.model;

import java.time.Instant;
import java.util.UUID;

public record CommitMetadata(
        String commitId,
        long commitNumber,
        Instant createdAt,
        UUID author,
        String message,
        int changeCount,
        String objectFileName,
        String parentCommitId,
        String mergeParentCommitId,
        String branchName) {}
