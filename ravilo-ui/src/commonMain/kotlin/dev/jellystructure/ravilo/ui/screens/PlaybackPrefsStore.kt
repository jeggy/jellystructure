package dev.jellystructure.ravilo.ui.screens

/**
 * R181 — a viewer's audio/subtitle language choice, remembered **client-side only** (never sent to the
 * jellystructure backend — the opposite of `SettingsStore`/`RaviloConfig`). `subtitlesOff` is a real,
 * first-class remembered state, distinct from "no subtitle preference" (`subtitleLanguage == null`).
 */
data class RememberedChoice(
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val subtitlesOff: Boolean = false,
)

/**
 * Platform-specific: persists per-profile playback language preferences locally on the device.
 * Modeled on [MultiTokenStore] (`ProfilePickerScreen.kt`) — same SharedPreferences/localStorage split,
 * same "per profile" keying (by `LocalSession.userId`). Two tiers: a per-series remembered choice
 * ([seriesKey] = the series' own item id, or a movie's own id — a movie is its own bucket per the R181
 * spec) and one learned global choice used when no per-series memory exists yet.
 */
expect object PlaybackPrefsStore {
    fun getSeriesChoice(profileId: String, seriesKey: String): RememberedChoice?
    fun setSeriesChoice(profileId: String, seriesKey: String, choice: RememberedChoice)
    fun getGlobalChoice(profileId: String): RememberedChoice?
    fun setGlobalChoice(profileId: String, choice: RememberedChoice)

    /** Called when a profile is removed/unpaired — hook into `MultiTokenStore.remove`/`clear` call sites. */
    fun clearProfile(profileId: String)
}
