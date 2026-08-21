package dev.alkom.gwm

import dev.alkom.gwm.git.AheadBehind
import dev.alkom.gwm.git.parseAheadBehind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [parseAheadBehind] (Этап 8, plan §6 cases 38-40). The side semantics are locked
 * here: with the range `HEAD...<upstream>`, git prints `<ahead>\t<behind>` (LEFT = ahead), which was
 * verified empirically against a real repo. If someone flips the range order or the field mapping,
 * case 38 fails — that is the whole point of pinning it.
 */
class AheadBehindParseTest {

    @Test // 38 — semantics: left = ahead, right = behind (catches an inversion)
    fun `left-right count maps left to ahead and right to behind`() {
        // Ground truth from the empirical probe: local ahead 3, behind 2 → git printed "3\t2".
        assertEquals(AheadBehind(ahead = 3, behind = 2), parseAheadBehind("3\t2"))
        // A clearly asymmetric case so a swap can't accidentally pass.
        assertEquals(AheadBehind(ahead = 7, behind = 1), parseAheadBehind("7\t1\n"))
    }

    @Test // 39 — up to date (upstream exists, in sync) is 0/0, NOT null
    fun `zero zero is up-to-date not null`() {
        assertEquals(AheadBehind(ahead = 0, behind = 0), parseAheadBehind("0\t0"))
    }

    @Test // 40 — empty / malformed → null
    fun `empty or malformed input yields null`() {
        assertNull(parseAheadBehind(""))
        assertNull(parseAheadBehind("   \n"))
        assertNull(parseAheadBehind("3")) // only one field
        assertNull(parseAheadBehind("a\tb")) // not integers
        assertNull(parseAheadBehind("1\t2\t3")) // three fields
    }

    @Test // regression: only tab is a valid separator — `--count` output is strictly tab-separated,
    // so a space-separated line (not real git output) must fail, not silently parse (found on review:
    // the old parser split on tab OR space, which could mis-parse an unexpected format instead of
    // falling through to null).
    fun `space separated input is rejected, not silently parsed`() {
        assertNull(parseAheadBehind("4 5"))
    }
}
