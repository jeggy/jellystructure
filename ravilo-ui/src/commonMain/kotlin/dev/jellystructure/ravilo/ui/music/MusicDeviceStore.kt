package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.shared.tv.RaviloWireJson

/**
 * R321/R322 — what this device remembers about listening: the mode (FR-R321-1, per device and never on the server),
 * *Even out volume* (FR-R322-9) and the last queue (dev review 7). Its own storage, apart from the sessions store,
 * so that only the sign-out code decides what a sign-out forgets.
 */
expect object MusicDeviceStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** FR-R321-1 — `video` or `music`. */
object ListeningMode {
    const val VIDEO = "video"
    const val MUSIC = "music"
    fun read(): String = runCatching { MusicDeviceStore.get("mode") }.getOrNull()?.takeIf { it == MUSIC } ?: VIDEO
    fun write(mode: String) { runCatching { MusicDeviceStore.put("mode", mode) } }
}

object MusicPrefs {
    /** FR-R322-9 (J6's lean) — on unless this device turned it off. */
    var evenVolume: Boolean
        get() = runCatching { MusicDeviceStore.get("even_volume") }.getOrNull() != "0"
        set(on) { runCatching { MusicDeviceStore.put("even_volume", if (on) "1" else "0") } }
}

/** Dev review 7 — the queue a device resumes: which songs, which one, where in it, and for which viewer. */
object MusicQueueStore {
    fun save(s: MusicQueueSnapshot) {
        runCatching { MusicDeviceStore.put("queue", RaviloWireJson.encodeToString(MusicQueueSnapshot.serializer(), s)) }
    }
    fun load(): MusicQueueSnapshot? = runCatching {
        MusicDeviceStore.get("queue")?.let { RaviloWireJson.decodeFromString(MusicQueueSnapshot.serializer(), it) }
    }.getOrNull()
    fun clear() { runCatching { MusicDeviceStore.put("queue", null) } }
}

/**
 * R373 (dev review 4, open question 1) — the album page's ▾ pick, per album and per device: one key (`album_pick`)
 * holding the albums whose pick is *Play album + extras*. A garbled value reads as the plain album.
 */
object AlbumPickStore {
    private const val KEY = "album_pick"
    /** One album id per line (Jellyfin ids have no line breaks); anything else reads as no pick. */
    private fun read(): Set<String> = runCatching { MusicDeviceStore.get(KEY) }.getOrNull().orEmpty()
        .split('\n').map { it.trim() }.filter { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }.toSet()

    fun get(albumId: String): AlbumPick = if (albumId in read()) AlbumPick.EXTRAS else AlbumPick.ALBUM

    fun set(albumId: String, pick: AlbumPick) {
        val now = read().let { if (pick == AlbumPick.EXTRAS) it + albumId else it - albumId }
        runCatching { MusicDeviceStore.put(KEY, if (now.isEmpty()) null else now.sorted().joinToString("\n")) }
    }

    fun clear() { runCatching { MusicDeviceStore.put(KEY, null) } }
}

/** FR-R321-1 — signing out: the next viewer on this phone starts in video mode, with nobody's queue. */
fun forgetListening() {
    ListeningMode.write(ListeningMode.VIDEO)
    MusicQueueStore.clear()
    AlbumPickStore.clear()   // R373 dev review 4
    BookLastStore.clear()
    runCatching { MusicEngine.clear() }
}
