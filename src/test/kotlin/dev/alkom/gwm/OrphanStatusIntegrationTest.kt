package dev.alkom.gwm

import dev.alkom.gwm.git.GitCommand
import dev.alkom.gwm.git.WorktreeService
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the Этап-5 orphaned/stale detection against a REAL git repo in
 * a [TempDir] (the deterministic-fake-of-the-real-stack tier, TECHNICAL_PLAN §8): we
 * drive actual `git merge` / `git worktree add` and assert what [WorktreeService.
 * withOrphanStatus] observes, rather than mocking [ProcessBuilder]. Skipped if `git`
 * is unavailable.
 *
 * Note: a fresh temp repo has no remote, so *every* local branch legitimately has no
 * upstream — the "no-upstream" signal fires broadly here. Assertions therefore target
 * the specific signal under test on each worktree and never assume a branch is "clean".
 */
class OrphanStatusIntegrationTest {

    private fun git(dir: File, vararg args: String) = GitCommand.run(dir, *args)

    private fun gitAvailable(): Boolean =
        runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

    private fun initRepo(dir: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        assertTrue(git(dir, "init", "-b", "main").ok, "git init failed")
        git(dir, "config", "user.email", "test@example.com")
        git(dir, "config", "user.name", "Test")
        File(dir, "README.md").writeText("hello\n")
        git(dir, "add", "README.md")
        assertTrue(git(dir, "commit", "-m", "initial").ok, "initial commit failed")
    }

    @Test
    fun `baseBranch prefers main then master`(@TempDir tmp: File) {
        initRepo(tmp)
        assertEquals("main", WorktreeService(tmp).baseBranch())
    }

    @Test
    fun `a branch merged into main is flagged merged`(@TempDir tmp: File) {
        initRepo(tmp)
        val service = WorktreeService(tmp)
        val target = File(tmp.parentFile, "${tmp.name}-merged")
        service.add(target, newBranch = "done", baseRef = "main")
        try {
            // Commit some work on the worktree's branch, then merge it back into main.
            File(target, "work.txt").writeText("finished\n")
            git(target, "add", "work.txt")
            assertTrue(git(target, "commit", "-m", "work").ok)
            assertTrue(git(tmp, "merge", "--no-ff", "done", "-m", "merge done").ok, "merge failed")

            val flagged = service.withOrphanStatus(service.list())
            val done = flagged.first { it.branch == "done" }
            assertTrue(done.orphan.merged, "a merged branch must be flagged merged")
            assertTrue(done.orphan.isOrphaned)
            assertTrue("merged" in done.orphan.reasons)

            // The main worktree is never flagged, even though its own tip trivially
            // contains itself.
            val main = flagged.first { it.isMain }
            assertFalse(main.orphan.isOrphaned, "main worktree must never be flagged")
        } finally {
            service.remove(target.absolutePath, force = true)
        }
    }

    @Test
    fun `an unmerged local branch without a remote is flagged no-upstream but not merged`(
        @TempDir tmp: File,
    ) {
        initRepo(tmp)
        val service = WorktreeService(tmp)
        val target = File(tmp.parentFile, "${tmp.name}-wip")
        service.add(target, newBranch = "wip", baseRef = "main")
        try {
            // Diverge from main so it is NOT merged, and never set an upstream.
            File(target, "wip.txt").writeText("in progress\n")
            git(target, "add", "wip.txt")
            assertTrue(git(target, "commit", "-m", "wip").ok)

            val flagged = service.withOrphanStatus(service.list())
            val wip = flagged.first { it.branch == "wip" }
            assertFalse(wip.orphan.merged, "a diverged branch must not be flagged merged")
            assertTrue(wip.orphan.noUpstream, "a local-only branch must be flagged no-upstream")
        } finally {
            service.remove(target.absolutePath, force = true)
        }
    }

    @Test
    fun `a worktree whose directory was deleted manually is flagged prunable`(@TempDir tmp: File) {
        initRepo(tmp)
        val service = WorktreeService(tmp)
        val target = File(tmp.parentFile, "${tmp.name}-gone")
        service.add(target, newBranch = "gone", baseRef = "main")

        // Reproduce the Этап-1 orphaned scenario: user deletes the worktree dir by hand.
        assertTrue(target.deleteRecursively(), "failed to delete worktree dir for the test")

        val flagged = service.withOrphanStatus(service.list())
        val gone = flagged.first { it.branch == "gone" }
        assertTrue(gone.orphan.prunable, "a worktree with a missing dir must be flagged prunable")
        assertTrue(gone.orphan.isOrphaned)
        assertTrue("prunable" in gone.orphan.reasons)

        // Purely informational — prune (explicit) is still what actually cleans it up.
        assertTrue(service.prune().let { true }, "prune must not throw")
    }
}
