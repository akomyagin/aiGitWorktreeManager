package dev.alkom.gwm

import dev.alkom.gwm.git.OrphanClassifier
import dev.alkom.gwm.git.OrphanStatus
import dev.alkom.gwm.git.Worktree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Table-driven unit tests for the pure [OrphanClassifier] — the trickiest part of
 * Этап 5. No git, no I/O: we hand it a [Worktree] plus already-gathered merged/upstream
 * facts and assert the combined [OrphanStatus]. Mirrors [WorktreeParserTest]'s style.
 */
class OrphanClassifierTest {

    private fun linked(
        branch: String? = "feature",
        prunable: Boolean = false,
        detached: Boolean = false,
        bare: Boolean = false,
        main: Boolean = false,
    ) = Worktree(
        path = "/repo-feature",
        head = "abc123",
        branch = branch,
        isBare = bare,
        isDetached = detached,
        isPrunable = prunable,
        isMain = main,
    )

    @Test
    fun `a plain active worktree is not orphaned`() {
        val status = OrphanClassifier.classify(linked(), merged = false, noUpstream = false)
        assertEquals(OrphanStatus.ACTIVE, status)
        assertFalse(status.isOrphaned)
        assertTrue(status.reasons.isEmpty())
    }

    @Test
    fun `each single signal independently flags the worktree`() {
        val merged = OrphanClassifier.classify(linked(), merged = true, noUpstream = false)
        assertTrue(merged.isOrphaned)
        assertEquals(listOf("merged"), merged.reasons)

        val noUpstream = OrphanClassifier.classify(linked(), merged = false, noUpstream = true)
        assertTrue(noUpstream.isOrphaned)
        assertEquals(listOf("no-upstream"), noUpstream.reasons)

        val prunable = OrphanClassifier.classify(linked(prunable = true), merged = false, noUpstream = false)
        assertTrue(prunable.isOrphaned)
        assertEquals(listOf("prunable"), prunable.reasons)
    }

    @Test
    fun `signals are independent and combine - not mutually exclusive`() {
        val all = OrphanClassifier.classify(linked(prunable = true), merged = true, noUpstream = true)
        assertTrue(all.merged && all.noUpstream && all.prunable)
        assertEquals(listOf("merged", "no-upstream", "prunable"), all.reasons)

        val two = OrphanClassifier.classify(linked(), merged = true, noUpstream = true)
        assertEquals(listOf("merged", "no-upstream"), two.reasons)
    }

    @Test
    fun `the main worktree is never flagged, whatever the signals`() {
        val status = OrphanClassifier.classify(
            linked(branch = "main", main = true),
            merged = true,
            noUpstream = true,
        )
        assertEquals(OrphanStatus.ACTIVE, status)
        assertFalse(status.isOrphaned)
    }

    @Test
    fun `bare and detached ignore branch signals but still honour prunable`() {
        // Branch-based signals are meaningless without a branch, so they're dropped...
        val bare = OrphanClassifier.classify(
            linked(branch = null, bare = true),
            merged = true,
            noUpstream = true,
        )
        assertFalse(bare.merged)
        assertFalse(bare.noUpstream)
        assertFalse(bare.isOrphaned)

        val detached = OrphanClassifier.classify(
            linked(branch = null, detached = true),
            merged = true,
            noUpstream = true,
        )
        assertFalse(detached.isOrphaned)

        // ...but a gone directory (prunable) is still surfaced on its own.
        val detachedPrunable = OrphanClassifier.classify(
            linked(branch = null, detached = true, prunable = true),
            merged = true,
            noUpstream = true,
        )
        assertEquals(listOf("prunable"), detachedPrunable.reasons)
    }
}
