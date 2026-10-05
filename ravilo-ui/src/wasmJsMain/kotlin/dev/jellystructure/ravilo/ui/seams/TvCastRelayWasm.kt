package dev.jellystructure.ravilo.ui.seams

/** R378 — the web app is never a relay (R370 owner decision 1); this is the Android TV's. */
actual fun platformTvCastRelay(): TvCastRelay? = null
