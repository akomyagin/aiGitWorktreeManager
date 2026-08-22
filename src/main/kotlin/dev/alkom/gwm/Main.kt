package dev.alkom.gwm

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.findObject
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
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
import dev.alkom.gwm.config.Colors
import dev.alkom.gwm.config.GwmConfig
import dev.alkom.gwm.git.WorktreeService
import dev.alkom.gwm.git.WorktreeService.RemoveStatus
import dev.alkom.gwm.scan.MultiRootSelection
import dev.alkom.gwm.scan.RepoScanner
import dev.alkom.gwm.scan.RootCandidate
import dev.alkom.gwm.scan.RootChoice
import dev.alkom.gwm.scan.RootSelection
import dev.alkom.gwm.scan.ScanService
import dev.alkom.gwm.scan.WorktreeMatcher
import dev.alkom.gwm.scan.formatAllRootsMissingWarning
import dev.alkom.gwm.scan.formatMissingRootsWarning
import dev.alkom.gwm.scan.reposToScan
import dev.alkom.gwm.ui.InteractiveScreen
import dev.alkom.gwm.ui.ShellInit
import dev.alkom.gwm.ui.TableColors
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
/**
 * Root-command options exposed to subcommands via the Clikt Context.obj (Р4/Р6).
 *
 * [config] is loaded ONCE in [Gwm.run] and carried here so subcommands see it without re-reading
 * the file. It is the optional `~/.config/gwm/config.toml` (Этап 8): a silent fallback for the
 * scan root, the `gwm create` path template and the status color scheme.
 */
data class GwmGlobals(val root: String?, val config: GwmConfig)

class Gwm : CliktCommand(name = "gwm") {
    // Р6: install the terminal into the Clikt context so subcommands inherit it and
    // CliktCommand.test(argv, width = ...) can substitute its own — the only way to unit-test
    // the width-dependent rendering (bugs 2/4/5).
    init {
        context { terminal = gwmTerminal() }
    }

    override val invokeWithoutSubcommand: Boolean = true

    private val printPath: String? by option(
        "--print-path",
        help = "напечатать абсолютный путь worktree по неточному имени (для `cd \$(gwm --print-path ...)`) и выйти",
        metavar = "FUZZY",
    )
    private val root: String? by option(
        "--root",
        help = "корень портфеля для scan и --print-path (по умолчанию ~/Projects/ai-projects или \$GWM_ROOT)",
    )
    // Real user-facing override for the config path (Этап 8) — e.g. per-project or per-shell
    // configs. Defaults to the standard ~/.config/gwm/config.toml (via GwmConfig.load()) when
    // absent, so everyday usage needs no flag. Doubles as the test-injection point: command-level
    // tests point it at a @TempDir config without touching the real one.
    private val configPath: String? by option(
        "--config",
        help = "путь к config.toml (по умолчанию ~/.config/gwm/config.toml)",
    )

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "TUI-менеджер git worktree по локальным репозиториям"

    override fun run() {
        // Load the config ONCE, before anything else: a broken TOML must abort with a clear
        // CliktError (stderr, non-zero exit) BEFORE any scan runs, and subcommands read it from
        // GwmGlobals rather than re-parsing the file.
        val config = configPath?.let { GwmConfig.load(File(it)) } ?: GwmConfig.load()
        // Must run BEFORE the early return: the subcommand's run() fires after ours and needs to
        // see the root/config here. Setting it later would leave `scan` with a null root (bug 4).
        currentContext.obj = GwmGlobals(root, config)
        val query = printPath ?: return
        // Machine-readable path resolution; prints exactly the path to stdout or throws a
        // CliktError (stderr + non-zero exit). See PrintPath.emit for the WHY.
        PrintPath.emit(query, root, config)
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
 *
 * WHY it is now multi-root (fix/print-path-multi-root): it shares [MultiRootSelection.resolveRootsToScan]
 * with `scan`, so with no CLI/`$GWM_ROOT` override it resolves the fuzzy query over worktrees
 * aggregated from EVERY existing `config.roots` entry — `gwm cd <repo-in-a-second-root>` now reaches
 * repos beyond `roots[0]`, matching what `gwm scan` already shows. The stdout contract above is
 * unchanged: multi-root diagnostics (skipped/missing roots) go to **stderr** (never stdout), and a
 * missing root still just yields no match → [CliktError], not a crash.
 */
object PrintPath {
    fun emit(query: String, root: String?, config: GwmConfig) {
        // `root` = the root command's `--root` (the only CLI root source on the root command; there
        // is no positional ROOT / `scan --root` here). Reduce it to `chosen` the same way scan reduces
        // its three sources, then use the shared fork so print-path aggregates exactly like scan.
        val chosen = root?.takeIf { it.isNotBlank() }
        val rr = MultiRootSelection.resolveRootsToScan(chosen, config.roots)
        // Diagnostics go to STDERR, never stdout: `cd "$(gwm --print-path ...)"` captures stdout, so a
        // warning there would corrupt the emitted path (see the class doc). A missing root is NOT an
        // error here — an empty repo list falls through to Match.None → CliktError below.
        if (!rr.singleRootOverride) {
            if (rr.missing.isNotEmpty() && rr.roots.isNotEmpty()) {
                System.err.println(formatMissingRootsWarning(rr.missing))
            }
            if (rr.fellBackToDefaultFromMissing) {
                System.err.println(formatAllRootsMissingWarning(rr.missing))
            }
        }
        val repos = rr.reposToScan()
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

/**
 * Non-interactive output (piped, redirected, a pty that can't report its size — e.g. `TERM=dumb`
 * CI runners) falls back to Mordant's built-in ~79-column default, which is often narrower than
 * where the output will actually be read (`less`, a log viewer, CI logs). Respect `$COLUMNS` when
 * the environment sets it so piped/logged output isn't crushed tighter than necessary. Real
 * interactive terminals are unaffected — [Terminal.width] stays auto-detected and live-resizable.
 */
private fun gwmTerminal(): Terminal =
    Terminal(nonInteractiveWidth = System.getenv("COLUMNS")?.toIntOrNull() ?: 120)

/**
 * Shared helper: resolve a [WorktreeService] for a repo path, or raise a [CliktError] (stderr +
 * non-zero exit) so callers — and any shell script wrapping `gwm` — can detect the failure.
 */
private fun openRepo(repo: String): WorktreeService {
    val repoDir = File(repo).absoluteFile
    val service = WorktreeService(repoDir)
    if (!service.isGitRepo()) {
        throw CliktError("Не git-репозиторий: ${repoDir.path}")
    }
    return service
}

class ListCommand : CliktCommand(name = "list") {
    private val repo: String by argument(help = "путь к git-репозиторию").default(".")

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Показать worktree репозитория (ветка, статус)"

    override fun run() {
        val service = openRepo(repo)
        val colors = tableColors(currentContext.findObject<GwmGlobals>(), terminal)
        val worktrees = service.withOrphanStatus(service.withDirtyFlags(service.list()))
        // Base = the repo's PARENT dir: `gwm create`'s default layout is sibling `../<repo>-<branch>`,
        // so relative-to-parent renders main = `repo`, linked = `repo-branch`. Anchor it in the
        // header so the relativity isn't a mystery (Р1).
        // normalize() so `list .` shows the real directory name (not ".") in the header/base.
        val repoDir = File(repo).absoluteFile.normalize()
        val base = repoDir.parentFile
        terminal.println(bold("Worktrees: ${repoDir.name}") + gray("  (${base?.path ?: repoDir.path})"))
        terminal.println(WorktreeTable.render(worktrees, base, terminal.size.width, colors))
    }
}

/**
 * Resolves the status color scheme from the loaded config, or the historic default when no
 * globals are present (e.g. a subcommand-only unit test). Kept in one place so every command
 * derives colors identically. Warns (stderr, exit stays 0) about any unrecognized color name
 * instead of silently falling back — the config never crashes the tool, but an unknown name
 * (typo like "gren") should be visible, not silently swallowed (plan §Р6).
 */
private fun tableColors(globals: GwmGlobals?, terminal: Terminal): TableColors {
    val scheme = globals?.config?.colors
    if (scheme != null) {
        listOf("clean" to scheme.clean, "dirty" to scheme.dirty, "muted" to scheme.muted).forEach { (role, name) ->
            if (name != null && !Colors.isKnown(name)) {
                terminal.println(brightYellow("⚠ неизвестный цвет '$name' для '$role' в конфиге — используется дефолт."))
            }
        }
    }
    return scheme?.let { TableColors.from(it) } ?: TableColors.DEFAULT
}

class InteractiveCommand : CliktCommand(name = "interactive") {
    private val repo: String by argument(help = "путь к git-репозиторию").default(".")

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Интерактивный экран: выбор worktree и действий (детали / удалить)"

    override fun run() {
        val service = openRepo(repo)
        InteractiveScreen(terminal, service, pathBase = File(repo).absoluteFile.normalize().parentFile).run()
    }
}

class CreateCommand : CliktCommand(name = "create") {
    private val branch: String by argument(help = "имя новой ветки для worktree")
    private val path: String? by argument(help = "путь для worktree (по умолчанию рядом с репо)").optional()
    private val repo: String by option("--repo", help = "путь к git-репозиторию").default(".")
    private val base: String by option("--base", help = "базовый ref для новой ветки").default("HEAD")

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Создать новый worktree (git worktree add) с новой веткой"

    override fun run() {
        val service = openRepo(repo)
        val globals = currentContext.findObject<GwmGlobals>()
        val template = globals?.config?.worktreePathTemplate
        val target = path?.let { File(it).absoluteFile } ?: service.defaultWorktreePath(branch, template)

        if (target.exists()) {
            throw CliktError("Путь уже существует: ${target.path}")
        }

        terminal.println(gray("Создаю worktree: ветка '$branch' от '$base' в ${target.path}"))
        val res = service.add(target, newBranch = branch, baseRef = base)
        if (res.ok) {
            terminal.println(brightGreen("✓ Создан worktree: ${target.path}"))
            if (res.stdout.isNotBlank()) terminal.println(gray(res.stdout.trim()))
        } else {
            throw CliktError("Ошибка git: ${res.stderr.trim()}")
        }
    }
}

class RemoveCommand : CliktCommand(name = "remove") {
    private val target: String by argument(help = "путь или имя ветки worktree")
    private val repo: String by option("--repo", help = "путь к git-репозиторию").default(".")
    private val force: Boolean by option("--force", help = "удалить даже при незакоммиченных изменениях").flag()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Удалить worktree (git worktree remove) с проверкой на dirty-состояние"

    override fun run() {
        val service = openRepo(repo)
        val outcome = service.safeRemove(target, force = force)
        when (outcome.status) {
            RemoveStatus.REMOVED -> terminal.println(brightGreen("✓ Удалён worktree: $target"))
            RemoveStatus.NOT_FOUND ->
                throw CliktError("Worktree не найден: $target")
            RemoveStatus.BLOCKED_DIRTY -> throw CliktError(
                "В worktree есть незакоммиченные изменения. " +
                    "Повторите с --force, чтобы удалить и потерять их.",
            )
            RemoveStatus.GIT_ERROR ->
                throw CliktError("Ошибка git: ${outcome.result?.stderr?.trim().orEmpty()}")
        }
    }
}

/**
 * `gwm scan` — aggregated worktree overview across the whole portfolio.
 *
 * **Multi-root trigger (Этап 9, Р1).** Multi-root scanning is enabled ONLY when no CLI root source
 * is given (no positional `ROOT`, no `scan --root`, no root `gwm --root`) AND `$GWM_ROOT` is unset.
 * In that case `scan` aggregates EVERY existing directory in `config.roots` into one output. Any
 * explicit CLI input or `$GWM_ROOT` stays a single-root override — explicit user input = one concrete
 * root — which keeps backwards compatibility with Этапы 7/8. Multi-root lives BELOW the
 * [RootSelection] CLI-conflict gate: a divergence of explicit CLI roots is still a hard error; the
 * union of silent config roots happens only when the CLI gave nothing (see [MultiRootSelection]).
 *
 * Duplicate roots (same path written twice, `/x` vs `/x/`, `~/p` vs `$HOME/p`) collapse in
 * [MultiRootSelection]; a single physical repo reachable from two roots collapses in
 * [ScanService.dedupRepos]. Missing/broken config roots WARN (stderr, exit 0) but never abort — the
 * only non-zero exits remain a CLI-root conflict and a broken config (plan §Р5).
 *
 * `--print-path` shares this exact fork — [MultiRootSelection.resolveRootsToScan] — so it aggregates
 * worktrees from every existing `config.roots` entry the same way this command does (see [PrintPath]'s
 * KDoc for its side of the contract: stderr diagnostics, stdout untouched).
 */
class ScanCommand : CliktCommand(name = "scan") {
    // Three ways to give the root — positional ROOT, `scan --root`, and the root command's
    // `--root` (via context.obj). Clikt parses options after the subcommand token with the
    // subcommand's OWN parser, so `scan --root=X` is the subcommand's option, not the root one.
    // We can't drop either (`--root` is needed for --print-path; `scan --root` is the shipped
    // form), so all three are reconciled to ONE value (Р4).
    private val rootArg: String? by argument(
        "ROOT",
        help = "корень портфеля (по умолчанию ~/Projects/ai-projects или \$GWM_ROOT)",
    ).optional()
    private val rootOpt: String? by option("--root", help = "то же, что позиционный ROOT")

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Агрегированный обзор worktree по всем репозиториям портфеля"

    override fun run() {
        val globals = currentContext.findObject<GwmGlobals>()
        val choice = RootSelection.choose(
            listOf(
                RootCandidate("аргумент ROOT", rootArg),
                RootCandidate("scan --root", rootOpt),
                RootCandidate("gwm --root", globals?.root),
            ),
        )
        val chosen = when (choice) {
            is RootChoice.One -> choice.value
            is RootChoice.Conflict -> throw CliktError(
                "Корень портфеля указан несколько раз и по-разному:\n" +
                    choice.candidates.joinToString("\n") { "  ${it.source} = ${it.value}" } +
                    "\nУкажите его один раз.",
            )
        }

        // Resolve the roots to scan via the shared override-vs-multi-root fork (Этап 9 Р1; the same
        // helper now backs --print-path). It returns roots + diagnostics and does no I/O itself, so
        // scan renders the diagnostics its own way: warnings to stdout (terminal.println), a missing
        // single/default root as a CliktError. See MultiRootSelection.resolveRootsToScan / ResolvedRoots.
        val rr = MultiRootSelection.resolveRootsToScan(chosen, globals?.config?.roots.orEmpty())
        if (rr.singleRootOverride) {
            if (rr.roots.isEmpty()) {
                // The explicit CLI/env root does not exist — same hard error as Этап 7/8. rejectedSingleRoot
                // is the File resolveRootsToScan already resolved internally — no need to re-resolve it here.
                throw CliktError(
                    "Корень портфеля не найден или не директория: ${rr.rejectedSingleRoot?.path}",
                )
            }
        } else {
            // A stale/typo'd config entry otherwise gives no signal it was ignored — generalises
            // Этап 8's warning (plan §Р5, §Р8). exit stays 0.
            if (rr.missing.isNotEmpty() && rr.roots.isNotEmpty()) {
                terminal.println(brightYellow(formatMissingRootsWarning(rr.missing)))
            }
            if (rr.fellBackToDefaultFromMissing) {
                terminal.println(brightYellow(formatAllRootsMissingWarning(rr.missing)))
            }
            if (rr.roots.isEmpty()) {
                // Multi-root branch fell back to the default, but even that doesn't exist (Этап 9 bug 1).
                throw CliktError("Корень портфеля не найден или не директория: ${RepoScanner.defaultRoot().path}")
            }
        }
        val rootsToScan = rr.roots
        // One root → anchor relative paths to it (identical to Этап 8, keeps output byte-stable);
        // several → no common base, PathDisplay falls back to ~/… or absolute (Р6).
        val base: File? = rootsToScan.singleOrNull()

        // Collect repos from every root, then dedup a physical repo reachable from >1 root (Р3/Р4).
        val repos = rr.reposToScan()
        if (repos.isEmpty()) {
            val where = rootsToScan.joinToString(", ") { it.path }
            terminal.println(brightYellow("Git-репозитории не найдены в: $where"))
            return
        }

        // Header: one root reads as Этап 8 (path + count); several announce multiplicity + the list
        // of roots actually scanned, so the user never wonders what was aggregated (Р6).
        val singleRoot = rootsToScan.singleOrNull()
        if (singleRoot != null) {
            terminal.println(bold("Портфель: ${singleRoot.path} (${repos.size} репо)"))
        } else {
            terminal.println(bold("Портфель: ${rootsToScan.size} корней, ${repos.size} репо"))
            rootsToScan.forEach { terminal.println(gray("  ${it.path}")) }
        }

        val result = ScanService().scan(repos)
        terminal.println(
            WorktreeTable.renderAggregated(result.worktrees, base, terminal.size.width, tableColors(globals, terminal)),
        )

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
