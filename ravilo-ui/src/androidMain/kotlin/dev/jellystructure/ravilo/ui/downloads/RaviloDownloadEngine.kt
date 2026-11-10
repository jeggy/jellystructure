package dev.jellystructure.ravilo.ui.downloads

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * DEB-4070 (DEB-3995 FR-7) — [DownloadEngine] on Media3's [DownloadManager]. Everything that touches the manager runs on the main thread
 * (the manager binds to the looper it was built on). One download at a time, Wi-Fi only by default, files under `filesDir`.
 */
@UnstableApi
class RaviloDownloadEngine(private val ctx: Context, private val prefs: DownloadPrefs) : DownloadEngine {
    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dm: DownloadManager = RaviloDownloadStack.manager(ctx)

    // Main thread only.
    private val downloads = HashMap<String, Download>()
    private val failures = HashMap<String, Int>()
    private val pending = HashSet<String>()

    private val _phases = MutableStateFlow<Map<String, DownloadPhase>>(emptyMap())
    override val phases: StateFlow<Map<String, DownloadPhase>> = _phases.asStateFlow()

    @Volatile private var busyNow = false

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    init {
        applyRequirements()
        // A new manager starts with its downloads *paused*, and only the service's onCreate resumes them. A service that does not start
        // (the fallback in [enqueue]) would leave every download Queued for ever, so the manager is resumed here, whoever runs it.
        dm.resumeDownloads()
        runCatching { dm.downloadIndex.getDownloads().use { c -> while (c.moveToNext()) c.download.let { downloads[it.request.id] = it } } }
        dm.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                val id = download.request.id
                downloads[id] = download
                pending.remove(id)
                if (download.state == Download.STATE_FAILED) failures[id] = statusOf(finalException) ?: 0
                else if (download.state != Download.STATE_STOPPED) failures.remove(id)
                publish()
            }
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                val id = download.request.id
                downloads.remove(id); failures.remove(id); pending.remove(id)
                publish()
            }
            override fun onRequirementsStateChanged(downloadManager: DownloadManager, requirements: Requirements, notMetRequirements: Int) = publish()
        })
        publish()
        // A phone restarted (or a service stopped by the daily dataSync limit) with downloads still in the index: run them again.
        if (downloads.values.any { it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_RESTARTING }) {
            runCatching { DownloadService.start(ctx, RaviloDownloadService::class.java) }
                .onFailure { Log.w(TAG, "the download service would not start at launch; the manager runs in this process", it) }
        }
        poll()
    }

    /** 401 / 403 / 410 are an expired ticket; 503 is the server's "waiting". A Media3 failure carries the HTTP answer in its cause chain. */
    private fun statusOf(e: Throwable?): Int? {
        var t = e
        while (t != null) {
            if (t is HttpDataSource.InvalidResponseCodeException) return t.responseCode
            t = t.cause
        }
        return null
    }

    private fun percent(d: Download) = d.percentDownloaded.let { if (it < 0f) 0 else it.toInt().coerceIn(0, 100) }

    private fun phaseOf(id: String, d: Download): DownloadPhase = when (d.state) {
        Download.STATE_COMPLETED -> DownloadPhase.Downloaded
        Download.STATE_FAILED -> failures[id].let { if (it == 503) DownloadPhase.WaitingServer else DownloadPhase.Failed(it?.takeIf { s -> s > 0 }) }
        Download.STATE_STOPPED -> DownloadPhase.Paused(percent(d))
        Download.STATE_DOWNLOADING -> DownloadPhase.Running(percent(d))
        Download.STATE_QUEUED, Download.STATE_RESTARTING ->
            if (dm.notMetRequirements and (Requirements.NETWORK or Requirements.NETWORK_UNMETERED) != 0) DownloadPhase.WaitingWifi else DownloadPhase.Queued
        else -> DownloadPhase.None   // removing
    }

    private fun publish() {
        val out = HashMap<String, DownloadPhase>()
        downloads.forEach { (id, d) -> phaseOf(id, d).let { if (it != DownloadPhase.None) out[id] = it } }
        // Handed over but not yet reported by the manager: it is queued (and the engine is busy) from this instant.
        pending.forEach { if (it !in out) out[it] = DownloadPhase.Queued }
        busyNow = out.values.any { it is DownloadPhase.Running || it == DownloadPhase.Queued || it == DownloadPhase.WaitingWifi || it == DownloadPhase.WaitingServer }
        _phases.value = out
    }

    /** The manager reports state changes, not progress: while anything runs, read the percentages once a second. */
    private fun poll() {
        main.postDelayed({
            if (dm.currentDownloads.isNotEmpty()) {
                dm.currentDownloads.forEach { downloads[it.request.id] = it }
                publish()
            }
            poll()
        }, 1_000L)
    }

    private fun applyRequirements() {
        dm.requirements = Requirements(if (prefs.wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
    }

    /** Settings changed *Wi-Fi only*: the queue waits or goes on at once. */
    fun wifiOnlyChanged() = onMain { applyRequirements(); publish() }

    override fun enqueue(job: DownloadJob) = onMain {
        val ticket = job.ticket
        val uri = Uri.parse(if (ticket.url.startsWith("/")) job.baseUrl.trimEnd('/') + ticket.url else ticket.url)
        val builder = DownloadRequest.Builder(job.id, uri)
        if (ticket.kind == "hls") builder.setMimeType(MimeTypes.APPLICATION_M3U8) else builder.setCustomCacheKey(job.id)
        val request = builder.build()
        pending += job.id
        failures.remove(job.id)
        busyNow = true
        io.launch { DownloadSidecars.save(downloadUserId(job.id), downloadItemId(job.id), ticket, job.baseUrl) }
        // The service keeps the process in the foreground; if Android will not let it start from here, the manager still runs while we live.
        runCatching { DownloadService.sendAddDownload(ctx, RaviloDownloadService::class.java, request, true) }
            .onFailure {
                Log.w(TAG, "the download service would not start; the manager runs ${job.id} in this process", it)
                dm.addDownload(request)
            }
        publish()
    }

    override fun pause(id: String) = onMain {
        runCatching { DownloadService.sendSetStopReason(ctx, RaviloDownloadService::class.java, id, PAUSED, false) }
            .onFailure { dm.setStopReason(id, PAUSED) }
    }

    override fun resume(id: String) = onMain {
        runCatching { DownloadService.sendSetStopReason(ctx, RaviloDownloadService::class.java, id, Download.STOP_REASON_NONE, true) }
            .onFailure { dm.setStopReason(id, Download.STOP_REASON_NONE) }
    }

    override fun remove(id: String) = onMain {
        runCatching { DownloadService.sendRemoveDownload(ctx, RaviloDownloadService::class.java, id, false) }
            .onFailure { dm.removeDownload(id) }
        downloads.remove(id); failures.remove(id); pending.remove(id)
        io.launch { DownloadSidecars.delete(downloadUserId(id), downloadItemId(id)) }
        publish()
    }

    override fun freeBytes(): Long = runCatching { StatFs(RaviloDownloadStack.root(ctx).path).availableBytes }.getOrDefault(0L)

    override fun totalBytes(): Long = runCatching { StatFs(RaviloDownloadStack.root(ctx).path).totalBytes }.getOrDefault(0L)

    override fun busy(): Boolean = busyNow

    private companion object {
        const val TAG = "RaviloDownloads"
        const val PAUSED = 1
    }
}
