package io.froststream.untitled8.plotgit.checkout;

import java.util.Locale;

public enum ApplyQueueOverflowPolicy {
    REJECT_NEW("reject-new"),
    DROP_OLDEST_PENDING("drop-oldest-pending");

    private final String configName;

    ApplyQueueOverflowPolicy(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }

    public static ApplyQueueOverflowPolicy fromConfig(String raw) {
        if (raw == null) {
            return REJECT_NEW;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (ApplyQueueOverflowPolicy policy : values()) {
            if (policy.configName.equals(normalized)) {
                return policy;
            }
        }
        return REJECT_NEW;
    }
}
