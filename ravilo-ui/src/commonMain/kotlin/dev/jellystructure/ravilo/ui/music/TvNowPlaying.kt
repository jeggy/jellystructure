package dev.jellystructure.ravilo.ui.music

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import dev.jellystructure.ravilo.ui.components.DeskIcon
import dev.jellystructure.ravilo.ui.components.GlyphDirection
import dev.jellystructure.ravilo.ui.components.TriangleGlyph
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.TvCastChannel
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.shared.tv.TrackLyrics
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.delay

// ─── R380 — the TV plays music too ─────────────────────────────────────────────────────────────────────────────────
//
// A music queue (or a book) the TV plays — cast from a phone through Cast Connect (R266) or sent by the server (R370) —
// on a page of its own, at 10-foot sizes: the cover on the left, the song's facts, a progress line, *Next · {song}*,
// synced lyrics when they are on. No buttons until a key is pressed (FR-R380-3). The remote follows the owner's
// TV-remote rules (FR-R380-4); Back hides the page and the music plays on (FR-R380-6); the queue's end closes it
// (FR-R380-8). Nothing here opens an album or an artist (FR-R380-9: browsing music on a TV is a later phase).

/** R380 (FR-R380-6) — opens Now playing from the TV app bar's pill; null where there is no TV page. */
val LocalOpenTvNowPlaying = staticCompositionLocalOf<(() -> Unit)?> { null }

/** A key on the TV remote, as Now playing reads it. A *hold* is ◀ ▶ kept down past the remote's key repeat. */
enum class TvMusicKey { OK, PLAY_PAUSE, PLAY, PAUSE, STOP, LEFT, RIGHT, LEFT_HOLD, RIGHT_HOLD, DOWN, UP, BACK }

sealed interface TvMusicAction {
    data object Toggle : TvMusicAction
    data object Play : TvMusicAction
    data object Pause : TvMusicAction
    /** The music stops and the session ends. */
    data object Stop : TvMusicAction
    data object Previous : TvMusicAction
    data object Next : TvMusicAction
    /** A book's −30 s / +30 s (R323), across parts. */
    data class SkipBook(val deltaMs: Long) : TvMusicAction
    /** Hold ◀ ▶: seek within the song (or the book). */
    data class Seek(val deltaMs: Long) : TvMusicAction
    data object Lyrics : TvMusicAction
    data object Queue : TvMusicAction
    /** Back only hides (never stops). */
    data object Hide : TvMusicAction
}

const val TV_MUSIC_HOLD_SEEK_MS = 10_000L
const val TV_BOOK_SKIP_MS = 30_000L

/**
 * FR-R380-4 — the owner's TV-remote rules (2026-09-28, as 286 FR-286-5): OK and Play/Pause toggle, Stop stops,
 * ◀ ▶ previous/next song (a book: −30 s / +30 s), hold ◀ ▶ seeks 10 s, ▼ lyrics, ▲ the queue, Back hides.
 */
fun tvMusicAction(key: TvMusicKey, book: Boolean): TvMusicAction = when (key) {
    TvMusicKey.OK, TvMusicKey.PLAY_PAUSE -> TvMusicAction.Toggle
    TvMusicKey.PLAY -> TvMusicAction.Play
    TvMusicKey.PAUSE -> TvMusicAction.Pause
    TvMusicKey.STOP -> TvMusicAction.Stop
    TvMusicKey.LEFT -> if (book) TvMusicAction.SkipBook(-TV_BOOK_SKIP_MS) else TvMusicAction.Previous
    TvMusicKey.RIGHT -> if (book) TvMusicAction.SkipBook(TV_BOOK_SKIP_MS) else TvMusicAction.Next
    TvMusicKey.LEFT_HOLD -> TvMusicAction.Seek(-TV_MUSIC_HOLD_SEEK_MS)
    TvMusicKey.RIGHT_HOLD -> TvMusicAction.Seek(TV_MUSIC_HOLD_SEEK_MS)
    TvMusicKey.DOWN -> TvMusicAction.Lyrics
    TvMusicKey.UP -> TvMusicAction.Queue
    TvMusicKey.BACK -> TvMusicAction.Hide
}

/** FR-R380-4 — the ▼ hint under the transport row names what ▼ will do: *Lyrics off* while they show, else *Lyrics on*. */
internal fun lyricsHintKey(lyricsOn: Boolean): String = if (lyricsOn) "tvmusic.lyrics_off" else "tvmusic.lyrics_on"

/**
 * FR-R380-5 — the heading over the queue panel's songs: *Up next from {album}* when the queue came from somewhere with a
 * name (a cast album, an artist, a playlist), else *Up next*. Recently played and a search have no place to name.
 */
internal fun upNextHeading(context: MusicContext?): Pair<String, Map<String, String>> {
    val label = context?.label?.takeIf { it.isNotBlank() && context.kind != "played" && context.kind != "search" }
    return if (label != null) "tvmusic.up_next_from" to mapOf("x" to label) else "music.up_next" to emptyMap()
}

/** FR-R380-3 — the transport row shows this long after a key. */
private const val TRANSPORT_MS = 5_000L
/** FR-R380-8 — the last song shows as finished this long, then the page closes. */
private const val FINISHED_MS = 3_000L
/** A held ◀ ▶ seeks at most this often (a remote repeats its key every 50–100 ms). */
private const val HOLD_STEP_MS = 400L

/** Carries out [a] on the TV's own engine; Hide and the two panels are the page's. */
private fun perform(a: TvMusicAction) {
    when (a) {
        TvMusicAction.Toggle -> MusicEngine.togglePlay()
        TvMusicAction.Play -> MusicEngine.play()
        TvMusicAction.Pause -> MusicEngine.pause()
        TvMusicAction.Stop -> { TvCastChannel.endMusic(); MusicEngine.clear() }
        TvMusicAction.Previous -> MusicEngine.previous()
        TvMusicAction.Next -> MusicEngine.next()
        is TvMusicAction.SkipBook -> MusicEngine.skipBy(a.deltaMs)
        is TvMusicAction.Seek -> {
            val st = MusicEngine.state.value
            if (st.book != null) MusicEngine.skipBy(a.deltaMs)
            else {
                val end = st.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
                MusicEngine.seekTo((MusicEngine.currentPositionMs() + a.deltaMs).coerceIn(0L, end))
            }
        }
        TvMusicAction.Lyrics, TvMusicAction.Queue, TvMusicAction.Hide -> Unit
    }
}

/** The key the page reads, or null for one it leaves alone. */
private fun tvKeyOf(k: Key): TvMusicKey? = when (k) {
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> TvMusicKey.OK
    Key.MediaPlayPause -> TvMusicKey.PLAY_PAUSE
    Key.MediaPlay -> TvMusicKey.PLAY
    Key.MediaPause -> TvMusicKey.PAUSE
    Key.MediaStop -> TvMusicKey.STOP
    Key.DirectionLeft, Key.MediaPrevious, Key.MediaRewind -> TvMusicKey.LEFT
    Key.DirectionRight, Key.MediaNext, Key.MediaFastForward -> TvMusicKey.RIGHT
    Key.DirectionDown -> TvMusicKey.DOWN
    Key.DirectionUp -> TvMusicKey.UP
    // FR-R380-6 — the page takes Back itself (down and up), so no root handler can read it as "leave the app".
    Key.Back, Key.Escape -> TvMusicKey.BACK
    else -> null
}

@Composable
fun TvNowPlayingScreen(api: TvApiClient, onHide: () -> Unit) {
    val colors = RaviloTheme.colors
    val st by MusicEngine.state.collectAsState()
    val lyricsOn by TvCastChannel.lyricsOn.collectAsState()
    var transportUntil by remember { mutableLongStateOf(0L) }
    var shownAction by remember { mutableStateOf<TvMusicAction?>(null) }
    var queueOpen by remember { mutableStateOf(false) }
    var queueIdx by remember { mutableIntStateOf(0) }
    var heldKey by remember { mutableStateOf<Key?>(null) }
    var heldRepeated by remember { mutableStateOf(false) }
    var lastHoldStep by remember { mutableLongStateOf(0L) }
    val focus = remember { FocusRequester() }
    var now by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(Unit) { while (true) { now = nowMs(); delay(250) } }
    // FR-R380-8 — the queue played out: the last song shows as finished, then the page closes and the session ends.
    LaunchedEffect(st.ended) {
        if (st.ended) { delay(FINISHED_MS); TvCastChannel.endMusic(); runCatching { MusicEngine.clear() }; onHide() }
    }
    // Nothing plays any more (stopped from the phone, or the session ended): the page has nothing to show.
    LaunchedEffect(st.active) { if (!st.active) { delay(500); if (!MusicEngine.state.value.active) onHide() } }

    fun act(a: TvMusicAction) {
        shownAction = a
        transportUntil = nowMs() + TRANSPORT_MS
        when (a) {
            TvMusicAction.Lyrics -> TvCastChannel.setLyrics(!lyricsOn)
            TvMusicAction.Queue -> { queueOpen = true; queueIdx = st.index.coerceAtLeast(0) }
            TvMusicAction.Hide -> onHide()
            else -> perform(a)
        }
    }

    Box(
        Modifier.fillMaxSize().background(playingGround())
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { ev ->
                // FR-R380-5 — the queue panel is its own focus group: ▲ ▼ move, OK jumps, Back and ◀ close it.
                if (queueOpen) {
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent ev.key in QUEUE_KEYS
                    when (ev.key) {
                        Key.DirectionUp -> { queueIdx = (queueIdx - 1).coerceAtLeast(0); true }
                        Key.DirectionDown -> { queueIdx = (queueIdx + 1).coerceAtMost((st.queue.size - 1).coerceAtLeast(0)); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { MusicEngine.playAt(queueIdx); queueOpen = false; true }
                        Key.Back, Key.Escape, Key.DirectionLeft -> { queueOpen = false; true }
                        else -> false
                    }
                } else {
                    val k = ev.key
                    val tk = tvKeyOf(k) ?: return@onPreviewKeyEvent false
                    val holdable = tk == TvMusicKey.LEFT || tk == TvMusicKey.RIGHT
                    when (ev.type) {
                        KeyEventType.KeyDown -> {
                            if (!holdable) { act(tvMusicAction(tk, st.book != null)); return@onPreviewKeyEvent true }
                            if (heldKey == k) {
                                // The remote repeats a held key: the first repeat turns the press into a hold.
                                heldRepeated = true
                                val t = nowMs()
                                if (t - lastHoldStep >= HOLD_STEP_MS) {
                                    lastHoldStep = t
                                    act(tvMusicAction(if (tk == TvMusicKey.LEFT) TvMusicKey.LEFT_HOLD else TvMusicKey.RIGHT_HOLD, st.book != null))
                                }
                            } else { heldKey = k; heldRepeated = false; lastHoldStep = 0L }
                            true
                        }
                        KeyEventType.KeyUp -> {
                            if (heldKey == k) {
                                if (!heldRepeated) act(tvMusicAction(tk, st.book != null))
                                heldKey = null; heldRepeated = false
                            }
                            // Every key the page reads is consumed on the way up too: a stray Back key-up reaching the
                            // activity after the page hid closed the app on Stue TV (R380 live test).
                            true
                        }
                        else -> false
                    }
                }
            },
    ) {
        val t = st.current
        val book = st.book
        if (t == null && book == null) return@Box
        Row(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 72.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicCover(
                book?.detail?.coverUrl ?: t?.imageUrl, book?.detail?.title ?: t?.album ?: t?.title.orEmpty(),
                Modifier.fillMaxHeight(0.78f).aspectRatio(1f).shadow(24.dp, RoundedCornerShape(16.dp)),
                corner = 16.dp, requestedWidth = 900, wordmarkSize = 30,
            )
            Spacer(Modifier.width(64.dp))
            Column(Modifier.weight(1f)) {
                Text(str("tvmusic.now_playing").uppercase(), color = colors.accentSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
                Spacer(Modifier.height(12.dp))
                if (book != null) BookFacts(book, colors.text, colors.textSecondary) else if (t != null) SongFacts(t, colors.text, colors.textSecondary)
                Spacer(Modifier.height(28.dp))
                ProgressLine(book != null, colors.text, colors.textSecondary)
                Spacer(Modifier.height(16.dp))
                val next = st.upNext.firstOrNull()
                if (book == null && next != null) Text(str("tvmusic.next", mapOf("song" to next.title)), color = colors.textSecondary, fontSize = 18.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (st.ended) Text(str("ab.finished"), color = colors.textSecondary, fontSize = 18.sp, fontFamily = Sora)
                if (book == null && t != null && lyricsOn && t.hasLyrics) {
                    Spacer(Modifier.height(28.dp))
                    TvLyrics(api, t.id, colors.text, colors.textDim)
                }
                AnimatedVisibility(visible = now < transportUntil, enter = fadeIn(), exit = fadeOut()) {
                    TransportRow(st.playing, shownAction, lyricsOn, colors.text, colors.textSecondary)
                }
            }
        }
        if (queueOpen) QueuePanel(st, queueIdx, Modifier.align(Alignment.CenterEnd))
    }
}

private val QUEUE_KEYS = setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back, Key.Escape, Key.DirectionLeft)

@Composable
private fun SongFacts(t: dev.jellystructure.shared.tv.MusicTrackItem, ink: Color, quiet: Color) {
    Text(t.title, color = ink, fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(10.dp))
    Text(artistLine(t), color = ink, fontSize = 24.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
    t.album?.let { Text(it, color = quiet, fontSize = 20.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (t.versions.isNotEmpty()) { Spacer(Modifier.height(10.dp)); VersionChips(t.versions, fold = 3, large = true) }
}

@Composable
private fun BookFacts(b: BookPlayback, ink: Color, quiet: Color) {
    val d = b.detail
    Text(d.title, color = ink, fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(10.dp))
    Text(d.authors.joinToString(", ") { it.name }, color = ink, fontSize = 24.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
    val at = MusicEngine.bookPositionMs()
    BookMath.chapter(d, at)?.let { Text(it.title, color = quiet, fontSize = 20.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (b.speed != 1.0) Text("${b.speed}×", color = quiet, fontSize = 18.sp, fontFamily = Sora)
}

@Composable
private fun ProgressLine(book: Boolean, ink: Color, quiet: Color) {
    val st by MusicEngine.state.collectAsState()
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { pos = if (book) MusicEngine.bookPositionMs() else MusicEngine.currentPositionMs(); delay(500) } }
    val length = st.book?.let { BookMath.length(it.detail) } ?: st.durationMs
    val frac = if (length > 0) (pos.toFloat() / length).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(quiet.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxWidth(frac).height(6.dp).background(ink))
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        Text(clock(pos), color = quiet, fontSize = 16.sp, fontFamily = Sora)
        Spacer(Modifier.weight(1f))
        Text(clock(length), color = quiet, fontSize = 16.sp, fontFamily = Sora)
    }
}

/** FR-R380-3 — synced lyrics only, the line being sung and two either side. */
@Composable
private fun TvLyrics(api: TvApiClient, trackId: String, ink: Color, dim: Color) {
    var lyrics by remember(trackId) { mutableStateOf<TrackLyrics?>(null) }
    LaunchedEffect(trackId) { lyrics = runCatching { api.getLyrics(trackId) }.getOrNull() }
    val synced = lyrics?.synced?.takeIf { it.isNotEmpty() } ?: return
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(trackId) { while (true) { pos = MusicEngine.currentPositionMs(); delay(200) } }
    val current = synced.indexOfLast { it.tMs <= pos + 200 }.coerceAtLeast(0)
    Column {
        for (i in (current - 2)..(current + 2)) {
            val line = synced.getOrNull(i) ?: continue
            Text(line.line.ifBlank { "·" }, color = if (i == current) ink else dim, fontSize = if (i == current) 30.sp else 24.sp,
                fontWeight = if (i == current) FontWeight.Bold else FontWeight.SemiBold, fontFamily = SpaceGrotesk, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

/** FR-R380-3 — ⏮ ⏯ ⏭ · Lyrics · Queue, shown for a few seconds after a key; the one the key did is lit. */
@Composable
private fun TransportRow(playing: Boolean, did: TvMusicAction?, lyricsOn: Boolean, ink: Color, quiet: Color) {
    // Icons, not ⏮ ⏸ ⏭ glyphs: the TV's font drew ⏸ as a colour emoji (Stue TV) and the web has no fallback for them.
    Column(Modifier.padding(top = 28.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable fun cell(lit: Boolean, content: @Composable (Color) -> Unit) {
                Box(Modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).background(if (lit) ink else ink.copy(alpha = 0.10f))
                    .border(1.dp, ink.copy(alpha = 0.18f), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center) { content(if (lit) Color.Black else ink) }
            }
            @Composable fun label(text: String, tint: Color) =
                Text(text, color = tint, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, softWrap = false)
            cell(did == TvMusicAction.Previous || (did is TvMusicAction.Seek && did.deltaMs < 0) || (did is TvMusicAction.SkipBook && did.deltaMs < 0)) {
                DeskIcon(DeskIcon.PREVIOUS, it, 20.dp)
            }
            cell(did == TvMusicAction.Toggle || did == TvMusicAction.Play || did == TvMusicAction.Pause) {
                DeskIcon(if (playing) DeskIcon.PAUSE else DeskIcon.PLAY, it, 20.dp)
            }
            cell(did == TvMusicAction.Next || (did is TvMusicAction.Seek && did.deltaMs > 0) || (did is TvMusicAction.SkipBook && did.deltaMs > 0)) {
                DeskIcon(DeskIcon.NEXT, it, 20.dp)
            }
            cell(did == TvMusicAction.Lyrics) { label(str(if (lyricsOn) "tvmusic.lyrics_on" else "tvmusic.lyrics_off"), it) }
            cell(did == TvMusicAction.Queue) { label(str("tvmusic.queue"), it) }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            TriangleGlyph(GlyphDirection.UP, quiet, 14.dp)
            Text(str("tvmusic.queue"), color = quiet, fontSize = 14.sp, fontFamily = Sora, modifier = Modifier.padding(start = 4.dp, end = 16.dp))
            TriangleGlyph(GlyphDirection.DOWN, quiet, 14.dp)
            Text(str(lyricsHintKey(lyricsOn)), color = quiet, fontSize = 14.sp, fontFamily = Sora, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** FR-R380-5 — the queue beside the page: the playing song marked; ▲ ▼ move, OK jumps there. No editing on a TV. */
@Composable
private fun QueuePanel(st: MusicPlayerState, selected: Int, modifier: Modifier) {
    val colors = RaviloTheme.colors
    val list = rememberLazyListState()
    LaunchedEffect(selected) { runCatching { list.animateScrollToItem((selected - 3).coerceAtLeast(0)) } }
    Column(modifier.fillMaxHeight().width(560.dp).background(colors.surface.copy(alpha = 0.96f)).padding(28.dp)) {
        Text(str("tvmusic.queue"), color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
        if (st.upNext.isNotEmpty()) {
            val (key, args) = upNextHeading(st.context)
            Text(str(key, args), color = colors.textSecondary, fontSize = 16.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(st.queue) { i, t ->
                val now = i == st.index
                val focused = i == selected
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (focused) colors.text else Color.Transparent)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MusicCover(t.imageUrl, t.album ?: t.title, Modifier.size(44.dp), corner = 6.dp, requestedWidth = 120, wordmarkSize = 7)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = if (focused) colors.background else if (now) colors.accentSecondary else colors.text, fontSize = 18.sp,
                            fontWeight = if (now) FontWeight.Bold else FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(artistLine(t), color = if (focused) colors.background.copy(alpha = 0.7f) else colors.textSecondary, fontSize = 14.sp, fontFamily = Sora,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}" else "$m:${sec.toString().padStart(2, '0')}"
}

private fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

/** FR-R380-6 — the TV app bar's pill: the cover, the title and ⏯, while the TV plays music. OK reopens Now playing. */
@Composable
fun TvMusicPill(focused: Boolean, modifier: Modifier = Modifier) {
    val st by MusicEngine.state.collectAsState()
    val colors = RaviloTheme.colors
    val t = st.current
    val title = st.book?.detail?.title ?: t?.title ?: return
    Row(
        modifier.clip(RoundedCornerShape(20.dp)).background(if (focused) colors.text else colors.text.copy(alpha = 0.10f))
            .padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusicCover(st.book?.detail?.coverUrl ?: t?.imageUrl, t?.album ?: title, Modifier.size(28.dp), corner = 14.dp, requestedWidth = 80, wordmarkSize = 6)
        Spacer(Modifier.width(8.dp))
        Text(title, color = if (focused) colors.background else colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp))
        Spacer(Modifier.width(6.dp))
        dev.jellystructure.ravilo.ui.components.PlayPauseGlyph(playing = st.playing, tint = if (focused) colors.background else colors.text, sizeDp = 12)
    }
}
