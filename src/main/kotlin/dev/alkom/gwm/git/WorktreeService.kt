package dev.alkom.gwm.git

import java.io.File

/**
 * High-level operations over a single repository's worktrees.
 *
 * Composes [GitCommand] + [WorktreeParser]. Everything here is a Фаза-1 building
 * block; multi-repo aggregation (Фаза 2) is layered on top in the `scan` package.
 */
class WorktreeService(private val repoDir: File) {

    /** True if [repoDir] is inside a git working tree. */
    fun isGitRepo(): Boolean =
        GitCommand.run(repoDir, "rev-parse", "--is-inside-work-tree").ok

    /** List worktrees; dirty flags are filled in lazily via [withDirtyFlags]. */
    fun list(): List<Worktree> {
        val res = GitCommand.run(repoDir, "worktree", "list", "--porcelain")
        if (!res.ok) return emptyList()
        return WorktreeParser.parse(res.stdout)
    }

    /**
     * Returns the same worktrees with [Worktree.dirty] populated by running
     * `git status --porcelain` inside each. Kept separate from [list] because
     * status is the expensive part and callers may want the cheap listing first.
     */
    fun withDirtyFlags(worktrees: List<Worktree>): List<Worktree> =
        worktrees.map { wt ->
            if (wt.isBare) wt.copy(dirty = false) else wt.copy(dirty = isDirty(File(wt.path)))
        }

    private fun isDirty(dir: File): Boolean {
        val res = GitCommand.run(dir, "status", "--porcelain")
        return res.ok && res.stdout.isNotBlank()
    }

    /**
     * Creates a worktree at [path]. If [newBranch] is set, creates that branch
     * from [baseRef]; otherwise checks out the existing [baseRef].
     */
    fun add(path: File, newBranch: String?, baseRef: String = "HEAD"): GitResult =
        if (newBranch != null) {
            GitCommand.run(repoDir, "worktree", "add", "-b", newBranch, path.absolutePath, baseRef)
        } else {
            GitCommand.run(repoDir, "worktree", "add", path.absolutePath, baseRef)
        }

    /** Removes a worktree; [force] passes `--force` (needed if it has changes). */
    fun remove(path: String, force: Boolean = false): GitResult {
        val args = buildList {
            add("worktree"); add("remove")
            if (force) add("--force")
            add(path)
        }
        return GitCommand.run(repoDir, *args.toTypedArray())
    }

    /** Runs `git worktree prune` to clean up administrative files of gone worktrees. */
    fun prune(): GitResult = GitCommand.run(repoDir, "worktree", "prune", "-v")
}
