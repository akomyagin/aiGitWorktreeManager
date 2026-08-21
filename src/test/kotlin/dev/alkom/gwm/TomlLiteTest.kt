package dev.alkom.gwm

import dev.alkom.gwm.config.TomlLite
import dev.alkom.gwm.config.TomlParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exhaustive unit tests for the hand-written TOML-subset parser (plan §6, cases 1-13). Pure —
 * no filesystem. Covers both the supported grammar and the "reject, don't silently ignore"
 * contract for syntax outside the subset.
 */
class TomlLiteTest {

    @Test // 1
    fun `flat key with double-quoted value strips quotes`() {
        val p = TomlLite.parse("""name = "value"""")
        assertEquals("value", p.string("name"))
    }

    @Test // 2
    fun `single-quoted value strips quotes`() {
        val p = TomlLite.parse("name = 'value'")
        assertEquals("value", p.string("name"))
    }

    @Test // 3
    fun `bare value (number or bool) kept verbatim as string`() {
        val p = TomlLite.parse("count = 42\nflag = true")
        assertEquals("42", p.string("count"))
        assertEquals("true", p.string("flag"))
    }

    @Test // 4
    fun `string array parses to list`() {
        val p = TomlLite.parse("""roots = ["/a", "/b"]""")
        assertEquals(listOf("/a", "/b"), p.stringList("roots"))
    }

    @Test // 5
    fun `empty array is an empty list`() {
        val p = TomlLite.parse("roots = []")
        assertEquals(emptyList(), p.stringList("roots"))
    }

    @Test // 6
    fun `section header prefixes following keys`() {
        val p = TomlLite.parse("[colors]\nclean = \"green\"")
        assertEquals("green", p.string("colors.clean"))
        assertNull(p.string("clean"), "unqualified key must not exist")
    }

    @Test // 7
    fun `comments and blank lines are skipped`() {
        val p = TomlLite.parse(
            """
            # a comment

            name = "v"  # trailing comment
            """.trimIndent(),
        )
        assertEquals("v", p.string("name"))
    }

    @Test // 8
    fun `duplicate key throws with line number`() {
        val e = assertFailsWith<TomlParseException> {
            TomlLite.parse("a = \"1\"\na = \"2\"")
        }
        assertEquals(2, e.line)
    }

    @Test // 9
    fun `array of tables is rejected with line number`() {
        val e = assertFailsWith<TomlParseException> {
            TomlLite.parse("[[x]]")
        }
        assertEquals(1, e.line)
    }

    @Test // 10
    fun `inline table is rejected`() {
        val e = assertFailsWith<TomlParseException> {
            TomlLite.parse("a = { b = 1 }")
        }
        assertTrue("inline" in e.message!!.lowercase() || "таблиц" in e.message!!)
    }

    @Test // 11
    fun `multiline string is rejected`() {
        assertFailsWith<TomlParseException> {
            TomlLite.parse("a = \"\"\"hello\"\"\"")
        }
    }

    @Test // 12
    fun `empty input yields empty parse`() {
        val p = TomlLite.parse("")
        assertTrue(p.strings.isEmpty())
        assertTrue(p.stringLists.isEmpty())
    }

    @Test // 13
    fun `junk line without equals throws with line number`() {
        val e = assertFailsWith<TomlParseException> {
            TomlLite.parse("name = \"ok\"\ngarbage line")
        }
        assertEquals(2, e.line)
    }
}
