package dev.alkom.gwm

import dev.alkom.gwm.git.Worktree
import dev.alkom.gwm.scan.AggregatedWorktree
import dev.alkom.gwm.scan.WorktreeMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [WorktreeMatcher] — the pure fuzzy-resolution logic behind
 * `gwm --print-path <fuzzy>` / `gwm cd <fuzzy>` (Этап 6). No git, no TTY: we build
 * [AggregatedWorktree] fixtures by hand and assert the precedence rules.
 *
 * The properties under test are the ones that make the shell wrapper safe:
 *  - an EXACT branch/dir hit wins over substring matches (so `main` isn't "ambiguous"
 *    just because `main-x` also exists),
 *  - several loose matches with no exact hit → [WorktreeMatcher.Match.Ambiguous] (refuse
 *    to guess, never silently pick the first),
 *  - no match / blank query → [WorktreeMatcher.Match.None].
 */
class WorktreeMatcherTest {

    private fun wt(repo: String, path: String, branch: String?): AggregatedWorktree =
        AggregatedWorktree(repo, Worktree(path = path, head = "sha", branch = branch))

    private val portfolio = listOf(
        wt("alpha", "/projects/alpha", "main"),
        wt("alpha", "/projects/alpha-feature-login", "feature/login"),
        wt("beta", "/projects/beta", "main"),
        wt("beta", "/projects/beta-bugfix", "bugfix/crash"),
    )

    @Test
    fun `unique substring resolves to that worktree`() {
        val match = WorktreeMatcher.resolve(portfolio, "login")
        assertTrue(match is WorktreeMatcher.Match.Found)
        assertEquals("/projects/alpha-feature-login", match.worktree.worktree.path)
    }

    @Test
    fun `substring matches the directory name, not only the branch`() {
        // "bugfix" appears in both the branch ("bugfix/crash") and the dir ("beta-bugfix");
        // still a single worktree, so it must resolve.
        val match = WorktreeMatcher.resolve(portfolio, "bugfix")
        assertTrue(match is WorktreeMatcher.Match.Found)
        assertEquals("/projects/beta-bugfix", match.worktree.worktree.path)
    }

    @Test
    fun `matching is case-insensitive`() {
        val match = WorktreeMatcher.resolve(portfolio, "LOGIN")
        assertTrue(match is WorktreeMatcher.Match.Found)
        assertEquals("/projects/alpha-feature-login", match.worktree.worktree.path)
    }

    @Test
    fun `exact branch name wins even when it is a substring of others`() {
        // Two worktrees are named `main`; the token "main" is also a substring of nothing
        // else here. Exactness makes each `main` uniquely addressable per repo — but with
        // two identical exact hits across repos, the resolver must flag ambiguity rather
        // than pick one.
        val match = WorktreeMatcher.resolve(portfolio, "main")
        assertTrue(match is WorktreeMatcher.Match.Ambiguous)
        assertEquals(2, match.candidates.size)
    }

    @Test
    fun `exact hit beats a broader substring set`() {
        // "feature/login" is an exact branch; "feature" alone would substring-match only it
        // here, but we assert the exact path explicitly resolves to a single Found.
        val match = WorktreeMatcher.resolve(portfolio, "feature/login")
        assertTrue(match is WorktreeMatcher.Match.Found)
        assertEquals("/projects/alpha-feature-login", match.worktree.worktree.path)
    }

    @Test
    fun `an exact branch hit is not drowned out by other substring matches`() {
        // The query is an EXACT branch name of one worktree AND a substring of another's
        // branch/dir. Exact must win — the whole point of the precedence rule.
        val repos = listOf(
            wt("x", "/p/repo-release-candidate", "release-candidate"), // branch contains "release"
            wt("x", "/p/repo-release", "release"), // branch is exactly "release"
        )
        // Query "release": substring-matches both, but exactly matches only the branch
        // "release" → exact set size 1 → Found, unambiguously the second worktree.
        val match = WorktreeMatcher.resolve(repos, "release")
        assertTrue(match is WorktreeMatcher.Match.Found)
        assertEquals("/p/repo-release", match.worktree.worktree.path)
    }

    @Test
    fun `several loose matches with no exact hit are ambiguous, not first-wins`() {
        // "e" is a substring of many; with no exact match we must refuse to guess.
        val match = WorktreeMatcher.resolve(portfolio, "e")
        assertTrue(match is WorktreeMatcher.Match.Ambiguous)
        assertTrue(match.candidates.size > 1)
    }

    @Test
    fun `no match yields None`() {
        val match = WorktreeMatcher.resolve(portfolio, "zzzznope")
        assertTrue(match is WorktreeMatcher.Match.None)
        assertEquals("zzzznope", match.query)
    }

    @Test
    fun `blank query never matches`() {
        assertTrue(WorktreeMatcher.resolve(portfolio, "") is WorktreeMatcher.Match.None)
        assertTrue(WorktreeMatcher.resolve(portfolio, "   ") is WorktreeMatcher.Match.None)
    }

    @Test
    fun `detached and bare worktrees are reachable by directory name only`() {
        val repos = listOf(
            wt("r", "/p/r", "main"),
            AggregatedWorktree(
                "r",
                Worktree(path = "/p/r-detached", head = "sha", branch = null, isDetached = true),
            ),
        )
        val match = WorktreeMatcher.resolve(repos, "detached")
        assertTrue(match is WorktreeMatcher.Match.Found)
        assertEquals("/p/r-detached", match.worktree.worktree.path)
    }
}
