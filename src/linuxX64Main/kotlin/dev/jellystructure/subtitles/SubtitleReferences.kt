package dev.jellystructure.subtitles

import dev.jellystructure.auth.JellyfinClient
import dev.jellystructure.auth.JellyfinMediaStream
import dev.jellystructure.config.ConfigStore
import dev.jellystructure.db.JellystructureDb
import dev.jellystructure.db.Subtitle_reference
import dev.jellystructure.log.Logger
import dev.jellystructure.media.FfmpegRunner
import dev.jellystructure.media.FileIntegrityService
import dev.jellystructure.model.Track
import dev.jellystructure.model.TrackKind
import dev.jellystructure.resolver.LanguageResolver

/**
 * Phase 273 (FR-273-2/5/6/7) — a video's timing references. An embedded text subtitle is the strongest (it cannot
 * be for another episode and it is timed for this exact release); Jellyfin extracts it, and `prewarm_subtitles`
 * already asks for it. A speech track is the fallback for files with no subtitle inside them. Both are stored per
 * video and are current while the video keeps its size and mtime; a stored "none" stops the same file from being
 * fetched or decoded again.
 */
class SubtitleReferences(
    private val db: JellystructureDb,
    private val jellyfinClient: JellyfinClient,
    private val configStore: ConfigStore,
) {
    private val q get() = db.subtitleCheckQueries

    companion object {
        const val EMBEDDED = "embedded"
        const val SPEECH = "speech"
        const val SOURCE_NONE = "none"
        const val SOURCE_MONO = "mono"
        private val TEXT_CODECS = setOf("subrip", "srt", "ass", "ssa", "webvtt", "mov_text", "text")

        /** The embedded text streams worth trying as a reference, best first: not forced, then by stream order. */
        fun embeddedCandidates(tracks: List<Track>): List<Track> = tracks.filter {
            it.kind == TrackKind.SUBTITLE && !it.external && !it.forced && it.codec.lowercase() in TEXT_CODECS
        }.sortedWith(compareBy<Track> { it.sdh }.thenBy { it.streamIndex })

        /** ffmpeg's `0:s:N` for a stream — Bazarr's sync reference needs this subtitle-relative index. */
        fun subtitleOrdinal(track: Track): Int? = track.specifier.removePrefix("0:s:").toIntOrNull()?.takeIf { track.specifier.startsWith("0:s:") }

        /**
         * Phase 301 (FR-301-1/2) — Jellyfin's subtitle streams inside the file, in its own order. Jellyfin 12.1 numbers
         * a video's external subtitle files first and shifts every stream inside the file up by their count, so its
         * index is not ffprobe's: they are matched by position among the internal subtitle streams instead.
         */
        private fun internalSubtitles(streams: List<JellyfinMediaStream>): List<JellyfinMediaStream> =
            streams.filter { it.type.equals("Subtitle", ignoreCase = true) && !it.isExternal }.sortedBy { it.index }

        /** FR-301-1 — Jellyfin's stream number for the store's internal subtitle [track]; null when the two do not agree. */
        fun jellyfinIndexFor(track: Track, tracks: List<Track>, streams: List<JellyfinMediaStream>): Int? {
            val ordinal = subtitleOrdinal(track) ?: return null
            val ours = tracks.count { it.kind == TrackKind.SUBTITLE && !it.external && subtitleOrdinal(it) != null }
            val theirs = internalSubtitles(streams)
            if (ours != theirs.size) return null
            val s = theirs.getOrNull(ordinal) ?: return null
            if (!track.language.isNullOrBlank() && !s.language.isNullOrBlank() && !LanguageResolver.sameLanguage(track.language, s.language)) return null
            return s.index
        }

        /** FR-301-2 — the store's track for one of Jellyfin's internal subtitle streams; null when the two do not agree. */
        fun trackForJellyfinStream(stream: JellyfinMediaStream, tracks: List<Track>, streams: List<JellyfinMediaStream>): Track? {
            if (stream.isExternal) return null
            val ordinal = internalSubtitles(streams).indexOfFirst { it.index == stream.index }.takeIf { it >= 0 } ?: return null
            val track = tracks.firstOrNull { it.kind == TrackKind.SUBTITLE && !it.external && subtitleOrdinal(it) == ordinal } ?: return null
            return track.takeIf { jellyfinIndexFor(it, tracks, streams) == stream.index }
        }
    }

    /** What is stored for a video: present (with its data), known to be absent, or unknown/stale. */
    sealed interface Stored {
        data class Present(val row: Subtitle_reference) : Stored
        data class Absent(val source: String) : Stored
        data object Unknown : Stored
    }

    fun stored(videoPath: String, kind: String): Stored {
        val stamp = FileIntegrityService.stampOf(videoPath) ?: return Stored.Unknown
        val row = q.referenceFor(videoPath, kind).executeAsOneOrNull() ?: return Stored.Unknown
        if (row.video_size != stamp.size || row.video_mtime != stamp.mtime) return Stored.Unknown
        return if (row.payload.isEmpty()) Stored.Absent(row.source ?: SOURCE_NONE) else Stored.Present(row)
    }

    fun embeddedCues(videoPath: String): List<Cue>? =
        (stored(videoPath, EMBEDDED) as? Stored.Present)?.row?.payload?.let { CueCodec.decode(it) }

    fun speechFrames(videoPath: String): ByteArray? =
        (stored(videoPath, SPEECH) as? Stored.Present)?.row?.payload

    fun embeddedSource(videoPath: String): String? = (stored(videoPath, EMBEDDED) as? Stored.Present)?.row?.source

    /** Every current reference of [paths], for comparing a sidecar against its neighbours (FR-273-3). */
    fun currentFor(paths: Collection<String>): Map<String, List<Subtitle_reference>> {
        if (paths.isEmpty()) return emptyMap()
        val stamps = paths.associateWith { FileIntegrityService.stampOf(it) }
        return paths.chunked(400).flatMap { q.referencesFor(it).executeAsList() }
            .filter { r -> stamps[r.video_path]?.let { it.size == r.video_size && it.mtime == r.video_mtime } == true && r.payload.isNotEmpty() }
            .groupBy { it.video_path }
    }

    private fun put(videoPath: String, kind: String, source: String?, data: ByteArray) {
        val stamp = FileIntegrityService.stampOf(videoPath) ?: return
        q.putReference(videoPath, kind, stamp.size, stamp.mtime, source, SubtitleTiming.HZ.toLong(), data, nowSec())
    }

    /** FR-273-6 — `prewarm_subtitles` hands over the text it fetched. Kept only when the stream is a usable
     *  reference (FR-273-2 rung 1); returns whether it was. */
    fun offerEmbedded(videoPath: String, track: Track, vttText: String, durationMs: Long?): Boolean {
        val cues = CueParser.parse(vttText)
        if (!VerdictRules.usableReference(cues, durationMs)) return false
        put(videoPath, EMBEDDED, "s:${subtitleOrdinal(track) ?: track.streamIndex}", CueCodec.encode(cues))
        return true
    }

    enum class Fetch { STORED, NONE, TIMED_OUT, FAILED }

    /**
     * FR-273-6 — fetch one usable embedded subtitle through Jellyfin for a video that has none stored. Tries at
     * most three streams; a Jellyfin timeout stops at once (it is still reading this file, phase 213's rule).
     */
    suspend fun fetchEmbedded(videoPath: String, jellyfinId: String?, tracks: List<Track>, durationMs: Long?): Fetch {
        val candidates = embeddedCandidates(tracks)
        if (candidates.isEmpty()) { put(videoPath, EMBEDDED, SOURCE_NONE, ByteArray(0)); return Fetch.NONE }
        val cfg = configStore.current.apiKeys
        if (jellyfinId.isNullOrBlank() || cfg.jellyfinUrl.isBlank() || cfg.jellyfinToken.isBlank()) return Fetch.FAILED
        // FR-301-1 — Jellyfin's own numbers for these streams; asking by ffprobe's index fetched a sidecar instead.
        val streams = jellyfinClient.getItemMediaStreams(cfg.jellyfinUrl, cfg.jellyfinToken, jellyfinId)?.mediaStreams ?: return Fetch.FAILED
        for (track in candidates.mapNotNull { t -> jellyfinIndexFor(t, tracks, streams)?.let { t to it } }.take(3)) {
            when (val r = jellyfinClient.fetchSubtitleText(cfg.jellyfinUrl, cfg.jellyfinToken, jellyfinId, track.second)) {
                is JellyfinClient.SubtitleText.Text -> if (offerEmbedded(videoPath, track.first, r.body, durationMs)) return Fetch.STORED
                JellyfinClient.SubtitleText.TimedOut -> return Fetch.TIMED_OUT
                is JellyfinClient.SubtitleText.Failed -> Logger.warn("subtitle reference: stream ${track.second} of $videoPath: ${r.reason}", "subtitles")
            }
        }
        put(videoPath, EMBEDDED, SOURCE_NONE, ByteArray(0))
        return Fetch.NONE
    }

    /** FR-273-5 — decode a speech track. The audio stream in the title's original language when there are several,
     *  else the first. Mono audio is stored as such: mid and side cannot tell dialogue from music there. */
    suspend fun computeSpeech(videoPath: String, tracks: List<Track>, originalLanguage: String?): Fetch {
        val audio = tracks.filter { it.kind == TrackKind.AUDIO }.sortedBy { it.streamIndex }
        if (audio.isEmpty()) { put(videoPath, SPEECH, SOURCE_NONE, ByteArray(0)); return Fetch.NONE }
        val chosen = audio.firstOrNull { LanguageResolver.sameLanguage(it.language, originalLanguage) } ?: audio.first()
        val ordinal = chosen.specifier.removePrefix("0:a:").toIntOrNull() ?: audio.indexOf(chosen)
        val result = FfmpegRunner.computeSpeechTrack(videoPath, ordinal) ?: return Fetch.FAILED
        return if (result.mono) {
            put(videoPath, SPEECH, SOURCE_MONO, ByteArray(0)); Fetch.NONE
        } else {
            put(videoPath, SPEECH, "a:$ordinal", result.frames); Fetch.STORED
        }
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal fun nowSec(): Long = platform.posix.time(null)
