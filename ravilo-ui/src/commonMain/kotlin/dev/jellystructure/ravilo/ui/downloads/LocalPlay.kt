package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.StreamTicket
import dev.jellystructure.shared.tv.SubTrack

/** One saved text subtitle (FR-7, FR-16): its WebVTT sits on the phone, [url] is that file as the player is told to read it. */
data class SavedSubtitleFile(val index: Int, val language: String?, val label: String?, val forced: Boolean, val isDefault: Boolean, val url: String)

/**
 * DEB-4072 (DEB-3995 FR-16) — what the phone saved beside one download, as the player needs it. [url] is the stream the downloader read
 * (the original file, or a smaller copy's playlist): the player opens it **through the download's own cache**, so no byte comes from the network.
 */
data class SavedDownload(val kind: String, val url: String, val subtitles: List<SavedSubtitleFile>)

/** The platform half of an offline play: reads [SavedDownload] for one viewer's title. Null when nothing usable is saved. */
interface LocalMediaSource {
    fun read(userId: String, itemId: String): SavedDownload?
    /** FR-13, FR-16 — a saved picture of one title (`poster`, `backdrop` or `still`) as a URL the image loader reads, or null when none was saved. */
    fun artwork(userId: String, itemId: String, type: String): String? = null
}

/**
 * FR-16 — the ticket the normal player is handed for a download, built from what is on the phone and **no server call**:
 * - **Original** (`file`): a direct play, so the player offers every audio and subtitle track the file carries; the ticket lists no
 *   audio of its own (the player's own tracks stand, as for any file), and adds the saved text subtitles.
 * - **Copy** (`hls`): the one audio the copy carries, plus the saved text subtitles.
 * It claims no transcode: nothing here can restream, so nothing in the ticket invites it.
 */
fun buildLocalTicket(itemId: String, saved: SavedDownload, startPositionMs: Long): StreamTicket = StreamTicket(
    jellyfinBaseUrl = "",
    accessToken = "",
    itemId = itemId,
    container = "",
    directPlay = saved.kind == "file",
    hlsUrl = saved.url,
    startPositionMs = startPositionMs.coerceAtLeast(0L),
    subtitles = saved.subtitles.map { SubTrack(index = it.index, language = it.language, label = it.label, forced = it.forced, isDefault = it.isDefault, url = it.url) },
    expiresAt = Long.MAX_VALUE,
)

/**
 * DEB-4072 (FR-15, FR-16) — one play from a download, as [dev.jellystructure.ravilo.ui.screens.PlayerStore] sees it: where the stream comes
 * from, and where progress and the finish go. Nothing here is a server call: progress is a **queued write** in the outbox, never local truth.
 */
interface LocalPlay {
    val itemId: String
    /** The stream ticket; [startPositionMs] null = the title's own resume point (the outbox's offline position, else the server's last). */
    suspend fun ticket(startPositionMs: Long?): StreamTicket
    fun recordProgress(positionMs: Long)
    fun recordWatched()
}

/** The card's kicker for an episode on the phone (`S01E03`, R346's form); null for a film or an episode the server gave no number. */
fun downloadKicker(r: DownloadRecord): String? =
    if (r.kind == "episode" && r.season != null && r.episode != null) dev.jellystructure.shared.tv.episodeCode(r.season, r.episode) else null
