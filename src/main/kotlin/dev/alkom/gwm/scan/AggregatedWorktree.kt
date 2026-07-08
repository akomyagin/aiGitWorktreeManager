package dev.alkom.gwm.scan

import dev.alkom.gwm.git.Worktree

/**
 * A [Worktree] tagged with the repository it belongs to, for the multi-repo view.
 *
 * We wrap rather than add a `repo` field to [Worktree] on purpose: the single-repo
 * Фаза-1 code (list/create/remove over one repository) never needs that context, and
 * keeping it out of the domain model avoids threading a redundant, always-known value
 * through every existing use case. The aggregation layer is the only place that cares
 * which repo a worktree came from.
 *
 * @param repo     repository name (the scanned sub-directory's name)
 * @param worktree the worktree itself, exactly as [dev.alkom.gwm.git.WorktreeService] produced it
 */
data class AggregatedWorktree(
    val repo: String,
    val worktree: Worktree,
)
