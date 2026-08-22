package dev.alkom.gwm.scan

import java.io.File

/**
 * Outcome of resolving `config.roots` into the set of scan roots that actually exist (Этап 9, Р3).
 *
 * @param roots   existing directories, in `config.roots` order, deduplicated by canonical absolute
 *                path. These are the roots the multi-root `scan` will aggregate over.
 * @param missing configured entries that do not exist / are not directories, kept verbatim (not
 *                canonicalized) so the caller can WARN about them (stderr, exit stays 0 — see
 *                [ScanCommand], Р5); deduplicated by raw value, preserving first-appearance order.
 */
data class ScanRoots(
    val roots: List<File>,
    val missing: List<String>,
)

/**
 * Outcome of the shared override-vs-multi-root fork (Этап 9, Р1), used by BOTH `scan` and
 * `--print-path` (fix/print-path-multi-root). It carries the roots to scan plus structured
 * diagnostics; it deliberately does NO terminal output and throws NO error, so each caller can
 * route diagnostics where its own contract demands:
 *  - `scan` prints warnings to stdout (`terminal.println`) and turns a missing single/default root
 *    into a [com.github.ajalt.clikt.core.CliktError];
 *  - `--print-path` prints warnings to **stderr** (its stdout is captured by `cd "$(...)"`, so a
 *    warning there would corrupt the emitted path) and treats a missing root as "no repos" →
 *    `Match.None` → `CliktError`, NOT as a resolve error here.
 *
 * @param roots               directories to scan (already filtered by `isDirectory`), in source
 *                            order. Empty in the override branch means the CLI/env root does not
 *                            exist; empty in the multi-root branch means even the default root is
 *                            absent — the caller decides what that means.
 * @param singleRootOverride  true when a CLI root (`chosen`) or `$GWM_ROOT` was given → single-root
 *                            override; false → multi-root branch over `config.roots`.
 * @param missing             configured-but-absent roots, for a warning (empty in the override
 *                            branch, where `config.roots` is ignored entirely).
 * @param fellBackToDefaultFromMissing true only in the multi-root branch when NO configured root
 *                            existed yet `config.roots` had non-blank entries — i.e. the scan fell
 *                            back to the default root and the user should be warned. A blank/empty
 *                            `config.roots` is the normal silent default and keeps this false.
 * @param rejectedSingleRoot  in the override branch, the resolved [File] the CLI/env root pointed at
 *                            when it was NOT a directory (so `roots` came back empty) — kept so the
 *                            caller can report exactly what was rejected without re-resolving it via
 *                            [RepoScanner.resolveRoot]. Null whenever the override root DID resolve to
 *                            a directory, and always null in the multi-root branch (no single root to
 *                            report there).
 */
data class ResolvedRoots(
    val roots: List<File>,
    val singleRootOverride: Boolean,
    val missing: List<String>,
    val fellBackToDefaultFromMissing: Boolean,
    val rejectedSingleRoot: File? = null,
)

/**
 * Reusable, provider-agnostic warning TEXT for a partially-missing `config.roots` (Этап 9 Р5,
 * fix/print-path-multi-root fix 2). Shared by `scan` (stdout via `terminal.println`) and
 * `--print-path` (stderr via `System.err.println`) so the two callers can never drift on wording —
 * only the destination stream/styling differs, decided by the caller.
 */
fun formatMissingRootsWarning(missing: List<String>): String =
    "⚠ пропущены несуществующие корни из конфига: ${missing.joinToString(", ")}"

/**
 * Reusable, provider-agnostic warning TEXT for when every `config.roots` entry was missing and the
 * multi-root branch fell back to the default root ([ResolvedRoots.fellBackToDefaultFromMissing]).
 * See [formatMissingRootsWarning] for why this is factored out.
 */
fun formatAllRootsMissingWarning(missing: List<String>): String =
    "⚠ ни один из корней в конфиге (${missing.joinToString(", ")}) не найден — используется корень по умолчанию."

/**
 * The repos to scan for an already-resolved [ResolvedRoots]: every root's immediate git-repo
 * children ([RepoScanner.findRepos]), deduplicated across roots ([ScanService.dedupRepos]) so a
 * physical repo reachable from two roots is counted once. Shared by `scan` and `--print-path` (fix
 * 4, fix/print-path-multi-root) so the roots-to-repos pipeline lives in exactly one place.
 */
fun ResolvedRoots.reposToScan(): List<File> =
    ScanService.dedupRepos(roots.flatMap { RepoScanner.findRepos(it) })

/**
 * Turns a config's `roots` array into the set of real scan roots for the multi-root `scan` (Этап 9).
 *
 * This is the SILENT-fallback layer, kept deliberately separate from [RootSelection]: the latter
 * reconciles the several CLI root sources and treats a divergence as a hard [RootChoice.Conflict],
 * whereas here we simply UNION every existing configured root into one aggregated scan. Mixing the
 * "conflict of explicit CLI inputs" role with the "union of silent fallback roots" role in one type
 * would corrupt both, so multi-root lives here, below the CLI gate (plan §Р1).
 *
 * Multi-root only kicks in when NO CLI root source and no `$GWM_ROOT` are given — any explicit user
 * input stays a single-root override, decided in [ScanCommand], not here.
 *
 * Pure logic: the only FS access is [File.isDirectory] to tell existing roots from missing ones. In
 * particular symlinks are NOT resolved — dedup is by `absoluteFile.normalize()`, which collapses
 * `/x` vs `/x/` and `~/p` vs `$HOME/p`, but not two distinct paths where one is a symlink to the
 * other (out of scope, plan §Р7 tech-debt).
 */
object MultiRootSelection {

    /**
     * Resolves [configRoots] into a [ScanRoots]:
     *  - blank/whitespace entries are dropped (neither `roots` nor `missing`);
     *  - each remaining entry gets `~`-expansion ([RepoScanner.expandTilde]) then
     *    `absoluteFile.normalize()`;
     *  - existing directories go to `roots`, deduplicated by canonical path while preserving first
     *    appearance order (via [LinkedHashMap]); non-existing entries go to `missing` (raw value),
     *    deduplicated by that raw value while preserving first appearance order (via [LinkedHashSet]).
     *
     * @param home the `$HOME` used for `~`-expansion; overridable so unit tests can point `~` at a
     *             [org.junit.jupiter.api.io.TempDir].
     */
    fun resolveScanRoots(
        configRoots: List<String>,
        home: String = System.getProperty("user.home"),
    ): ScanRoots {
        val roots = LinkedHashMap<String, File>() // canonical path -> File, keeps first-seen order
        val missing = LinkedHashSet<String>() // dedup raw values too, keeps first-seen order
        for (raw in configRoots) {
            if (raw.isBlank()) continue
            val canonical = File(RepoScanner.expandTilde(raw, home)).absoluteFile.normalize()
            if (canonical.isDirectory) {
                roots.putIfAbsent(canonical.path, canonical)
            } else {
                missing += raw
            }
        }
        return ScanRoots(roots.values.toList(), missing.toList())
    }

    /**
     * The single source of truth for the override-vs-multi-root fork (Этап 9, Р1), shared by `scan`
     * and `--print-path`. Pure: the only FS access is `isDirectory` (via [resolveScanRoots] and
     * [RepoScanner.resolveRoot]); it NEITHER prints NOR throws — all diagnostics are returned in
     * [ResolvedRoots] so each caller sends them to the right stream and decides what counts as a
     * failure (see [ResolvedRoots]).
     *
     * Fork:
     *  - **Override** — a non-blank [chosen] (the reconciled CLI root) OR a non-blank `$GWM_ROOT`
     *    means the user pointed at ONE concrete root; `config.roots` is ignored. The root is
     *    [RepoScanner.resolveRoot]'d; it lands in `roots` only if it is a directory (else `roots` is
     *    empty and the caller reports the miss). `singleRootOverride = true`, `missing = []`.
     *  - **Multi-root** — nothing explicit given: aggregate every existing `config.roots` entry
     *    ([resolveScanRoots]). If some exist, scan them all. If none exist, fall back to
     *    [RepoScanner.defaultRoot] (kept only if it is a directory), flagging
     *    `fellBackToDefaultFromMissing` when `config.roots` had non-blank entries so the caller can
     *    warn (an empty/blank config stays a silent default).
     *
     * `$GWM_ROOT` is checked HERE (not only inside [RepoScanner.resolveRoot]) so it counts as an
     * override for the fork decision even though it is not a [RootSelection] conflict source. [env]
     * is injectable so unit tests can drive `$GWM_ROOT` without touching the process environment.
     *
     * @param chosen      the reconciled CLI root value ([RootSelection.choose]); null = CLI gave none
     * @param configRoots the config's `roots` (drives the multi-root branch)
     */
    fun resolveRootsToScan(
        chosen: String?,
        configRoots: List<String>,
        env: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home"),
    ): ResolvedRoots {
        val hasSingleRootOverride = chosen != null || !env("GWM_ROOT").isNullOrBlank()
        if (hasSingleRootOverride) {
            // configRoot is deliberately null: chosen or $GWM_ROOT outranks config by definition of
            // this branch (plan §Р2 of Этап 9). A missing root yields an empty list, not a throw —
            // the caller (scan → CliktError; --print-path → Match.None) decides the diagnostic.
            val rootDir = RepoScanner.resolveRoot(chosen, configRoot = null, env = env)
            return ResolvedRoots(
                roots = if (rootDir.isDirectory) listOf(rootDir) else emptyList(),
                singleRootOverride = true,
                missing = emptyList(),
                fellBackToDefaultFromMissing = false,
                rejectedSingleRoot = if (rootDir.isDirectory) null else rootDir,
            )
        }

        val sr = resolveScanRoots(configRoots, home)
        if (sr.roots.isNotEmpty()) {
            return ResolvedRoots(sr.roots, singleRootOverride = false, missing = sr.missing, fellBackToDefaultFromMissing = false)
        }
        // No usable config root: fall back to the default. Whether the config was "really" non-empty
        // is judged by sr.missing (post blank-filtering), so an all-blank roots array reads as "no
        // config" (silent default), not "all configured roots missing" (Этап 9 bug 3).
        val default = RepoScanner.defaultRoot()
        return ResolvedRoots(
            roots = if (default.isDirectory) listOf(default) else emptyList(),
            singleRootOverride = false,
            missing = sr.missing,
            fellBackToDefaultFromMissing = sr.missing.isNotEmpty(),
        )
    }
}
