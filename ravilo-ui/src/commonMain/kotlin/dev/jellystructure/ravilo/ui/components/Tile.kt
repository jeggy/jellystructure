package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme

enum class TileVariant { POSTER, LANDSCAPE }

private val POSTER_W   = 140.dp
private val POSTER_H   = 210.dp
private val LANDSCAPE_W = 224.dp
private val LANDSCAPE_H = 126.dp

@Composable
fun Tile(
    title: String,
    posterUrl: String?,
    focusRequester: FocusRequester,
    variant: TileVariant = TileVariant.POSTER,
    progressPct: Float = 0f,
    watched: Boolean = false,
    isNew: Boolean = false,
    onFocused: () -> Unit = {},
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    onSelect: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "tileScale")
    val tileShape = remember { RoundedCornerShape(10.dp) }

    val (w, h) = if (variant == TileVariant.POSTER) POSTER_W to POSTER_H else LANDSCAPE_W to LANDSCAPE_H

    Column(
        modifier = Modifier
            .width(w)
            .scale(scale),
    ) {
        Box(
            modifier = Modifier
                .width(w)
                .height(h)
                .clip(tileShape)
                .then(
                    if (focused) Modifier.border(2.dp, colors.focusRing, tileShape)
                    else Modifier
                )
                .dpadFocusable(
                    focusRequester = focusRequester,
                    onFocused = { focused = true; onFocused() },
                    onBlurred = { focused = false },
                    onLeft = onLeft, onRight = onRight, onUp = onUp, onDown = onDown, onSelect = onSelect,
                ),
        ) {
            if (posterUrl != null) {
                RemoteImage(
                    url = posterUrl,
                    contentDescription = title,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Box(
                    modifier = Modifier.matchParentSize().background(colors.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(title.take(2).uppercase(), color = colors.textSecondary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
            }
            // Progress bar
            if (progressPct > 0f && !watched) {
                Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(colors.progressBg)) {
                    Box(modifier = Modifier.fillMaxWidth(progressPct).height(4.dp).background(colors.progressFill))
                }
            }
            // Watched badge
            if (watched) {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .background(colors.badgeWatched, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text("✓", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            // New badge
            if (isNew && !watched) {
                Box(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(colors.badgeNew, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text("NEW", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            color = if (focused) colors.text else colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(w),
        )
    }
}
