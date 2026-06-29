package dev.jellystructure.torrent

import dev.jellystructure.config.TrackerEntry

data class ResolvedTracker(
    val name: String,
    val priv: Boolean,
    val matchedHost: String,
    val mirrorCount: Int,
    val unmapped: Boolean,
)

object TrackerResolver {
    fun resolve(announceUrls: List<String>, trackers: List<TrackerEntry>): ResolvedTracker {
        val hosts = announceUrls.map { hostOf(it) }
        for (tracker in trackers) {
            val matched = hosts.firstOrNull { tracker.hosts.contains(it) }
            if (matched != null) return ResolvedTracker(tracker.name, tracker.isPrivate, matched, hosts.size, false)
        }
        val firstHost = hosts.firstOrNull() ?: "unknown"
        return ResolvedTracker(firstHost, false, firstHost, hosts.size, true)
    }

    fun unmappedHosts(torrents: List<QBTorrent>, trackers: List<TrackerEntry>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (t in torrents) {
            val url = t.tracker.takeIf { it.isNotBlank() } ?: continue
            val host = hostOf(url)
            val mapped = trackers.any { it.hosts.contains(host) }
            if (!mapped) counts[host] = (counts[host] ?: 0) + 1
        }
        return counts
    }

    fun hostOf(url: String): String = runCatching {
        val stripped = url.removePrefix("udp://")
        val withScheme = if ("://" in stripped) stripped else "https://$stripped"
        withScheme.substringAfter("://").substringBefore("/").substringBefore(":")
    }.getOrElse { url }
}
