package dev.alkom.gwm

import dev.alkom.gwm.scan.RootCandidate
import dev.alkom.gwm.scan.RootChoice
import dev.alkom.gwm.scan.RootSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for reconciling the several root sources into one choice (Этап 7, plan §6, cases
 * 29-35). Pure logic, no git.
 */
class RootSelectionTest {

    private fun choose(vararg c: RootCandidate) = RootSelection.choose(c.toList())

    @Test // 29
    fun `only the root --root yields One`() {
        val r = choose(
            RootCandidate("аргумент ROOT", null),
            RootCandidate("scan --root", null),
            RootCandidate("gwm --root", "/tmp/x"),
        )
        assertEquals(RootChoice.One("/tmp/x"), r)
    }

    @Test // 30
    fun `only scan --root yields One`() {
        val r = choose(
            RootCandidate("аргумент ROOT", null),
            RootCandidate("scan --root", "/tmp/x"),
            RootCandidate("gwm --root", null),
        )
        assertEquals(RootChoice.One("/tmp/x"), r)
    }

    @Test // 31
    fun `only the positional yields One`() {
        val r = choose(
            RootCandidate("аргумент ROOT", "/tmp/x"),
            RootCandidate("scan --root", null),
            RootCandidate("gwm --root", null),
        )
        assertEquals(RootChoice.One("/tmp/x"), r)
    }

    @Test // 32
    fun `same value, one with a trailing slash, is not a conflict`() {
        val r = choose(
            RootCandidate("аргумент ROOT", "/tmp/x"),
            RootCandidate("gwm --root", "/tmp/x/"),
        )
        assertTrue(r is RootChoice.One, "trailing slash must normalize away, got $r")
    }

    @Test // 33
    fun `root --root and scan --root differing is a conflict listing both`() {
        val r = choose(
            RootCandidate("scan --root", "/tmp/a"),
            RootCandidate("gwm --root", "/tmp/b"),
        )
        assertTrue(r is RootChoice.Conflict)
        val sources = r.candidates.map { it.source }
        assertTrue("scan --root" in sources && "gwm --root" in sources, "both sources listed: $sources")
    }

    @Test // 34
    fun `positional and --root differing is a conflict`() {
        val r = choose(
            RootCandidate("аргумент ROOT", "/tmp/a"),
            RootCandidate("gwm --root", "/tmp/b"),
        )
        assertTrue(r is RootChoice.Conflict)
    }

    @Test // 35
    fun `all blank yields One null`() {
        val r = choose(
            RootCandidate("аргумент ROOT", ""),
            RootCandidate("scan --root", "   "),
            RootCandidate("gwm --root", null),
        )
        assertEquals(RootChoice.One(null), r)
    }
}
