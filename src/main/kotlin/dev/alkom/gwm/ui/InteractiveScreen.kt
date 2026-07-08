package dev.alkom.gwm.ui

import com.github.ajalt.mordant.input.interactiveSelectList
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.cyan
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.YesNoPrompt
import dev.alkom.gwm.git.Worktree
import dev.alkom.gwm.git.WorktreeService
import dev.alkom.gwm.git.WorktreeService.RemoveStatus

/**
 * Interactive, keyboard-driven worktree screen (Mordant `interactiveSelectList`).
 *
 * Renders the repo's worktrees as a selectable list; on pick the user chooses an
 * action (details / remove). Line-oriented rather than full-screen — enough for the
 * MVP (docs/TECHNICAL_PLAN.md §1). The UI is thin: all git work lives in
 * [WorktreeService]; the row-label formatting is pulled into pure functions
 * ([rowLabel], [entryKey]) so it can be unit-tested without a TTY.
 */
class InteractiveScreen(
    private val terminal: Terminal,
    private val service: WorktreeService,
) {

    fun run() {
        // interactiveSelectList needs raw-mode stdin; on a non-interactive terminal
        // (piped input, no TTY) it would throw. Fail gracefully with a hint instead.
        if (!terminal.terminalInfo.inputInteractive) {
            terminal.println(
                brightYellow(
                    "Интерактивный режим требует настоящего терминала (TTY). " +
                        "Используйте команды list / create / remove.",
                ),
            )
            return
        }
        while (true) {
            // Annotate with dirty flags AND orphaned/stale hints (Этап 5) so the row
            // label can flag stale worktrees. Both are informational; nothing is deleted.
            val worktrees = service.withOrphanStatus(service.withDirtyFlags(service.list()))
            if (worktrees.isEmpty()) {
                terminal.println(brightYellow("Нет worktree для отображения."))
                return
            }

            terminal.println(bold("Worktrees — стрелки для выбора, Enter — действие, q — выход"))
            val keys = worktrees.map { entryKey(it) }
            val selectedKey = terminal.interactiveSelectList {
                worktrees.forEach { wt -> addEntry(entryKey(wt), rowLabel(wt)) }
                clearOnExit(false)
            }

            if (selectedKey == null) return
            val selected = worktrees[keys.indexOf(selectedKey)]
            if (!handleAction(selected)) return
        }
    }

    /** Runs the action menu for [wt]. Returns false when the user wants to quit. */
    private fun handleAction(wt: Worktree): Boolean {
        terminal.println()
        terminal.println(bold("Выбрано: ") + cyan(wt.label) + gray("  ${wt.path}"))

        val action = terminal.interactiveSelectList {
            addEntry("details", "Показать детали")
            addEntry("remove", "Удалить worktree")
            addEntry("back", "Назад к списку")
            clearOnExit(false)
        } ?: return false

        when (action) {
            "details" -> showDetails(wt)
            "remove" -> removeWorktree(wt)
            "back" -> {}
        }
        return true
    }

    private fun showDetails(wt: Worktree) {
        terminal.println()
        terminal.println(bold("Детали worktree"))
        terminal.println("  ${bold("Путь:")}    ${wt.path}")
        terminal.println("  ${bold("Ветка:")}   ${wt.label}")
        terminal.println("  ${bold("HEAD:")}    ${wt.head ?: "-"}")
        terminal.println("  ${bold("Статус:")}  ${WorktreeTable.statusCell(wt)}")
        terminal.println(
            "  ${bold("Флаги:")}   " +
                listOfNotNull(
                    if (wt.isMain) "main" else null,
                    if (wt.isBare) "bare" else null,
                    if (wt.isDetached) "detached" else null,
                    if (wt.isLocked) "locked" else null,
                    if (wt.isPrunable) "prunable" else null,
                ).ifEmpty { listOf("-") }.joinToString(", "),
        )
        // Orphaned/stale hint (Этап 5): advisory only — we suggest, the user decides.
        WorktreeTable.orphanHint(wt)?.let { hint ->
            terminal.println("  ${bold("Orphaned:")} " + brightYellow("⚠ $hint"))
        }
        terminal.println()
    }

    private fun removeWorktree(wt: Worktree) {
        if (wt.isMain) {
            terminal.println(brightYellow("Нельзя удалить основной (main) worktree."))
            return
        }

        val confirmed = YesNoPrompt(
            "Удалить worktree ${wt.label} (${wt.path})?",
            terminal,
            default = false,
        ).ask() ?: false
        if (!confirmed) {
            terminal.println(gray("Отменено."))
            return
        }

        val outcome = service.safeRemove(wt.path, force = false)
        when (outcome.status) {
            RemoveStatus.REMOVED -> terminal.println(brightGreen("✓ Удалён: ${wt.path}"))
            RemoveStatus.NOT_FOUND -> terminal.println(brightYellow("Worktree не найден (уже удалён?)."))
            RemoveStatus.GIT_ERROR ->
                terminal.println(brightYellow("Ошибка git: ${outcome.result?.stderr?.trim().orEmpty()}"))
            RemoveStatus.BLOCKED_DIRTY -> confirmForceRemove(wt)
        }
    }

    private fun confirmForceRemove(wt: Worktree) {
        terminal.println(
            brightYellow("⚠ В worktree есть незакоммиченные изменения — они будут потеряны."),
        )
        val force = YesNoPrompt(
            "Всё равно удалить с --force?",
            terminal,
            default = false,
        ).ask() ?: false
        if (!force) {
            terminal.println(gray("Отменено — изменения сохранены."))
            return
        }
        val outcome = service.safeRemove(wt.path, force = true)
        if (outcome.status == RemoveStatus.REMOVED) {
            terminal.println(brightGreen("✓ Удалён (force): ${wt.path}"))
        } else {
            terminal.println(brightYellow("Ошибка git: ${outcome.result?.stderr?.trim().orEmpty()}"))
        }
    }

    companion object {
        /**
         * Stable identity key for a worktree entry — its path is unique per repo.
         * Pure function, unit-testable without a terminal.
         */
        fun entryKey(wt: Worktree): String = wt.path

        /**
         * Human-readable one-line label for the select list: branch, status glyph,
         * and path. Pure function so formatting is testable without a TTY.
         */
        fun rowLabel(wt: Worktree): String {
            val marker = when (wt.dirty) {
                true -> "●"
                false -> "✓"
                null -> "?"
            }
            val branch = if (wt.isMain) "${wt.label} (main)" else wt.label
            // Append a stale badge inline (Этап 5) — a hint to the human, not an action.
            val orphan = if (wt.orphan.isOrphaned) "  ⚠ ${wt.orphan.reasons.joinToString("/")}" else ""
            return "$marker  $branch$orphan  —  ${wt.path}"
        }
    }
}
