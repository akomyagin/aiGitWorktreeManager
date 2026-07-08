package dev.alkom.gwm

import dev.alkom.gwm.git.GitCommand
import dev.alkom.gwm.git.WorktreeService
import dev.alkom.gwm.git.WorktreeService.RemoveStatus
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests against a REAL git repository created in a [TempDir]. This is
 * the deterministic-fake-of-the-real-stack tier promised for Этап 1+ (TECHNICAL_PLAN
 * §8): we drive actual `git init` / `git worktree add` / `git worktree remove`
 * rather than mocking [ProcessBuilder]. If `git` is unavailable the whole class is
 * skipped via [assumeTrue].
 */
class WorktreeServiceIntegrationTest {

    private fun git(dir: File, vararg args: String) = GitCommand.run(dir, *args)

    /** Initialise a repo with one commit on `main` and a deterministic identity. */
    private fun initRepo(dir: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        assertTrue(git(dir, "init", "-b", "main").ok, "git init failed")
        git(dir, "config", "user.email", "test@example.com")
        git(dir, "config", "user.name", "Test")
        File(dir, "README.md").writeText("hello\n")
        git(dir, "add", "README.md")
        assertTrue(git(dir, "commit", "-m", "initial").ok, "initial commit failed")
    }

    private fun gitAvailable(): Boolean =
        runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

    @Test
    fun `list returns the main worktree of a fresh repo`(@TempDir tmp: File) {
        initRepo(tmp)
        val service = WorktreeService(tmp)

        assertTrue(service.isGitRepo())
        val worktrees = service.list()
        assertEquals(1, worktrees.size)
        assertEquals("main", worktrees[0].branch)
        assertTrue(worktrees[0].isMain)
    }

    @Test
    fun `add creates a new worktree with a new branch`(@TempDir tmp: File) {
        initRepo(tmp)
        val service = WorktreeService(tmp)
        val target = File(tmp.parentFile, "${tmp.name}-feature")
        try {
            val res = service.add(target, newBranch = "feature", baseRef = "main")
            assertTrue(res.ok, "add failed: ${res.stderr}")
            assertTrue(target.isDirectory)

            val branches = service.list().map { it.branch }
            assertTrue("feature" in branches)
            assertTrue("main" in branches)
        } finally {
            service.remove(target.absolutePath, force = true)
        }
    }

    @Test
    fun `defaultWorktreePath resolves against the real repo root`(@TempDir tmp: File) {
        initRepo(tmp)
        val service = WorktreeService(tmp)

        val path = service.defaultWorktreePath("bugfix/thing")

        assertEquals(File(tmp.parentFile, "${tmp.name}-bugfix-thing").absolutePath, path.absolutePath)
    }

    @Test
    fun `safeRemove removes a clean worktree`(@TempDir tmp: File) {
        initRepo(tmp)
        val service = WorktreeService(tmp)
        val target = File(tmp.parentFile, "${tmp.name}-clean")
        service.add(target, newBranch = "clean", baseRef = "main")

        val outcome = service.safeRemove(target.absolutePath, force = false)

        assertEquals(RemoveStatus.REMOVED, outcome.status)
        assertFalse(target.exists())
    }

    @Test
    fun `safeRemove blocks a dirty worktree unless forced`(@TempDir tmp: File) {
        initRepo(tmp)
        val service = WorktreeService(tmp)
        val target = File(tmp.parentFile, "${tmp.name}-dirty")
        service.add(target, newBranch = "dirty", baseRef = "main")
        // Make it dirty.
        File(target, "uncommitted.txt").writeText("work in progress\n")

        try {
            val blocked = service.safeRemove(target.absolutePath, force = false)
            assertEquals(RemoveStatus.BLOCKED_DIRTY, blocked.status)
            assertTrue(target.exists(), "worktree must survive a blocked removal")

            val forced = service.safeRemove(target.absolutePath, force = true)
            assertEquals(RemoveStatus.REMOVED, forced.status)
            assertFalse(target.exists())
        } finally {
            if (target.exists()) service.remove(target.absolutePath, force = true)
        }
    }

    @Test
    fun `findWorktree locates by branch and by path on a real repo`(@TempDir tmp: File) {
        initRepo(tmp)
        val service = WorktreeService(tmp)
        val target = File(tmp.parentFile, "${tmp.name}-find")
        service.add(target, newBranch = "findme", baseRef = "main")
        try {
            assertNotNull(service.findWorktree("findme"))
            assertNotNull(service.findWorktree(target.absolutePath))
            assertNull(service.findWorktree("ghost"))
        } finally {
            service.remove(target.absolutePath, force = true)
        }
    }

    @Test
    fun `withDirtyFlags reflects real working-tree state`(@TempDir tmp: File) {
        initRepo(tmp)
        val service = WorktreeService(tmp)
        val target = File(tmp.parentFile, "${tmp.name}-flags")
        service.add(target, newBranch = "flags", baseRef = "main")
        File(target, "change.txt").writeText("x\n")
        try {
            val flagged = service.withDirtyFlags(service.list())
            val main = flagged.first { it.isMain }
            val feature = flagged.first { it.branch == "flags" }
            assertEquals(false, main.dirty)
            assertEquals(true, feature.dirty)
        } finally {
            service.remove(target.absolutePath, force = true)
        }
    }

    /**
     * Regression test for the crash found by independent /code-review on Этап 1:
     * `git worktree add` registers a worktree, but nothing stops the user (or an
     * accidental `rm -rf`) from deleting its directory outside of git — the
     * worktree stays listed as "prunable" until `git worktree prune` runs. That
     * is exactly the orphaned-worktree scenario this tool is meant to surface,
     * so it must never crash the app.
     */
    @Test
    fun `withDirtyFlags and safeRemove tolerate a worktree whose directory was deleted manually`(
        @TempDir tmp: File,
    ) {
        initRepo(tmp)
        val service = WorktreeService(tmp)
        val target = File(tmp.parentFile, "${tmp.name}-orphan")
        service.add(target, newBranch = "orphan", baseRef = "main")

        // Simulate the user deleting the worktree folder by hand, outside git.
        assertTrue(target.deleteRecursively(), "failed to delete worktree dir for the test")

        // Rendering the list must not throw — this used to crash with an
        // uncaught IOException from ProcessBuilder.start() on a missing dir.
        val flagged = service.withDirtyFlags(service.list())
        val orphan = flagged.first { it.branch == "orphan" }
        assertEquals(false, orphan.dirty, "a missing directory holds no uncommitted changes to lose")

        // safeRemove must not block on BLOCKED_DIRTY (nothing to lose) and must
        // not crash; git itself is left to clean up the administrative entry.
        val outcome = service.safeRemove("orphan", force = false)
        assertTrue(
            outcome.status == WorktreeService.RemoveStatus.REMOVED ||
                outcome.status == WorktreeService.RemoveStatus.GIT_ERROR,
            "expected git to handle the missing directory itself, got ${outcome.status}",
        )

        // Whatever git left behind, prune always succeeds and never throws.
        assertTrue(service.prune().let { true }, "prune must not throw")
    }
}
