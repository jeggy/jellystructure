package dev.jellystructure.ravilo.ui.seams

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * R337 (FR-R337-2/5, dev review 11) — the window's own controls where Ravilo draws the window: GNOME's undecorated
 * window gets a drag area along the top (double-click maximises) with the close button alone on the right. On the Mac
 * the system draws the traffic lights and handles the drag; everywhere else there is no window. Drawn over the page.
 */
@Composable
expect fun DesktopTitleStrip(modifier: Modifier = Modifier)

/** R337 (dev review 11) — invisible 6 dp edges that resize an undecorated window, and its 1 dp border. */
@Composable
expect fun DesktopWindowFrame()

/** R337 — GNOME's primary menu opens the desktop app's About window (the Mac's comes from its app menu). */
expect fun showAboutWindow()

/**
 * R337 (FR-R337-5) — empty chrome that behaves as a title bar: a drag moves the window, a double-click does what the
 * system says a title bar's does. The Mac's window runs its content under a transparent title bar, where AWT takes
 * every press, so the app says where the window may be moved from; GNOME's undecorated window has no title bar at all.
 */
expect fun Modifier.windowDragArea(): Modifier

/**
 * R337 (FR-R337-5) — where the Mac's traffic lights sit: the close button's centre from the window's top-left, in
 * points. The layout says it (inside the sidebar's glass, inside the rail, in the phone layout's strip, over a film).
 * Nothing on any other platform.
 */
expect fun placeWindowControls(x: Float, y: Float)
