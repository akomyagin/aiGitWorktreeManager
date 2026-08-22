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
import kotlin.test.assertNull
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
        // Placed INSIDE the scanned root, as a sibling of "beta" — exactly the layout
        // `gwm add`'s default path produces. This must NOT be discovered as its own
        // top-level repo (regression test for the double-counting bug found by
        // independent /code-review: RepoScanner now only counts `.git`-DIRECTORY
        // entries as repos, so a linked worktree's `.git`-FILE is correctly skipped
        // here and it is only ever reached via beta's own `git worktree list`).
        val extra = File(root, "beta-feature")
        WorktreeService(repoB).add(extra, newBranch = "feature", baseRef = "main")

        // Also drop a non-git folder under the root — discovery must ignore it.
        File(root, "plain").mkdir()

        try {
            val repos = RepoScanner.findRepos(root)
            assertEquals(
                listOf("alpha", "beta"),
                repos.map { it.name },
                "the linked worktree beta-feature must not surface as its own repo",
            )

            val result = ScanService().scan(repos)
            assertTrue(result.errors.isEmpty(), "no repo should error: ${result.errors}")

            val byRepo = result.worktrees.groupBy({ it.repo }, { it.worktree.branch })
            assertEquals(setOf("alpha", "beta"), byRepo.keys)
            assertEquals(listOf("main"), byRepo["alpha"])
            assertEquals(setOf("main", "feature"), byRepo.getValue("beta").toSet())
            // Exactly 3 worktrees total (alpha/main, beta/main, beta/feature) — not 5,
            // which is what a double-count of beta-feature-as-its-own-repo would yield.
            assertEquals(3, result.worktrees.size, "beta-feature must not be double-counted")

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

    @Test // 51/53 — real upstream: ahead/behind and last-commit age are populated correctly
    fun `worktree with upstream reports correct ahead-behind and age`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")

        // A bare "remote" plus a tracking clone under the scanned root. The clone's `main` tracks
        // origin/main; we then add 2 local commits so it is ahead 2, behind 0.
        val bare = File(root, "up.git").apply { assertTrue(mkdirs()) }
        assertTrue(git(bare, "init", "--bare", "-b", "main").ok, "bare init failed")

        val clone = File(root, "clone")
        assertTrue(GitCommand.run(root, "clone", bare.absolutePath, clone.name).ok, "clone failed")
        git(clone, "config", "user.email", "test@example.com")
        git(clone, "config", "user.name", "Test")
        File(clone, "f").writeText("a\n"); git(clone, "add", "f"); git(clone, "commit", "-m", "c1")
        assertTrue(git(clone, "push", "-u", "origin", "main").ok, "initial push failed")
        // Two local commits ahead of the upstream.
        File(clone, "f").writeText("b\n"); git(clone, "commit", "-am", "c2")
        File(clone, "f").writeText("c\n"); git(clone, "commit", "-am", "c3")

        // Only the clone is a real repo we care about; the bare dir has no worktrees to speak of.
        val repos = RepoScanner.findRepos(root).filter { it.name == "clone" }
        val result = ScanService().scan(repos)
        assertTrue(result.errors.isEmpty(), "no repo should error: ${result.errors}")

        val wt = result.worktrees.single { it.repo == "clone" }.worktree
        assertEquals(2, wt.aheadBehind?.ahead, "ahead should be 2 (two local commits)")
        assertEquals(0, wt.aheadBehind?.behind, "behind should be 0")
        assertTrue(wt.lastCommitEpoch != null && wt.lastCommitEpoch!! > 0, "age epoch must be filled")
    }

    @Test // 52 — no upstream: aheadBehind null, and it does NOT surface as a scan error
    fun `worktree without upstream reports null ahead-behind and no error`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")

        // A plain local repo (initRepo commits on `main` but sets no upstream).
        initRepo(root, "solo")

        val repos = RepoScanner.findRepos(root)
        val result = ScanService().scan(repos)

        assertTrue(result.errors.isEmpty(), "no-upstream must not become a RepoError: ${result.errors}")
        val wt = result.worktrees.single { it.repo == "solo" }.worktree
        assertNull(wt.aheadBehind, "a branch with no upstream must have null ahead/behind")
        assertTrue(wt.lastCommitEpoch != null, "age epoch still filled even without upstream")
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

    // --- Этап 9: dedupRepos (pure logic, no git) --------------------------------------------

    @Test // 9: same dir spelled two ways collapses to one
    fun `dedupRepos collapses two spellings of the same directory`(@TempDir root: File) {
        val repo = File(root, "repo").apply { assertTrue(mkdirs()) }
        val out = ScanService.dedupRepos(listOf(repo, File(root, "repo/")))
        assertEquals(1, out.size, "'/x/repo' and '/x/repo/' must dedup: $out")
    }

    @Test // 9: order of first appearance preserved with a mix of uniques and dupes
    fun `dedupRepos keeps first-appearance order`(@TempDir root: File) {
        val a = File(root, "a").apply { assertTrue(mkdirs()) }
        val b = File(root, "b").apply { assertTrue(mkdirs()) }
        val out = ScanService.dedupRepos(listOf(a, b, File(root, "a/"), b))
        assertEquals(listOf(a.absoluteFile.normalize().path, b.absoluteFile.normalize().path), out.map { it.absoluteFile.normalize().path })
    }

    // --- Этап 9: aggregation across multiple roots (real git) --------------------------------

    @Test // 9: two roots, one repo each — worktrees of both aggregate, order = root1 then root2
    fun `aggregates repos from two separate roots in root order`(@TempDir root1: File, @TempDir root2: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        initRepo(root1, "alpha")
        initRepo(root2, "zeta")

        val allRepos = listOf(root1, root2).flatMap { RepoScanner.findRepos(it) }
        val repos = ScanService.dedupRepos(allRepos)
        val result = ScanService().scan(repos)

        assertTrue(result.errors.isEmpty(), "no repo should error: ${result.errors}")
        // root1's alpha comes before root2's zeta (roots kept in config order, plan §2).
        assertEquals(listOf("alpha", "zeta"), result.worktrees.map { it.repo })
    }

    @Test // 9: one physical repo reachable from two roots (same root listed twice) → dedup to one
    fun `same repo reachable from two roots is aggregated once`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        initRepo(root, "solo")

        // Simulate the same root appearing twice in rootsToScan — the direct test of
        // "repo reachable from several roots → dedup" (plan §6).
        val allRepos = listOf(root, root).flatMap { RepoScanner.findRepos(it) }
        assertEquals(2, allRepos.size, "sanity: pre-dedup the repo appears twice")

        val repos = ScanService.dedupRepos(allRepos)
        assertEquals(1, repos.size, "dedup must collapse the same physical repo")

        val result = ScanService().scan(repos)
        assertEquals(1, result.worktrees.count { it.repo == "solo" }, "each worktree must appear once")
    }

    @Test // 9: two DIFFERENT repos with the same name from two roots — both kept, no crash
    fun `same-named repos from different roots both appear`(@TempDir root1: File, @TempDir root2: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        initRepo(root1, "foo")
        initRepo(root2, "foo")

        val allRepos = listOf(root1, root2).flatMap { RepoScanner.findRepos(it) }
        val repos = ScanService.dedupRepos(allRepos)
        assertEquals(2, repos.size, "two DIFFERENT repos named foo are not duplicates: $repos")

        val result = ScanService().scan(repos)
        assertTrue(result.errors.isEmpty(), "no repo should error: ${result.errors}")
        val foos = result.worktrees.filter { it.repo == "foo" }
        assertEquals(2, foos.size, "both foo repos' worktrees must be present")
        // Different physical paths, same displayed name.
        assertEquals(2, foos.map { it.worktree.path }.toSet().size, "the two foos must have distinct paths")
    }
}
