package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Phase R220 (FR-R220-3) — [onVideoOutputStuck] is rung 4 of the video-output-loss recovery ladder: the
 * Android actual calls it only after re-attaching the surface, flushing via a seek, and recreating the
 * surface have all failed to restore rendering while the player is genuinely still playing. Default
 * no-op so every other platform (and any existing call site) is unaffected; the Android actual is the
 * only one that ever invokes it. The caller (PlayerScreen) re-arms the current item's session exactly as
 * its existing background/foreground return path already does — see that call site's own comment.
 */
@Composable
expect fun PlayerVideoSurface(player: RaviloPlayer, modifier: Modifier = Modifier, onVideoOutputStuck: () -> Unit = {})
