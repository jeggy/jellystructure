package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.RaviloAppContext
import org.json.JSONArray
import org.json.JSONObject

private data class StoredSeriesChoice(
    val seriesKey: String,
    val audioLanguage: String?,
    val subtitleLanguage: String?,
    val subtitlesOff: Boolean,
)

actual object PlaybackPrefsStore {
    private val prefs get() = RaviloAppContext.get()
        .getSharedPreferences("ravilo_playback_prefs", android.content.Context.MODE_PRIVATE)

    private fun choiceToJson(c: RememberedChoice): JSONObject = JSONObject().apply {
        if (c.audioLanguage != null) put("audioLanguage", c.audioLanguage)
        if (c.subtitleLanguage != null) put("subtitleLanguage", c.subtitleLanguage)
        put("subtitlesOff", c.subtitlesOff)
    }

    private fun choiceFromJson(o: JSONObject): RememberedChoice = RememberedChoice(
        audioLanguage = o.optString("audioLanguage").ifEmpty { null },
        subtitleLanguage = o.optString("subtitleLanguage").ifEmpty { null },
        subtitlesOff = o.optBoolean("subtitlesOff", false),
    )

    private fun loadSeriesMap(profileId: String): List<StoredSeriesChoice> {
        val raw = prefs.getString("series_$profileId", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                StoredSeriesChoice(
                    seriesKey        = o.getString("seriesKey"),
                    audioLanguage    = o.optString("audioLanguage").ifEmpty { null },
                    subtitleLanguage = o.optString("subtitleLanguage").ifEmpty { null },
                    subtitlesOff     = o.optBoolean("subtitlesOff", false),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveSeriesMap(profileId: String, list: List<StoredSeriesChoice>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("seriesKey", s.seriesKey)
                if (s.audioLanguage != null) put("audioLanguage", s.audioLanguage)
                if (s.subtitleLanguage != null) put("subtitleLanguage", s.subtitleLanguage)
                put("subtitlesOff", s.subtitlesOff)
            })
        }
        prefs.edit().putString("series_$profileId", arr.toString()).apply()
    }

    actual fun getSeriesChoice(profileId: String, seriesKey: String): RememberedChoice? {
        val stored = loadSeriesMap(profileId).firstOrNull { it.seriesKey == seriesKey } ?: return null
        return RememberedChoice(stored.audioLanguage, stored.subtitleLanguage, stored.subtitlesOff)
    }

    actual fun setSeriesChoice(profileId: String, seriesKey: String, choice: RememberedChoice) {
        val list = loadSeriesMap(profileId).filter { it.seriesKey != seriesKey }.toMutableList()
        list.add(StoredSeriesChoice(seriesKey, choice.audioLanguage, choice.subtitleLanguage, choice.subtitlesOff))
        saveSeriesMap(profileId, list)
    }

    actual fun getGlobalChoice(profileId: String): RememberedChoice? {
        val raw = prefs.getString("global_$profileId", null) ?: return null
        return runCatching { choiceFromJson(JSONObject(raw)) }.getOrNull()
    }

    actual fun setGlobalChoice(profileId: String, choice: RememberedChoice) {
        prefs.edit().putString("global_$profileId", choiceToJson(choice).toString()).apply()
    }

    actual fun clearProfile(profileId: String) {
        prefs.edit().remove("global_$profileId").remove("series_$profileId").apply()
    }
}
