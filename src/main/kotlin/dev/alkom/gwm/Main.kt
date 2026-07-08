package dev.alkom.gwm

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
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
import dev.alkom.gwm.scan.RepoScanner
import dev.alkom.gwm.scan.ScanService
import dev.alkom.gwm.scan.WorktreeMatcher
import dev.alkom.gwm.ui.InteractiveScreen
import dev.alkom.gwm.ui.ShellInit
import dev.alkom.gwm.ui.WorktreeTable
import java.io.File

/**
 * gwm — git worktree manager (TUI).
 *
 * Фаза 1 (single repo): `list` (static table), `interactive` (selectable screen),
 * `create` (new worktree) and `remove` (safe deletion). Multi-repo scanning,
 * orphaned-worktree detection and the cwd-switch helpers (Этап 6) are layered on top
 * (see docs/PLAN.md).
 *
 * The `--print-path <fuzzy>` option lives on the ROOT command, not as a subcommand, on
 * purpose: Clikt reserves leading-dash tokens for options, so a subcommand literally named
 * `--print-path` can never be dispatched. Modelling it as a root option preserves the exact
 * `gwm --print-path foo` UX the shell wrapper depends on (docs/TECHNICAL_PLAN §5). We set
 * [invokeWithoutSubcommand] so [run] fires even when no subcommand follows the option.
 */
class Gwm : CliktCommand(name = "gwm") {
    override val invokeWithoutSubcommand: Boolean = true

    private val printPath: String? by option(
        "--print-path",
        help = "напечатать абсолютный путь worktree по неточному имени (для `cd \$(gwm --print-path ...)`) и выйти",
        metavar = "FUZZY",
    )
    private val root: String? by option(
        "--root",
        help = "корень портфеля репозиториев (по умолчанию ~/Projects/ai-projects или \$GWM_ROOT)",
    )

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "TUI-менеджер git worktree по локальным репозиториям"

    override fun run() {
        val query = printPath ?: return
        // Machine-readable path resolution; prints exactly the path to stdout or throws a
        // CliktError (stderr + non-zero exit). See PrintPath.emit for the WHY.
        PrintPath.emit(query, root)
    }
}

/**
 * The `--print-path` resolution, factored out of [Gwm] so the exit-code/stderr contract is
 * defined in one place. Scans the portfolio, resolves the fuzzy [query] via
 * [WorktreeMatcher] and either prints ONE absolute path to plain stdout or raises a
 * [CliktError].
 *
 * WHY the path is written with a raw [println] (not Mordant): a shell command substitution
 * `cd "$(gwm --print-path foo)"` captures stdout verbatim, so any color codes would corrupt
 * the path. We emit exactly `<absolute-path>\n` and route every diagnostic to stderr.
 *
 * WHY failures exit non-zero and print NOTHING to stdout: on an empty/failed lookup we must
 * not emit a blank line, because `cd "$(...)"` on empty output can drop the user in $HOME. A
 * [CliktError] writes to stderr and exits non-zero, so the wrapper's `&&`-guard suppresses
 * the `cd` entirely (see [ShellInit]).
 */
object PrintPath {
    fun emit(query: String, root: String?) {
        val rootDir = RepoScanner.resolveRoot(root)
        val repos = RepoScanner.findRepos(rootDir)
        val worktrees = ScanService().scan(repos).worktrees

        when (val match = WorktreeMatcher.resolve(worktrees, query)) {
            is WorktreeMatcher.Match.Found ->
                println(File(match.worktree.worktree.path).absolutePath)

            is WorktreeMatcher.Match.None ->
                throw CliktError("Worktree не найден по запросу: '${match.query}'")

            is WorktreeMatcher.Match.Ambiguous -> {
                val candidates = match.candidates.joinToString("\n") { c ->
                    "  ${c.repo}/${c.worktree.label} → ${File(c.worktree.path).absolutePath}"
                }
                throw CliktError(
                    "Неоднозначный запрос '${match.query}' — подходит несколько worktree:\n" +
                        "$candidates\nУточните имя.",
                )
            }
        }
    }
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
        val worktrees = service.withOrphanStatus(service.withDirtyFlags(service.list()))
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

class ScanCommand : CliktCommand(name = "scan") {
    private val terminal = Terminal()
    private val root: String? by option(
        "--root",
        help = "корень портфеля репозиториев (по умолчанию ~/Projects/ai-projects или \$GWM_ROOT)",
    )

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Агрегированный обзор worktree по всем репозиториям портфеля"

    override fun run() {
        val rootDir = RepoScanner.resolveRoot(root)
        val repos = RepoScanner.findRepos(rootDir)
        if (repos.isEmpty()) {
            terminal.println(brightYellow("Git-репозитории не найдены в: ${rootDir.path}"))
            return
        }

        terminal.println(bold("Портфель: ${rootDir.path} (${repos.size} репо)"))
        val result = ScanService().scan(repos)
        terminal.println(WorktreeTable.renderAggregated(result.worktrees))

        // Broken repos are reported separately so a partial scan still shows the rest.
        result.errors.forEach { err ->
            terminal.println(brightYellow("⚠ ${err.repo}: ${err.reason.trim()}"))
        }
    }
}

/**
 * `gwm shell-init` — prints the shell-function wrapper to install once via
 * `eval "$(gwm shell-init)"`. See [ShellInit] for why the wrapper (and its success-guard)
 * are needed. `gwm` only PRINTS the snippet; it never touches the user's dotfiles.
 */
class ShellInitCommand : CliktCommand(name = "shell-init") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Напечатать shell-функцию для .bashrc/.zshrc: eval \"\$(gwm shell-init)\" → `gwm cd <fuzzy>`"

    override fun run() {
        // Raw stdout so `eval` gets clean, unstyled shell code.
        println(ShellInit.snippet())
    }
}

fun main(args: Array<String>) =
    Gwm().subcommands(
        ListCommand(),
        InteractiveCommand(),
        CreateCommand(),
        RemoveCommand(),
        ScanCommand(),
        ShellInitCommand(),
    ).main(args)
