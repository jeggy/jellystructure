package dev.jellystructure.ravilo.ui.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopStoresTest {
    private fun tempDir(): File = Files.createTempDirectory("ravilo-desktop-test").toFile().also { it.deleteOnExit() }

    @Test
    fun `a prefs file survives a new reader and removes on null`() {
        val dir = tempDir()
        val a = PrefsFile("p", dir = dir)
        a.put("base_url", "https://example.invalid")
        a.put("device_id", "abc")
        a.put("device_id", null)
        val b = PrefsFile("p", dir = dir)
        assertEquals("https://example.invalid", b.get("base_url"))
        assertNull(b.get("device_id"))
        assertEquals(setOf("base_url"), b.keys())
    }

    @Test
    fun `a corrupt prefs file reads as empty and is replaced on the next write`() {
        val dir = tempDir()
        File(dir, "p.json").writeText("{not json")
        val p = PrefsFile("p", dir = dir)
        assertNull(p.get("anything"))
        p.put("k", "v")
        assertEquals("v", PrefsFile("p", dir = dir).get("k"))
    }

    @Test
    fun `a private prefs file is readable by its owner only`() {
        val dir = tempDir()
        PrefsFile("tokens", private = true, dir = dir).put("device_token", "secret")
        val perms = Files.getPosixFilePermissions(File(dir, "tokens.json").toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms)
    }

    @Test
    fun `remove and clear`() {
        val dir = tempDir()
        val p = PrefsFile("p", dir = dir)
        p.put("a", "1"); p.put("b", "2"); p.put("c", "3")
        p.remove("a", "b")
        assertEquals(setOf("c"), PrefsFile("p", dir = dir).keys())
        p.clear()
        assertFalse(File(dir, "p.json").exists())
        assertTrue(p.keys().isEmpty())
    }
}
