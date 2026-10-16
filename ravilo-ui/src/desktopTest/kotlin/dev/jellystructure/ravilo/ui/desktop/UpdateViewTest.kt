package dev.jellystructure.ravilo.ui.desktop

import dev.jellystructure.ravilo.ui.components.CheckedAt
import dev.jellystructure.ravilo.ui.components.MacInstallState
import dev.jellystructure.ravilo.ui.components.SoftwareUpdateModel
import dev.jellystructure.ravilo.ui.components.UpdateBlockState
import dev.jellystructure.shared.tv.RaviloVersion
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** R386 (FR-R386-7) — which state the block is in, from the last check and Sparkle's last report, and from nothing else. */
class UpdateViewTest {
    private val at = CheckedAt(today = true, date = "d", time = "09:14")
    private val checked: (Long) -> CheckedAt = { at }

    private fun sparkle(phase: SparkleSnapshot.Phase, version: String = "", failure: SparkleSnapshot.Failure? = null, bytes: Long = -1, total: Long = -1) =
        SparkleSnapshot(phase, version, failure, "", true, bytes, total)

    private fun status(plan: UpdatePlan? = UpdatePlan.UpToDate, engine: SparkleSnapshot = SparkleSnapshot.UNAVAILABLE, from: MacInstallState? = null, checking: Boolean = false, checkedAt: Long? = 1L) =
        UpdateStatus(plan = plan, checkedAt = checkedAt, sparkle = engine, cannotUpdateFrom = from, checking = checking)

    private fun view(s: UpdateStatus, auto: Boolean = true, app: String = "1.52", mac: Boolean = true): SoftwareUpdateModel? = UpdateView.of(s, auto, app, mac, checked)
    private fun block(s: UpdateStatus, auto: Boolean = true) = view(s, auto)?.block

    @Test fun `state 10 — no block on Linux, and none on a development build`() {
        assertNull(view(status(), mac = false))
        assertNull(view(status(), app = "1.52-3-gabcdef0"))
        assertNull(view(status(), app = "dev"))
    }

    @Test fun `state 1 — up to date carries the app's release and when it was checked`() {
        val m = view(status())!!
        assertEquals(UpdateBlockState.UpToDate(at), m.block)
        assertEquals("1.52", m.appRelease)
        assertTrue(m.autoDownload)
    }

    @Test fun `state 1 — a v-prefixed app version reads the same`() {
        assertEquals("1.52", view(status(), app = "v1.52")!!.appRelease)
    }

    @Test fun `state 1 — a check that has never finished has no time`() {
        assertEquals(UpdateBlockState.UpToDate(null), block(status(checkedAt = null)))
    }

    @Test fun `state 2 — checking before the first check has finished, and while one runs`() {
        assertEquals(UpdateBlockState.Checking, block(status(plan = null)))
        assertEquals(UpdateBlockState.Checking, block(status(checking = true)))
        assertEquals(UpdateBlockState.Checking, block(status(engine = sparkle(SparkleSnapshot.Phase.CHECKING))))
    }

    @Test fun `state 3 — Sparkle downloading, with figures only when it reports them`() {
        val ask = UpdatePlan.AskSparkle(RaviloVersion(1, 53), "feed")
        assertEquals(UpdateBlockState.Downloading("1.53", -1, -1), block(status(ask, sparkle(SparkleSnapshot.Phase.DOWNLOADING, "1.53"))))
        assertEquals(UpdateBlockState.Downloading("1.53", 41, 94), block(status(ask, sparkle(SparkleSnapshot.Phase.DOWNLOADING, "1.53", bytes = 41, total = 94))))
    }

    @Test fun `state 3 — with the toggle off the same update is the offer form`() {
        val ask = UpdatePlan.AskSparkle(RaviloVersion(1, 53), "feed")
        assertEquals(UpdateBlockState.Offer("1.53"), block(status(ask, sparkle(SparkleSnapshot.Phase.AVAILABLE, "1.53")), auto = false))
        assertEquals(UpdateBlockState.Offer("1.53"), block(status(ask, sparkle(SparkleSnapshot.Phase.WINDOW, "1.53")), auto = true))
        assertFalse(view(status(ask, sparkle(SparkleSnapshot.Phase.AVAILABLE, "1.53")), auto = false)!!.autoDownload)
    }

    private fun assertFalse(b: Boolean) = assertTrue(!b)

    @Test fun `state 4 — ready names the version and the tag's release page`() {
        val b = block(status(UpdatePlan.AskSparkle(RaviloVersion(1, 53), "feed"), sparkle(SparkleSnapshot.Phase.READY, "1.53")))
        assertEquals(UpdateBlockState.Ready("1.53", "https://github.com/jeggy/jellystructure/releases/tag/v1.53"), b)
    }

    @Test fun `the release page of something that is not a release is the latest release's`() {
        assertEquals("https://github.com/jeggy/jellystructure/releases/latest", UpdateView.notesUrl(""))
        assertEquals("https://github.com/jeggy/jellystructure/releases/tag/v1.9", UpdateView.notesUrl("v1.9"))
    }

    @Test fun `the handover — Sparkle is installing`() {
        assertEquals(UpdateBlockState.Handover("1.53"), block(status(UpdatePlan.AskSparkle(null, "f"), sparkle(SparkleSnapshot.Phase.INSTALLING, "1.53"))))
    }

    @Test fun `state 6 — held back names the newer release and the server's`() {
        val held = UpdatePlan.HeldBack(RaviloVersion(1, 53), RaviloVersion(1, 52))
        assertEquals(UpdateBlockState.HeldBack("1.53", "1.52"), block(status(held)))
    }

    @Test fun `state 7 — a failed download and a failed signature are told apart`() {
        val ask = UpdatePlan.AskSparkle(RaviloVersion(1, 53), "feed")
        assertEquals(UpdateBlockState.Failed(signature = false), block(status(ask, sparkle(SparkleSnapshot.Phase.FAILED, failure = SparkleSnapshot.Failure.DOWNLOAD))))
        assertEquals(UpdateBlockState.Failed(signature = true), block(status(ask, sparkle(SparkleSnapshot.Phase.FAILED, failure = SparkleSnapshot.Failure.SIGNATURE))))
        // A bad feed or something else is, to the viewer, an update that did not arrive.
        assertEquals(UpdateBlockState.Failed(signature = false), block(status(ask, sparkle(SparkleSnapshot.Phase.FAILED, failure = SparkleSnapshot.Failure.FEED))))
    }

    @Test fun `state 8 — where the app cannot update from beats everything, including a ready update`() {
        val ready = sparkle(SparkleSnapshot.Phase.READY, "1.53")
        assertEquals(UpdateBlockState.CannotUpdate(fromDiskImage = false), block(status(engine = ready, from = MacInstallState.TRANSLOCATED)))
        assertEquals(UpdateBlockState.CannotUpdate(fromDiskImage = true), block(status(engine = ready, from = MacInstallState.ON_VOLUME)))
    }

    @Test fun `state 12 — offline keeps the last check's time`() {
        assertEquals(UpdateBlockState.Offline(at), block(status(UpdatePlan.Offline)))
        assertEquals(UpdateBlockState.Offline(null), block(status(UpdatePlan.Offline, checkedAt = null)))
    }

    @Test fun `Sparkle's ready update stays on screen when a later check is offline`() {
        val b = block(status(UpdatePlan.Offline, sparkle(SparkleSnapshot.Phase.READY, "1.53")))
        assertTrue(b is UpdateBlockState.Ready, "$b")
    }

    @Test fun `Sparkle found nothing newer — up to date, and no Sparkle in the bundle — no block`() {
        val ask = UpdatePlan.AskSparkle(RaviloVersion(1, 53), "feed")
        assertEquals(UpdateBlockState.UpToDate(at), block(status(ask, sparkle(SparkleSnapshot.Phase.NOT_FOUND))))
        assertNull(view(status(ask, SparkleSnapshot.UNAVAILABLE)))
    }

    @Test fun `a check that was asked for and has not begun is checking, never up to date`() {
        val ask = UpdatePlan.AskSparkle(RaviloVersion(1, 53), "feed")
        assertEquals(UpdateBlockState.Checking, block(status(ask, sparkle(SparkleSnapshot.Phase.IDLE))))
    }

    @Test fun `the checked time is today's or a named date, in the viewer's format`() {
        val zone = ZoneId.of("UTC")
        val now = java.time.Instant.parse("2026-10-16T09:16:40Z").toEpochMilli()
        val today = UpdateView.checkedAt(now - 120_000, now, zone, Locale.UK)
        assertTrue(today.today)
        assertEquals("09:14", today.time)
        val earlier = UpdateView.checkedAt(now - 2 * 86_400_000L, now, zone, Locale.UK)
        assertTrue(!earlier.today)
        assertNotNull(earlier.date)
    }

    @Test fun `the engine's report carries the menu's count`() {
        assertEquals(2, SparkleSnapshot.parse("phase=idle\nmenu=2").menuClicks)
        assertEquals(0, SparkleSnapshot.parse("phase=idle").menuClicks)
        assertEquals(0, SparkleSnapshot.UNAVAILABLE.menuClicks)
    }

    @Test fun `the preview seam names every state it can show, and refuses an unknown name`() {
        for (name in UpdatePreview.names) assertNotNull(UpdatePreview.parse(name), name)
        assertNull(UpdatePreview.parse("nonsense"))
        assertEquals(false, UpdatePreview.parse("off")!!.first)
        assertNull(UpdatePreview.parse("dev-build")!!.second)
    }
}
