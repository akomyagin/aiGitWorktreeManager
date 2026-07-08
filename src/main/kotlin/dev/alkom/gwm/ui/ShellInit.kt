package dev.alkom.gwm.ui

/**
 * Generates the shell-function wrapper a user installs once (via `eval "$(gwm shell-init)"`)
 * so that `gwm cd <fuzzy>` actually changes the directory of the *parent* shell.
 *
 * WHY this exists (POSIX limitation): a child process can never change its parent shell's
 * working directory — `gwm` itself can only ever print a path. The wrapper closes that gap:
 * it runs `gwm --print-path` in a command substitution and does the `cd` from inside the
 * shell function, which *is* the parent shell (see docs/TECHNICAL_PLAN.md §5). `gwm` only
 * PRINTS this snippet — it never edits the user's dotfiles itself.
 *
 * WHY the `path=$(...) && cd "$path"` guard is load-bearing: without checking gwm's exit
 * status first, a failed lookup (`--print-path` errors → empty stdout) would make a bare
 * `cd "$(gwm --print-path bad)"` run `cd ""`, which is a no-op in bash but `cd` with no
 * argument in some shells jumps to $HOME. Assigning to a local and gating the `cd` on
 * gwm's success (`&&`) means a failed lookup leaves you exactly where you were. This is the
 * single most important property of the generated function.
 *
 * Pure string generation — no I/O — so it is fully unit-testable.
 */
object ShellInit {

    /**
     * The `gwm` shell function. It shadows the real binary and intercepts the `cd`
     * subcommand: `gwm cd <fuzzy>` resolves a path and `cd`s to it, while every other
     * invocation (`gwm list`, `gwm scan`, ...) is passed straight through to the real
     * binary via `command gwm`.
     *
     * The `&&` between the assignment and the `cd` is deliberate and must not be
     * simplified away — see the class doc: it prevents `cd`-ing anywhere when the lookup
     * fails, so a mistyped name never silently drops you in the wrong directory or $HOME.
     */
    fun snippet(): String =
        """
        # gwm shell integration — added via: eval "${'$'}(gwm shell-init)"
        # Lets `gwm cd <fuzzy>` change the current shell's directory. A child process
        # can't cd its parent shell (POSIX), so the cd happens here, in the shell.
        gwm() {
          if [ "${'$'}1" = "cd" ]; then
            shift
            local dir
            # Only cd on success: if gwm errors (no match / ambiguous), ${'$'}dir stays unset
            # and the `&&` short-circuits, leaving you where you are — never cd to "" / ${'$'}HOME.
            dir="${'$'}(command gwm --print-path "${'$'}@")" && [ -n "${'$'}dir" ] && cd "${'$'}dir"
          else
            command gwm "${'$'}@"
          fi
        }
        """.trimIndent()
}
