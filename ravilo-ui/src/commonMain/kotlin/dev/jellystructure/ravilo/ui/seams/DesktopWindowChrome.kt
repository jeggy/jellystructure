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
