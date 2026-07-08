package dev.alkom.gwm

import dev.alkom.gwm.git.GitCommand
import dev.alkom.gwm.git.GitResult
import dev.alkom.gwm.git.RealGitRunner
import dev.alkom.gwm.git.WorktreeService
import dev.alkom.gwm.scan.RepoScanner
import dev.alkom.gwm.scan.ScanService
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for multi-repo aggregation against REAL git repositories created
 * in a [TempDir] (the deterministic-fake-of-the-real-stack tier, TECHNICAL_PLAN §8):
 * we drive actual `git init` / `git worktree add`, then aggregate through the real
 * [WorktreeService], rather than mocking [ProcessBuilder]. Skipped entirely if `git`
 * is unavailable.
 */
class ScanServiceIntegrationTest {

    private fun git(dir: File, vararg args: String) = GitCommand.run(dir, *args)

    private fun gitAvailable(): Boolean =
        runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

    /** Initialise a repo with one commit on `main`. */
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

    @Test
    fun `aggregates worktrees across multiple real repos in parallel`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")

        val repoA = initRepo(root, "alpha")
        val repoB = initRepo(root, "beta")
        // Give beta a second worktree so we exercise multi-worktree aggregation too.
        // Place it OUTSIDE the scanned root so it doesn't get discovered as its own
        // top-level repo (a linked worktree also carries a `.git`).
        val extra = File(root.parentFile, "${root.name}-beta-feature")
        WorktreeService(repoB).add(extra, newBranch = "feature", baseRef = "main")

        // Also drop a non-git folder under the root — discovery must ignore it.
        File(root, "plain").mkdir()

        try {
            val repos = RepoScanner.findRepos(root)
            assertEquals(listOf("alpha", "beta"), repos.map { it.name }, "only git repos, sorted")

            val result = ScanService().scan(repos)
            assertTrue(result.errors.isEmpty(), "no repo should error: ${result.errors}")

            val byRepo = result.worktrees.groupBy({ it.repo }, { it.worktree.branch })
            assertEquals(setOf("alpha", "beta"), byRepo.keys)
            assertEquals(listOf("main"), byRepo["alpha"])
            assertEquals(setOf("main", "feature"), byRepo.getValue("beta").toSet())

            // Dirty flags must be populated (not the "?"/null placeholder).
            assertTrue(result.worktrees.all { it.worktree.dirty != null }, "dirty flags should be filled")
        } finally {
            WorktreeService(repoB).remove(extra.absolutePath, force = true)
            assertTrue(repoA.exists())
        }
    }

    @Test
    fun `one broken repo is isolated - the rest still aggregate`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")

        val healthy = initRepo(root, "healthy")

        // A repo whose git calls always fail: mimic an unreadable/broken repo by using a
        // fake runner that errors for this specific dir but delegates to real git otherwise.
        val brokenDir = File(root, "broken").apply { mkdir(); File(this, ".git").mkdir() }
        val poisonRunner: (File, List<String>) -> GitResult = { dir, args ->
            if (dir.absoluteFile.path.startsWith(brokenDir.absoluteFile.path)) {
                throw RuntimeException("simulated broken repo: $dir")
            }
            RealGitRunner(dir, args)
        }

        val repos = RepoScanner.findRepos(root)
        assertEquals(listOf("broken", "healthy"), repos.map { it.name })

        val result = ScanService(poisonRunner).scan(repos)

        // The healthy repo still produced its worktree despite broken throwing.
        assertEquals(setOf("healthy"), result.worktrees.map { it.repo }.toSet())
        assertEquals(listOf("main"), result.worktrees.map { it.worktree.branch })

        // The broken repo surfaced as an isolated error, not a crash.
        assertEquals(listOf("broken"), result.errors.map { it.repo })
        assertTrue(result.errors.single().reason.contains("simulated broken repo"))

        assertTrue(healthy.exists())
    }

    @Test
    fun `empty portfolio yields empty result, no errors`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        File(root, "just-a-folder").mkdir()

        val repos = RepoScanner.findRepos(root)
        assertTrue(repos.isEmpty())

        val result = ScanService().scan(repos)
        assertTrue(result.worktrees.isEmpty())
        assertTrue(result.errors.isEmpty())
    }
}
