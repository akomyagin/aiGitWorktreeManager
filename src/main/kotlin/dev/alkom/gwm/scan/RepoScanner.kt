package dev.alkom.gwm.scan

import java.io.File

/**
 * Discovers git repositories directly under a portfolio root (default
 * `~/Projects/ai-projects`, overridable via `--root` or the `GWM_ROOT` env var).
 *
 * Deliberately scans exactly ONE level of sub-directories: this is a flat portfolio
 * of side-by-side projects, not a nested tree, so recursing would only surface a
 * parent repo's own linked worktrees (and any vendored/embedded repos) as if they
 * were top-level projects. One level keeps the "what projects do I have" model honest.
 *
 * A directory counts as a repository only when its `.git` entry is a DIRECTORY —
 * a primary checkout. A linked worktree stores `.git` as a plain FILE pointing at
 * the shared gitdir, and is deliberately NOT counted as its own top-level repo here:
 * `git worktree list` on the primary checkout already returns every one of its
 * worktrees (including ones living as siblings under this same root, which is the
 * layout `gwm add`'s default path produces). Counting the file form too would make
 * ScanService aggregate each such worktree twice — once under the primary's name,
 * once again under the worktree's own directory name (found by independent
 * /code-review on the first version of this scanner).
 *
 * Pure filesystem logic — no git subprocess — so it is fully unit-testable without git.
 */
object RepoScanner {

    /** Default portfolio root: `~/Projects/ai-projects`. */
    fun defaultRoot(): File = File(System.getProperty("user.home"), "Projects/ai-projects")

    /**
     * Resolves the scan root from an explicit [override] (e.g. a `--root` flag), else
     * the `GWM_ROOT` env var, else [defaultRoot]. Empty/blank values are ignored so a
     * stray `GWM_ROOT=""` doesn't silently point the scan at the filesystem root.
     */
    fun resolveRoot(override: String? = null, env: (String) -> String? = System::getenv): File {
        override?.takeIf { it.isNotBlank() }?.let { return File(it).absoluteFile }
        env("GWM_ROOT")?.takeIf { it.isNotBlank() }?.let { return File(it).absoluteFile }
        return defaultRoot()
    }

    /**
     * Returns the immediate sub-directories of [root] that are git repositories,
     * sorted by name for a stable, predictable listing. A missing or non-directory
     * [root] yields an empty list rather than throwing — a mis-typed root should be
     * reported by the caller as "no repos found", not crash the scan.
     */
    fun findRepos(root: File): List<File> {
        val children = root.takeIf { it.isDirectory }?.listFiles() ?: return emptyList()
        return children
            .filter { it.isDirectory && isGitRepo(it) }
            .sortedBy { it.name }
    }

    /**
     * True when [dir] is a primary git checkout — its `.git` entry is a directory.
     * A `.git` FILE marks a linked worktree, which is intentionally excluded (see
     * class doc): it is already reachable via its primary checkout's worktree list.
     */
    private fun isGitRepo(dir: File): Boolean = File(dir, ".git").isDirectory
}
