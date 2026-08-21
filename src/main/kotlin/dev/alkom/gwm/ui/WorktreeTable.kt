package dev.alkom.gwm.ui

import com.github.ajalt.mordant.rendering.OverflowWrap
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyle
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.Borders
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.widgets.Text
import dev.alkom.gwm.config.ColorScheme
import dev.alkom.gwm.config.Colors
import dev.alkom.gwm.git.Worktree
import dev.alkom.gwm.scan.AggregatedWorktree
import java.io.File

/**
 * The three resolved status styles used by [WorktreeTable]. Its [DEFAULT] is the historic
 * hard-coded Этап 7 scheme (`brightGreen`/`brightYellow`/`gray`), so a render call that passes
 * no scheme — every existing test and code path — produces byte-identical output. A config
 * [ColorScheme] is folded in via [from], each role falling back to its default when the name is
 * absent or unknown (plan §Р6).
 */
data class TableColors(val clean: TextStyle, val dirty: TextStyle, val muted: TextStyle) {
    companion object {
        val DEFAULT = TableColors(clean = brightGreen, dirty = brightYellow, muted = gray)

        fun from(scheme: ColorScheme): TableColors = TableColors(
            clean = Colors.resolve(scheme.clean, DEFAULT.clean),
            dirty = Colors.resolve(scheme.dirty, DEFAULT.dirty),
            muted = Colors.resolve(scheme.muted, DEFAULT.muted),
        )
    }
}

/**
 * Shared Mordant rendering for the worktree overview (Этап 7 rewrite).
 *
 * Design (plan §2):
 *  - **Р1** paths are shown relative to a base (portfolio root for `scan`, repo's parent for
 *    `list`), truncated preserving the TAIL — the distinguishing segment is at the end.
 *  - **Р2** we budget column widths ourselves ([TableLayout]) and pre-truncate cells, then hand
 *    Mordant `Auto` columns it can't shrink further. `Expand()` is gone: it collapses to zero when
 *    there is no remainder (the 80-column bug).
 *  - **Р3** compact table: only the header and outer frame keep horizontal rules
 *    (`body { cellBorders = Borders.LEFT_RIGHT }`), so 27 worktrees render in ~31 lines, not 58.
 *  - **Р5** on a terminal too narrow for even the minimal set, fall back to a frameless list.
 *
 * Kept UI-only: takes already-computed [Worktree]s and produces widgets. Widths are measured on
 * PLAIN strings before any color is applied (color would corrupt `String.length`).
 */
object WorktreeTable {

    /** Colored status cell for a worktree's dirty flag. Kept unchanged (plan §4). */
    fun statusCell(wt: Worktree, colors: TableColors = TableColors.DEFAULT): String =
        colorStatus(wt.dirty, statusPlain(wt.dirty), colors)

    /** One-line, safe-to-delete hint for orphaned worktrees, or null when active. Unchanged. */
    fun orphanHint(wt: Worktree): String? =
        if (wt.orphan.isOrphaned) {
            "безопасно удалить (${wt.orphan.reasons.joinToString(", ")})"
        } else {
            null
        }

    /**
     * Single-repo overview. Base = repo's parent dir; no REPO column at any width.
     * [colors] defaults to the historic scheme so callers/tests that omit it are unaffected.
     */
    fun render(
        worktrees: List<Worktree>,
        base: File?,
        width: Int,
        colors: TableColors = TableColors.DEFAULT,
    ): Widget = renderRows(rows(worktrees, base), width, colors)

    /**
     * Multi-repo aggregated overview. Base = portfolio root; REPO column droppable by width.
     * [colors] defaults to the historic scheme so callers/tests that omit it are unaffected.
     */
    fun renderAggregated(
        worktrees: List<AggregatedWorktree>,
        root: File?,
        width: Int,
        colors: TableColors = TableColors.DEFAULT,
    ): Widget = renderRows(rowsAggregated(worktrees, root), width, colors)

    // --- plain-row projection -------------------------------------------------------------

    internal fun rows(worktrees: List<Worktree>, base: File?): List<OverviewRow> =
        worktrees.map { wt -> row(repo = null, wt = wt, base = base) }

    internal fun rowsAggregated(worktrees: List<AggregatedWorktree>, root: File?): List<OverviewRow> {
        // Grouping (plan §Р1): the repo name is shown ONLY on the first row of each consecutive
        // run of the same repo; on the 2nd+ rows its REPO cell collapses to "". The row ORDER is
        // untouched — this relies on ScanService (via flatMap over per-repo results) already
        // returning worktrees grouped by repo, so a "group" is simply a maximal run of equal repo
        // names; a caller that ever interleaves repos would silently re-print the same name in
        // more than one run. Purely visual; measurement is unaffected because the group's first
        // row still carries the full name (see plainCell REPO / naturalWidths).
        var prevRepo: String? = null
        return worktrees.map { agg ->
            val isStart = agg.repo != prevRepo
            prevRepo = agg.repo
            row(repo = agg.repo, wt = agg.worktree, base = root, repoIsGroupStart = isStart)
        }
    }

    private fun row(
        repo: String?,
        wt: Worktree,
        base: File?,
        repoIsGroupStart: Boolean = true,
    ): OverviewRow {
        val shortened = PathDisplay.shorten(wt.path, base)
        // Relative iff it did not fall back to "~/..." or an absolute path.
        val relative = !shortened.startsWith("~") && !shortened.startsWith("/")
        return OverviewRow(
            repo = repo,
            branch = wt.label,
            isMain = wt.isMain,
            dirty = wt.dirty,
            orphanReasons = if (wt.orphan.isOrphaned) wt.orphan.reasons else emptyList(),
            path = shortened,
            pathIsRelative = relative,
            repoIsGroupStart = repoIsGroupStart,
        )
    }

    // --- layout + render ------------------------------------------------------------------

    private const val H_REPO = "Репозиторий"
    private const val H_BRANCH = "Ветка"
    private const val H_STATUS = "Статус"
    private const val H_ORPHAN = "Orphaned"
    private const val H_PATH = "Путь"

    private fun header(col: OverviewColumn): String = when (col) {
        OverviewColumn.REPO -> H_REPO
        OverviewColumn.BRANCH -> H_BRANCH
        OverviewColumn.STATUS -> H_STATUS
        OverviewColumn.ORPHAN -> H_ORPHAN
        OverviewColumn.PATH -> H_PATH
    }

    /** Natural width per column = max(header, max plain cell) over the given [cols]. */
    internal fun naturalWidths(rows: List<OverviewRow>, cols: List<OverviewColumn>): Map<OverviewColumn, Int> =
        cols.associateWith { col ->
            val cells = rows.map { plainCell(it, col, cols) }
            (cells + header(col)).maxOf { it.length }
        }

    /**
     * Plain (uncolored) content of a cell, used both for width measurement and, after truncation,
     * as the string that gets styled. [cols] is the ACTIVE column set so we know whether ORPHAN was
     * dropped (→ append `⚠` to the branch) and whether REPO was dropped (→ prefix `<repo>: ` to a
     * non-relative path so repo membership isn't lost).
     */
    private fun plainCell(r: OverviewRow, col: OverviewColumn, cols: List<OverviewColumn>): String = when (col) {
        // Grouping (plan §Р1): blank the repo name on the 2nd+ row of a group. The group's first
        // row still returns the full name, so naturalWidths' max is unaffected (empty cells never
        // widen a column).
        OverviewColumn.REPO -> if (r.repoIsGroupStart) r.repo ?: "" else ""
        OverviewColumn.BRANCH -> {
            val orphanDropped = OverviewColumn.ORPHAN !in cols
            if (orphanDropped && r.orphanReasons.isNotEmpty()) "${r.branch} ⚠" else r.branch
        }
        OverviewColumn.STATUS -> statusPlain(r.dirty)
        OverviewColumn.ORPHAN -> if (r.orphanReasons.isNotEmpty()) "⚠ ${r.orphanReasons.joinToString("/")}" else ""
        OverviewColumn.PATH -> {
            val repoDropped = OverviewColumn.REPO !in cols
            if (repoDropped && !r.pathIsRelative && r.repo != null) "${r.repo}: ${r.path}" else r.path
        }
    }

    private fun statusPlain(dirty: Boolean?): String = when (dirty) {
        true -> "● dirty"
        false -> "✓ clean"
        null -> "?"
    }

    /**
     * Core renderer. Chooses columns (ORPHAN only if any row is orphaned), plans widths, and either
     * renders the compact list (Р5) or a bordered table with pre-truncated, then styled, cells.
     */
    internal fun renderRows(
        rows: List<OverviewRow>,
        width: Int,
        colors: TableColors = TableColors.DEFAULT,
    ): Widget {
        if (rows.isEmpty()) return Text("")

        val hasRepo = rows.any { it.repo != null }
        val anyOrphan = rows.any { it.orphanReasons.isNotEmpty() }
        val wanted = buildList {
            if (hasRepo) add(OverviewColumn.REPO)
            add(OverviewColumn.BRANCH)
            add(OverviewColumn.STATUS)
            if (anyOrphan) add(OverviewColumn.ORPHAN)
            add(OverviewColumn.PATH)
        }

        val plan = TableLayout.plan(width, wanted, naturalWidths(rows, wanted))
        if (plan.compact) return renderCompact(rows, width, colors)

        val cols = plan.columns.map { it.first }
        val widthOf = plan.columns.toMap()

        return table {
            // Р3: keep the OUTER frame (tableBorders) but drop inter-row horizontal rules in the
            // body (cellBorders = LEFT_RIGHT). Empirically in Mordant 3.0.1, body cellBorders alone
            // ALSO strips the table's bottom edge, so tableBorders = ALL restores it. Recorded in
            // SKILL.md. Result: 27 worktrees render in ~31 lines, not 58.
            tableBorders = Borders.ALL
            body {
                cellBorders = Borders.LEFT_RIGHT
            }
            column(cols.indexOf(OverviewColumn.PATH)) {
                // Safety net if our budgeting is off by a char: a VISIBLE ellipsis, not silent drop.
                // Width stays ColumnWidth.Auto (the default) — we've already pre-truncated cells,
                // so Mordant has nothing left to shrink (Р2). Expand() is deliberately gone.
                whitespace = Whitespace.NORMAL
                overflowWrap = OverflowWrap.ELLIPSES
            }
            header {
                row(*cols.map { bold(header(it)) }.toTypedArray())
            }
            body {
                rows.forEach { r ->
                    val cells = cols.map { col -> styledCell(r, col, cols, widthOf.getValue(col), colors) }
                    row(*cells.toTypedArray())
                }
            }
        }
    }

    /** Pre-truncate to the assigned width, THEN color. Never measure a colored string. */
    private fun styledCell(
        r: OverviewRow,
        col: OverviewColumn,
        cols: List<OverviewColumn>,
        w: Int,
        colors: TableColors,
    ): String {
        val plain = plainCell(r, col, cols)
        return when (col) {
            OverviewColumn.REPO -> {
                // Collapsed group row → leave it a plain empty cell, don't bold it. Branch on the
                // authoritative flag, not plain.isEmpty() — a repo name is never blank in practice,
                // but deriving "is this collapsed" from string emptiness re-guesses a fact the row
                // already carries explicitly.
                if (!r.repoIsGroupStart) "" else bold(PathDisplay.truncateHead(plain, w))
            }
            OverviewColumn.BRANCH -> {
                // When ORPHAN was dropped, plain ends in " ⚠". Head-truncating the whole cell would
                // cut the glyph off the tail, so truncate only the branch NAME and re-append " ⚠".
                val orphanSuffix = OverviewColumn.ORPHAN !in cols && r.orphanReasons.isNotEmpty()
                val t = if (orphanSuffix) {
                    PathDisplay.truncateHead(r.branch, (w - 2).coerceAtLeast(1)) + " ⚠"
                } else {
                    PathDisplay.truncateHead(plain, w)
                }
                if (r.isMain) bold(t) else t
            }
            OverviewColumn.STATUS -> colorStatus(r.dirty, plain, colors)
            // ORPHAN is a "needs attention" signal → the dirty (warning) role; empty → muted.
            OverviewColumn.ORPHAN ->
                if (plain.isEmpty()) colors.muted("") else colors.dirty(PathDisplay.truncateHead(plain, w))
            OverviewColumn.PATH -> {
                // When REPO was dropped, `plain` starts with "<repo>: " (plainCell above). Tail-
                // truncating that whole string would cut the prefix off the HEAD first — exactly the
                // repo identity it exists to preserve. Reserve room for the prefix and truncate only
                // the path portion, mirroring how BRANCH reserves room for its " ⚠" suffix.
                val repoDropped = OverviewColumn.REPO !in cols
                val prefix = if (repoDropped && !r.pathIsRelative && r.repo != null) "${r.repo}: " else ""
                val avail = (w - prefix.length).coerceAtLeast(1)
                colors.muted(prefix + PathDisplay.truncateTail(r.path, avail))
            }
        }
    }

    private fun colorStatus(dirty: Boolean?, plain: String, colors: TableColors): String = when (dirty) {
        true -> colors.dirty(plain)
        false -> colors.clean(plain)
        null -> colors.muted(plain)
    }

    /**
     * Compact, frameless fallback for a very narrow terminal (Р5): two lines per worktree, no blank
     * separators. Line 1 = status glyph + path (tail-truncated to width-2). Line 2 = two spaces,
     * optional `⚠ reasons · `, then the branch (tail-truncated to width-2).
     */
    internal fun renderCompact(
        rows: List<OverviewRow>,
        width: Int,
        colors: TableColors = TableColors.DEFAULT,
    ): Widget {
        val cap = (width - 2).coerceAtLeast(1)
        val sb = StringBuilder()
        rows.forEachIndexed { i, r ->
            val glyph = when (r.dirty) {
                true -> "●"
                false -> "✓"
                null -> "?"
            }
            // Repo is never a column here (compact has none), so a non-relative path always gets a
            // "<repo>: " prefix if we have a repo name — reserve room for it first, same fix as the
            // bordered PATH cell above, so it isn't tail-truncated away.
            val prefix = if (!r.pathIsRelative && r.repo != null) "${r.repo}: " else ""
            val pathAvail = (cap - prefix.length).coerceAtLeast(1)
            val line1 = "$glyph $prefix${PathDisplay.truncateTail(r.path, pathAvail)}"
            val orphan = if (r.orphanReasons.isNotEmpty()) "⚠ ${r.orphanReasons.joinToString("/")} · " else ""
            val line2body = PathDisplay.truncateTail(orphan + r.branch, cap)
            if (i > 0) sb.append('\n')
            sb.append(colorStatus(r.dirty, line1, colors)).append('\n')
            sb.append("  ").append(line2body)
        }
        return Text(sb.toString(), whitespace = Whitespace.PRE)
    }
}

/**
 * A worktree overview row in PLAIN form: widths are measured on this, styling is applied at render.
 *
 * @param repo           repo name; null for the single-repo `list` view
 * @param branch         short branch/label
 * @param isMain         primary worktree → bold
 * @param dirty          working-tree cleanliness (null = not checked)
 * @param orphanReasons  concrete staleness signals (empty = active)
 * @param path             ALREADY shortened via [PathDisplay.shorten]
 * @param pathIsRelative   false → path is `~/...` or absolute; when REPO is dropped its cell is
 *                         prefixed `<repo>: ` so repo membership isn't lost
 * @param repoIsGroupStart first row of a same-repo run → shows the name; 2nd+ rows collapse the
 *                         REPO cell to "" (plan §Р1). Always true for single-repo `list` (no REPO
 *                         column there anyway). Only affects the REPO cell when that column is present.
 */
data class OverviewRow(
    val repo: String?,
    val branch: String,
    val isMain: Boolean,
    val dirty: Boolean?,
    val orphanReasons: List<String>,
    val path: String,
    val pathIsRelative: Boolean,
    val repoIsGroupStart: Boolean = true,
)
