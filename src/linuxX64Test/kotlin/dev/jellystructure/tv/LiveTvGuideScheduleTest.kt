package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.cron.ScheduleCarry
import dev.jellystructure.ui.SchedForm
import dev.jellystructure.ui.SchedMode
import dev.jellystructure.ui.SchedOptions
import kotlinx.datetime.TimeZone
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DEB-4387 (FR-5.1) — the Live TV guide refresh on the shared schedule: the carry-over from `epg_cadence_minutes` at load, the 15-minute floor
 * and no *Off*, `epg_cadence_minutes` still written for one release, and the guide due by the FR-2.3 rule instead of by cache age.
 */
class LiveTvGuideScheduleTest {
    private val run = getpid().toString()
    private val storePath = "/tmp/jellystructure-test-guide-sched-$run.json"
    private val cfgPath = "/tmp/jellystructure-test-guide-sched-$run.toml"
    private val utc = TimeZone.UTC

    @AfterTest fun tearDown() { for (f in listOf(storePath, "$storePath.tmp", cfgPath)) runCatching { platform.posix.remove(f) } }

    private fun service(legacyJson: String? = null): Pair<LiveTvService, LiveTvStore> {
        if (legacyJson != null) dev.jellystructure.io.FileIo.writeText(kotlinx.io.files.Path(storePath), legacyJson)
        val store = LiveTvStore(storePath).also { it.load() }
        val cs = ConfigStore(cfgPath).also { kotlinx.coroutines.runBlocking { it.update(it.current.copy(general = it.current.general.copy(timezone = "UTC"))) } }
        return LiveTvService("/tmp/jellystructure-test-guide-sched-$run", store, JellyfinClient(), cs) to store
    }

    private fun legacy(minutes: Int, lastSyncedMs: Long = 0) =
        """{"settings":{"enabled":true,"epg_cadence_minutes":$minutes,"last_synced_at":$lastSyncedMs},"overrides":[]}"""

    @Test fun `a guide on 60 loads as every 1 hour at minute 00 and still writes epg_cadence_minutes 60`() {
        val (svc, store) = service(legacy(60))
        assertNull(store.settings().epgSchedule)
        svc.carryOver()
        assertEquals("0 * * * *", store.settings().epgSchedule)
        assertEquals(60, store.settings().epgCadenceMinutes)
        assertEquals("0 * * * *", svc.overview().epgSchedule)
        assertEquals(60, svc.overview().epgCadenceMinutes)
        val form = SchedForm.of(svc.overview().epgSchedule, SchedOptions(canOff = false, floorMinutes = 15))
        assertEquals(SchedMode.HOURS, form.mode); assertEquals(1, form.n); assertEquals(0, form.minute)
        // and it is on disk: the next load finds it
        assertEquals("0 * * * *", LiveTvStore(storePath).also { it.load() }.settings().epgSchedule)
    }

    @Test fun `15 60 360 and 1440 are mapped and any other value rounds down`() {
        val last = 1_800_000_000L  // 2027-01-15 08:00:00 UTC
        assertEquals("*/15 * * * *", ScheduleCarry.fromGuideMinutes(15, null, utc))
        assertEquals("0 * * * *", ScheduleCarry.fromGuideMinutes(60, null, utc))
        assertEquals("0 */6 * * *", ScheduleCarry.fromGuideMinutes(360, null, utc))
        assertEquals("0 4 * * *", ScheduleCarry.fromGuideMinutes(1440, null, utc))   // no sync yet: 04:00
        assertEquals("0 8 * * *", ScheduleCarry.fromGuideMinutes(1440, last, utc))    // daily at the hour and minute of the last sync
        assertEquals("*/15 * * * *", ScheduleCarry.fromGuideMinutes(30, null, utc))
        assertEquals("*/15 * * * *", ScheduleCarry.fromGuideMinutes(1, null, utc))
        assertEquals("0 * * * *", ScheduleCarry.fromGuideMinutes(120, null, utc))
        assertEquals("0 * * * *", ScheduleCarry.fromGuideMinutes(359, null, utc))
        assertEquals("0 */6 * * *", ScheduleCarry.fromGuideMinutes(720, null, utc))
        assertEquals("0 4 * * *", ScheduleCarry.fromGuideMinutes(5000, null, utc))
    }

    @Test fun `a daily guide keeps the hour and minute of its last sync across the carry-over`() {
        val (svc, store) = service(legacy(1440, lastSyncedMs = 1_800_000_000_000L + 12 * 60_000L))
        svc.carryOver()
        assertEquals("12 8 * * *", store.settings().epgSchedule)
        assertEquals(1440, store.settings().epgCadenceMinutes)
    }

    @Test fun `the carry-over happens once - a stored schedule is never made again`() {
        val (svc, store) = service(legacy(60))
        store.updateSettings { it.copy(epgSchedule = "every:FREQ=DAILY;INTERVAL=2;AT=03:00;FROM=2026-11-01") }
        svc.carryOver()
        assertEquals("every:FREQ=DAILY;INTERVAL=2;AT=03:00;FROM=2026-11-01", store.settings().epgSchedule)
    }

    @Test fun `epg_cadence_minutes is written as the minutes between the next two fires within 15 to 1440`() {
        val (svc, store) = service(legacy(60))
        assertNull(svc.updateSettings(epgSchedule = "*/20 * * * *")); assertEquals(20, store.settings().epgCadenceMinutes)
        assertNull(svc.updateSettings(epgSchedule = "0 */6 * * *")); assertEquals(360, store.settings().epgCadenceMinutes)
        assertNull(svc.updateSettings(epgSchedule = "0 4 * * *")); assertEquals(1440, store.settings().epgCadenceMinutes)
        // a weekly guide is longer than a day: clamped, so a rolled-back backend still refreshes daily
        assertNull(svc.updateSettings(epgSchedule = "0 4 * * 1")); assertEquals(1440, store.settings().epgCadenceMinutes)
        assertNull(svc.updateSettings(epgSchedule = "every:FREQ=YEARLY;INTERVAL=10;BYMONTH=3;BYMONTHDAY=1;AT=04:00;FROM=2026-03-01"))
        assertEquals(1440, store.settings().epgCadenceMinutes)
    }

    @Test fun `no Off and a 15-minute floor - a refused schedule changes nothing`() {
        val (svc, store) = service(legacy(60))
        svc.carryOver()
        for (bad in listOf("", "  ", "*/5 * * * *", "not a schedule", "every:FREQ=HOURLY;INTERVAL=0")) {
            assertNotNull(svc.updateSettings(epgSchedule = bad), "'$bad' is refused")
            assertEquals("0 * * * *", store.settings().epgSchedule, "'$bad' wrote nothing")
        }
        assertNull(svc.updateSettings(epgSchedule = "*/15 * * * *"))
    }

    @Test fun `an admin of the last release sends only epg_cadence_minutes and it still works`() {
        val (svc, store) = service(legacy(60))
        assertNull(svc.updateSettings(epgCadenceMinutes = 360))
        assertEquals("0 */6 * * *", store.settings().epgSchedule)
        assertEquals(360, store.settings().epgCadenceMinutes)
        assertNull(svc.updateSettings(enabled = false))
        assertFalse(store.settings().enabled)
        assertEquals("0 */6 * * *", store.settings().epgSchedule)
    }

    @Test fun `the guide is due by the schedule and not by the age of the cache`() {
        val noon = 1_800_000_000_000L + 4 * 3_600_000L  // 2027-01-15 12:00:00 UTC
        // read at 12:00 on an hourly schedule: due from 13:00, not before — and a 59-minute-old cache is not due by age either way
        assertFalse(guideDue("0 * * * *", utc, noon, noon + 59 * 60_000L))
        assertTrue(guideDue("0 * * * *", utc, noon, noon + 60 * 60_000L))
        // read at 12:30: the 13:00 fire is the next one, so it is due at 13:00 — 30 minutes after the read, though the cache is "young"
        assertFalse(guideDue("0 * * * *", utc, noon + 30 * 60_000L, noon + 59 * 60_000L))
        assertTrue(guideDue("0 * * * *", utc, noon + 30 * 60_000L, noon + 60 * 60_000L))
        // a daily guide at 04:00 read at 12:00 is not due until the next 04:00, however old the cache gets in between
        assertFalse(guideDue("0 4 * * *", utc, noon, noon + 15 * 3_600_000L))
        assertTrue(guideDue("0 4 * * *", utc, noon, noon + 16 * 3_600_000L))
        // fires missed in a row are one read: due, and the read at 'now' is the new last run
        assertTrue(guideDue("*/15 * * * *", utc, noon, noon + 10 * 3_600_000L))
        assertFalse(guideDue("*/15 * * * *", utc, noon + 10 * 3_600_000L, noon + 10 * 3_600_000L + 60_000L))
        // never read: due
        assertTrue(guideDue("0 4 * * *", utc, 0L, noon))
        // a hand-edited file that does not parse falls back to the hourly it always had
        assertFalse(guideDue("garbage", utc, noon, noon + 59 * 60_000L))
        assertTrue(guideDue("garbage", utc, noon, noon + 61 * 60_000L))
    }
}
