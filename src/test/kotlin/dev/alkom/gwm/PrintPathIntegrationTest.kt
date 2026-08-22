package dev.alkom.gwm

import dev.alkom.gwm.git.GitCommand
import dev.alkom.gwm.git.WorktreeService
import dev.alkom.gwm.scan.RepoScanner
import dev.alkom.gwm.scan.ScanService
import dev.alkom.gwm.scan.WorktreeMatcher
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration test for the `--print-path` resolution pipeline (Этап 6) against a REAL
 * portfolio of git repos in a [TempDir] — scan → aggregate → fuzzy-resolve, the exact
 * chain [dev.alkom.gwm.PrintPath.emit] runs, minus the terminal I/O. This is the
 * deterministic-fake-of-the-real-stack tier (TECHNICAL_PLAN §8): actual `git init` /
 * `git worktree add`, not a mocked [ProcessBuilder]. Skipped if `git` is unavailable.
 */
class PrintPathIntegrationTest {

    private fun git(dir: File, vararg args: String) = GitCommand.run(dir, *args)

    private fun gitAvailable(): Boolean =
        runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

    private fun initRepo(parent: File, name: String): File {
        val dir = File(parent, name).apply { assertTrue(mkdirs()) }
        assertTrue(git(dir, "init", "-b", "main").ok, "git init failed")
        git(dir, "config", "user.email", "test@example.com")
        git(dir, "config", "user.name", "Test")
        File(dir, "README.md").writeText("hello\n")
        git(dir, "add", "README.md")
        assertTrue(git(dir, "commit", "-m", "initial").ok, "initial commit failed")
        return dir
    }

    /** Scan the portfolio and resolve, mirroring PrintPath.emit's resolution step. */
    private fun resolve(root: File, query: String): WorktreeMatcher.Match {
        val repos = RepoScanner.findRepos(root)
        val worktrees = ScanService().scan(repos).worktrees
        return WorktreeMatcher.resolve(worktrees, query)
    }

    /**
     * Multi-root variant, mirroring PrintPath.emit's multi-root resolution step exactly:
     * findRepos across every root → [ScanService.dedupRepos] → scan → resolve
     * (fix/print-path-multi-root).
     */
    private fun resolveMulti(roots: List<File>, query: String): WorktreeMatcher.Match {
        val repos = ScanService.dedupRepos(roots.flatMap { RepoScanner.findRepos(it) })
        val worktrees = ScanService().scan(repos).worktrees
        return WorktreeMatcher.resolve(worktrees, query)
    }

    @Test
    fun `resolves a fuzzy branch to the real worktree path`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        val repo = initRepo(root, "alpha")
        val feature = File(root, "alpha-feature-login")
        WorktreeService(repo).add(feature, newBranch = "feature/login", baseRef = "main")
        try {
            val match = resolve(root, "login")
            assertTrue(match is WorktreeMatcher.Match.Found)
            assertEquals(
                feature.absoluteFile.canonicalPath,
                File(match.worktree.worktree.path).canonicalPath,
            )
        } finally {
            WorktreeService(repo).remove(feature.absolutePath, force = true)
        }
    }

    @Test
    fun `no match on a real portfolio yields None (empty stdout - non-zero exit at the CLI)`(
        @TempDir root: File,
    ) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        initRepo(root, "alpha")
        val match = resolve(root, "does-not-exist")
        assertTrue(match is WorktreeMatcher.Match.None)
    }

    @Test
    fun `an exact branch name across multiple repos is reported ambiguous, never guessed`(
        @TempDir root: File,
    ) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        // Two repos, both with a `main` — an exact hit that isn't unique must be ambiguous.
        initRepo(root, "alpha")
        initRepo(root, "beta")
        val match = resolve(root, "main")
        assertTrue(match is WorktreeMatcher.Match.Ambiguous, "two `main`s must be ambiguous")
        assertEquals(2, match.candidates.size)
    }

    @Test // 47 — protects the `cd "$(...)"` contract: no shortened/decorated path may leak here.
    fun `resolved path is absolute with no tilde, ellipsis or ANSI`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        val repo = initRepo(root, "alpha")
        val feature = File(root, "alpha-feature-login")
        WorktreeService(repo).add(feature, newBranch = "feature/login", baseRef = "main")
        try {
            val match = resolve(root, "login")
            assertTrue(match is WorktreeMatcher.Match.Found)
            // This is exactly what PrintPath.emit prints to stdout.
            val emitted = File(match.worktree.worktree.path).absolutePath
            assertTrue(emitted.startsWith("/"), "must be absolute: $emitted")
            assertFalse(emitted.contains("~"), "no tilde: $emitted")
            assertFalse(emitted.contains("…"), "no ellipsis: $emitted")
            assertFalse(emitted.contains(""), "no ANSI escape: $emitted")
        } finally {
            WorktreeService(repo).remove(feature.absolutePath, force = true)
        }
    }

    // ── Multi-root resolution (fix/print-path-multi-root) ──

    @Test // the core fix: a worktree living under the SECOND root must resolve, not just roots[0].
    fun `resolves a worktree from the second root, not only the first`(
        @TempDir root1: File,
        @TempDir root2: File,
    ) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        initRepo(root1, "alpha")
        val beta = initRepo(root2, "beta")
        val feature = File(root2, "beta-feature-login")
        WorktreeService(beta).add(feature, newBranch = "feature/login", baseRef = "main")
        try {
            val match = resolveMulti(listOf(root1, root2), "login")
            assertTrue(match is WorktreeMatcher.Match.Found, "must reach into the second root")
            assertEquals(
                feature.absoluteFile.canonicalPath,
                File(match.worktree.worktree.path).canonicalPath,
            )
        } finally {
            WorktreeService(beta).remove(feature.absolutePath, force = true)
        }
    }

    @Test // a physical repo reachable from two roots (same root twice) must not double its worktrees.
    fun `dedup - the same repo reached via two roots resolves once, not ambiguous`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        val repo = initRepo(root, "alpha")
        val feature = File(root, "alpha-feature-x")
        WorktreeService(repo).add(feature, newBranch = "feature/x", baseRef = "main")
        try {
            // The same root listed twice would findRepos `alpha` twice; dedupRepos must collapse it,
            // so the fuzzy hit stays a single Found (not a spurious Ambiguous of two identical copies).
            val match = resolveMulti(listOf(root, root), "feature/x")
            assertTrue(match is WorktreeMatcher.Match.Found, "duplicate root must dedup, not become ambiguous: $match")
        } finally {
            WorktreeService(repo).remove(feature.absolutePath, force = true)
        }
    }

    @Test // two DIFFERENT repos of the same name in different roots stay ambiguous, with distinct paths.
    fun `same-named repos in different roots are ambiguous with distinct paths`(
        @TempDir root1: File,
        @TempDir root2: File,
    ) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        initRepo(root1, "foo")
        initRepo(root2, "foo")
        val match = resolveMulti(listOf(root1, root2), "main")
        assertTrue(match is WorktreeMatcher.Match.Ambiguous, "two different `foo` repos must be ambiguous: $match")
        assertEquals(2, match.candidates.size)
        val paths = match.candidates.map { File(it.worktree.path).canonicalPath }.toSet()
        assertEquals(2, paths.size, "the two candidates must have distinct paths so the user can tell them apart: $paths")
    }
}
