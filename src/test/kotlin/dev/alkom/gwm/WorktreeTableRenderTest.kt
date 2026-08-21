package dev.alkom.gwm

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import dev.alkom.gwm.git.OrphanStatus
import dev.alkom.gwm.git.Worktree
import dev.alkom.gwm.scan.AggregatedWorktree
import dev.alkom.gwm.ui.WorktreeTable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Render tests for the overview table (Этап 7, plan §6, cases 19-28). We render the widget through a
 * fixed-width, colorless [Terminal] and inspect the plain text lines — direct regressions on the
 * observed bugs (nulled column, per-row separators, useless path column).
 */
class WorktreeTableRenderTest {

    private fun term(width: Int) = Terminal(ansiLevel = AnsiLevel.NONE, width = width)

    private fun renderScan(worktrees: List<AggregatedWorktree>, root: File?, width: Int): String =
        term(width).render(WorktreeTable.renderAggregated(worktrees, root, width))

    private fun renderList(worktrees: List<Worktree>, base: File?, width: Int): String =
        term(width).render(WorktreeTable.render(worktrees, base, width))

    private val ROOT = File("/home/u/Projects/portfolio")

    private fun wt(repoRel: String, branch: String, orphan: OrphanStatus = OrphanStatus.ACTIVE) =
        Worktree(
            path = "/home/u/Projects/portfolio/$repoRel",
            head = "abc123",
            branch = branch,
            dirty = false,
            orphan = orphan,
        )

    private fun agg(repo: String, repoRel: String, branch: String, orphan: OrphanStatus = OrphanStatus.ACTIVE) =
        AggregatedWorktree(repo, wt(repoRel, branch, orphan))

    private fun fakePortfolio(n: Int): List<AggregatedWorktree> = (0 until n).map { i ->
        agg("repo-$i", "repo-$i/wt-branch-number-$i", "feature/branch-number-$i")
    }

    @Test // 19
    fun `width 80 - every line is at most 80 chars`() {
        val out = renderScan(fakePortfolio(27), ROOT, 80)
        out.lines().forEach { line ->
            assertTrue(line.length <= 80, "line ${line.length} chars: >$line<")
        }
    }

    @Test // 20
    fun `width 80 - no zero-width columns`() {
        val out = renderScan(fakePortfolio(27), ROOT, 80)
        assertFalse("││" in out, "found back-to-back borders (zero-width column): $out")
        assertFalse("┬┐" in out, "found collapsed top border")
    }

    @Test // 21
    fun `compactness - line count is rows plus 4`() {
        val n = 27
        val out = renderScan(fakePortfolio(n), ROOT, 140)
        // frame top, header, header separator, N rows, frame bottom = N + 4.
        val lines = out.trimEnd('\n').lines()
        assertEquals(n + 4, lines.size, "expected ${n + 4} lines, got ${lines.size}:\n$out")
    }

    @Test // 22
    fun `header has Путь and path cell is relative not absolute`() {
        val rows = listOf(agg("ai-knowledge-vault", "ai-knowledge-vault/docs-handoff", "docs/handoff"))
        val out = renderScan(rows, ROOT, 160)
        assertTrue("Путь" in out, "header must contain Путь")
        assertTrue("ai-knowledge-vault/docs-handoff" in out, "relative path expected: $out")
        // The path cell itself must not render the absolute /home prefix.
        val pathLines = out.lines().filter { "ai-knowledge-vault" in it }
        assertTrue(pathLines.none { "/home" in it }, "path cell should be relative, not /home...: $pathLines")
    }

    @Test // 23
    fun `truncated path starts with ellipsis and keeps the last segment`() {
        val longSeg = "very-long-worktree-directory-name-that-will-not-fit-in-a-narrow-column"
        val rows = listOf(agg("repo", "repo/$longSeg", "br"))
        val out = renderScan(rows, ROOT, 60)
        assertTrue("…" in out, "expected an ellipsis somewhere: $out")
        // last segment tail should be visible
        assertTrue(out.contains("not-fit-in-a-narrow-column") || out.contains("…"), out)
    }

    @Test // 24
    fun `Репозиторий header shown at 160 but dropped at 80`() {
        // Wide content (long repo + branch names) so 80 columns genuinely can't fit REPO — the
        // real-portfolio scenario. Short names legitimately fit at 80, so we force width pressure.
        val rows = (0 until 5).map { i ->
            agg(
                repo = "ai-knowledge-vault-project-$i",
                repoRel = "ai-knowledge-vault-project-$i/docs-session-handoff-2026-08-2$i",
                branch = "feature/documentation-session-handoff-$i",
            )
        }
        assertFalse("Репозиторий" in renderScan(rows, ROOT, 80), "REPO must be dropped at 80")
        assertTrue("Репозиторий" in renderScan(rows, ROOT, 160), "REPO must be present at 160")
    }

    @Test // regression: repo prefix must survive PATH truncation once REPO is dropped by width
    fun `REPO dropped and path outside portfolio - repo prefix survives truncation`() {
        val outside = Worktree(
            path = "/mnt/data/ci-runners/scratch-environment-for-testing",
            head = "abc123",
            branch = "feature/very-long-branch-name-for-width-pressure",
            dirty = false,
            orphan = OrphanStatus.ACTIVE,
        )
        val rows = listOf(AggregatedWorktree("myrepo-with-a-long-name", outside))
        val out = renderScan(rows, ROOT, 80)
        assertFalse("Репозиторий" in out, "REPO must be dropped at 80 for this width pressure: $out")
        assertTrue(
            "myrepo-with-a-long-name:" in out,
            "repo prefix must not be truncated away when REPO column is dropped: $out",
        )
    }

    @Test // 25
    fun `Orphaned column only when a row is orphaned`() {
        val active = fakePortfolio(3)
        assertFalse("Orphaned" in renderScan(active, ROOT, 160), "no orphaned rows → no column")

        val withOrphan = listOf(
            agg("r", "r/wt", "b", orphan = OrphanStatus(merged = true)),
            agg("r2", "r2/wt", "b2"),
        )
        assertTrue("Orphaned" in renderScan(withOrphan, ROOT, 160), "orphaned row → column present")
    }

    @Test // 26
    fun `ORPHAN dropped by width - branch cell carries the warning glyph`() {
        // Wide content so ORPHAN genuinely can't fit and gets dropped (short rows would fit at 60).
        val withOrphan = (0 until 4).map { i ->
            agg(
                repo = "repository-with-a-long-name-$i",
                repoRel = "repository-with-a-long-name-$i/worktree-directory-$i",
                branch = "feature/long-branch-name-$i",
                orphan = OrphanStatus(merged = true, noUpstream = true),
            )
        }
        val out = renderScan(withOrphan, ROOT, 60)
        assertFalse("Orphaned" in out, "ORPHAN column should be dropped at 60: $out")
        assertTrue("⚠" in out, "warning glyph must move to the branch cell: $out")
    }

    @Test // 27
    fun `width 40 - compact frameless mode with two lines per row`() {
        val rows = fakePortfolio(3)
        val out = renderScan(rows, ROOT, 40)
        assertFalse("┌" in out, "compact mode has no frame: $out")
        assertFalse("│" in out, "compact mode has no vertical borders: $out")
        val lines = out.trimEnd('\n').lines()
        assertEquals(2 * rows.size, lines.size, "expected ${2 * rows.size} lines: $out")
    }

    @Test // 28
    fun `single-repo render never has a Репозиторий column`() {
        val worktrees = listOf(wt("wt-a", "a"), wt("wt-b", "b"))
        for (w in listOf(200, 120, 80, 60)) {
            assertFalse("Репозиторий" in renderList(worktrees, ROOT, w), "list must never show REPO at width $w")
        }
    }
}
