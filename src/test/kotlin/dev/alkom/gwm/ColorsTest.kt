package dev.alkom.gwm

import com.github.ajalt.mordant.rendering.TextColors
import dev.alkom.gwm.config.Colors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Color-name resolution (plan §6, cases 23-26). */
class ColorsTest {

    private val fallback = TextColors.gray

    @Test // 23
    fun `known name resolves to its style`() {
        assertEquals(TextColors.brightGreen, Colors.resolve("green", fallback))
        assertTrue(Colors.isKnown("green"))
    }

    @Test // 24
    fun `unknown name falls back`() {
        assertSame(fallback, Colors.resolve("chartreuse", fallback))
        assertFalse(Colors.isKnown("chartreuse"))
    }

    @Test // 25
    fun `null falls back`() {
        assertSame(fallback, Colors.resolve(null, fallback))
        assertFalse(Colors.isKnown(null))
    }

    @Test // 26
    fun `grey and gray are both valid and case-insensitive`() {
        assertEquals(TextColors.gray, Colors.resolve("grey", fallback))
        assertEquals(TextColors.gray, Colors.resolve("gray", fallback))
        assertEquals(TextColors.brightGreen, Colors.resolve("GREEN", fallback))
        assertTrue(Colors.isKnown("GREY"))
    }
}
