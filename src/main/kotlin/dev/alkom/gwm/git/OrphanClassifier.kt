package dev.alkom.gwm.git

/**
 * Pure logic that turns raw git facts about a worktree into an [OrphanStatus].
 *
 * Kept free of any I/O (no [GitCommand], no [ProcessBuilder]) so the trickiest part —
 * *which combination of signals counts as stale* — is exhaustively unit-testable with
 * table-driven inputs, exactly like [WorktreeParser] (SKILL "Тестирование"). The
 * git-facing side (running `git branch --merged`, resolving the base branch, probing
 * upstream) lives in [WorktreeService], which feeds already-gathered facts in here.
 */
object OrphanClassifier {

    /**
     * Combines the three independent staleness signals for one worktree.
     *
     * Certain worktrees are never candidates and short-circuit to [OrphanStatus.ACTIVE]:
     *  - the **main** worktree — you never "clean up" the primary checkout;
     *  - a **bare** entry — it has no working branch to be stale;
     *  - a **detached** HEAD — there is no branch to test for merged/upstream, so
     *    merged/no-upstream are meaningless. (A detached entry can still be prunable,
     *    which we surface via [prunable] below.)
     *
     * The `prunable` signal is honoured regardless, because a gone directory is a real
     * staleness fact independent of branch state.
     *
     * @param worktree   the worktree being classified (for its main/bare/detached flags).
     * @param merged     result of the base-merge check; ignored for main/bare/detached.
     * @param noUpstream result of the upstream probe; ignored for main/bare/detached.
     */
    fun classify(
        worktree: Worktree,
        merged: Boolean,
        noUpstream: Boolean,
    ): OrphanStatus {
        val prunable = worktree.isPrunable
        // The main checkout is a place we live in, not something to tidy away — never flag.
        if (worktree.isMain) return OrphanStatus.ACTIVE

        // No branch to reason about (bare/detached): branch-based signals don't apply,
        // but a missing directory (prunable) is still worth surfacing on its own.
        if (worktree.isBare || worktree.isDetached) {
            return OrphanStatus(merged = false, noUpstream = false, prunable = prunable)
        }

        return OrphanStatus(merged = merged, noUpstream = noUpstream, prunable = prunable)
    }
}
