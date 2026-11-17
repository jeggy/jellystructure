package dev.jellystructure.ravilo.screen

import dev.jellystructure.shared.tv.RememberedChoice
import kotlinx.browser.localStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val TRACKS_KEY_PREFIX = "ravilo.screen.tracks."
/** More series than a household watches; the oldest pick goes first so the entry never grows without bound. */
private const val MAX_SERIES = 200
private val trackJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class StoredChoice(
    val audio: String? = null, val sub: String? = null, val off: Boolean = false,
    val audioVariant: String? = null, val subVariant: String? = null,
) {
    fun toChoice() = RememberedChoice(audio, sub, off, audioVariant, subVariant)
    companion object {
        fun of(c: RememberedChoice) = StoredChoice(c.audioLanguage, c.subtitleLanguage, c.subtitlesOff, c.audioVariant, c.subtitleVariant)
    }
}

@Serializable
private data class StoredTracks(val global: StoredChoice? = null, val series: Map<String, StoredChoice> = emptyMap())

/**
 * DEB-4932 (FR-23) — what each signed-in profile last chose for audio and subtitles, on this set only (`localStorage`; nothing
 * goes to the backend, as R181 says). The Compose clients' two tiers: one per **series** and one **global**. A pick writes both;
 * a start reads the series tier first. [seriesId] is null for a start with no series behind it (a phone's push), which reads and
 * writes the global tier only.
 */
internal object ScreenTrackMemory {
    private fun load(userId: String): StoredTracks = runCatching {
        trackJson.decodeFromString<StoredTracks>(localStorage.getItem(TRACKS_KEY_PREFIX + userId) ?: return StoredTracks())
    }.getOrDefault(StoredTracks())

    fun series(userId: String, seriesId: String): RememberedChoice? = load(userId).series[seriesId]?.toChoice()
    fun global(userId: String): RememberedChoice? = load(userId).global?.toChoice()

    /** [change] gets what a pick builds on (the series tier, else the global one) and returns what is remembered now. */
    fun remember(userId: String, seriesId: String?, change: (RememberedChoice?) -> RememberedChoice) {
        val stored = load(userId)
        val base = (seriesId?.let { stored.series[it] } ?: stored.global)?.toChoice()
        val next = StoredChoice.of(change(base))
        val series = if (seriesId == null) stored.series else (stored.series - seriesId + (seriesId to next)).let { m ->
            if (m.size > MAX_SERIES) m.entries.drop(m.size - MAX_SERIES).associate { it.key to it.value } else m
        }
        localStorage.setItem(TRACKS_KEY_PREFIX + userId, trackJson.encodeToString(StoredTracks(global = next, series = series)))
    }

    /** A removed profile's memory goes with it (FR-23). */
    fun clear(userId: String) = localStorage.removeItem(TRACKS_KEY_PREFIX + userId)

    /** Every profile's memory — a new server address signs everyone out (FR-11). */
    fun clearAll() {
        val keys = (0 until localStorage.length).mapNotNull { localStorage.key(it) }.filter { it.startsWith(TRACKS_KEY_PREFIX) }
        keys.forEach { localStorage.removeItem(it) }
    }
}
