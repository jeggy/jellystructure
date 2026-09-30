package dev.jellystructure.ravilo.ui.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.blur
import dev.jellystructure.ravilo.ui.components.deskHover
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalPortrait
import dev.jellystructure.ravilo.ui.components.MusicGlyph
import dev.jellystructure.ravilo.ui.components.MusicIcon
import dev.jellystructure.ravilo.ui.components.PlayingBars
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.screens.HandsetSheet
import dev.jellystructure.ravilo.ui.seams.reportTextFieldFocus
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.shared.tv.AudiobookBookmarkItem
import dev.jellystructure.shared.tv.AudiobookDetail
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * R323 (FR-R323-4..7) — the book variant of Now playing and of the mini bar. The same screen and gestures as R322,
 * with a chapter's seek bar over a hairline for the whole book, −30 s / +30 s (long-press repeats), a speed chip and
 * a sleep timer where shuffle and repeat were, and chapters · bookmark · ⋯ along the bottom. No cover swipe, no
 * lyrics, no queue. Speed exists here and nowhere else in Ravilo.
 */

val BOOK_SPEEDS = listOf(0.8, 0.9, 1.0, 1.1, 1.2, 1.5, 1.75, 2.0)
val SLEEP_MINUTES = listOf(15, 30, 45, 60)

/** `1.0`, `1.25`, `1.75` — the speed as the chip says it. */
fun speedText(s: Double): String {
    val hundredths = (s * 100).roundToInt()
    return if (hundredths % 10 == 0) "${hundredths / 100}.${(hundredths / 10) % 10}" else "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}

/** Book time, sampled while a screen shows it. */
@Composable
fun rememberBookPosition(): Long {
    var pos by remember { mutableLongStateOf(MusicEngine.bookPositionMs()) }
    LaunchedEffect(Unit) { while (true) { pos = MusicEngine.bookPositionMs(); delay(250) } }
    return pos
}

@Composable
fun BookPlayingScreen(
    api: TvApiClient,
    store: AudiobookStore?,
    onClose: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    val colors = RaviloTheme.colors
    val st by MusicEngine.state.collectAsState()
    val b = st.book ?: return
    val d = b.detail
    val portrait = LocalPortrait.current
    val bookPos = rememberBookPosition()
    val ci = BookMath.chapterAt(d, bookPos)
    val chapter = d.chapters.getOrNull(ci)
    var sheet by remember { mutableStateOf<String?>(null) }   // speed · sleep · chapters · bookmarks · add · menu
    val desk = dev.jellystructure.ravilo.ui.theme.isDesktopLayout
    Box(Modifier.fillMaxSize().then(if (desk) Modifier.background(colors.background) else Modifier.background(playingGround()))) {
        // R337 (FR-R337-7) — a computer's window: the Playing page's shape (`.np`), with the chapters where a song's lyrics are.
        if (desk) DeskBookPlaying(api, store, st, b, bookPos, ci, onOpenBook, onOpenAuthor) { sheet = it }
        val content: @Composable () -> Unit = {
            Column {
                Text(chapter?.let { chapterTitle(it.title, ci) } ?: d.title, color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.title, color = colors.textSecondary, fontSize = 14.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).tap { onOpenBook(d.id) })
                    d.authors.firstOrNull()?.let { a ->
                        Text(" · ", color = colors.textDim, fontSize = 14.5.sp, fontFamily = Sora)
                        Text(a.name, color = colors.accentSecondary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).tap { onOpenAuthor(a.id) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                ChapterSeekBar(d, ci, bookPos)
                // R326 (FR-R326-9) — a small scrubber for the whole book (the hairline it replaces), then *… left*.
                val length = BookMath.length(d).coerceAtLeast(1L)
                WholeBookBar(d, bookPos)
                Text(str("ab.left", mapOf("t" to fmtTotal((length - bookPos).coerceAtLeast(0L)))), color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora)
                if (b.finished) FinishedRow(api, store, d) else BookTransport(st, b, onSpeed = { sheet = "speed" }, onSleep = { sheet = "sleep" })
                Spacer(Modifier.height(6.dp))
                ChapterStrip(d, ci)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).tap { sheet = "chapters" }, contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.CHAPTERS, colors.textSecondary, 22.dp, description = str("ab.chapters")) }
                    Box(Modifier.size(46.dp).tap { sheet = "add" }, contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.BOOKMARK, colors.textSecondary, 21.dp, description = str("ab.bookmark_add")) }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(46.dp).tap { sheet = "menu" }, contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.MORE, colors.textSecondary, 22.dp, description = str("music.more")) }
                }
            }
        }
        if (desk) Unit else if (!portrait) {
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                MusicCover(d.coverUrl, d.title, Modifier.fillMaxHeight(0.86f).aspectRatio(1f).shadow(18.dp, RoundedCornerShape(14.dp)), corner = 14.dp, requestedWidth = 720, wordmarkSize = 24)
                Spacer(Modifier.width(28.dp))
                Box(Modifier.weight(1f)) { content() }
            }
            Box(Modifier.padding(10.dp).size(42.dp).clip(CircleShape).background(Color.Black.copy(0.35f)).tap(onClose), contentAlignment = Alignment.Center) {
                MusicGlyph(MusicIcon.CHEVRON_DOWN, Color.White, 20.dp, description = str("music.close"))
            }
        } else Column(Modifier.fillMaxSize().padding(horizontal = raviloHPad).padding(top = 14.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.tap { onOpenBook(d.id) }) {
                MusicGlyph(MusicIcon.NOTE, colors.accentSecondary, 15.dp)
                Spacer(Modifier.width(8.dp))
                Text(d.title, color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                MusicCover(d.coverUrl, d.title, Modifier.fillMaxWidth(0.8f).aspectRatio(1f).shadow(22.dp, RoundedCornerShape(16.dp)), corner = 16.dp, requestedWidth = 900, wordmarkSize = 30)
            }
            Spacer(Modifier.height(18.dp))
            content()
        }
        BookFailureSheet(st.failed)
        SpeedSheet(sheet == "speed", b.speed) { sheet = null }
        SleepSheet(sheet == "sleep", b.sleep) { sheet = null }
        ChaptersSheet(sheet == "chapters" || sheet == "bookmarks", api, d, bookPos, startOnBookmarks = sheet == "bookmarks") { sheet = null }
        AddBookmarkSheet(sheet == "add", api, d, bookPos) { sheet = null }
        if (store != null) BookMenuSheet(sheet == "menu", d, started = true, finished = b.finished, api, store, onDismiss = { sheet = null }, onOpenAuthor = onOpenAuthor)
    }
}

/**
 * R337 (FR-R337-7) — a book in a computer's window. The Playing page's shape (`.np` in `design/ravilo/desktop-directions.css`):
 * the cover with the chapter's name, the book and its author, the chapter's seek line, the whole book's scrubber and
 * the transport under it; and beside it, where a song has its lyrics, the book's chapters — the one being read marked,
 * a click reads from there. Between 600 and 839 dp the chapters sit under the cover. A book of one chapter keeps the
 * cover's column alone, centred. Speed, the sleep timer, bookmarks and ⋯ open the phone's sheets as dialogs.
 */
@Composable
private fun DeskBookPlaying(
    api: TvApiClient,
    store: AudiobookStore?,
    st: MusicPlayerState,
    b: BookPlayback,
    bookPos: Long,
    ci: Int,
    onOpenBook: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
    onSheet: (String) -> Unit,
) {
    val colors = RaviloTheme.colors
    val d = b.detail
    val chapter = d.chapters.getOrNull(ci)
    val width = dev.jellystructure.ravilo.ui.theme.LocalWindowWidth.current
    val stacked = width < dev.jellystructure.ravilo.ui.theme.WindowWidths.EXPANDED
    val coverSize = if (width >= dev.jellystructure.ravilo.ui.theme.WindowWidths.LARGE) 380.dp else if (stacked) 220.dp else 280.dp
    val graphite = dev.jellystructure.ravilo.ui.theme.LocalRaviloTheme.current == dev.jellystructure.ravilo.ui.theme.ThemeId.GRAPHITE
    // `.np .bl` — the cover as the page's ground.
    d.coverUrl?.let {
        dev.jellystructure.ravilo.ui.seams.RemoteImage(it, null,
            Modifier.fillMaxSize().blur(70.dp).alpha(if (colors.isLight) 0.18f else 0.35f), requestedWidth = 240)
    }
    @Composable
    fun head(modifier: Modifier) {
        Column(modifier) {
            MusicCover(d.coverUrl, d.title, Modifier.size(coverSize).shadow(30.dp, RoundedCornerShape(16.dp)), corner = 16.dp, requestedWidth = 900, wordmarkSize = 26)
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chapter?.let { chapterTitle(it.title, ci) } ?: d.title, color = colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk,
                    letterSpacing = (-0.5).sp, maxLines = 1, modifier = Modifier.weight(1f).basicMarquee())
                Box(Modifier.size(32.dp).tap { onSheet("add") }, contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.BOOKMARK, colors.textSecondary, 19.dp, description = str("ab.bookmark_add")) }
                Box(Modifier.size(32.dp).tap { onSheet("menu") }, contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.MORE, colors.textSecondary, 20.dp, description = str("music.more")) }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(d.title, color = colors.textSecondary, fontSize = 15.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).tap { onOpenBook(d.id) })
                d.authors.firstOrNull()?.let { a ->
                    Text("·", color = colors.textSecondary, fontSize = 15.sp, fontFamily = Sora)
                    Text(a.name, color = colors.textSecondary, fontSize = 15.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).tap { onOpenAuthor(a.id) })
                }
            }
            Spacer(Modifier.height(18.dp))
            ChapterSeekBar(d, ci, bookPos, desk = true)
            Spacer(Modifier.height(6.dp))
            WholeBookBar(d, bookPos)
            Text(str("ab.left", mapOf("t" to fmtTotal((BookMath.length(d) - bookPos).coerceAtLeast(0L)))), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
            Spacer(Modifier.height(6.dp))
            if (b.finished) FinishedRow(api, store, d)
            // `.np .tp` — the speed · −30 s · play/pause (58 dp, ink) · +30 s · the sleep timer, centred.
            else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                Box(Modifier.size(width = 52.dp, height = 36.dp).tap { onSheet("speed") }, contentAlignment = Alignment.Center) {
                    Text("${speedText(b.speed)}×", color = if (b.speed != 1.0) colors.accentSecondary else colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
                        modifier = Modifier.border(1.dp, colors.fg.copy(alpha = 0.22f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                }
                RepeatSkip(MusicIcon.BACK30, str("ab.skip_back"), small = true) { MusicEngine.skipBy(-30_000L) }
                Box(Modifier.size(58.dp).clip(CircleShape).background(if (graphite) colors.accent else colors.text).tap { MusicEngine.togglePlay() }, contentAlignment = Alignment.Center) {
                    val ink = if (graphite) Color.White else colors.background
                    if (st.buffering) Pulse(ink) else dev.jellystructure.ravilo.ui.components.DeskIcon(
                        if (st.playing) dev.jellystructure.ravilo.ui.components.DeskIcon.PAUSE else dev.jellystructure.ravilo.ui.components.DeskIcon.PLAY, ink, 22.dp)
                }
                RepeatSkip(MusicIcon.FWD30, str("ab.skip_fwd"), small = true) { MusicEngine.skipBy(30_000L) }
                Box(Modifier.size(width = 52.dp, height = 36.dp).tap { onSheet("sleep") }, contentAlignment = Alignment.Center) {
                    val sleep = b.sleep
                    MusicGlyph(MusicIcon.SLEEP, if (sleep != null) colors.accentSecondary else colors.textSecondary, 20.dp, description = str("ab.sleep"))
                    if (sleep?.endsAtMs != null) {
                        var now by remember { mutableLongStateOf(bookNowMs()) }
                        LaunchedEffect(sleep) { while (true) { now = bookNowMs(); delay(1000) } }
                        val mins = ((sleep.endsAtMs - now + 59_999) / 60_000).coerceAtLeast(0)
                        Text(mins.toString(), color = colors.accentSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, modifier = Modifier.align(Alignment.BottomEnd))
                    }
                }
            }
        }
    }
    @Composable
    fun chapters(modifier: Modifier) {
        val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (ci - 3).coerceAtLeast(0))
        Column(modifier) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(str("ab.chapters"), color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, modifier = Modifier.weight(1f))
                Text(str("ab.bookmarks"), color = colors.accentSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, modifier = Modifier.tap { onSheet("bookmarks") }.padding(vertical = 4.dp))
            }
            LazyColumn(state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                itemsIndexed(d.chapters) { i, c ->
                    val now = i == ci
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp))
                            .then(if (now) Modifier.background(colors.fg.copy(alpha = 0.08f)) else Modifier)
                            .deskHover().tap { MusicEngine.seekBook(c.startMs); MusicEngine.play() }.padding(horizontal = 10.dp),
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
    val many = d.chapters.size > 1
    val pad = if (stacked) 28.dp else 48.dp
    if (stacked) Column(Modifier.fillMaxSize().padding(top = 60.dp, start = pad, end = pad)) {
        head(Modifier.width(coverSize).align(Alignment.CenterHorizontally))
        if (many) chapters(Modifier.weight(1f).fillMaxWidth().padding(top = 14.dp))
    } else Row(Modifier.fillMaxSize().padding(top = 70.dp, start = pad, end = pad), horizontalArrangement = if (many) Arrangement.spacedBy(48.dp) else Arrangement.Center) {
        head(Modifier.width(coverSize).verticalScroll(rememberScrollState()))
        if (many) chapters(Modifier.weight(1f).fillMaxHeight())
    }
    dev.jellystructure.ravilo.ui.components.AppBar()
}

/** R326 (FR-R326-9) — the whole book as a small labelled scrubber: *Book* · a thin bar with a knob · elapsed / total.
 *  Tapped or dragged, it seeks in book time (`MusicEngine.seekBook`). */
@Composable
private fun WholeBookBar(d: AudiobookDetail, bookPos: Long) {
    val colors = RaviloTheme.colors
    val length = BookMath.length(d).coerceAtLeast(1L)
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val frac = dragFrac ?: (bookPos.toFloat() / length).coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(str("ab.whole_book").uppercase(), color = colors.textDim, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 1.sp)
        Spacer(Modifier.width(10.dp))
        BoxWithConstraints(Modifier.weight(1f).height(24.dp)) {
            val wPx = with(LocalDensity.current) { maxWidth.toPx() }
            val knobX = maxWidth * frac
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(length) { detectTapGestures { o -> MusicEngine.seekBook(((o.x / wPx).coerceIn(0f, 1f) * length).toLong()) } }
                    .pointerInput(length) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> dragFrac = (o.x / wPx).coerceIn(0f, 1f) },
                            onDragEnd = { dragFrac?.let { f -> MusicEngine.seekBook((f * length).toLong()) }; dragFrac = null },
                            onDragCancel = { dragFrac = null },
                        ) { change, _ -> dragFrac = (change.position.x / wPx).coerceIn(0f, 1f) }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(colors.textDim.copy(0.25f))) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(colors.accentGradient))
                }
                Box(Modifier.offset(x = (knobX - 5.dp).coerceAtLeast(0.dp)).size(10.dp).clip(CircleShape).background(colors.text))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text("${fmtTotal((frac * length).toLong())} / ${fmtTotal(length)}", color = colors.textDim, fontSize = 11.sp, fontFamily = Sora)
    }
}

/** R326 (FR-R326-9) — the chapters as proportional segments under the transport: the current one lit, the finished
 *  ones quieter; a tap jumps to that chapter's start. Absent on a one-chapter book. */
@Composable
private fun ChapterStrip(d: AudiobookDetail, ci: Int) {
    if (d.chapters.size < 2) return
    val colors = RaviloTheme.colors
    val length = BookMath.length(d).coerceAtLeast(1L)
    Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        d.chapters.forEachIndexed { i, c ->
            val w = ((BookMath.chapterEnd(d, i) - c.startMs).coerceAtLeast(1L).toFloat() / length).coerceAtLeast(0.012f)
            Box(
                Modifier.weight(w).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                    .background(when { i == ci -> colors.accentSecondary; i < ci -> colors.textDim.copy(0.5f); else -> colors.textDim.copy(0.22f) })
                    .tap { MusicEngine.seekBook(c.startMs) },
            )
        }
    }
}

/** FR-R323-4 — the chapter's seek bar (*12:40 / 29:05*), dragged or tapped, in book time underneath. */
@Composable
private fun ChapterSeekBar(d: AudiobookDetail, ci: Int, bookPos: Long, desk: Boolean = false) {
    val colors = RaviloTheme.colors
    val start = d.chapters.getOrNull(ci)?.startMs ?: 0L
    val end = if (ci >= 0) BookMath.chapterEnd(d, ci) else BookMath.length(d)
    val len = (end - start).coerceAtLeast(1L)
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val frac = dragFrac ?: ((bookPos - start).toFloat() / len).coerceIn(0f, 1f)
    val gradient = colors.accentGradient
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().height(if (desk) 17.dp else 34.dp)) {
            val wPx = with(LocalDensity.current) { maxWidth.toPx() }
            Canvas(Modifier.fillMaxSize().pointerInput(ci, len) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFrac = (o.x / wPx).coerceIn(0f, 1f) },
                    onDragEnd = { dragFrac?.let { MusicEngine.seekBook(start + (it * len).toLong()) }; dragFrac = null },
                    onDragCancel = { dragFrac = null },
                    onHorizontalDrag = { ch, _ -> dragFrac = (ch.position.x / wPx).coerceIn(0f, 1f) },
                )
            }.pointerInput(ci, len) { detectTapGestures { o -> MusicEngine.seekBook(start + ((o.x / wPx).coerceIn(0f, 1f) * len).toLong()) } }) {
                val y = size.height / 2
                if (desk) {
                    // `.np .seek` — a 5 dp line in ink; the knob only while it is dragged.
                    val h = 5.dp.toPx()
                    drawRoundRect(colors.fg.copy(alpha = 0.18f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2, h / 2))
                    drawRoundRect(colors.text, Offset(0f, y - h / 2), Size(size.width * frac, h), CornerRadius(h / 2, h / 2))
                    if (dragFrac != null) drawCircle(colors.text, 7.dp.toPx(), Offset(size.width * frac, y))
                    return@Canvas
                }
                val h = 4.dp.toPx()
                drawRoundRect(colors.textDim.copy(0.35f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2, h / 2))
                drawRoundRect(gradient, Offset(0f, y - h / 2), Size(size.width * frac, h), CornerRadius(h / 2, h / 2))
                drawCircle(colors.fg, (if (dragFrac != null) 9.dp else 6.dp).toPx(), Offset(size.width * frac, y))
            }
        }
        Row {
            Text(fmtLen((frac * len).toLong()), color = if (desk) colors.textDim else colors.textSecondary, fontSize = 12.sp, fontFamily = Sora)
            Spacer(Modifier.weight(1f))
            Text(fmtLen(len), color = if (desk) colors.textDim else colors.textSecondary, fontSize = 12.sp, fontFamily = Sora)
        }
    }
}

/** FR-R323-4 — speed chip · −30 s · play/pause · +30 s · sleep. A long press on a skip repeats it every ~0.4 s. */
@Composable
private fun BookTransport(st: MusicPlayerState, b: BookPlayback, onSpeed: () -> Unit, onSleep: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.size(52.dp).tap(onSpeed), contentAlignment = Alignment.Center) {
            Text("${speedText(b.speed)}×", color = if (b.speed != 1.0) colors.accentSecondary else colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Sora,
                modifier = Modifier.border(1.dp, colors.textDim.copy(0.5f), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        RepeatSkip(MusicIcon.BACK30, str("ab.skip_back")) { MusicEngine.skipBy(-30_000L) }
        Box(Modifier.size(64.dp).clip(CircleShape).background(colors.accentGradient).tap { MusicEngine.togglePlay() }, contentAlignment = Alignment.Center) {
            if (st.buffering) Pulse(colors.onAccent) else MusicGlyph(if (st.playing) MusicIcon.PAUSE else MusicIcon.PLAY, colors.onAccent, 28.dp)
        }
        RepeatSkip(MusicIcon.FWD30, str("ab.skip_fwd")) { MusicEngine.skipBy(30_000L) }
        Box(Modifier.size(52.dp).tap(onSleep), contentAlignment = Alignment.Center) {
            val sleep = b.sleep
            MusicGlyph(MusicIcon.SLEEP, if (sleep != null) colors.accentSecondary else colors.textSecondary, 22.dp, description = str("ab.sleep"))
            if (sleep?.endsAtMs != null) {
                var now by remember { mutableLongStateOf(bookNowMs()) }
                LaunchedEffect(sleep) { while (true) { now = bookNowMs(); delay(1000) } }
                val mins = ((sleep.endsAtMs - now + 59_999) / 60_000).coerceAtLeast(0)
                Text(mins.toString(), color = colors.accentSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun RepeatSkip(icon: MusicIcon, description: String, small: Boolean = false, onSkip: () -> Unit) {
    val colors = RaviloTheme.colors
    val scope = rememberCoroutineScope()
    Box(
        Modifier.size(if (small) 40.dp else 56.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                onSkip()
                val job = scope.launch { delay(500); while (true) { onSkip(); delay(400) } }
                tryAwaitRelease()
                job.cancel()
            })
        },
        contentAlignment = Alignment.Center,
    ) { MusicGlyph(icon, colors.text, if (small) 26.dp else 30.dp, description = description) }
}

/** FR-R323-6 — the last part played through: *Finished* and *Start over* in place of the transport. */
@Composable
private fun FinishedRow(api: TvApiClient, store: AudiobookStore?, d: AudiobookDetail) {
    val colors = RaviloTheme.colors
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(str("ab.finished"), color = colors.accentSecondary, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, modifier = Modifier.weight(1f))
        PillButton(str("ab.start_over"), MusicIcon.PLAY, primary = true) {
            scope.launch { runCatching { api.setAudiobookFinished(d.id, false) }; MusicEngine.playBook(d.copy(position = null), 0, 0L); store?.refresh(d.id) }
        }
    }
}

/** FR-R323-6 — *Couldn't play this · Try again. Your place in the book is kept.* with Try again and Close. */
@Composable
private fun BookFailureSheet(failed: Boolean) {
    // A tap beside the sheet keeps the book (paused where it is); only *Close* clears the player.
    HandsetSheet(visible = failed, onDismiss = {}, dismissible = false) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
            Text(str("music.fail_t"), color = RaviloTheme.colors.fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            Spacer(Modifier.height(6.dp))
            Text(str("ab.fail_p"), color = RaviloTheme.colors.fg.copy(0.7f), fontSize = 14.sp, fontFamily = Sora)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(str("music.try_again"), null, primary = true, Modifier.weight(1f)) { MusicEngine.retry() }
                PillButton(str("ab.close"), null, primary = false, Modifier.weight(1f)) { MusicEngine.clear() }
            }
        }
    }
}

// ── FR-R323-5: the sheets ──

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedSheet(visible: Boolean, current: Double, onDismiss: () -> Unit) {
    val colors = RaviloTheme.colors
    HandsetSheet(visible = visible, onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
            Text(str("ab.speed"), color = RaviloTheme.colors.fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 4) {
                BOOK_SPEEDS.forEach { s ->
                    val on = kotlin.math.abs(s - current) < 0.001
                    Box(
                        Modifier.weight(1f).heightIn(min = 46.dp).then(if (on) Modifier.background(colors.accentGradient, RoundedCornerShape(12.dp)) else Modifier.background(RaviloTheme.colors.fg.copy(0.08f), RoundedCornerShape(12.dp)))
                            .tap { MusicEngine.setSpeed(s); onDismiss() },
                        contentAlignment = Alignment.Center,
                    ) { Text("${speedText(s)}×", color = if (on) colors.onAccent else RaviloTheme.colors.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(str("ab.speed_note"), color = RaviloTheme.colors.fg.copy(0.6f), fontSize = 13.sp, fontFamily = Sora)
        }
    }
}

@Composable
private fun SleepSheet(visible: Boolean, current: SleepTimer?, onDismiss: () -> Unit) {
    HandsetSheet(visible = visible, onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp)) {
            Text(str("ab.sleep"), color = RaviloTheme.colors.fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            Spacer(Modifier.height(6.dp))
            SLEEP_MINUTES.forEach { m ->
                BookSheetRow(str("ab.sleep_min", mapOf("n" to m.toString())), MusicIcon.SLEEP, lit = current?.minutes == m && current.endsAtMs != null) {
                    MusicEngine.setSleep(SleepTimer(endsAtMs = bookNowMs() + m * 60_000L, minutes = m)); onDismiss()
                }
            }
            BookSheetRow(str("ab.sleep_end_chapter"), MusicIcon.CHAPTERS, lit = current?.endOfChapter == true) { MusicEngine.setSleep(SleepTimer(endOfChapter = true)); onDismiss() }
            if (current != null) BookSheetRow(str("ab.off"), MusicIcon.PAUSE) { MusicEngine.setSleep(null); onDismiss() }
            if (BookPrefs.sleepFade) Text(str("ab.sleep_fade_note"), color = RaviloTheme.colors.fg.copy(0.55f), fontSize = 12.5.sp, fontFamily = Sora, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Chapters / Bookmarks — two tabs; a tap plays from there. */
@Composable
private fun ChaptersSheet(visible: Boolean, api: TvApiClient, d: AudiobookDetail, bookPos: Long, startOnBookmarks: Boolean, onDismiss: () -> Unit) {
    val colors = RaviloTheme.colors
    var tab by remember(visible) { mutableStateOf(if (startOnBookmarks) "bookmarks" else "chapters") }
    var marks by remember(visible) { mutableStateOf<List<AudiobookBookmarkItem>?>(null) }
    LaunchedEffect(visible, tab) { if (visible && tab == "bookmarks") marks = runCatching { api.getAudiobook(d.id).bookmarks }.getOrNull() ?: d.bookmarks }
    HandsetSheet(visible = visible, onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp).heightIn(max = 520.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(str("ab.chapters"), tab == "chapters") { tab = "chapters" }
                Chip(str("ab.bookmarks"), tab == "bookmarks") { tab = "bookmarks" }
            }
            Spacer(Modifier.height(8.dp))
            val cur = BookMath.chapterAt(d, bookPos)
            if (tab == "chapters") LazyColumn {
                itemsIndexed(d.chapters) { i, c ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).tap { MusicEngine.seekBook(c.startMs); MusicEngine.play(); onDismiss() }, verticalAlignment = Alignment.CenterVertically) {
                        if (i == cur) Box(Modifier.width(30.dp)) { PlayingBars(true, colors.accentSecondary, 14.dp) }
                        else Text((i + 1).toString(), color = RaviloTheme.colors.fg.copy(0.45f), fontSize = 13.sp, fontFamily = Sora, modifier = Modifier.width(30.dp))
                        Text(chapterTitle(c.title, i), color = if (i == cur) colors.accentSecondary else if (i < cur) RaviloTheme.colors.fg.copy(0.45f) else RaviloTheme.colors.fg,
                            fontSize = 14.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(fmtLen(c.lengthMs), color = RaviloTheme.colors.fg.copy(0.45f), fontSize = 12.5.sp, fontFamily = Sora)
                    }
                }
            } else {
                val l = marks
                if (l != null && l.isEmpty()) Text(str("ab.no_bookmarks"), color = RaviloTheme.colors.fg.copy(0.6f), fontSize = 14.sp, fontFamily = Sora, modifier = Modifier.padding(vertical = 18.dp))
                else LazyColumn {
                    itemsIndexed(l.orEmpty()) { _, m ->
                        val i = BookMath.chapterAt(d, m.positionMs)
                        val ch = d.chapters.getOrNull(i)
                        val chName = ch?.let { chapterTitle(it.title, i) } ?: d.title
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).tap { MusicEngine.seekBook(m.positionMs); MusicEngine.play(); onDismiss() }, verticalAlignment = Alignment.CenterVertically) {
                            MusicGlyph(MusicIcon.BOOKMARK, colors.accentSecondary, 18.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.note ?: chName, color = RaviloTheme.colors.fg, fontSize = 14.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$chName · ${fmtLen(m.positionMs - (ch?.startMs ?: 0L))}", color = RaviloTheme.colors.fg.copy(0.55f), fontSize = 12.5.sp, fontFamily = Sora, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** *Add bookmark* — the chapter and offset, a one-line note (the system keyboard), Save. */
@Composable
private fun AddBookmarkSheet(visible: Boolean, api: TvApiClient, d: AudiobookDetail, bookPos: Long, onDismiss: () -> Unit) {
    val colors = RaviloTheme.colors
    val scope = rememberCoroutineScope()
    val at = remember(visible) { bookPos }
    var note by remember(visible) { mutableStateOf("") }
    val added = str("ab.bookmark_added")
    HandsetSheet(visible = visible, onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
            Text(str("ab.bookmark_add"), color = RaviloTheme.colors.fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            val i = BookMath.chapterAt(d, at)
            val ch = d.chapters.getOrNull(i)
            Text("${ch?.let { chapterTitle(it.title, i) } ?: d.title} · ${fmtLen(at - (ch?.startMs ?: 0L))}", color = RaviloTheme.colors.fg.copy(0.6f), fontSize = 13.5.sp, fontFamily = Sora)
            Spacer(Modifier.height(12.dp))
            Text(str("ab.note"), color = RaviloTheme.colors.fg.copy(0.6f), fontSize = 12.5.sp, fontFamily = Sora)
            Spacer(Modifier.height(4.dp))
            val save = {
                scope.launch {
                    if (runCatching { api.addAudiobookBookmark(d.id, at, note.trim().ifBlank { null }) }.isSuccess) MusicToasts.show(added)
                    onDismiss()
                }
                Unit
            }
            Box(Modifier.fillMaxWidth().height(48.dp).background(RaviloTheme.colors.fg.copy(0.08f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = note, onValueChange = { note = it.take(200) },
                    modifier = Modifier.fillMaxWidth().reportTextFieldFocus(),
                    textStyle = TextStyle(color = RaviloTheme.colors.fg, fontSize = 15.sp, fontFamily = Sora),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    cursorBrush = SolidColor(colors.accent),
                    decorationBox = { inner -> if (note.isEmpty()) Text(str("ab.note_ph"), color = RaviloTheme.colors.fg.copy(0.4f), fontSize = 15.sp, fontFamily = Sora); inner() },
                )
            }
            Spacer(Modifier.height(14.dp))
            PillButton(str("ab.save"), MusicIcon.BOOKMARK, primary = true, Modifier.fillMaxWidth()) { save() }
        }
    }
}

// ── FR-R323-7: the mini bar, book variant ──

@Composable
fun BookMiniBar(onOpen: () -> Unit) {
    val colors = RaviloTheme.colors
    val st by MusicEngine.state.collectAsState()
    val b = st.book ?: return
    val d = b.detail
    val bookPos = rememberBookPosition()
    val scope = rememberCoroutineScope()
    val dy = remember { Animatable(0f) }
    val density = LocalDensity.current
    val limit = with(density) { 60.dp.toPx() }
    val ci = BookMath.chapterAt(d, bookPos)
    Column(
        Modifier.fillMaxWidth().height(RaviloDimens.musicMiniBarHeight)
            .offset { IntOffset(0, dy.value.roundToInt()) }.alpha(1f - (dy.value / (limit * 1.6f)).coerceIn(0f, 0.9f))
            .background(colors.surface)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { scope.launch { if (dy.value > limit) { dy.animateTo(limit * 2, tween(140)); MusicEngine.clear(); dy.snapTo(0f) } else dy.animateTo(0f, tween(140)) } },
                    onVerticalDrag = { _, dd -> scope.launch { dy.snapTo((dy.value + dd).coerceAtLeast(0f)) } },
                )
            }
            .tap(onOpen),
    ) {
        // The hairline is the book's progress.
        val frac = (bookPos.toFloat() / BookMath.length(d).coerceAtLeast(1L)).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(2.dp).background(colors.textDim.copy(0.25f))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(colors.accentGradient))
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicCover(d.coverUrl, d.title, Modifier.size(44.dp), corner = 6.dp, requestedWidth = 120, wordmarkSize = 8)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.chapters.getOrNull(ci)?.let { chapterTitle(it.title, ci) } ?: d.title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(d.title, d.authors.joinToString(", ") { it.name }).filter { it.isNotBlank() }.joinToString(" · "), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(46.dp).tap { MusicEngine.skipBy(-30_000L) }, contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.BACK30, colors.text, 24.dp, description = str("ab.skip_back")) }
            Box(Modifier.size(46.dp).tap { MusicEngine.togglePlay() }, contentAlignment = Alignment.Center) {
                if (st.buffering) PlayingBars(true, colors.text, 18.dp) else MusicGlyph(if (st.playing) MusicIcon.PAUSE else MusicIcon.PLAY, colors.text, 22.dp)
            }
        }
    }
}
