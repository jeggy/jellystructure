package dev.jellystructure.ravilo.ui.screens

/**
 * R366 (owner decision, 2026-10-04) — whether the Coming Soon calendar was empty the last time it answered, kept
 * across launches so Discover can open on the next chip without waiting for the calendar (the cold-start wait R262
 * removed). `null` = never fetched on this device. Per device, not per viewer: the calendar is the same for every
 * viewer (R160). Its own storage, apart from the session store, so a sign-out does not reset it.
 */
expect object DiscoverPrefs {
    fun upcomingEmpty(): Boolean?
    fun setUpcomingEmpty(empty: Boolean?)
}
