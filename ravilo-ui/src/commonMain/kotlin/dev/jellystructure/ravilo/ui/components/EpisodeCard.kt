package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.Episode

@Composable
fun EpisodeCard(
    episode: Episode,
    focusRequester: FocusRequester,
    onFocused: () -> Unit = {},
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onSelect: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, label = "epScale")

    Row(
        modifier = Modifier
            .scale(scale)
            .width(320.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .then(
                if (focused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(10.dp))
                else Modifier
            )
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true; onFocused() },
                onLeft = onLeft, onRight = onRight, onUp = onUp, onDown = onDown, onSelect = onSelect,
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Still image
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(68.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceVariant),
        ) {
            val stillUrl = episode.stillUrl
            if (stillUrl != null) {
                RemoteImage(
                    url = stillUrl,
                    contentDescription = episode.title,
                    modifier = Modifier.matchParentSize(),
                )
            }
            // Progress bar
            val pct = episode.playback.pct
            if (pct > 0f && !episode.playback.watched) {
                Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(colors.progressBg)) {
                    Box(modifier = Modifier.fillMaxWidth(pct).height(3.dp).background(colors.progressFill))
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "E${episode.episodeNumber} · ${episode.title}",
                color = if (focused) colors.text else colors.textSecondary,
                fontSize = 13.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (episode.runtime > 0) {
                Spacer(Modifier.height(4.dp))
                Text("${episode.runtime} min", color = colors.textSecondary, fontSize = 11.sp)
            }
            episode.overview?.let { overview ->
                Spacer(Modifier.height(4.dp))
                Text(overview, color = colors.textSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
