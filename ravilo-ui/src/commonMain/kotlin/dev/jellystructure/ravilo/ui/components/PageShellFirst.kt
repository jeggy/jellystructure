package dev.jellystructure.ravilo.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * DEB-4287 — a top-bar page's own content composes one frame after its shell.
 *
 * Choosing Movies, Series, Discover or Home composed the whole new page — app bar, title, facet strip, the grid's or
 * rows' first tiles — in the one frame that follows the key press, a 60–120 ms hold on a TV. The bar and the title are
 * cheap and are what says "the page changed", so they draw first; the content region reads this and composes in the
 * next frame, so the hold is split in two instead of lying in front of everything.
 *
 * True from the second frame on; [skip] true (a Back-return, whose focus restore needs its tile at once) is true in the
 * first, and so is a page the profile menu's Back is returning to ([LocalAvatarReturn]: the bar's own focus waits two
 * frames for the page's arrival focus to have run first, which staging would push past it). A changed [key] starts over, so a page that swaps what it shows in place (Movies to Series keeps the same
 * composable and swaps its store) tears the old content down in one frame and builds the new in the next.
 *
 * Nothing is drawn differently and nothing is derived: the region is simply absent for one frame, and focus requests
 * aimed at it already retry per frame (`requestFocusAwaiting`, the grid's own first-focus loop).
 */
@Composable
fun rememberPastFirstFrame(key: Any? = null, skip: Boolean = false): Boolean {
    val returningToAvatar = LocalAvatarReturn.current != null
    var past by remember(key) { mutableStateOf(skip || returningToAvatar) }
    if (!past) LaunchedEffect(key) {
        withFrameNanos { }
        past = true
    }
    return past
}
