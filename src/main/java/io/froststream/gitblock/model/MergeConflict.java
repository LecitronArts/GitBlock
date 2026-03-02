package io.froststream.untitled8.plotgit.model;

public record MergeConflict(
        String world,
        int x,
        int y,
        int z,
        String baseState,
        String oursState,
        String theirsState) {}
