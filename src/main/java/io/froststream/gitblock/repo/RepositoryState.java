package io.froststream.untitled8.plotgit.repo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.block.Block;

public final class RepositoryState {
    private final boolean initialized;
    private final String repoName;
    private final RepoRegion region;
    private final String currentBranch;
    private final String activeCommitId;
    private final Map<String, String> branchHeads;

    private RepositoryState(
            boolean initialized,
            String repoName,
            RepoRegion region,
            String currentBranch,
            String activeCommitId,
            Map<String, String> branchHeads) {
        this.initialized = initialized;
        this.repoName = repoName;
        this.region = region;
        this.currentBranch = currentBranch;
        this.activeCommitId = activeCommitId;
        this.branchHeads = Collections.unmodifiableMap(new LinkedHashMap<>(branchHeads));
    }

    public static RepositoryState uninitialized() {
        return new RepositoryState(false, "default", null, "main", null, Collections.emptyMap());
    }

    public static RepositoryState initialized(String repoName, RepoRegion region) {
        Map<String, String> heads = new LinkedHashMap<>();
        heads.put("main", null);
        return new RepositoryState(true, repoName, region, "main", null, heads);
    }

    public boolean initialized() {
        return initialized;
    }

    public String repoName() {
        return repoName;
    }

    public RepoRegion region() {
        return region;
    }

    public String currentBranch() {
        return currentBranch;
    }

    public String activeCommitId() {
        return activeCommitId;
    }

    public Map<String, String> branchHeads() {
        return branchHeads;
    }

    public String headOf(String branchName) {
        return branchHeads.get(branchName);
    }

    public boolean hasBranch(String branchName) {
        return branchHeads.containsKey(branchName);
    }

    public boolean tracks(Block block) {
        return initialized && region != null && region.contains(block);
    }

    public boolean tracks(String world, int x, int y, int z) {
        return initialized && region != null && region.contains(world, x, y, z);
    }

    public RepositoryState withCommit(String commitId) {
        if (!initialized) {
            return this;
        }
        Map<String, String> heads = new LinkedHashMap<>(branchHeads);
        heads.put(currentBranch, commitId);
        return new RepositoryState(true, repoName, region, currentBranch, commitId, heads);
    }

    public RepositoryState withActiveCommitOnly(String commitId) {
        return new RepositoryState(initialized, repoName, region, currentBranch, commitId, branchHeads);
    }

    public RepositoryState withNewBranch(String branchName, String headCommitId) {
        Map<String, String> heads = new LinkedHashMap<>(branchHeads);
        heads.put(branchName, headCommitId);
        return new RepositoryState(initialized, repoName, region, currentBranch, activeCommitId, heads);
    }

    public RepositoryState withBranchHead(String branchName, String headCommitId) {
        if (!branchHeads.containsKey(branchName)) {
            return withNewBranch(branchName, headCommitId);
        }
        Map<String, String> heads = new LinkedHashMap<>(branchHeads);
        heads.put(branchName, headCommitId);
        return new RepositoryState(initialized, repoName, region, currentBranch, activeCommitId, heads);
    }

    public RepositoryState withCurrentBranch(String branchName, String activeCommitId) {
        return new RepositoryState(initialized, repoName, region, branchName, activeCommitId, branchHeads);
    }
}
