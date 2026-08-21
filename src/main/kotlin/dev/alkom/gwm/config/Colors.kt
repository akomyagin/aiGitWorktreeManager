package dev.alkom.gwm.config

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyle

/**
 * Resolves a color NAME from the config (`[colors]` section) into a Mordant [TextStyle].
 *
 * Deliberately a tiny fixed table, not a general color parser: gwm exposes only a handful
 * of semantic status roles (clean/dirty/muted), and the value is a plain color name a user
 * would recognise (`green`, `yellow`, `red`, `cyan`, …). An unknown or null name falls back
 * to the caller-supplied role default — the config never crashes the tool, at most a warning
 * is emitted on the call side (plan §Р6).
 *
 * Pure function, no I/O, no Mordant terminal — fully unit-testable.
 */
object Colors {

    /**
     * Known color names → their Mordant [TextStyle]. `bright*` variants are preferred for the
     * status roles because that is what the hard-coded Этап 7 scheme used (`brightGreen` etc.),
     * so `"green"` reproduces the historic look. Both `gray` and the British `grey` are accepted.
     */
    private val table: Map<String, TextStyle> = mapOf(
        "green" to TextColors.brightGreen,
        "yellow" to TextColors.brightYellow,
        "red" to TextColors.brightRed,
        "blue" to TextColors.brightBlue,
        "cyan" to TextColors.brightCyan,
        "magenta" to TextColors.brightMagenta,
        "white" to TextColors.brightWhite,
        "gray" to TextColors.gray,
        "grey" to TextColors.gray,
    )

    /** Resolves [name] (case-insensitive, trimmed) to a style, or [fallback] when unknown/null. */
    fun resolve(name: String?, fallback: TextStyle): TextStyle =
        name?.trim()?.lowercase()?.let { table[it] } ?: fallback

    /** True when [name] maps to a known color (used by callers to decide whether to warn). */
    fun isKnown(name: String?): Boolean =
        name?.trim()?.lowercase()?.let { it in table } ?: false
}
