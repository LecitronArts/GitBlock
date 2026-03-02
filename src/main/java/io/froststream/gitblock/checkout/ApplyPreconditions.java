package io.froststream.gitblock.checkout;

import io.froststream.gitblock.model.BlockChangeRecord;

public final class ApplyPreconditions {
    private ApplyPreconditions() {}

    public static Decision decide(String currentState, BlockChangeRecord change) {
        if (currentState.equals(change.newState())) {
            return Decision.ALREADY_AT_TARGET;
        }
        if (!currentState.equals(change.oldState())) {
            return Decision.PRECONDITION_FAILED;
        }
        return Decision.APPLY;
    }

    public enum Decision {
        APPLY,
        ALREADY_AT_TARGET,
        PRECONDITION_FAILED
    }
}
