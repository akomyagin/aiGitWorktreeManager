package dev.alkom.gwm

import dev.alkom.gwm.ui.PathDisplay
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the pure path-presentation helpers (Этап 7, plan §6, cases 1-10). No FS access
 * beyond `$HOME`; all inputs are absolute string paths.
 */
class PathDisplayTest {

    @Test // 1
    fun `path inside base becomes relative without leading dot-slash`() {
        val base = File("/home/u/Projects/portfolio")
        val out = PathDisplay.shorten("/home/u/Projects/portfolio/repo/wt", base, home = File("/home/u"))
        assertEquals("repo/wt", out)
    }

    @Test // 2
    fun `path equal to base becomes dot`() {
        val base = File("/home/u/Projects/portfolio")
        assertEquals(".", PathDisplay.shorten("/home/u/Projects/portfolio", base, home = File("/home/u")))
    }

    @Test // 3
    fun `base null and path inside home becomes tilde form`() {
        val out = PathDisplay.shorten("/home/u/x/y", base = null, home = File("/home/u"))
        assertEquals("~/x/y", out)
    }

    @Test // 4
    fun `path outside base and outside home stays absolute`() {
        val base = File("/home/u/Projects/portfolio")
        val out = PathDisplay.shorten("/tmp/elsewhere/wt", base, home = File("/home/u"))
        assertEquals("/tmp/elsewhere/wt", out)
    }

    @Test // 5
    fun `prefix must be on a segment boundary`() {
        // base=/a/b must NOT match /a/bc/d
        val out = PathDisplay.shorten("/a/bc/d", base = File("/a/b"), home = File("/home/u"))
        assertEquals("/a/bc/d", out)
        assertFalse(out.startsWith("c/"))
    }

    @Test // 6
    fun `trailing slash in base does not break comparison`() {
        val base = File("/home/u/portfolio/")
        val out = PathDisplay.shorten("/home/u/portfolio/repo/wt", base, home = File("/home/u"))
        assertEquals("repo/wt", out)
    }

    @Test // 7
    fun `truncateTail leaves short text unchanged`() {
        assertEquals("repo/wt", PathDisplay.truncateTail("repo/wt", 20))
    }

    @Test // 8
    fun `truncateTail keeps the last segment whole and starts with ellipsis`() {
        // Budget wide enough for the last segment ("handoff", 7) but not the whole path.
        val out = PathDisplay.truncateTail("home/alkom/projects/portfolio/repo/handoff", 20)
        assertTrue(out.startsWith("…"), "should start with ellipsis: $out")
        assertTrue(out.length <= 20, "length ${out.length} > 20: $out")
        assertTrue(out.endsWith("/handoff"), "last segment kept whole: $out")
        assertFalse(out.contains("…and"), "no partial leading segment after ellipsis: $out")
    }

    @Test // 9
    fun `truncateTail with maxWidth 1 is just ellipsis`() {
        assertEquals("…", PathDisplay.truncateTail("anything/long", 1))
    }

    @Test // 10
    fun `truncateHead keeps the head and ends with ellipsis`() {
        val out = PathDisplay.truncateHead("aiGitWorktreeManager", 12)
        assertEquals(12, out.length)
        assertTrue(out.endsWith("…"))
        assertTrue(out.startsWith("aiGit"))
    }
}
