package io.froststream.gitblock.command;

import java.util.Map;
import java.util.Set;

public final class GitBlockMenuModel {
    static final int MENU_SIZE = 54;

    static final int SLOT_STATUS = 20;
    static final int SLOT_LOG = 21;
    static final int SLOT_BRANCHES = 22;
    static final int SLOT_COMMIT = 23;
    static final int SLOT_JOBS = 24;
    static final int SLOT_CHECKPOINT = 25;
    static final int SLOT_CHECKOUT_HELP = 39;
    static final int SLOT_DIFF_HELP = 40;
    static final int SLOT_CLOSE = 49;

    static final int SLOT_DECORATIVE_1 = 11;
    static final int SLOT_DECORATIVE_2 = 12;
    static final int SLOT_DECORATIVE_3 = 13;
    static final int SLOT_DECORATIVE_4 = 14;
    static final int SLOT_DECORATIVE_5 = 15;
    static final int SLOT_DECORATIVE_6 = 29;
    static final int SLOT_DECORATIVE_7 = 30;
    static final int SLOT_DECORATIVE_8 = 31;
    static final int SLOT_DECORATIVE_9 = 32;
    static final int SLOT_DECORATIVE_10 = 33;

    private static final Set<Integer> INTERACTIVE_SLOTS =
            Set.of(
                    SLOT_STATUS,
                    SLOT_LOG,
                    SLOT_BRANCHES,
                    SLOT_COMMIT,
                    SLOT_JOBS,
                    SLOT_CHECKPOINT,
                    SLOT_CHECKOUT_HELP,
                    SLOT_DIFF_HELP,
                    SLOT_CLOSE);

    static final Map<Integer, String> ACTION_SLOT_TO_KEY =
            Map.of(
                    SLOT_STATUS, "status",
                    SLOT_LOG, "log",
                    SLOT_BRANCHES, "branches",
                    SLOT_COMMIT, "commit",
                    SLOT_JOBS, "jobs",
                    SLOT_CHECKPOINT, "checkpoint",
                    SLOT_CHECKOUT_HELP, "checkout_help",
                    SLOT_DIFF_HELP, "diff_help",
                    SLOT_CLOSE, "close");

    private GitBlockMenuModel() {}

    static boolean isInteractiveSlot(int slot) {
        return INTERACTIVE_SLOTS.contains(slot);
    }
}
