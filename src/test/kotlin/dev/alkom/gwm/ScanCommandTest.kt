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
import kotlin.test.assertTrue

/**
 * Command-level tests via Clikt's `test()` harness (Этап 7, plan §6, cases 36-41). The context
 * terminal (Р6) is what makes the output capturable here; case 36 doubles as the Р6 sanity check
 * that output IS intercepted. Cases 36-40 need no git — an empty @TempDir root is enough (scan
 * reports "no repos found" but the ROOT resolution / conflict / not-found paths still execute).
 */
class ScanCommandTest {

    private fun app() = Gwm().subcommands(ScanCommand())

    private fun gitAvailable(): Boolean =
        runCatching { ProcessBuilder("git", "--version").start().waitFor() == 0 }.getOrDefault(false)

    @Test // 36 — also the Р6 output-capture sanity check
    fun `root --root reaches scan (bug 4 regression) and output is captured`(@TempDir tmp: File) {
        val r = app().test("--root=${tmp.path} scan", ansiLevel = AnsiLevel.NONE)
        assertEquals(0, r.statusCode, "expected success, got ${r.statusCode}: ${r.output}")
        assertTrue(tmp.path in r.output, "scan must use the root path, not the default: ${r.output}")
    }

    @Test // 37
    fun `scan --root form still works`(@TempDir tmp: File) {
        val r = app().test("scan --root=${tmp.path}", ansiLevel = AnsiLevel.NONE)
        assertEquals(0, r.statusCode, r.output)
        assertTrue(tmp.path in r.output, r.output)
    }

    @Test // 38
    fun `positional ROOT works, no unexpected extra argument (bug 5 regression)`(@TempDir tmp: File) {
        val r = app().test("scan ${tmp.path}", ansiLevel = AnsiLevel.NONE)
        assertEquals(0, r.statusCode, "must not be an arg error: ${r.output}")
        assertTrue(tmp.path in r.output, r.output)
        assertTrue("unexpected extra argument" !in r.output, r.output)
    }

    @Test // 39
    fun `conflicting roots fail with a clear message`(@TempDir a: File, @TempDir b: File) {
        val r = app().test("--root=${a.path} scan --root=${b.path}", ansiLevel = AnsiLevel.NONE)
        assertTrue(r.statusCode != 0, "conflict must be non-zero: ${r.output}")
        assertTrue("указан несколько раз" in r.output, "conflict message expected: ${r.output}")
    }

    @Test // 40
    fun `missing root directory fails`() {
        val r = app().test("scan /definitely/not/here", ansiLevel = AnsiLevel.NONE)
        assertTrue(r.statusCode != 0, "missing root must be non-zero: ${r.output}")
        assertTrue("не найден" in r.output || "не директория" in r.output, r.output)
    }

    @Test // 41
    fun `real temp portfolio at width 80 - every line at most 80`(@TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        // Build a small real portfolio so the table actually renders rows.
        repeat(3) { i ->
            val dir = File(root, "repo-$i").apply { mkdirs() }
            GitCommand.run(dir, "init", "-b", "main")
            GitCommand.run(dir, "config", "user.email", "t@e.com")
            GitCommand.run(dir, "config", "user.name", "T")
            File(dir, "README.md").writeText("hi\n")
            GitCommand.run(dir, "add", "README.md")
            GitCommand.run(dir, "commit", "-m", "init")
            WorktreeService(dir).add(File(root, "repo-$i-feature"), newBranch = "feature/x", baseRef = "main")
        }
        val r = app().test("scan ${root.path}", ansiLevel = AnsiLevel.NONE, width = 80)
        assertEquals(0, r.statusCode, r.output)
        r.output.lines().forEach { line ->
            assertTrue(line.length <= 80, "line ${line.length}: >$line<")
        }
    }
}
