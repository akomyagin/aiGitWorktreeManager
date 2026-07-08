package dev.alkom.gwm

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import dev.alkom.gwm.git.WorktreeService
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import java.io.File

/**
 * gwm — git worktree manager (TUI).
 *
 * Этап 0 skeleton: a `list` command that renders the current repo's worktrees
 * as a Mordant table. Later Этапы add interactive selection, create/remove,
 * multi-repo scanning and orphaned-worktree detection (see docs/PLAN.md).
 */
class Gwm : CliktCommand(name = "gwm") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "TUI-менеджер git worktree по локальным репозиториям"

    override fun run() = Unit
}

class ListCommand : CliktCommand(name = "list") {
    private val terminal = Terminal()
    private val repo: String by argument(help = "путь к git-репозиторию").default(".")

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Показать worktree репозитория (ветка, статус)"

    override fun run() {
        val repoDir = File(repo).absoluteFile
        val service = WorktreeService(repoDir)
        if (!service.isGitRepo()) {
            terminal.println(brightYellow("Не git-репозиторий: ${repoDir.path}"))
            return
        }

        val worktrees = service.withDirtyFlags(service.list())
        terminal.println(bold("Worktrees: ${repoDir.name}"))
        terminal.println(
            table {
                header { row("Ветка", "Статус", "Путь") }
                body {
                    worktrees.forEach { wt ->
                        val status = when (wt.dirty) {
                            true -> brightYellow("● dirty")
                            false -> brightGreen("✓ clean")
                            null -> gray("?")
                        }
                        val branchCell = if (wt.isMain) bold(wt.label) else wt.label
                        row(branchCell, status, gray(wt.path))
                    }
                }
            }
        )
    }
}

fun main(args: Array<String>) =
    Gwm().subcommands(ListCommand()).main(args)
