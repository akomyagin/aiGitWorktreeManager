package dev.alkom.gwm

import dev.alkom.gwm.git.AheadBehind
import dev.alkom.gwm.git.GitResult
import dev.alkom.gwm.git.GitRunner
import dev.alkom.gwm.git.Worktree
import dev.alkom.gwm.git.WorktreeService
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [WorktreeService.withAheadBehindAndAge] driven by a fake [GitRunner]
 * (Этап 8, plan §6 cases 46-50). No real git: the fake decides per-command whether an
 * upstream exists, so we exercise the "no upstream = null, not an error" contract exactly.
 */
class WorktreeServiceAheadBehindTest {

    private class FakeGit(private val responder: (List<String>) -> GitResult) : GitRunner {
        override fun invoke(dir: File, args: List<String>): GitResult = responder(args)
    }

    private val repo = File("/repo")

    private fun wt(branch: String?, detached: Boolean = false, bare: Boolean = false) =
        Worktree(path = "/repo", head = "abc", branch = branch, isDetached = detached, isBare = bare)

    @Test // 46 — upstream configured → fields populated
    fun `upstream present fills ahead-behind and age`() {
        val fake = FakeGit { args ->
            when (args.firstOrNull()) {
                "rev-list" -> GitResult(0, "3\t2\n", "")
                "log" -> GitResult(0, "1700000000\n", "")
                else -> GitResult(0, "", "")
            }
        }
        val result = WorktreeService(repo, fake).withAheadBehindAndAge(listOf(wt("feature")))
        assertEquals(AheadBehind(ahead = 3, behind = 2), result.single().aheadBehind)
        assertEquals(1700000000L, result.single().lastCommitEpoch)
    }

    @Test // 47 — no upstream (git exit != 0) → aheadBehind null, NO exception
    fun `no upstream yields null ahead-behind without throwing`() {
        var revListCalled = false
        val fake = FakeGit { args ->
            when (args.firstOrNull()) {
                // The narrowed no-upstream check (hasUpstream) fails first — rev-list must never run.
                "rev-parse" -> GitResult(128, "", "fatal: no upstream configured")
                "rev-list" -> { revListCalled = true; GitResult(0, "0\t0\n", "") }
                "log" -> GitResult(0, "1700000000\n", "")
                else -> GitResult(0, "", "")
            }
        }
        val result = WorktreeService(repo, fake).withAheadBehindAndAge(listOf(wt("local-only")))
        assertNull(result.single().aheadBehind, "no upstream must be null, not an error")
        assertTrue(!revListCalled, "no upstream (per hasUpstream) must short-circuit before rev-list")
        // Age is independent and still filled.
        assertEquals(1700000000L, result.single().lastCommitEpoch)
    }

    @Test // regression: upstream exists but rev-list itself fails for an unrelated reason
    fun `upstream exists but rev-list fails - still null, not an exception`() {
        val fake = FakeGit { args ->
            when (args.firstOrNull()) {
                "rev-parse" -> GitResult(0, "origin/feature\n", "") // hasUpstream says yes
                "rev-list" -> GitResult(128, "", "fatal: bad object deadbeef") // but rev-list itself fails
                "log" -> GitResult(0, "1700000000\n", "")
                else -> GitResult(0, "", "")
            }
        }
        val result = WorktreeService(repo, fake).withAheadBehindAndAge(listOf(wt("feature")))
        assertNull(result.single().aheadBehind, "a rev-list failure past the upstream check must still be null, not throw")
    }

    @Test // 48 — detached / no branch → ahead-behind null (no git call needed)
    fun `detached worktree yields null ahead-behind`() {
        var revListCalled = false
        val fake = FakeGit { args ->
            if (args.firstOrNull() == "rev-list") revListCalled = true
            if (args.firstOrNull() == "log") GitResult(0, "1700000000\n", "") else GitResult(0, "", "")
        }
        val result = WorktreeService(repo, fake).withAheadBehindAndAge(listOf(wt(branch = null, detached = true)))
        assertNull(result.single().aheadBehind)
        assertTrue(!revListCalled, "no branch → must not even run rev-list")
    }

    @Test // 49 — %ct parsed; no commits (log fails) → lastCommitEpoch null
    fun `age null when log fails`() {
        val fake = FakeGit { args ->
            when (args.firstOrNull()) {
                "rev-list" -> GitResult(0, "0\t0\n", "")
                "log" -> GitResult(128, "", "fatal: your current branch does not have any commits yet")
                else -> GitResult(0, "", "")
            }
        }
        val result = WorktreeService(repo, fake).withAheadBehindAndAge(listOf(wt("feature")))
        assertNull(result.single().lastCommitEpoch)
        assertEquals(AheadBehind(0, 0), result.single().aheadBehind)
    }

    @Test // 50 — the method never throws on no-upstream (a whole list stays intact)
    fun `mixed list with and without upstream all resolve without throwing`() {
        val fake = FakeGit { args ->
            when {
                args.firstOrNull() == "rev-parse" && args.any { it == "tracked@{upstream}" } ->
                    GitResult(0, "origin/tracked\n", "")
                args.firstOrNull() == "rev-parse" -> GitResult(128, "", "no upstream")
                args.firstOrNull() == "rev-list" && args.any { it == "HEAD...tracked@{upstream}" } ->
                    GitResult(0, "1\t0\n", "")
                args.firstOrNull() == "log" -> GitResult(0, "1700000000", "")
                else -> GitResult(0, "", "")
            }
        }
        val result = WorktreeService(repo, fake).withAheadBehindAndAge(
            listOf(wt("tracked"), wt("untracked")),
        )
        assertEquals(AheadBehind(1, 0), result[0].aheadBehind)
        assertNull(result[1].aheadBehind)
    }

    @Test // bare worktree → both null, no git call
    fun `bare worktree yields both null`() {
        var anyCall = false
        val fake = FakeGit { _ -> anyCall = true; GitResult(0, "", "") }
        val result = WorktreeService(repo, fake).withAheadBehindAndAge(listOf(wt(branch = null, bare = true)))
        assertNull(result.single().aheadBehind)
        assertNull(result.single().lastCommitEpoch)
        assertTrue(!anyCall, "bare → no git subprocess")
    }
}
