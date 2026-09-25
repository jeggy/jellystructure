package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.ui.isTvPlatform
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.geometry.Rect
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.ActiveCastSender
import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.screens.castMiniBarVisible
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.seams.safeAreaPadding
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.CastCommand
import dev.jellystructure.shared.tv.CastEpisode
import dev.jellystructure.shared.tv.CastLoadData
import dev.jellystructure.shared.tv.RemoteDevice
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * R245 — one object the whole app reads through [LocalCast]: the sender (platform SDK), the server's
 * cast capability (218 FR-218-3 — present means a button exists; absent means NO button, never a
 * greyed one) and the hand-off (218 FR-218-9). Null in [LocalCast] when there is nothing to cast to on
 * this platform or from this server.
 */
class CastController(
    val sender: ActiveCastSender,
    private val api: TvApiClient,
    val serverUrl: String,
    /** The phone's own device name — the receiver's row is named after the Chromecast, not this. */
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // Snapshot state, not plain fields: the sheet's route listener keys on [appId], and a plain field
    // set after the config loads never recomposed it — the passive listener then only registered once
    // the sheet was opened, so the SDK could not resume a session at start-up.
    private var appIdState by mutableStateOf<String?>(null)
    var appId: String?
        get() = appIdState
        set(value) { appIdState = value; if (value != null) sender.setAppId(value) }
    /** R265 (dev review item 5) — the server's `RaviloConfig.screens.enabled`: the sheet lists screens
     *  and offers *Add a TV* only when this is true, exactly as [appId] gates the Chromecast rows. */
    var screensEnabled by mutableStateOf(false)
    /** The active viewer's (Jellyfin) user id — what a screen's `session_user_id` is compared with to
     *  tell *mine* (Playing, tappable) from *someone else's* (Busy, not tappable) — FR-R270-3. */
    var userId by mutableStateOf<String?>(null)

    val connected: Boolean get() = sender.link.value == CastLinkState.CONNECTED

    /**
     * FR-R245-4 — cast an item: mint a hand-off code under the phone's session and LOAD it with the
     * position (null = the server resolves the resume point, as for a TV). [lastReceiverId] rides
     * along so a receiver whose storage survived reuses its own device row (218 open question on
     * storage durability, handled both ways).
     *
     * R265 — when a SCREEN is already the connected device (not Chromecast), there is no hand-off code
     * to mint (FR-R265-6): the screen's socket is already open, so this just posts the new item to it
     * directly and returns. Every existing Chromecast call site (Detail/Player's "Play" while
     * `connected`) keeps working unchanged either way — it's this method that now looks at which side
     * is actually connected, not the caller.
     */
    fun cast(
        itemId: String, title: String, kicker: String?, artUrl: String?, positionMs: Long?,
        episodes: List<CastEpisode> = emptyList(), currentIndex: Int = -1, lang: String = "en", subSize: Char = 'M',
        onError: (Throwable) -> Unit = {},
    ) {
        if (sender.screen.link.value == CastLinkState.CONNECTED) {
            sender.screen.playItem(itemId, positionMs ?: 0)
            return
        }
        scope.launch {
            val code = runCatching { api.castHandoff() }.getOrElse { onError(it); return@launch }
            sender.load(CastLoadData(
                serverUrl = serverUrl,
                code = code.code,
                itemId = itemId, title = title, kicker = kicker, artUrl = artUrl,
                positionMs = positionMs,
                deviceName = sender.deviceName.value,
                receiverId = sender.status.value?.receiverId,
                episodes = episodes, currentIndex = currentIndex, lang = lang, subSize = subSize.toString(),
            ))
        }
    }

    /**
     * R265 (FR-R265-6) — starts [itemId] on a specific, not-yet-linked screen (a sheet row tapped with
     * something queued to play). Stops whichever side is currently connected first (dev review item 4's
     * "at most one linked at a time" — this covers the Chromecast-was-live direction; [cast] above covers
     * the reverse by checking [sender]'s own linked side before ever minting a hand-off code).
     */
    fun castOnScreen(device: RemoteDevice, itemId: String, startPositionMs: Long? = null) {
        if (sender.link.value == CastLinkState.CONNECTED) sender.stop()
        sender.screen.link(device)
        sender.screen.playItem(itemId, startPositionMs ?: 0)
    }

    /**
     * R265 (FR-R265-3) — a Chromecast row in Ravilo's own sheet. A linked screen is unlinked first (dev
     * review item 4: at most one linked at a time) — unlinked, not stopped: the phone stops watching
     * that TV, the TV keeps playing. The SDK then starts the session from the selected route exactly as
     * its own dialog would, and the in-player hand-off (FR-R245-4) fires on the connection as before.
     */
    fun castOnChromecast(route: dev.jellystructure.ravilo.ui.seams.CastRoute) {
        if (sender.screen.link.value != CastLinkState.NONE) sender.screen.unlink()
        route.select()
    }

    /**
     * R265 — the sheet is drawn ONCE, at the app's root ([dev.jellystructure.ravilo.ui.RaviloApp]), over
     * every screen; a glyph only asks for it. Found on the Pixel 9: drawn inside the glyph, as the first
     * build had it, the full-height sheet was laid out inside a 40 dp box in a 60 dp app bar, so tapping
     * the glyph never showed anything. Null = closed; otherwise what to start on a tapped screen.
     */
    val sheet = MutableStateFlow<SheetRequest?>(null)
    fun openSheet(playContext: ScreenPlayContext? = null) { sheet.value = SheetRequest(playContext) }
    fun closeSheet() { sheet.value = null }

    /** FR-R245-10 — the sheet's *Stop casting*: explicit, ends the session on whichever side is linked. */
    fun stopCasting() = sender.stop()

    /** R265 (FR-R265-5) — claims a code the TV is showing. Null = the sheet's one error sentence. */
    suspend fun pairScreen(code: String) = api.remotePair(code)

    /** R265 (FR-R265-2/3) — the sheet's own device list; `nearby` already resolved server-side. */
    suspend fun screenDevices() = runCatching { api.remoteDevices() }.getOrDefault(emptyList())

    /** R265 (FR-R265-7) — join an already-playing (or idle) screen without starting anything new: app
     *  start / a sheet row tapped with no item in mind. */
    fun joinScreen(device: RemoteDevice) {
        if (sender.link.value == CastLinkState.CONNECTED && sender.screen.link.value != CastLinkState.CONNECTED) sender.stop()
        sender.screen.link(device)
    }

    fun command(type: String, index: Int? = null, size: String? = null) {
        sender.send(json.encodeToString(CastCommand.serializer(), CastCommand(type, index, size)))
    }
}

/**
 * R265 (FR-R265-7) — the one screen the phone reconnects to on start or return: online, something loaded
 * and not finished, started by [userId]. Someone else's session is never adopted (236's 409 rule), and a
 * screen that does not say whose it is (`session_user_id` absent) is not assumed to be this viewer's.
 */
fun reconnectsTo(d: RemoteDevice, userId: String): Boolean {
    val np = d.nowPlaying ?: return false
    return d.online && np.loaded && !np.ended && np.sessionUserId == userId
}

/** R265 — an open "Play on a TV" sheet, and what it should start on a tapped screen (null: join only). */
data class SheetRequest(val playContext: ScreenPlayContext?)

val LocalCast = staticCompositionLocalOf<CastController?> { null }

/** R245 (FR-R245-4) — set by the app while the local player is on screen: called with the live position
 *  the instant a cast session connects, so the player stops and the remote takes over — one act. */
val LocalCastHandoff = staticCompositionLocalOf<((positionMs: Long) -> Unit)?> { null }

/**
 * FR-R245-1 / R265 FR-R265-1 — the cast button, on every app bar, present when EITHER capability exists
 * ([LocalCast] is non-null exactly then — see [dev.jellystructure.ravilo.ui.RaviloApp]'s `castActive`).
 *
 * One glyph, one sheet, on every platform: the Cast mark in its two forms (idle · connected, with a pulse
 * while connecting), opening Ravilo's own "Play on a TV" sheet — never the platform's dialog. On Android
 * the SDK's Chromecasts are rows inside that sheet ([rememberCastRoutes]); a TV running R264's receiver
 * and (on Safari) AirPlay are rows beside them. The owner accepted that the mark is a little misleading
 * for a TV that is not a Chromecast; the sheet's first line, *Play on a TV*, is what makes it plain.
 */
@Composable
fun CastButton(modifier: Modifier = Modifier, playContext: ScreenPlayContext? = null) {
    // R286 (FR-R286-1) — never on a TV. Casting sends playback *to* a screen and the TV is the screen
    // (236/R264 make it a receiver), so the glyph was backwards there; it was also unreachable, because
    // the TV app bar's D-pad chain hops nav -> search -> avatar and never through it. The gate lives
    // here rather than at the three call sites so a fourth inherits it — which is how it reached the
    // player's chrome to begin with. `isTvPlatform`, not a width: R256 is why (a 540dp TV is a TV).
    if (isTvPlatform) return
    val cast = LocalCast.current ?: return
    val link by cast.sender.link.collectAsState()
    Box(
        modifier.size(40.dp).clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { cast.openSheet(playContext) },
        contentAlignment = Alignment.Center,
    ) { CastMarkGlyph(tint = RaviloTheme.colors.text, link = link) }
}

/** R265 — the root-level host for [CastController.sheet]; RaviloApp draws it once, above every screen. */
@Composable
fun CastSheetHost(cast: CastController) {
    val request by cast.sheet.collectAsState()
    ScreensSheet(cast = cast, open = request != null, onClose = cast::closeSheet, playContext = request?.playContext)
}

/**
 * FR-R245-1's two forms of the Cast mark, drawn rather than borrowed from the SDK's `MediaRouteButton`
 * (which opens the SDK's dialog and exists only on Android): a frame open at its lower-left corner with
 * three waves; **connected** fills the frame. While connecting or reconnecting the waves pulse.
 */
@Composable
internal fun CastMarkGlyph(tint: Color, link: CastLinkState, sizeDp: Int = 24) {
    val pulsing = link == CastLinkState.CONNECTING || link == CastLinkState.RECONNECTING
    val pulse = if (pulsing) {
        val t = rememberInfiniteTransition(label = "castPulse")
        t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "castPulseAlpha").value
    } else 1f
    val connected = link == CastLinkState.CONNECTED
    Canvas(Modifier.size(sizeDp.dp)) {
        val w = size.width; val h = size.height
        val stroke = w * 0.085f
        val l = w * 0.08f; val t = h * 0.17f; val r = w * 0.92f; val b = h * 0.83f
        val frame = Path().apply {
            moveTo(l, t + h * 0.20f); lineTo(l, t); lineTo(r, t); lineTo(r, b); lineTo(l + w * 0.40f, b)
        }
        drawPath(frame, tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (connected) {
            val hole = Path().apply { addOval(Rect(center = Offset(l, b), radius = w * 0.50f)) }
            clipPath(hole, clipOp = ClipOp.Difference) {
                drawRect(tint, topLeft = Offset(l + w * 0.13f, t + h * 0.13f), size = Size(r - l - w * 0.26f, b - t - h * 0.26f))
            }
        }
        val waves = tint.copy(alpha = tint.alpha * pulse)
        drawArc(waves, startAngle = -90f, sweepAngle = 90f, useCenter = true,
            topLeft = Offset(l - w * 0.13f, b - w * 0.13f), size = Size(w * 0.26f, w * 0.26f))
        for (radius in listOf(w * 0.27f, w * 0.41f)) {
            drawArc(waves, startAngle = -90f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(l - radius, b - radius), size = Size(radius * 2, radius * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
    }
}

/** A plain TV-outline mark — screens have no brand glyph of their own the way Chromecast does. */
@Composable
internal fun ScreenCastGlyph(tint: Color, on: Boolean, sizeDp: Int = 22) {
    Canvas(Modifier.size(sizeDp.dp)) {
        val w = size.width; val h = size.height
        val bodyH = h * 0.72f
        drawRoundRect(if (on) tint else tint.copy(alpha = 0.85f), topLeft = Offset(w * 0.06f, 0f), size = Size(w * 0.88f, bodyH), cornerRadius = CornerRadius(w * 0.08f), style = Stroke(width = h * 0.09f))
        drawLine(tint, Offset(w * 0.36f, h * 0.94f), Offset(w * 0.64f, h * 0.94f), strokeWidth = h * 0.09f, cap = StrokeCap.Round)
    }
}

/** FR-R245-3 — connecting is a bar, not a screen: "Connecting to {device}…" → "Casting to {device}",
 *  retiring ~2 s after it connects; "Reconnecting to {device}…" on app start (FR-R245-5). */
@Composable
fun CastConnectingBar() {
    val cast = LocalCast.current ?: return
    val colors = RaviloTheme.colors
    val link by cast.sender.link.collectAsState()
    val device by cast.sender.deviceName.collectAsState()
    var shownConnectedUntil by remember { mutableStateOf(false) }
    LaunchedEffect(link) {
        shownConnectedUntil = link == CastLinkState.CONNECTED
        if (link == CastLinkState.CONNECTED) { delay(2_000); shownConnectedUntil = false }
    }
    // Nothing without a name: "Casting to" with a hole where the TV should be (seen on the Pixel 9 during
    // a resume, before the SDK had the device again) is worse than no bar — R270 FR-R270-5's rule.
    // The last name seen, for the bar's exit animation, which outlives the name itself.
    val lastName = remember { mutableStateOf<String?>(null) }
    SideEffect { device?.let { lastName.value = it } }
    val visible = device != null &&
        (link == CastLinkState.CONNECTING || link == CastLinkState.RECONNECTING || (link == CastLinkState.CONNECTED && shownConnectedUntil))
    AnimatedVisibility(visible = visible, enter = slideInVertically { -it } + fadeIn(tween(160)), exit = slideOutVertically { -it } + fadeOut(tween(200))) {
        val name = device ?: lastName.value.orEmpty()
        val text = when (link) {
            CastLinkState.CONNECTING -> str("cast.connecting", mapOf("device" to name))
            CastLinkState.RECONNECTING -> str("cast.reconnecting", mapOf("device" to name))
            else -> str("cast.connected", mapOf("device" to name))
        }
        Box(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 60.dp).padding(horizontal = 12.dp),
        ) {
            Text(
                text, color = Color.White, fontSize = 13.sp, fontFamily = Sora, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().background(colors.accent.copy(alpha = 0.92f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

/**
 * FR-R245-6 — the 64 dp mini bar above the bottom safe area on every screen while a cast runs:
 * thumbnail · title · ▶/❚❚ · device name · hairline progress. Tap opens the remote. There is NO swipe
 * to dismiss: while a cast runs this is the only route back to the remote.
 */
@Composable
fun CastMiniBar(onOpen: () -> Unit) {
    val cast = LocalCast.current ?: return
    val colors = RaviloTheme.colors
    val link by cast.sender.link.collectAsState()
    val status by cast.sender.status.collectAsState()
    val device by cast.sender.deviceName.collectAsState()
    val st = status
    val visible = castMiniBarVisible(link, st)
    AnimatedVisibility(visible = visible, enter = slideInVertically { it } + fadeIn(tween(180)), exit = slideOutVertically { it } + fadeOut(tween(160))) {
        val s = st ?: return@AnimatedVisibility
        Column(
            // R274 (FR-R274-4) — the seam with the IME excluded, not plain safeDrawing: the mini bar
            // docks above the nav bar (FR-R267-8) and the pair has to move as one block, so when the
            // keyboard covers the bar it covers this too. safeDrawing also tracks live bar VISIBILITY,
            // which is what R261 FR-R261-5 replaced everywhere else.
            Modifier.fillMaxWidth().safeAreaPadding(includeIme = false).padding(horizontal = 12.dp, vertical = RaviloDimens.castMiniBarMargin)
                .clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E1119).copy(alpha = 0.97f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen),
        ) {
            Row(Modifier.fillMaxWidth().height(RaviloDimens.castMiniBarRowHeight).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(64.dp).height(36.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1A1D28))) {
                    val art = s.artUrl
                    if (art != null) RemoteImage(url = art, contentDescription = null, modifier = Modifier.size(64.dp, 36.dp), contentScale = ContentScale.Crop, requestedWidth = 320)
                    else Text(s.title.orEmpty().take(2).uppercase(), color = Color.White.copy(0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title.orEmpty(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(str(if (s.playing) "cast.playing_on" else "cast.paused_on", mapOf("device" to (device ?: ""))), color = Color.White.copy(0.55f), fontSize = 13.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.10f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { if (s.playing) cast.sender.pause() else cast.sender.play() }),
                    contentAlignment = Alignment.Center,
                ) { PlayPauseGlyph(playing = s.playing, tint = Color.White, sizeDp = 18) }
            }
            // hairline progress
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(0.12f))) {
                val frac = if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth(frac).height(2.dp).background(colors.accent))
            }
        }
    }
}

@Composable
internal fun PlayPauseGlyph(playing: Boolean, tint: Color, sizeDp: Int) {
    Canvas(Modifier.size(sizeDp.dp)) {
        if (playing) {
            val bw = size.width * 0.28f; val gap = size.width * 0.16f
            drawRect(tint, Offset(size.width / 2 - gap / 2 - bw, 0f), Size(bw, size.height))
            drawRect(tint, Offset(size.width / 2 + gap / 2, 0f), Size(bw, size.height))
        } else {
            val p = Path().apply { moveTo(size.width * 0.18f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.18f, size.height); close() }
            drawPath(p, tint)
        }
    }
}

/** The landscape art a cast carries (notification + remote card): a still or a backdrop, never a poster. */
fun castArtFor(backdropUrl: String?, stillUrl: String?): String? = stillUrl ?: backdropUrl

/** Builds the receiver's episode list from the player's own entries (ids + the markers it needs). */
fun castEpisodes(entries: List<dev.jellystructure.ravilo.ui.screens.PlayerEpisodeEntry>?): List<CastEpisode> =
    entries?.map { e ->
        CastEpisode(
            id = e.id, title = e.title, kicker = e.kicker, stillUrl = e.stillUrls.firstOrNull { it != null },
            introStartMs = e.segments.introStartMs, introEndMs = e.segments.introEndMs, creditsStartMs = e.segments.creditsStartMs,
        )
    } ?: emptyList()

/** The connected Chromecast's name, or null when nothing is connected — for "Play on {device}" (FR-R245-4). */
@Composable
fun castConnectedDeviceName(): String? {
    val cast = LocalCast.current ?: return null
    val link by cast.sender.link.collectAsState()
    val device by cast.sender.deviceName.collectAsState()
    return if (link == CastLinkState.CONNECTED) device else null
}
