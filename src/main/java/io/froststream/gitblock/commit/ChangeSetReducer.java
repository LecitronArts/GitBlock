package io.froststream.untitled8.plotgit.commit;

import io.froststream.untitled8.plotgit.model.BlockChangeRecord;
import io.froststream.untitled8.plotgit.model.DirtyEntry;
import io.froststream.untitled8.plotgit.model.LocationKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChangeSetReducer {
    private ChangeSetReducer() {}

    public static List<BlockChangeRecord> reduce(List<BlockChangeRecord> orderedChanges) {
        return new Accumulator().acceptAll(orderedChanges).toReducedList();
    }

    public static final class Accumulator {
        private final Map<LocationKey, DirtyEntry> merged = new LinkedHashMap<>();

        public Accumulator acceptAll(Iterable<BlockChangeRecord> changes) {
            for (BlockChangeRecord change : changes) {
                accept(change);
            }
            return this;
        }

        public Accumulator accept(BlockChangeRecord change) {
            LocationKey key = new LocationKey(change.world(), change.x(), change.y(), change.z());
            DirtyEntry existing = merged.get(key);
            if (existing == null) {
                if (!change.oldState().equals(change.newState())) {
                    merged.put(key, new DirtyEntry(change.oldState(), change.newState()));
                }
                return this;
            }
            if (existing.originalState().equals(change.newState())) {
                merged.remove(key);
            } else {
                merged.put(key, new DirtyEntry(existing.originalState(), change.newState()));
            }
            return this;
        }

        public List<BlockChangeRecord> toReducedList() {
            List<BlockChangeRecord> reduced = new ArrayList<>(merged.size());
            for (Map.Entry<LocationKey, DirtyEntry> entry : merged.entrySet()) {
                LocationKey key = entry.getKey();
                DirtyEntry value = entry.getValue();
                reduced.add(
                        new BlockChangeRecord(
                                key.world(),
                                key.x(),
                                key.y(),
                                key.z(),
                                value.originalState(),
                                value.latestState()));
            }
            return reduced;
        }
    }
}
