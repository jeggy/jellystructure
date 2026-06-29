package dev.jellystructure.torrent

import dev.jellystructure.config.TrackerEntry
import kotlinx.serialization.Serializable

data class ResolvedTracker(
    val name: String,
    val priv: Boolean,
    val matchedHost: String,
    val mirrorCount: Int,
    val unmapped: Boolean,
)

@Serializable
data class DetectedTrackerGroup(
    val hosts: List<String>,
    val torrentCount: Int,
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

    /**
     * Groups unmapped tracker hosts by passkey co-occurrence. qBittorrent reports only
     * one active announce URL per torrent, but mirrors of the same tracker share the same
     * per-user passkey segment in the URL path. Grouping by that segment surfaces mirror
     * sets as a single detected group, so the operator can name the whole group at once.
     */
    fun detectUnmappedGroups(torrents: List<QBTorrent>, knownTrackers: List<TrackerEntry>): List<DetectedTrackerGroup> {
        val knownHosts = knownTrackers.flatMap { it.hosts }.toSet()
        // passkey (or "single:<host>" for no-passkey trackers) → set of observed hosts
        val groupHosts = mutableMapOf<String, MutableSet<String>>()
        val groupCount = mutableMapOf<String, Int>()
        for (t in torrents) {
            val url = t.tracker.takeIf { it.isNotBlank() } ?: continue
            val host = hostOf(url)
            if (host in knownHosts) continue
            val key = extractPasskey(url).takeIf { it.length >= 8 } ?: "single:$host"
            groupHosts.getOrPut(key) { mutableSetOf() }.add(host)
            groupCount[key] = (groupCount[key] ?: 0) + 1
        }
        return groupHosts.map { (key, hosts) ->
            DetectedTrackerGroup(hosts.sorted(), groupCount[key] ?: 0)
        }.sortedByDescending { it.torrentCount }
    }

    // Extract the longest alphanumeric path segment that looks like a passkey (16+ chars).
    // Passkeys appear as path segments in announce URLs, e.g. /announce/<passkey> or /a/<passkey>/announce.
    private fun extractPasskey(url: String): String {
        val path = url.substringAfter("://").substringAfter("/")
        return path.split("/")
            .filter { seg -> seg.length >= 16 && seg.all { it.isLetterOrDigit() || it == '-' || it == '_' } }
            .maxByOrNull { it.length } ?: ""
    }

    fun hostOf(url: String): String = runCatching {
        val stripped = url.removePrefix("udp://")
        val withScheme = if ("://" in stripped) stripped else "https://$stripped"
        withScheme.substringAfter("://").substringBefore("/").substringBefore(":")
    }.getOrElse { url }
}
