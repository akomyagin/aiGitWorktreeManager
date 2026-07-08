package dev.alkom.gwm

import dev.alkom.gwm.git.OrphanStatus
import dev.alkom.gwm.git.Worktree
import dev.alkom.gwm.ui.InteractiveScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the pure formatting helpers of the interactive screen. The TTY
 * loop itself needs a real terminal and is exercised manually; the label/key logic
 * is pulled out precisely so it can be verified here without a terminal.
 */
class InteractiveScreenTest {

    private fun wt(
        path: String,
        branch: String?,
        isMain: Boolean = false,
        dirty: Boolean? = null,
    ) = Worktree(path = path, head = "abc", branch = branch, isMain = isMain, dirty = dirty)

    @Test
    fun `entryKey is the worktree path`() {
        assertEquals("/repo-x", InteractiveScreen.entryKey(wt("/repo-x", "x")))
    }

    @Test
    fun `rowLabel marks main and shows branch and path`() {
        val label = InteractiveScreen.rowLabel(wt("/repo", "main", isMain = true, dirty = false))
        assertTrue("main" in label)
        assertTrue("(main)" in label)
        assertTrue("/repo" in label)
        assertTrue(label.startsWith("✓"))
    }

    @Test
    fun `rowLabel uses dirty and unknown markers`() {
        assertTrue(InteractiveScreen.rowLabel(wt("/a", "b", dirty = true)).startsWith("●"))
        assertTrue(InteractiveScreen.rowLabel(wt("/a", "b", dirty = null)).startsWith("?"))
    }

    @Test
    fun `rowLabel appends the orphaned badge only when stale`() {
        val active = InteractiveScreen.rowLabel(wt("/a", "b", dirty = false))
        assertFalse("⚠" in active)

        val stale = wt("/a", "b", dirty = false)
            .copy(orphan = OrphanStatus(merged = true, noUpstream = true))
        val label = InteractiveScreen.rowLabel(stale)
        assertTrue("⚠" in label)
        assertTrue("merged/no-upstream" in label)
    }
}
