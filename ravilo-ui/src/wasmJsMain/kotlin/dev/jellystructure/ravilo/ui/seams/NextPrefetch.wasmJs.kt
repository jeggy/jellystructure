package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.PreparedStream

/** R381 — the web player has no prefetch yet. */
actual object NextPrefetch {
    actual fun prefetch(stream: PreparedStream) {}
    actual fun discard() {}
}
