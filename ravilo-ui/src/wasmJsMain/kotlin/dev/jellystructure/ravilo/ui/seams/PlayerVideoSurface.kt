package dev.jellystructure.ravilo.ui.seams

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

@Composable
actual fun PlayerVideoSurface(
    player: RaviloPlayer,
    modifier: Modifier,
    onVideoOutputStuck: () -> Unit,
    onVideoOutputRecovering: (Boolean) -> Unit,
    fill: Boolean,
    subtitleBottomInset: Dp,
) {
    // R251 — the browser renders its own cues inside the <video> element; the inset has no seam here.
    // R244 (FR-R244-6) — the <video> element's object-fit is the web's fit/fill.
    player.setObjectFit(fill)
    Box(modifier) // <video> element is fixed-positioned behind the skiko canvas via CSS
}
