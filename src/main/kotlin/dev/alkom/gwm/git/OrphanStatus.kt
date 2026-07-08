package dev.alkom.gwm.git

/**
 * Heuristic "is this worktree stale?" verdict for a single worktree (Этап 5).
 *
 * Deliberately a set of *independent* boolean signals rather than one `enum` of
 * mutually-exclusive kinds: a worktree can be merged into the base branch AND lack
 * an upstream AND be prunable all at once, or in any combination. Collapsing them
 * into a single category would lose information the human reviewer wants ("it's
 * merged so I can drop it" reads differently from "git already can't find its dir").
 *
 * This is strictly a *hint for a human*, never a trigger for automatic deletion:
 * `gwm` never removes a worktree on its own (PLAN §4/§5, SKILL "Разрушающие действия").
 * The Этап-5 layer only annotates; it does not change `safeRemove`/`remove`.
 *
 * @param merged     the worktree's branch is fully merged into the repo's base branch
 *                   (`git branch --merged <base>`) — its commits already live on main.
 * @param noUpstream the branch is local-only, with no tracking remote branch
 *                   (`git rev-parse --abbrev-ref <branch>@{upstream}` fails) — nothing
 *                   pushed anywhere, easy to lose track of.
 * @param prunable   git itself flags the entry as prunable (its directory is gone);
 *                   mirrors [Worktree.isPrunable].
 */
data class OrphanStatus(
    val merged: Boolean = false,
    val noUpstream: Boolean = false,
    val prunable: Boolean = false,
) {
    /** True if any staleness signal fired — the row is worth flagging to the user. */
    val isOrphaned: Boolean get() = merged || noUpstream || prunable

    /**
     * The signal names that fired, in a stable order, for compact display
     * (e.g. `["merged", "no-upstream"]`). Empty when the worktree looks active.
     */
    val reasons: List<String>
        get() = buildList {
            if (merged) add("merged")
            if (noUpstream) add("no-upstream")
            if (prunable) add("prunable")
        }

    companion object {
        /** A worktree with no staleness signals — the common, active case. */
        val ACTIVE = OrphanStatus()
    }
}
