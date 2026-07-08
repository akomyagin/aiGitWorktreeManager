package dev.alkom.gwm.git

/**
 * Parses the output of `git worktree list --porcelain`.
 *
 * The porcelain format is a sequence of records separated by blank lines. Each
 * record is a set of `key value` lines; boolean attributes appear as a bare key.
 * Example:
 * ```
 * worktree /home/user/repo
 * HEAD 1a2b3c...
 * branch refs/heads/main
 *
 * worktree /home/user/repo-feature
 * HEAD 4d5e6f...
 * branch refs/heads/feature/x
 * locked
 * ```
 *
 * We parse the stable, machine-readable `--porcelain` form on purpose: the plain
 * `git worktree list` output is column-aligned for humans and brittle to split.
 */
object WorktreeParser {

    fun parse(porcelain: String): List<Worktree> {
        val records = porcelain
            .split(Regex("\\r?\\n\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return records.mapIndexed { index, record ->
            var path = ""
            var head: String? = null
            var branch: String? = null
            var bare = false
            var detached = false
            var locked = false
            var prunable = false

            for (rawLine in record.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val key = line.substringBefore(' ')
                val value = line.substringAfter(' ', "").trim()
                when (key) {
                    "worktree" -> path = value
                    "HEAD" -> head = value.ifEmpty { null }
                    "branch" -> branch = value.removePrefix("refs/heads/").ifEmpty { null }
                    "bare" -> bare = true
                    "detached" -> detached = true
                    "locked" -> locked = true
                    "prunable" -> prunable = true
                }
            }

            Worktree(
                path = path,
                head = head,
                branch = branch,
                isBare = bare,
                isDetached = detached,
                isLocked = locked,
                isPrunable = prunable,
                // The first record is always the main (primary) worktree.
                isMain = index == 0,
            )
        }
    }
}
