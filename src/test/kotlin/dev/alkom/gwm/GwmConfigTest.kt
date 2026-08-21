package dev.alkom.gwm

import com.github.ajalt.clikt.core.CliktError
import dev.alkom.gwm.config.ColorScheme
import dev.alkom.gwm.config.GwmConfig
import dev.alkom.gwm.config.TomlLite
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** GwmConfig mapping + file loading (plan §6, cases 14-22). Pure `from` cases + @TempDir `load`. */
class GwmConfigTest {

    private fun cfg(text: String) = GwmConfig.from(TomlLite.parse(text))

    @Test // 14
    fun `from empty parse yields all defaults`() {
        val c = GwmConfig.from(TomlLite.parse(""))
        assertEquals(emptyList(), c.roots)
        assertNull(c.worktreePathTemplate)
        assertEquals(ColorScheme.DEFAULT, c.colors)
    }

    @Test // 15
    fun `only roots leaves template null and colors default`() {
        val c = cfg("""roots = ["/a", "/b"]""")
        assertEquals(listOf("/a", "/b"), c.roots)
        assertNull(c.worktreePathTemplate)
        assertEquals(ColorScheme.DEFAULT, c.colors)
    }

    @Test // regression: blank template must not survive as a literal ("" -> File("") -> CWD)
    fun `blank worktree-path-template is treated as unset, not a literal empty template`() {
        assertNull(cfg("worktree-path-template = \"\"").worktreePathTemplate)
        assertNull(cfg("worktree-path-template = \"   \"").worktreePathTemplate)
    }

    @Test // 16
    fun `primaryRoot returns first EXISTING root, skipping missing ones`(@TempDir a: File, @TempDir b: File) {
        val missing = File(a, "nope").path
        val c = GwmConfig(roots = listOf(missing, a.path, b.path))
        assertEquals(a.path, c.primaryRoot())
    }

    @Test // 16b
    fun `primaryRoot is null when no root exists`() {
        val c = GwmConfig(roots = listOf("/definitely/not/here", "/also/not"))
        assertNull(c.primaryRoot())
    }

    @Test // 17
    fun `primaryRoot expands a leading tilde`(@TempDir home: File) {
        val sub = File(home, "portfolio").apply { mkdirs() }
        val c = GwmConfig(roots = listOf("~/portfolio"))
        assertEquals(sub.path, c.primaryRoot(home = home.path))
    }

    @Test // 18
    fun `load of a missing file returns EMPTY`(@TempDir dir: File) {
        assertEquals(GwmConfig.EMPTY, GwmConfig.load(File(dir, "no-such.toml")))
    }

    @Test // 19
    fun `load of an empty file returns EMPTY`(@TempDir dir: File) {
        val f = File(dir, "config.toml").apply { writeText("") }
        assertEquals(GwmConfig.EMPTY, GwmConfig.load(f))
    }

    @Test // 20
    fun `load of a colors-only file leaves roots empty and template null`(@TempDir dir: File) {
        val f = File(dir, "config.toml").apply { writeText("[colors]\nclean = \"cyan\"\n") }
        val c = GwmConfig.load(f)
        assertEquals(emptyList(), c.roots)
        assertNull(c.worktreePathTemplate)
        assertEquals("cyan", c.colors.clean)
    }

    @Test // 21
    fun `load of broken TOML throws CliktError naming the path`(@TempDir dir: File) {
        val f = File(dir, "config.toml").apply { writeText("[[bad\n") }
        val e = assertFailsWith<CliktError> { GwmConfig.load(f) }
        assertTrue(f.path in e.message!!, "message must name the config path: ${e.message}")
    }

    @Test // 22
    fun `defaultPath honors XDG_CONFIG_HOME, else falls back to dot-config`() {
        val xdg = GwmConfig.defaultPath(env = { if (it == "XDG_CONFIG_HOME") "/xdg" else null }, home = "/home/u")
        assertEquals(File("/xdg/gwm/config.toml"), xdg)

        val fallback = GwmConfig.defaultPath(env = { null }, home = "/home/u")
        assertEquals(File("/home/u/.config/gwm/config.toml"), fallback)
    }
}
