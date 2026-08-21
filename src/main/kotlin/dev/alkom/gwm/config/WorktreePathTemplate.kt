package dev.alkom.gwm.config

import java.io.File

/**
 * Renders the path of a new worktree from a template with `{parent}`, `{repo}` and `{branch}`
 * placeholders — the config's `worktree-path-template` (plan §Р2 / point 2).
 *
 * The DEFAULT (a null template) reproduces the historic [dev.alkom.gwm.git.WorktreeService]
 * behaviour exactly: `<parent>/<repo>-<branch>`, i.e. a sibling of the primary checkout named
 * `<repo>-<branch>`. A custom template lets the user pick another layout, e.g.
 * `"{parent}/worktrees/{repo}/{branch}"`.
 *
 * Branch handling matches the old code: the branch is trimmed, its leading/trailing `/` are
 * stripped and any remaining `/` flattened to `-`, so `feature/foo` never creates nested dirs
 * inside a `{repo}-{branch}` segment. Unknown placeholders are left verbatim (a forgiving
 * choice: a typo like `{parnt}` yields a visibly wrong but harmless literal path the user will
 * notice, rather than a crash mid-create).
 *
 * Pure function — no filesystem access — so it is fully unit-testable.
 */
object WorktreePathTemplate {

    const val DEFAULT = "{parent}/{repo}-{branch}"

    /**
     * @param template the raw template, or null for [DEFAULT]
     * @param parent   the primary checkout's parent directory
     * @param repo     the repository (primary checkout) directory name
     * @param branch   the branch name (slashes flattened as described above)
     */
    fun render(template: String?, parent: File, repo: String, branch: String): File {
        val safeBranch = branch.trim().trim('/').replace('/', '-')
        val rendered = (template ?: DEFAULT)
            .replace("{parent}", parent.path)
            .replace("{repo}", repo)
            .replace("{branch}", safeBranch)
        return File(rendered)
    }
}
