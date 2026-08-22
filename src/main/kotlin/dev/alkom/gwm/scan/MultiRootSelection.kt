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
}
