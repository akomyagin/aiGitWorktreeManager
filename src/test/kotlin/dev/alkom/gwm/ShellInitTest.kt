package dev.alkom.gwm

import dev.alkom.gwm.ui.ShellInit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the shell-function snippet emitted by `gwm shell-init` (Этап 6).
 *
 * We don't execute the shell here (that's exercised end-to-end by hand / the CLI); this
 * pins the load-bearing fragments so a future refactor can't silently drop the safety
 * guard. The single most important property is that a FAILED lookup never `cd`s anywhere —
 * i.e. the `cd` is gated on gwm's success, so `gwm cd <typo>` can't drop the user in $HOME.
 */
class ShellInitTest {

    private val snippet = ShellInit.snippet()

    @Test
    fun `defines a gwm shell function`() {
        assertTrue(snippet.contains("gwm() {"), "must define a gwm() shell function")
    }

    @Test
    fun `intercepts the cd subcommand and passes everything else through`() {
        assertTrue(snippet.contains("""[ "${'$'}1" = "cd" ]"""), "must branch on the cd subcommand")
        assertTrue(
            snippet.contains("""command gwm "${'$'}@""""),
            "non-cd invocations must be forwarded to the real binary via `command gwm`",
        )
    }

    @Test
    fun `calls print-path to resolve the target`() {
        assertTrue(
            snippet.contains("command gwm --print-path"),
            "cd must resolve the path via `gwm --print-path`",
        )
    }

    @Test
    fun `guards the cd on gwm success so a failed lookup never cd-s anywhere`() {
        // The whole point: assign to a local, then `&&` the cd on gwm's exit status AND a
        // non-empty result. Without this a failed/empty lookup could `cd ""` → $HOME.
        assertTrue(
            snippet.contains(
                """dir="${'$'}(command gwm --print-path "${'$'}@")" && """ +
                    """[ -n "${'$'}dir" ] && cd "${'$'}dir"""",
            ),
            "the cd must be guarded by gwm's success and a non-empty path — found:\n$snippet",
        )
    }

    @Test
    fun `never runs a bare cd of a command substitution`() {
        // A naive `cd "$(gwm ...)"` is the anti-pattern we must avoid: on empty output some
        // shells cd to $HOME. Assert that pattern is absent.
        assertFalse(
            snippet.contains("""cd "${'$'}(command gwm --print-path"""),
            "must not `cd \$(gwm ...)` directly — that risks cd-ing to \$HOME on failure",
        )
    }

    @Test
    fun `documents how to install it`() {
        assertTrue(
            snippet.contains("""eval "$(gwm shell-init)""""),
            "snippet should document the eval install line",
        )
    }
}
