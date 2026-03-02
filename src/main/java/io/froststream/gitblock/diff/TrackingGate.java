package io.froststream.untitled8.plotgit.diff;

import java.util.concurrent.atomic.AtomicInteger;

public final class TrackingGate {
    private final AtomicInteger suppressionDepth = new AtomicInteger(0);

    public void suppress() {
        suppressionDepth.incrementAndGet();
    }

    public void resume() {
        suppressionDepth.updateAndGet(value -> Math.max(0, value - 1));
    }

    public boolean isSuppressed() {
        return suppressionDepth.get() > 0;
    }
}
