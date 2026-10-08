package dev.jellystructure.ravilo.android.castconnect

import android.app.Application
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.media3.session.MediaSession
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.CastReceiverOptions
import com.google.android.gms.cast.tv.ReceiverOptionsProvider
import com.google.android.gms.cast.tv.SenderInfo
import com.google.android.gms.cast.tv.media.MediaCommandCallback
import com.google.android.gms.cast.tv.media.MediaException
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import dev.jellystructure.ravilo.ui.seams.CastConnectInbox
import dev.jellystructure.ravilo.ui.seams.CastConnectMusic
import dev.jellystructure.ravilo.ui.seams.TvCastChannel
import dev.jellystructure.ravilo.ui.seams.TvMusicSession
import dev.jellystructure.ravilo.ui.seams.TvPlayerSessionHooks
import dev.jellystructure.ravilo.ui.seams.castConnectLoadFromJson
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * R266 (FR-R266-2) — Cast Connect's receiver options, named in the manifest's
 * `com.google.android.gms.cast.tv.RECEIVER_OPTIONS_PROVIDER_CLASS_NAME`. No application id is pinned: whichever Cast
 * application lists this package (the household's, or the development one for a `.debug` build) may launch it.
 * R380 (FR-R380-7) — Ravilo's own channel is declared, so the phone's remote reaches the TV app.
 */
class RaviloReceiverOptionsProvider : ReceiverOptionsProvider {
    override fun getOptions(context: Context): CastReceiverOptions =
        CastReceiverOptions.Builder(context).setStatusText("Ravilo").setCustomNamespaces(listOf(TvCastChannel.NAMESPACE)).build()
}

/**
 * R266 — the Android TV app as a Cast receiver. Cast Connect carries the launch and the transport; the play travels
 * 236's road ([dev.jellystructure.ravilo.ui.RaviloApp] takes it from [CastConnectInbox] and opens an ordinary player
 * under the casting viewer's own token on this TV, or refuses). No hand-off is redeemed and no device is minted.
 *
 * R380 — a music queue plays on the TV's own music engine; the phone's remote speaks Ravilo's Cast channel to the TV
 * exactly as it does to the web receiver ([TvCastChannel], the reading shared with it), and the media session Cast
 * Connect mirrors is the one the cast drives: the music service's for music, the video player's for a film.
 *
 * Lifecycle (FR-R266-2): initialised once in [dev.jellystructure.ravilo.android.RaviloApplication] (TV only), started
 * while the TV activity is started, stopped when it stops — never held across a backgrounded player. Owner, 2026-10-08:
 * leaving the app (Home) ends a music cast too.
 */
object CastConnectReceiver {
    private const val TAG = "RaviloCastConnect"
    @Volatile private var ready = false
    private var started = false
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    /** R380 — the cast now playing is a music queue: the music service's session is the one handed over. */
    @Volatile private var musicCast = false

    fun isTelevision(context: Context): Boolean =
        runCatching { (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION }
            .getOrDefault(false)

    /** Once, from Application.onCreate. A phone (the same APK) never becomes a receiver. */
    fun init(app: Application) {
        if (ready || !isTelevision(app)) return
        runCatching {
            CastReceiverContext.initInstance(app)
            val ctx = CastReceiverContext.getInstance()
            ctx.mediaManager.setMediaLoadCommandCallback(LoadCallback())
            ctx.mediaManager.setMediaCommandCallback(CommandCallback())
            // R380 (FR-R380-7) — the phone's remote: its commands in, the TV's status out (to every sender).
            ctx.setMessageReceivedListener(TvCastChannel.NAMESPACE) { _, _, message -> main.post { TvCastChannel.onMessage(message) } }
            ctx.registerEventCallback(object : CastReceiverContext.EventCallback() {
                override fun onSenderConnected(sender: SenderInfo) { main.post { TvCastChannel.senderConnected() } }
            })
            TvCastChannel.send = { text ->
                main.post { runCatching { CastReceiverContext.getInstance().sendMessage(TvCastChannel.NAMESPACE, null, text) }.onFailure { Log.w(TAG, "R380: status not sent: ${it.message}") } }
            }
            TvPlayerSessionHooks.listener = { s ->
                if (!musicCast) { if (Looper.myLooper() == Looper.getMainLooper()) bindSession(s) else main.post { bindSession(s) } }
            }
            ready = true
            TvPlayerSessionHooks.current?.let { bindSession(it) }
            Log.i(TAG, "R266: Cast Connect receiver ready")
        }.onFailure { Log.w(TAG, "R266: Cast Connect unavailable on this TV (${it.javaClass.simpleName}: ${it.message})") }
    }

    fun start() {
        if (!ready || started) return
        runCatching { CastReceiverContext.getInstance().start() }.onSuccess { started = true }
            .onFailure { Log.w(TAG, "R266: start failed: ${it.message}") }
    }

    fun stop() {
        if (!ready || !started) return
        started = false
        // Owner, 2026-10-08 — Home (the activity stopping) ends a music cast; a film's player is released anyway.
        TvCastChannel.receiverStopped()
        musicCast = false
        TvPlayerSessionHooks.castEnded()
        runCatching { CastReceiverContext.getInstance().stop() }
    }

    /** The activity's launch / new intent: true when it was a Cast Connect intent (LAUNCH or LOAD) the SDK took. */
    fun handleIntent(intent: Intent?): Boolean {
        if (!ready || intent == null) return false
        start()
        val took = runCatching { CastReceiverContext.getInstance().mediaManager.onNewIntent(intent) }.getOrDefault(false)
        if (took) Log.i(TAG, "R266: Cast Connect intent ${intent.action}")
        return took
    }

    private var boundToken: MediaSessionCompat.Token? = null

    private fun bindSession(session: MediaSession?) {
        if (!ready) return
        val mm = runCatching { CastReceiverContext.getInstance().mediaManager }.getOrNull() ?: return
        val token = session?.let { runCatching { MediaSessionCompat.Token.fromToken(it.platformToken) }.getOrNull() }
        if (token == boundToken) return
        boundToken = token
        runCatching { mm.setSessionCompatToken(token) }.onFailure { Log.w(TAG, "R266: session token not handed over: ${it.message}") }
        // The player closed under a cast (Back, or a 180 teardown): say so, so the phone's remote hears the end
        // (acceptance 6) rather than a session that never answers again.
        if (token == null) runCatching { mm.broadcastMediaStatus() }
        Log.i(TAG, "R266: media session ${if (token == null) "released" else "handed to Cast Connect"}")
    }

    private fun refusal(): MediaException =
        MediaException(MediaError.Builder().setReason(MediaError.ERROR_REASON_INVALID_REQUEST).build())

    /**
     * R380 — the LOAD as the TV reports it back: the phone's own media info, but with an explicitly EMPTY track list.
     * The phone (R285) then reads every subtitle and audio pick as one only the receiver can make and sends it on Ravilo's
     * channel, by its place in the TV's own lists — the same road it takes against the web receiver's restreams.
     */
    private fun withNoMediaTracks(request: MediaLoadRequestData): MediaLoadRequestData = runCatching {
        val mi = request.mediaInfo ?: return request
        val info = MediaInfo.Builder(mi.contentId ?: mi.contentUrl ?: "")
            .setContentType(mi.contentType ?: "video/*")
            .setStreamType(mi.streamType)
            .setMetadata(mi.metadata)
            .setCustomData(mi.customData)
            .setStreamDuration(mi.streamDuration)
            .setMediaTracks(emptyList<MediaTrack>())
            .build()
        MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(request.autoplay)
            .setCurrentTime(request.currentTime)
            .setCustomData(request.customData)
            .build()
    }.getOrDefault(request)

    /** FR-R266-3 — a LOAD is a Ravilo play request (customData), never a Jellyfin URL. */
    private class LoadCallback : MediaLoadCommandCallback() {
        override fun onLoad(senderId: String?, request: MediaLoadRequestData): Task<MediaLoadRequestData> {
            // R359 — the phone puts CastLoadData on the MEDIA's customData; the request's own is read as a fallback.
            val custom = request.mediaInfo?.customData ?: request.customData
            val load = castConnectLoadFromJson(custom?.toString())
                ?: run {
                    Log.w(TAG, "R266: a LOAD from $senderId that is not a Ravilo play; refused")
                    return Tasks.forException(refusal())
                }
            val result = TaskCompletionSource<MediaLoadRequestData>()
            scope.launch {
                val ok = CastConnectInbox.submit(load)
                if (!ok) {
                    Log.w(TAG, "R266: a LOAD was refused (no token for the viewer here, or not ready)")
                    result.trySetException(refusal())
                    return@launch
                }
                val music = load is CastConnectMusic
                musicCast = music
                if (music) {
                    // R380 (dev review item 4) — the music service's session is the one the cast drives.
                    TvPlayerSessionHooks.castEnded()
                    bindSession(TvMusicSession.session())
                } else {
                    TvPlayerSessionHooks.castAccepted()
                    TvPlayerSessionHooks.current?.let { bindSession(it) }
                }
                val reported = withNoMediaTracks(request)
                runCatching {
                    val mm = CastReceiverContext.getInstance().mediaManager
                    mm.setDataFromLoad(reported)
                    mm.broadcastMediaStatus()
                }
                result.trySetResult(reported)
            }
            return result.task
        }
    }

    /** R380 — Cast's own track selection, from a sender that used it instead of the channel: mapped to the TV's lists. */
    private class CommandCallback : MediaCommandCallback() {
        override fun onSelectTracksByType(senderId: String?, type: Int, tracks: List<MediaTrack>): Task<Void> {
            val ids = tracks.map { it.id }
            // The SDK resolves ids against the media info's tracks, which this receiver leaves empty: an empty list here
            // says nothing about the viewer's pick (subtitles off comes on the channel as `subtitle -1`).
            if (ids.isEmpty()) return Tasks.forResult(null)
            when (type) {
                MediaTrack.TYPE_TEXT -> main.post { TvCastChannel.onStandardTrackSelect(text = true, trackIds = ids) }
                MediaTrack.TYPE_AUDIO -> main.post { TvCastChannel.onStandardTrackSelect(text = false, trackIds = ids) }
            }
            return Tasks.forResult(null)
        }
    }
}
