package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.jellystructure.ravilo.ui.seams.rememberCastRoutes
import dev.jellystructure.ravilo.ui.seams.CastRoute
import dev.jellystructure.ravilo.ui.seams.CastLinkState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.hasCastSdk
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.screens.HandsetSheet
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.DeviceKind
import dev.jellystructure.shared.tv.RemoteDevice
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** R265 (FR-R265-6) — what to start on the screen the sheet's user picks; null when the sheet is opened
 *  with nothing queued (join-only — the app-bar glyph's case, FR-R265-7's "reconnect is a list" path). */
data class ScreenPlayContext(val itemId: String, val startPositionMs: Long? = null)

/**
 * R265 — the three-tier "Play on a TV" sheet (FR-R265-1..5), reusing [HandsetSheet]'s chrome. Tier 1 is
 * absent when empty, never an empty box or a "searching…" state (FR-R265-2); tier 2 is collapsed until the
 * viewer opens it, and this phone remembers that choice (FR-R265-3); the TV used last is pinned to the top
 * of its tier (open question 3). On Android the SDK's Chromecasts are tier-2 rows with the Cast mark, so
 * the sheet is the only picker on every platform. Tier 3 is R270's footnote-link AirPlay row, shown only
 * where [airplayAvailable].
 *
 * [playContext] is what to start on a screen the moment it is tapped; null opens it join-only (FR-R265-7).
 * Inside the player a tap is the hand-off instead (FR-R245-4, `LocalCastHandoff`): the new connection
 * carries the live position over, for a screen exactly as for a Chromecast.
 */
@Composable
fun ScreensSheet(
    cast: CastController,
    open: Boolean,
    onClose: () -> Unit,
    playContext: ScreenPlayContext?,
    airplayAvailable: Boolean = false,
    onAirplay: () -> Unit = {},
    /** R324 (FR-R324-1) — the music-mode sheet: *Play on…*, the audio routes first; video mode lists no speaker. */
    music: Boolean = false,
) {
    var devices by remember { mutableStateOf<List<RemoteDevice>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var tier2Open by remember { mutableStateOf(ScreensSheetPrefs.tier2Open()) }
    var addTvOpen by remember { mutableStateOf(false) }
    // R324 (FR-R324-2) — a busy speaker asks first: the route waiting for *Play on {device}*.
    var takeOver by remember { mutableStateOf<CastRoute?>(null) }
    val link by cast.sender.link.collectAsState()
    val deviceName by cast.sender.deviceName.collectAsState()
    // Scan for Chromecasts while the app is on screen (and never while it is not — R293's rule): the SDK
    // cannot finish resuming a session (FR-R245-5) until the route is found again, and its resume starts
    // before this sheet or the sender exist, so "scan only while resuming" had nothing to key on (tried on
    // the Pixel 9: the resume waited forever). A Cast app scanning while it is open is the platform norm.
    val routes = rememberCastRoutes(cast.appId, discovering = dev.jellystructure.ravilo.ui.seams.rememberAppOnScreen())
    val status by cast.sender.status.collectAsState()
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        addTvOpen = false
        takeOver = null
        loaded = false
        devices = if (cast.screensEnabled) cast.screenDevices() else emptyList()
        loaded = true
    }
    fun tapDevice(d: RemoteDevice) {
        onClose()
        ScreensSheetPrefs.setLastDevice(d.deviceId)
        val ctx = playContext
        if (ctx != null) cast.castOnScreen(d, ctx.itemId, ctx.startPositionMs) else cast.joinScreen(d)
    }
    // The Chromecast this phone is casting to. Its own route is not the one MediaRouter marks selected —
    // a Cast session selects a group route (`…-groupRoute`) — so the row is matched by the SDK's device
    // name, which is the route's name (seen on the Pixel 9: the connected TV read "Ready").
    val castingTo = deviceName.takeIf { link == CastLinkState.CONNECTED && cast.sender.screen.link.value == CastLinkState.NONE }
    fun connectedTo(r: CastRoute) = r.selected || (castingTo != null && r.name == castingTo)
    fun startRoute(r: CastRoute) {
        onClose()
        ScreensSheetPrefs.setLastDevice(r.id)
        if (!connectedTo(r)) cast.castOnChromecast(r, music = music)
    }
    fun tapRoute(r: CastRoute) {
        // R324 (FR-R324-2, owner) — a device another app holds is tappable, and asks first. Selecting the route
        // launches Ravilo on it — that IS the stop; the confirmation is the only gate (dev review 3).
        if (music && r.busyWith != null && !connectedTo(r) && !r.busyWith.equals("Ravilo", ignoreCase = true)) { takeOver = r; return }
        startRoute(r)
    }
    HandsetSheet(visible = open, onDismiss = onClose) {
        val pending = takeOver
        if (addTvOpen) {
            AddTvSheetBody(cast = cast, onBack = { addTvOpen = false }, onPaired = { addTvOpen = false })
        } else if (pending != null) {
            TakeOverSheetBody(route = pending, onBack = { takeOver = null }, onConfirm = { takeOver = null; startRoute(pending) })
        } else {
            ScreensSheetBody(
                devices = devices, routes = routes, loaded = loaded, lastDevice = ScreensSheetPrefs.lastDevice(), myUserId = cast.userId,
                playingTitle = status?.takeIf { it.loaded }?.title, music = music,
                tier2Open = tier2Open,
                onToggleTier2 = { tier2Open = !tier2Open; ScreensSheetPrefs.setTier2Open(tier2Open) },
                onTapDevice = ::tapDevice, onTapRoute = ::tapRoute, isConnected = ::connectedTo,
                onAddTv = if (cast.screensEnabled) ({ addTvOpen = true }) else null,
                airplayAvailable = airplayAvailable, onAirplay = { onClose(); onAirplay() },
                // Music on a speaker: its queue comes back, paused where it stopped (FR-R324-5) — the same as the ⋯ menu's row.
                onStop = if (link != CastLinkState.NONE) ({ onClose(); if (dev.jellystructure.ravilo.ui.music.MusicCast.linked.value) dev.jellystructure.ravilo.ui.music.MusicCast.stop() else cast.stopCasting() }) else null,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun ScreensSheetBody(
    devices: List<RemoteDevice>, routes: List<CastRoute>, loaded: Boolean, lastDevice: String?, myUserId: String?,
    playingTitle: String?, music: Boolean, tier2Open: Boolean, onToggleTier2: () -> Unit,
    onTapDevice: (RemoteDevice) -> Unit, onTapRoute: (CastRoute) -> Unit, isConnected: (CastRoute) -> Boolean, onAddTv: (() -> Unit)?,
    airplayAvailable: Boolean, onAirplay: () -> Unit, onStop: (() -> Unit)?, onClose: () -> Unit,
) {
    val colors = RaviloTheme.colors
    // Open question 3 — the TV used last leads its own tier; the server's order holds for the rest.
    // R327 (FR-R327-1) — screens only. A Chromecast's receiver record (`kind = cast`) is never online or
    // nearby (the receiver has no events socket) and cannot be driven through /api/remote; the Cast SDK's
    // route below is the one row a Chromecast gets. Listing both showed every Chromecast twice.
    val screens = devices.filter { it.kind == DeviceKind.SCREEN }
        .sortedByDescending { it.deviceId == lastDevice }
    val near = screens.filter { it.nearby }
    val rest = screens.filter { !it.nearby }
    // R324 (FR-R324-1) — in video mode no audio-only route is listed, and nothing says so (Google's rule; R265's
    // absent-not-empty). In music mode the audio routes lead: speakers, groups, then the displays and the TVs.
    val visibleRoutes = if (music) routes else routes.filter { it.kind == "display" }
    val audioRows = if (music) visibleRoutes.filter { it.kind != "display" }.sortedWith(compareByDescending<CastRoute> { it.id == lastDevice }.thenBy { it.kind != "speaker" }) else emptyList()
    val castRows = visibleRoutes.filter { it.kind == "display" }.sortedByDescending { it.id == lastDevice }
    val tier2Count = rest.size + castRows.size
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        SheetHeader(str(if (music) "cast.sheet_music" else "screens.title"), onClose)
        if (!loaded) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.textDim, strokeWidth = 2.5.dp, modifier = Modifier.height(28.dp).width(28.dp))
            }
        } else {
            // FR-R265-2 — absent when empty, never an empty box.
            if (near.isNotEmpty() || audioRows.isNotEmpty()) {
                SectionLabel(str("screens.nearby"))
                audioRows.forEach { ChromecastRow(it, isConnected(it), playingTitle, onClick = { onTapRoute(it) }) }
                near.forEach { DeviceRow(it, myUserId, onClick = { onTapDevice(it) }) }
            }
            // FR-R265-3 — every paired TV the server does not call nearby, and (Android) every Chromecast
            // the SDK can see, behind one collapsible. The design draws the Chromecast here too.
            if (tier2Count > 0) {
                CollapsibleRow(str("screens.all"), tier2Count, tier2Open, onToggleTier2)
                if (tier2Open) {
                    rest.forEach { DeviceRow(it, myUserId, onClick = { onTapDevice(it) }) }
                    castRows.forEach { ChromecastRow(it, isConnected(it), playingTitle, onClick = { onTapRoute(it) }) }
                }
            }
            if (near.isEmpty() && audioRows.isEmpty() && tier2Count == 0 && !airplayAvailable && onAddTv != null) {
                Text(str("screens.add"), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
            // R324 (FR-R324-10) — no Cast sender here (an iPhone, the web): the speakers are said to be missing, once.
            if (music && !hasCastSdk) {
                Text(str("cast.speakers_ios"), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
            // R330 (FR-R330-8) — the Mac was refused Local Network access: said once, under the rows, with the way to it.
            val lanDenied by dev.jellystructure.ravilo.ui.seams.CastPlatform.localNetworkDenied.collectAsState()
            if (lanDenied) {
                Text(str("mac.local_network"), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                dev.jellystructure.ravilo.ui.seams.CastPlatform.openLocalNetworkSettings?.let { open ->
                    SimpleRow(icon = {}, label = str("mac.open_settings"), onClick = open)
                }
            }
        }
        // Tier 3 — R270's footnote form: one quiet line, the caveat inside the label itself, one step
        // below the TV rows, never a full row with its own second line (supersedes FR-R265-4's original shape).
        if (airplayAvailable) {
            SimpleRow(icon = { AirplayGlyph(colors.textSecondary) }, label = str("screens.airplay_footnote"), onClick = onAirplay, labelColor = colors.textSecondary)
        }
        if (onAddTv != null) SimpleRow(icon = { PlusGlyph(colors.text) }, label = str("screens.add"), onClick = onAddTv)
        // FR-R245-10 — ending a session is only ever explicit, and this is where the sheet says so.
        // The design's own warning ink for this row (`#ff9b8a`); the palette has no token for it.
        if (onStop != null) SimpleRow(icon = {}, label = str("cast.stop"), onClick = onStop, labelColor = Color(0xFFFF9B8A))
    }
}

/** R265 (FR-R265-3) — a Chromecast the SDK found: the Cast mark, its name, and Ready or what it plays. */
@Composable
private fun ChromecastRow(route: CastRoute, connected: Boolean, playingTitle: String?, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    val ourselves = route.busyWith.equals("Ravilo", ignoreCase = true)
    // R324 (FR-R324-2) — Ready · Playing {title} (this phone's session) · Playing Ravilo (another phone's — tapping
    // joins) · Busy · {app} (tappable; asks first). The kind leads the state on an audio route.
    val kindWord = when (route.kind) { "speaker" -> str("cast.speaker"); "group" -> str("cast.group"); else -> null }
    val state = when {
        connected && playingTitle != null -> str("screens.playing", mapOf("title" to playingTitle))
        !connected && ourselves -> str("screens.playing", mapOf("title" to "Ravilo"))
        !connected && route.busyWith != null -> str("cast.busy_with", mapOf("app" to route.busyWith))
        else -> str("screens.ready")
    }
    val line = listOfNotNull(kindWord, state).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.height(36.dp).width(36.dp).background(colors.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            when (route.kind) {
                "speaker" -> SpeakerGlyph(colors.text, group = false)
                "group" -> SpeakerGlyph(colors.text, group = true)
                else -> CastMarkGlyph(tint = colors.text, link = if (connected) CastLinkState.CONNECTED else CastLinkState.NONE, sizeDp = 20)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(route.name, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = colors.textDim, fontSize = 18.sp)
    }
}

/** R324 (FR-R324-2) — *Stop {app} and play here?* · what it does to whoever started it · Play on {device} · Cancel. */
@Composable
private fun TakeOverSheetBody(route: CastRoute, onBack: () -> Unit, onConfirm: () -> Unit) {
    val colors = RaviloTheme.colors
    val app = route.busyWith.orEmpty()
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        SheetHeader(str("cast.take_over", mapOf("app" to app)), onBack)
        Text(str("cast.take_over_sub", mapOf("device" to route.name, "app" to app)), color = colors.textSecondary, fontSize = 14.sp, fontFamily = Sora, modifier = Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(8.dp))
        SimpleRow(icon = { SpeakerGlyph(colors.text, group = route.kind == "group") }, label = str("cast.play_on", mapOf("device" to route.name)), onClick = onConfirm)
        SimpleRow(icon = {}, label = str("action.cancel"), onClick = onBack, labelColor = colors.textSecondary)
        Spacer(Modifier.height(6.dp))
    }
}

/** A speaker — a box with a cone — and, for a group, a second smaller one behind it. Drawn, like the Cast mark. */
@Composable
internal fun SpeakerGlyph(tint: Color, group: Boolean, sizeDp: Int = 20) {
    androidx.compose.foundation.Canvas(Modifier.height(sizeDp.dp).width(sizeDp.dp)) {
        val w = size.width; val h = size.height
        val stroke = w * 0.09f
        fun one(x: Float, y: Float, bw: Float, bh: Float) {
            drawRoundRect(tint, topLeft = androidx.compose.ui.geometry.Offset(x, y), size = androidx.compose.ui.geometry.Size(bw, bh), cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw * 0.18f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawCircle(tint, radius = bw * 0.22f, center = androidx.compose.ui.geometry.Offset(x + bw / 2, y + bh * 0.64f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawCircle(tint, radius = bw * 0.07f, center = androidx.compose.ui.geometry.Offset(x + bw / 2, y + bh * 0.28f))
        }
        if (group) { one(w * 0.42f, h * 0.02f, w * 0.44f, h * 0.7f); one(w * 0.08f, h * 0.22f, w * 0.5f, h * 0.76f) }
        else one(w * 0.22f, h * 0.04f, w * 0.56f, h * 0.92f)
    }
}

@Composable
private fun AddTvSheetBody(cast: CastController, onBack: () -> Unit, onPaired: () -> Unit) {
    val colors = RaviloTheme.colors
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    LaunchedEffect(code) {
        if (code.length < 6 || checking) return@LaunchedEffect
        checking = true
        error = false
        val result = cast.pairScreen(code)
        checking = false
        if (result != null) onPaired() else { error = true; code = "" }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        SheetHeader(str("screens.add"), onBack)
        TextField(
            value = code,
            onValueChange = { v -> code = v.uppercase().filter { it.isLetterOrDigit() }.take(6) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            placeholder = { Text("· · · · · ·", color = colors.textDim) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = colors.surfaceVariant, unfocusedContainerColor = colors.surfaceVariant,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = colors.text, unfocusedTextColor = colors.text,
            ),
        )
        val hint = if (error) str("screens.code_failed") else str("screens.code_hint")
        Text(hint, color = if (error) colors.accentSecondary else colors.textDim, fontSize = 13.sp, fontFamily = Sora, modifier = Modifier.padding(horizontal = 14.dp))
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SheetHeader(title: String, onClose: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, modifier = Modifier.weight(1f))
        Box(
            Modifier.height(36.dp).width(36.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { CloseGlyph(colors.textSecondary, 16.dp, description = str("action.close")) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = RaviloTheme.colors.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun CollapsibleRow(label: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 46.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
        Spacer(Modifier.width(6.dp))
        Text("($count)", color = colors.textDim, fontSize = 14.sp, fontFamily = Sora)
        Spacer(Modifier.weight(1f))
        ChevronGlyph(if (expanded) GlyphDirection.UP else GlyphDirection.DOWN, colors.textDim, 14.dp)
    }
}

@Composable
private fun DeviceRow(device: RemoteDevice, myUserId: String?, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    val np = device.nowPlaying
    // FR-R270-3 — busy is SOMEONE ELSE's session. R265's first build marked any loaded session busy,
    // so a screen playing this viewer's own title read "Busy · {me} is watching" — and every busy row was
    // tappable, although 236's 409 would refuse the play. Mine reads "Playing {title}" and opens the
    // remote; someone else's is dimmed and not tappable, as the spec and the design draw it.
    val busy = np != null && np.loaded && np.sessionUserId != null && np.sessionUserId != myUserId
    val offline = !device.online
    val tappable = !offline && !busy
    val state = when {
        !device.online -> str("screens.offline", mapOf("when" to lastSeenLabel(device.lastSeen)))
        // R270 (FR-R270-3) — the person ACTUALLY watching, resolved server-side. This used to read
        // `device.pairedUsers.firstOrNull()`, which is the first user *paired to the TV*: on a set two
        // people had paired with it named the wrong household member about half the time, and for
        // `kind = "tv"` (where pairedUsers is never populated) it rendered "Busy · is watching" with a
        // hole in it. A row that is confidently wrong is worse than one that is vague, so an
        // unresolvable viewer falls back to "In use" rather than to an empty name (FR-R270-5: one
        // disclosure decision, made once on the server, for busy and offline together).
        busy -> device.nowPlayingUser
            ?.let { str("screens.busy", mapOf("user" to it)) }
            ?: str("screens.in_use")
        np?.loaded == true -> str("screens.playing", mapOf("title" to (np.title ?: "")))
        else -> str("screens.ready")
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp)
            .clickable(enabled = tappable, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.height(36.dp).width(36.dp).background(colors.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            TvGlyph(if (tappable) colors.text else colors.textDim)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, color = if (tappable) colors.text else colors.textDim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state, color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (tappable) Text("›", color = colors.textDim, fontSize = 18.sp)
    }
}

@Composable
private fun SimpleRow(icon: @Composable () -> Unit, label: String, onClick: () -> Unit, labelColor: Color? = null) {
    val colors = RaviloTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.height(28.dp).width(28.dp), contentAlignment = Alignment.Center) { icon() }
        Text(label, color = labelColor ?: colors.text, fontSize = 15.sp, fontFamily = Sora, textAlign = TextAlign.Start)
    }
}

@Composable private fun TvGlyph(tint: Color) { ScreenCastGlyph(tint = tint, on = false, sizeDp = 20) }
@Composable private fun PlusGlyph(tint: Color) { Text("+", color = tint, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
@Composable private fun AirplayGlyph(tint: Color) { TriangleGlyph(GlyphDirection.UP, tint, 16.dp) }   // R315

/**
 * R270 (FR-R270-4) — *"Offline · last seen {when}"*: a **weekday** inside the last seven days, a
 * **date** beyond that, and **never a duration and never "just now"**.
 *
 * The shipped helper broke all three rules — it answered "just now", "17 min ago", "3 h ago", "2 d
 * ago" — so an offline row could read *"Offline · last seen just now"*, which is a contradiction on
 * one line. Dev review item 6 asked for this to be verified rather than assumed; it was, and it was
 * wrong.
 *
 * A weekday is the right grain because it is how a household actually talks about a TV nobody has
 * turned on ("it's been off since Tuesday"), and it does not imply a precision the `lastSeen` stamp
 * does not have.
 */
@Composable
internal fun lastSeenLabel(lastSeenMs: Long, nowMs: Long = Clock.System.now().toEpochMilliseconds()): String {
    val key = lastSeenKey(lastSeenMs, nowMs)
    return if (key.startsWith("wd.")) str(key) else key
}

/**
 * The decidable half, split out so it is testable without a composition: returns either a `wd.*`
 * string key (a weekday, inside the last seven days) or a literal date (beyond that).
 */
internal fun lastSeenKey(lastSeenMs: Long, nowMs: Long): String {
    val tz = TimeZone.currentSystemDefault()
    val seen = Instant.fromEpochMilliseconds(lastSeenMs).toLocalDateTime(tz).date
    val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz).date
    // Whole calendar days, not elapsed hours: a TV last seen at 23:50 yesterday is "yesterday's
    // weekday", not "8 hours".
    val days = today.toEpochDays() - seen.toEpochDays()
    return if (days in 0..6) weekdayKey(seen.dayOfWeek) else "${seen.day}/${seen.monthNumber}/${seen.year}"
}

private fun weekdayKey(d: DayOfWeek): String = when (d) {
    DayOfWeek.MONDAY -> "wd.mon"
    DayOfWeek.TUESDAY -> "wd.tue"
    DayOfWeek.WEDNESDAY -> "wd.wed"
    DayOfWeek.THURSDAY -> "wd.thu"
    DayOfWeek.FRIDAY -> "wd.fri"
    DayOfWeek.SATURDAY -> "wd.sat"
    else -> "wd.sun"
}
