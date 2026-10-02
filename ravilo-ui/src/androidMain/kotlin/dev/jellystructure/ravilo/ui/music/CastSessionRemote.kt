package dev.jellystructure.ravilo.ui.music

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.seams.CastSender
import dev.jellystructure.shared.tv.CastCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * R356 (FR-R356-1..4) — while the phone is the remote for a cast, Ravilo's own media session (the one
 * [RaviloMusicService] hosts for the phone's music, R322) plays a [CastRemotePlayer] that mirrors the cast: the
 * notification, the lock-screen card and the media keys are this one card, and Media3 keeps the service in the
 * foreground (type `mediaPlayback`) while the cast plays and for ten minutes after a pause. Without it the app had no
 * foreground service while casting (the phone plays nothing), Android froze it, and Play services dropped its Cast
 * connection once the frozen app's binder buffer filled — the card's *next* went nowhere and the app came back a dead
 * remote (the Pixel 9, 2026-10-02).
 *
 * Started once, by the Android sender, the first time a Cast session attaches. Everything runs on the main thread (the
 * session's and [MusicEngine]'s thread).
 */
@OptIn(UnstableApi::class)
internal object CastSessionRemote {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var raw: CastSender? = null
    private var player: CastRemotePlayer? = null
    /** The session plays the cast's mirror (not the phone's own player). */
    @Volatile var active: Boolean = false
        private set

    fun start(context: Context, sender: CastSender) {
        if (started) return
        started = true
        raw = sender
        dev.jellystructure.ravilo.ui.RaviloAppContext.init(context)
        // FR-R356-12 — the card is tied to the cast's MediaRouter2 routing session (Android 11+).
        if (android.os.Build.VERSION.SDK_INT >= 30) route = CastRouteWatch(context.applicationContext) { applyRoute() }.also { it.start() }
        scope.launch {
            // A song is the receiver's report read the way the Playing page reads it ([MusicCast.state]: the queue's own
            // items, with the album covers the app holds) — straight from the report, so the card does not drop out for
            // the moment between the report and the music bridge following it.
            // R358 (FR-R358-2) — as the bar shows it: the hand-over's place until the device reports one of its own.
            combine(sender.link, sender.status, sender.volume) { link, raw, vol ->
                val st = MusicCast.shown(link, raw)
                link to castCard(link, st, vol, st?.takeIf { it.music }?.let { MusicCast.state(it) })
            }.collect { (link, card) -> show(card, link == CastLinkState.CONNECTED) }
        }
    }

    private var dropJob: Job? = null
    private var route: CastRouteWatch? = null

    /** FR-R356-12 — the cast's routing controller id onto the card's player (null: none, or not one we can tell). */
    private fun applyRoute() {
        val id = route?.controllerId()
        player?.let { if (it.routingControllerId != id) { it.routingControllerId = id; android.util.Log.i("RaviloCast", "R356: the media card's route ${id ?: "(none)"}") } }
    }

    private fun show(card: CastCard?, connected: Boolean) {
        if (card == null) {
            if (!active) return
            // Between two songs the device reports "finished" for a moment before the next one loads: the card stays (a
            // swap to the phone's own player and back would drop the foreground service, which may not start again from
            // the background). Only a session that ended, or no song for a while, gives the card back.
            if (connected) { if (dropJob == null) dropJob = scope.launch { delay(CARD_GRACE_MS); drop() }; return }
            drop()
            return
        }
        dropJob?.cancel(); dropJob = null
        val p = player ?: CastRemotePlayer(actions).also { player = it }
        if (!active) applyRoute()
        p.show(card.copy(artUrl = card.artUrl?.let { MusicEngine.artworkUri(it) }))
        // Also after the service went away (the app removed from recents): the next report gives the mirror a new one.
        if (!active || !MusicEngine.castingRemote) {
            if (!active) android.util.Log.i("RaviloCast", "R356: the media card mirrors the cast (${if (card.music) "music" else "a film"})")
            active = true
            MusicEngine.useRemote(p)
        }
    }

    private fun drop() {
        dropJob?.cancel(); dropJob = null
        if (!active) return
        active = false
        android.util.Log.i("RaviloCast", "R356: the cast ended; the media card is the phone's own player again")
        MusicEngine.useRemote(null)
    }

    /** How long the card outlives a report with no live song while the session stays connected. */
    private const val CARD_GRACE_MS = 5_000L

    /** The sender the app's screens use (it routes to whichever device is linked); the raw one before the app bound it. */
    private fun sender(): CastSender? = MusicCast.controller?.sender ?: raw
    private fun music(): Boolean = MusicCast.linked.value

    private val actions = object : CastRemotePlayer.Actions {
        override fun play() { if (music()) MusicPlayback.play() else sender()?.play() }
        override fun pause() { if (music()) MusicPlayback.pause() else sender()?.pause() }
        override fun next() {
            if (music()) MusicPlayback.next()
            else sender()?.send(dev.jellystructure.shared.tv.RaviloWireJsonWithDefaults.encodeToString(CastCommand.serializer(), CastCommand("next")))
        }
        override fun previous() { if (music()) MusicPlayback.previous() else sender()?.seekTo(0L) }
        override fun seekTo(positionMs: Long) { if (music()) MusicPlayback.seekTo(positionMs) else sender()?.seekTo(positionMs) }
        override fun setVolume(level: Double) { sender()?.setVolume(level.coerceIn(0.0, 1.0)) }
    }

    /** The card's custom action for *Stop casting* (the ■ beside the transport). */
    const val STOP_CASTING_ACTION = "ravilo.cast.stop"

    /**
     * FR-R356-14a — the card's custom actions: true when [action] is one of the cast's (and it was carried out). The
     * MediaSession callback asks this first; a book's ±30 s are the engine's own.
     */
    fun onCardAction(action: String): Boolean {
        if (action != STOP_CASTING_ACTION) return false
        stopCasting()
        return true
    }

    /**
     * The card's *Stop casting* (FR-R356-3, FR-R356-14a): the Cast session ends with the receiver
     * stopped, as the app's own *Stop casting* does; a song comes back to the phone paused at its place (R324's *Stop
     * casting*, or — no live song — R353's hand-back from the last one when the session ends).
     */
    fun stopCasting() {
        android.util.Log.i("RaviloCast", "R356: the media card's Stop: ending the cast (${if (music()) "music" else "no live song"})")
        // Without a controller the app's screens never bound one (a process the service kept): the raw sender ends it.
        if (MusicCast.controller != null && (music() || MusicCast.holdsDevice)) MusicCast.stop()
        else (MusicCast.controller?.stopCasting() ?: raw?.stop())
    }
}

/**
 * R356 — a Media3 player that is a mirror of a cast: its state is the [CastCard] the sender last reported, and every
 * command goes to the device through [Actions]. One media item per song or film (the card's key), plus a placeholder
 * after it when there is a next, so the platform offers *next*; *previous* is always offered for a song (the device
 * applies the 3-second rule). Remote playback: the volume keys move the device's volume in 5 % steps.
 */
@OptIn(UnstableApi::class)
internal class CastRemotePlayer(private val actions: Actions) : SimpleBasePlayer(Looper.getMainLooper()) {
    interface Actions {
        fun play()
        fun pause()
        fun next()
        fun previous()
        fun seekTo(positionMs: Long)
        fun setVolume(level: Double)
    }

    /** FR-R356-12 — the cast's MediaRouter2 routing controller id, carried on the [DeviceInfo]; null = none known. */
    var routingControllerId: String? = null
        set(value) { if (field != value) { field = value; invalidateState() } }

    private var card: CastCard? = null
    private var position: PositionSupplier = PositionSupplier.getConstant(0)

    fun show(c: CastCard) {
        position = if (c.playing) PositionSupplier.getExtrapolating(c.positionMs, 1f) else PositionSupplier.getConstant(c.positionMs)
        card = c
        invalidateState()
    }

    private fun volumeStep(c: CastCard?): Int = ((c?.volume ?: 0.0) * VOLUME_STEPS).roundToInt().coerceIn(0, VOLUME_STEPS)

    override fun getState(): State {
        val c = card ?: return State.Builder().setAvailableCommands(Player.Commands.EMPTY).build()
        val meta = MediaMetadata.Builder()
            .setTitle(c.title)
            .setArtist(c.subtitle)
            .setAlbumTitle(c.album)
            .apply { c.artUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .setMediaType(if (c.music) MediaMetadata.MEDIA_TYPE_MUSIC else MediaMetadata.MEDIA_TYPE_MOVIE)
            .build()
        val now = MediaItemData.Builder("now:${c.key}")
            .setMediaItem(MediaItem.Builder().setMediaId(c.key).setMediaMetadata(meta).build())
            .setMediaMetadata(meta)
            .setDurationUs(if (c.durationMs > 0) c.durationMs * 1000 else C.TIME_UNSET)
            .setIsSeekable(true)
            .build()
        val playlist = if (c.hasNext) listOf(now, MediaItemData.Builder("next:${c.key}").setMediaItem(MediaItem.Builder().setMediaId("next:${c.key}").build()).build()) else listOf(now)
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_DEVICE_VOLUME,
            Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        ).apply {
            if (c.music) addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            else addAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
            if (c.hasNext) addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }.build()
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(c.playing || c.buffering, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(if (c.buffering) Player.STATE_BUFFERING else Player.STATE_READY)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(position)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(30_000L)
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMaxVolume(VOLUME_STEPS).setRoutingControllerId(routingControllerId).build())
            .setDeviceVolume(volumeStep(c))
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) actions.play() else actions.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> actions.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> actions.previous()
            else -> if (positionMs != C.TIME_UNSET) actions.seekTo(positionMs.coerceAtLeast(0L))
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        actions.setVolume(deviceVolume.coerceIn(0, VOLUME_STEPS) / VOLUME_STEPS.toDouble())
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        actions.setVolume((volumeStep(card) + 1).coerceAtMost(VOLUME_STEPS) / VOLUME_STEPS.toDouble())
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        actions.setVolume((volumeStep(card) - 1).coerceAtLeast(0) / VOLUME_STEPS.toDouble())
        return Futures.immediateVoidFuture()
    }

    private companion object {
        /** R324's slider step: 5 %. */
        const val VOLUME_STEPS = 20
    }
}

/**
 * R356 (FR-R356-12) — which MediaRouter2 routing controller is the cast's, as Media3's own `RemoteCastPlayer` reads it:
 * the controllers' first is always the system's (local playback); exactly one other is the Cast session's. None, or
 * several (another app casting too), and it cannot be told — no id. Watches transfers and controller changes so the
 * card follows a session that starts, moves or ends. Registers with an empty discovery preference: nothing scans (R293).
 */
@androidx.annotation.RequiresApi(30)
internal class CastRouteWatch(context: Context, private val onChange: () -> Unit) {
    private val router = android.media.MediaRouter2.getInstance(context)
    private val handler = android.os.Handler(Looper.getMainLooper())
    private val executor = java.util.concurrent.Executor { handler.post(it) }
    private val transfer = object : android.media.MediaRouter2.TransferCallback() {
        override fun onTransfer(oldController: android.media.MediaRouter2.RoutingController, newController: android.media.MediaRouter2.RoutingController) = onChange()
        override fun onStop(controller: android.media.MediaRouter2.RoutingController) = onChange()
    }
    private val controllers = object : android.media.MediaRouter2.ControllerCallback() {
        override fun onControllerUpdated(controller: android.media.MediaRouter2.RoutingController) = onChange()
    }
    /** Needed for the transfer callbacks to arrive at all (Media3 registers the same empty one). */
    private val routes = object : android.media.MediaRouter2.RouteCallback() {}

    fun start() {
        runCatching {
            router.registerTransferCallback(executor, transfer)
            router.registerControllerCallback(executor, controllers)
            router.registerRouteCallback(executor, routes, android.media.RouteDiscoveryPreference.Builder(emptyList(), false).build())
        }.onFailure { android.util.Log.w("RaviloCast", "R356: no MediaRouter2 route watch (${it.message})") }
    }

    fun controllerId(): String? = runCatching { castRouteControllerId(router.controllers.map { it.id }) }.getOrNull()
}
