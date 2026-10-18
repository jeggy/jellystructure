package dev.jellystructure.tv

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.shared.tv.ChannelConfig

/**
 * DEB-1788 (FR-2, FR-3) — which users hold a stored channel logo's URL. The two places a URL can live are a
 * collection's `logo_url` (the global layout or any per-user layout) and a Live TV channel override's
 * `logo_override_url`; both are read at the moment of the request, so a removal is decided on the server's
 * state and not on what the picker last showed.
 */
class ChannelLogoUsage(
    private val raviloConfigService: RaviloConfigService,
    private val liveTvService: LiveTvService,
    private val configStore: ConfigStore,
    private val jellyfinClient: JellyfinClient,
) {
    /** Stored file name → who uses it; a file nothing uses is absent. */
    suspend fun byFile(): Map<String, List<String>> {
        val layouts = raviloConfigService.allLayouts()
        val names = if (layouts.any { it.first != GLOBAL_USER_ID }) userNames() else emptyMap()
        return usageByFile(
            layouts.map { (id, cfg) -> layoutLabel(id, names) to cfg.channels },
            liveTvService.lineup().map { it.name to it.logoUrl },
        )
    }

    /** Jellyfin user id → name, empty when Jellyfin is not configured or does not answer (the id is shown instead). */
    private suspend fun userNames(): Map<String, String> {
        val jf = configStore.current.apiKeys
        if (jf.jellyfinUrl.isBlank() || jf.jellyfinToken.isBlank()) return emptyMap()
        return jellyfinClient.getUsersOrNull(jf.jellyfinUrl, jf.jellyfinToken).orEmpty().associate { it.id to it.name }
    }

    companion object {
        const val PUBLIC_PATH = "/api/tv/channel-logos/"

        private fun layoutLabel(userId: String, names: Map<String, String>): String =
            if (userId == GLOBAL_USER_ID) "everyone's layout"
            else "${names[userId]?.takeIf { it.isNotBlank() } ?: userId}'s layout"

        /** The stored file [url] names — query string and fragment ignored, a host in front tolerated — or null. */
        fun storedFileOf(url: String?): String? {
            val bare = url?.substringBefore('#')?.substringBefore('?') ?: return null
            val at = bare.lastIndexOf(PUBLIC_PATH)
            if (at < 0) return null
            return bare.substring(at + PUBLIC_PATH.length).takeIf { it.isNotEmpty() && '/' !in it }
        }

        /** [layouts] is (layout label, its collections); [liveTv] is (channel display name, logo override URL). */
        fun usageByFile(layouts: List<Pair<String, List<ChannelConfig>>>, liveTv: List<Pair<String, String?>>): Map<String, List<String>> {
            val out = linkedMapOf<String, MutableList<String>>()
            fun add(url: String?, who: String) {
                val file = storedFileOf(url) ?: return
                val list = out.getOrPut(file) { mutableListOf() }
                if (who !in list) list += who
            }
            for ((label, channels) in layouts) for (ch in channels) add(ch.logoUrl, "the collection ‘${ch.name.ifBlank { ch.id }}’ ($label)")
            for ((name, url) in liveTv) add(url, "the Live TV channel ‘$name’")
            return out
        }

        /** FR-1's 409 sentence: "In use by A and B — pick another logo there first." */
        fun refusal(usedBy: List<String>): String {
            val who = when (usedBy.size) {
                1 -> usedBy[0]
                else -> usedBy.dropLast(1).joinToString(", ") + " and " + usedBy.last()
            }
            return "In use by $who — pick another logo there first."
        }
    }
}
