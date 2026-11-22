package dev.jellystructure.ravilo.ui.samsungtv

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEB-4843 (FR-5, FR-9) — framing, one command per connection, push, install sequencing, the lenient id read. */
class SdbTest {
    private val log = mutableListOf<String>()
    private fun client(tv: FakeSdbTv) = SdbClient(tv, { log += it }, pause = {})

    @Test fun packetsRoundTripWithChecksumAndMagic() {
        val p = SdbPacket(SdbWire.WRTE, 1, 2, "abc".encodeToByteArray())
        val b = SdbWire.encode(p)
        assertEquals(SdbWire.HEADER + 3, b.size)
        assertEquals(SdbWire.WRTE, SdbWire.readLe32(b, 0))
        assertEquals(294, SdbWire.readLe32(b, 16))   // 97 + 98 + 99
        assertEquals(SdbWire.WRTE.inv(), SdbWire.readLe32(b, 20))
    }

    @Test fun aShellAnswerIsReadAndLogged() = runTest {
        val tv = FakeSdbTv(mapOf("0 getduid" to "EXCKEBBVWCGFC\n"))
        assertEquals("EXCKEBBVWCGFC\n", client(tv).shell("10.0.0.5", "0 getduid"))
        assertTrue(log.any { it.contains("EXCKEBBVWCGFC") })
    }

    @Test fun everyCommandUsesAFreshConnection() = runTest {
        val tv = FakeSdbTv(mapOf("0 getduid" to "EXCKEBBVWCGFC"))
        val c = client(tv)
        c.shell("10.0.0.5", "0 getduid"); c.shell("10.0.0.5", "0 getduid")
        assertEquals(2, tv.connections)
        assertFalse(tv.wedged)
    }

    @Test fun duidReadIsLenient() = runTest {
        for (answer in listOf("EXCKEBBVWCGFC", "  EXCKEBBVWCGFC \r\n", "sh-3.2# EXCKEBBVWCGFC\n", "duid: EXCKEBBVWCGFC")) {
            assertEquals("EXCKEBBVWCGFC", client(FakeSdbTv(mapOf("0 getduid" to answer))).readDuid("10.0.0.5"), answer)
        }
        assertFailsWith<SdbException> { client(FakeSdbTv(mapOf("0 getduid" to "error: closed"))).readDuid("10.0.0.5") }
        assertNull(DuidParse.first("short\n"))
    }

    @Test fun installPushesThenInstallsThenLaunchesOnSeparateConnections() = runTest {
        val tv = FakeSdbTv(mapOf(
            "0 vd_appinstall RaviloScrn /home/owner/share/tmp/sdk_tools/RaviloScrn.wgt" to "install completed",
            "0 was_execute RaviloScrn.RaviloScrn" to "launched",
        ))
        val wgt = ByteArray(150_000) { it.toByte() }
        val stages = mutableListOf<SdbClient.Stage>()
        client(tv).install("10.0.0.5", wgt, "RaviloScrn", "RaviloScrn.RaviloScrn") { s, _ -> if (stages.lastOrNull() != s) stages += s }
        assertEquals(3, tv.connections)
        assertFalse(tv.wedged)
        assertTrue(wgt.contentEquals(tv.pushed.getValue("/home/owner/share/tmp/sdk_tools/RaviloScrn.wgt")))
        assertEquals(listOf(SdbClient.Stage.SENDING, SdbClient.Stage.INSTALLING), stages)
    }

    @Test fun anInstallThatDoesNotSayCompletedFails() = runTest {
        val tv = FakeSdbTv(mapOf("0 vd_appinstall RaviloScrn /home/owner/share/tmp/sdk_tools/RaviloScrn.wgt" to "Failed[118, -12]"))
        assertFailsWith<SdbException> { client(tv).install("10.0.0.5", ByteArray(10), "RaviloScrn", "RaviloScrn.RaviloScrn") }
        assertEquals(2, tv.connections)   // never reached the launch
    }

    @Test fun aRefusedPushFails() = runTest {
        val tv = FakeSdbTv(emptyMap()).also { it.refusePush = true }
        assertFailsWith<SdbException> { client(tv).push("10.0.0.5", "/x", ByteArray(5)) }
    }
}
