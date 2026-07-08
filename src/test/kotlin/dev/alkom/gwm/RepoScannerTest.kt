package dev.alkom.gwm

import dev.alkom.gwm.scan.RepoScanner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for repository discovery. Pure filesystem logic — no git subprocess — so
 * we fabricate the `.git` markers directly instead of running `git init`, keeping this
 * tier fast and git-independent (git-backed aggregation is covered separately in the
 * integration test).
 */
class RepoScannerTest {

    private fun dir(parent: File, name: String): File =
        File(parent, name).apply { assertTrue(mkdirs(), "mkdir $name failed") }

    @Test
    fun `finds only immediate sub-directories that are primary checkouts`(@TempDir root: File) {
        // A primary checkout: `.git` is a directory.
        val repoA = dir(root, "repoA").also { File(it, ".git").mkdir() }
        // A plain, non-git project directory — must be ignored.
        dir(root, "plain-folder").also { File(it, "README.md").writeText("no git here\n") }
        // A loose file at the root — must be ignored.
        File(root, "notes.txt").writeText("hi\n")

        val repos = RepoScanner.findRepos(root).map { it.name }

        assertEquals(listOf("repoA"), repos, "only primary-checkout dirs, sorted by name")
        assertTrue(repoA.exists())
    }

    @Test
    fun `excludes a linked worktree sitting next to its primary checkout`(@TempDir root: File) {
        // A linked worktree living under the root: `.git` is a FILE pointing at the
        // shared gitdir — exactly the layout `gwm add`'s default sibling path produces.
        // It must NOT surface as its own top-level repo: `git worktree list` on the
        // primary checkout already reports it, so counting the file form here would
        // double the aggregation (the bug an earlier version of this scanner had).
        val repoB = dir(root, "repoB").also { File(it, ".git").writeText("gitdir: /somewhere/.git/worktrees/repoB\n") }

        val repos = RepoScanner.findRepos(root).map { it.name }

        assertTrue(repos.isEmpty(), "a .git FILE marks a linked worktree, not a standalone repo; got $repos")
        assertTrue(repoB.exists())
    }

    @Test
    fun `does not recurse below one level`(@TempDir root: File) {
        // A nested git repo two levels deep must NOT surface: this is a flat portfolio.
        val outer = dir(root, "outer") // no .git of its own
        val nested = dir(outer, "nested-repo").also { File(it, ".git").mkdir() }

        val repos = RepoScanner.findRepos(root).map { it.name }

        assertTrue(repos.isEmpty(), "outer has no .git and we don't descend into it; got $repos")
        assertTrue(nested.exists())
    }

    @Test
    fun `missing or non-directory root yields empty list, not a crash`(@TempDir root: File) {
        val missing = File(root, "does-not-exist")
        assertEquals(emptyList(), RepoScanner.findRepos(missing))

        val asFile = File(root, "a-file").apply { writeText("x") }
        assertEquals(emptyList(), RepoScanner.findRepos(asFile))
    }

    @Test
    fun `resolveRoot prefers explicit override, then env, then default`() {
        val explicit = RepoScanner.resolveRoot(override = "/tmp/explicit-root", env = { "/tmp/env-root" })
        assertEquals(File("/tmp/explicit-root").absoluteFile, explicit)

        val fromEnv = RepoScanner.resolveRoot(override = null, env = { key -> if (key == "GWM_ROOT") "/tmp/env-root" else null })
        assertEquals(File("/tmp/env-root").absoluteFile, fromEnv)

        val default = RepoScanner.resolveRoot(override = null, env = { null })
        assertEquals(RepoScanner.defaultRoot(), default)
    }

    @Test
    fun `resolveRoot ignores blank override and blank env`() {
        // A stray GWM_ROOT="" must not point the scan at the filesystem root.
        val default = RepoScanner.resolveRoot(override = "   ", env = { "" })
        assertEquals(RepoScanner.defaultRoot(), default)
    }
}
