package io.froststream.gitblock.diff;

import io.froststream.gitblock.model.LocationKey;
import java.util.HashMap;
import java.util.Map;

public final class TrackingGate {
    private final Map<LocationKey, Integer> suppressionByLocation = new HashMap<>();

    public synchronized void suppress(LocationKey key) {
        suppressionByLocation.merge(key, 1, Integer::sum);
    }

    public synchronized void resume(LocationKey key) {
        Integer count = suppressionByLocation.get(key);
        if (count == null) {
            return;
        }
        if (count <= 1) {
            suppressionByLocation.remove(key);
            return;
        }
        suppressionByLocation.put(key, count - 1);
    }

    public synchronized boolean isSuppressed(LocationKey key) {
        return suppressionByLocation.containsKey(key);
    }
}
