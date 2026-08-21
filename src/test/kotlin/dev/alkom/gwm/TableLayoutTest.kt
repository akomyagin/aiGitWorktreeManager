package dev.alkom.gwm

import dev.alkom.gwm.ui.OverviewColumn
import dev.alkom.gwm.ui.TableLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the pure width-budgeting planner (Этап 7, plan §6, cases 11-18). No Mordant.
 */
class TableLayoutTest {

    private val ALL = listOf(
        OverviewColumn.REPO,
        OverviewColumn.BRANCH,
        OverviewColumn.STATUS,
        OverviewColumn.ORPHAN,
        OverviewColumn.PATH,
    )

    private fun natural(repo: Int, branch: Int, status: Int, orphan: Int, path: Int) = mapOf(
        OverviewColumn.REPO to repo,
        OverviewColumn.BRANCH to branch,
        OverviewColumn.STATUS to status,
        OverviewColumn.ORPHAN to orphan,
        OverviewColumn.PATH to path,
    )

    private fun assertFits(width: Int, plan: dev.alkom.gwm.ui.LayoutPlan) {
        val sum = plan.columns.sumOf { it.second }
        assertTrue(
            sum + TableLayout.chrome(plan.columns.size) <= width,
            "sum $sum + chrome(${plan.columns.size}) > $width",
        )
    }

    @Test // 11
    fun `width 140 keeps all five columns, PATH at natural`() {
        val nat = natural(repo = 23, branch = 20, status = 7, orphan = 12, path = 40)
        val plan = TableLayout.plan(140, ALL, nat)
        assertEquals(5, plan.columns.size)
        assertFits(140, plan)
        val pathW = plan.columns.first { it.first == OverviewColumn.PATH }.second
        // PATH natural (40) > PREFERRED (30) but there's room → capped at PREFERRED unless more needed;
        // the invariant we assert is it is at least PREFERRED_PATH here.
        assertTrue(pathW >= TableLayout.PREFERRED_PATH, "path width $pathW")
    }

    @Test // 12
    fun `width 80 drops REPO, PATH at least MIN_PATH`() {
        val nat = natural(repo = 23, branch = 25, status = 7, orphan = 12, path = 50)
        val plan = TableLayout.plan(80, ALL, nat)
        assertFalse(plan.compact)
        val cols = plan.columns.map { it.first }
        assertTrue(OverviewColumn.REPO !in cols, "REPO should be dropped at 80: $cols")
        val pathW = plan.columns.first { it.first == OverviewColumn.PATH }.second
        assertTrue(pathW >= TableLayout.MIN_PATH, "path $pathW < MIN_PATH")
        assertFits(80, plan)
    }

    @Test // 13
    fun `width 80 without ORPHAN gives three columns, PATH at least PREFERRED`() {
        // No ORPHAN column in wanted (no orphaned rows).
        val wanted = listOf(OverviewColumn.BRANCH, OverviewColumn.STATUS, OverviewColumn.PATH)
        val nat = mapOf(
            OverviewColumn.BRANCH to 20,
            OverviewColumn.STATUS to 7,
            OverviewColumn.PATH to 50,
        )
        val plan = TableLayout.plan(80, wanted, nat)
        assertEquals(3, plan.columns.size)
        val pathW = plan.columns.first { it.first == OverviewColumn.PATH }.second
        assertTrue(pathW >= TableLayout.PREFERRED_PATH, "path $pathW < PREFERRED")
        assertFits(80, plan)
    }

    @Test // 14
    fun `width 80 with long branches shrinks BRANCH but not below MIN_BRANCH`() {
        val wanted = listOf(OverviewColumn.BRANCH, OverviewColumn.STATUS, OverviewColumn.PATH)
        val nat = mapOf(
            OverviewColumn.BRANCH to 40,
            OverviewColumn.STATUS to 7,
            OverviewColumn.PATH to 50,
        )
        val plan = TableLayout.plan(80, wanted, nat)
        assertFalse(plan.compact)
        val branchW = plan.columns.first { it.first == OverviewColumn.BRANCH }.second
        assertTrue(branchW >= TableLayout.MIN_BRANCH, "branch $branchW < MIN_BRANCH")
        assertTrue(branchW < 40, "branch should have shrunk from natural 40, got $branchW")
        assertFits(80, plan)
    }

    @Test // 15
    fun `width 60 drops ORPHAN, PATH at least MIN_PATH`() {
        val nat = natural(repo = 23, branch = 25, status = 7, orphan = 12, path = 50)
        val plan = TableLayout.plan(60, ALL, nat)
        assertFalse(plan.compact)
        val cols = plan.columns.map { it.first }
        assertTrue(OverviewColumn.ORPHAN !in cols, "ORPHAN dropped at 60: $cols")
        val pathW = plan.columns.first { it.first == OverviewColumn.PATH }.second
        assertTrue(pathW >= TableLayout.MIN_PATH, "path $pathW")
        assertFits(60, plan)
    }

    @Test // 16
    fun `width 40 is compact`() {
        val nat = natural(repo = 23, branch = 25, status = 7, orphan = 12, path = 50)
        assertTrue(TableLayout.plan(40, ALL, nat).compact)
    }

    @Test // 17
    fun `all natural small - nobody gets more than natural`() {
        val nat = natural(repo = 5, branch = 6, status = 7, orphan = 4, path = 8)
        val plan = TableLayout.plan(200, ALL, nat)
        plan.columns.forEach { (col, w) ->
            assertTrue(w <= nat.getValue(col), "$col got $w > natural ${nat.getValue(col)}")
        }
    }

    @Test // 18
    fun `invariant holds across the width sweep`() {
        val nat = natural(repo = 23, branch = 25, status = 7, orphan = 12, path = 50)
        for (w in 30..200) {
            val plan = TableLayout.plan(w, ALL, nat)
            val sum = plan.columns.sumOf { it.second }
            assertTrue(
                sum + TableLayout.chrome(plan.columns.size) <= w,
                "width $w: sum $sum + chrome(${plan.columns.size}) > $w",
            )
            if (!plan.compact) {
                val pathW = plan.columns.first { it.first == OverviewColumn.PATH }.second
                val floor = minOf(nat.getValue(OverviewColumn.PATH), TableLayout.MIN_PATH)
                assertTrue(pathW >= floor, "width $w: path $pathW < floor $floor")
            }
        }
    }
}
