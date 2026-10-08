package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.shared.tv.PreparedStream

/** R381 — the desktop players (mpv, AVPlayer) have no prefetch yet. */
actual object NextPrefetch {
    actual fun prefetch(stream: PreparedStream) {}
    actual fun discard() {}
}
