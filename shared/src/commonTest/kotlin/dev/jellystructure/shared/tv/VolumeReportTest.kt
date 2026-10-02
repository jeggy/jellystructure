package dev.jellystructure.shared.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R357 — what a progress report says about the player's volume, on the wire. */
class VolumeReportTest {

    private fun body(volume: VolumeReport?) =
        RaviloWireJson.encodeToString(PlaybackProgressRequest.serializer(), progressRequest("ep1", 42_000L, true, volume))

    @Test
    fun withoutAVolumeTheBodyIsThePreR357One() {
        // FR-R357-1 — an app that cannot know its level sends exactly what an app older than R357 sends.
        assertEquals("""{"item_id":"ep1","position_ms":42000,"is_paused":true}""", body(null))
        assertEquals(body(null), RaviloWireJson.encodeToString(PlaybackProgressRequest.serializer(), PlaybackProgressRequest("ep1", 42_000L, true)))
    }

    @Test
    fun aVolumeRidesAsItsTwoFields() {
        assertEquals("""{"item_id":"ep1","position_ms":42000,"is_paused":true,"volume_percent":35,"muted":true}""", body(VolumeReport(35, true)))
        // Clamped, so a stray value never reaches the server out of range.
        assertEquals(100, progressRequest("ep1", 0L, false, VolumeReport(140, false)).volumePercent)
    }

    @Test
    fun anOlderAppsBodyStillDecodes() {
        val r = RaviloWireJson.decodeFromString(PlaybackProgressRequest.serializer(), """{"item_id":"ep1","position_ms":1}""")
        assertNull(r.volumePercent)
        assertNull(r.muted)
    }

    @Test
    fun whatAPlayerReportsAfterTheDashboardsCommands() {
        // FR-R357-3 — the level the dashboard's commands move: SetVolume 35 reads back as 35.
        val v = RemoteVolume()
        v.apply(RemoteCommand.SetVolume(35))
        assertEquals(VolumeReport(35, false), v.report())
        v.apply(RemoteCommand.Mute(true))
        assertEquals(VolumeReport(35, true), v.report())
        v.apply(RemoteCommand.Mute(null))   // ToggleMute
        assertEquals(VolumeReport(35, false), v.report())
        v.apply(RemoteCommand.Mute(null))
        assertEquals(VolumeReport(35, true), v.report())
    }

    @Test
    fun aDevicesOwnLevelIsRoundedAndNeverGuessed() {
        // A receiver's system volume: 0.29 is 0.28999… as a double and must read back as 29.
        assertEquals(VolumeReport(29, false), volumeReportOf(0.29, false))
        assertEquals(VolumeReport(35, true), volumeReportOf(0.35, true))
        assertEquals(VolumeReport(0, false), volumeReportOf(0.0, null))
        // FR-R357-5 — no level, no report.
        assertNull(volumeReportOf(null, true))
        assertNull(volumeReportOf(Double.NaN, false))
        // A synced level is rounded the same way.
        val v = RemoteVolume()
        v.sync(0.29f, null)
        assertEquals(29, v.percent)
    }
}
