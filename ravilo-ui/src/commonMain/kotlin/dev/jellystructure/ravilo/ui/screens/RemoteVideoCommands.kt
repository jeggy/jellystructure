package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.jellystructure.ravilo.ui.RemoteControl
import dev.jellystructure.ravilo.ui.isTvPlatform
import dev.jellystructure.ravilo.ui.seams.CastChannelVideo
import dev.jellystructure.ravilo.ui.seams.CastVideoSnapshot
import dev.jellystructure.ravilo.ui.seams.PlayerAudioTrack
import dev.jellystructure.ravilo.ui.seams.PlayerSubtitleTrack
import dev.jellystructure.ravilo.ui.seams.RaviloPlayer
import dev.jellystructure.ravilo.ui.seams.TvCastChannel
import dev.jellystructure.shared.tv.CastTrack
import dev.jellystructure.shared.tv.RemotePlayer
import dev.jellystructure.shared.tv.SessionQueueReport
import kotlinx.coroutines.delay

/**
 * R354 (FR-R354-3/-4) — while the film player is composed, it is the player remote commands drive (the Jellyfin
 * dashboard, Home Assistant, a phone remote). Each command calls what the player's own control calls: the play button
 * ([togglePlay], only when the state differs — a stale or repeated command is harmless), the scrubber ([seekTo]), the
 * skip buttons ([skip]), Back ([stop]), the next-up card's *Play now* ([next]) and the episode list ([previous]).
 * Its own composable, so `PlayerScreen`'s method stays the size R258's register-count verifier allows.
 */
@Composable
internal fun RemoteVideoCommands(
    player: RaviloPlayer,
    isPlaying: () -> Boolean,
    togglePlay: () -> Unit,
    seekTo: (Long) -> Unit,
    skip: (Long) -> Unit,
    stop: () -> Unit,
    next: () -> Unit,
    previous: () -> Unit,
) {
    val playing by rememberUpdatedState(isPlaying)
    val onToggle by rememberUpdatedState(togglePlay)
    val seek by rememberUpdatedState(seekTo)
    val skipBy by rememberUpdatedState(skip)
    val leave by rememberUpdatedState(stop)
    val forward by rememberUpdatedState(next)
    val back by rememberUpdatedState(previous)
    DisposableEffect(player) {
        val remote = object : RemotePlayer {
            override fun play() { if (!playing()) onToggle() }
            override fun pause() { if (playing()) onToggle() }
            override fun toggle() = onToggle()
            override fun stop() = leave()
            override fun seekTo(positionMs: Long) = seek(positionMs)
            override fun seekBy(deltaMs: Long) = skipBy(deltaMs)
            override fun next() = forward()
            override fun previous() = back()
            override fun setVolume(level: Float, muted: Boolean) = player.setVolume(if (muted) 0f else level)
            // R266 (found live 2026-10-09) — a session remote's *Audio & Subs* (`set_audio` / `set_subtitle`): the same
            // picks a cast remote makes, through the player's own picker door. Indexes are the reported lists'.
            override fun selectAudio(index: Int) { SessionVideoTracks.source?.selectAudioAt(index) }
            override fun selectSubtitle(index: Int) { SessionVideoTracks.source?.selectSubtitleAt(index) }
        }
        val detach = RemoteControl.attachVideo(remote)
        onDispose { detach() }
    }
}

/** R380 (FR-R380-7) — one row of the phone's track list: where it sits in the picker's groups. */
internal data class CastPickEntry(val group: PickerLanguage, val version: PickerVersion)

/** R380 — the picker's groups as the phone's remote lists them (R285's shape), and what each position picks. */
internal data class CastVideoLists(
    val audio: List<CastTrack>,
    val subtitles: List<CastTrack>,
    val audioEntries: List<CastPickEntry>,
    val subtitleEntries: List<CastPickEntry>,
    val selectedAudio: Int,
    val selectedSub: Int,
    val subtitleOff: CastPickEntry?,
)

/**
 * R380 — flattens the picker's two-level groups into the flat lists a cast remote shows: every version of every
 * language, in the picker's order. Subtitle *Off* is not a row (the phone's own picker adds it; `subtitle -1` picks it).
 * [selectedAudioFlat]/[selectedSubFlat] are the picker's flat indexes (-1 = subtitles off). Track ids follow the web
 * receiver's scheme (100 + position for subtitles, 200 + position for audio) so the phone can address them.
 */
internal fun castVideoLists(
    audioGroups: List<PickerLanguage>,
    subGroupsWithOff: List<PickerLanguage>,
    audioTracks: List<PlayerAudioTrack>,
    subOptions: List<PlayerSubtitleTrack>,
    selectedAudioFlat: Int,
    selectedSubFlat: Int,
): CastVideoLists {
    val audioEntries = audioGroups.flatMap { g -> g.versions.map { CastPickEntry(g, it) } }
    val subEntries = subGroupsWithOff.filter { !it.isOff }.flatMap { g -> g.versions.map { CastPickEntry(g, it) } }
    val off = subGroupsWithOff.firstOrNull { it.isOff }?.let { g -> g.versions.firstOrNull()?.let { CastPickEntry(g, it) } }
    val audio = audioEntries.mapIndexed { i, e ->
        CastTrack(index = i, label = audioTracks.getOrNull(e.version.flatIndex)?.label, language = e.group.language,
            isDefault = e.version.isDefault, trackId = 200L + i)
    }
    val subs = subEntries.mapIndexed { i, e ->
        CastTrack(index = i, label = subOptions.getOrNull(e.version.flatIndex)?.label, language = e.group.language,
            forced = e.version.forced, isDefault = e.version.isDefault, trackId = 100L + i)
    }
    return CastVideoLists(
        audio = audio, subtitles = subs, audioEntries = audioEntries, subtitleEntries = subEntries,
        selectedAudio = audioEntries.indexOfFirst { it.version.flatIndex == selectedAudioFlat }.coerceAtLeast(0),
        selectedSub = if (selectedSubFlat < 0) -1 else subEntries.indexOfFirst { it.version.flatIndex == selectedSubFlat },
        subtitleOff = off,
    )
}

/**
 * R380 (FR-R380-7) — what the film player hands the cast channel, as fields the player fills one statement at a time.
 * A holder, not ten parameters: PlayerScreen's dex method sits near ART's register limit (R258,
 * `scripts/check-player-dex.sh`), and a ten-lambda call keeps ten values live at once; a field write keeps two.
 */
internal class CastVideoSource {
    var title: () -> String = { "" }
    var kicker: () -> String? = { null }
    var lists: () -> CastVideoLists? = { null }
    var subSize: () -> Char = { 'M' }
    var hasNext: () -> Boolean = { false }
    var applyPick: (Int, PickerLanguage, PickerVersion) -> Unit = { _, _, _ -> }
    var setSubSize: (Char) -> Unit = {}
    var next: () -> Unit = {}
}

/** R380 — the audio track at [index] of the flat list a remote shows, picked as the picker's OK picks it. */
internal fun CastVideoSource.selectAudioAt(index: Int) {
    val e = lists()?.audioEntries?.getOrNull(index) ?: return
    applyPick(0, e.group, e.version)
}

/** R380 — the subtitle at [index] of the flat list a remote shows; -1 is *Off*. */
internal fun CastVideoSource.selectSubtitleAt(index: Int) {
    val l = lists() ?: return
    val e = if (index < 0) l.subtitleOff else l.subtitleEntries.getOrNull(index)
    if (e != null) applyPick(1, e.group, e.version)
}

/**
 * R266 (found live 2026-10-09) — the film player's tracks for the playback session it is the place of: the server
 * keeps them on the session ([SessionQueueReport] with no queue — a film has none), and the session remote shows
 * *Audio & Subs* from them. Null while the player has no track lists yet.
 */
internal fun sessionTracksReport(itemId: String, l: CastVideoLists?): SessionQueueReport? {
    if (l == null || (l.audio.isEmpty() && l.subtitles.isEmpty())) return null
    return SessionQueueReport(itemId = itemId, audioTracks = l.audio, subtitleTracks = l.subtitles, audioIndex = l.selectedAudio, subtitleIndex = l.selectedSub)
}

/** R266 — the film player a session's track commands reach (the TV's, while [CastChannelVideoHost] is composed). */
internal object SessionVideoTracks {
    var source: CastVideoSource? = null
}

/** How often the TV looks for a changed track list or pick, and re-sends an unchanged one (the session may be newer). */
private const val TRACKS_CHECK_MS = 2_000L
private const val TRACKS_RESEND_MS = 30_000L

/**
 * R380 (FR-R380-7) — while a cast drives this film player (R266, the TV app), the phone's remote reads its tracks and
 * picks them through the player's own `applyPick` door (persistence, restreams and burn-ins as a picker OK). Its own
 * composable, beside [RemoteVideoCommands], for the same register-count reason (R258). TV only.
 */
@Composable
internal fun CastChannelVideoHost(itemId: String, source: CastVideoSource) {
    if (!isTvPlatform) return
    DisposableEffect(itemId, source) {
        val host = object : CastChannelVideo {
            override fun snapshot(): CastVideoSnapshot? {
                val l = source.lists() ?: return null
                return CastVideoSnapshot(
                    itemId = itemId, title = source.title(), kicker = source.kicker(), audioTracks = l.audio, subtitleTracks = l.subtitles,
                    selectedAudio = l.selectedAudio, selectedSub = l.selectedSub, subSize = source.subSize().toString(),
                    hasNext = source.hasNext(), transcoding = null,
                )
            }
            override fun selectAudio(index: Int) = source.selectAudioAt(index)
            override fun selectSubtitle(index: Int) = source.selectSubtitleAt(index)
            override fun setSubSize(size: String) { source.setSubSize(size.firstOrNull()?.takeIf { it in "SML" } ?: 'M') }
            override fun nextEpisode() = source.next()
        }
        val detach = TvCastChannel.attachVideo(host)
        SessionVideoTracks.source = source
        onDispose { detach(); if (SessionVideoTracks.source === source) SessionVideoTracks.source = null }
    }
    // R266 (found live 2026-10-09) — the server road: a film the server started here has a session remote whose *Audio &
    // Subs* reads the tracks from the session. Sent on change, and again now and then (a first report can come before
    // the session exists; the server drops a report that changes nothing).
    LaunchedEffect(itemId, source) {
        var last: SessionQueueReport? = null
        var sentAt = 0L
        while (true) {
            val r = sessionTracksReport(itemId, source.lists())
            val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
            if (r != null && (r != last || now - sentAt >= TRACKS_RESEND_MS)) {
                dev.jellystructure.ravilo.ui.sessions.SessionRemote.reportQueue(r)
                last = r; sentAt = now
            }
            delay(TRACKS_CHECK_MS)
        }
    }
}
