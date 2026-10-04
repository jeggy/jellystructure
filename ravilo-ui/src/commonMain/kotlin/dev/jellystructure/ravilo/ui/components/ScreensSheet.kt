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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.jellystructure.ravilo.ui.seams.CastRoute
import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.seams.castRouteInSession
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    /** R368 (FR-R368-7) — a *Playing everywhere* row tapped (R369: its remote). */
    onOpenSession: (dev.jellystructure.shared.tv.SessionView) -> Unit = {},
    /** R368 (FR-R368-9) — ⏯ on a row the server says this device controls. */
    onSessionPlayPause: ((dev.jellystructure.shared.tv.SessionView) -> Unit)? = null,
) {
    val sessionsState = rememberSessionsState()
    var devices by remember { mutableStateOf<List<RemoteDevice>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var tier2Open by remember { mutableStateOf(ScreensSheetPrefs.tier2Open()) }
    // R324 (FR-R324-2) — a busy speaker asks first: the route waiting for *Play on {device}*.
    var takeOver by remember { mutableStateOf<CastRoute?>(null) }
    val link by cast.sender.link.collectAsState()
    val deviceName by cast.sender.deviceName.collectAsState()
    // Chromecasts are scanned for while the app is on screen (R293's rule) by [CastSheetHost], one level up, and read
    // here from the controller (R360 dev review item 5): the glyph's presence rule reads the same list.
    val routes by cast.routes.collectAsState()
    val status by cast.sender.status.collectAsState()
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        takeOver = null
        loaded = false
        devices = if (cast.screensEnabled) cast.screenDevices() else emptyList()
        // R360 (dev review item 4) — the sheet's fetch is newer than the config's `paired`: it overrides it until the
        // next config refresh, so a TV revoked in between takes the glyph with it (after the grace).
        if (cast.screensEnabled) cast.screensPaired = devices.any { it.kind == DeviceKind.SCREEN }
        loaded = true
    }
    // R360 (dev review item 4) — the sheet can no longer open empty (*Add a TV* and its hint are gone): with no row at
    // all and nothing to stop, it closes itself rather than showing a bare title.
    val hasRows = devices.any { it.kind == DeviceKind.SCREEN } || visibleCastRoutes(routes, music).isNotEmpty() || airplayAvailable ||
        sessionsState.sessions.isNotEmpty() ||   // R368 — a *Playing everywhere* row is part of the list
        dev.jellystructure.ravilo.ui.sessions.PlayOnStore.targets.value.orEmpty().any { !it.here && it.reachable }   // R370 — a server place
    LaunchedEffect(open, loaded, hasRows, link) {
        if (open && loaded && !hasRows && link == CastLinkState.NONE) onClose()
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
    // R355 (FR-R355-3) — and every speaker the session was grouped onto (Android's output panel ⊕): they play this
    // session too. They used to read Busy (the follower's "Casting: …" line) and a tap moved the music off the group.
    val members by cast.sender.members.collectAsState()
    fun connectedTo(r: CastRoute) = r.selected || (castingTo != null && castRouteInSession(r.name, castingTo, members))
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
    // R370 (FR-R370-1, review item 11) — the server's places while the sheet is open; null = today's list stands alone.
    val serverTargets by dev.jellystructure.ravilo.ui.sessions.PlayOnStore.targets.collectAsState()
    LaunchedEffect(open) { if (open) dev.jellystructure.ravilo.ui.sessions.PlayOnStore.opened() else dev.jellystructure.ravilo.ui.sessions.PlayOnStore.closed() }
    var asking by remember { mutableStateOf<dev.jellystructure.ravilo.ui.sessions.PlayOnRow?>(null) }
    LaunchedEffect(open) { if (!open) asking = null }
    val musicNow by dev.jellystructure.ravilo.ui.music.MusicPlayback.state.collectAsState()
    /** What to start on a place: this page's title, or the music queue that plays here; null = nothing to start. */
    fun startRequestFor(row: dev.jellystructure.ravilo.ui.sessions.PlayOnRow, replace: dev.jellystructure.shared.tv.SessionView?): dev.jellystructure.shared.tv.SessionStartRequest? {
        val r = replace?.let { dev.jellystructure.shared.tv.SessionReplace(it.id, it.revision) }
        if (music) {
            val st = musicNow
            if (st.queue.isEmpty() || st.book != null) return null
            return dev.jellystructure.shared.tv.SessionStartRequest(targetId = row.id, kind = "music", items = st.queue.map { it.id }, index = st.index.coerceAtLeast(0),
                startMs = dev.jellystructure.ravilo.ui.music.MusicPlayback.currentPositionMs(), shuffle = st.shuffle, repeat = st.repeat.name.lowercase(), replace = r)
        }
        val ctx = playContext ?: return null
        return dev.jellystructure.shared.tv.SessionStartRequest(targetId = row.id, kind = "film", items = listOf(ctx.itemId), startMs = ctx.startPositionMs, replace = r)
    }
    fun startOn(row: dev.jellystructure.ravilo.ui.sessions.PlayOnRow, replace: dev.jellystructure.shared.tv.SessionView?) {
        asking = null
        // A Cast device this app's own discovery sees: today's path (the SDK starts it; the receiver's first report
        // makes the session). Anything else goes through the server (a Ravilo app, or the relay — owner decision 1).
        row.route?.let { startRoute(it); return }
        val req = startRequestFor(row, replace) ?: return
        onClose()
        dev.jellystructure.ravilo.ui.sessions.PlayOnStore.start(req, onStarted = { r -> r.session?.let { onOpenSession(it) } },
            onRefused = { reason -> println("R370: start on ${row.name} refused: $reason") })
    }
    fun tapRow(row: dev.jellystructure.ravilo.ui.sessions.PlayOnRow) {
        if (row.here) {
            onClose()
            if (music && dev.jellystructure.ravilo.ui.music.MusicCast.linked.value) dev.jellystructure.ravilo.ui.music.MusicCast.playHere()
            return
        }
        val holds = row.route?.let { connectedTo(it) } == true
        when (dev.jellystructure.ravilo.ui.sessions.busyChoice(row, holds)) {
            dev.jellystructure.ravilo.ui.sessions.BusyChoice.Start -> startOn(row, null)
            dev.jellystructure.ravilo.ui.sessions.BusyChoice.AskReplace, dev.jellystructure.ravilo.ui.sessions.BusyChoice.AskReplacePerson -> asking = row
            else -> Unit
        }
    }
    val tierRows = serverTargets?.let { st -> dev.jellystructure.ravilo.ui.sessions.mergeTargets(st, visibleCastRoutes(routes, music)) }
    HandsetSheet(visible = open, onDismiss = onClose, popover = true) {   // R368 — a popover on a computer
        val pending = takeOver
        if (pending != null) {
            TakeOverSheetBody(route = pending, onBack = { takeOver = null }, onConfirm = { takeOver = null; startRoute(pending) })
        } else {
            ScreensSheetBody(
                // R368 (FR-R368-7) — *Playing everywhere* on top, under the title; absent when nothing plays anywhere.
                top = {
                    PlayingEverywhereSection(sessionsState, onOpen = { onClose(); onOpenSession(it) }, onPlayPause = onSessionPlayPause)
                    // R370 (FR-R370-2) — the four tiers, when the server can say (otherwise today's list below).
                    if (tierRows != null) PlayOnTiersSection(
                        tiers = dev.jellystructure.ravilo.ui.sessions.playOnTiers(tierRows, if (music) "music" else "film"),
                        asking = asking, title = musicNow.current?.title ?: status?.title.orEmpty(),
                        onTap = ::tapRow, onReplace = { row -> startOn(row, row.busy) }, onCancel = { asking = null },
                    )
                },
                devices = devices, routes = if (tierRows != null) emptyList() else routes, loaded = loaded, lastDevice = ScreensSheetPrefs.lastDevice(), myUserId = cast.userId,
                playingTitle = status?.takeIf { it.loaded }?.title, music = music,
                tier2Open = tier2Open,
                onToggleTier2 = { tier2Open = !tier2Open; ScreensSheetPrefs.setTier2Open(tier2Open) },
                onTapDevice = ::tapDevice, onTapRoute = ::tapRoute, isConnected = ::connectedTo,
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
    top: @Composable () -> Unit = {},
    devices: List<RemoteDevice>, routes: List<CastRoute>, loaded: Boolean, lastDevice: String?, myUserId: String?,
    playingTitle: String?, music: Boolean, tier2Open: Boolean, onToggleTier2: () -> Unit,
    onTapDevice: (RemoteDevice) -> Unit, onTapRoute: (CastRoute) -> Unit, isConnected: (CastRoute) -> Boolean,
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
    val visibleRoutes = visibleCastRoutes(routes, music)   // R360 — the one mode filter, shared with the glyph's rule
    val audioRows = if (music) visibleRoutes.filter { it.kind != "display" }.sortedWith(compareByDescending<CastRoute> { it.id == lastDevice }.thenBy { it.kind != "speaker" }) else emptyList()
    val castRows = visibleRoutes.filter { it.kind == "display" }.sortedByDescending { it.id == lastDevice }
    val tier2Count = rest.size + castRows.size
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        SheetHeader(str(if (music) "cast.sheet_music" else "screens.title"), onClose)
        top()
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
        // FR-R245-10 — ending a session is only ever explicit, and this is where the sheet says so.
        // The design's own warning ink for this row (`#ff9b8a`); the palette has no token for it.
        if (onStop != null) SimpleRow(icon = {}, label = str("cast.stop"), onClick = onStop, labelColor = Color(0xFFFF9B8A))
    }
}

/**
 * R370 (FR-R370-2/-4) — *Play on…* in four tiers: this device · playing now (what plays there) · free (TVs and displays
 * first, then speakers) · not reachable (dimmed, a tap does nothing). A busy row asks inline, every time: *Play {title}
 * here instead* / *Cancel* — never *Add*; someone else's reads *Stop {person}'s {title} and play here?*.
 */
@Composable
private fun PlayOnTiersSection(
    tiers: dev.jellystructure.ravilo.ui.sessions.PlayOnTiers, asking: dev.jellystructure.ravilo.ui.sessions.PlayOnRow?, title: String,
    onTap: (dev.jellystructure.ravilo.ui.sessions.PlayOnRow) -> Unit, onReplace: (dev.jellystructure.ravilo.ui.sessions.PlayOnRow) -> Unit, onCancel: () -> Unit,
) {
    val colors = RaviloTheme.colors
    @Composable fun row(r: dev.jellystructure.ravilo.ui.sessions.PlayOnRow, line: String?) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 54.dp).alpha(if (r.reachable) 1f else 0.45f)
                .clickable(enabled = r.reachable, interactionSource = remember { MutableInteractionSource() }, indication = null) { onTap(r) }
                .padding(horizontal = 14.dp, vertical = 8.dp).testTag("playon-${r.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.height(36.dp).width(36.dp).background(colors.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                DeskIcon(placeIcon(r.icon), colors.text, 18.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (line != null) Text(line, color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (asking?.id == r.id) {
            val busy = r.busy
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().background(colors.fg.copy(alpha = 0.06f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                if (busy != null && !busy.mine) Text(str("target.replace_person", mapOf("person" to busy.owner.name, "title" to (busy.title ?: r.name))), color = colors.text, fontSize = 13.5.sp, fontFamily = Sora)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.heightIn(min = 40.dp).background(colors.accent, RoundedCornerShape(20.dp)).clickable { onReplace(r) }.padding(horizontal = 14.dp).testTag("playon-replace"),
                        contentAlignment = Alignment.Center) { Text(str("target.replace", mapOf("title" to title)), color = colors.fg, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1) }
                    Box(Modifier.heightIn(min = 40.dp).background(colors.fg.copy(alpha = 0.08f), RoundedCornerShape(20.dp)).clickable { onCancel() }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center) { Text(str("action.cancel"), color = colors.text, fontSize = 13.5.sp, fontFamily = Sora) }
                }
            }
        }
    }
    tiers.thisDevice?.let { row(it.copy(name = str(if (dev.jellystructure.ravilo.ui.isDesktopPlatform) "mode.label_desk" else "mode.label")), null) }
    if (tiers.playingNow.isNotEmpty()) {
        SectionLabel(str("target.playing_now"))
        tiers.playingNow.forEach { r ->
            val b = r.busy
            row(r, b?.let { if (it.mine) (it.title ?: "") else str(if (it.kind == "music" || it.kind == "audiobook") "session.person_listening" else "session.person_watching", mapOf("person" to it.owner.name)) })
        }
    }
    if (tiers.free.isNotEmpty()) {
        SectionLabel(str("target.free"))
        tiers.free.forEach { r -> row(r, null) }
    }
    if (tiers.unreachable.isNotEmpty()) {
        SectionLabel(str("target.unreachable"))
        tiers.unreachable.forEach { r -> row(r, str("target.unreachable")) }
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
