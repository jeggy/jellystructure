package dev.jellystructure.ravilo.ui.seams

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
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
    // R376 (FR-R376-1) — the hole the picture shows through. The <video> sits fixed behind the Compose viewport and
    // skiko clears the canvas to opaque white every frame, so this surface clears its own pixels to transparent
    // (BlendMode.Clear writes 0 alpha into the canvas; the WebGL context has an alpha channel). Everything the player
    // draws after it — the shutter, the scrim, the chrome, the picker — paints over the picture as on every platform.
    Box(modifier.drawBehind { drawRect(Color.Black, blendMode = BlendMode.Clear) })
}
