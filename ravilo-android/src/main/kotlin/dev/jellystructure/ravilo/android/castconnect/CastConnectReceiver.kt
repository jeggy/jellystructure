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
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.CastReceiverOptions
import com.google.android.gms.cast.tv.ReceiverOptionsProvider
import com.google.android.gms.cast.tv.media.MediaException
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import dev.jellystructure.ravilo.ui.seams.CastConnectInbox
import dev.jellystructure.ravilo.ui.seams.TvPlayerSessionHooks
import dev.jellystructure.ravilo.ui.seams.castConnectPlayFromJson
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * R266 (FR-R266-2) — Cast Connect's receiver options, named in the manifest's
 * `com.google.android.gms.cast.tv.RECEIVER_OPTIONS_PROVIDER_CLASS_NAME`. No application id is pinned: whichever Cast
 * application lists this package (the household's, or the development one for a `.debug` build) may launch it.
 */
class RaviloReceiverOptionsProvider : ReceiverOptionsProvider {
    override fun getOptions(context: Context): CastReceiverOptions =
        CastReceiverOptions.Builder(context).setStatusText("Ravilo").build()
}

/**
 * R266 — the Android TV app as a Cast receiver. Cast Connect carries the launch and the transport; the play travels
 * 236's road ([dev.jellystructure.ravilo.ui.RaviloApp] takes it from [CastConnectInbox] and opens an ordinary player
 * under the casting viewer's own token on this TV, or refuses). No hand-off is redeemed and no device is minted.
 *
 * Lifecycle (FR-R266-2): initialised once in [dev.jellystructure.ravilo.android.RaviloApplication] (TV only), started
 * while the TV activity is started, stopped when it stops — never held across a backgrounded player. The media
 * session is R44's own (dev review item 4): its token is handed to [com.google.android.gms.cast.tv.media.MediaManager]
 * whenever the player creates or releases it, so the phone's remote drives the same session the TV remote does.
 */
object CastConnectReceiver {
    private const val TAG = "RaviloCastConnect"
    @Volatile private var ready = false
    private var started = false
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    fun isTelevision(context: Context): Boolean =
        runCatching { (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION }
            .getOrDefault(false)

    /** Once, from Application.onCreate. A phone (the same APK) never becomes a receiver. */
    fun init(app: Application) {
        if (ready || !isTelevision(app)) return
        runCatching {
            CastReceiverContext.initInstance(app)
            CastReceiverContext.getInstance().mediaManager.setMediaLoadCommandCallback(LoadCallback())
            TvPlayerSessionHooks.listener = { s -> if (Looper.myLooper() == Looper.getMainLooper()) bindSession(s) else main.post { bindSession(s) } }
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

    /** FR-R266-3 — a LOAD is a Ravilo play request (customData), never a Jellyfin URL. */
    private class LoadCallback : MediaLoadCommandCallback() {
        override fun onLoad(senderId: String?, request: MediaLoadRequestData): Task<MediaLoadRequestData> {
            // R359 — the phone puts CastLoadData on the MEDIA's customData; the request's own is read as a fallback.
            val custom = request.mediaInfo?.customData ?: request.customData
            val play = castConnectPlayFromJson(custom?.toString())
                ?: run {
                    Log.w(TAG, "R266: a LOAD from $senderId that is not a Ravilo film/episode play; refused")
                    return Tasks.forException(refusal())
                }
            val result = TaskCompletionSource<MediaLoadRequestData>()
            scope.launch {
                val ok = CastConnectInbox.submit(play)
                if (!ok) {
                    Log.w(TAG, "R266: the LOAD of ${play.itemId} was refused (no token for the viewer here, or not ready)")
                    result.trySetException(refusal())
                    return@launch
                }
                TvPlayerSessionHooks.castAccepted()
                TvPlayerSessionHooks.current?.let { bindSession(it) }
                runCatching {
                    val mm = CastReceiverContext.getInstance().mediaManager
                    mm.setDataFromLoad(request)
                    mm.broadcastMediaStatus()
                }
                result.trySetResult(request)
            }
            return result.task
        }
    }
}
