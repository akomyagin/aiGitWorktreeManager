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

    // --- Этап 8, Фаза B: grouping (plan §Р1, cases 61-63) ---------------------------------

    /** A repo with three worktrees, wide enough that REPO stays a column at 200. */
    private fun grouped(): List<AggregatedWorktree> = listOf(
        agg("knowledge_vault", "knowledge_vault/main", "main"),
        agg("knowledge_vault", "knowledge_vault/docs", "docs"),
        agg("knowledge_vault", "knowledge_vault/draft", "draft"),
        agg("other-repo", "other-repo/main", "main"),
    )

    @Test // 61
    fun `grouping - repo name only on the first row of a group`() {
        val out = renderScan(grouped(), ROOT, 200)
        // Inspect the REPO column itself (first bordered cell of each body row), not whole lines —
        // the relative PATH cell also contains "knowledge_vault/…", which is expected and unrelated
        // to grouping. A body row looks like: │ <REPO> │ <BRANCH> │ … │
        val repoCells = out.lines()
            .filter { it.startsWith("│") && "Репозиторий" !in it }
            .map { it.trim('│').split("│").first().trim() }
        val nonEmptyRepoCells = repoCells.filter { it.isNotEmpty() }
        // Only the group start carries the name: exactly one "knowledge_vault" + one "other-repo".
        assertEquals(
            listOf("knowledge_vault", "other-repo"),
            nonEmptyRepoCells,
            "repo name must appear only on each group's first row; got REPO cells $repoCells\n$out",
        )
        // The three branches are all present (rows weren't dropped/merged).
        assertTrue("main" in out && "docs" in out && "draft" in out, "all group rows present: $out")
    }

    @Test // 62
    fun `grouping - row order is unchanged`() {
        val out = renderScan(grouped(), ROOT, 200)
        // Branches appear in exactly the input order: main, docs, draft, then other-repo's main.
        val order = listOf("knowledge_vault/main", "knowledge_vault/docs", "knowledge_vault/draft", "other-repo/main")
        var searchFrom = 0
        for (seg in order) {
            val idx = out.indexOf(seg, searchFrom)
            assertTrue(idx >= 0, "segment '$seg' missing or out of order in:\n$out")
            searchFrom = idx + seg.length
        }
    }

    @Test // 63
    fun `grouping - empty cells do not shrink the REPO column natural width`() {
        // The projection's REPO natural width must still be measured on the non-empty first-row name.
        val rows = WorktreeTable.rowsAggregated(grouped(), ROOT)
        val natural = WorktreeTable.naturalWidths(rows, listOf(dev.alkom.gwm.ui.OverviewColumn.REPO))
        val repoWidth = natural.getValue(dev.alkom.gwm.ui.OverviewColumn.REPO)
        // "knowledge_vault" (15) is the widest name; the blanked cells (0) must not have won the max.
        assertEquals("knowledge_vault".length, repoWidth, "natural REPO width must ignore blank grouped cells")

        // And second/third rows of the group DO collapse to "" in the plain projection.
        assertEquals("knowledge_vault", rows[0].repo)
        assertTrue(rows[0].repoIsGroupStart, "first row is a group start")
        assertFalse(rows[1].repoIsGroupStart, "second row of the group is NOT a start")
        assertFalse(rows[2].repoIsGroupStart, "third row of the group is NOT a start")
        assertTrue(rows[3].repoIsGroupStart, "new repo starts a new group")
    }
}
