package dev.alkom.gwm

import dev.alkom.gwm.git.OrphanStatus
import dev.alkom.gwm.git.Worktree
import dev.alkom.gwm.scan.AggregatedWorktree
import dev.alkom.gwm.scan.BulkCleanPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the pure candidate-selection logic (Этап 10, plan §Тест-кейсы / Юнит).
 * No git, no TTY — just table-in/table-out over [BulkCleanPlan.from].
 */
class BulkCleanPlanTest {

    private fun wt(
        repo: String,
        branch: String,
        orphan: OrphanStatus = OrphanStatus.ACTIVE,
        dirty: Boolean? = false,
        isMain: Boolean = false,
    ): AggregatedWorktree =
        AggregatedWorktree(
            repo = repo,
            worktree = Worktree(
                path = "/portfolio/$repo/$branch",
                head = "abc",
                branch = branch,
                isMain = isMain,
                dirty = dirty,
                orphan = orphan,
            ),
        )

    private val noUpstream = OrphanStatus(noUpstream = true)
    private val merged = OrphanStatus(merged = true)

    @Test
    fun `empty input yields empty plan`() {
        val plan = BulkCleanPlan.from(emptyList())
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.total)
    }

    @Test
    fun `only orphaned worktrees become candidates`() {
        val plan = BulkCleanPlan.from(
            listOf(
                wt("a", "active", orphan = OrphanStatus.ACTIVE),
                wt("a", "stale", orphan = noUpstream),
                wt("b", "gone", orphan = merged),
            ),
        )
        assertEquals(2, plan.total)
        assertEquals(listOf("stale", "gone"), plan.candidates.map { it.label })
    }

    @Test
    fun `main worktree is never a candidate even if orphan-flagged`() {
        // Defense-in-depth (invariant §5): a main worktree carrying a non-empty orphan must be excluded.
        val plan = BulkCleanPlan.from(
            listOf(
                wt("a", "main", orphan = noUpstream, isMain = true),
                wt("a", "feature", orphan = noUpstream),
            ),
        )
        assertEquals(1, plan.total)
        assertEquals("feature", plan.candidates.single().label)
        assertFalse(plan.candidates.any { it.aggregated.worktree.isMain }, "main must never be a candidate")
    }

    @Test
    fun `dirty and clean split correctly`() {
        val plan = BulkCleanPlan.from(
            listOf(
                wt("a", "clean1", orphan = noUpstream, dirty = false),
                wt("a", "dirty1", orphan = noUpstream, dirty = true),
                wt("b", "dirty2", orphan = merged, dirty = true),
            ),
        )
        assertEquals(2, plan.dirtyCount)
        assertEquals(listOf("dirty1", "dirty2"), plan.dirtyCandidates.map { it.label })
        assertEquals(listOf("clean1"), plan.cleanCandidates.map { it.label })
    }

    @Test
    fun `candidate order is preserved as in input (repo grouping intact)`() {
        val plan = BulkCleanPlan.from(
            listOf(
                wt("repoA", "x", orphan = noUpstream),
                wt("repoA", "y", orphan = merged),
                wt("repoB", "z", orphan = noUpstream),
            ),
        )
        assertEquals(
            listOf("repoA" to "x", "repoA" to "y", "repoB" to "z"),
            plan.candidates.map { it.repo to it.label },
        )
    }

    @Test
    fun `null dirty is treated as not dirty`() {
        val plan = BulkCleanPlan.from(listOf(wt("a", "stale", orphan = noUpstream, dirty = null)))
        assertEquals(1, plan.total)
        assertFalse(plan.candidates.single().dirty)
        assertEquals(0, plan.dirtyCount)
    }

    @Test
    fun `reasons are carried through for display`() {
        val plan = BulkCleanPlan.from(
            listOf(wt("a", "stale", orphan = OrphanStatus(merged = true, noUpstream = true))),
        )
        assertEquals(listOf("merged", "no-upstream"), plan.candidates.single().reasons)
    }
}
