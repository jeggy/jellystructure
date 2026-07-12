@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jellystructure.ravilo.ui.screens

// Wasm-safe helpers: all js() calls must be top-level functions
private fun jsGet(key: String): String? = js("localStorage.getItem(key)")
private fun jsSet(key: String, value: String): Unit = js("localStorage.setItem(key, value)")
private fun jsRemove(key: String): Unit = js("localStorage.removeItem(key)")

// Simple JSON-ish serialization without kotlinx.serialization in wasm actual (same convention as
// MultiTokenStoreWasm). Global format: {"a":audioLanguage,"s":subtitleLanguage,"o":subtitlesOff}.
private fun encodeChoice(c: RememberedChoice): String =
    """{"a":"${c.audioLanguage ?: ""}","s":"${c.subtitleLanguage ?: ""}","o":${c.subtitlesOff}}"""

private fun decodeChoice(raw: String): RememberedChoice? = runCatching {
    val map = raw.removePrefix("{").removeSuffix("}")
        .split(",")
        .associate { kv ->
            val (k, v) = kv.split(":", limit = 2)
            k.trim().removeSurrounding("\"") to v.trim().removeSurrounding("\"")
        }
    RememberedChoice(
        audioLanguage    = map["a"]?.ifEmpty { null },
        subtitleLanguage = map["s"]?.ifEmpty { null },
        subtitlesOff     = map["o"] == "true",
    )
}.getOrNull()

// Series map format: JSON array of {"k":seriesKey,"a":audioLanguage,"s":subtitleLanguage,"o":subtitlesOff}
private fun encodeSeriesMap(list: List<Pair<String, RememberedChoice>>): String =
    list.joinToString(",", "[", "]") { (key, c) ->
        """{"k":"$key","a":"${c.audioLanguage ?: ""}","s":"${c.subtitleLanguage ?: ""}","o":${c.subtitlesOff}}"""
    }

private fun decodeSeriesMap(raw: String): List<Pair<String, RememberedChoice>> =
    raw.removePrefix("[").removeSuffix("]")
        .split("},{")
        .filter { it.isNotBlank() }
        .mapNotNull { chunk ->
            val s = chunk.trim().removePrefix("{").removeSuffix("}")
            val map = s.split(",").associate { kv ->
                val (k, v) = kv.split(":", limit = 2)
                k.trim().removeSurrounding("\"") to v.trim().removeSurrounding("\"")
            }
            val key = map["k"] ?: return@mapNotNull null
            key to RememberedChoice(
                audioLanguage    = map["a"]?.ifEmpty { null },
                subtitleLanguage = map["s"]?.ifEmpty { null },
                subtitlesOff     = map["o"] == "true",
            )
        }

actual object PlaybackPrefsStore {
    actual fun getSeriesChoice(profileId: String, seriesKey: String): RememberedChoice? {
        val raw = jsGet("ravilo_playback_series_$profileId") ?: return null
        return runCatching { decodeSeriesMap(raw) }.getOrDefault(emptyList())
            .firstOrNull { it.first == seriesKey }?.second
    }

    actual fun setSeriesChoice(profileId: String, seriesKey: String, choice: RememberedChoice) {
        val raw = jsGet("ravilo_playback_series_$profileId")
        val list = (raw?.let { runCatching { decodeSeriesMap(it) }.getOrDefault(emptyList()) } ?: emptyList())
            .filter { it.first != seriesKey }
            .toMutableList()
        list.add(seriesKey to choice)
        jsSet("ravilo_playback_series_$profileId", encodeSeriesMap(list))
    }

    actual fun getGlobalChoice(profileId: String): RememberedChoice? =
        jsGet("ravilo_playback_global_$profileId")?.let { decodeChoice(it) }

    actual fun setGlobalChoice(profileId: String, choice: RememberedChoice) {
        jsSet("ravilo_playback_global_$profileId", encodeChoice(choice))
    }

    actual fun clearProfile(profileId: String) {
        jsRemove("ravilo_playback_global_$profileId")
        jsRemove("ravilo_playback_series_$profileId")
    }
}
