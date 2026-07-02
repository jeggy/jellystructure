package dev.jellystructure.ravilo.ui.seams

import androidx.compose.ui.Modifier

/**
 * R157 (FR-R157-3.1) — fires [onMove] on every pointer-move event, used by the player root to wake
 * chrome + reset the cursor-hide timer on mouse activity. Continuous move-tracking (not just enter/
 * exit) needs the low-level `onPointerEvent` API, which is skiko-only (desktop/web) and unavailable
 * on Android in commonMain — so this is an expect/actual rather than a direct call, no-op on Android
 * where there's no mouse to track anyway (D-pad activity already wakes chrome via the existing
 * onLeft/onRight/onUp/onDown/onSelect handlers).
 */
expect fun Modifier.wakeOnPointerMove(onMove: () -> Unit): Modifier
