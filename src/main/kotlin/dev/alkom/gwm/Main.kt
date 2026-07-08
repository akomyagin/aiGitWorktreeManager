package dev.alkom.gwm

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.default
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.rendering.TextColors.gray
import com.github.ajalt.mordant.rendering.TextStyles.bold
import com.github.ajalt.mordant.terminal.Terminal
import dev.alkom.gwm.git.WorktreeService
import dev.alkom.gwm.git.WorktreeService.RemoveStatus
import dev.alkom.gwm.ui.InteractiveScreen
import dev.alkom.gwm.ui.WorktreeTable
import java.io.File

/**
 * gwm — git worktree manager (TUI).
 *
 * Фаза 1 (single repo): `list` (static table), `interactive` (selectable screen),
 * `create` (new worktree) and `remove` (safe deletion). Multi-repo scanning and
 * orphaned-worktree detection arrive in later Этапы (see docs/PLAN.md).
 */
class Gwm : CliktCommand(name = "gwm") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "TUI-менеджер git worktree по локальным репозиториям"

    override fun run() = Unit
}

/** Shared helper: resolve a [WorktreeService] for a repo path or bail with a message. */
private fun openRepo(terminal: Terminal, repo: String): WorktreeService? {
    val repoDir = File(repo).absoluteFile
    val service = WorktreeService(repoDir)
    if (!service.isGitRepo()) {
        terminal.println(brightYellow("Не git-репозиторий: ${repoDir.path}"))
        return null
    }
    return service
}

class ListCommand : CliktCommand(name = "list") {
    private val terminal = Terminal()
    private val repo: String by argument(help = "путь к git-репозиторию").default(".")

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Показать worktree репозитория (ветка, статус)"

    override fun run() {
        val service = openRepo(terminal, repo) ?: return
        val worktrees = service.withDirtyFlags(service.list())
        terminal.println(bold("Worktrees: ${File(repo).absoluteFile.name}"))
        terminal.println(WorktreeTable.render(worktrees))
    }
}

class InteractiveCommand : CliktCommand(name = "interactive") {
    private val terminal = Terminal()
    private val repo: String by argument(help = "путь к git-репозиторию").default(".")

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Интерактивный экран: выбор worktree и действий (детали / удалить)"

    override fun run() {
        val service = openRepo(terminal, repo) ?: return
        InteractiveScreen(terminal, service).run()
    }
}

class CreateCommand : CliktCommand(name = "create") {
    private val terminal = Terminal()
    private val branch: String by argument(help = "имя новой ветки для worktree")
    private val path: String? by argument(help = "путь для worktree (по умолчанию рядом с репо)").optional()
    private val repo: String by option("--repo", help = "путь к git-репозиторию").default(".")
    private val base: String by option("--base", help = "базовый ref для новой ветки").default("HEAD")

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Создать новый worktree (git worktree add) с новой веткой"

    override fun run() {
        val service = openRepo(terminal, repo) ?: return
        val target = path?.let { File(it).absoluteFile } ?: service.defaultWorktreePath(branch)

        if (target.exists()) {
            terminal.println(brightYellow("Путь уже существует: ${target.path}"))
            return
        }

        terminal.println(gray("Создаю worktree: ветка '$branch' от '$base' в ${target.path}"))
        val res = service.add(target, newBranch = branch, baseRef = base)
        if (res.ok) {
            terminal.println(brightGreen("✓ Создан worktree: ${target.path}"))
            if (res.stdout.isNotBlank()) terminal.println(gray(res.stdout.trim()))
        } else {
            terminal.println(brightYellow("Ошибка git: ${res.stderr.trim()}"))
        }
    }
}

class RemoveCommand : CliktCommand(name = "remove") {
    private val terminal = Terminal()
    private val target: String by argument(help = "путь или имя ветки worktree")
    private val repo: String by option("--repo", help = "путь к git-репозиторию").default(".")
    private val force: Boolean by option("--force", help = "удалить даже при незакоммиченных изменениях").flag()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Удалить worktree (git worktree remove) с проверкой на dirty-состояние"

    override fun run() {
        val service = openRepo(terminal, repo) ?: return
        val outcome = service.safeRemove(target, force = force)
        when (outcome.status) {
            RemoveStatus.REMOVED -> terminal.println(brightGreen("✓ Удалён worktree: $target"))
            RemoveStatus.NOT_FOUND ->
                terminal.println(brightYellow("Worktree не найден: $target"))
            RemoveStatus.BLOCKED_DIRTY -> terminal.println(
                brightYellow(
                    "В worktree есть незакоммиченные изменения. " +
                        "Повторите с --force, чтобы удалить и потерять их.",
                ),
            )
            RemoveStatus.GIT_ERROR ->
                terminal.println(brightYellow("Ошибка git: ${outcome.result?.stderr?.trim().orEmpty()}"))
        }
    }
}

fun main(args: Array<String>) =
    Gwm().subcommands(
        ListCommand(),
        InteractiveCommand(),
        CreateCommand(),
        RemoveCommand(),
    ).main(args)
