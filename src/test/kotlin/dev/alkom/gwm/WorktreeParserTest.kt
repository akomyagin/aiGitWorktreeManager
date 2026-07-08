package dev.alkom.gwm

import dev.alkom.gwm.git.WorktreeParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorktreeParserTest {

    @Test
    fun `parses main and linked worktrees`() {
        val porcelain = """
            worktree /home/user/repo
            HEAD 1a2b3c4d5e6f
            branch refs/heads/main

            worktree /home/user/repo-feature
            HEAD 4d5e6f7a8b9c
            branch refs/heads/feature/x
            locked
        """.trimIndent()

        val result = WorktreeParser.parse(porcelain)

        assertEquals(2, result.size)
        val main = result[0]
        assertEquals("/home/user/repo", main.path)
        assertEquals("main", main.branch)
        assertTrue(main.isMain)
        assertFalse(main.isLocked)

        val feature = result[1]
        assertEquals("feature/x", feature.branch)
        assertFalse(feature.isMain)
        assertTrue(feature.isLocked)
    }

    @Test
    fun `parses detached and bare entries`() {
        val porcelain = """
            worktree /home/user/repo.git
            bare

            worktree /home/user/repo-detached
            HEAD deadbeef
            detached
        """.trimIndent()

        val result = WorktreeParser.parse(porcelain)

        assertEquals(2, result.size)
        assertTrue(result[0].isBare)
        assertEquals("(bare)", result[0].label)
        assertTrue(result[1].isDetached)
        assertNull(result[1].branch)
        assertEquals("(detached)", result[1].label)
    }

    @Test
    fun `handles empty output`() {
        assertTrue(WorktreeParser.parse("").isEmpty())
        assertTrue(WorktreeParser.parse("\n\n").isEmpty())
    }

    @Test
    fun `flags prunable worktrees`() {
        val porcelain = """
            worktree /home/user/repo
            HEAD 1a2b3c
            branch refs/heads/main

            worktree /home/user/gone
            HEAD 9f8e7d
            branch refs/heads/old
            prunable gitdir file points to non-existent location
        """.trimIndent()

        val result = WorktreeParser.parse(porcelain)
        assertEquals(2, result.size)
        assertTrue(result[1].isPrunable)
    }
}
