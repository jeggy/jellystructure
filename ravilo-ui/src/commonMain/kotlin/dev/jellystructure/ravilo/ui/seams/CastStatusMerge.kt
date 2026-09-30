package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.ravilo.ui.screens.failedAfter
import dev.jellystructure.shared.tv.CastReceiverMessage
import dev.jellystructure.shared.tv.CastTrack

/*
 * R330 (FR-R330-3, dev review 5) — what the remote shows, rebuilt from the receiver's media status plus the
 * receiver's own last message, as ONE pure function both senders use: Android maps the Cast SDK's `MediaStatus`
 * into a [CastMediaSnapshot], the Mac maps `MEDIA_STATUS` JSON. It was `CastSenderAndroid.rebuildStatus`; the
 * rules are unchanged.
 */

/** The receiver's media, neutral of any SDK. */
data class CastMediaSnapshot(
    /** `IDLE` · `PLAYING` · `PAUSED` · `BUFFERING` · `LOADING`; null = no media status yet. */
    val playerState: String? = null,
    /** Idle because the item played to its end. */
    val idleFinished: Boolean = false,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val activeTrackIds: Set<Long> = emptySet(),
    /** The media's own CAF tracks; null when no media is loaded (nothing can be a burn-in then). */
    val mediaTrackIds: Set<Long>? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val imageUrl: String? = null,
)

/** The receiver's messages, folded: a `status` or `tracks` message replaces; anything else updates what it carries. */
fun foldReceiverMessage(prev: CastReceiverMessage?, msg: CastReceiverMessage): CastReceiverMessage = when (msg.type) {
    "status", "tracks" -> msg
    else -> (prev ?: msg).copy(
        type = msg.type, retryAfter = msg.retryAfter, sinceMs = msg.sinceMs, nextupSecs = msg.nextupSecs,
        nextTitle = msg.nextTitle, receiverId = msg.receiverId ?: prev?.receiverId,
    )
}

/** R285 — a listed subtitle with no CAF track behind it is a burn-in (PGS) candidate. */
fun isCastBurnIn(track: CastTrack?, media: CastMediaSnapshot): Boolean {
    val id = track?.trackId ?: return false
    return media.mediaTrackIds?.none { it == id } ?: false
}

fun mergeCastStatus(prev: CastRemoteStatus?, said: CastReceiverMessage?, media: CastMediaSnapshot, event: String?, nowMs: Long): CastRemoteStatus {
    val p = prev ?: CastRemoteStatus()
    val idle = media.playerState == null || media.playerState == "IDLE"
    val ended = event == "ended" || media.idleFinished
    val subs = said?.subtitleTracks ?: emptyList()
    // R285 — an active CAF text track is the selection; with none active, a burned-in subtitle (which has no CAF
    // track at all) is — and only the receiver can know that. Audio is never a CAF track on an HLS cast.
    val selectedSub = subs.indexOfFirst { it.trackId != null && it.trackId in media.activeTrackIds }
        .takeIf { it >= 0 } ?: said?.selectedSub?.takeIf { isCastBurnIn(subs.getOrNull(it), media) } ?: -1
    val audios = said?.audioTracks ?: emptyList()
    val selectedAudio = said?.selectedAudio ?: 0
    // A song's length: what the device reports, else what the queue says of the song. A speaker reports none for a
    // FLAC (the guest-room speaker, 2026-09-30: the bar read 2:17 of 0:00 and had no progress), and the last song's
    // length must not stand in for the next one's.
    val queue = said?.queue ?: p.queue
    val queueIndex = said?.queueIndex ?: p.queueIndex
    val itemId = said?.itemId ?: p.itemId
    val duration = media.durationMs?.takeIf { it > 0 }
        ?: queue.getOrNull(queueIndex)?.durationMs?.takeIf { it > 0 }
        ?: (if (queue.isNotEmpty() && itemId != p.itemId) 0L else p.durationMs)
    return p.copy(
        itemId = itemId,
        title = said?.title ?: media.title ?: p.title,
        kicker = said?.kicker ?: media.subtitle ?: p.kicker,
        artUrl = said?.artUrl ?: media.imageUrl ?: p.artUrl,
        positionMs = media.positionMs?.coerceAtLeast(0) ?: p.positionMs,
        durationMs = duration,
        playing = media.playerState == "PLAYING",
        buffering = media.playerState == "BUFFERING" || media.playerState == "LOADING",
        loaded = !idle || said?.type == "status",
        ended = ended,
        // R299 — set by the receiver's own word, cleared by the next load's status.
        failed = failedAfter(event, p.failed),
        hasNext = said?.hasNext ?: p.hasNext,
        nextUpSecs = if (event == "nextup") said?.nextupSecs else if (event == "status" || ended) null else p.nextUpSecs,
        nextTitle = said?.nextTitle ?: p.nextTitle,
        busyRetryAfter = if (event == "busy") said?.retryAfter else if (event == "status" || event == "tracks") null else p.busyRetryAfter,
        busySinceMs = if (event == "busy") (said?.sinceMs ?: nowMs) else if (event == "status" || event == "tracks") null else p.busySinceMs,
        noServer = if (event == "noserver") true else if (event == "status" || event == "tracks") false else p.noServer,
        audioTracks = audios.ifEmpty { p.audioTracks },
        subtitleTracks = subs.ifEmpty { p.subtitleTracks },
        selectedAudio = if (audios.isNotEmpty()) selectedAudio else p.selectedAudio,
        selectedSub = if (subs.isNotEmpty()) selectedSub else p.selectedSub,
        transcoding = said?.transcoding ?: p.transcoding,
        subSize = said?.subSize?.firstOrNull() ?: p.subSize,
        receiverId = said?.receiverId ?: p.receiverId,
        // R324 (FR-R324-4/8) — the receiver's queue snapshot, rebuilt from its word (286 dev review 10).
        music = said?.queue?.isNotEmpty() ?: p.music,
        queue = queue,
        queueIndex = queueIndex,
        repeat = said?.repeat ?: p.repeat,
        shuffle = said?.shuffle ?: p.shuffle,
        lyricsOn = if (said != null) said.lyricsOn else p.lyricsOn,
    )
}
