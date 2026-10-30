package dev.jellystructure.ravilo.ui.remote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import dev.jellystructure.ravilo.ui.components.CastController
import dev.jellystructure.ravilo.ui.components.DeskIcon
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.platformGroupController
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.SessionRoom
import kotlinx.coroutines.delay

/**
 * DEB-2660 (DEB-2628 FR-11) — a station's rooms in the remote's radio shape (`radio-remote-downstairs`): each room's level and
 * *Add a speaker…*. R371 draws these from the server's session, and a station has none (DEB-2627 FR-5.4), so this reads the link:
 * this app's own [platformGroupController] (Android 11+) for the rooms, their levels and the speakers that can join. Where the
 * platform has none (the Mac, Linux) the panel is absent and the radio shape's volume is the group's level alone.
 *
 * The first room leads the group, so it has no remove here (a song's does a *Move to…*, which a station has no session for);
 * every other room can be taken out. One room alone shows no per-room row: the group's level is the room's.
 */
@Composable
internal fun RadioRoomsPanel(cast: CastController) {
    val g = platformGroupController() ?: return
    val colors = RaviloTheme.colors
    var members by remember { mutableStateOf<List<SessionRoom>>(emptyList()) }
    var addable by remember { mutableStateOf<List<SessionRoom>>(emptyList()) }
    var open by remember { mutableStateOf(false) }
    // The controller has no flow: the rooms are read again every 2 s while the remote is up, and at once after a press.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(g, open, tick) {
        while (true) {
            members = g.members()
            if (open) addable = g.selectable()
            delay(2_000L)
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("radio-rooms")) {
        if (members.size > 1) members.forEachIndexed { i, room ->
            Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).alpha(if (room.volume != null) 1f else 0.5f), verticalAlignment = Alignment.CenterVertically) {
                Text(room.name, color = colors.text, fontSize = 14.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
                var drag by remember(room.castDeviceId, room.volume) { mutableStateOf<Float?>(null) }
                Slider(
                    value = drag ?: ((room.volume ?: 0) / 100f), enabled = room.volume != null, onValueChange = { drag = it },
                    onValueChangeFinished = { drag?.let { f -> g.setRoomVolume(room.castDeviceId, (f * 100).toInt()); tick++ }; drag = null },
                    modifier = Modifier.weight(1f).testTag("radio-room-vol-${room.castDeviceId}"),
                    colors = SliderDefaults.colors(thumbColor = colors.fg, activeTrackColor = colors.accentSecondary, inactiveTrackColor = colors.fg.copy(0.2f)),
                )
                if (i > 0) {
                    val removeLabel = str("volume.remove_room", mapOf("place" to room.name))
                    Box(Modifier.size(40.dp).clip(CircleShape).clickable { g.remove(room.castDeviceId); tick++ }
                        .semantics { contentDescription = removeLabel }.testTag("radio-room-remove-${room.castDeviceId}"), contentAlignment = Alignment.Center) {
                        DeskIcon(DeskIcon.CLOSE, colors.textDim, 16.dp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable { open = !open }.testTag("radio-add-speaker"), verticalAlignment = Alignment.CenterVertically) {
            Text("+", color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
            Text(str("group.add_speaker"), color = colors.text, fontSize = 14.sp, fontFamily = Sora)
        }
        if (open) addable.filter { a -> members.none { it.castDeviceId == a.castDeviceId } }.forEach { a ->
            Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(start = 28.dp).clickable { g.add(a.castDeviceId); tick++ }.testTag("radio-add-${a.castDeviceId}"), verticalAlignment = Alignment.CenterVertically) {
                Text(a.name, color = colors.text, fontSize = 14.sp, fontFamily = Sora)
            }
        }
    }
}
