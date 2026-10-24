package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import dev.jellystructure.ravilo.ui.screens.SsoPlatform

/**
 * DEB-2233 (FR-12) — this platform's way into a browser and back for *Sign in with ‹name›*, or null where the button is never offered:
 * an Android TV (the TV signs in by code, FR-5). Android opens a Custom Tab and is returned to by its private-use scheme, the desktop opens the
 * default browser and listens on a loopback port, Ravilo Web navigates this tab and finds the hand-off in its address when it loads again.
 * Remembered once per composition, so the same instance serves every recomposition of the screen.
 */
@Composable
expect fun rememberSsoPlatform(): SsoPlatform?
