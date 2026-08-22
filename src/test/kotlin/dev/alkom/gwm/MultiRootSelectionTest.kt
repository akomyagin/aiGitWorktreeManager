package dev.alkom.gwm

import dev.alkom.gwm.scan.MultiRootSelection
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for resolving `config.roots` into the set of existing scan roots (Этап 9, plan §6).
 * Pure logic — the only FS touch is `isDirectory`, so we point at real [TempDir]s (existing) and
 * fabricated paths (missing); no git needed.
 */
class MultiRootSelectionTest {

    private fun resolve(roots: List<String>, home: String = System.getProperty("user.home")) =
        MultiRootSelection.resolveScanRoots(roots, home)

    @Test
    fun `two existing roots both kept in order, no missing`(@TempDir a: File, @TempDir b: File) {
        val sr = resolve(listOf(a.path, b.path))
        assertEquals(listOf(a.absoluteFile.normalize(), b.absoluteFile.normalize()), sr.roots)
        assertTrue(sr.missing.isEmpty())
    }

    @Test
    fun `duplicate path collapses to one`(@TempDir a: File) {
        val sr = resolve(listOf(a.path, a.path))
        assertEquals(1, sr.roots.size, "the same path listed twice must dedup: ${sr.roots}")
        assertTrue(sr.missing.isEmpty())
    }

    @Test
    fun `two spellings of the same dir collapse (trailing slash)`(@TempDir a: File) {
        val sr = resolve(listOf(a.path, a.path + "/"))
        assertEquals(1, sr.roots.size, "'/x' and '/x/' must normalize to one: ${sr.roots}")
    }

    @Test
    fun `tilde and absolute HOME spelling collapse to one`(@TempDir home: File) {
        // A real sub-directory of the fake $HOME, addressed as ~/sub and as the absolute path.
        val sub = File(home, "sub").apply { assertTrue(mkdirs()) }
        val sr = resolve(listOf("~/sub", sub.path), home = home.path)
        assertEquals(1, sr.roots.size, "~/sub and \$HOME/sub must dedup: ${sr.roots}")
        assertEquals(sub.absoluteFile.normalize(), sr.roots.single())
    }

    @Test
    fun `non-existing path goes to missing, not roots`(@TempDir tmp: File) {
        val ghost = File(tmp, "nope").path
        val sr = resolve(listOf(ghost))
        assertTrue(sr.roots.isEmpty(), "a missing path must not be a scan root: ${sr.roots}")
        assertEquals(listOf(ghost), sr.missing)
    }

    @Test
    fun `mix of existing, missing and duplicate`(@TempDir a: File, @TempDir tmp: File) {
        val ghost = File(tmp, "gone").path
        val sr = resolve(listOf(a.path, ghost, a.path))
        assertEquals(listOf(a.absoluteFile.normalize()), sr.roots)
        assertEquals(listOf(ghost), sr.missing)
    }

    @Test
    fun `blank and whitespace entries are ignored entirely`(@TempDir a: File) {
        val sr = resolve(listOf("", "   ", a.path))
        assertEquals(listOf(a.absoluteFile.normalize()), sr.roots)
        assertTrue(sr.missing.isEmpty(), "blank entries are neither roots nor missing: ${sr.missing}")
    }

    @Test // regression: a missing path repeated in config.roots must warn about it only once
    fun `duplicate missing path collapses to one entry in missing`(@TempDir tmp: File) {
        val ghost = File(tmp, "nope").path
        val sr = resolve(listOf(ghost, ghost))
        assertTrue(sr.roots.isEmpty())
        assertEquals(listOf(ghost), sr.missing, "duplicate missing entries must dedup: ${sr.missing}")
    }

    @Test
    fun `empty config yields empty roots and empty missing`() {
        val sr = resolve(emptyList())
        assertTrue(sr.roots.isEmpty())
        assertTrue(sr.missing.isEmpty())
    }

    @Test // regression: config.roots of only blank/whitespace entries must read as "no config",
    // same shape as an empty roots array — neither a scan root nor a missing/warn-worthy entry.
    fun `config roots of only blank entries yields empty roots and empty missing`() {
        val sr = resolve(listOf("", "   ", "\t"))
        assertTrue(sr.roots.isEmpty())
        assertTrue(sr.missing.isEmpty(), "blank-only entries must not surface as missing: ${sr.missing}")
    }

    @Test
    fun `tilde expansion resolves against the given home`(@TempDir home: File) {
        val sub = File(home, "projects").apply { assertTrue(mkdirs()) }
        val sr = resolve(listOf("~/projects"), home = home.path)
        assertEquals(listOf(sub.absoluteFile.normalize()), sr.roots)
    }

    // ── resolveRootsToScan: the shared override-vs-multi-root fork (fix/print-path-multi-root) ──
    // Pure, no git; $GWM_ROOT is injected via `env` so the process environment is never touched.

    /** An `env` lambda that reports NO $GWM_ROOT — the default for most fork tests. */
    private val noEnv: (String) -> String? = { null }

    @Test
    fun `resolveRootsToScan - CLI override to an existing dir is single-root`(@TempDir root: File) {
        val rr = MultiRootSelection.resolveRootsToScan(root.path, configRoots = emptyList(), env = noEnv)
        assertTrue(rr.singleRootOverride)
        assertEquals(listOf(root.absoluteFile), rr.roots)
        assertTrue(rr.missing.isEmpty())
        assertTrue(!rr.fellBackToDefaultFromMissing)
    }

    @Test
    fun `resolveRootsToScan - GWM_ROOT counts as an override even when chosen is null`(@TempDir root: File) {
        val env: (String) -> String? = { if (it == "GWM_ROOT") root.path else null }
        val rr = MultiRootSelection.resolveRootsToScan(chosen = null, configRoots = listOf("/ignored"), env = env)
        assertTrue(rr.singleRootOverride, "GWM_ROOT must trigger the override branch")
        assertEquals(listOf(root.absoluteFile), rr.roots)
        assertTrue(rr.missing.isEmpty(), "config.roots must be ignored under an override: ${rr.missing}")
    }

    @Test
    fun `resolveRootsToScan - override to a missing dir yields empty roots (caller decides)`(@TempDir tmp: File) {
        val ghost = File(tmp, "nope").path
        val rr = MultiRootSelection.resolveRootsToScan(ghost, configRoots = emptyList(), env = noEnv)
        assertTrue(rr.singleRootOverride)
        assertTrue(rr.roots.isEmpty(), "a missing override root must be empty, not a throw: ${rr.roots}")
    }

    @Test
    fun `resolveRootsToScan - multi-root aggregates every existing config root`(@TempDir a: File, @TempDir b: File) {
        val rr = MultiRootSelection.resolveRootsToScan(chosen = null, configRoots = listOf(a.path, b.path), env = noEnv)
        assertTrue(!rr.singleRootOverride)
        assertEquals(listOf(a.absoluteFile.normalize(), b.absoluteFile.normalize()), rr.roots)
        assertTrue(rr.missing.isEmpty())
        assertTrue(!rr.fellBackToDefaultFromMissing)
    }

    @Test
    fun `resolveRootsToScan - partial multi-root keeps existing, reports missing`(@TempDir a: File, @TempDir tmp: File) {
        val ghost = File(tmp, "gone").path
        val rr = MultiRootSelection.resolveRootsToScan(chosen = null, configRoots = listOf(a.path, ghost), env = noEnv)
        assertEquals(listOf(a.absoluteFile.normalize()), rr.roots)
        assertEquals(listOf(ghost), rr.missing)
        assertTrue(!rr.fellBackToDefaultFromMissing, "some root existed — no default fallback")
    }

    @Test // all config roots missing → default fallback + fellBack flag. Pins user.home to a @TempDir
    // with Projects/ai-projects created, else it only passes where the dev's own default root exists.
    fun `resolveRootsToScan - all config roots missing falls back to default and flags it`(
        @TempDir tmp: File,
        @TempDir fakeHome: File,
    ) {
        val defaultRoot = File(fakeHome, "Projects/ai-projects").apply { assertTrue(mkdirs()) }
        val g1 = File(tmp, "gone1").path
        val g2 = File(tmp, "gone2").path
        val originalHome = System.getProperty("user.home")
        System.setProperty("user.home", fakeHome.path)
        try {
            val rr = MultiRootSelection.resolveRootsToScan(chosen = null, configRoots = listOf(g1, g2), env = noEnv, home = fakeHome.path)
            assertEquals(listOf(defaultRoot.absoluteFile.normalize()), rr.roots)
            assertTrue(rr.fellBackToDefaultFromMissing, "non-blank-but-missing config must flag the fallback")
            assertEquals(listOf(g1, g2), rr.missing)
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }

    @Test // empty config → silent default (no fellBack flag).
    fun `resolveRootsToScan - empty config is a silent default`(@TempDir fakeHome: File) {
        val defaultRoot = File(fakeHome, "Projects/ai-projects").apply { assertTrue(mkdirs()) }
        val originalHome = System.getProperty("user.home")
        System.setProperty("user.home", fakeHome.path)
        try {
            val rr = MultiRootSelection.resolveRootsToScan(chosen = null, configRoots = emptyList(), env = noEnv, home = fakeHome.path)
            assertEquals(listOf(defaultRoot.absoluteFile.normalize()), rr.roots)
            assertTrue(!rr.fellBackToDefaultFromMissing, "an empty config is a silent default, no warning")
            assertTrue(rr.missing.isEmpty())
        } finally {
            System.setProperty("user.home", originalHome)
        }
    }
}
