package dev.jellystructure.ravilo.ui.seams

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import dev.jellystructure.ravilo.ui.music.CastSessionRemote
import dev.jellystructure.shared.tv.CAST_NAMESPACE
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastReceiverMessage
import dev.jellystructure.shared.tv.castLoadLog
import dev.jellystructure.shared.tv.castLoadPlan
import dev.jellystructure.shared.tv.castWireBytes
import dev.jellystructure.shared.tv.newCastQueueId
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * The LOAD for [data]. contentId is resolved by the receiver (it enrols and negotiates the ticket itself); the phone
 * never hands it a media URL — that is the whole point of the receiver being its own device. R359 (FR-R359-2):
 * `CastLoadData` rides once, as the media's customData — the one the receiver reads; the request carries none.
 */
internal fun castLoadRequest(data: CastLoadData): MediaLoadRequestData {
    val song = data.tracks.getOrNull(data.currentIndex)
    val meta = if (song != null) MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
        // R324 (FR-R324-9) — a song's card: cover · title · artist; the receiver rewrites it per song.
        putString(MediaMetadata.KEY_TITLE, song.title)
        song.artist?.let { putString(MediaMetadata.KEY_ARTIST, it) }
        song.album?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
        (song.coverUrl ?: data.artUrl)?.let { addImage(WebImage(Uri.parse(if (it.startsWith("http")) it else data.serverUrl.trimEnd('/') + it))) }
    } else MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
        putString(MediaMetadata.KEY_TITLE, data.title)
        data.kicker?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
        // FR-R245-11 — a landscape still/backdrop for the notification, never a poster.
        data.artUrl?.let { addImage(WebImage(Uri.parse(it))) }
    }
    val info = MediaInfo.Builder("ravilo://${data.itemId}")
        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
        .setContentType(if (song != null) "audio/mpeg" else "application/x-mpegURL")
        .setMetadata(meta)
        .setCustomData(JSONObject(json.encodeToString(CastLoadData.serializer(), data)))
        .build()
    return MediaLoadRequestData.Builder()
        .setMediaInfo(info)
        .setAutoplay(true)
        .setCurrentTime(data.positionMs ?: 0L)
        .build()
}

/**
 * R245 — the Cast SDK's options: a placeholder receiver id (the real one is applied at runtime from the
 * config snapshot, 218 FR-218-11). Registered in ravilo-android's manifest.
 *
 * R356 (FR-R356-1) — the SDK's own media notification (R245 FR-R245-11, R324 FR-R324-9) and its media session are OFF.
 * While the phone is the remote for a cast, the card, the lock screen and the media keys are Ravilo's own session
 * ([CastSessionRemote] in RaviloMusicService), whose foreground service keeps the app from being frozen: the SDK's
 * notification never did, and Play services dropped a frozen app's Cast connection once its binder buffer filled.
 */
class RaviloCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(null)
            .setMediaSessionEnabled(false)
            // No expanded controller is set: the remote is Ravilo's own screen.
            .build()
        return CastOptions.Builder()
            // The app id the server gave last time (218 FR-218-11), else a syntactically valid placeholder
            // that CastContext.setReceiverApplicationId replaces once the config loads. R265: the stored
            // id is what makes FR-R245-5's re-connect work at all — the SDK attempts a resume once, at
            // start-up, against THIS id, and a session with the real receiver never matched the
            // placeholder, so after the app process died the phone never rejoined a TV that was still
            // playing (seen on the Pixel 9 against the soveværelse TV).
            .setReceiverApplicationId(lastAppId(context) ?: PLACEHOLDER_APP_ID)
            .setCastMediaOptions(media)
            // R265 (2026-09-26, Pixel 9 → stue TV): with the reconnection service on, killing the app
            // restarted it in the background at once to resume the session — and Android's freezer froze
            // that half-done resume. Opened again seconds later, the SDK ended the stuck session, and with
            // stop-on-end that STOPPED THE TV. No background service (R293: off screen holds nothing
            // open): the one foreground resume at start-up is the reconnect, and it rejoins.
            .setEnableReconnectionService(false)
            // An end the SDK decides on its own must leave the TV playing (FR-R245-5: the TV keeps going
            // when the phone does not). Stop casting still stops it — stop() passes `true` explicitly.
            .setStopReceiverApplicationWhenEndingSession(false)
            .build()
    }
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
    companion object {
        const val PLACEHOLDER_APP_ID = "CC1AD845"
        private const val PREFS = "ravilo_cast"
        private const val KEY_APP_ID = "receiver_app_id"
        fun lastAppId(context: Context): String? =
            runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_APP_ID, null) }.getOrNull()
        fun rememberAppId(context: Context, appId: String) {
            runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_APP_ID, appId).apply() }
        }
    }
}

private val json = dev.jellystructure.shared.tv.RaviloWireJsonWithDefaults   // R318

/** One sender per process — the SDK's `CastContext` is itself a singleton. */
private object CastSenderHolder {
    var sender: CastSenderAndroid? = null
}

/** R265 — [ScreenSender] is not held in a process-singleton holder like [CastSenderAndroid]: it carries
 *  no SDK singleton to protect and is cheap to recreate, but must be recreated if [api]'s identity ever
 *  changes (a different server), which [remember]'s key already covers. */
@Composable
actual fun rememberCastSender(api: TvApiClient): ActiveCastSender {
    val ctx = LocalContext.current.applicationContext
    val chromecast = remember { CastSenderHolder.sender ?: runCatching { CastSenderAndroid(ctx) }.getOrNull()?.also { CastSenderHolder.sender = it } }
    val screen = remember(api) { ScreenSender(api) }
    return remember(chromecast, screen) { ActiveCastSender(chromecast, screen) }
}

class CastSenderAndroid(private val appContext: Context) : CastSender {
    private val castContext: CastContext = CastContext.getSharedInstance(appContext)
    private val _link = MutableStateFlow(CastLinkState.NONE)
    private val _device = MutableStateFlow<String?>(null)
    private val _status = MutableStateFlow<CastRemoteStatus?>(null)
    override val link: StateFlow<CastLinkState> = _link
    override val deviceName: StateFlow<String?> = _device
    override val status: StateFlow<CastRemoteStatus?> = _status
    /** R353 (FR-R353-4) — the device's volume as the session reports it (the speaker's own, 0.0–1.0); null while unlinked. */
    private val _volume = MutableStateFlow<Double?>(null)
    override val volume: StateFlow<Double?> = _volume
    private val castListener = object : com.google.android.gms.cast.Cast.Listener() {
        override fun onVolumeChanged() { _volume.value = runCatching { session?.volume }.getOrNull() }
        // R355 — a speaker added in Android's output panel renames the session ("Gæsteværelse + 1").
        override fun onDeviceNameChanged() { refreshName() }
    }
    /** R355 (FR-R355-1) — the speakers a dynamic group session plays on; empty on one device. */
    private val _members = MutableStateFlow<List<String>>(emptyList())
    override val members: StateFlow<List<String>> = _members
    /** The session's own name as Cast gives it (the device, or "Gæsteværelse + 1" once grouped). */
    private var sessionName: String? = null
    private val router: androidx.mediarouter.media.MediaRouter? by lazy { runCatching { androidx.mediarouter.media.MediaRouter.getInstance(appContext) }.getOrNull() }
    /**
     * R355 — the group's members are read off MediaRouter's selected route (Play services' dynamic group route), on
     * every route event. Unfiltered events, NO discovery request: off screen nothing scans (R293).
     */
    private val groupCallback = object : androidx.mediarouter.media.MediaRouter.Callback() {
        override fun onRouteChanged(router: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo) = refreshName()
        override fun onRouteSelected(router: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo, reason: Int) = refreshName()
        override fun onRouteUnselected(router: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo, reason: Int) = refreshName()
        override fun onRouteAdded(router: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo) = refreshName()
        override fun onRouteRemoved(router: androidx.mediarouter.media.MediaRouter, route: androidx.mediarouter.media.MediaRouter.RouteInfo) = refreshName()
    }

    /** The members of the selected route when it is a group the platform made; empty otherwise. Main thread. */
    private fun groupMembers(): List<String> = runCatching {
        val r = router?.selectedRoute ?: return@runCatching emptyList()
        if (r.isDefaultOrBluetooth || !r.isGroup) return@runCatching emptyList()
        val g = r.asGroup() ?: return@runCatching emptyList()
        // Only the speakers that play: a dynamic group route also lists the ones that COULD be added.
        g.routesInGroup.filter { runCatching { g.getSelectionState(it) == androidx.mediarouter.media.MediaRouteProvider.DynamicGroupRouteController.DynamicRouteDescriptor.SELECTED }.getOrDefault(true) }
            .map { it.name }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /** R355 (FR-R355-2) — the name every screen shows: the session's own, or its speakers when grouped. */
    private fun refreshName() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refreshName() }; return }
        val s = session ?: run { if (_members.value.isNotEmpty()) _members.value = emptyList(); return }
        sessionName = s.castDevice?.friendlyName ?: sessionName ?: selectedRouteName()
        val m = groupMembers()
        if (m != _members.value) {
            android.util.Log.i("RaviloCast", "R355: session '${sessionName}' plays on ${m.ifEmpty { listOf("one device") }}")
            _members.value = m
        }
        _device.value = castSessionName(sessionName, m)
    }
    private var session: CastSession? = null
    /** The last message the receiver sent about the item/tracks — merged with the SDK's media status. */
    private var receiverSaid: CastReceiverMessage? = null
    private var pendingLoad: CastLoadData? = null

    private val progressListener = RemoteMediaClient.ProgressListener { position, duration ->
        // R356 (FR-R356-7) — the SDK ticks on with 0/0 when it holds no media status (a dropped connection, a rejoin):
        // that is not the receiver's word, and it put 0:00 / -0:00 on the Playing page while the speaker played on.
        if (session?.remoteMediaClient?.mediaStatus == null) return@ProgressListener
        val s = _status.value ?: CastRemoteStatus()
        _status.value = s.copy(positionMs = position.coerceAtLeast(0), durationMs = duration.takeIf { it > 0 } ?: s.durationMs)
    }
    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() { heard(); rebuildStatus() }
        override fun onMetadataUpdated() { heard(); rebuildStatus() }
    }
    private val messageCallback = com.google.android.gms.cast.Cast.MessageReceivedCallback { _, _, message ->
        heard()
        // R356 (FR-R356-11) — the size of what the receiver sends: the first message of a session, and every big one.
        if (!sizeLogged || message.length > 32_000) {
            sizeLogged = true
            android.util.Log.i("RaviloCast", "R356: receiver message ${message.length} B")
        }
        runCatching { json.decodeFromString(CastReceiverMessage.serializer(), message) }.getOrNull()?.let { msg ->
            // R359 (FR-R359-5) — a queue too long for one message comes in parts after its status; whole, it is held as
            // if the status had carried it. A part is not a state report: nothing else changes.
            if (msg.type == "queue_part") {
                queueParts.part(msg)?.let { whole ->
                    android.util.Log.i("RaviloCast", "R359: the receiver's queue (${whole.size} songs, rev ${msg.queueRev}) came in parts")
                    _status.value = castStatusWithQueue(_status.value, whole, msg.queueRev, receiverSaid?.queueIndex)
                }
                return@let
            }
            if (msg.queue != null) queueParts.reset()
            // R356 (FR-R356-9) — a newer queue revision without the queue: ask for it, once per revision.
            if (castQueueGap(_status.value, msg) && askedQueueRev != msg.queueRev) {
                askedQueueRev = msg.queueRev
                android.util.Log.i("RaviloCast", "R356: queue revision ${msg.queueRev} not held (have ${_status.value?.queueRev}); asking for it")
                sendRaw(json.encodeToString(dev.jellystructure.shared.tv.CastCommand.serializer(), dev.jellystructure.shared.tv.CastCommand("get_queue")))
            }
            receiverSaid = foldReceiverMessage(receiverSaid, msg)   // R330 — the rule both senders share
            rebuildStatus(msg.type)
        }
    }
    @Volatile private var sizeLogged = false
    @Volatile private var askedQueueRev: Int? = null
    /** R359 — the receiver's queue, while it arrives in parts (touched on the main thread only). */
    private val queueParts = dev.jellystructure.shared.tv.CastQueueAssembly()

    // ── R356 (FR-R356-6): connected but silent — ask, then rejoin ──
    private val silence = CastSilenceWatch()
    /** Set while the stale session object is being replaced by a new one on the same receiver. */
    private var rejoin: Rejoin? = null
    private class Rejoin(val routeId: String?, val deviceId: String?, val name: String?, val at: Long)
    private var rejoinDiscovery: androidx.mediarouter.media.MediaRouter.Callback? = null

    private fun heard() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { silence.heard() }; return }
        silence.heard()
    }

    override fun onAppForeground() = onMain {
        if (_link.value != CastLinkState.CONNECTED || session == null || rejoin != null) return@onMain
        if (silence.nudge(android.os.SystemClock.elapsedRealtime(), askNow = true) == CastSilenceAction.ASK) ask("the app came on screen")
        watchSilence()
    }

    /** A command was sent: the receiver's answer is due (FR-R356-6). Main thread. */
    private fun commandSent() {
        if (_link.value != CastLinkState.CONNECTED || session == null || rejoin != null) return
        silence.nudge(android.os.SystemClock.elapsedRealtime(), askNow = false)
        watchSilence()
    }

    private val silenceTick = object : Runnable {
        override fun run() {
            val connected = _link.value == CastLinkState.CONNECTED && session != null && rejoin == null
            when (silence.tick(android.os.SystemClock.elapsedRealtime(), connected)) {
                CastSilenceAction.ASK -> ask("no answer to a command")
                CastSilenceAction.REJOIN -> { startRejoin(); return }
                CastSilenceAction.NONE -> Unit
            }
            if (silence.waiting) main.postDelayed(this, 500L)
        }
    }
    private fun watchSilence() { main.removeCallbacks(silenceTick); if (silence.waiting) main.postDelayed(silenceTick, 500L) }

    /** Both channels: the SDK's media status and Ravilo's own `status` (whose answer also carries the whole queue). */
    private fun ask(why: String) {
        android.util.Log.i("RaviloCast", "R356: asking the receiver for its status ($why)")
        val rmc = session?.remoteMediaClient
        runCatching { rmc?.requestStatus() }
        sendRaw(json.encodeToString(dev.jellystructure.shared.tv.CastCommand.serializer(), dev.jellystructure.shared.tv.CastCommand("status")))
    }

    /**
     * Nothing came after asking: Play services has dropped this app's connection (or the receiver is gone). End the stale
     * session object WITHOUT stopping the receiver and select the same route again — the SDK then joins the receiver app
     * that is running (no relaunch, no LOAD), as a fresh process's resume does. The link stays connected and the last
     * known state stays on screen (FR-R356-7); a rejoin that has not connected in [CAST_REJOIN_GIVE_UP_MS] ends it.
     */
    private fun startRejoin() {
        val s = session ?: return
        val route = router?.selectedRoute?.takeIf { !it.isDefaultOrBluetooth }
        val rj = Rejoin(route?.id, runCatching { s.castDevice?.deviceId }.getOrNull(), route?.name ?: sessionName, android.os.SystemClock.elapsedRealtime())
        rejoin = rj
        android.util.Log.w("RaviloCast", "R356: no word from the receiver after asking; rejoining ${rj.name} (route ${rj.routeId})")
        silence.reset()
        // Scan while looking for the route again: after the session ends, the route may drop off an idle list.
        runCatching {
            val cb = object : androidx.mediarouter.media.MediaRouter.Callback() {}
            router?.addCallback(castContext.mergedSelector ?: androidx.mediarouter.media.MediaRouteSelector.EMPTY, cb, androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            rejoinDiscovery = cb
        }
        runCatching { castContext.sessionManager.endCurrentSession(false) }
        main.postDelayed({ reselect(rj, 0) }, 700L)
        main.postDelayed({ giveUpRejoin(rj) }, CAST_REJOIN_GIVE_UP_MS)
    }

    private fun reselect(rj: Rejoin, attempt: Int) {
        if (rejoin !== rj) return
        val r = router ?: return
        if (CastStartWatch.startedAt > rj.at) return   // a session is starting from it already
        val route = r.routes.firstOrNull { it.id == rj.routeId }
            ?: r.routes.firstOrNull { rt -> rj.deviceId != null && runCatching { com.google.android.gms.cast.CastDevice.getFromBundle(rt.extras)?.deviceId }.getOrNull() == rj.deviceId }
        when {
            route == null -> if (attempt < 20) main.postDelayed({ reselect(rj, attempt + 1) }, 500L)
            // Still selected: the old session has not let go of it yet. Unselect WITHOUT stopping (DISCONNECTED, never
            // STOPPED — that would stop the receiver), then select it again.
            route.isSelected -> {
                if (attempt == 3) runCatching { r.unselect(androidx.mediarouter.media.MediaRouter.UNSELECT_REASON_DISCONNECTED) }
                if (attempt < 20) main.postDelayed({ reselect(rj, attempt + 1) }, 500L)
            }
            else -> {
                android.util.Log.i("RaviloCast", "R356: selecting ${route.name} again to rejoin its receiver")
                selectRoute(r, route.id)
            }
        }
    }

    private fun giveUpRejoin(rj: Rejoin) {
        if (rejoin !== rj) return
        android.util.Log.w("RaviloCast", "R356: the rejoin of ${rj.name} did not connect; the session ends")
        endRejoin()
        detach()
        _link.value = CastLinkState.NONE; _status.value = null; receiverSaid = null
    }

    private fun endRejoin() {
        rejoin = null
        rejoinDiscovery?.let { cb -> runCatching { router?.removeCallback(cb) } }
        rejoinDiscovery = null
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(s: CastSession) {
            CastStartWatch.mark()
            if (rejoin != null) return   // R356 — rejoining: the link stays connected, the last known state on screen
            _link.value = CastLinkState.CONNECTING; _device.value = s.castDevice?.friendlyName
        }
        override fun onSessionStarted(s: CastSession, sessionId: String) {
            val rejoined = rejoin != null
            if (rejoined) { endRejoin(); detach() }
            attach(s); _link.value = CastLinkState.CONNECTED
            val loading = pendingLoad?.also { loadOnMain(it) } != null
            if (rejoined) android.util.Log.i("RaviloCast", "R356: rejoined the running receiver on ${s.castDevice?.friendlyName}")
            // R356 — joined a receiver that is already playing (a rejoin, or a speaker another of the viewer's devices
            // started): the receiver speaks on change only, so ask where it is rather than wait for its next change.
            if (!loading) ask(if (rejoined) "rejoined" else "joined")
        }
        override fun onSessionStartFailed(s: CastSession, error: Int) {
            if (rejoin != null) { android.util.Log.w("RaviloCast", "R356: the rejoin failed ($error)"); endRejoin(); _status.value = null; receiverSaid = null }
            detach(); _link.value = CastLinkState.NONE
        }
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionEnded(s: CastSession, error: Int) {
            // R356 — the stale session object a rejoin ended on purpose: only its callbacks go; the receiver plays on.
            if (rejoin != null) { if (session === s) detach(); return }
            detach(); _link.value = CastLinkState.NONE; _status.value = null; receiverSaid = null
        }
        // FR-R245-5 — re-connect on app start: the SDK resumes; then ONE of two things happens (see onSessionResumed).
        override fun onSessionResuming(s: CastSession, sessionId: String) { CastStartWatch.mark(); _link.value = CastLinkState.RECONNECTING; _device.value = s.castDevice?.friendlyName ?: selectedRouteName() }
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {
            attach(s)
            _link.value = CastLinkState.CONNECTED
            val rmc = s.remoteMediaClient
            // Decide from the receiver's ANSWER, never from the client's state at this instant. R265,
            // seen on the Pixel 9 after the app process died with the soveværelse TV still playing: right
            // after a resume the client has received no media status yet, so `mediaInfo` is null, and
            // the check that stood here read that as "finished" and ended the session — the mini bar
            // vanished while the TV played on. FR-R245-5's silence is for a receiver that SAYS it is idle.
            fun decide() {
                val alive = rmc != null && rmc.mediaInfo != null && rmc.playerState != MediaStatus.PLAYER_STATE_IDLE
                if (!alive) {
                    // Finished or gone ⇒ silence: no bar, no toast, no error — the glyph returns to idle.
                    castContext.sessionManager.endCurrentSession(false)
                } else {
                    send(json.encodeToString(dev.jellystructure.shared.tv.CastCommand.serializer(), dev.jellystructure.shared.tv.CastCommand("status")))
                    rebuildStatus()
                }
            }
            if (rmc == null) decide()
            else runCatching { rmc.requestStatus().setResultCallback { decide() } }.onFailure { decide() }
        }
        override fun onSessionResumeFailed(s: CastSession, error: Int) { detach(); _link.value = CastLinkState.NONE }
        override fun onSessionSuspended(s: CastSession, reason: Int) { if (rejoin == null) _link.value = CastLinkState.RECONNECTING }
    }

    init {
        castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        castContext.sessionManager.currentCastSession?.let { s ->
            // A resume the SDK began before this sender existed (it starts at CastContext's creation and
            // again on entering the foreground) is RECONNECTING, not connected: the sheet's route list
            // scans actively while it is, which is what lets the SDK find the route and finish it.
            if (!s.isConnected) { _link.value = CastLinkState.RECONNECTING; _device.value = s.castDevice?.friendlyName ?: selectedRouteName(); return@let }
            attach(s); _link.value = CastLinkState.CONNECTED; rebuildStatus()
        }
        // R355 — route events only (no discovery request, R293); the group's members are read off the selected route.
        runCatching { router?.addCallback(androidx.mediarouter.media.MediaRouteSelector.EMPTY, groupCallback, androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_UNFILTERED_EVENTS) }
    }

    private fun attach(s: CastSession) {
        session = s
        sizeLogged = false; askedQueueRev = null
        CastSessionRemote.start(appContext, this)   // R356 (FR-R356-1) — Ravilo's one media card mirrors this cast
        runCatching { s.addCastListener(castListener) }
        _volume.value = runCatching { s.volume }.getOrNull()
        // Right after a resume the session may not carry its device yet; the route the SDK selected
        // for it does, and it is the same name the viewer picked it by. R355 — grouped, it is the group's speakers.
        sessionName = s.castDevice?.friendlyName ?: selectedRouteName()
        refreshName()
        runCatching { s.setMessageReceivedCallbacks(CAST_NAMESPACE, messageCallback) }
        s.remoteMediaClient?.let { rmc ->
            rmc.registerCallback(mediaCallback)
            rmc.addProgressListener(progressListener, 1_000L)
        }
    }

    private fun selectedRouteName(): String? = runCatching {
        androidx.mediarouter.media.MediaRouter.getInstance(appContext).selectedRoute.takeIf { !it.isDefaultOrBluetooth }?.name
    }.getOrNull()

    private fun detach() {
        runCatching { session?.removeCastListener(castListener) }
        _volume.value = null
        session?.remoteMediaClient?.let { rmc ->
            rmc.unregisterCallback(mediaCallback)
            rmc.removeProgressListener(progressListener)
        }
        runCatching { session?.removeMessageReceivedCallbacks(CAST_NAMESPACE) }
        session = null
        sessionName = null
        _members.value = emptyList()
    }

    /** Everything the remote shows is rebuilt from the SDK's media status + the receiver's last message — by the
     *  common [mergeCastStatus] (R330 FR-R330-3), which the Mac's own sender uses too. */
    private fun rebuildStatus(event: String? = null) {
        _status.value = mergeCastStatus(_status.value, receiverSaid, mediaSnapshot(), event, System.currentTimeMillis())
    }

    /** The Cast SDK's view of the receiver's media, in [mergeCastStatus]'s neutral shape. */
    private fun mediaSnapshot(): CastMediaSnapshot {
        val rmc = session?.remoteMediaClient
        val ms = rmc?.mediaStatus
        val info = rmc?.mediaInfo
        val meta = info?.metadata
        return CastMediaSnapshot(
            playerState = ms?.playerState?.let {
                when (it) {
                    MediaStatus.PLAYER_STATE_PLAYING -> "PLAYING"
                    MediaStatus.PLAYER_STATE_PAUSED -> "PAUSED"
                    MediaStatus.PLAYER_STATE_BUFFERING -> "BUFFERING"
                    MediaStatus.PLAYER_STATE_LOADING -> "LOADING"
                    else -> "IDLE"
                }
            },
            idleFinished = ms != null && ms.playerState == MediaStatus.PLAYER_STATE_IDLE && ms.idleReason == MediaStatus.IDLE_REASON_FINISHED,
            // R356 (FR-R356-7) — with no media status the SDK says 0 for both: no word, not "at 0:00".
            positionMs = rmc?.approximateStreamPosition?.takeIf { ms != null },
            durationMs = rmc?.streamDuration?.takeIf { ms != null },
            activeTrackIds = ms?.activeTrackIds?.toSet() ?: emptySet(),
            mediaTrackIds = info?.mediaTracks?.map { it.id }?.toSet(),
            title = meta?.getString(MediaMetadata.KEY_TITLE),
            subtitle = meta?.getString(MediaMetadata.KEY_SUBTITLE),
            imageUrl = meta?.images?.firstOrNull()?.url?.toString(),
        )
    }

    // R245 amendment 3 (2026-09-18) — every Cast SDK entry point (CastContext, SessionManager,
    // RemoteMediaClient, CastSession.sendMessage) throws "Must be called from the main thread" off it.
    // The shared CastController drives `load` from a Dispatchers.Default coroutine (the hand-off code is
    // fetched first), which killed the app the moment a title was cast — the second crash of the first
    // real cast. The seam owns the SDK, so the seam owns the thread: every command hops to main.
    private val main = Handler(Looper.getMainLooper())
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    /** The receiver id the SDK is using now: what [RaviloCastOptionsProvider] started it with. */
    private var appliedAppId: String? = RaviloCastOptionsProvider.lastAppId(appContext)

    override fun setAppId(appId: String) = onMain {
        RaviloCastOptionsProvider.rememberAppId(appContext, appId)
        // Only on a real change: setReceiverApplicationId ENDS the current session, even when handed the
        // id it already has (seen on the Pixel 9 — "End session" 200 ms into a resume, the moment the
        // config loaded). Re-applying the same id on every config load was the other half of why the
        // phone never rejoined a TV after a restart.
        if (appId == appliedAppId) return@onMain
        appliedAppId = appId
        runCatching { castContext.setReceiverApplicationId(appId) }
    }

    override fun load(data: CastLoadData) = onMain { loadOnMain(data) }

    private fun loadOnMain(data: CastLoadData) {
        val s = session ?: run { pendingLoad = data; return }
        pendingLoad = null
        val rmc = s.remoteMediaClient ?: return
        // R359 (FR-R359-1/3) — a queue too long for one message goes as a window, the rest at once in parts.
        val plan = castLoadPlan(data, newCastQueueId(), json)
        val req = castLoadRequest(plan.load)
        receiverSaid = null
        queueParts.reset()
        // The remote holds the whole queue from the start; the receiver says each song's place in the whole of it.
        _status.value = CastRemoteStatus(itemId = data.itemId, title = data.title, kicker = data.kicker, artUrl = data.artUrl, loaded = true, buffering = true,
            music = data.tracks.isNotEmpty(), queue = data.tracks, queueIndex = data.currentIndex, repeat = data.repeat, shuffle = data.shuffle)
        rmc.load(req)
        plan.parts.forEach { sendRaw(json.encodeToString(dev.jellystructure.shared.tv.CastCommand.serializer(), it)) }
        // FR-R359-7 — the LOAD's size and the number of parts, so a future limit is visible in the log.
        val bytes = runCatching { castWireBytes(req.toJson().toString()) }.getOrDefault(0)
        android.util.Log.i("RaviloCast", "R359: load ${if (data.tracks.isNotEmpty()) "music, ${castLoadLog(data.tracks.size, bytes, plan.parts.size)}" else "a film, ${(bytes + 512) / 1024} KB"}")
    }

    /** R324 (FR-R324-5) — the cast session's volume, in 5 % steps from the ⋯ slider; the keys reach it by themselves. */
    override fun setVolume(level: Double) = onMain { runCatching { session?.volume = level.coerceIn(0.0, 1.0) } }

    override fun play() = onMain { session?.remoteMediaClient?.play(); commandSent() }
    override fun pause() = onMain { session?.remoteMediaClient?.pause(); commandSent() }
    override fun seekTo(positionMs: Long) = onMain {
        commandSent()
        session?.remoteMediaClient?.seek(com.google.android.gms.cast.MediaSeekOptions.Builder().setPosition(positionMs.coerceAtLeast(0)).build())
    }
    override fun stop() = onMain { castContext.sessionManager.endCurrentSession(true) }
    /** R285 — a listed subtitle with no CAF Track behind it is a burn-in (PGS) candidate. */
    private fun isBurnIn(track: dev.jellystructure.shared.tv.CastTrack?): Boolean = isCastBurnIn(track, mediaSnapshot())

    private fun command(type: String, index: Int) =
        send(json.encodeToString(dev.jellystructure.shared.tv.CastCommand.serializer(), dev.jellystructure.shared.tv.CastCommand(type, index = index)))

    override fun selectSubtitle(trackId: Long?) = onMain {
        val rmc = session?.remoteMediaClient ?: return@onMain
        // R285 (FR-R285-4) — CAF can only switch between text tracks. Picking a burn-in, or picking
        // ANYTHING (Off included) while one is burned in, needs the receiver to restream — so it goes
        // to the receiver as a position in its own list. Plain text-to-text stays CAF, which is also
        // what keeps this sender working against a receiver that predates the command.
        val subs = receiverSaid?.subtitleTracks ?: emptyList()
        val target = subs.indexOfFirst { it.trackId == trackId }
        val burnedNow = isBurnIn(subs.getOrNull(receiverSaid?.selectedSub ?: -1))
        if (burnedNow || isBurnIn(subs.getOrNull(target))) { command("subtitle", if (trackId == null) -1 else target); return@onMain }
        val keepAudio = rmc.mediaStatus?.activeTrackIds?.filter { id -> rmc.mediaInfo?.mediaTracks?.any { it.id == id && it.type == MediaTrack.TYPE_AUDIO } == true } ?: emptyList()
        rmc.setActiveMediaTracks((keepAudio + listOfNotNull(trackId)).toLongArray())
    }
    override fun selectAudio(trackId: Long?) = onMain {
        val rmc = session?.remoteMediaClient ?: return@onMain
        // R285 (FR-R285-4) — an HLS cast carries one audio track: changing it is the receiver's restream.
        val position = receiverSaid?.audioTracks?.indexOfFirst { it.trackId != null && it.trackId == trackId } ?: -1
        if (position >= 0 && rmc.mediaInfo?.mediaTracks?.none { it.id == trackId } != false) { command("audio", position); return@onMain }
        val keepText = rmc.mediaStatus?.activeTrackIds?.filter { id -> rmc.mediaInfo?.mediaTracks?.any { it.id == id && it.type == MediaTrack.TYPE_TEXT } == true } ?: emptyList()
        rmc.setActiveMediaTracks((keepText + listOfNotNull(trackId)).toLongArray())
    }
    override fun send(json: String) = onMain { runCatching { session?.sendMessage(CAST_NAMESPACE, json) }; commandSent() }
    /** R356 — a message that expects no answer of its own (asking for status, for the queue): no nudge. */
    private fun sendRaw(json: String) = onMain { runCatching { session?.sendMessage(CAST_NAMESPACE, json) } }
}
