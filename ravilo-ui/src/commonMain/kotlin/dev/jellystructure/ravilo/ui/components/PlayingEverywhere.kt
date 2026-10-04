package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.sessions.PlaybackSessions
import dev.jellystructure.ravilo.ui.sessions.SessionsState
import dev.jellystructure.ravilo.ui.sessions.drawnPositionMs
import dev.jellystructure.ravilo.ui.sessions.orderSessionRows
import dev.jellystructure.ravilo.ui.sessions.sessionRowTitle
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.shared.tv.SessionView
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** A clock for drawing positions: ticks once a second while composed. */
@Composable
internal fun rememberSessionClock(): Long {
    var now by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = Clock.System.now().toEpochMilliseconds() } }
    return now
}

/** FR-R368-7 — the state line: three still bars + *Playing*, *Paused*, *Loading…*, *{person} is listening/watching*,
 *  and *Reconnecting…* over the state after a restart. */
@Composable
internal fun sessionStateText(v: SessionView): String = when {
    v.reconnecting -> str("session.reconnecting")
    !v.mine -> str(if (v.kind == "music" || v.kind == "audiobook") "session.person_listening" else "session.person_watching", mapOf("person" to v.owner.name))
    v.state == "paused" -> str("session.paused")
    v.state == "starting" || v.state == "buffering" -> str("loading")
    else -> ""
}

/**
 * FR-R368-7 — *Playing everywhere*: on top of the *Play on…* sheet (phone) and the toolbar popover (desktop). Absent
 * when nothing plays anywhere. A row: artwork (16:9 for a film or an episode, square otherwise), the title, the place
 * with its icon in the accent colour, the state, and a 3 dp progress line; 70 dp tall, 46 dp targets. This device's
 * own row is highlighted; an ended row fades for the 60 s the server keeps it.
 */
@Composable
fun PlayingEverywhereSection(
    state: SessionsState,
    onOpen: (SessionView) -> Unit = {},
    /** Present only for a row whose [SessionView.controllable] is true — absent, never greyed, otherwise. */
    onPlayPause: ((SessionView) -> Unit)? = null,
) {
    val rows = orderSessionRows(state.sessions)
    if (rows.isEmpty()) return
    val colors = RaviloTheme.colors
    val now = rememberSessionClock()
    Column(Modifier.fillMaxWidth().testTag("playing-everywhere")) {
        Text(str("session.everywhere").uppercase(), color = colors.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Sora,
            letterSpacing = 1.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        rows.forEach { v -> SessionRow(v, drawnPositionMs(v, state.serverNowMs, state.receivedAtMs, now), onOpen, onPlayPause) }
    }
}

@Composable
private fun SessionRow(v: SessionView, positionMs: Long?, onOpen: (SessionView) -> Unit, onPlayPause: ((SessionView) -> Unit)?) {
    val colors = RaviloTheme.colors
    val ended = v.state == "ended"
    val video = v.kind == "film" || v.kind == "episode"
    Box(
        Modifier.fillMaxWidth().heightIn(min = 70.dp).alpha(if (ended) 0.45f else 1f)
            .background(if (v.here) colors.fg.copy(alpha = 0.06f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { PlaybackSessions.touch(v.id); onOpen(v) }
            .testTag("session-row-${v.id}"),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 9.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            val art = v.artwork
            Box(
                (if (video) Modifier.width(64.dp).height(40.dp) else Modifier.size(48.dp)).clip(RoundedCornerShape(if (video) 7.dp else 9.dp))
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (art != null) RemoteImage(art, null, Modifier.fillMaxSize(), requestedWidth = 160)
                else if (!v.mine) Text(v.owner.name.take(1), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
            }
            Column(Modifier.weight(1f)) {
                sessionRowTitle(v)?.let { Text(it, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeskIcon(placeIcon(v.target.icon), colors.accentSecondary, 13.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(v.target.name, color = colors.accentSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (v.state == "playing" && v.mine && !v.reconnecting) { PlayingBars(false, colors.accentSecondary, 10.dp); Spacer(Modifier.width(4.dp)) }
                    val line = sessionStateText(v)
                    if (line.isNotEmpty()) Text(line, color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (onPlayPause != null && v.controllable && !ended) {
                val label = str(if (v.state == "playing") "session.pause" else "action.play")
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(23.dp)).clickable { PlaybackSessions.touch(v.id); onPlayPause(v) }
                    .semantics { contentDescription = label }.testTag("session-pp-${v.id}"), contentAlignment = Alignment.Center) {
                    MusicGlyph(if (v.state == "playing") MusicIcon.PAUSE else MusicIcon.PLAY, colors.text, 20.dp)
                }
            }
        }
        // The 3 dp progress line (absent with no position: someone else's hidden title).
        val d = v.durationMs
        if (positionMs != null && d != null && d > 0) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = 92.dp, end = 20.dp, bottom = 5.dp).fillMaxWidth().height(3.dp)
                .clip(RoundedCornerShape(2.dp)).background(colors.fg.copy(alpha = 0.10f))) {
                Box(Modifier.fillMaxWidth((positionMs.toFloat() / d).coerceIn(0f, 1f)).fillMaxHeight().background(colors.accentGradient))
            }
        }
    }
}

/** The store as a composable reads it. */
@Composable
fun rememberSessionsState(): SessionsState = PlaybackSessions.state.collectAsState().value

/**
 * FR-R368-8 — the phone's mini bar when nothing plays on this device: a session elsewhere (the one this app touched
 * last, else the latest playing one). The second line is the place with its icon, in accent. **+N** opens *Playing
 * everywhere*; ⏯ and next only where [SessionView.controllable] (absent otherwise). Tapping the bar opens [onOpen].
 */
@Composable
fun SessionMiniBar(
    session: SessionView,
    more: Int,
    onOpen: (SessionView) -> Unit,
    onMore: () -> Unit,
    onPlayPause: ((SessionView) -> Unit)? = null,
    onNext: ((SessionView) -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val state = rememberSessionsState()
    val now = rememberSessionClock()
    val pos = drawnPositionMs(session, state.serverNowMs, state.receivedAtMs, now)
    Column(
        Modifier.fillMaxWidth().height(dev.jellystructure.ravilo.ui.theme.RaviloDimens.musicMiniBarHeight).background(colors.surface)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { PlaybackSessions.touch(session.id); onOpen(session) }
            .testTag("session-bar"),
    ) {
        val d = session.durationMs
        val frac = if (pos != null && d != null && d > 0) (pos.toFloat() / d).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(2.dp).background(colors.textDim.copy(0.25f))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(colors.accentGradient))
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant)) {
                session.artwork?.let { RemoteImage(it, null, Modifier.fillMaxSize(), requestedWidth = 120) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(sessionRowTitle(session) ?: str(if (session.kind == "music" || session.kind == "audiobook") "session.person_listening" else "session.person_watching", mapOf("person" to session.owner.name)),
                    color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DeskIcon(placeIcon(session.target.icon), colors.accentSecondary, 12.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(session.target.name, color = colors.accentSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (more > 0) {
                Box(Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(colors.fg.copy(alpha = 0.10f))
                    .clickable { onMore() }.padding(horizontal = 10.dp).testTag("session-bar-more"), contentAlignment = Alignment.Center) {
                    Text("+$more", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
                }
            }
            if (session.controllable && onPlayPause != null) {
                val label = str(if (session.state == "playing") "session.pause" else "action.play")
                Box(Modifier.size(46.dp).clickable { PlaybackSessions.touch(session.id); onPlayPause(session) }.semantics { contentDescription = label }
                    .testTag("session-bar-pp"), contentAlignment = Alignment.Center) {
                    MusicGlyph(if (session.state == "playing") MusicIcon.PAUSE else MusicIcon.PLAY, colors.text, 22.dp)
                }
                if (onNext != null && (session.kind == "music" || session.kind == "episode" || session.kind == "audiobook")) {
                    Box(Modifier.size(46.dp).clickable { PlaybackSessions.touch(session.id); onNext(session) }.testTag("session-bar-next"), contentAlignment = Alignment.Center) {
                        MusicGlyph(MusicIcon.NEXT, colors.text, 20.dp)
                    }
                }
            }
        }
    }
}
