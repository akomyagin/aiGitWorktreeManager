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

    @Test // 35 — Этап 8: explicit --root overrides config roots[0]
    fun `explicit --root beats config roots`(@TempDir cliRoot: File, @TempDir cfgRoot: File, @TempDir cfgDir: File) {
        val cfg = File(cfgDir, "config.toml").apply { writeText("roots = [\"${cfgRoot.path}\"]\n") }
        val r = app().test("--config=${cfg.path} --root=${cliRoot.path} scan", ansiLevel = AnsiLevel.NONE)
        assertEquals(0, r.statusCode, r.output)
        assertTrue(cliRoot.path in r.output, "must use the CLI root, not the config root: ${r.output}")
        assertTrue(cfgRoot.path !in r.output, "config root must not win over --root: ${r.output}")
    }

    @Test // 35b — config roots[0] is used as a fallback when no CLI root / env is given
    fun `config roots is used as a fallback when no CLI root`(@TempDir cfgRoot: File, @TempDir cfgDir: File) {
        val cfg = File(cfgDir, "config.toml").apply { writeText("roots = [\"${cfgRoot.path}\"]\n") }
        val r = app().test("--config=${cfg.path} scan", ansiLevel = AnsiLevel.NONE)
        assertEquals(0, r.statusCode, r.output)
        assertTrue(cfgRoot.path in r.output, "scan must fall back to config root: ${r.output}")
    }

    @Test // 36 — broken config aborts non-zero with a clear message before scanning
    fun `broken config fails with a config-naming message`(@TempDir cfgDir: File) {
        val cfg = File(cfgDir, "config.toml").apply { writeText("[[bad\n") }
        val r = app().test("--config=${cfg.path} scan /tmp", ansiLevel = AnsiLevel.NONE)
        assertTrue(r.statusCode != 0, "broken config must be non-zero: ${r.output}")
        assertTrue("конфиг" in r.output.lowercase(), "message must mention the config: ${r.output}")
    }

    @Test // regression: a stale/typo'd config root must not fail SILENTLY into the hard default
    fun `all configured roots missing - warns and still falls through to the hard default`(
        @TempDir cfgDir: File,
        @TempDir realRoot: File,
    ) {
        val missing = File(cfgDir, "does-not-exist").path
        val cfg = File(cfgDir, "config.toml").apply { writeText("roots = [\"$missing\"]\n") }
        // No CLI root / $GWM_ROOT given, so the hard default (~/Projects/ai-projects, present on
        // this box) is what resolveRoot falls through to — we only assert the warning fires.
        val r = app().test("--config=${cfg.path} scan", ansiLevel = AnsiLevel.NONE)
        assertTrue(missing in r.output, "must name the ignored config root: ${r.output}")
        assertTrue("не найден" in r.output, "must warn, not silently fall through: ${r.output}")
    }

    @Test // regression: unknown color name in config warns instead of silently doing nothing
    fun `unknown color name in config warns on stderr, does not fail`(@TempDir cfgDir: File, @TempDir root: File) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        // tableColors() is only reached once at least one repo was found — an empty root returns
        // early before ever resolving colors, so a real minimal repo is needed here.
        val dir = File(root, "repo").apply { mkdirs() }
        GitCommand.run(dir, "init", "-b", "main")
        GitCommand.run(dir, "config", "user.email", "t@e.com")
        GitCommand.run(dir, "config", "user.name", "T")
        File(dir, "README.md").writeText("hi\n")
        GitCommand.run(dir, "add", "README.md")
        GitCommand.run(dir, "commit", "-m", "init")

        val cfg = File(cfgDir, "config.toml").apply { writeText("[colors]\nclean = \"chartreuse\"\n") }
        val r = app().test("--config=${cfg.path} scan ${root.path}", ansiLevel = AnsiLevel.NONE)
        assertEquals(0, r.statusCode, r.output)
        assertTrue("chartreuse" in r.output, "must name the unknown color: ${r.output}")
    }

    /** Build a minimal real git repo named [name] under [parent] with one commit on `main`. */
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

    @Test // 9 — multi-root from config, no CLI root: both roots aggregated into one output
    fun `multi-root from config aggregates all existing roots`(
        @TempDir root1: File,
        @TempDir root2: File,
        @TempDir cfgDir: File,
    ) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        makeRepo(root1, "alpha")
        makeRepo(root2, "zeta")
        val cfg = File(cfgDir, "config.toml").apply {
            writeText("roots = [\"${root1.path}\", \"${root2.path}\"]\n")
        }
        val r = app().test("--config=${cfg.path} scan", ansiLevel = AnsiLevel.NONE, width = 200)
        assertEquals(0, r.statusCode, r.output)
        assertTrue("корней" in r.output, "must signal multiplicity: ${r.output}")
        assertTrue(root1.path in r.output && root2.path in r.output, "both roots must be listed: ${r.output}")
    }

    @Test // 9 — CLI --root overrides multi-root config entirely
    fun `explicit --root overrides multi-root config`(
        @TempDir cfgRoot1: File,
        @TempDir cfgRoot2: File,
        @TempDir cliRoot: File,
        @TempDir cfgDir: File,
    ) {
        val cfg = File(cfgDir, "config.toml").apply {
            writeText("roots = [\"${cfgRoot1.path}\", \"${cfgRoot2.path}\"]\n")
        }
        val r = app().test("--config=${cfg.path} --root=${cliRoot.path} scan", ansiLevel = AnsiLevel.NONE)
        assertEquals(0, r.statusCode, r.output)
        assertTrue(cliRoot.path in r.output, "must use the CLI root: ${r.output}")
        assertTrue(cfgRoot1.path !in r.output && cfgRoot2.path !in r.output, "config roots must not appear: ${r.output}")
    }

    @Test // 9 — all config roots missing: warn naming both, fall through to default, exit 0
    fun `all multi-root config roots missing warns and falls back`(@TempDir cfgDir: File) {
        val g1 = File(cfgDir, "gone1").path
        val g2 = File(cfgDir, "gone2").path
        val cfg = File(cfgDir, "config.toml").apply { writeText("roots = [\"$g1\", \"$g2\"]\n") }
        val r = app().test("--config=${cfg.path} scan", ansiLevel = AnsiLevel.NONE)
        assertEquals(0, r.statusCode, "missing roots are a warning, not a failure: ${r.output}")
        assertTrue("не найден" in r.output, "must warn: ${r.output}")
        assertTrue(g1 in r.output && g2 in r.output, "both missing roots must be named: ${r.output}")
    }

    @Test // 9 — partial: one existing + one missing → warn about missing, still scan the existing
    fun `partial multi-root warns about the missing one but scans the existing`(
        @TempDir root1: File,
        @TempDir cfgDir: File,
    ) {
        assumeTrue(gitAvailable(), "git not available on PATH")
        makeRepo(root1, "alpha")
        val ghost = File(cfgDir, "gone").path
        val cfg = File(cfgDir, "config.toml").apply {
            writeText("roots = [\"${root1.path}\", \"$ghost\"]\n")
        }
        val r = app().test("--config=${cfg.path} scan", ansiLevel = AnsiLevel.NONE, width = 200)
        assertEquals(0, r.statusCode, r.output)
        assertTrue("пропущены" in r.output, "must warn about the skipped root: ${r.output}")
        assertTrue(ghost in r.output, "the skipped root must be named: ${r.output}")
        assertTrue(root1.path in r.output, "the existing root must still be scanned: ${r.output}")
    }

    @Test // regression: no config roots at all (and no CLI/env override) + missing default root
    // must fail non-zero, not silently print "не найдены" and exit 0 (bug 1 fix — Р8 exit-code
    // regression). `defaultRoot()` reads `user.home` directly with no injection point, so we
    // temporarily point `user.home` at a location with no "Projects/ai-projects" subdir.
    fun `no config and missing default root fails non-zero, does not silently succeed`(@TempDir fakeHome: File) {
        val originalHome = System.getProperty("user.home")
        System.setProperty("user.home", fakeHome.path)
        try {
            val r = app().test("scan", ansiLevel = AnsiLevel.NONE)
            assertTrue(r.statusCode != 0, "missing default root must be non-zero, not a silent no-op: ${r.output}")
            assertTrue(
                "не найден" in r.output || "не директория" in r.output,
                "must report the missing root, not just 'not found repos': ${r.output}",
            )
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }

    @Test // regression: config.roots present but all entries blank must behave as "no config" —
    // silent default fallback, no "ни один из корней... не найден" warning (bug 3 fix).
    fun `config roots of only blank entries is treated as empty config, no warning`(
        @TempDir cfgDir: File,
        @TempDir realDefaultHome: File,
    ) {
        val defaultRoot = File(realDefaultHome, "Projects/ai-projects").apply { assertTrue(mkdirs()) }
        val cfg = File(cfgDir, "config.toml").apply { writeText("roots = [\"\", \"   \"]\n") }
        val originalHome = System.getProperty("user.home")
        System.setProperty("user.home", realDefaultHome.path)
        try {
            val r = app().test("--config=${cfg.path} scan", ansiLevel = AnsiLevel.NONE)
            assertEquals(0, r.statusCode, r.output)
            assertTrue(
                "ни один из корней" !in r.output,
                "blank-only roots must read as no-config, not as all-missing: ${r.output}",
            )
            assertTrue(defaultRoot.path in r.output, "must fall through to the default root: ${r.output}")
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }

    @Test // 9 — single config root: output stays single-root (no "N корней"), backwards compatible
    fun `single config root stays single-root output`(@TempDir cfgRoot: File, @TempDir cfgDir: File) {
        val cfg = File(cfgDir, "config.toml").apply { writeText("roots = [\"${cfgRoot.path}\"]\n") }
        val r = app().test("--config=${cfg.path} scan", ansiLevel = AnsiLevel.NONE)
        assertEquals(0, r.statusCode, r.output)
        assertTrue(cfgRoot.path in r.output, "must scan the single config root: ${r.output}")
        assertTrue("корней" !in r.output, "single root must not use the multi-root header: ${r.output}")
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
