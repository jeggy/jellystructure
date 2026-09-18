package dev.jellystructure.ravilo.ui.focus

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import dev.jellystructure.ravilo.ui.isTvPlatform

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
 *
 * R262 (FR-R262-6) — scroll-to-top-on-Back is a TV thing, not a page thing: off the TV a phone's
 * gesture/button Back already pops directly through `PlatformBackHandler`, which this key-event modifier
 * never sees, but a keyboard Escape/Backspace on the web hit this same handler and scrolled to the top
 * instead of leaving. Gated once, here, so none of this modifier's call sites need their own check.
 */
fun Modifier.backToTopOnBack(
    atTop: () -> Boolean,
    onBackToTop: () -> Unit,
): Modifier {
    if (!isTvPlatform) return this
    return this.onKeyEvent { ev ->
        if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
        if (ev.key != Key.Back && ev.key != Key.Escape && ev.key != Key.Backspace) return@onKeyEvent false
        if (atTop()) return@onKeyEvent false        // already at top → let RaviloApp pop/exit
        onBackToTop()                               // scroll to top + move focus to a top target
        true                                        // consumed → no pop/exit
    }
}
