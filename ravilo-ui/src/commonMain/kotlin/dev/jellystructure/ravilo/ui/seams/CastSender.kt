package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.CastTrack
import dev.jellystructure.shared.tv.CastTrackItem
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.flow.StateFlow

/**
 * R245 — the phone's side of a cast, as the platform Cast SDK exposes it. The receiver is its own
 * Ravilo device (218 FR-218-9), so this is a REMOTE: it never owns the truth about what is playing —
 * everything in [status] is rebuilt from what the receiver reports (FR-R245-5), never from anything the
 * phone remembered before it died.
 *
 * Android: the Cast SDK (`CastContext` / `RemoteMediaClient` / a custom-namespace channel). Web: there
 * is no Chromecast sender, only the screen one (R265). Either way the entry point is Ravilo's own sheet
 * behind [dev.jellystructure.ravilo.ui.components.CastButton] — R265 retired the SDK's `MediaRouteButton`.
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
    // R324 (FR-R324-4) — the receiver's music snapshot (286 dev review 10): the queue, where it is, and its modes.
    val music: Boolean = false,
    val queue: List<CastTrackItem> = emptyList(),
    val queueIndex: Int = -1,
    /** `off` · `all` · `one` */
    val repeat: String = "off",
    val shuffle: Boolean = false,
    /** FR-286-6 — lyrics on the display; null = a speaker (nothing to show them on). */
    val lyricsOn: Boolean? = null,
    /** R356 (FR-R356-9) — the receiver's revision of [queue]; null from a receiver that predates R356 or before any. */
    val queueRev: Int? = null,
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
    /**
     * R370 (owner decision 1) — drops this app's link and leaves the receiver playing (a relay launch, or a move that
     * hands the session to another place). A no-op where the platform cannot leave without stopping.
     */
    fun leave() {}
    /** Selects a subtitle by the receiver's track id; null = off. */
    fun selectSubtitle(trackId: Long?)
    fun selectAudio(trackId: Long?)
    /** A [dev.jellystructure.shared.tv.CastCommand], already JSON-encoded, on the custom namespace. */
    fun send(json: String)
    /** R324 (FR-R324-5) — the receiver's volume, 0.0–1.0; a no-op where the platform has no session volume. */
    fun setVolume(level: Double) {}
    /** R337 — the device's own volume as it last reported it, 0.0–1.0; null where the platform does not say. */
    val volume: StateFlow<Double?> get() = UNKNOWN_VOLUME
    /**
     * R355 (FR-R355-1) — the speakers the session plays on when the platform grouped them (Android's output panel ⊕
     * adds a speaker to a music session: a Cast *dynamic group*). Empty when it plays on one device, on a group made in
     * Google Home (one route), and on every platform that cannot say.
     */
    val members: StateFlow<List<String>> get() = NO_MEMBERS
    /**
     * R356 (FR-R356-6) — the app came on screen: with a session connected, ask the receiver where it is, and rejoin it
     * when nothing answers. A no-op where the platform's connection cannot go silent behind the app's back.
     */
    fun onAppForeground() {}
}

private val UNKNOWN_VOLUME: StateFlow<Double?> = kotlinx.coroutines.flow.MutableStateFlow(null)
private val NO_MEMBERS: StateFlow<List<String>> = kotlinx.coroutines.flow.MutableStateFlow(emptyList())

/**
 * R355 (FR-R355-2) — the name the phone shows for a session that plays on [members] (a group Android's output panel
 * made). One or no member: [sessionName] as it is. Two or three: the speaker that was playing first, then the others in
 * the platform's order, joined with ` + ` ("Gæsteværelse + Stue"). Four or more: the first and how many more ("Stue +
 * 3", Cast's own form). The first speaker is the member [sessionName] is or starts with — Cast renames the session
 * "Gæsteværelse + 1" once a speaker joins — else the platform's first.
 */
fun castSessionName(sessionName: String?, members: List<String>): String? {
    val names = members.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (names.size < 2) return sessionName
    val said = sessionName?.trim()
    val first = names.firstOrNull { said != null && (said.equals(it, ignoreCase = true) || said.startsWith("$it + ", ignoreCase = true)) } ?: names.first()
    val others = names.filter { it != first }
    return if (names.size <= 3) (listOf(first) + others).joinToString(" + ") else "$first + ${others.size}"
}

/**
 * R355 (FR-R355-3) — a route belongs to this session when its name is the session's own ([sessionName], the name the
 * phone shows) or one of the speakers the session was grouped onto ([members]) — or, while grouped, the group's own
 * route, which Cast names "{a member} + {n}" ("Gæsteværelse + 1"). Such a row reads as playing this session in
 * *Play on…* and a tap on it changes nothing.
 */
fun castRouteInSession(routeName: String, sessionName: String?, members: List<String>): Boolean {
    val n = routeName.trim()
    if (n.isEmpty()) return false
    if (sessionName != null && sessionName.trim().equals(n, ignoreCase = true)) return true
    val names = members.map { it.trim() }.filter { it.isNotEmpty() }
    if (names.any { it.equals(n, ignoreCase = true) }) return true
    return names.size >= 2 && names.any { m -> n.startsWith("$m + ", ignoreCase = true) && n.substring(m.length + 3).trim() == (names.size - 1).toString() }
}

/**
 * R265 — never null: every platform has at least [ScreenSender] (commonMain, no platform SDK needed).
 * The returned [ActiveCastSender] composes it with the platform's own Chromecast sender where one exists
 * (Android only) — see that class's own doc for the "at most one linked at a time" rule.
 */
@Composable
expect fun rememberCastSender(api: TvApiClient): ActiveCastSender


/**
 * R265 (FR-R265-3) — a Chromecast the platform's Cast SDK can see right now, listed as a row in Ravilo's
 * own "Play on a TV" sheet instead of in the platform's dialog. [select] hands the route to the SDK, which
 * starts the session exactly as its own dialog would (the dialog does nothing more than select a route),
 * so the sender, the hand-off code and the remote are untouched.
 */
class CastRoute(
    val id: String, val name: String, val selected: Boolean, val select: () -> Unit,
    /** R324 (dev review 1) — `display` · `speaker` · `group`; a route without a video output is a speaker. */
    val kind: String = "display",
    /** The receiver app another sender left running on it, as the route provider reports it; null = nothing known. */
    val busyWith: String? = null,
    /**
     * R370 (review item 3) — the Cast device's own id (Android's `CastDevice.deviceId`, the desktop's mDNS `id`): the one
     * key the apps and the server share for this device. Falls back to the route's id.
     */
    val deviceKey: String = id,
)

/**
 * R353 (FR-R353-1) — what a Cast route's status line says is running on the device, or null when it says nothing is.
 * The route provider puts the running receiver app's status there ("Spotify") — and, when NO app runs, the device's
 * model name ("Nest Wifi point", "Google Nest Hub"). Read as an app, the model name made every idle device *Busy* and
 * tapping an idle speaker asked to stop "Nest Wifi point" (Pixel 9, Android 16, 2026-10-02). A line that is only the
 * model name (or the device's own name) is an idle device.
 */
fun castRouteBusyWith(description: String?, modelName: String?, friendlyName: String? = null): String? {
    val said = description?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (listOfNotNull(modelName, friendlyName).any { it.trim().equals(said, ignoreCase = true) }) return null
    return said
}

/**
 * R265 (FR-R265-3) — the Chromecasts that answer for [appId], actively scanned for only while
 * [discovering] (the app is on screen — R293), listened for passively otherwise. Empty where there is no Cast SDK (the web)
 * and while [appId] is null (the server has no Chromecast capability: absent, never greyed).
 */
@Composable
expect fun rememberCastRoutes(appId: String?, discovering: Boolean): List<CastRoute>
