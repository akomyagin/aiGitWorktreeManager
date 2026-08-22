package dev.alkom.gwm.scan

/**
 * One worktree selected as a candidate for bulk deletion by `gwm clean` (Этап 10), with the facts
 * needed to decide how to delete it already computed. Pure data — no git, no I/O, no TTY — so
 * [BulkCleanPlan.from] can be exhaustively unit-tested like [OrphanClassifier]/[MultiRootSelection].
 *
 * @param aggregated the underlying repo + [dev.alkom.gwm.git.Worktree] (path, branch, dirty, orphan…)
 * @param dirty      whether the working tree has uncommitted changes. Derived from
 *                   [dev.alkom.gwm.git.Worktree.dirty] treating `null` (never checked) as NOT dirty
 *                   — nothing to lose. In practice `ScanService` always fills `dirty` via
 *                   `withDirtyFlags`, so `null` does not occur here; the mapping is documented for
 *                   completeness. A dirty candidate is NEVER deleted without `--force` (safety
 *                   invariant §3).
 * @param reasons    the orphan reasons ([dev.alkom.gwm.git.OrphanStatus.reasons]), for display.
 */
data class CleanCandidate(
    val aggregated: AggregatedWorktree,
    val dirty: Boolean,
    val reasons: List<String>,
) {
    val repo: String get() = aggregated.repo
    val label: String get() = aggregated.worktree.label
    val path: String get() = aggregated.worktree.path
}

/**
 * The outcome of planning a bulk `clean`: the orphaned worktrees eligible for deletion, split into
 * those deletable straight away (clean) and those guarded behind `--force` (dirty). An empty
 * [candidates] means there is nothing to delete.
 *
 * Pure classification, kept out of `Main.kt` so the "who is a candidate / who is dirty" decision is
 * unit-tested without a TTY or real git. The actual deletion I/O stays thin in [CleanCommand].
 */
data class BulkCleanPlan(
    val candidates: List<CleanCandidate>,
) {
    val dirtyCandidates: List<CleanCandidate> get() = candidates.filter { it.dirty }
    val cleanCandidates: List<CleanCandidate> get() = candidates.filter { !it.dirty }
    val total: Int get() = candidates.size
    val dirtyCount: Int get() = dirtyCandidates.size
    val isEmpty: Boolean get() = candidates.isEmpty()

    companion object {
        /**
         * Pure candidate selection from a scan result. A worktree is a candidate ⟺
         * `orphan.isOrphaned` AND NOT `isMain`.
         *
         * The `!isMain` filter is EXPLICIT defense-in-depth on top of [OrphanClassifier] (which
         * already maps `isMain → ACTIVE`): safety invariant §5 requires the primary worktree to
         * NEVER appear in the candidate list, so we re-guard it here rather than relying on a
         * single upstream classifier alone.
         *
         * Input order is preserved (scan groups worktrees by repo — the adjacency invariant
         * [dev.alkom.gwm.ui.WorktreeTable] depends on), so the printed candidate list and the
         * deletion order stay grouped by repo.
         */
        fun from(worktrees: List<AggregatedWorktree>): BulkCleanPlan {
            val candidates = worktrees
                .filter { it.worktree.orphan.isOrphaned && !it.worktree.isMain }
                .map { agg ->
                    CleanCandidate(
                        aggregated = agg,
                        dirty = agg.worktree.dirty ?: false,
                        reasons = agg.worktree.orphan.reasons,
                    )
                }
            return BulkCleanPlan(candidates)
        }
    }
}
