package dev.alkom.gwm.ui

import java.io.File

/**
 * Pure, FS-free path presentation for the overview table (Этап 7).
 *
 * The only environment touch is a default [File] for `$HOME`; everything else is string
 * math, so this is unit-testable without a TTY and — critically — has no dependency on
 * [dev.alkom.gwm.PrintPath]. The `--print-path` contract prints RAW absolute paths; nothing
 * here must ever leak into that path (see plan §3, §10 and test 47).
 *
 * Two truncation directions on purpose:
 *  - paths keep the TAIL (`…/repo/worktree`) — the distinguishing segment lives at the end,
 *    which is exactly why Mordant's default head-preserving ELLIPSES is useless for paths;
 *  - names (repo, branch) keep the HEAD (`aiGitWorktreeMana…`) — identity reads from the start.
 */
object PathDisplay {
    const val ELLIPSIS = "…"

    /**
     * Human-readable form of an absolute worktree [path]:
     * relative to [base] (when inside it), else `~/...` (when inside [home]), else as-is.
     *
     * Comparison is on a segment boundary: `base=/a/b` is NOT a prefix of `/a/bc`.
     * We normalize with `absoluteFile.normalize()` (NOT `canonicalPath`) — we do not resolve
     * symlinks; git already hands us real paths and resolving would defeat the relativity anchor.
     */
    fun shorten(path: String, base: File?, home: File = File(System.getProperty("user.home"))): String {
        val p = File(path).absoluteFile.normalize()

        if (base != null) {
            val b = base.absoluteFile.normalize()
            relativize(p, b)?.let { return it }
        }
        relativize(p, home.absoluteFile.normalize())?.let { rest ->
            return if (rest == ".") "~" else "~/$rest"
        }
        return p.path
    }

    /**
     * Returns [p] expressed relative to [anchor] on a segment boundary, or null if [p] is not
     * inside (or equal to) [anchor]. `p == anchor` → "."; otherwise the relative path with no
     * leading "./".
     */
    private fun relativize(p: File, anchor: File): String? {
        if (p == anchor) return "."
        val prefix = anchor.path.let { if (it.endsWith(File.separator)) it else it + File.separator }
        val pp = p.path
        if (!pp.startsWith(prefix)) return null
        return pp.substring(prefix.length)
    }

    /**
     * Truncation preserving the TAIL: `…/repo/worktree`. A string already within [maxWidth]
     * is returned unchanged. `maxWidth <= 1` → just the ellipsis.
     *
     * We take the last `maxWidth-1` chars and, if they still contain a `/`, drop everything up
     * to and including the first `/` so the result reads `…/full-segment`, not `…ent/xyz`.
     */
    fun truncateTail(text: String, maxWidth: Int): String {
        if (text.length <= maxWidth) return text
        if (maxWidth <= 1) return ELLIPSIS
        var tail = text.takeLast(maxWidth - 1)
        val slash = tail.indexOf('/')
        if (slash >= 0) tail = tail.substring(slash) // keep the leading '/', drop the partial segment
        return ELLIPSIS + tail
    }

    /**
     * Truncation preserving the HEAD: `aiGitWorktreeMana…`. For repo names and branches, whose
     * identity reads from the start. A string already within [maxWidth] is returned unchanged.
     * `maxWidth <= 1` → just the ellipsis.
     */
    fun truncateHead(text: String, maxWidth: Int): String {
        if (text.length <= maxWidth) return text
        if (maxWidth <= 1) return ELLIPSIS
        return text.take(maxWidth - 1) + ELLIPSIS
    }
}
