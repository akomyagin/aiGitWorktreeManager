package dev.alkom.gwm

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.testing.test
import com.github.ajalt.mordant.rendering.AnsiLevel
import dev.alkom.gwm.git.GitCommand
import dev.alkom.gwm.git.WorktreeService
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Command-level tests for the multi-root `--print-path` (fix/print-path-multi-root), driving the
 * FULL `Gwm().test("--config=... --print-path <fuzzy>")` path — resolution, stdout contract and
 * exit code together. These are the regression guard for the shell contract: on success stdout is
 * EXACTLY the path, and diagnostics never leak into stdout (they go to stderr).
 *
 * `CommandResult.output` in Clikt's harness is the intercepted TERMINAL (Mordant → stdout). Our
 * warnings use `System.err.println`, which bypasses that terminal — so a clean `.output` proves the
 * warning did not corrupt the path. `--print-path` also uses a raw `println` for the path itself
 * (not the Mordant terminal), so we additionally capture `System.out`/`System.err` around the call
 * to assert the exact bytes on each stream.
 */
class PrintPathCommandTest {

    private fun app() = Gwm().subcommands(ScanCommand())

    private fun gitAvailable(): Boolean =
        runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

    private fun makeRepoWithWorktree(parent: File, name: String, branch: String): File {
        val dir = File(parent, name).apply { mkdirs() }
        GitCommand.run(dir, "init", "-b", "main")
        GitCommand.run(dir, "config", "user.email", "t@e.com")
        GitCommand.run(dir, "config", "user.name", "T")
        File(dir, "README.md").writeText("hi\n")
        GitCommand.run(dir, "add", "README.md")
        GitCommand.run(dir, "commit", "-m", "init")
        WorktreeService(dir).add(File(parent, "$name-wt"), newBranch = branch, baseRef = "main")
        return dir
    }

    /** Run [block] capturing the process stdout and stderr (the path goes to a raw System.out). */
    private data class Streams(val out: String, val err: String)

    private fun capturing(block: () -> Unit): Streams {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        try {
            block()
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
        return Streams(out.toString(), err.toString())
    }

    @Test // core fix: with no CLI root, a fuzzy query resolves a worktree in the SECOND config root.
    fun `multi-root print-path resolves a worktree from the second root`(
        @TempDir root1: File,
        @TempDir root2: File,
        @TempDir cfgDir: File,
    ) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        makeRepoWithWorktree(root1, "alpha", "feature/a")
        makeRepoWithWorktree(root2, "beta", "feature/login")
        val cfg = File(cfgDir, "config.toml").apply {
            writeText("roots = [\"${root1.path}\", \"${root2.path}\"]\n")
        }
        val streams = capturing {
            val r = app().test("--config=${cfg.path} --print-path login", ansiLevel = AnsiLevel.NONE)
            assertEquals(0, r.statusCode, "expected success: ${r.output}")
        }
        val printed = streams.out.trim()
        assertTrue(printed.startsWith("/"), "must print an absolute path: >$printed<")
        assertTrue(File(root2, "beta-wt").canonicalPath == File(printed).canonicalPath, "must resolve the second root's worktree: >$printed<")
    }

    @Test // CLI --root overrides multi-root config: a query only in config must NOT resolve.
    fun `explicit --root overrides config for print-path`(
        @TempDir cfgRoot1: File,
        @TempDir cfgRoot2: File,
        @TempDir cliRoot: File,
        @TempDir cfgDir: File,
    ) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        makeRepoWithWorktree(cfgRoot1, "alpha", "feature/a")
        makeRepoWithWorktree(cfgRoot2, "beta", "feature/login")
        // cliRoot stays empty — so a query for the config-only branch must fail (empty stdout, non-zero).
        val cfg = File(cfgDir, "config.toml").apply {
            writeText("roots = [\"${cfgRoot1.path}\", \"${cfgRoot2.path}\"]\n")
        }
        val streams = capturing {
            val r = app().test("--config=${cfg.path} --root=${cliRoot.path} --print-path login", ansiLevel = AnsiLevel.NONE)
            assertTrue(r.statusCode != 0, "config roots must be ignored under --root: ${r.output}")
        }
        assertTrue(streams.out.isBlank(), "failed lookup must print NOTHING to stdout: >${streams.out}<")
    }

    @Test // backwards compatibility: single --root resolves exactly as before (shell-contract guard).
    fun `single --root print-path still resolves - backwards compatible`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        makeRepoWithWorktree(root, "alpha", "feature/login")
        val streams = capturing {
            val r = app().test("--root=${root.path} --print-path login", ansiLevel = AnsiLevel.NONE)
            assertEquals(0, r.statusCode, r.output)
        }
        assertEquals(File(root, "alpha-wt").canonicalPath, File(streams.out.trim()).canonicalPath)
    }

    @Test // THE contract test: a partial multi-root warns to STDERR, never stdout, on a successful lookup.
    fun `partial multi-root warns to stderr only, stdout stays exactly the path`(
        @TempDir root1: File,
        @TempDir cfgDir: File,
    ) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        makeRepoWithWorktree(root1, "alpha", "feature/login")
        val ghost = File(cfgDir, "gone").path
        val cfg = File(cfgDir, "config.toml").apply {
            writeText("roots = [\"${root1.path}\", \"$ghost\"]\n")
        }
        val streams = capturing {
            val r = app().test("--config=${cfg.path} --print-path login", ansiLevel = AnsiLevel.NONE)
            assertEquals(0, r.statusCode, r.output)
        }
        // stdout: exactly the path, one line, no warning text.
        val lines = streams.out.trim().lines()
        assertEquals(1, lines.size, "stdout must be exactly one line (the path): >${streams.out}<")
        assertTrue(lines.single().startsWith("/"), "stdout line must be an absolute path: >${lines.single()}<")
        assertTrue("пропущены" !in streams.out, "the skip warning must NOT leak into stdout: >${streams.out}<")
        assertTrue("⚠" !in streams.out, "no warning glyph in stdout: >${streams.out}<")
        // stderr: carries the skip warning naming the ghost root.
        assertTrue("пропущены" in streams.err, "the skip warning must be on stderr: >${streams.err}<")
        assertTrue(ghost in streams.err, "the skipped root must be named on stderr: >${streams.err}<")
    }
}
