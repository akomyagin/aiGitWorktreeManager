package dev.alkom.gwm.git

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Result of running a git subprocess.
 */
data class GitResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Thin wrapper around the `git` executable via [ProcessBuilder].
 *
 * We shell out to the user's real `git` rather than embedding JGit: worktree
 * semantics (locks, prune, orphaned detection) track the installed git exactly,
 * there is no JVM dependency to keep current, and the tool stays lightweight.
 * All commands run with an explicit working directory and a timeout so a hung
 * git process can never freeze the TUI.
 */
object GitCommand {
    private const val DEFAULT_TIMEOUT_SECONDS = 30L

    fun run(
        workingDir: File,
        vararg args: String,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): GitResult {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(workingDir)
            .redirectErrorStream(false)
            .start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return GitResult(exitCode = -1, stdout = "", stderr = "git timed out after ${timeoutSeconds}s")
        }

        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        return GitResult(process.exitValue(), out, err)
    }
}
