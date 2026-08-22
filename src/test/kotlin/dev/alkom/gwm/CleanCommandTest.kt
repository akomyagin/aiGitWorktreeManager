package dev.alkom.gwm

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import com.github.ajalt.mordant.rendering.AnsiLevel
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
 * Command-level tests for `gwm clean` via Clikt's `test()` harness (Этап 10, plan §Тест-кейсы /
 * Командные), driving REAL temporary git repositories in @TempDir. `test()` gives no TTY, so every
 * deletion path is exercised with `--yes`/`--dry-run`; the aggregated interactive prompt is covered
 * indirectly by the "no --yes, no TTY refuses" case.
 */
class CleanCommandTest {

    private fun app() = Gwm().subcommands(CleanCommand())

    private fun gitAvailable(): Boolean =
        runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

    /** Minimal real git repo named [name] under [parent] with one commit on `main`. */
    private fun makeRepo(parent: File, name: String): File {
        val dir = File(parent, name).apply { mkdirs() }
        GitCommand.run(dir, "init", "-b", "main")
        GitCommand.run(dir, "config", "user.email", "t@e.com")
        GitCommand.run(dir, "config", "user.name", "T")
        File(dir, "README.md").writeText("hi\n")
        GitCommand.run(dir, "add", "README.md")
        GitCommand.run(dir, "commit", "-m", "init")
        return dir
    }

    /**
     * Adds a worktree on a NEW local branch (no upstream → orphaned via OrphanStatus.noUpstream),
     * at a sibling dir. Returns the worktree directory. If [dirty], writes an uncommitted file.
     */
    private fun addOrphanWorktree(repo: File, branch: String, dirty: Boolean = false): File {
        val wtDir = File(repo.parentFile, "${repo.name}-$branch")
        WorktreeService(repo).add(wtDir, newBranch = branch, baseRef = "main")
        if (dirty) File(wtDir, "scratch.txt").writeText("uncommitted\n")
        return wtDir
    }

    @Test // dry-run: lists the candidate, deletes NOTHING
    fun `dry-run lists candidate and removes nothing`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        val repo = makeRepo(root, "alpha")
        val wt = addOrphanWorktree(repo, "feat")
        val r = app().test("clean --dry-run ${root.path}", ansiLevel = AnsiLevel.NONE, width = 120)
        assertEquals(0, r.statusCode, r.output)
        assertTrue("feat" in r.output, "must name the candidate: ${r.output}")
        assertTrue("dry-run" in r.output, r.output)
        assertTrue(wt.exists(), "dry-run must not delete the worktree: ${r.output}")
    }

    @Test // finding 1: --dry-run --force (no --yes) must not PROMISE the dirty is deleted
    // Without --yes, each dirty still needs an interactive per-item confirmation the dry-run can't know
    // the answer to — so the prediction must say "requires confirmation", never fold it into "удалено".
    fun `dry-run force without yes does not overcount dirty as deleted`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        val repo = makeRepo(root, "alpha")
        addOrphanWorktree(repo, "feat", dirty = true) // one dirty candidate, zero clean candidates
        val r = app().test("clean --dry-run --force ${root.path}", ansiLevel = AnsiLevel.NONE, width = 120)
        assertEquals(0, r.statusCode, r.output)
        // Zero clean candidates → "было бы удалено: 0" (the dirty is NOT promised deleted), and it is
        // flagged as requiring confirmation instead.
        assertTrue("было бы удалено: 0" in r.output, "must not count the unconfirmed dirty as deletable: ${r.output}")
        assertTrue("потребуют подтверждения" in r.output, "dirty under --force-only must be flagged as needing confirmation: ${r.output}")
    }

    @Test // finding 1: --dry-run --yes --force DOES promise the dirty is deleted (the only such path)
    fun `dry-run yes force counts dirty as deletable`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        val repo = makeRepo(root, "alpha")
        addOrphanWorktree(repo, "feat", dirty = true)
        val r = app().test("clean --dry-run --yes --force ${root.path}", ansiLevel = AnsiLevel.NONE, width = 120)
        assertEquals(0, r.statusCode, r.output)
        assertTrue("было бы удалено: 1" in r.output, "with --yes --force the dirty WOULD be deleted: ${r.output}")
    }

    @Test // --yes deletes a non-dirty orphaned worktree
    fun `yes deletes non-dirty orphaned`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        val repo = makeRepo(root, "alpha")
        val wt = addOrphanWorktree(repo, "feat", dirty = false)
        val r = app().test("clean --yes ${root.path}", ansiLevel = AnsiLevel.NONE, width = 120)
        assertEquals(0, r.statusCode, r.output)
        assertFalse(wt.exists(), "non-dirty orphaned must be deleted: ${r.output}")
        assertTrue("Удалено: 1" in r.output, "summary must report one deletion: ${r.output}")
    }

    @Test // dirty is skipped without --force
    fun `dirty skipped without force`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        val repo = makeRepo(root, "alpha")
        val wt = addOrphanWorktree(repo, "feat", dirty = true)
        val r = app().test("clean --yes ${root.path}", ansiLevel = AnsiLevel.NONE, width = 120)
        assertEquals(0, r.statusCode, r.output)
        assertTrue(wt.exists(), "dirty worktree must NOT be deleted without --force: ${r.output}")
        assertTrue("пропущен" in r.output, "must report a skip: ${r.output}")
        assertTrue("пропущено (dirty): 1" in r.output, "summary must count the dirty skip: ${r.output}")
    }

    @Test // dirty IS deleted with --yes --force (two explicit flags)
    fun `dirty deleted with yes and force`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        val repo = makeRepo(root, "alpha")
        val wt = addOrphanWorktree(repo, "feat", dirty = true)
        val r = app().test("clean --yes --force ${root.path}", ansiLevel = AnsiLevel.NONE, width = 120)
        assertEquals(0, r.statusCode, r.output)
        assertFalse(wt.exists(), "dirty worktree must be deleted with --yes --force: ${r.output}")
        assertTrue("Удалено: 1" in r.output, "summary must report one deletion: ${r.output}")
    }

    @Test // nothing to delete: only main, no orphaned
    fun `nothing to delete when no orphaned`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        makeRepo(root, "alpha")
        val r = app().test("clean --yes ${root.path}", ansiLevel = AnsiLevel.NONE, width = 120)
        assertEquals(0, r.statusCode, r.output)
        assertTrue("нечего удалять" in r.output, "must report nothing to delete: ${r.output}")
    }

    @Test // no --yes and no TTY → refuse (non-zero), delete nothing
    fun `no yes no TTY refuses and deletes nothing`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        val repo = makeRepo(root, "alpha")
        val wt = addOrphanWorktree(repo, "feat", dirty = false)
        val r = app().test("clean ${root.path}", ansiLevel = AnsiLevel.NONE, width = 120)
        assertTrue(r.statusCode != 0, "without --yes and without a TTY it must refuse (non-zero): ${r.output}")
        assertTrue(wt.exists(), "safety invariant: no consent → zero deletions: ${r.output}")
    }

    @Test // safety: --force alone (no --yes, no TTY) must NOT delete a dirty worktree
    // Guards finding 4: the aggregated gate itself refuses without --yes/TTY, so `--force` on its own
    // never reaches (let alone force-deletes) a dirty worktree. A future "simplification" that let
    // --force bypass consent would break THIS test loudly instead of silently drifting from intent.
    fun `force alone without yes never deletes dirty`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        val repo = makeRepo(root, "alpha")
        val wt = addOrphanWorktree(repo, "feat", dirty = true)
        val r = app().test("clean --force ${root.path}", ansiLevel = AnsiLevel.NONE, width = 120)
        // The aggregated gate refuses first (no --yes, no TTY) → non-zero, nothing deleted.
        assertTrue(r.statusCode != 0, "--force without --yes and without a TTY must refuse (non-zero): ${r.output}")
        assertTrue(wt.exists(), "safety invariant: --force alone must never delete a dirty worktree: ${r.output}")
    }

    @Test // main worktree never appears as a candidate (real stack)
    fun `main is never a candidate`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        val repo = makeRepo(root, "alpha")
        addOrphanWorktree(repo, "feat")
        val r = app().test("clean --dry-run ${root.path}", ansiLevel = AnsiLevel.NONE, width = 120)
        assertEquals(0, r.statusCode, r.output)
        // main worktree's label is "main"; the candidate block must not list it. The repo dir name is
        // "alpha", and the main worktree row would read "alpha/main" — assert that pairing is absent.
        assertTrue("alpha/main" !in r.output, "main worktree must never be a candidate: ${r.output}")
    }

    @Test // multi-root portfolio: orphaned in BOTH config roots are listed (no CLI root)
    fun `multi-root config lists candidates from all roots`(
        @TempDir root1: File,
        @TempDir root2: File,
        @TempDir cfgDir: File,
    ) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        val repoA = makeRepo(root1, "alpha")
        val repoB = makeRepo(root2, "zeta")
        addOrphanWorktree(repoA, "one")
        addOrphanWorktree(repoB, "two")
        val cfg = File(cfgDir, "config.toml").apply {
            writeText("roots = [\"${root1.path}\", \"${root2.path}\"]\n")
        }
        val r = app().test("--config=${cfg.path} clean --dry-run", ansiLevel = AnsiLevel.NONE, width = 200)
        assertEquals(0, r.statusCode, r.output)
        assertTrue("one" in r.output && "two" in r.output, "both roots' candidates must be listed: ${r.output}")
    }

    @Test // continues after each deletion: two non-dirty orphaned → both removed, "Удалено: 2"
    fun `two non-dirty orphaned both removed`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        val repo = makeRepo(root, "alpha")
        val wt1 = addOrphanWorktree(repo, "one")
        val wt2 = addOrphanWorktree(repo, "two")
        val r = app().test("clean --yes ${root.path}", ansiLevel = AnsiLevel.NONE, width = 120)
        assertEquals(0, r.statusCode, r.output)
        assertFalse(wt1.exists(), "first must be deleted: ${r.output}")
        assertFalse(wt2.exists(), "second must be deleted: ${r.output}")
        assertTrue("Удалено: 2" in r.output, "summary must report two deletions: ${r.output}")
    }
}
