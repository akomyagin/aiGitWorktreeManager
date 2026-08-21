package dev.alkom.gwm

import dev.alkom.gwm.config.WorktreePathTemplate
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Worktree path template rendering (plan §6, cases 27-29). Pure — no filesystem. */
class WorktreePathTemplateTest {

    private val parent = File("/home/u/projects")

    @Test // 27
    fun `null template reproduces the default parent-repo-branch layout`() {
        val out = WorktreePathTemplate.render(null, parent, "myrepo", "feature")
        assertEquals(File("/home/u/projects/myrepo-feature"), out)
    }

    @Test // 28
    fun `branch slashes are flattened to dashes`() {
        val out = WorktreePathTemplate.render(null, parent, "myrepo", "feature/foo/bar")
        assertEquals(File("/home/u/projects/myrepo-feature-foo-bar"), out)
    }

    @Test // 28b
    fun `leading and trailing slashes in branch are trimmed`() {
        val out = WorktreePathTemplate.render(null, parent, "myrepo", "/feature/")
        assertEquals(File("/home/u/projects/myrepo-feature"), out)
    }

    @Test // 29
    fun `custom template substitutes all placeholders`() {
        val out = WorktreePathTemplate.render(
            "{parent}/worktrees/{repo}/{branch}",
            parent,
            "myrepo",
            "feature/foo",
        )
        assertEquals(File("/home/u/projects/worktrees/myrepo/feature-foo"), out)
    }

    @Test // 29b
    fun `unknown placeholder is left verbatim, not a crash`() {
        val out = WorktreePathTemplate.render("{parent}/{unknown}-{branch}", parent, "myrepo", "b")
        assertEquals(File("/home/u/projects/{unknown}-b"), out)
    }
}
