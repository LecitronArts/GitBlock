package io.froststream.gitblock.model;

public record MergeConflict(
        String world,
        int x,
        int y,
        int z,
        String baseState,
        String oursState,
        String theirsState) {}
