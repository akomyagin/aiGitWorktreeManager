package dev.alkom.gwm.config

import com.github.ajalt.clikt.core.CliktError
import dev.alkom.gwm.scan.RepoScanner
import java.io.File

/**
 * The parsed gwm configuration (`~/.config/gwm/config.toml`). EVERY field is optional — an
 * absent file means every field is null/default and the tool behaves exactly as it did before
 * the config existed (plan §Р2, point 2). The config only ever *lowers* into fallbacks; an
 * explicit CLI flag or `$GWM_ROOT` always wins.
 *
 * @param roots                scan-root candidates; on Этап 8 only the first EXISTING one is used
 *                             (single-root scan), but the array shape is fixed now so the config
 *                             doesn't have to change when multi-root lands (see [primaryRoot])
 * @param worktreePathTemplate template for `gwm create`'s default path, or null for the built-in
 *                             `{parent}/{repo}-{branch}` (see [WorktreePathTemplate])
 * @param colors               semantic status color roles, resolved in [Colors]
 */
data class GwmConfig(
    val roots: List<String> = emptyList(),
    val worktreePathTemplate: String? = null,
    val colors: ColorScheme = ColorScheme.DEFAULT,
) {
    /**
     * The first EXISTING directory from [roots] (with a leading `~` expanded), or null when none
     * of them exist / the list is empty. "First existing" — not "first" — so a stale entry in a
     * shared config doesn't shadow a later valid root (plan §6 case 16). Returns the expanded
     * absolute-ish path string; [RepoScanner.resolveRoot] does the final absolute resolution.
     */
    fun primaryRoot(home: String = System.getProperty("user.home")): String? =
        roots.asSequence()
            .map { RepoScanner.expandTilde(it, home) }
            .firstOrNull { it.isNotBlank() && File(it).isDirectory }

    companion object {
        val EMPTY = GwmConfig()

        /**
         * The default config path: `$XDG_CONFIG_HOME/gwm/config.toml` when the env var is set,
         * else `~/.config/gwm/config.toml`.
         */
        fun defaultPath(
            env: (String) -> String? = System::getenv,
            home: String = System.getProperty("user.home"),
        ): File {
            val xdg = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            val base = if (xdg != null) File(xdg) else File(home, ".config")
            return File(base, "gwm/config.toml")
        }

        /**
         * Reads and parses the config. A MISSING file → [EMPTY] (config is optional, not an error).
         * A file that exists but is unreadable or contains TOML outside the supported subset →
         * [CliktError] with the path in the message, so the process exits non-zero with a clear
         * diagnostic rather than a stack trace (SKILL "Exit-коды"). An empty or partial file yields
         * defaults for the unset fields.
         */
        fun load(path: File = defaultPath()): GwmConfig {
            if (!path.exists()) return EMPTY
            val text = try {
                path.readText()
            } catch (e: Exception) {
                throw CliktError("Не удалось прочитать конфиг ${path.path}: ${e.message}")
            }
            val parsed = try {
                TomlLite.parse(text)
            } catch (e: TomlParseException) {
                throw CliktError("Ошибка в конфиге ${path.path}: ${e.message}")
            }
            return from(parsed)
        }

        /** Pure mapping [ParsedToml] → [GwmConfig], with no filesystem access (unit-testable). */
        fun from(parsed: ParsedToml): GwmConfig = GwmConfig(
            roots = parsed.stringList("roots") ?: emptyList(),
            // Blank ("" or whitespace) is treated as unset, not a literal template: an empty
            // template would render to File("") — the process CWD — silently misplacing every
            // `gwm create` (found in review).
            worktreePathTemplate = parsed.string("worktree-path-template")?.takeIf { it.isNotBlank() },
            colors = ColorScheme(
                clean = parsed.string("colors.clean"),
                dirty = parsed.string("colors.dirty"),
                muted = parsed.string("colors.muted"),
            ),
        )
    }
}

/**
 * The three semantic status color roles the config can override. Each is a color NAME (resolved
 * by [Colors]) or null to keep the built-in default. All-null = [DEFAULT] = the hard-coded Этап 7
 * scheme, so a config without a `[colors]` section changes nothing.
 */
data class ColorScheme(
    val clean: String? = null,
    val dirty: String? = null,
    val muted: String? = null,
) {
    companion object {
        val DEFAULT = ColorScheme(null, null, null)
    }
}
