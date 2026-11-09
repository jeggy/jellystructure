package dev.jellystructure.ravilo.ui.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import dev.jellystructure.ravilo.i18n.LastLanguage
import dev.jellystructure.ravilo.ui.i18n.t

/**
 * DEB-4070 (DEB-3995 FR-7) — the background half: Media3's [DownloadService] as a `dataSync` foreground service with a progress
 * notification. Android's daily time limit for `dataSync` services stops it ([onTimeout]); the downloads stay paused-in-place in the index
 * and resume at the next start ([RaviloDownloadEngine] resumes them when it is built).
 */
@UnstableApi
class RaviloDownloadService : DownloadService(
    RaviloDownloadStack.FOREGROUND_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    null,   // the channel is made in getForegroundNotification, with the viewer's language for its name
    0,
    0,
) {
    override fun getDownloadManager(): DownloadManager = RaviloDownloadStack.manager(this)

    override fun getScheduler(): Scheduler = PlatformScheduler(this, RaviloDownloadStack.SCHEDULER_JOB_ID)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        ensureChannel(this)
        return DownloadNotificationHelper(this, RaviloDownloadStack.CHANNEL_ID)
            .buildProgressNotification(this, android.R.drawable.stat_sys_download, null, null, downloads, notMetRequirements)
    }

    /** API 35+: the `dataSync` budget is spent. Stop cleanly; the next start resumes. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            val name = t("download.page_title", LastLanguage.read() ?: "en")
            nm.createNotificationChannel(NotificationChannel(RaviloDownloadStack.CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW))
        }
    }
}
