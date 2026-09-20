package dev.jellystructure.ravilo.ui.seams

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import dev.jellystructure.shared.tv.CAST_NAMESPACE
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastReceiverMessage
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * R245 — the Cast SDK's options: a placeholder receiver id (the real one is applied at runtime from the
 * config snapshot, 218 FR-218-11) and the SDK's own media notification (FR-R245-11) with the four
 * actions the spec names. Registered in ravilo-android's manifest.
 */
class RaviloCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val notification = NotificationOptions.Builder()
            .setActions(
                listOf(
                    MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK,
                    MediaIntentReceiver.ACTION_REWIND,
                    MediaIntentReceiver.ACTION_FORWARD,
                    MediaIntentReceiver.ACTION_STOP_CASTING,
                ),
                intArrayOf(0, 3),
            )
            .setSkipStepMs(30_000L)
            .build()
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(notification)
            // No expanded controller is set: the remote is Ravilo's own screen.
            .build()
        return CastOptions.Builder()
            // A syntactically valid placeholder; CastContext.setReceiverApplicationId replaces it.
            .setReceiverApplicationId(PLACEHOLDER_APP_ID)
            .setCastMediaOptions(media)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }
    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
    companion object { const val PLACEHOLDER_APP_ID = "CC1AD845" }
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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

@Composable
actual fun PlatformCastButton(modifier: Modifier) {
    // FR-R245-1/2 — the platform's own Cast mark, in its two standard forms, opening the SYSTEM dialog.
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MediaRouteButton(ContextThemeWrapper(ctx, androidx.mediarouter.R.style.Theme_MediaRouter)).also { btn ->
                runCatching { CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, btn) }
            }
        },
    )
}

class CastSenderAndroid(private val appContext: Context) : CastSender {
    private val castContext: CastContext = CastContext.getSharedInstance(appContext)
    private val _link = MutableStateFlow(CastLinkState.NONE)
    private val _device = MutableStateFlow<String?>(null)
    private val _status = MutableStateFlow<CastRemoteStatus?>(null)
    override val link: StateFlow<CastLinkState> = _link
    override val deviceName: StateFlow<String?> = _device
    override val status: StateFlow<CastRemoteStatus?> = _status
    private var session: CastSession? = null
    /** The last message the receiver sent about the item/tracks — merged with the SDK's media status. */
    private var receiverSaid: CastReceiverMessage? = null
    private var pendingLoad: CastLoadData? = null

    private val progressListener = RemoteMediaClient.ProgressListener { position, duration ->
        _status.value = (_status.value ?: CastRemoteStatus()).copy(positionMs = position.coerceAtLeast(0), durationMs = duration.coerceAtLeast(0))
    }
    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() { rebuildStatus() }
        override fun onMetadataUpdated() { rebuildStatus() }
    }
    private val messageCallback = com.google.android.gms.cast.Cast.MessageReceivedCallback { _, _, message ->
        runCatching { json.decodeFromString(CastReceiverMessage.serializer(), message) }.getOrNull()?.let { msg ->
            receiverSaid = when (msg.type) {
                "status", "tracks" -> msg
                else -> (receiverSaid ?: msg).copy(type = msg.type, retryAfter = msg.retryAfter, sinceMs = msg.sinceMs, nextupSecs = msg.nextupSecs, nextTitle = msg.nextTitle, receiverId = msg.receiverId ?: receiverSaid?.receiverId)
            }
            rebuildStatus(msg.type)
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(s: CastSession) { _link.value = CastLinkState.CONNECTING; _device.value = s.castDevice?.friendlyName }
        override fun onSessionStarted(s: CastSession, sessionId: String) { attach(s); _link.value = CastLinkState.CONNECTED; pendingLoad?.let { loadOnMain(it) } }
        override fun onSessionStartFailed(s: CastSession, error: Int) { detach(); _link.value = CastLinkState.NONE }
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionEnded(s: CastSession, error: Int) { detach(); _link.value = CastLinkState.NONE; _status.value = null; receiverSaid = null }
        // FR-R245-5 — re-connect on app start: the SDK resumes; then ONE of two things happens (see onSessionResumed).
        override fun onSessionResuming(s: CastSession, sessionId: String) { _link.value = CastLinkState.RECONNECTING; _device.value = s.castDevice?.friendlyName }
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {
            attach(s)
            _link.value = CastLinkState.CONNECTED
            val rmc = s.remoteMediaClient
            val alive = rmc != null && rmc.mediaInfo != null && rmc.playerState != MediaStatus.PLAYER_STATE_IDLE
            if (!alive) {
                // Finished or gone ⇒ silence: no bar, no toast, no error — the glyph returns to idle.
                castContext.sessionManager.endCurrentSession(false)
            } else {
                send(json.encodeToString(dev.jellystructure.shared.tv.CastCommand.serializer(), dev.jellystructure.shared.tv.CastCommand("status")))
                rebuildStatus()
            }
        }
        override fun onSessionResumeFailed(s: CastSession, error: Int) { detach(); _link.value = CastLinkState.NONE }
        override fun onSessionSuspended(s: CastSession, reason: Int) { _link.value = CastLinkState.RECONNECTING }
    }

    init {
        castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        castContext.sessionManager.currentCastSession?.let { s ->
            attach(s); _link.value = CastLinkState.CONNECTED; _device.value = s.castDevice?.friendlyName; rebuildStatus()
        }
    }

    private fun attach(s: CastSession) {
        session = s
        _device.value = s.castDevice?.friendlyName
        runCatching { s.setMessageReceivedCallbacks(CAST_NAMESPACE, messageCallback) }
        s.remoteMediaClient?.let { rmc ->
            rmc.registerCallback(mediaCallback)
            rmc.addProgressListener(progressListener, 1_000L)
        }
    }

    private fun detach() {
        session?.remoteMediaClient?.let { rmc ->
            rmc.unregisterCallback(mediaCallback)
            rmc.removeProgressListener(progressListener)
        }
        runCatching { session?.removeMessageReceivedCallbacks(CAST_NAMESPACE) }
        session = null
    }

    /** Everything the remote shows is rebuilt here from the SDK's media status + the receiver's last message. */
    private fun rebuildStatus(event: String? = null) {
        val rmc = session?.remoteMediaClient
        val ms = rmc?.mediaStatus
        val info = rmc?.mediaInfo
        val meta = info?.metadata
        val said = receiverSaid
        val prev = _status.value ?: CastRemoteStatus()
        val idle = ms == null || ms.playerState == MediaStatus.PLAYER_STATE_IDLE
        val ended = event == "ended" || (ms != null && ms.playerState == MediaStatus.PLAYER_STATE_IDLE && ms.idleReason == MediaStatus.IDLE_REASON_FINISHED)
        val active = ms?.activeTrackIds?.toSet() ?: emptySet()
        val subs = said?.subtitleTracks ?: emptyList()
        // R285 — an active CAF text track is the selection; with none active, a burned-in subtitle
        // (which has no CAF track at all) is — and only the receiver can know that. Audio is never a
        // CAF track on an HLS cast, so the receiver's word is the only word.
        val selectedSub = subs.indexOfFirst { it.trackId != null && it.trackId in active }
            .takeIf { it >= 0 } ?: said?.selectedSub?.takeIf { isBurnIn(subs.getOrNull(it)) } ?: -1
        val audios = said?.audioTracks ?: emptyList()
        val selectedAudio = said?.selectedAudio ?: 0
        _status.value = prev.copy(
            itemId = said?.itemId ?: prev.itemId,
            title = said?.title ?: meta?.getString(MediaMetadata.KEY_TITLE) ?: prev.title,
            kicker = said?.kicker ?: meta?.getString(MediaMetadata.KEY_SUBTITLE) ?: prev.kicker,
            artUrl = said?.artUrl ?: meta?.images?.firstOrNull()?.url?.toString() ?: prev.artUrl,
            positionMs = rmc?.approximateStreamPosition?.coerceAtLeast(0) ?: prev.positionMs,
            durationMs = rmc?.streamDuration?.coerceAtLeast(0) ?: prev.durationMs,
            playing = ms?.playerState == MediaStatus.PLAYER_STATE_PLAYING,
            buffering = ms?.playerState == MediaStatus.PLAYER_STATE_BUFFERING || ms?.playerState == MediaStatus.PLAYER_STATE_LOADING,
            loaded = !idle || said?.type == "status",
            ended = ended,
            hasNext = said?.hasNext ?: prev.hasNext,
            nextUpSecs = if (event == "nextup") said?.nextupSecs else if (event == "status" || ended) null else prev.nextUpSecs,
            nextTitle = said?.nextTitle ?: prev.nextTitle,
            busyRetryAfter = if (event == "busy") said?.retryAfter else if (event == "status" || event == "tracks") null else prev.busyRetryAfter,
            busySinceMs = if (event == "busy") (said?.sinceMs ?: System.currentTimeMillis()) else if (event == "status" || event == "tracks") null else prev.busySinceMs,
            noServer = if (event == "noserver") true else if (event == "status" || event == "tracks") false else prev.noServer,
            audioTracks = audios.ifEmpty { prev.audioTracks },
            subtitleTracks = subs.ifEmpty { prev.subtitleTracks },
            selectedAudio = if (audios.isNotEmpty()) selectedAudio else prev.selectedAudio,
            selectedSub = if (subs.isNotEmpty()) selectedSub else prev.selectedSub,
            transcoding = said?.transcoding ?: prev.transcoding,
            subSize = said?.subSize?.firstOrNull() ?: prev.subSize,
            receiverId = said?.receiverId ?: prev.receiverId,
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

    override fun setAppId(appId: String) = onMain {
        runCatching { castContext.setReceiverApplicationId(appId) }
    }

    override fun load(data: CastLoadData) = onMain { loadOnMain(data) }

    private fun loadOnMain(data: CastLoadData) {
        val s = session ?: run { pendingLoad = data; return }
        pendingLoad = null
        val rmc = s.remoteMediaClient ?: return
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, data.title)
            data.kicker?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            // FR-R245-11 — a landscape still/backdrop for the notification, never a poster.
            data.artUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }
        // contentId is resolved by the receiver (it enrols and negotiates the ticket itself); the phone
        // never hands it a media URL — that is the whole point of the receiver being its own device.
        val info = MediaInfo.Builder("ravilo://${data.itemId}")
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("application/x-mpegURL")
            .setMetadata(meta)
            .setCustomData(JSONObject(json.encodeToString(CastLoadData.serializer(), data)))
            .build()
        val req = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(data.positionMs ?: 0L)
            .setCustomData(JSONObject(json.encodeToString(CastLoadData.serializer(), data)))
            .build()
        receiverSaid = null
        _status.value = CastRemoteStatus(itemId = data.itemId, title = data.title, kicker = data.kicker, artUrl = data.artUrl, loaded = true, buffering = true)
        rmc.load(req)
    }

    override fun play() = onMain { session?.remoteMediaClient?.play() }
    override fun pause() = onMain { session?.remoteMediaClient?.pause() }
    override fun seekTo(positionMs: Long) = onMain {
        session?.remoteMediaClient?.seek(com.google.android.gms.cast.MediaSeekOptions.Builder().setPosition(positionMs.coerceAtLeast(0)).build())
    }
    override fun stop() = onMain { castContext.sessionManager.endCurrentSession(true) }
    /** R285 — a listed subtitle with no CAF Track behind it is a burn-in (PGS) candidate. */
    private fun isBurnIn(track: dev.jellystructure.shared.tv.CastTrack?): Boolean {
        val id = track?.trackId ?: return false
        return session?.remoteMediaClient?.mediaInfo?.mediaTracks?.none { it.id == id } ?: false
    }

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
    override fun send(json: String) = onMain { runCatching { session?.sendMessage(CAST_NAMESPACE, json) } }
}
