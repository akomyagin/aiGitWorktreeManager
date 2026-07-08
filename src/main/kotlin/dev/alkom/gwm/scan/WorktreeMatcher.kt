package dev.alkom.gwm.scan

import java.io.File

/**
 * Resolves a fuzzy user query (e.g. `gwm cd feature`) to exactly ONE worktree across
 * the scanned portfolio.
 *
 * WHY a pure resolver (no git, no I/O): this is the one piece of Этап-6 logic with real
 * decision-making — exact-vs-fuzzy precedence and the "refuse to guess when ambiguous"
 * rule — so it must be unit-testable without a terminal or a real repo. The `scan` layer
 * already produces the [AggregatedWorktree] list; this only ranks and picks.
 *
 * WHY substring, not Levenshtein/proper fuzzy: the plan asks to "find a worktree by an
 * imprecise input", and for a personal portfolio of a few dozen worktrees a
 * case-insensitive substring match over the branch name and the directory name is enough.
 * A qualitative fuzzy algorithm would add surprising near-misses (worse for a command that
 * then `cd`s you somewhere), so we deliberately keep matching predictable and boring.
 *
 * WHY exact match wins over substring, and ambiguity is an error (not first-match): a
 * shell wrapper does `cd "$(gwm --print-path "$q")"`. Silently picking the first of several
 * candidates would teleport the user somewhere unintended; making them disambiguate is the
 * safe default. An exact branch/name hit is treated as unambiguous even if it is also a
 * substring of other worktrees (`main` shouldn't be "ambiguous" just because `main-x` exists).
 */
object WorktreeMatcher {

    /** Outcome of resolving a query against the portfolio. */
    sealed interface Match {
        /** Exactly one worktree matched — its absolute path is ready for `cd`. */
        data class Found(val worktree: AggregatedWorktree) : Match

        /** Nothing matched [query]. */
        data class None(val query: String) : Match

        /**
         * Several worktrees matched [query] and none is an exact hit. We refuse to guess:
         * [candidates] is returned so the caller can list them and ask the user to be
         * more specific.
         */
        data class Ambiguous(val query: String, val candidates: List<AggregatedWorktree>) : Match
    }

    /**
     * Resolves [query] against [worktrees].
     *
     * Precedence:
     *  1. Exact match (case-insensitive) on branch name OR directory name → [Match.Found]
     *     when it uniquely identifies a single worktree. Bare/detached entries carry no
     *     branch and are only reachable by their directory name.
     *  2. Otherwise, case-insensitive substring match on branch name or directory name:
     *     one hit → [Match.Found]; several → [Match.Ambiguous]; none → [Match.None].
     *
     * A blank [query] never matches anything ([Match.None]) so an empty argument can't
     * accidentally select a worktree.
     */
    fun resolve(worktrees: List<AggregatedWorktree>, query: String): Match {
        val q = query.trim()
        if (q.isEmpty()) return Match.None(query)

        val exact = worktrees.filter { matchesExactly(it, q) }
        // An exact hit is authoritative: if it names one worktree, take it even when the
        // same token appears as a substring elsewhere (see class doc — `main` vs `main-x`).
        when (exact.size) {
            1 -> return Match.Found(exact.single())
            in 2..Int.MAX_VALUE -> return Match.Ambiguous(query, exact)
        }

        val fuzzy = worktrees.filter { matchesLoosely(it, q) }
        return when (fuzzy.size) {
            0 -> Match.None(query)
            1 -> Match.Found(fuzzy.single())
            else -> Match.Ambiguous(query, fuzzy)
        }
    }

    /** True when [q] equals the worktree's branch name or its directory name (ignoring case). */
    private fun matchesExactly(wt: AggregatedWorktree, q: String): Boolean {
        val branch = wt.worktree.branch
        val dirName = File(wt.worktree.path).name
        return branch.equals(q, ignoreCase = true) || dirName.equals(q, ignoreCase = true)
    }

    /** True when [q] is a substring of the worktree's branch name or its directory name. */
    private fun matchesLoosely(wt: AggregatedWorktree, q: String): Boolean {
        val branch = wt.worktree.branch
        val dirName = File(wt.worktree.path).name
        return (branch != null && branch.contains(q, ignoreCase = true)) ||
            dirName.contains(q, ignoreCase = true)
    }
}
