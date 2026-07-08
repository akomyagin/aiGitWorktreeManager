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
 * A directory counts as a repository when it contains a `.git` entry — either a
 * directory (the usual primary checkout) OR a plain file (a linked worktree stores
 * its `.git` as a file pointing at the shared gitdir). Accepting the file form means
 * a worktree placed next to its parent under the root is still recognised as a repo.
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

    /** True when [dir] holds a `.git` entry (directory for a primary checkout, file for a worktree). */
    private fun isGitRepo(dir: File): Boolean = File(dir, ".git").exists()
}
