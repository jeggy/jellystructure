package dev.jellystructure.ravilo.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.SessionReplace
import dev.jellystructure.shared.tv.SessionStartRequest
import dev.jellystructure.shared.tv.SessionView

/** R370 (FR-R370-4) — Play pressed while the same kind plays for this viewer elsewhere: what was pressed, and the other. */
data class SameKindAsk(val elsewhere: SessionView, val kind: String, val itemId: String, val title: String, val playHere: () -> Unit)

/**
 * R370 (FR-R370-4) — whether pressing Play asks: the viewer's own session of the same kind plays on another place that
 * this app can steer (never asked on the TV, never remembered). Null = just play here.
 */
fun sameKindAskFor(sessions: List<SessionView>, kind: String, isTv: Boolean): SessionView? =
    if (isTv) null else sameKindElsewhere(sessions, kind)?.takeIf { it.controllable }

/** The place to start on instead: the Cast device's own id when the place is a Cast device, else the device. */
fun sameKindStartRequest(a: SameKindAsk): SessionStartRequest = SessionStartRequest(
    targetId = a.elsewhere.target.castDeviceId?.let { "cast:$it" } ?: a.elsewhere.target.id, kind = a.kind, items = listOf(a.itemId),
    replace = SessionReplace(a.elsewhere.id, a.elsewhere.revision),
)

/** The ask itself: *Play on {place} instead* · *Play here* (the other keeps playing). */
@Composable
fun SameKindAskBody(a: SameKindAsk, playHereLabel: String, onThere: () -> Unit, onHere: () -> Unit) {
    val colors = RaviloTheme.colors
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("same-kind-ask"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(str("target.already_playing", mapOf("place" to a.elsewhere.target.name)), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
        a.elsewhere.title?.let { Text(it, color = colors.textSecondary, fontSize = 14.sp, fontFamily = Sora) }
        Box(Modifier.fillMaxWidth().heightIn(min = 46.dp).background(colors.accent, RoundedCornerShape(23.dp)).clickable(onClick = onThere).testTag("same-kind-there"),
            contentAlignment = Alignment.Center) { Text(str("target.play_there", mapOf("place" to a.elsewhere.target.name)), color = colors.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora) }
        Box(Modifier.fillMaxWidth().heightIn(min = 46.dp).background(colors.fg.copy(alpha = 0.08f), RoundedCornerShape(23.dp)).clickable(onClick = onHere).testTag("same-kind-here"),
            contentAlignment = Alignment.Center) { Text(playHereLabel, color = colors.text, fontSize = 15.sp, fontFamily = Sora) }
    }
}
