package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastTrack
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.flow.StateFlow

/**
 * R245 — the phone's side of a cast, as the platform Cast SDK exposes it. The receiver is its own
 * Ravilo device (218 FR-218-9), so this is a REMOTE: it never owns the truth about what is playing —
 * everything in [status] is rebuilt from what the receiver reports (FR-R245-5), never from anything the
 * phone remembered before it died.
 *
 * Android: the Cast SDK (`CastContext` / `RemoteMediaClient` / a custom-namespace channel). Web: there
 * is no sender — [rememberCastSender] returns null and [PlatformCastButton] draws nothing.
 */
enum class CastLinkState { NONE, CONNECTING, CONNECTED, RECONNECTING }

/** What the phone knows about the receiver right now — rebuilt from its reports, never remembered. */
data class CastRemoteStatus(
    val itemId: String? = null,
    val title: String? = null,
    val kicker: String? = null,
    val artUrl: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    /** Media is loaded on the receiver (the mini bar and the remote have something to show). */
    val loaded: Boolean = false,
    /** The receiver reported the item finished (FR-R245-9 · Ended). */
    val ended: Boolean = false,
    /** R299 (FR-R299-2) — the receiver could not play the item; it is idle and reachable. */
    val failed: Boolean = false,
    val hasNext: Boolean = false,
    /** FR-R245-9 · Next-up mirrored — the RECEIVER owns this countdown. */
    val nextUpSecs: Int? = null,
    val nextTitle: String? = null,
    /** FR-R245-9 · Server busy — phase 182's 503 as the receiver saw it, with when it started waiting. */
    val busyRetryAfter: Int? = null,
    val busySinceMs: Long? = null,
    val noServer: Boolean = false,
    val audioTracks: List<CastTrack> = emptyList(),
    val subtitleTracks: List<CastTrack> = emptyList(),
    val selectedAudio: Int = 0,
    val selectedSub: Int = -1,
    val subSize: Char = 'M',
    val receiverId: String? = null,
    /** FR-R245-19 — the receiver said its stream is a server-side conversion, not the original file. */
    val transcoding: Boolean = false,
)

interface CastSender {
    val link: StateFlow<CastLinkState>
    val deviceName: StateFlow<String?>
    val status: StateFlow<CastRemoteStatus?>
    /** 218 FR-218-11 — the per-installation app id, applied at runtime once the config snapshot loads. */
    fun setAppId(appId: String)
    fun load(data: CastLoadData)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    /** Ends the session (stops the receiver). Only ever explicit (FR-R245-10). */
    fun stop()
    /** Selects a subtitle by the receiver's track id; null = off. */
    fun selectSubtitle(trackId: Long?)
    fun selectAudio(trackId: Long?)
    /** A [dev.jellystructure.shared.tv.CastCommand], already JSON-encoded, on the custom namespace. */
    fun send(json: String)
}

/**
 * R265 — never null: every platform has at least [ScreenSender] (commonMain, no platform SDK needed).
 * The returned [ActiveCastSender] composes it with the platform's own Chromecast sender where one exists
 * (Android only) — see that class's own doc for the "at most one linked at a time" rule.
 */
@Composable
expect fun rememberCastSender(api: TvApiClient): ActiveCastSender

/** FR-R245-1/2 — the platform's own Cast mark and the platform's own device dialog. Nothing bespoke. */
@Composable
expect fun PlatformCastButton(modifier: Modifier = Modifier)
