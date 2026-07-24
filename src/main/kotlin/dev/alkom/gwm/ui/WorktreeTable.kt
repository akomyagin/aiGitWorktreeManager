package dev.alkom.gwm.ui

import com.github.ajalt.mordant.rendering.OverflowWrap
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.ColumnWidth
import com.github.ajalt.mordant.table.table
import dev.alkom.gwm.git.Worktree
import dev.alkom.gwm.scan.AggregatedWorktree

/**
 * Shared Mordant rendering for a repository's worktrees.
 *
 * Kept UI-only: takes already-computed [Worktree]s (dirty flags optional) and
 * produces widgets. No git, no I/O — so the domain layer stays independent of the
 * terminal (see docs/TECHNICAL_PLAN.md §3).
 */
object WorktreeTable {

    /** Colored status cell for a worktree's dirty flag. */
    fun statusCell(wt: Worktree): String = when (wt.dirty) {
        true -> brightYellow("● dirty")
        false -> brightGreen("✓ clean")
        null -> gray("?")
    }

    /**
     * Colored orphaned/stale badge for a worktree (Этап 5), or a blank cell when the
     * worktree looks active. Uses [brightYellow] — the same "attention, not error" hue
     * as the dirty marker — and lists the concrete signals so the human can judge
     * ("merged" reads very differently from "prunable"). This is a hint only: `gwm`
     * never deletes anything on its own.
     */
    fun orphanCell(wt: Worktree): String =
        if (wt.orphan.isOrphaned) {
            brightYellow("⚠ ${wt.orphan.reasons.joinToString("/")}")
        } else {
            gray("")
        }

    /**
     * One-line, safe-to-delete hint for orphaned worktrees, or null when active.
     * Purely advisory text — the user still has to trigger removal explicitly.
     */
    fun orphanHint(wt: Worktree): String? =
        if (wt.orphan.isOrphaned) {
            "безопасно удалить (${wt.orphan.reasons.joinToString(", ")})"
        } else {
            null
        }

    /**
     * Full table widget for a list of worktrees.
     *
     * On a narrow/undetectable terminal, Mordant must shrink columns to fit. Путь is the only
     * column the user actually needs to act on `cd`, so it gets [ColumnWidth.Expand] priority
     * (soaks up remaining space instead of shrinking in lockstep with the cosmetic columns) and
     * [OverflowWrap.ELLIPSES] so any truncation that does happen is visible, not silently dropped
     * characters.
     */
    fun render(worktrees: List<Worktree>): Widget = table {
        overflowWrap = OverflowWrap.ELLIPSES
        column(3) {
            width = ColumnWidth.Expand()
            // ELLIPSES only fires when whitespace.wrap is true; table cells default to
            // NOWRAP, which silently hard-truncates instead.
            whitespace = Whitespace.NORMAL
            overflowWrap = OverflowWrap.ELLIPSES
        }
        header { row("Ветка", "Статус", "Orphaned", "Путь") }
        body {
            worktrees.forEach { wt ->
                val branchCell = if (wt.isMain) bold(wt.label) else wt.label
                row(branchCell, statusCell(wt), orphanCell(wt), gray(wt.path))
            }
        }
    }

    /**
     * Table widget for the multi-repo aggregated view (Этап 4).
     *
     * A separate renderer rather than an overloaded universal table: the aggregated
     * view leads with a bolded "Репозиторий" column, and keeps the same `main`-is-bold
     * cue per row — it's still meaningful once qualified by the repo name in the same
     * row. Two genuinely different shapes read cleaner as two small functions than as
     * one branchy one.
     */
    fun renderAggregated(worktrees: List<AggregatedWorktree>): Widget = table {
        overflowWrap = OverflowWrap.ELLIPSES
        column(4) {
            width = ColumnWidth.Expand()
            whitespace = Whitespace.NORMAL
            overflowWrap = OverflowWrap.ELLIPSES
        }
        header { row("Репозиторий", "Ветка", "Статус", "Orphaned", "Путь") }
        body {
            worktrees.forEach { agg ->
                val wt = agg.worktree
                val branchCell = if (wt.isMain) bold(wt.label) else wt.label
                row(bold(agg.repo), branchCell, statusCell(wt), orphanCell(wt), gray(wt.path))
            }
        }
    }
}
