package dev.jellystructure.ravilo.ui.seams

/**
 * R169 (FR-R169-3) — web-only fallback transport bar. On web, the Compose canvas can't be made
 * transparent in this Compose Multiplatform version (verified: `CanvasBasedWindow` has no alpha
 * param, and migrating to `ComposeViewport` would replace the hand-authored `#ComposeTarget` canvas
 * that the app's manual keyboard/gamepad dispatch targets directly — too large a blast radius to land
 * blind here), so a Compose-drawn chrome can never composite over a still-visible `<video>`: either
 * the video sits above the canvas (visible, but Compose's chrome — painted on the canvas below — is
 * hidden), or below it (chrome visible, but the whole picture is blacked out). This bridge is the
 * documented "second choice" (see the spec): while only the basic transport (play/pause/skip/seek) is
 * needed — i.e. no track picker / next-up card / episode rail — the web actual keeps the `<video>`
 * promoted above the canvas and draws a small DOM/CSS transport bar + dim scrim on top of it instead,
 * wired back into the same player actions. The picker/next-up/rail overlays are unaffected: they still
 * demote the video behind the canvas exactly as before, so Compose keeps drawing them with zero change.
 * Android/TV: a no-op — Compose keeps drawing 100% of the chrome, exactly as it does today.
 */
expect object PlayerChromeBridge {
    fun show(state: PlayerChromeState, actions: PlayerChromeActions)
    fun hide()
}

data class PlayerChromeState(
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

class PlayerChromeActions(
    val onTogglePlay: () -> Unit,
    val onSkipBack: () -> Unit,
    val onSkipForward: () -> Unit,
    val onSeek: (Long) -> Unit,
)
