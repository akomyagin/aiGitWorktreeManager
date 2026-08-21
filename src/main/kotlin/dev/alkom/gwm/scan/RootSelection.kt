package dev.alkom.gwm.scan

import java.io.File

/** A source of a portfolio-root value, kept so a conflict message can name where each came from. */
data class RootCandidate(val source: String, val value: String?) // e.g. "аргумент ROOT", "scan --root", "gwm --root"

/** Outcome of reconciling the several places a root can be specified (Р4). */
sealed interface RootChoice {
    /** The single agreed value (null = no source given → fall through to GWM_ROOT/default). */
    data class One(val value: String?) : RootChoice

    /** Two or more sources gave DIFFERENT non-blank values — the caller raises a CliktError. */
    data class Conflict(val candidates: List<RootCandidate>) : RootChoice
}

/**
 * Reconciles the root value from its several possible sources into ONE choice (Р4).
 *
 * There is intentionally no silent precedence: a root given twice with different values is an
 * error, because "silently take the wrong root is worse than an error" and any precedence here
 * would be unguessable. Blank/empty values are ignored. Two values that normalize to the same
 * path (after `~`-expansion + `absoluteFile.normalize()`, so `/tmp/x` and `/tmp/x/` match) are
 * NOT a conflict.
 *
 * Pure logic — no FS access beyond `$HOME` for `~`-expansion; unit-tested without git.
 */
object RootSelection {
    fun choose(candidates: List<RootCandidate>): RootChoice {
        val present = candidates.filter { !it.value.isNullOrBlank() }
        if (present.isEmpty()) return RootChoice.One(null)

        val distinct = present.map { normalize(it.value!!) }.distinct()
        return if (distinct.size == 1) {
            // Return the first raw value; resolveRoot re-normalizes it anyway.
            RootChoice.One(present.first().value)
        } else {
            RootChoice.Conflict(present)
        }
    }

    private fun normalize(raw: String): String =
        File(RepoScanner.expandTilde(raw)).absoluteFile.normalize().path
}
