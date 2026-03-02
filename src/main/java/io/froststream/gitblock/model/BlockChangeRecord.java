package io.froststream.gitblock.model;

public record BlockChangeRecord(
        String world,
        int x,
        int y,
        int z,
        String oldState,
        String newState) {}
