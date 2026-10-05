package dev.jellystructure.ravilo.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.DeskIcon
import dev.jellystructure.ravilo.ui.components.placeIcon
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.volumeRows
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.SessionCommandRequest
import dev.jellystructure.shared.tv.SessionDetail

/**
 * R371 (FR-R371-2/-4, canvas §B4·a / §B4a) — a session's volume in its remote: one slider for one place; with several
 * rooms the master *Volume* on top (never *All speakers*) and one slider per room, each with its own mute and a remove;
 * *Add a speaker…* last (music only) — one tap adds one room, no ticks, no confirm, the list stays open. A room that
 * does not report its level shows *—*, disabled, with *{place} doesn't report its volume*. Every change goes to the
 * server: the master to the receiver, a room to the app holding the session's Cast link or a relay app.
 */
@Composable
fun SessionVolumePanel(d: SessionDetail, onMoveToRoom: ((castDeviceId: String) -> Unit)? = null) {
    val colors = RaviloTheme.colors
    val v = d.session
    val roomsReachable = "room_volume" in d.ops
    val rows = volumeRows(d.volume, d.muted, v.target.name, v.rooms, roomsReachable)
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("session-volume")) {
        rows.forEachIndexed { i, row ->
            val master = row.castDeviceId == null
            Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).alpha(if (row.enabled) 1f else 0.5f), verticalAlignment = Alignment.CenterVertically) {
                Text(if (master && rows.size > 1) str("cast.volume") else row.name, color = colors.text, fontSize = 14.sp,
                    fontWeight = if (master) FontWeight.SemiBold else FontWeight.Normal, fontFamily = Sora,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp).padding(start = if (master) 0.dp else 12.dp))
                var drag by remember(row.castDeviceId, row.level) { mutableStateOf<Float?>(null) }
                if (row.level != null) androidx.compose.material3.Slider(
                    value = drag ?: (row.level / 100f), enabled = row.enabled, onValueChange = { drag = it },
                    onValueChangeFinished = {
                        drag?.let { f -> SessionRemote.command(v.id, SessionCommandRequest(op = "set_volume", level = (f * 100).toInt(), castDeviceId = row.castDeviceId)) }
                        drag = null
                    },
                    modifier = Modifier.weight(1f).testTag("session-vol-${row.castDeviceId ?: "master"}"),
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = colors.fg, activeTrackColor = colors.accentSecondary, inactiveTrackColor = colors.fg.copy(0.2f)),
                ) else Text("—", color = colors.textDim, fontSize = 14.sp, fontFamily = Sora, modifier = Modifier.weight(1f))
                if (!master && row.enabled) {
                    // Found on the Pixel 9 Pro (2026-10-05): both room buttons had no name for a screen reader (or a test).
                    val muteLabel = str(if (row.muted) "volume.unmute_room" else "volume.mute_room", mapOf("place" to row.name))
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable {
                        SessionRemote.command(v.id, SessionCommandRequest(op = "set_mute", muted = !row.muted, castDeviceId = row.castDeviceId))
                    }.semantics { contentDescription = muteLabel }, contentAlignment = Alignment.Center) { DeskIcon(DeskIcon.VOLUME, if (row.muted) colors.textDim else colors.text, 18.dp) }
                    val removeLabel = str("volume.remove_room", mapOf("place" to row.name))
                    if ("remove_room" in d.ops) Box(Modifier.size(40.dp).clip(CircleShape).clickable {
                        // R371 (review item 10) — the first room leads the group: dropping it is a *Move to* the next room
                        // (R372); the last room stops the session; any other room is deselected.
                        when (val p = dev.jellystructure.ravilo.ui.seams.removeRoomPlan(v.rooms, row.castDeviceId ?: return@clickable)) {
                            is dev.jellystructure.ravilo.ui.seams.RoomRemoval.Deselect ->
                                SessionRemote.command(v.id, SessionCommandRequest(op = "remove_room", castDeviceId = p.castDeviceId))
                            is dev.jellystructure.ravilo.ui.seams.RoomRemoval.MoveTo ->
                                onMoveToRoom?.invoke(p.castDeviceId) ?: SessionRemote.move(v.id, "cast:${p.castDeviceId}")
                            dev.jellystructure.ravilo.ui.seams.RoomRemoval.Stop -> SessionRemote.command(v.id, SessionCommandRequest(op = "stop"))
                        }
                    }.semantics { contentDescription = removeLabel }.testTag("session-room-remove-${row.castDeviceId}"), contentAlignment = Alignment.Center) { DeskIcon(DeskIcon.CLOSE, colors.textDim, 16.dp) }
                }
            }
            if (!row.enabled && (row.level == null)) Text(str("volume.no_report", mapOf("place" to row.name)), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora,
                modifier = Modifier.padding(start = if (i == 0) 0.dp else 12.dp))
        }
        if (v.kind == "music") AddSpeakerRows(d)
    }
}

/** FR-R371-2 — *Add a speaker…*: the free speakers and displays the server lists; a busy one is listed, not tappable. */
@Composable
private fun AddSpeakerRows(d: SessionDetail) {
    val colors = RaviloTheme.colors
    var open by remember { mutableStateOf(false) }
    val canAdd = "add_room" in d.ops
    val targets by PlayOnStore.targets.collectAsState()
    val inGroup = d.session.rooms.map { it.castDeviceId }.toSet() + setOfNotNull(d.session.target.id)
    val candidates = dev.jellystructure.ravilo.ui.seams.addableRooms(targets.orEmpty(), inGroup + setOfNotNull(d.session.target.castDeviceId), d.addable)
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).alpha(if (canAdd) 1f else 0.5f)
        .then(if (canAdd) Modifier.clickable { open = !open; if (open) PlayOnStore.changed() } else Modifier).testTag("session-add-speaker"),
        verticalAlignment = Alignment.CenterVertically) {
        Text("+", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
        Text(str("group.add_speaker"), color = colors.text, fontSize = 14.sp, fontFamily = Sora)
    }
    if (!canAdd) Text(str("group.no_reach"), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
    if (open && canAdd) candidates.forEach { t ->
        val free = t.busy == null
        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(start = 28.dp).alpha(if (free) 1f else 0.45f)
            .clip(RoundedCornerShape(8.dp))
            .then(if (free) Modifier.clickable {
                SessionRemote.command(d.session.id, SessionCommandRequest(op = "add_room", castDeviceId = t.castDeviceId))
            } else Modifier).background(Color.Transparent).testTag("session-add-${t.castDeviceId}"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DeskIcon(placeIcon(t.icon), colors.text, 16.dp)
            Text(t.name, color = colors.text, fontSize = 14.sp, fontFamily = Sora)
        }
    }
}

/** FR-R371-2 — the speakers and displays that may join: Cast places not already in the group; busy ones listed. */
fun addSpeakerRows(targets: List<dev.jellystructure.shared.tv.PlaybackTarget>, inGroup: Set<String>): List<dev.jellystructure.shared.tv.PlaybackTarget> =
    targets.filter { it.kind == "cast" && it.reachable && it.castDeviceId != null && it.castDeviceId !in inGroup && it.id !in inGroup }
