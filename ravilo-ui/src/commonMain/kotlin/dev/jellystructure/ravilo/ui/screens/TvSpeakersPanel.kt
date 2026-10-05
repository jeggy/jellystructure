package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.volumeRows
import dev.jellystructure.ravilo.ui.sessions.PlayOnStore
import dev.jellystructure.ravilo.ui.sessions.SessionRemote
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.PlaybackTarget
import dev.jellystructure.shared.tv.SessionCommandRequest
import dev.jellystructure.shared.tv.SessionDetail
import dev.jellystructure.shared.tv.SessionView

/**
 * R372 (FR-R372-6, owner decision 3; canvas §B9·c) — the TV's *Speakers* panel: one of the viewer's music sessions plays
 * on a group of speakers, and the TV player's chrome gets a *Speakers* button at the end of its row. The panel lists the
 * master *Volume* and one row per room; ▲▼ move between rows, ◀▶ change the focused level by 5, OK mutes, Back closes.
 * Every change goes through the server (the master to the receiver, a room by R371's road). The TV remote's own volume
 * keys stay the TV's: the panel never consumes them.
 */
object TvSpeakers {
    /** The TV player shows the button (set by the app from the session list). */
    var available by mutableStateOf(false)
    /** The panel is open over the player. */
    var open by mutableStateOf(false)
    /** The session the panel is for. */
    var sessionId by mutableStateOf<String?>(null)
}

/** The session the TV's Speakers panel is for: the viewer's own live music session on a group, steerable, not here. */
fun tvSpeakersSession(sessions: List<SessionView>): SessionView? = sessions.firstOrNull {
    it.mine && it.controllable && !it.here && it.kind == "music" && it.state in LIVE &&
        (it.target.kind == "cast_group" || it.rooms.size > 1)
}

private val LIVE = setOf("starting", "playing", "paused", "buffering")

/** One row of the panel. [castDeviceId] null = the master (or the add row when [add]); [candidate] = a speaker to add. */
data class TvSpeakerRow(
    val castDeviceId: String?,
    val name: String,
    val level: Int?,
    val muted: Boolean,
    val enabled: Boolean,
    /** `volume.no_report` / `group.no_reach` — why a row is disabled. */
    val reason: String? = null,
    val master: Boolean = false,
    val add: Boolean = false,
    val candidate: Boolean = false,
)

/**
 * The rows: the master (labelled *Volume* when there are rooms), each room (disabled with its reason when no road
 * reaches the rooms or the room does not report its level), then *Add a speaker…* and, when opened, the free speakers.
 */
fun tvSpeakersPanel(d: SessionDetail, addOpen: Boolean = false, targets: List<PlaybackTarget> = emptyList()): List<TvSpeakerRow> {
    val v = d.session
    val roomsReachable = "room_volume" in d.ops
    val rows = volumeRows(d.volume, d.muted, v.target.name, v.rooms, roomsReachable).map { r ->
        val master = r.castDeviceId == null
        TvSpeakerRow(
            castDeviceId = r.castDeviceId, name = r.name, level = r.level, muted = r.muted, enabled = r.enabled, master = master,
            reason = when {
                r.enabled -> null
                r.level == null -> "volume.no_report"
                else -> "group.no_reach"
            },
        )
    }
    val canAdd = "add_room" in d.ops
    val add = TvSpeakerRow(null, "", null, false, enabled = canAdd, reason = if (canAdd) null else "group.no_reach", add = true)
    val inGroup = v.rooms.map { it.castDeviceId }.toSet() + setOfNotNull(v.target.id, v.target.castDeviceId)
    val candidates = if (addOpen && canAdd) dev.jellystructure.ravilo.ui.seams.addableRooms(targets, inGroup, d.addable).map { t ->
        TvSpeakerRow(t.castDeviceId, t.name, null, false, enabled = t.busy == null, candidate = true)
    } else emptyList()
    return rows + add + candidates
}

enum class TvKey { UP, DOWN, LEFT, RIGHT, OK, BACK, OTHER }

sealed interface TvSpeakersAction {
    data class Focus(val index: Int) : TvSpeakersAction
    data class Send(val command: SessionCommandRequest) : TvSpeakersAction
    data object ToggleAdd : TvSpeakersAction
    data object Close : TvSpeakersAction
    /** Consumed, nothing to do (an edge, a disabled row). */
    data object Nothing : TvSpeakersAction
    /** Not the panel's key: the TV's own volume keys, and anything else, pass on. */
    data object NotConsumed : TvSpeakersAction
}

const val TV_SPEAKERS_STEP = 5

/** What a key does on the panel (pure, so the D-pad rules are tested without a TV). */
fun tvSpeakersKey(rows: List<TvSpeakerRow>, focus: Int, key: TvKey): TvSpeakersAction {
    val row = rows.getOrNull(focus)
    return when (key) {
        TvKey.UP -> if (focus > 0) TvSpeakersAction.Focus(focus - 1) else TvSpeakersAction.Nothing
        TvKey.DOWN -> if (focus < rows.lastIndex) TvSpeakersAction.Focus(focus + 1) else TvSpeakersAction.Nothing
        TvKey.LEFT, TvKey.RIGHT -> {
            val level = row?.level
            if (row == null || !row.enabled || row.add || row.candidate || level == null) TvSpeakersAction.Nothing
            else {
                val next = (level + if (key == TvKey.RIGHT) TV_SPEAKERS_STEP else -TV_SPEAKERS_STEP).coerceIn(0, 100)
                if (next == level) TvSpeakersAction.Nothing
                else TvSpeakersAction.Send(SessionCommandRequest(op = "set_volume", level = next, castDeviceId = row.castDeviceId))
            }
        }
        TvKey.OK -> when {
            row == null || !row.enabled -> TvSpeakersAction.Nothing
            row.add -> TvSpeakersAction.ToggleAdd
            row.candidate -> TvSpeakersAction.Send(SessionCommandRequest(op = "add_room", castDeviceId = row.castDeviceId))
            else -> TvSpeakersAction.Send(SessionCommandRequest(op = "set_mute", muted = !row.muted, castDeviceId = row.castDeviceId))
        }
        TvKey.BACK -> TvSpeakersAction.Close
        TvKey.OTHER -> TvSpeakersAction.NotConsumed
    }
}

private fun tvKeyOf(k: Key): TvKey = when (k) {
    Key.DirectionUp -> TvKey.UP
    Key.DirectionDown -> TvKey.DOWN
    Key.DirectionLeft -> TvKey.LEFT
    Key.DirectionRight -> TvKey.RIGHT
    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> TvKey.OK
    Key.Back, Key.Escape -> TvKey.BACK
    else -> TvKey.OTHER
}

/** The panel itself, over the TV player (right side, as §B9·c draws it). */
@Composable
fun TvSpeakersPanelHost() {
    val id = TvSpeakers.sessionId
    if (!TvSpeakers.open || id == null) return
    val colors = RaviloTheme.colors
    DisposableEffect(id) {
        SessionRemote.open(id)
        PlayOnStore.changed()
        onDispose { SessionRemote.close() }
    }
    val detail by SessionRemote.detail.collectAsState()
    val targets by PlayOnStore.targets.collectAsState()
    var addOpen by remember(id) { mutableStateOf(false) }
    var focus by remember(id) { mutableIntStateOf(0) }
    val d = detail?.takeIf { it.session.id == id }
    val rows = d?.let { tvSpeakersPanel(it, addOpen, targets.orEmpty()) }.orEmpty()
    val fr = remember { FocusRequester() }
    LaunchedEffect(id) { runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(colors.background.copy(alpha = 0.55f)).testTag("tv-speakers"), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.width(560.dp).fillMaxHeight().background(colors.surface).padding(horizontal = 40.dp, vertical = 48.dp)
                .focusRequester(fr).focusable()
                .onKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent tvKeyOf(ev.key) != TvKey.OTHER
                    when (val a = tvSpeakersKey(rows, focus, tvKeyOf(ev.key))) {
                        is TvSpeakersAction.Focus -> { focus = a.index; true }
                        is TvSpeakersAction.Send -> { SessionRemote.command(id, a.command); true }
                        TvSpeakersAction.ToggleAdd -> { addOpen = !addOpen; if (addOpen) PlayOnStore.changed(); true }
                        TvSpeakersAction.Close -> { TvSpeakers.open = false; true }
                        TvSpeakersAction.Nothing -> true
                        TvSpeakersAction.NotConsumed -> false
                    }
                },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(d?.session?.title ?: str("tv.speakers"), color = colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = Sora,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            d?.let { Text(it.session.rooms.joinToString(" + ") { r -> r.name }.ifEmpty { it.session.target.name }, color = colors.textSecondary, fontSize = 18.sp, fontFamily = Sora) }
            Box(Modifier.height(12.dp))
            rows.forEachIndexed { i, r -> TvSpeakerRowView(r, focused = i == focus, multi = rows.count { !it.add && !it.candidate } > 1) }
            Box(Modifier.weight(1f))
            Text(str("tv.speakers_keys"), color = colors.textDim, fontSize = 16.sp, fontFamily = Sora)
        }
    }
}

@Composable
private fun TvSpeakerRowView(r: TvSpeakerRow, focused: Boolean, multi: Boolean) {
    val colors = RaviloTheme.colors
    val shape = RoundedCornerShape(12.dp)
    // R350 — focus is never colour alone: the focused row is lit and ringed.
    Column(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .background(if (focused) colors.fg.copy(alpha = 0.12f) else colors.fg.copy(alpha = 0f), shape)
            .then(if (focused) Modifier.border(3.dp, colors.focusRing, shape) else Modifier)
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .testTag("tv-speaker-${if (r.add) "add" else r.castDeviceId ?: "master"}"),
        verticalArrangement = Arrangement.Center,
    ) {
        val alpha = if (r.enabled) 1f else 0.5f
        Row(verticalAlignment = Alignment.CenterVertically) {
            val label = when {
                r.add -> str("group.add_speaker")
                r.master && multi -> str("cast.volume")
                else -> r.name
            }
            Text(label, color = colors.text.copy(alpha = alpha), fontSize = 20.sp, fontFamily = Sora,
                fontWeight = if (r.master) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f).padding(start = if (r.candidate) 24.dp else 0.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!r.add && !r.candidate) Text(
                when { r.level == null -> "—"; r.muted -> str("tv.speakers_muted"); else -> "${r.level}%" },
                color = colors.textSecondary.copy(alpha = alpha), fontSize = 18.sp, fontFamily = Sora,
            )
        }
        if (!r.add && !r.candidate && r.level != null) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).background(colors.fg.copy(alpha = 0.18f), RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(r.level / 100f).height(6.dp).background(if (r.muted) colors.textDim else colors.accentSecondary, RoundedCornerShape(3.dp)))
            }
        }
        r.reason?.let { Text(str(it, mapOf("place" to r.name)), color = colors.textDim, fontSize = 15.sp, fontFamily = Sora, modifier = Modifier.padding(top = 4.dp)) }
    }
}
