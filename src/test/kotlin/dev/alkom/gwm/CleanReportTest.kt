package dev.alkom.gwm

import dev.alkom.gwm.git.OrphanStatus
import dev.alkom.gwm.git.Worktree
import dev.alkom.gwm.scan.AggregatedWorktree
import dev.alkom.gwm.scan.CleanCandidate
import dev.alkom.gwm.ui.CleanReport
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Unit tests for the pure text formatting of `gwm clean` (Этап 10, plan §Тест-кейсы / Юнит). */
class CleanReportTest {

    private fun candidate(
        repo: String,
        branch: String,
        dirty: Boolean,
        reasons: List<String>,
        path: String,
    ): CleanCandidate =
        CleanCandidate(
            aggregated = AggregatedWorktree(
                repo = repo,
                worktree = Worktree(path = path, head = "abc", branch = branch, dirty = dirty, orphan = OrphanStatus()),
            ),
            dirty = dirty,
            reasons = reasons,
        )

    @Test
    fun `candidateLine shows repo branch glyph reasons and shortened path`() {
        val base = File("/portfolio")
        val line = CleanReport.candidateLine(
            candidate("myrepo", "feature/x", dirty = false, reasons = listOf("no-upstream"), path = "/portfolio/myrepo/feature-x"),
            base,
        )
        assertTrue("myrepo" in line, line)
        assertTrue("feature/x" in line, line)
        assertTrue("✓ clean" in line, line)
        assertTrue("no-upstream" in line, line)
        // Path anchored to base → relative.
        assertTrue("myrepo/feature-x" in line, line)
    }

    @Test
    fun `candidateLine marks dirty worktrees`() {
        val line = CleanReport.candidateLine(
            candidate("r", "b", dirty = true, reasons = listOf("merged"), path = "/x/r/b"),
            base = null,
        )
        assertTrue("● dirty" in line, line)
        assertTrue("merged" in line, line)
    }

    @Test
    fun `summary places numbers and words correctly`() {
        assertTrue(CleanReport.summary(2, 1, 0) == "Удалено: 2, пропущено (dirty): 1, ошибок: 0")
        assertTrue(CleanReport.summary(0, 0, 1) == "Удалено: 0, пропущено (dirty): 0, ошибок: 1")
    }
}
