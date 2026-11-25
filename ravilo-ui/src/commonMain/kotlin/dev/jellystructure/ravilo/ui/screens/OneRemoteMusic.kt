package dev.jellystructure.ravilo.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.MusicGlyph
import dev.jellystructure.ravilo.ui.components.MusicIcon
import dev.jellystructure.ravilo.ui.components.PlayPauseGlyph
import dev.jellystructure.ravilo.ui.components.PlayingBars
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.music.BonusChip
import dev.jellystructure.ravilo.ui.music.BookMath
import dev.jellystructure.ravilo.ui.music.BookFailureSheet
import dev.jellystructure.ravilo.ui.music.AddBookmarkSheet
import dev.jellystructure.ravilo.ui.music.BookMenuSheet
import dev.jellystructure.ravilo.ui.music.BookTransport
import dev.jellystructure.ravilo.ui.music.ChapterSeekBar
import dev.jellystructure.ravilo.ui.music.ChapterStrip
import dev.jellystructure.ravilo.ui.music.ChaptersSheet
import dev.jellystructure.ravilo.ui.music.FailureSheet
import dev.jellystructure.ravilo.ui.music.FinishedRow
import dev.jellystructure.ravilo.ui.music.LyricsView
import dev.jellystructure.ravilo.ui.music.MusicCover
import dev.jellystructure.ravilo.ui.music.MusicFavorites
import dev.jellystructure.ravilo.ui.music.MusicPlayback
import dev.jellystructure.ravilo.ui.music.SleepSheet
import dev.jellystructure.ravilo.ui.music.SpeedSheet
import dev.jellystructure.ravilo.ui.music.VersionChips
import dev.jellystructure.ravilo.ui.music.WholeBookBar
import dev.jellystructure.ravilo.ui.music.chapterTitle
import dev.jellystructure.ravilo.ui.music.contextLabel
import dev.jellystructure.ravilo.ui.music.fmtLen
import dev.jellystructure.ravilo.ui.music.fmtTotal
import dev.jellystructure.ravilo.ui.music.moreTap
import dev.jellystructure.ravilo.ui.music.playingGround
import dev.jellystructure.ravilo.ui.music.rememberBookPosition
import dev.jellystructure.ravilo.ui.music.songsCount
import dev.jellystructure.ravilo.ui.music.speedText
import dev.jellystructure.ravilo.ui.music.tap
import dev.jellystructure.ravilo.ui.remote.MusicFace
import dev.jellystructure.ravilo.ui.remote.OneRemote
import dev.jellystructure.ravilo.ui.remote.UpNextRow
import dev.jellystructure.ravilo.ui.remote.hereLineKey
import dev.jellystructure.ravilo.ui.remote.offers
import dev.jellystructure.ravilo.ui.remote.viewOnly
import dev.jellystructure.ravilo.ui.sessions.CommandFeedback
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.LocalWindowWidth
import dev.jellystructure.ravilo.ui.theme.WindowWidths
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * DEB-2185 (FR-3, FR-4; revision 3 — `one-remote-a-music*`, `-a-book`, `-mac-music`, `-web-music-1440`) — the one remote of a
 * song or a book: the film remote's shape with the Playing page's functions in it, left-aligned as the frames draw it.
 * Top to bottom: the place pill with ⋯ in the header's right slot, *Playing from {context}*, the square cover (a swipe is
 * next / previous) or the lyrics, the title with ♥, the version chips · artist · album, the state line, seek, shuffle ·
 * previous · ⏯ · next · repeat, the volume block, and the footer *Up next* · *Lyrics* · **Stop**. A book swaps the middle for
 * the chapter line, the chapter's seek bar over the whole book's scrubber, speed · −30 s · ⏯ · +30 s · sleep, the chapter
 * strip, and the footer *Chapters* · *Add bookmark* · **Stop**.
 *
 * What a place cannot do is absent (FR-5.3): a remote reached through the server has the queue's titles and a tap that jumps,
 * and no ♥, versions, lyrics or ⋯, because its session does not carry the track ([MusicFace.local]).
 *
 * [embedded] is the Playing tab: no back to draw, the bottom nav under it, and on a computer the bar under it carries the
 * seek and the transport, so the page does not repeat them (the 2026-10-23 amendment, DEB-2303).
 */
@Composable
internal fun OneRemoteMusicScreen(remote: OneRemote, onBack: () -> Unit, desktop: Boolean, embedded: Boolean) {
    val colors = RaviloTheme.colors
    val m = remote.model
    val face = m.music ?: MusicFace()
    val book = face.book
    val track = face.track
    val extras = remote.extras.music
    val s = m.status
    val width = LocalWindowWidth.current
    val wide = desktop && width >= WindowWidths.MEDIUM
    var moveOpen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }   // upnext · speed · sleep · chapters · bookmarks · add · menu
    var lyricsOn by remember { mutableStateOf(false) }
    var confirmStop by remember(m.sessionId) { mutableStateOf(false) }
    LaunchedEffect(track?.id, face.hasLyrics) { if (!face.hasLyrics) lyricsOn = false }
    fun doStop() { confirmStop = false; remote.actions.stop(); if (m.leavesOnStop) onBack() }
    val showLyrics = lyricsOn && face.hasLyrics && track != null && extras != null
    // On a computer the bar under the Playing page holds the seek and the transport (DEB-2303); a remote opened over the window has no bar.
    val barHolds = wide && embedded

    val ground = if (embedded && !wide) playingGround() else androidx.compose.ui.graphics.Brush.verticalGradient(listOf(colors.background, colors.background))
    BoxWithConstraints(
        Modifier.fillMaxSize().background(ground)
            .then(if (embedded) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)).testTag("one-remote"),
    ) {
        // `.np .bl` — the cover, blurred and faint, is the computer's page ground.
        if (wide) (track?.imageUrl ?: s.artUrl)?.let { dev.jellystructure.ravilo.ui.seams.RemoteImage(it, null, Modifier.fillMaxSize().blur(70.dp).alpha(if (colors.isLight) 0.18f else 0.35f), requestedWidth = 240) }
        val header: @Composable () -> Unit = {
            RemoteHeader(
                remote, onBack, back = !embedded, onMoveOpen = { moveOpen = true },
                trailing = {
                    // ⋯ is the header's right slot (revision 3): a song's own menu, or the book's.
                    if (!m.viewOnly && track != null && extras != null) Box(Modifier.size(46.dp).moreTap { extras.onTrackMore(track) }, contentAlignment = Alignment.Center) {
                        MusicGlyph(MusicIcon.MORE, colors.textSecondary, 22.dp, description = str("music.more"))
                    } else if (!m.viewOnly && book != null) Box(Modifier.size(46.dp).tap { sheet = "menu" }, contentAlignment = Alignment.Center) {
                        MusicGlyph(MusicIcon.MORE, colors.textSecondary, 22.dp, description = str("music.more"))
                    }
                },
            )
        }
        if (wide) MusicRemoteWide(remote, face, header, embedded, barHolds, showLyrics, { lyricsOn = !lyricsOn }, { sheet = it }, confirmStop, { confirmStop = it }, ::doStop)
        else {
            RemoteFitLayout(
                fitHeight = maxHeight - 16.dp,
                artFraction = 0.8f,
                artShape = if (showLyrics) RemoteArtShape.FILL else RemoteArtShape.SQUARE,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                top = {
                    header()
                    Spacer(Modifier.height(10.dp))
                    PlayingFromLine(face, remote)
                    Spacer(Modifier.height(10.dp))
                    if (showLyrics) LyricsHead(s.title, s.kicker, track?.imageUrl ?: s.artUrl)
                },
                art = {
                    if (showLyrics && track != null && extras != null) LyricsView(extras.api, track, Modifier.fillMaxSize())
                    else RemoteSwipeCover(track?.imageUrl ?: s.artUrl, track?.album ?: s.title ?: "", swipe = face.local && !m.viewOnly, onNext = remote.actions::next, onPrevious = remote.actions::previous, wordmark = 30)
                },
                bottom = {
                    if (!showLyrics) { Spacer(Modifier.height(14.dp)); TitleBlock(remote, face) }
                    Spacer(Modifier.height(4.dp))
                    StateLine(remote, desktop)
                    Spacer(Modifier.height(12.dp))
                    SeekAndTransport(remote, face, onSheet = { sheet = it })
                    VolumeBlock(remote)
                    Spacer(Modifier.height(14.dp))
                    StopConfirm(remote, confirmStop, { confirmStop = false }, ::doStop)
                    Footer(remote, face, lyricsOn = showLyrics, onUpNext = { sheet = "upnext" }, onLyrics = { lyricsOn = !lyricsOn }, onSheet = { sheet = it }, onStop = { if (m.confirmStop) confirmStop = true else doStop() })
                    Spacer(Modifier.height(10.dp))
                },
            )
        }
    }

    if (moveOpen) MoveToSheet(remote, onClose = { moveOpen = false })
    UpNextSheet(sheet == "upnext", remote, face) { sheet = null }
    if (book != null && extras != null) BookSheets(sheet, extras, book, onSheet = { sheet = it })
    if (track != null) FailureSheet(s.failed && face.local)
}

// ── the parts ──

/** `Playing from {context}` with its ♪; a book names itself in its place. Absent when the place carries neither. */
@Composable
private fun PlayingFromLine(face: MusicFace, remote: OneRemote) {
    val colors = RaviloTheme.colors
    val text = when {
        face.book != null -> face.book.detail.title
        face.context != null -> if (face.context.kind == "played" || face.context.label.isNotBlank()) str("music.from", mapOf("x" to contextLabel(face.context))) else null
        face.local -> str("music.now_playing")
        else -> null
    } ?: return
    val open = face.book?.let { b -> remote.extras.music?.let { e -> { e.onOpenBook(b.detail.id) } } }
    Row(Modifier.fillMaxWidth().then(if (open != null) Modifier.tap(open) else Modifier).testTag("remote-playing-from"), verticalAlignment = Alignment.CenterVertically) {
        MusicGlyph(MusicIcon.NOTE, colors.accentSecondary, 15.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** FR-R322-7 — while lyrics show, the head shrinks to a small cover and the title. */
@Composable
private fun LyricsHead(title: String?, artist: String?, art: String?) {
    val colors = RaviloTheme.colors
    Row(Modifier.fillMaxWidth().testTag("remote-lyrics-head"), verticalAlignment = Alignment.CenterVertically) {
        MusicCover(art, title ?: "", Modifier.size(52.dp), corner = 8.dp, requestedWidth = 140, wordmarkSize = 8)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title.orEmpty(), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist.orEmpty(), color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora, maxLines = 1)
        }
    }
}

/** The cover: a square; on this app's own queue a swipe is next / previous with the cover sliding off (FR-R322-4). */
@Composable
private fun RemoteSwipeCover(url: String?, title: String, swipe: Boolean, onNext: () -> Unit, onPrevious: () -> Unit, wordmark: Int, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val dx = remember(url) { Animatable(0f) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val w = with(density) { maxWidth.toPx() }
        MusicCover(
            url, title,
            Modifier.fillMaxSize().offset { IntOffset(dx.value.roundToInt(), 0) }.alpha(1f - (abs(dx.value) / w).coerceIn(0f, 0.6f))
                .shadow(22.dp, RoundedCornerShape(16.dp)).testTag("remote-cover")
                .then(if (!swipe) Modifier else Modifier.pointerInput(url) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val v = dx.value
                            scope.launch {
                                when {
                                    v < -w * 0.25f -> { dx.animateTo(-w, tween(160)); onNext() }
                                    v > w * 0.25f -> { dx.animateTo(w, tween(160)); onPrevious() }
                                    else -> dx.animateTo(0f, tween(160))
                                }
                            }
                        },
                        onHorizontalDrag = { _, d -> scope.launch { dx.snapTo(dx.value + d) } },
                    )
                }),
            corner = 16.dp, requestedWidth = 900, wordmarkSize = wordmark,
        )
    }
}

/** The title with ♥ level with it, then the version chips · artist · album (R344, R373); a book is its chapter, then book · author. */
@Composable
private fun TitleBlock(remote: OneRemote, face: MusicFace) {
    val colors = RaviloTheme.colors
    val extras = remote.extras.music
    val s = remote.model.status
    val t = face.track
    val book = face.book
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(s.title ?: str("loading"), color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, maxLines = 1,
            modifier = Modifier.weight(1f).basicMarquee().testTag("remote-title"))
        if (t != null && extras != null && !remote.model.viewOnly) {
            val favs by MusicFavorites.overrides.collectAsState()
            val fav = favs[t.id] ?: t.favorite
            Box(Modifier.size(44.dp).tap { extras.onFavorite(t, !fav) }.testTag("remote-heart"), contentAlignment = Alignment.Center) {
                MusicGlyph(if (fav) MusicIcon.HEART_FILLED else MusicIcon.HEART, if (fav) colors.accentSecondary else colors.textSecondary, 24.dp, description = str("nav.my_list"))
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            t != null -> {
                if (t.versions.isNotEmpty()) VersionChips(t.versions, fold = Int.MAX_VALUE, large = true)
                if (t.extra) BonusChip(large = true)
                t.artists.firstOrNull()?.let { a ->
                    Text(t.artists.joinToString(", ") { it.name }, color = colors.accentSecondary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).then(if (extras != null) Modifier.tap { extras.onOpenArtist(a.id) } else Modifier).testTag("remote-artist"))
                }
                t.album?.let { al -> t.albumId?.let { id ->
                    Text("·", color = colors.textDim, fontSize = 14.5.sp, fontFamily = Sora)
                    Text(al, color = colors.textSecondary, fontSize = 14.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).then(if (extras != null) Modifier.tap { extras.onOpenAlbum(id) } else Modifier).testTag("remote-album"))
                } }
            }
            book != null -> {
                Text(book.detail.title, color = colors.textSecondary, fontSize = 14.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).then(if (extras != null) Modifier.tap { extras.onOpenBook(book.detail.id) } else Modifier))
                book.detail.authors.firstOrNull()?.let { a ->
                    Text("·", color = colors.textDim, fontSize = 14.5.sp, fontFamily = Sora)
                    Text(a.name, color = colors.accentSecondary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).then(if (extras != null) Modifier.tap { extras.onOpenBookAuthor(a.id) } else Modifier))
                }
            }
            // A session reached through the server names the artist (a book its chapter) and nothing more.
            else -> s.kicker?.let { Text(it, color = colors.textSecondary, fontSize = 14.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("remote-artist")) }
        }
    }
}

/** *Playing on {place}* — on this device *Playing here* (FR-4) — under an equaliser glyph, with FR-5.4's states in its place. */
@Composable
private fun StateLine(remote: OneRemote, desktop: Boolean) {
    val colors = RaviloTheme.colors
    val m = remote.model
    val here = if (m.here && !m.viewOnly) str(hereLineKey(m.status.playing)) else null
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (m.line == null && !m.unreachable) Box(Modifier.padding(top = 3.dp, end = 8.dp)) { PlayingBars(m.status.playing, colors.accentSecondary, 15.dp) }
        Box(Modifier.weight(1f)) { RemoteStateLines(remote, desktop, busyElapsed = 0, onConverted = {}, lineOverride = here, start = true) }
    }
}

/** Seek, then the transport — a song's or a book's. Absent for someone else's with the switch off, read-only seek aside (FR-8). */
@Composable
private fun SeekAndTransport(remote: OneRemote, face: MusicFace, onSheet: (String) -> Unit) {
    val colors = RaviloTheme.colors
    val m = remote.model
    val s = m.status
    val act = remote.actions
    val book = face.book
    val st by MusicPlayback.state.collectAsState()
    val transportEnabled = remoteTransportEnabled(s, m.unreachable)
    val seekOffered = m.offers("seek") && !m.viewOnly
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPos by remember { mutableLongStateOf(0L) }
    if (book != null && face.local) {
        val bookPos = rememberBookPosition()
        val ci = BookMath.chapterAt(book.detail, bookPos)
        ChapterSeekBar(book.detail, ci, bookPos)
        Spacer(Modifier.height(4.dp))
        WholeBookBar(book.detail, bookPos)
        Text(str("ab.left", mapOf("t" to fmtTotal((BookMath.length(book.detail) - bookPos).coerceAtLeast(0L)))), color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora)
    } else {
        RemoteSeekBar(
            colors = colors, positionMs = if (scrubbing) scrubPos else s.positionMs, durationMs = s.durationMs, enabled = transportEnabled && seekOffered, readOnly = !seekOffered,
            onSeekStart = { ms -> scrubbing = true; scrubPos = ms }, onSeekDrag = { ms -> scrubPos = ms }, onSeekEnd = { scrubbing = false; act.seekTo(scrubPos) },
        )
        val shown = if (scrubbing) scrubPos else s.positionMs
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(fmtLen(shown), color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora, modifier = Modifier.testTag("remote-position"))
            if (m.feedback == CommandFeedback.SPINNER) CircularProgressIndicator(color = colors.textDim, strokeWidth = 2.dp, modifier = Modifier.size(14.dp).testTag("remote-spinner"))
            Text(if (s.durationMs > 0) "−" + fmtLen((s.durationMs - shown).coerceAtLeast(0L)) else "", color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora)
        }
    }
    if (m.viewOnly) return
    Spacer(Modifier.height(10.dp))
    val alpha = if (!transportEnabled) 0.4f else if (m.feedback == CommandFeedback.DIM) 0.5f else 1f
    when {
        book != null && face.local -> {
            val ex = remote.extras.music
            if (book.finished) { if (ex != null) FinishedRow(ex.api, ex.books, book.detail) }
            else BookTransport(st, book, onSpeed = { onSheet("speed") }, onSleep = { onSheet("sleep") })
            Spacer(Modifier.height(6.dp))
            ChapterStrip(book.detail, BookMath.chapterAt(book.detail, rememberBookPosition()))
        }
        m.kind == "audiobook" -> Row(Modifier.fillMaxWidth().alpha(alpha).testTag("remote-transport"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            if (seekOffered) RemoteGlyphButton(MusicIcon.BACK30, colors.text, transportEnabled, "remote-skip-back") { act.seekTo((s.positionMs - 30_000L).coerceAtLeast(0L)) }
            RemotePlayPause(remote, transportEnabled)
            if (seekOffered) RemoteGlyphButton(MusicIcon.FWD30, colors.text, transportEnabled, "remote-skip-fwd") { act.seekTo((s.positionMs + 30_000L).coerceAtMost(s.durationMs.coerceAtLeast(0L))) }
        }
        else -> Row(Modifier.fillMaxWidth().alpha(alpha).testTag("remote-transport"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            if (m.offers("set_shuffle")) RemoteGlyphButton(MusicIcon.SHUFFLE, if (m.shuffle) colors.accentSecondary else colors.textDim, transportEnabled, "remote-shuffle") { act.setShuffle(!m.shuffle) }
            if (m.offers("previous")) RemoteGlyphButton(MusicIcon.PREVIOUS, colors.text, transportEnabled, "remote-prev") { act.previous() }
            RemotePlayPause(remote, transportEnabled)
            // The queue's last song has no next (FR-R322-5): absent where this app holds the queue and it has none.
            if (m.offers("next") && (!face.local || s.hasNext)) RemoteGlyphButton(MusicIcon.NEXT, colors.text, transportEnabled, "remote-next-song") { act.next() }
            if (m.offers("set_repeat")) RemoteGlyphButton(MusicIcon.REPEAT, if (m.repeat != "off") colors.accentSecondary else colors.textDim, transportEnabled, "remote-repeat") {
                act.setRepeat(when (m.repeat) { "all" -> "one"; "one" -> "off"; else -> "all" })
            }
        }
    }
}

@Composable
private fun RemotePlayPause(remote: OneRemote, enabled: Boolean) {
    val colors = RaviloTheme.colors
    val m = remote.model
    val s = m.status
    if (!(m.offers("play") || m.offers("pause"))) return
    Box(
        Modifier.size(72.dp).clip(CircleShape).background(colors.text)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) { if (s.playing) remote.actions.pause() else remote.actions.play() }
            .testTag("remote-play-pause"),
        contentAlignment = Alignment.Center,
    ) {
        if (s.buffering && enabled) CircularProgressIndicator(color = colors.background, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
        else PlayPauseGlyph(playing = s.playing, tint = colors.background, sizeDp = 28)
    }
}

/** FR-10 — the volume block, inline for every kind: one slider, or R371's master with a slider per room. */
@Composable
private fun VolumeBlock(remote: OneRemote) {
    val m = remote.model
    if (m.unreachable || m.viewOnly) return
    remote.linkVolume?.let { vol -> Spacer(Modifier.height(10.dp)); vol(Modifier.fillMaxWidth()) }
    remote.sessionVolume?.let { vol -> Spacer(Modifier.height(10.dp)); vol() }
}

/** FR-7 — stopping someone else's asks first, every time, with one inline confirm. */
@Composable
private fun StopConfirm(remote: OneRemote, open: Boolean, onNo: () -> Unit, onYes: () -> Unit) {
    val colors = RaviloTheme.colors
    val m = remote.model
    if (!open || !m.confirmStop || m.viewOnly) return
    Column(Modifier.fillMaxWidth().background(colors.surfaceVariant, RoundedCornerShape(14.dp)).padding(14.dp).testTag("remote-stop-confirm")) {
        Text(str("session.stop_person", mapOf("person" to m.ownerName.orEmpty(), "title" to (m.status.title ?: m.placeName))), color = colors.text, fontSize = 15.sp, fontFamily = Sora)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            RemotePill(str("cast.stop_room"), primary = true, modifier = Modifier.weight(1f).testTag("remote-stop-yes"), onClick = onYes)
            RemotePill(str("action.cancel"), primary = false, modifier = Modifier.weight(1f).testTag("remote-stop-no"), onClick = onNo)
        }
    }
    Spacer(Modifier.height(10.dp))
}

/** A footer button: the glyph over its label, as `one-remote-a-music.png` draws them. */
@Composable
private fun FooterButton(label: String, tag: String, modifier: Modifier, tint: Color, glyph: @Composable () -> Unit, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    Column(
        modifier.heightIn(min = 62.dp).clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        glyph()
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The footer: *Up next* · *Lyrics* · **Stop** for a song; *Chapters* · *Add bookmark* · **Stop** for a book. */
@Composable
private fun Footer(remote: OneRemote, face: MusicFace, lyricsOn: Boolean, onUpNext: () -> Unit, onLyrics: () -> Unit, onSheet: (String) -> Unit, onStop: () -> Unit, stop: Boolean = true, lyricsButton: Boolean = true) {
    val colors = RaviloTheme.colors
    val m = remote.model
    if (m.viewOnly) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (face.book != null && face.local) {
            FooterButton(str("ab.chapters"), "remote-chapters", Modifier.weight(1f), colors.text, { MusicGlyph(MusicIcon.CHAPTERS, colors.text, 20.dp) }) { onSheet("chapters") }
            FooterButton(str("ab.bookmark_add"), "remote-bookmark", Modifier.weight(1f), colors.text, { MusicGlyph(MusicIcon.BOOKMARK, colors.text, 20.dp) }) { onSheet("add") }
        } else if (face.book == null) {
            if (face.hasQueue) FooterButton(str("music.up_next"), "remote-up-next", Modifier.weight(1f), colors.text, { MusicGlyph(MusicIcon.QUEUE, colors.text, 20.dp) }, onUpNext)
            if (lyricsButton && face.hasLyrics) FooterButton(str("music.lyrics"), "remote-lyrics", Modifier.weight(1f), if (lyricsOn) colors.accentSecondary else colors.text,
                { MusicGlyph(MusicIcon.LYRICS, if (lyricsOn) colors.accentSecondary else colors.text, 20.dp) }, onLyrics)
        }
        if (stop && m.offers("stop")) FooterButton(str("cast.stop_room"), "remote-stop", Modifier.weight(1f), REMOTE_WARN, {
            Canvas(Modifier.size(20.dp)) { drawRoundRect(REMOTE_WARN, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.25f, size.height * 0.25f), size = Size(size.width * 0.5f, size.height * 0.5f), cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(1.8.dp.toPx())) }
        }, onStop)
    }
}

/** The song's *Up next* sheet over the remote (`-a-music-upnext`): the song playing, then what follows; a tap jumps, and on this app's own queue a grip reorders. */
@Composable
private fun UpNextSheet(visible: Boolean, remote: OneRemote, face: MusicFace, onDismiss: () -> Unit) {
    val colors = RaviloTheme.colors
    val now = face.now
    val up = face.upNext
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val rowPx = with(density) { 52.dp.toPx() }
    HandsetSheet(visible = visible && now != null, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 14.dp).testTag("remote-up-next-sheet")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(str("music.up_next"), color = colors.fg, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, modifier = Modifier.weight(1f))
                Box(Modifier.size(40.dp).tap(onDismiss), contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.CLOSE, colors.fg, 20.dp, description = str("music.close")) }
            }
            if (up.isNotEmpty()) {
                val left = up.sumOf { it.durationMs ?: 0L }
                val leftText = if (left > 0) fmtTotal(left) else null
                Text(if (leftText == null) songsCount(up.size) else if (up.size == 1) songsCount(1) + " · " + str("ab.left", mapOf("t" to leftText)) else str("music.queue_left", mapOf("n" to up.size.toString(), "t" to leftText)),
                    color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora)
            }
            Spacer(Modifier.height(10.dp))
            if (now != null) {
                Text(str("music.now_playing").uppercase(), color = colors.textDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 1.2.sp)
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(36.dp)) { PlayingBars(remote.model.status.playing, colors.accentSecondary, 16.dp) }
                    Text(now.title, color = colors.accentSecondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (now.durationMs != null) Text(fmtLen(now.durationMs), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora)
                }
            }
            if (up.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(str("music.up_next").uppercase(), color = colors.textDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 1.2.sp)
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(up, key = { i, r -> "u-$i-${r.id}" }) { i, r ->
                        val lifted = dragging == i
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                .offset { IntOffset(0, if (lifted) dragDy.roundToInt() else 0) }
                                .background(if (lifted) colors.surfaceVariant else Color.Transparent, RoundedCornerShape(10.dp))
                                .tap { onDismiss(); remote.actions.jumpTo(r.index) }.testTag("remote-up-next-${r.index}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The grip reorders on this app's own queue; a place reached through the server does not take it.
                            Box(
                                Modifier.size(36.dp).then(if (!face.local) Modifier else Modifier.pointerInput(r.index) {
                                    detectVerticalDragGestures(
                                        onDragStart = { dragging = i; dragDy = 0f },
                                        onDragEnd = {
                                            val to = (r.index + (dragDy / rowPx).roundToInt()).coerceIn((now?.index ?: -1) + 1, (now?.index ?: -1) + up.size)
                                            if (to != r.index) MusicPlayback.move(r.index, to)
                                            dragging = null; dragDy = 0f
                                        },
                                        onDragCancel = { dragging = null; dragDy = 0f },
                                        onVerticalDrag = { _, d -> dragDy += d },
                                    )
                                }),
                                contentAlignment = Alignment.Center,
                            ) { MusicGlyph(MusicIcon.DRAG, colors.textDim, 20.dp) }
                            Text(r.title, color = colors.fg, fontSize = 16.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                            if (r.durationMs != null) Text(fmtLen(r.durationMs), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora)
                        }
                    }
                }
            }
        }
    }
}

/** The book's sheets: speed, sleep, chapters, bookmarks, add a bookmark and the book's own menu. Absent for a place reached through the server. */
@Composable
private fun BookSheets(sheet: String?, extras: dev.jellystructure.ravilo.ui.remote.MusicExtras, book: dev.jellystructure.ravilo.ui.music.BookPlayback, onSheet: (String?) -> Unit) {
    val bookPos = rememberBookPosition()
    val d = book.detail
    val st by MusicPlayback.state.collectAsState()
    BookFailureSheet(st.failed)
    SpeedSheet(sheet == "speed", book.speed) { onSheet(null) }
    SleepSheet(sheet == "sleep", book.sleep) { onSheet(null) }
    ChaptersSheet(sheet == "chapters" || sheet == "bookmarks", extras.api, d, bookPos, startOnBookmarks = sheet == "bookmarks") { onSheet(null) }
    AddBookmarkSheet(sheet == "add", extras.api, d, bookPos) { onSheet(null) }
    extras.books?.let { store -> BookMenuSheet(sheet == "menu", d, started = true, finished = book.finished, extras.api, store, onDismiss = { onSheet(null) }, onOpenAuthor = extras.onOpenBookAuthor) }
}

// ── a computer's window (`-mac-music`, `-web-music-1440`) ──

/**
 * The wide layout: the cover with its column on the left and, beside it, the lyrics (a book's chapters); under 840 dp the
 * cover sits above them. Embedded, the bar under the page holds the seek line and the transport and Stop (DEB-2303), so
 * the column keeps the place pill, *Playing from*, the title, ♥, ⋯, the chips and the volume block.
 */
@Composable
private fun MusicRemoteWide(
    remote: OneRemote, face: MusicFace, header: @Composable () -> Unit, embedded: Boolean, barHolds: Boolean, showLyrics: Boolean,
    onLyrics: () -> Unit, onSheet: (String) -> Unit, confirmStop: Boolean, setConfirmStop: (Boolean) -> Unit, doStop: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val m = remote.model
    val s = m.status
    val track = face.track
    val extras = remote.extras.music
    val width = LocalWindowWidth.current
    val stacked = width < WindowWidths.EXPANDED
    val coverSize = if (width >= WindowWidths.LARGE) 380.dp else if (stacked) 220.dp else 280.dp
    val pad = if (stacked) 28.dp else 48.dp
    val side: @Composable (Modifier) -> Unit = { mod ->
        when {
            face.book != null && face.local && extras != null -> BookChapters(face.book, mod)
            face.hasLyrics && track != null && extras != null -> LyricsView(extras.api, track, mod, large = true)
        }
    }
    val hasSide = (face.book != null && face.local && face.book.detail.chapters.size > 1) || (face.hasLyrics && track != null && extras != null)
    val column: @Composable (Modifier) -> Unit = { mod ->
        Column(mod, horizontalAlignment = Alignment.Start) {
            header()
            Spacer(Modifier.height(12.dp))
            PlayingFromLine(face, remote)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.size(coverSize).align(Alignment.CenterHorizontally)) {
                RemoteSwipeCover(track?.imageUrl ?: s.artUrl, track?.album ?: s.title ?: "", swipe = face.local && !m.viewOnly, onNext = remote.actions::next, onPrevious = remote.actions::previous, wordmark = 26)
            }
            Spacer(Modifier.height(18.dp))
            TitleBlock(remote, face)
            Spacer(Modifier.height(4.dp))
            StateLine(remote, desktop = true)
            Spacer(Modifier.height(12.dp))
            if (!barHolds) { SeekAndTransport(remote, face, onSheet); Spacer(Modifier.height(8.dp)) }
            else if (face.book != null && face.local) BookOnlyControls(face, onSheet)
            VolumeBlock(remote)
            Spacer(Modifier.height(14.dp))
            if (!barHolds) {
                StopConfirm(remote, confirmStop, { setConfirmStop(false) }, doStop)
                Footer(remote, face, lyricsOn = showLyrics, onUpNext = { onSheet("upnext") }, onLyrics = onLyrics, onSheet = onSheet, onStop = { if (m.confirmStop) setConfirmStop(true) else doStop() }, lyricsButton = false)
            } else Footer(remote, face, lyricsOn = showLyrics, onUpNext = { onSheet("upnext") }, onLyrics = onLyrics, onSheet = onSheet, onStop = {}, stop = false, lyricsButton = false)
            Spacer(Modifier.height(16.dp))
        }
    }
    if (stacked) Column(Modifier.fillMaxSize().padding(top = 24.dp, start = pad, end = pad)) {
        column(Modifier.width(coverSize.coerceAtLeast(320.dp)).align(Alignment.CenterHorizontally).verticalScroll(rememberScrollState()))
        if (hasSide) side(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp))
    } else Row(Modifier.fillMaxSize().padding(top = 24.dp, start = pad, end = pad), horizontalArrangement = if (hasSide) Arrangement.spacedBy(48.dp) else Arrangement.Center) {
        column(Modifier.width(coverSize.coerceAtLeast(360.dp)).verticalScroll(rememberScrollState()))
        if (hasSide) side(Modifier.weight(1f).fillMaxHeight())
    }
}

/** A book on a computer: the bar carries ⏯, ±30 s and the seek, and the page keeps what only a book has — speed and the sleep timer — beside the whole book's scrubber. */
@Composable
private fun BookOnlyControls(face: MusicFace, onSheet: (String) -> Unit) {
    val colors = RaviloTheme.colors
    val book = face.book ?: return
    val bookPos = rememberBookPosition()
    WholeBookBar(book.detail, bookPos)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${speedText(book.speed)}×", color = if (book.speed != 1.0) colors.accentSecondary else colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
            modifier = Modifier.tap { onSheet("speed") }.testTag("remote-speed"))
        Box(Modifier.size(36.dp).tap { onSheet("sleep") }.testTag("remote-sleep"), contentAlignment = Alignment.Center) {
            MusicGlyph(MusicIcon.SLEEP, if (book.sleep != null) colors.accentSecondary else colors.textSecondary, 20.dp, description = str("ab.sleep"))
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** Where a song's lyrics sit, a book's chapters: the one being read marked; a click reads from there. */
@Composable
private fun BookChapters(book: dev.jellystructure.ravilo.ui.music.BookPlayback, modifier: Modifier) {
    val colors = RaviloTheme.colors
    val d = book.detail
    if (d.chapters.size < 2) return
    val bookPos = rememberBookPosition()
    val ci = BookMath.chapterAt(d, bookPos)
    val st by MusicPlayback.state.collectAsState()
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (ci - 3).coerceAtLeast(0))
    Column(modifier) {
        Text(str("ab.chapters"), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, modifier = Modifier.padding(bottom = 8.dp))
        LazyColumn(state = list) {
            itemsIndexed(d.chapters) { i, c ->
                val now = i == ci
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).then(if (now) Modifier.background(colors.fg.copy(alpha = 0.08f)) else Modifier)
                        .tap { MusicPlayback.seekBook(c.startMs); MusicPlayback.play() }.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (now) Box(Modifier.width(30.dp)) { PlayingBars(st.playing, colors.accentSecondary, 14.dp) }
                    else Text((i + 1).toString(), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, modifier = Modifier.width(30.dp))
                    Text(chapterTitle(c.title, i), color = if (now) colors.accentSecondary else if (i < ci) colors.textDim else colors.text, fontSize = 14.sp,
                        fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(fmtLen(c.lengthMs), color = colors.textDim, fontSize = 12.5.sp, fontFamily = Sora)
                }
            }
        }
    }
}
