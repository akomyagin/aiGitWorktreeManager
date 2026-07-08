package dev.alkom.gwm.git

/**
 * A single git worktree as reported by `git worktree list --porcelain`.
 *
 * @param path       absolute filesystem path of the worktree
 * @param head       commit SHA the worktree points at (null for a fresh, unborn branch)
 * @param branch     short branch name (e.g. "feature/foo"), null when detached
 * @param isBare     true for the bare main repository entry
 * @param isDetached true when HEAD is detached (no branch)
 * @param isLocked   true when the worktree is locked (`locked` attribute present)
 * @param isPrunable true when git itself flags the entry as prunable
 * @param isMain     true for the primary worktree (the first entry / the repo root)
 * @param dirty      working-tree cleanliness, filled in separately via status; null = not yet checked
 * @param orphan     staleness hint (merged / no-upstream / prunable), filled in separately
 *                   via [WorktreeService.withOrphanStatus]; [OrphanStatus.ACTIVE] by default
 */
data class Worktree(
    val path: String,
    val head: String?,
    val branch: String?,
    val isBare: Boolean = false,
    val isDetached: Boolean = false,
    val isLocked: Boolean = false,
    val isPrunable: Boolean = false,
    val isMain: Boolean = false,
    val dirty: Boolean? = null,
    val orphan: OrphanStatus = OrphanStatus.ACTIVE,
) {
    /** Short branch label for display: branch name, "(detached)", or "(bare)". */
    val label: String
        get() = when {
            isBare -> "(bare)"
            isDetached -> "(detached)"
            branch != null -> branch
            else -> "(unknown)"
        }
}
