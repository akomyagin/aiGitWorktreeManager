package dev.alkom.gwm.git

/**
 * How far a worktree's branch has diverged from its configured upstream (Этап 8):
 * [ahead] = local commits not yet on the upstream, [behind] = upstream commits not yet local.
 *
 * A `null` [Worktree.aheadBehind] means "no upstream configured" (a common, non-error case:
 * detached HEAD, a purely local branch) — distinct from `AheadBehind(0, 0)`, which means the
 * branch tracks an upstream and is exactly in sync.
 */
data class AheadBehind(val ahead: Int, val behind: Int)

/**
 * Parses the stdout of `git rev-list --left-right --count HEAD...<branch>@{upstream}` into
 * an [AheadBehind].
 *
 * **Side semantics (verified empirically against a real repo, plan §Р3/§10 — easy to invert):**
 * with the range written `HEAD...<upstream>`, git prints `<left>\t<right>` where the LEFT count
 * is commits reachable from HEAD but not the upstream = **ahead**, and the RIGHT count is commits
 * reachable from the upstream but not HEAD = **behind**. So the two tab-separated integers map to
 * `AheadBehind(ahead = left, behind = right)`.
 *
 * Returns `null` for empty or malformed input (e.g. a git failure whose stdout is blank, or a line
 * that isn't two integers) — the caller already treats "no upstream" (git exit != 0) as `null`, and
 * a garbled success is handled the same defensive way rather than throwing.
 */
fun parseAheadBehind(revListStdout: String): AheadBehind? {
    val line = revListStdout.trim().lineSequence().firstOrNull { it.isNotBlank() } ?: return null
    // `--count` output is strictly tab-separated (`<int>\t<int>`) — splitting on ONLY '\t' means
    // an unexpected format (e.g. some future git printing "ahead N behind M") falls through to the
    // size-check below and returns null, instead of a looser split mis-parsing the wrong tokens.
    val parts = line.trim().split('\t').filter { it.isNotBlank() }
    if (parts.size != 2) return null
    val ahead = parts[0].toIntOrNull() ?: return null
    val behind = parts[1].toIntOrNull() ?: return null
    return AheadBehind(ahead = ahead, behind = behind)
}
