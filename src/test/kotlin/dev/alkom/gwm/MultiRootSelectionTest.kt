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
}
