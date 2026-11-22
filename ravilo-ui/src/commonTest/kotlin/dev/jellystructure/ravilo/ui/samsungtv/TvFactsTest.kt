package dev.jellystructure.ravilo.ui.samsungtv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-4843 (FR-2, FR-9) — the TV's own answer, and the phone's own subnet. */
class TvFactsTest {
    private fun body(model: String, dev: String = "1", ip: String = "192.168.1.52") =
        """{"device":{"modelName":"$model","name":"[TV] Living","developerMode":"$dev","developerIP":"$ip","OS":"Tizen"},"version":"2.4.0"}"""

    @Test fun parsesDevModeAndAddress() {
        val tv = SamsungTv.parse("192.168.1.9", body("UE55RU7445"))!!
        assertEquals("UE55RU7445", tv.model); assertEquals("[TV] Living", tv.name)
        assertTrue(tv.developerMode); assertEquals("192.168.1.52", tv.developerIp)
        assertEquals(2019, tv.year); assertEquals("5.0", tv.tizenVersion); assertFalse(tv.appSettingsMenu)
    }

    @Test fun developerModeOffAndNoAddress() {
        val tv = SamsungTv.parse("192.168.1.9", body("QE65QN90DAT", dev = "0", ip = "0.0.0.0"))!!
        assertFalse(tv.developerMode); assertNull(tv.developerIp)
        assertEquals(2024, tv.year); assertTrue(tv.appSettingsMenu)
    }

    @Test fun anythingElseIsNotATv() {
        assertNull(SamsungTv.parse("1.2.3.4", "<html>router</html>"))
        assertNull(SamsungTv.parse("1.2.3.4", """{"device":{"name":"x"}}"""))
        assertNull(SamsungTv.parse("1.2.3.4", """{"hello":1}"""))
    }

    @Test fun unknownModelGetsTheShortPath() {
        val tv = SamsungTv.parse("1.2.3.4", body("Tizen-Emulator"))!!
        assertNull(tv.year); assertEquals("unknown", tv.tizenVersion); assertFalse(tv.appSettingsMenu)
    }

    @Test fun subnetIsOnlyThePhonesOwnSlash24() {
        val hosts = Subnet.hosts("192.168.1.52")
        assertEquals(253, hosts.size)
        assertTrue(hosts.all { it.startsWith("192.168.1.") }); assertFalse("192.168.1.52" in hosts)
        assertTrue(Subnet.hosts("not an ip").isEmpty()); assertTrue(Subnet.hosts("1.2.3").isEmpty()); assertTrue(Subnet.hosts("1.2.3.999").isEmpty())
    }

    @Test fun addressIsSpacedForReading() = assertEquals("192 . 168 . 1 . 52", Subnet.spaced("192.168.1.52"))
}
