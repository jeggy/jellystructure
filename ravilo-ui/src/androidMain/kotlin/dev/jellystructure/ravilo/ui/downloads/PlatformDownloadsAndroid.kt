package dev.jellystructure.ravilo.ui.downloads

import androidx.media3.common.util.UnstableApi
import dev.jellystructure.ravilo.ui.RaviloAppContext
import dev.jellystructure.ravilo.ui.isTvPlatform
import dev.jellystructure.ravilo.ui.screens.MultiTokenStore
import dev.jellystructure.shared.tv.DownloadOptions
import dev.jellystructure.shared.tv.DownloadStartRequest
import dev.jellystructure.shared.tv.DownloadTicket
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * DEB-4070 — the one [DownloadsController] of this process, so a download goes on when the screen that asked for it is gone (the service
 * keeps the process alive). A TV never has one (the layout family decides, spec FR-1). The api behind it is rebound on every call, because
 * the client is rebuilt when the viewer or server changes.
 */
@OptIn(UnstableApi::class)
private object RaviloDownloads {
    @Volatile private var client: TvApiClient? = null
    private var controller: DownloadsController? = null

    private val api = object : DownloadApi {
        override suspend fun options(itemId: String, mediaSourceId: String?): DownloadOptions =
            requireNotNull(client) { "no api" }.getDownloadOptions(itemId, mediaSourceId)
        override suspend fun start(request: DownloadStartRequest): DownloadTicket =
            requireNotNull(client) { "no api" }.startDownload(request)
    }

    fun controller(api0: TvApiClient): DownloadsController {
        client = api0
        controller?.let { return it }
        val ctx = RaviloAppContext.get()
        var engine: RaviloDownloadEngine? = null
        val prefs = AndroidDownloadPrefs(ctx) { engine?.wifiOnlyChanged() }
        val built = RaviloDownloadEngine(ctx, prefs).also { engine = it }
        return DownloadsController(
            userId = { MultiTokenStore.getActive()?.userId },
            api = api,
            engine = built,
            library = DownloadLibrary(AndroidDownloadIndexStorage()),
            prefs = prefs,
            baseUrl = { client?.baseUrl.orEmpty() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            now = { System.currentTimeMillis() },
        ).also { controller = it }
    }
}

@OptIn(UnstableApi::class)
actual fun platformDownloads(api: TvApiClient): DownloadsController? =
    if (isTvPlatform) null else RaviloDownloads.controller(api)
