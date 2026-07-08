package dev.alkom.gwm.ui

import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.table.table
import dev.alkom.gwm.git.Worktree

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

    /** Full table widget for a list of worktrees. */
    fun render(worktrees: List<Worktree>): Widget = table {
        header { row("Ветка", "Статус", "Путь") }
        body {
            worktrees.forEach { wt ->
                val branchCell = if (wt.isMain) bold(wt.label) else wt.label
                row(branchCell, statusCell(wt), gray(wt.path))
            }
        }
    }
}
