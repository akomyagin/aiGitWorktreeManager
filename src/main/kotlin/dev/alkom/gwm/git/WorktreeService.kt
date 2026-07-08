package dev.alkom.gwm.git

import java.io.File

/**
 * A git runner: `(workingDir, args) -> GitResult`.
 *
 * Defaults to the real [GitCommand]; unit tests inject a fake so the create/remove
 * logic can be exercised without touching a real repository or the `git` binary.
 */
typealias GitRunner = (File, List<String>) -> GitResult

/** The production runner — shells out to the user's real `git` via [GitCommand]. */
val RealGitRunner: GitRunner = { dir, args -> GitCommand.run(dir, *args.toTypedArray()) }

/**
 * High-level operations over a single repository's worktrees.
 *
 * Composes a [GitRunner] + [WorktreeParser]. Everything here is a Фаза-1 building
 * block; multi-repo aggregation (Фаза 2) is layered on top in the `scan` package.
 *
 * The [git] runner is injectable purely so unit tests can drive create/remove logic
 * with a fake git; production code uses the [RealGitRunner] default.
 */
class WorktreeService(
    private val repoDir: File,
    private val git: GitRunner = RealGitRunner,
) {

    /** True if [repoDir] is inside a git working tree. */
    fun isGitRepo(): Boolean =
        git(repoDir, listOf("rev-parse", "--is-inside-work-tree")).ok

    /** List worktrees; dirty flags are filled in lazily via [withDirtyFlags]. */
    fun list(): List<Worktree> {
        val res = git(repoDir, listOf("worktree", "list", "--porcelain"))
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
        val res = git(dir, listOf("status", "--porcelain"))
        return res.ok && res.stdout.isNotBlank()
    }

    /**
     * Default location for a new worktree of [branch]: a sibling of the repo root
     * named `<repo>-<branch>`, with any slashes in the branch flattened to `-`
     * (so `feature/foo` → `myrepo-feature-foo`). This mirrors the common convention
     * of keeping worktrees next to the primary checkout.
     */
    fun defaultWorktreePath(branch: String): File {
        val repoRoot = mainWorktreePath() ?: repoDir.absoluteFile
        val parent = repoRoot.absoluteFile.parentFile ?: repoRoot.absoluteFile
        val safeBranch = branch.trim().trim('/').replace('/', '-')
        return File(parent, "${repoRoot.name}-$safeBranch")
    }

    /** Absolute path of the primary (main) worktree, or null if it can't be determined. */
    private fun mainWorktreePath(): File? =
        list().firstOrNull { it.isMain }?.let { File(it.path).absoluteFile }

    /**
     * Creates a worktree at [path]. If [newBranch] is set, creates that branch
     * from [baseRef]; otherwise checks out the existing [baseRef].
     */
    fun add(path: File, newBranch: String?, baseRef: String = "HEAD"): GitResult {
        val args = buildList {
            add("worktree"); add("add")
            if (newBranch != null) {
                add("-b"); add(newBranch)
            }
            add(path.absolutePath)
            add(baseRef)
        }
        return git(repoDir, args)
    }

    /**
     * Finds a worktree by either its path (absolute or relative) or its branch name.
     * Returns null if nothing matches.
     */
    fun findWorktree(pathOrBranch: String): Worktree? {
        val worktrees = list()
        val target = File(pathOrBranch).absoluteFile
        return worktrees.firstOrNull { File(it.path).absoluteFile == target }
            ?: worktrees.firstOrNull { it.branch == pathOrBranch }
    }

    /**
     * Outcome of a [safeRemove] attempt.
     *
     * [BLOCKED_DIRTY] means the worktree has uncommitted changes and `force` was not
     * given — the caller must confirm before retrying with `force = true`. We never
     * silently discard local work.
     */
    enum class RemoveStatus { REMOVED, NOT_FOUND, BLOCKED_DIRTY, GIT_ERROR }

    data class RemoveOutcome(val status: RemoveStatus, val result: GitResult? = null)

    /**
     * Removes a worktree identified by [pathOrBranch], guarding against silent data
     * loss: if the target has uncommitted changes and [force] is false, returns
     * [RemoveStatus.BLOCKED_DIRTY] without touching anything. With [force] true the
     * removal runs with `git worktree remove --force`.
     */
    fun safeRemove(pathOrBranch: String, force: Boolean = false): RemoveOutcome {
        val wt = findWorktree(pathOrBranch) ?: return RemoveOutcome(RemoveStatus.NOT_FOUND)
        val dir = File(wt.path)
        if (!force && isDirty(dir)) {
            return RemoveOutcome(RemoveStatus.BLOCKED_DIRTY)
        }
        val res = remove(wt.path, force = force)
        return RemoveOutcome(
            if (res.ok) RemoveStatus.REMOVED else RemoveStatus.GIT_ERROR,
            res,
        )
    }

    /** Removes a worktree; [force] passes `--force` (needed if it has changes). */
    fun remove(path: String, force: Boolean = false): GitResult {
        val args = buildList {
            add("worktree"); add("remove")
            if (force) add("--force")
            add(path)
        }
        return git(repoDir, args)
    }

    /** Runs `git worktree prune` to clean up administrative files of gone worktrees. */
    fun prune(): GitResult = git(repoDir, listOf("worktree", "prune", "-v"))
}
