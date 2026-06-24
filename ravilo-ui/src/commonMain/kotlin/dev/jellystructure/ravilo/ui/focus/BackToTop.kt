package dev.jellystructure.ravilo.ui.focus

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * R55 — Back/Escape scrolls a scrolled content page to the top before leaving it.
 *
 * When the page is **not** at the top ([atTop] returns false), a Back press runs [onBackToTop] — which
 * should scroll the page to the top **and move focus to a top target** (an app-bar entry, the hero, the
 * search keyboard, or the first item) so Compose's bring-into-view doesn't immediately pull the scroll
 * back down to the still-focused lower item — and the event is **consumed**. When the page is already at
 * the top, the event is **not** consumed, so it bubbles up to `RaviloApp`'s handler and does the normal
 * thing (pop to the previous page, or exit at the root). This makes it noticeably harder to close the
 * app by accident.
 *
 * Key events bubble child → ancestor, so a screen applying this on its root runs **before** `RaviloApp`'s
 * Box; consuming here cleanly suppresses the pop. Media-item detail pages deliberately do **not** apply
 * it — Back there pops straight back.
 */
fun Modifier.backToTopOnBack(
    atTop: () -> Boolean,
    onBackToTop: () -> Unit,
): Modifier = this.onKeyEvent { ev ->
    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
    if (ev.key != Key.Back && ev.key != Key.Escape && ev.key != Key.Backspace) return@onKeyEvent false
    if (atTop()) return@onKeyEvent false        // already at top → let RaviloApp pop/exit
    onBackToTop()                               // scroll to top + move focus to a top target
    true                                        // consumed → no pop/exit
}
