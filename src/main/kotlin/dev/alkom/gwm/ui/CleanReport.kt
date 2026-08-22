package dev.alkom.gwm.ui

import dev.alkom.gwm.scan.CleanCandidate
import java.io.File

/**
 * Pure, colorless text formatting for `gwm clean` (Этап 10). Like [dev.alkom.gwm.scan.formatMissingRootsWarning],
 * these functions return PLAIN strings — any Mordant coloring is applied by the command, so the
 * lines can be asserted by text in unit tests without ANSI noise.
 *
 * Path shortening reuses [PathDisplay.shorten] (same anchor logic as `scan`/`interactive`).
 */
object CleanReport {

    /**
     * One candidate line: `<repo>/<branch>  <status>  [<reasons>]  <path>`, where status is
     * `✓ clean` / `● dirty` and reasons is the comma-joined orphan hints. [base] anchors the path
     * (null → `~/…`/absolute), matching the `scan` table.
     */
    fun candidateLine(c: CleanCandidate, base: File?): String {
        val status = if (c.dirty) "● dirty" else "✓ clean"
        val reasons = if (c.reasons.isEmpty()) "" else c.reasons.joinToString(", ")
        val path = PathDisplay.shorten(c.path, base)
        return "  ${c.repo}/${c.label}  $status  [$reasons]  $path"
    }

    /** Final tally line: `Удалено: X, пропущено (dirty): Y, ошибок: Z`. */
    fun summary(removed: Int, skippedDirty: Int, errors: Int): String =
        "Удалено: $removed, пропущено (dirty): $skippedDirty, ошибок: $errors"
}
