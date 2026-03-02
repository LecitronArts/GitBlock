package io.froststream.untitled8.plotgit.checkout;

import io.froststream.untitled8.plotgit.model.BlockChangeRecord;

final class ApplyPreconditions {
    private ApplyPreconditions() {}

    static Decision decide(String currentState, BlockChangeRecord change) {
        if (currentState.equals(change.newState())) {
            return Decision.ALREADY_AT_TARGET;
        }
        if (!currentState.equals(change.oldState())) {
            return Decision.PRECONDITION_FAILED;
        }
        return Decision.APPLY;
    }

    enum Decision {
        APPLY,
        ALREADY_AT_TARGET,
        PRECONDITION_FAILED
    }
}
