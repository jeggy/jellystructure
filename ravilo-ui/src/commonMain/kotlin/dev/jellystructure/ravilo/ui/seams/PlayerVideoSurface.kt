package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Phase R220 (FR-R220-3) — [onVideoOutputStuck] is rung 4 of the video-output-loss recovery ladder: the
 * Android actual calls it only after re-attaching the surface, flushing via a seek, and recreating the
 * surface have all failed to restore rendering while the player is genuinely still playing. Default
 * no-op so every other platform (and any existing call site) is unaffected; the Android actual is the
 * only one that ever invokes it. The caller (PlayerScreen) re-arms the current item's session exactly as
 * its existing background/foreground return path already does — see that call site's own comment.
 *
 * Phase R220 (FR-R220-5) — [onVideoOutputRecovering] reports true for the whole time the Android actual's
 * ladder is running (rungs 1-3; false again once a rung restores frames, or right before the rung-4
 * hand-off), so the caller can force R218's existing STALL presentation on for that stretch instead of
 * a viewer sitting on a frozen frame with no chrome change (phase-R220 §5 open question 7). Default no-op;
 * only the Android actual ever calls it with anything but false.
 */
// R244 (FR-R244-6) — [fill] crops the picture to the frame (a pinch on a handset toggles it); false is
// the letterboxed fit every platform has always drawn. Never an arbitrary zoom.
@Composable
expect fun PlayerVideoSurface(
    player: RaviloPlayer,
    modifier: Modifier = Modifier,
    onVideoOutputStuck: () -> Unit = {},
    onVideoOutputRecovering: (Boolean) -> Unit = {},
    fill: Boolean = false,
    // R251 (FR-R251-4) — bottom inset for rendered subtitle cues, screen-relative (R77): the 28 dp floor
    // plus the transport band while the chrome is up. Platforms without their own cue renderer ignore it.
    subtitleBottomInset: Dp = 28.dp,
)
