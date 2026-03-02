package io.froststream.gitblock.checkout;

public record ApplyJobStatus(
        String jobId,
        int total,
        int processed,
        int applied,
        int failed,
        long durationMillis) {}
