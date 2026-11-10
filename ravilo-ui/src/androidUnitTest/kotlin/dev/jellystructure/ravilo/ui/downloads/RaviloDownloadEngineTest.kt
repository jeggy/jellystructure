package dev.jellystructure.ravilo.ui.downloads

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.test.core.app.ApplicationProvider
import dev.jellystructure.ravilo.ui.RaviloAppContext
import kotlinx.serialization.json.JsonNull
import dev.jellystructure.shared.tv.DownloadTicket
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * DEB-4188 (DEB-4070, FR-7) — a phone's download never left *Queued* with no service, no notification and no log line. Media3's
 * `DownloadManager` is built with its downloads **paused**; only `DownloadService.onCreate` resumes them. When the service did not start,
 * the engine's fallback `addDownload` ran against a paused manager. The engine now resumes its manager itself.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RaviloDownloadEngineTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    /** A context Android will not start a service from: the case the fallback is for. */
    private class NoServices(base: Context) : ContextWrapper(base) {
        override fun startService(service: Intent?): ComponentName = throw IllegalStateException("not allowed to start service")
        override fun startForegroundService(service: Intent?): ComponentName = throw IllegalStateException("not allowed to start service")
    }

    private fun job(ctx: Context) = DownloadJob(
        id = "u|item-1",
        record = DownloadRecord(itemId = "item-1", kind = "movie", title = "A film", quality = "original"),
        ticket = DownloadTicket(kind = "file", quality = "original", url = "http://127.0.0.1:9/none", bytes = 10, expiresAt = 0, itemKind = "movie", item = JsonNull),
        baseUrl = "http://127.0.0.1:9",
    )

    @Test fun `a download added while Android will not start the service is run by the manager, not left Queued`() {
        RaviloAppContext.init(app)
        // One test: the manager is a process-wide singleton that Robolectric's per-test reset would leave stale for a second engine.
        val engine = RaviloDownloadEngine(NoServices(app), InMemoryDownloadPrefs(wifiOnly = false))
        assertFalse(RaviloDownloadStack.manager(app).downloadsPaused, "a built engine does not leave the manager paused")
        val dm = RaviloDownloadStack.manager(app)
        dm.requirements = Requirements(0)   // Robolectric has no validated network; the point is paused-or-not, not the radio
        engine.enqueue(job(app))
        val deadline = System.currentTimeMillis() + 10_000
        var state = Download.STATE_QUEUED
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            state = dm.currentDownloads.firstOrNull()?.state ?: dm.downloadIndex.getDownload("u|item-1")?.state ?: Download.STATE_QUEUED
            if (state != Download.STATE_QUEUED) break
            Thread.sleep(50)
        }
        assertNotEquals(Download.STATE_QUEUED, state, "the manager started the download itself")
        assertTrue(state == Download.STATE_DOWNLOADING || state == Download.STATE_FAILED || state == Download.STATE_RESTARTING, "state $state")
    }
}
