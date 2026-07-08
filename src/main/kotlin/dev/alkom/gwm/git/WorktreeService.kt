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
     * Annotates each worktree with its [OrphanStatus] (Этап 5) — a *hint* about
     * whether it looks stale (branch merged into base / no upstream / prunable).
     * Kept separate from [list]/[withDirtyFlags] because it costs extra git calls
     * per worktree, and callers may want the cheaper listing first.
     *
     * This is purely informational: it never removes anything and does not touch
     * [safeRemove]/[remove]. `gwm` never deletes a worktree on its own (PLAN §4/§5).
     *
     * The merged/upstream probes run against the repo the worktrees belong to, using
     * this service's [git] runner — no git-call logic is duplicated, and the raw facts
     * are combined by the pure [OrphanClassifier].
     */
    fun withOrphanStatus(worktrees: List<Worktree>): List<Worktree> {
        val base = baseBranch()
        // One `git branch --merged` call for the whole repo, not one per worktree:
        // the command already returns every merged branch in a single pass, so
        // re-running it N times (found by independent /code-review) was N-1
        // redundant subprocesses for no extra information.
        val merged = base?.let { mergedBranches(it) } ?: emptySet()
        return worktrees.map { wt ->
            val isMerged = wt.branch != null && base != null && wt.branch != base && wt.branch in merged
            val noUpstream = wt.branch != null && !hasUpstream(wt.branch)
            wt.copy(orphan = OrphanClassifier.classify(wt, merged = isMerged, noUpstream = noUpstream))
        }
    }

    /**
     * The repository's base branch — `main` or `master`, whichever exists as a local
     * ref (preferring `main`). Used as the target for the "branch already merged" check.
     * Returns null when neither is present, in which case the merged signal is skipped.
     *
     * Known limitation (flagged by independent /code-review): repos whose trunk is
     * neither `main` nor `master` (e.g. `develop`) get no merged-signal at all — this
     * is a silent false-negative, never a false-positive, which matches the "hint,
     * never autodelete" principle (PLAN §4/§5): understating staleness is safe,
     * overstating it is not.
     */
    fun baseBranch(): String? {
        for (candidate in listOf("main", "master")) {
            val res = git(repoDir, listOf("rev-parse", "--verify", "--quiet", "refs/heads/$candidate"))
            if (res.ok) return candidate
        }
        return null
    }

    /**
     * The set of local branches fully merged into [base]. Uses `git branch --merged
     * <base>` — one call per repo (not per worktree, see [withOrphanStatus]) — which
     * lists exactly the local branches whose tip is reachable from [base], i.e. whose
     * work already lives on the base branch and can be safely dropped.
     */
    private fun mergedBranches(base: String): Set<String> {
        val res = git(repoDir, listOf("branch", "--merged", base, "--format=%(refname:short)"))
        if (!res.ok) return emptySet()
        return res.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * True if [branch] tracks a remote upstream. Probes `<branch>@{upstream}`: git
     * exits non-zero when the branch has no configured upstream, which is precisely the
     * "local-only, nothing pushed" signal we treat as a mild staleness hint.
     */
    private fun hasUpstream(branch: String): Boolean =
        git(repoDir, listOf("rev-parse", "--abbrev-ref", "$branch@{upstream}")).ok

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
