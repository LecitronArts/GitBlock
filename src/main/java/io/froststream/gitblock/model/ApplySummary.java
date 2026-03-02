package io.froststream.untitled8.plotgit.model;

import java.util.Map;

public record ApplySummary(
        int total,
        int applied,
        int failed,
        long durationMillis,
        double minTps,
        double avgTps,
        Map<LocationKey, DirtyEntry> appliedDelta) {}
