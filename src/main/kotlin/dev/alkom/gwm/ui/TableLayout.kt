package dev.alkom.gwm.ui

/** Logical columns of the overview table, in a fixed vocabulary the layout planner reasons over. */
enum class OverviewColumn { REPO, BRANCH, STATUS, ORPHAN, PATH }

/**
 * The chosen columns (in render order) with an assigned CONTENT width each. An empty list means
 * the table cannot fit even the minimal set → the caller falls back to the compact list (Р5).
 */
data class LayoutPlan(
    val columns: List<Pair<OverviewColumn, Int>>,
) {
    val compact: Boolean get() = columns.isEmpty()
}

/**
 * Pure width budgeting for the overview table (Р2). Mordant's `ColumnWidth.Expand()` gives a
 * column the *remainder*, and at 80 columns the remainder can be zero → the Путь column collapses.
 * There is no "Expand with a minimum". So we compute widths ourselves, pre-truncate cells, and hand
 * Mordant `Auto` columns it has nothing left to shrink.
 *
 * No Mordant, no I/O — a plain function of (terminal width, wanted columns, natural widths), fully
 * unit-testable. The invariant `sum(assigned) + chrome(n) <= width` is asserted by a sweep test.
 */
object TableLayout {
    const val MIN_BRANCH = 12
    const val MIN_PATH = 20
    const val PREFERRED_PATH = 30

    /**
     * Mordant table chrome: `n+1` vertical borders plus 2 spaces of padding per column
     * (`3 * n + 1`). Content widths must leave room for this.
     */
    fun chrome(columnCount: Int): Int = 3 * columnCount + 1

    /**
     * @param width   terminal width
     * @param wanted  desired columns in render order
     * @param natural natural width per column = max(header width, max cell width)
     */
    fun plan(width: Int, wanted: List<OverviewColumn>, natural: Map<OverviewColumn, Int>): LayoutPlan {
        // Degradation ladder: full → drop REPO → drop REPO+ORPHAN → compact.
        val ladder = listOf(
            wanted,
            wanted - OverviewColumn.REPO,
            wanted - OverviewColumn.REPO - OverviewColumn.ORPHAN,
        ).distinct()

        // Two sweeps of the ladder. First sweep: a rung only "fits" if PATH reaches its PREFERRED
        // width — so we drop REPO/ORPHAN in favour of a readable path rather than keeping them and
        // squeezing PATH to its bare minimum (the plan's §2 intent: "drop REPO — the biggest win").
        // Second sweep: accept PATH down to MIN_PATH, the last resort before the compact list.
        for (requirePreferred in listOf(true, false)) {
            for (cols in ladder) {
                assign(width, cols, natural, requirePreferred)?.let { return LayoutPlan(it) }
            }
        }
        return LayoutPlan(emptyList())
    }

    /**
     * Try to fit [cols] into [width]. Returns per-column assigned widths on success, null when even
     * PATH cannot reach its floor (caller drops to the next ladder rung).
     *
     * PATH is the elastic column: it takes what's left, capped at [PREFERRED_PATH] and its natural
     * width. When space is short we borrow from BRANCH (down to [MIN_BRANCH]); REPO, STATUS and
     * ORPHAN never shrink (too narrow already — REPO gets dropped whole instead, see below). If
     * PATH still can't reach `min(natural, MIN_PATH)`, this rung fails.
     */
    private fun assign(
        width: Int,
        cols: List<OverviewColumn>,
        natural: Map<OverviewColumn, Int>,
        requirePreferred: Boolean,
    ): List<Pair<OverviewColumn, Int>>? {
        if (OverviewColumn.PATH !in cols) return null
        val avail = width - chrome(cols.size)
        if (avail <= 0) return null

        // Start every column at its natural width.
        val w = cols.associateWith { (natural[it] ?: 0) }.toMutableMap()

        fun nonPathSum() = cols.filter { it != OverviewColumn.PATH }.sumOf { w.getValue(it) }

        var rest = avail - nonPathSum()
        val naturalPath = natural[OverviewColumn.PATH] ?: 0
        val target = minOf(naturalPath, PREFERRED_PATH)

        if (rest < target) {
            // Borrow from BRANCH only, never below MIN_BRANCH. We deliberately do NOT squeeze REPO:
            // a 10-char REPO cell is unreadable, and when the path needs that space the right move
            // is to DROP the whole REPO column (next ladder rung) — at a relative path the repo name
            // is already the first path segment, so nothing is lost. (Plan §2 Р2 lists REPO as a
            // borrow source, but honoring test cases 12/24/26 and the stated intent "drop REPO —
            // the biggest win" means dropping beats squeezing. Deviation recorded in the report.)
            if (OverviewColumn.BRANCH in w) {
                val shrinkable = (w.getValue(OverviewColumn.BRANCH) - MIN_BRANCH).coerceAtLeast(0)
                val take = minOf(shrinkable, target - rest)
                w[OverviewColumn.BRANCH] = w.getValue(OverviewColumn.BRANCH) - take
                rest += take
            }
        }

        // On the preferred sweep this rung only "fits" if PATH reaches its target width; on the
        // relaxed sweep MIN_PATH is enough. `min(natural, ...)` so a naturally-short path never
        // forces a rung to fail (test 17).
        val need = if (requirePreferred) minOf(naturalPath, target) else minOf(naturalPath, MIN_PATH)
        if (rest < need) return null

        w[OverviewColumn.PATH] = minOf(naturalPath, rest)
        return cols.map { it to w.getValue(it) }
    }
}
