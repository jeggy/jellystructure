package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable

/**
 * R293 (FR-R293-1, dev review item 1) — is the app on screen? True from the host activity's `ON_START`
 * (or `ACTION_SCREEN_ON` with the activity still started) until its `ON_STOP` or `ACTION_SCREEN_OFF` (a
 * TV entering standby may send no `ON_STOP`, R292 FR-R292-8). Each entry point is one Activity, so the
 * activity's lifecycle *is* the process's foreground state — the same hook [PlayerLifecycleEffect] uses,
 * no `lifecycle-process` dependency. The events socket, R141's config poll and Home's Live TV poll are all
 * keyed on it: leaving the foreground cancels their effects, which cancels the loop *and* its backoff
 * delay — "no reconnects, no timer" falls out of structured concurrency. A browser tab is always on
 * screen here (its own lifecycle is the tab's; only FR-R293-4's backoff applies there).
 */
@Composable
expect fun rememberAppOnScreen(): Boolean

/**
 * R293 (FR-R293-6) — the device's state at the moment a socket ended, for [EventsSocketLog]:
 * `<lifecycle>;<interactive>;<net>;<sdk>`, each field already whitelisted. Read lazily, never stored.
 */
@Composable
expect fun rememberDeviceStateProbe(): () -> String
