package dev.alkom.gwm

import dev.alkom.gwm.git.GitResult
import dev.alkom.gwm.git.GitRunner
import dev.alkom.gwm.git.WorktreeService
import dev.alkom.gwm.git.WorktreeService.RemoveStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [WorktreeService] create/remove logic driven by a fake [GitRunner].
 * No real git is invoked — the fake records commands and returns canned results,
 * so we can assert on exactly which `git` args the service constructs and how it
 * branches on dirty state / exit codes.
 */
class WorktreeServiceUnitTest {

    /** Records every invocation and replies from a queue keyed by the leading subcommand. */
    private class FakeGit(
        private val responder: (List<String>) -> GitResult,
    ) : GitRunner {
        val calls = mutableListOf<List<String>>()
        override fun invoke(dir: File, args: List<String>): GitResult {
            calls.add(args)
            return responder(args)
        }
    }

    private val repo = File("/repo")

    private fun mainOnlyPorcelain() = """
        worktree /repo
        HEAD abc123
        branch refs/heads/main
    """.trimIndent()

    private fun twoWorktreePorcelain() = """
        worktree /repo
        HEAD abc123
        branch refs/heads/main

        worktree /repo-feature
        HEAD def456
        branch refs/heads/feature
    """.trimIndent()

    @Test
    fun `add builds worktree add with new branch`() {
        val fake = FakeGit { GitResult(0, "Preparing worktree", "") }
        val service = WorktreeService(repo, fake)

        val res = service.add(File("/repo-x"), newBranch = "feature/x", baseRef = "main")

        assertTrue(res.ok)
        assertEquals(
            listOf("worktree", "add", "-b", "feature/x", "/repo-x", "main"),
            fake.calls.single(),
        )
    }

    @Test
    fun `add without new branch checks out existing ref`() {
        val fake = FakeGit { GitResult(0, "", "") }
        val service = WorktreeService(repo, fake)

        service.add(File("/repo-x"), newBranch = null, baseRef = "origin/dev")

        assertEquals(
            listOf("worktree", "add", "/repo-x", "origin/dev"),
            fake.calls.single(),
        )
    }

    @Test
    fun `defaultWorktreePath is a sibling named repo-branch with slashes flattened`() {
        val fake = FakeGit { args ->
            if (args.take(2) == listOf("worktree", "list")) {
                GitResult(0, mainOnlyPorcelain(), "")
            } else {
                GitResult(0, "", "")
            }
        }
        val service = WorktreeService(repo, fake)

        val path = service.defaultWorktreePath("feature/login")

        assertEquals(File("/repo-feature-login").absolutePath, path.absolutePath)
    }

    @Test
    fun `safeRemove returns NOT_FOUND when nothing matches`() {
        val fake = FakeGit { GitResult(0, mainOnlyPorcelain(), "") }
        val service = WorktreeService(repo, fake)

        val outcome = service.safeRemove("/does-not-exist")

        assertEquals(RemoveStatus.NOT_FOUND, outcome.status)
    }

    @Test
    fun `safeRemove blocks a dirty worktree when force is false`() {
        val fake = FakeGit { args ->
            when {
                args.take(2) == listOf("worktree", "list") -> GitResult(0, twoWorktreePorcelain(), "")
                // status --porcelain returns non-blank => dirty
                args.firstOrNull() == "status" -> GitResult(0, " M file.txt", "")
                else -> GitResult(0, "", "")
            }
        }
        val service = WorktreeService(repo, fake)

        val outcome = service.safeRemove("feature", force = false)

        assertEquals(RemoveStatus.BLOCKED_DIRTY, outcome.status)
        // Must NOT have run `worktree remove`.
        assertTrue(fake.calls.none { it.take(2) == listOf("worktree", "remove") })
    }

    @Test
    fun `safeRemove force deletes a dirty worktree with --force flag`() {
        val fake = FakeGit { args ->
            when {
                args.take(2) == listOf("worktree", "list") -> GitResult(0, twoWorktreePorcelain(), "")
                args.firstOrNull() == "status" -> GitResult(0, " M file.txt", "")
                else -> GitResult(0, "", "")
            }
        }
        val service = WorktreeService(repo, fake)

        val outcome = service.safeRemove("feature", force = true)

        assertEquals(RemoveStatus.REMOVED, outcome.status)
        val removeCall = fake.calls.single { it.take(2) == listOf("worktree", "remove") }
        assertTrue("--force" in removeCall)
        assertTrue("/repo-feature" in removeCall)
    }

    @Test
    fun `safeRemove removes a clean worktree without force`() {
        val fake = FakeGit { args ->
            when {
                args.take(2) == listOf("worktree", "list") -> GitResult(0, twoWorktreePorcelain(), "")
                args.firstOrNull() == "status" -> GitResult(0, "", "") // clean
                else -> GitResult(0, "", "")
            }
        }
        val service = WorktreeService(repo, fake)

        val outcome = service.safeRemove("/repo-feature", force = false)

        assertEquals(RemoveStatus.REMOVED, outcome.status)
        val removeCall = fake.calls.single { it.take(2) == listOf("worktree", "remove") }
        assertTrue("--force" !in removeCall)
    }

    @Test
    fun `safeRemove surfaces git failure as GIT_ERROR`() {
        val fake = FakeGit { args ->
            when {
                args.take(2) == listOf("worktree", "list") -> GitResult(0, twoWorktreePorcelain(), "")
                args.firstOrNull() == "status" -> GitResult(0, "", "")
                args.take(2) == listOf("worktree", "remove") -> GitResult(1, "", "fatal: boom")
                else -> GitResult(0, "", "")
            }
        }
        val service = WorktreeService(repo, fake)

        val outcome = service.safeRemove("/repo-feature", force = false)

        assertEquals(RemoveStatus.GIT_ERROR, outcome.status)
        assertEquals("fatal: boom", outcome.result?.stderr)
    }

    @Test
    fun `findWorktree matches by branch name and by path`() {
        val fake = FakeGit { GitResult(0, twoWorktreePorcelain(), "") }
        val service = WorktreeService(repo, fake)

        assertEquals("/repo-feature", service.findWorktree("feature")?.path)
        assertEquals("/repo-feature", service.findWorktree("/repo-feature")?.path)
        assertEquals(null, service.findWorktree("nope"))
    }
}
