package io.froststream.gitblock.diff;

import io.froststream.gitblock.model.DirtyEntry;
import io.froststream.gitblock.model.LocationKey;
import java.util.HashMap;
import java.util.Map;

public final class DirtyMap {
    private final Map<LocationKey, DirtyEntry> entries = new HashMap<>();
    private final Map<String, String> statePool = new HashMap<>();

    public synchronized void recordChange(LocationKey key, String oldState, String newState) {
        if (oldState.equals(newState)) {
            return;
        }
        String canonicalOldState = canonicalize(oldState);
        String canonicalNewState = canonicalize(newState);

        DirtyEntry existing = entries.get(key);
        if (existing == null) {
            entries.put(key, new DirtyEntry(canonicalOldState, canonicalNewState));
            return;
        }

        if (existing.originalState().equals(canonicalNewState)) {
            entries.remove(key);
            return;
        }

        entries.put(key, new DirtyEntry(existing.originalState(), canonicalNewState));
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized Map<LocationKey, DirtyEntry> drainAll() {
        Map<LocationKey, DirtyEntry> drained = new HashMap<>(entries);
        entries.clear();
        return drained;
    }

    public synchronized void restoreAll(Map<LocationKey, DirtyEntry> snapshot) {
        for (Map.Entry<LocationKey, DirtyEntry> entry : snapshot.entrySet()) {
            DirtyEntry restoring = entry.getValue();
            String canonicalOriginal = canonicalize(restoring.originalState());
            String canonicalLatest = canonicalize(restoring.latestState());
            DirtyEntry existing = entries.get(entry.getKey());
            if (existing == null) {
                entries.put(entry.getKey(), new DirtyEntry(canonicalOriginal, canonicalLatest));
                continue;
            }

            if (existing.originalState().equals(canonicalLatest)) {
                entries.remove(entry.getKey());
                continue;
            }

            entries.put(
                    entry.getKey(),
                    new DirtyEntry(existing.originalState(), canonicalLatest));
        }
    }

    private String canonicalize(String value) {
        String existing = statePool.get(value);
        if (existing != null) {
            return existing;
        }
        statePool.put(value, value);
        return value;
    }
}
