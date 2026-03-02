package io.froststream.untitled8.plotgit.model;

public record BlockChangeRecord(
        String world,
        int x,
        int y,
        int z,
        String oldState,
        String newState) {}
