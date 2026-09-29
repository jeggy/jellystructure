package dev.jellystructure.ravilo.castv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CastDeviceTest {
    private fun txt(ca: String, rs: String = "") =
        mapOf("id" to "8a1b2c", "fn" to "Stue", "md" to "Google Home Mini", "ca" to ca, "rs" to rs, "st" to "0")

    @Test
    fun `the kind comes from the capability bits`() {
        assertEquals("display", CastDevice.fromTxt(txt("201221"), "10.0.0.5", 8009)!!.kind, "video out (bit 1) set")
        assertEquals("speaker", CastDevice.fromTxt(txt("199172"), "10.0.0.6", 8009)!!.kind, "audio out only")
        assertEquals("group", CastDevice.fromTxt(txt("199204"), "10.0.0.7", 32187)!!.kind, "the multizone bit (32)")
    }

    @Test
    fun `the running app is the status line and an idle device says nothing`() {
        assertEquals("Spotify", CastDevice.fromTxt(txt("199172", "Spotify"), "h", 8009)!!.runningApp)
        assertNull(CastDevice.fromTxt(txt("199172", " "), "h", 8009)!!.runningApp)
    }

    @Test
    fun `a record without an id or a name is not a device`() {
        assertNull(CastDevice.fromTxt(mapOf("fn" to "Stue"), "h", 8009))
        assertNull(CastDevice.fromTxt(mapOf("id" to "x"), "h", 8009))
        assertEquals("Stue", CastDevice.fromTxt(mapOf("ID" to "x", "FN" to "Stue"), "h", 8009)!!.name, "keys in any case")
    }
}
