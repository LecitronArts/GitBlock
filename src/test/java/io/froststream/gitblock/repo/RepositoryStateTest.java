package io.froststream.gitblock.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RepositoryStateTest {
    @Test
    void initializedStateHasMainBranchAndTracksRegion() {
        RepoRegion region = new RepoRegion("world", 0, 0, 0, 10, 10, 10);
        RepositoryState state = RepositoryState.initialized("repo", region);

        assertTrue(state.initialized());
        assertEquals("repo", state.repoName());
        assertEquals("main", state.currentBranch());
        assertTrue(state.hasBranch("main"));
        assertTrue(state.tracks("world", 0, 0, 0));
        assertTrue(state.tracks("world", 10, 10, 10));
        assertFalse(state.tracks("world_nether", 0, 0, 0));
        assertFalse(state.tracks("world", 11, 10, 10));
    }

    @Test
    void withCommitMovesCurrentBranchHeadAndActiveCommit() {
        RepoRegion region = new RepoRegion("world", 0, 0, 0, 10, 10, 10);
        RepositoryState state = RepositoryState.initialized("repo", region).withCommit("c1");

        assertEquals("c1", state.activeCommitId());
        assertEquals("c1", state.headOf("main"));
    }

    @Test
    void withBranchOperationsUpdateHeadsWithoutMutatingOriginalMap() {
        RepoRegion region = new RepoRegion("world", 0, 0, 0, 10, 10, 10);
        RepositoryState base = RepositoryState.initialized("repo", region);
        RepositoryState branched = base.withNewBranch("feature", "c1");
        RepositoryState switched = branched.withCurrentBranch("feature", "c1");
        RepositoryState advanced = switched.withBranchHead("feature", "c2");

        assertFalse(base.hasBranch("feature"));
        assertTrue(branched.hasBranch("feature"));
        assertEquals("feature", switched.currentBranch());
        assertEquals("c2", advanced.headOf("feature"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> advanced.branchHeads().put("hotfix", "c3"));
    }
}
