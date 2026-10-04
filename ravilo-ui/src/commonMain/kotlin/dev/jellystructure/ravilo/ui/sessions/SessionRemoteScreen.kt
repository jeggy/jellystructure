package dev.jellystructure.ravilo.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.DeskIcon
import dev.jellystructure.ravilo.ui.components.MusicGlyph
import dev.jellystructure.ravilo.ui.components.MusicIcon
import dev.jellystructure.ravilo.ui.components.placeIcon
import dev.jellystructure.ravilo.ui.components.rememberSessionClock
import dev.jellystructure.ravilo.ui.components.rememberSessionsState
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.seams.safeAreaPadding
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.shared.tv.SessionCommandRequest
import dev.jellystructure.shared.tv.SessionDetail
import dev.jellystructure.shared.tv.SessionView
import kotlin.time.Clock

/** R371 / R372 — the remote's extra parts, supplied by the app (absent ⇒ not drawn). */
class SessionRemoteExtras(
    /** R372 (FR-R372-2) — the place line opens *Move to…*; a place picked there goes here. */
    val onMoveTo: ((SessionView, dev.jellystructure.shared.tv.PlaybackTarget) -> Unit)? = null,
    /** R372 (FR-R372-3) — *Play here*: a move to this device. */
    val onPlayHere: ((SessionView) -> Unit)? = null,
    /** R372's label for *Play here* on this platform (`cast.play_here` · `_mac` · `_desk`). */
    val playHereLabel: String? = null,
    /** R371 — the volume panel (master + rooms + *Add a speaker…*). */
    val volume: (@Composable (SessionDetail) -> Unit)? = null,
)

/**
 * R369 (FR-R369-6, canvas §B5) — a session's remote: the place line under the title (accent, with its icon; R372's
 * *Move to…*), the transport, the seek bar, shuffle/repeat, a film's tracks, the queue, volume (R371), and ⋯'s *Play
 * here* and *Stop* (asks first, every time, on someone else's — owner decision 1). Every control acts on the session
 * through the server; one the target does not obey is absent (its op is not in `SessionDetail.ops`). A press dims for
 * up to 400 ms, a spinner shows after 1 s, and *Can't reach {place}* after 3 s with nothing reflecting it.
 */
@Composable
fun SessionRemoteScreen(sessionId: String, onBack: () -> Unit, extras: SessionRemoteExtras = SessionRemoteExtras()) {
    DisposableEffect(sessionId) { SessionRemote.open(sessionId); onDispose { SessionRemote.close() } }
    val colors = RaviloTheme.colors
    val detail by SessionRemote.detail.collectAsState()
    val listState = rememberSessionsState()
    // The row from the list is newer than the detail for the state and position (session_state reaches every socket).
    val row = listState.sessions.firstOrNull { it.id == sessionId }
    val d = detail
    val v = row ?: d?.session
    val pending by SessionRemote.pending.collectAsState()
    val now = rememberSessionClock()
    val feedback = commandFeedback(pending?.first, Clock.System.now().toEpochMilliseconds().coerceAtLeast(now), reflected = false)
    var moveOpen by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(row?.revision) { if (row != null) SessionRemote.onState(row.revision, row.id) }
    Column(
        Modifier.fillMaxSize().background(colors.background).safeAreaPadding(includeIme = false)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp).testTag("session-remote"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(CircleShape).clickable(onClick = onBack).semantics { contentDescription = "back" }, contentAlignment = Alignment.Center) {
                DeskIcon(DeskIcon.BACK, colors.text, 22.dp)
            }
        }
        if (v == null) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.textDim, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
            }
            return@Column
        }
        val ops = d?.ops.orEmpty().takeIf { v.controllable } ?: emptyList()
        val video = v.kind == "film" || v.kind == "episode"
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = if (video) 480.dp else 300.dp).fillMaxWidth()
                .aspectRatio(if (video) 16f / 9f else 1f).clip(RoundedCornerShape(14.dp)).background(colors.surfaceVariant),
        ) { v.artwork?.let { RemoteImage(it, null, Modifier.fillMaxSize(), requestedWidth = 720) } }
        Spacer(Modifier.height(18.dp))
        Text(sessionRowTitle(v) ?: str(if (v.kind == "music" || v.kind == "audiobook") "session.person_listening" else "session.person_watching", mapOf("person" to v.owner.name)),
            color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, maxLines = 2, overflow = TextOverflow.Ellipsis)
        v.subtitle?.takeIf { v.kind != "episode" }?.let { Text(it, color = colors.textSecondary, fontSize = 15.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        // The place line: *Playing on {place}*, in accent, with its icon; tapping it opens *Move to…* (R372).
        val placeLine = when {
            v.movingTo != null -> str("session.moving", mapOf("place" to v.movingTo.orEmpty()))
            v.moveFailed != null -> str("session.move_failed", mapOf("place" to v.moveFailed.orEmpty()))
            // R371 (FR-R371-3) — a room that left on its own is named for 5 s.
            v.leftRoom != null && listState.serverNowMs > 0 &&
                (listState.serverNowMs + (now - listState.receivedAtMs) - (v.leftAt ?: 0L)) < dev.jellystructure.ravilo.ui.seams.ROOM_LEFT_SHOWN_MS ->
                str("group.left", mapOf("room" to v.leftRoom.orEmpty()))
            v.state == "paused" -> str("cast.paused_on", mapOf("device" to v.target.name))
            else -> str("cast.playing_on", mapOf("device" to v.target.name))
        }
        Row(
            Modifier.padding(top = 6.dp).heightIn(min = 46.dp).then(if (extras.onMoveTo != null && v.controllable) Modifier.clickable { moveOpen = !moveOpen; if (moveOpen) PlayOnStore.changed() } else Modifier)
                .testTag("session-place-line"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DeskIcon(placeIcon(v.target.icon), colors.accentSecondary, 16.dp)
            Spacer(Modifier.width(6.dp))
            Text(placeLine, color = colors.accentSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
        }
        // R372 (FR-R372-2, canvas §B7) — *Move to…*: the single places, the current one ticked; a film only to a video place.
        if (moveOpen && extras.onMoveTo != null) {
            val targets by PlayOnStore.targets.collectAsState()
            Text(str("session.move_to"), color = colors.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            moveTargets(targets.orEmpty(), v).forEach { (t, current) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).then(if (!current) Modifier.clickable { moveOpen = false; extras.onMoveTo.invoke(v, t) } else Modifier)
                    .testTag("session-move-${t.id}"), verticalAlignment = Alignment.CenterVertically) {
                    DeskIcon(placeIcon(t.icon), colors.text, 16.dp)
                    Spacer(Modifier.width(10.dp))
                    val thisDevice = if (dev.jellystructure.ravilo.ui.isDesktopPlatform) str("mode.label_desk") else str("mode.label")
                    Text(if (t.here) thisDevice else t.name, color = colors.text, fontSize = 14.sp, fontFamily = Sora, modifier = Modifier.weight(1f))
                    if (current) Text("✓", color = colors.accentSecondary, fontSize = 15.sp)
                }
            }
        }
        val stateLine = sessionStateLineText(v)
        if (stateLine != null) Text(stateLine, color = colors.textDim, fontSize = 13.sp, fontFamily = Sora)
        // Seek.
        val pos = drawnPositionMs(v, listState.serverNowMs, listState.receivedAtMs, now)
        val dur = v.durationMs
        if (pos != null && dur != null && dur > 0) {
            Spacer(Modifier.height(12.dp))
            var drag by remember { mutableStateOf<Float?>(null) }
            if ("seek" in ops) androidx.compose.material3.Slider(
                value = drag ?: (pos.toFloat() / dur).coerceIn(0f, 1f), onValueChange = { drag = it },
                onValueChangeFinished = { drag?.let { f -> SessionRemote.command(v.id, SessionCommandRequest(op = "seek", positionMs = (f * dur).toLong())) }; drag = null },
                modifier = Modifier.fillMaxWidth().testTag("session-seek"),
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = colors.fg, activeTrackColor = colors.accentSecondary, inactiveTrackColor = colors.fg.copy(0.2f)),
            ) else Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.fg.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth((pos.toFloat() / dur).coerceIn(0f, 1f)).height(4.dp).background(colors.accentGradient))
            }
            Row(Modifier.fillMaxWidth()) {
                Text(dev.jellystructure.ravilo.ui.music.fmtLen(pos), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
                Spacer(Modifier.weight(1f))
                if (feedback == CommandFeedback.SPINNER) CircularProgressIndicator(color = colors.textDim, strokeWidth = 2.dp, modifier = Modifier.size(14.dp).testTag("session-spinner"))
                Spacer(Modifier.weight(1f))
                Text(dev.jellystructure.ravilo.ui.music.fmtLen(dur), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
            }
        }
        if (feedback == CommandFeedback.CANT_REACH) Text(str("session.cant_reach", mapOf("place" to v.target.name)), color = colors.accentSecondary, fontSize = 13.sp, fontFamily = Sora)
        // Transport.
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).alpha(if (feedback == CommandFeedback.DIM) 0.5f else 1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if ("set_shuffle" in ops) RemoteButton(MusicIcon.SHUFFLE, if (d?.shuffle == true) colors.accentSecondary else colors.textDim, "session-shuffle") {
                SessionRemote.command(v.id, SessionCommandRequest(op = "set_shuffle", on = d?.shuffle != true))
            }
            if ("previous" in ops) RemoteButton(MusicIcon.PREVIOUS, colors.text, "session-prev") { SessionRemote.command(v.id, SessionCommandRequest(op = "previous")) }
            if ("play" in ops || "pause" in ops) {
                val playing = v.state == "playing"
                Box(Modifier.padding(horizontal = 12.dp).size(64.dp).clip(CircleShape).background(colors.text)
                    .clickable { SessionRemote.command(v.id, SessionCommandRequest(op = if (playing) "pause" else "play")) }.testTag("session-play-pause")
                    .semantics { contentDescription = if (playing) "pause" else "play" }, contentAlignment = Alignment.Center) {
                    MusicGlyph(if (playing) MusicIcon.PAUSE else MusicIcon.PLAY, colors.background, 28.dp)
                }
            }
            if ("next" in ops) RemoteButton(MusicIcon.NEXT, colors.text, "session-next") { SessionRemote.command(v.id, SessionCommandRequest(op = "next")) }
            if ("set_repeat" in ops) RemoteButton(MusicIcon.REPEAT, if (d?.repeat != null && d.repeat != "off") colors.accentSecondary else colors.textDim, "session-repeat") {
                val nextMode = when (d?.repeat) { "all" -> "one"; "one" -> "off"; else -> "all" }
                SessionRemote.command(v.id, SessionCommandRequest(op = "set_repeat", mode = nextMode))
            }
        }
        // R371 — volume (master and rooms) where the app supplies it.
        if (d != null && v.controllable) extras.volume?.invoke(d)
        // A film's tracks: the picks, as the target reported them.
        if (d != null && "set_audio" in ops && d.audioTracks.size > 1) TrackRow(str("session.audio"), d.audioTracks.map { it.index to (it.label ?: it.language ?: "${it.index + 1}") }, d.audioIndex) { i ->
            SessionRemote.command(v.id, SessionCommandRequest(op = "set_audio", index = i))
        }
        if (d != null && "set_subtitle" in ops && d.subtitleTracks.isNotEmpty()) TrackRow(str("session.subtitles"),
            listOf(-1 to str("session.subtitles_off")) + d.subtitleTracks.map { it.index to (it.label ?: it.language ?: "${it.index + 1}") }, d.subtitleIndex ?: -1) { i ->
            SessionRemote.command(v.id, SessionCommandRequest(op = "set_subtitle", index = i))
        }
        // ⋯ — Play here (R372) and Stop.
        var confirmStop by remember(v.id) { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (extras.onPlayHere != null && !v.here && v.controllable) Pill(extras.playHereLabel ?: str("cast.play_here"), "session-play-here") { extras.onPlayHere.invoke(v) }
            if ("stop" in ops) Pill(str("cast.stop_room"), "session-stop") {
                if (stopNeedsConfirm(v)) confirmStop = true else SessionRemote.command(v.id, SessionCommandRequest(op = "stop"))
            }
        }
        if (confirmStop) {
            Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.fg.copy(alpha = 0.06f)).padding(12.dp).testTag("session-stop-confirm")) {
                Text(str("session.stop_person", mapOf("person" to v.owner.name, "title" to (v.title ?: v.target.name))), color = colors.text, fontSize = 14.sp, fontFamily = Sora)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(str("session.stop_everywhere", mapOf("place" to v.target.name)), "session-stop-yes") { confirmStop = false; SessionRemote.command(v.id, SessionCommandRequest(op = "stop")) }
                    Pill(str("action.cancel"), "session-stop-no") { confirmStop = false }
                }
            }
        }
        // The queue (FR-R369-6): tap to jump.
        if (d != null && d.queue.size > 1) {
            Text(str("music.up_next"), color = colors.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
            d.queue.forEachIndexed { i, e ->
                val index = d.queueOffset + i
                val current = index == d.queueIndex
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (current) colors.fg.copy(alpha = 0.08f) else Color.Transparent)
                        .then(if ("jump" in ops && !current) Modifier.clickable { SessionRemote.command(v.id, SessionCommandRequest(op = "jump", index = index)) } else Modifier)
                        .padding(horizontal = 10.dp).testTag("session-queue-$index"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title ?: "—", color = colors.text, fontSize = 14.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        e.subtitle?.let { Text(it, color = colors.textDim, fontSize = 12.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * R372 (FR-R372-2) — the places a session may move to: every reachable single place that can play its kind (a film or an
 * episode only a video place; a book never a Cast place), the current one ticked.
 */
fun moveTargets(targets: List<dev.jellystructure.shared.tv.PlaybackTarget>, v: SessionView): List<Pair<dev.jellystructure.shared.tv.PlaybackTarget, Boolean>> =
    targets.filter { it.reachable && canPlay(it.capabilities, v.kind, it.kind == "cast") }
        .map { it to (it.id == v.target.id || (it.castDeviceId != null && it.castDeviceId == v.target.castDeviceId)) }

/** FR-R372-5 — the state line under the place, where the state has words of its own. */
@Composable
internal fun sessionStateLineText(v: SessionView): String? = when {
    v.reconnecting -> str("session.reconnecting")
    v.state == "ended" && v.endedBy != null -> str("session.stopped_by", mapOf("person" to v.endedBy.orEmpty()))
    v.state == "ended" && v.endReason == "finished" -> str("ab.finished")
    v.offline -> str("session.place_offline", mapOf("place" to v.target.name, "time" to dev.jellystructure.ravilo.ui.music.fmtLen(v.positionMs ?: 0)))
    v.state == "starting" || v.state == "buffering" -> str("loading")
    !v.mine -> str(if (v.kind == "music" || v.kind == "audiobook") "session.person_listening" else "session.person_watching", mapOf("person" to v.owner.name))
    else -> null
}

@Composable
private fun RemoteButton(icon: MusicIcon, tint: Color, tag: String, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).clickable(onClick = onClick).testTag(tag), contentAlignment = Alignment.Center) { MusicGlyph(icon, tint, 24.dp) }
}

@Composable
internal fun Pill(label: String, tag: String, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    Box(Modifier.heightIn(min = 46.dp).clip(RoundedCornerShape(23.dp)).background(colors.fg.copy(alpha = 0.10f)).clickable(onClick = onClick)
        .padding(horizontal = 18.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
    }
}

@Composable
private fun TrackRow(label: String, options: List<Pair<Int, String>>, selected: Int?, onPick: (Int) -> Unit) {
    val colors = RaviloTheme.colors
    Text(label, color = colors.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.take(6).forEach { (i, name) ->
            val on = i == selected
            Box(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(if (on) colors.accent.copy(alpha = 0.25f) else colors.fg.copy(alpha = 0.08f))
                .clickable { onPick(i) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(name, color = colors.text, fontSize = 13.sp, fontFamily = Sora, maxLines = 1)
            }
        }
    }
}

