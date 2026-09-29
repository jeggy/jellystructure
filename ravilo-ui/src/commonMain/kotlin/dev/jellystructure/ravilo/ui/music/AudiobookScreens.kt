package dev.jellystructure.ravilo.ui.music

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.MusicGlyph
import dev.jellystructure.ravilo.ui.components.MusicIcon
import dev.jellystructure.ravilo.ui.components.PlayingBars
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.screens.HandsetSheet
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.shared.tv.AudiobookAuthorDetail
import dev.jellystructure.shared.tv.AudiobookCard
import dev.jellystructure.shared.tv.AudiobookDetail
import dev.jellystructure.shared.tv.AudiobookShelf
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

/*
 * R323 — audiobooks in the listening mode: the shelf (Browse ▸ Audiobooks), *Continue listening* (also on Listen),
 * the book page and the author page. Every time shown is book time from the server's facts (281); the words are
 * the phone's own table. Nothing here names a provider, a format or a file.
 */

/** The shelf, its sort and filter, and the pages it opens — kept across navigation like the music stores. */
class AudiobookStore(private val api: TvApiClient) {
    val sort = kotlinx.coroutines.flow.MutableStateFlow("added")
    /** `books` · `authors` · `series` — the chips under *All books*. */
    val view = kotlinx.coroutines.flow.MutableStateFlow("books")
    private val shelves = HashMap<String, MusicLoader<AudiobookShelf?>>()
    fun shelf(sort: String): MusicLoader<AudiobookShelf?> = shelves.getOrPut(sort) { MusicLoader { api.getAudiobooks(sort) } }
    private val details = HashMap<String, MusicLoader<AudiobookDetail>>()
    fun detail(id: String): MusicLoader<AudiobookDetail> = details.getOrPut(id) { MusicLoader { api.getAudiobook(id) } }
    private val authors = HashMap<String, MusicLoader<AudiobookAuthorDetail>>()
    fun author(id: String): MusicLoader<AudiobookAuthorDetail> = authors.getOrPut(id) { MusicLoader { api.getAudiobookAuthor(id) } }
    /** A place moved (a play, *Start over*, *Mark as finished*): what shows it is read again, quietly. */
    fun refresh(bookId: String? = null) {
        shelves.values.forEach { it.load() }
        bookId?.let { details[it]?.load() }
    }
}

/** Play [id] from where this viewer is (a tap on a *Continue listening* card: no detour through the book page). */
suspend fun resumeBook(api: TvApiClient, id: String, fromStart: Boolean = false) {
    val d = runCatching { api.getAudiobook(id) }.getOrNull() ?: return
    val p = d.position
    when {
        fromStart || p == null -> MusicEngine.playBook(d, 0, 0L)
        p.finished -> { runCatching { api.setAudiobookFinished(id, false) }; MusicEngine.playBook(d.copy(position = null), 0, 0L) }
        else -> MusicEngine.playBook(d, p.part, p.positionMs)
    }
}

// ── pieces ──

/** The progress ring: the heard part of the book, in the accent. */
@Composable
fun BookRing(fraction: Float, size: Dp, modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    val track = colors.textDim.copy(0.3f)
    val lit = colors.accentSecondary
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension * 0.14f
        val inset = w / 2
        val sz = Size(this.size.width - w, this.size.height - w)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), sz, style = Stroke(w))
        drawArc(lit, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), sz, style = Stroke(w, cap = StrokeCap.Round))
    }
}

@Composable
fun booksCount(n: Int): String = if (n == 1) str("ab.books_one") else str("ab.books_n", mapOf("n" to n.toString()))

@Composable
fun chaptersCount(n: Int): String = if (n == 1) str("ab.chapters_one") else str("ab.chapters_n", mapOf("n" to n.toString()))

@Composable
fun chapterTitle(title: String, index: Int): String = title.ifBlank { str("ab.chapter_n", mapOf("n" to (index + 1).toString())) }

private fun authorsLine(c: AudiobookCard) = c.authors.joinToString(", ") { it.name }

/** FR-R323-1.1 — one book in progress: cover · title · author · ring and time left · where · a round play button.
 *  A tap anywhere resumes. */
@Composable
fun ContinueCard(c: AudiobookCard, onResume: () -> Unit) {
    val colors = RaviloTheme.colors
    val st by MusicEngine.state.collectAsState()
    val playingThis = st.book?.id == c.id && st.playing
    Row(
        Modifier.padding(horizontal = raviloHPad, vertical = 5.dp).fillMaxWidth().background(colors.surface, RoundedCornerShape(16.dp)).tap(onResume).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusicCover(c.coverUrl, c.title, Modifier.size(84.dp), corner = 10.dp, requestedWidth = 240, wordmarkSize = 11)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(authorsLine(c), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookRing(((c.positionMs ?: 0L).toFloat() / c.durationMs.coerceAtLeast(1L)), 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(str("ab.left", mapOf("t" to fmtTotal(c.leftMs ?: 0L))), color = colors.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
            }
            if (c.chapter != null && c.part != null) Text(
                "${str("ab.chapter_n", mapOf("n" to c.chapter.toString()))} · ${str("ab.part_of", mapOf("n" to c.part.toString(), "m" to c.parts.toString()))}",
                color = colors.textDim, fontSize = 12.sp, fontFamily = Sora, maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(44.dp).clip(CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
            MusicGlyph(if (playingThis) MusicIcon.PAUSE else MusicIcon.PLAY, colors.onAccent, 18.dp)
        }
    }
}

/** FR-R323-1.2 — a cell: 1:1 cover, title, author; a small ring on a started book, ✓ on a finished one. */
@Composable
fun BookCell(c: AudiobookCard, width: Dp, onOpen: () -> Unit) {
    val colors = RaviloTheme.colors
    Column(Modifier.width(width).tap(onOpen)) {
        Box {
            MusicCover(c.coverUrl, c.title, Modifier.size(width))
            if (c.finished) Box(Modifier.align(Alignment.BottomEnd).padding(7.dp).size(26.dp).clip(CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
                MusicGlyph(MusicIcon.CHECK, colors.onAccent, 15.dp)
            } else if (c.positionMs != null) Box(Modifier.align(Alignment.BottomEnd).padding(7.dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(0.55f)), contentAlignment = Alignment.Center) {
                BookRing(((c.positionMs ?: 0L).toFloat() / c.durationMs.coerceAtLeast(1L)), 20.dp)
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(c.title, color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(authorsLine(c), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── FR-R323-1: the shelf (Browse ▸ Audiobooks) ──

val AUDIOBOOK_SORTS = listOf("added" to "music.sort_added", "title" to "ab.sort_title", "author" to "ab.sort_author", "series" to "ab.sort_series")

internal fun LazyListScope.audiobookShelf(
    store: AudiobookStore,
    sort: String,
    view: String,
    onResume: (String) -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenAuthor: (String) -> Unit,
) {
    val loader = store.shelf(sort)
    item(key = "ab-load-$sort") { LaunchedEffect(loader) { loader.load() } }
    item(key = "ab-shelf-$sort-$view") {
        val state by loader.state.collectAsState()
        val colors = RaviloTheme.colors
        val s = (state as? Load.Ready)?.value
        when {
            state is Load.Loading -> Box(Modifier.fillMaxWidth().height(120.dp))
            s == null || s.books.isEmpty() -> Box(Modifier.fillMaxWidth().height(200.dp)) { EmptyLine(str("ab.empty")) }
            else -> Column {
                if (s.continueListening.isNotEmpty()) {
                    Box(Modifier.padding(horizontal = raviloHPad)) { MusicSectionHeader(str("ab.continue")) }
                    s.continueListening.forEach { c -> ContinueCard(c) { onResume(c.id) } }
                }
                Box(Modifier.padding(horizontal = raviloHPad)) { MusicSectionHeader(str("ab.all_books"), count = booksCount(s.books.size)) }
                // FR-R323-1.3 — Authors / Series only when there is more than one author or any series.
                if (s.authors.isNotEmpty() || s.series.isNotEmpty()) LazyRow(
                    contentPadding = PaddingValues(horizontal = raviloHPad), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    item(key = "all") { Chip(str("ab.all_books"), view == "books") { store.view.value = "books" } }
                    if (s.authors.isNotEmpty()) item(key = "authors") { Chip(str("ab.authors"), view == "authors") { store.view.value = "authors" } }
                    if (s.series.isNotEmpty()) item(key = "series") { Chip(str("ab.series"), view == "series") { store.view.value = "series" } }
                }
                when (view) {
                    "authors" -> Column(Modifier.padding(horizontal = raviloHPad)) {
                        s.authors.forEach { a ->
                            val n = s.books.count { b -> b.authors.any { it.id == a.id } }
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).tap { onOpenAuthor(a.id) }, verticalAlignment = Alignment.CenterVertically) {
                                ArtistCircle(null, a.name, Modifier.size(50.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.name, color = colors.text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, fontFamily = Sora, maxLines = 1)
                                    Text(booksCount(n), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora)
                                }
                            }
                        }
                    }
                    "series" -> Column {
                        s.series.forEach { name ->
                            val inSeries = s.books.filter { it.series == name }.sortedBy { it.seriesPosition?.toDoubleOrNull() ?: 0.0 }
                            Box(Modifier.padding(horizontal = raviloHPad)) { MusicSectionHeader(name, count = booksCount(inSeries.size)) }
                            Grid(inSeries, 2, 12.dp, {}) { c, w -> BookCell(c, w) { onOpenBook(c.id) } }
                        }
                    }
                    else -> Grid(s.books, 2, 12.dp, {}) { c, w -> BookCell(c, w) { onOpenBook(c.id) } }
                }
            }
        }
    }
}

/** FR-R323-2 — *Continue listening* on Listen, above the music rows, while a book is in progress. */
internal fun LazyListScope.continueListeningRow(store: AudiobookStore, onResume: (String) -> Unit) {
    val loader = store.shelf("added")
    item(key = "ab-cont") {
        LaunchedEffect(loader) { loader.load() }
        val state by loader.state.collectAsState()
        val cont = (state as? Load.Ready)?.value?.continueListening.orEmpty()
        if (cont.isNotEmpty()) Column {
            Box(Modifier.padding(horizontal = raviloHPad)) { MusicSectionHeader(str("ab.continue")) }
            cont.forEach { c -> ContinueCard(c) { onResume(c.id) } }
        }
    }
}

// ── FR-R323-3: the book page ──

@Composable
fun AudiobookDetailScreen(
    store: AudiobookStore,
    api: TvApiClient,
    id: String,
    onBack: () -> Unit,
    onOpenAuthor: (String) -> Unit,
    onOpenPlaying: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val loader = remember(id) { store.detail(id) }
    val state by loader.state.collectAsState()
    val st by MusicEngine.state.collectAsState()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var desc by remember { mutableStateOf(false) }
    LaunchedEffect(id) { loader.load() }
    // The place moves while this book plays; read it again when the engine's part or the playing flag changes.
    LaunchedEffect(st.book?.part, st.playing, st.book?.finished) { if (st.book?.id == id) loader.load() }
    Box(Modifier.fillMaxSize().background(playingGround())) {
        val d = (state as? Load.Ready)?.value
        if (d == null) { if (state is Load.Failed) EmptyLine(str("ab.empty")); DetailBack(onBack); return@Box }
        val here = st.book?.takeIf { it.id == id }
        val bookPos = if (here != null) BookMath.bookPosition(here.detail, here.part, rememberLivePosition()) else d.position?.bookPositionMs ?: 0L
        val started = d.position != null && (d.position!!.bookPositionMs > 0 || d.position!!.finished) || here != null
        val finished = here?.finished ?: (d.position?.finished == true)
        val length = BookMath.length(d)
        LazyColumn(contentPadding = PaddingValues(top = 56.dp, bottom = 32.dp)) {
            item(key = "head") {
                Column(Modifier.fillMaxWidth().padding(horizontal = raviloHPad), horizontalAlignment = Alignment.CenterHorizontally) {
                    MusicCover(d.coverUrl, d.title, Modifier.fillMaxWidth(0.62f).aspectRatio(1f), corner = 14.dp, requestedWidth = 720, wordmarkSize = 22)
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = raviloHPad).padding(top = 16.dp)) {
                    Text(d.title, color = colors.text, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
                    d.subtitle?.let { Text(it, color = colors.textSecondary, fontSize = 15.sp, fontFamily = Sora) }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        d.authors.forEach { a -> Text(a.name, color = colors.accentSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, modifier = Modifier.tap { onOpenAuthor(a.id) }) }
                    }
                    if (d.narrators.isNotEmpty()) Text(str("ab.read_by", mapOf("narrator" to d.narrators.joinToString(", "))), color = colors.textSecondary, fontSize = 13.5.sp, fontFamily = Sora)
                    Spacer(Modifier.height(4.dp))
                    Text("${fmtTotal(length)} · ${chaptersCount(d.chapters.size)}", color = colors.textSecondary, fontSize = 13.5.sp, fontFamily = Sora)
                    d.series?.let { s ->
                        Spacer(Modifier.height(8.dp))
                        Text(d.seriesPosition?.let { "$s · ${str("ab.series_book", mapOf("n" to it))}" } ?: s, color = colors.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
                            modifier = Modifier.background(colors.surfaceVariant, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                    if (started && !finished) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BookRing(bookPos.toFloat() / length.coerceAtLeast(1L), 22.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(str("ab.left", mapOf("t" to fmtTotal((length - bookPos).coerceAtLeast(0L)))), color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
                        }
                    } else if (finished) {
                        Spacer(Modifier.height(12.dp))
                        Text(str("ab.finished"), color = colors.accentSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
                    }
                    Spacer(Modifier.height(16.dp))
                    // One primary action: Continue · {where} / Start / Start over, plus ⋯.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val label = when {
                            finished -> str("ab.start_over")
                            started -> str("ab.continue_from", mapOf("t" to fmtTotal(bookPos)))
                            else -> str("ab.start")
                        }
                        PillButton(label, MusicIcon.PLAY, primary = true, Modifier.weight(1f)) {
                            if (here != null && !finished) { MusicEngine.play(); onOpenPlaying() }
                            else scope.launch { resumeBook(api, id, fromStart = finished); store.refresh(id); onOpenPlaying() }
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.size(46.dp).clip(CircleShape).background(colors.surfaceVariant).tap { menu = true }, contentAlignment = Alignment.Center) {
                            MusicGlyph(MusicIcon.MORE, colors.text, 20.dp, description = str("music.more"))
                        }
                    }
                    d.description?.takeIf { it.isNotBlank() }?.let { text ->
                        Spacer(Modifier.height(16.dp))
                        Text(text, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 21.sp, fontFamily = Sora, maxLines = if (desc) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.animateContentSize(tween(340)))   // R326 (FR-R326-1)
                        Text(str(if (desc) "music.less" else "music.more"), color = colors.accentSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, modifier = Modifier.tap { desc = !desc }.padding(vertical = 6.dp))
                    }
                    MusicSectionHeader(str("ab.chapters"), count = d.chapters.size.toString())
                }
            }
            itemsIndexed(d.chapters, key = { i, _ -> "ch$i" }) { i, c ->
                val end = BookMath.chapterEnd(d, i)
                val current = here != null && bookPos >= c.startMs && bookPos < end
                val heard = started && bookPos >= end
                Row(
                    Modifier.padding(horizontal = raviloHPad).fillMaxWidth().heightIn(min = 52.dp).tap {
                        if (here != null) { MusicEngine.seekBook(c.startMs); MusicEngine.play() }
                        else MusicEngine.playBook(d, c.part, c.partOffsetMs)
                        store.refresh(id)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (current) Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterStart) { PlayingBars(st.playing, colors.accentSecondary, 16.dp) }
                    else Text((i + 1).toString(), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, modifier = Modifier.width(30.dp))
                    Text(chapterTitle(c.title, i), color = when { current -> colors.accentSecondary; heard -> colors.textDim; else -> colors.text },
                        fontSize = 14.5.sp, fontWeight = FontWeight.Medium, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(fmtLen(c.lengthMs), color = colors.textDim, fontSize = 12.5.sp, fontFamily = Sora)
                }
            }
        }
        DetailBack(onBack)
        BookMenuSheet(menu, d, started, finished, api, store, onDismiss = { menu = false }, onOpenAuthor = onOpenAuthor)
    }
}

/** ⋯ on a book: Start over (once started) · Mark as finished, or *Mark as not finished* on a finished one (R332) · Go to author. */
@Composable
fun BookMenuSheet(visible: Boolean, d: AudiobookDetail, started: Boolean, finished: Boolean, api: TvApiClient, store: AudiobookStore, onDismiss: () -> Unit, onOpenAuthor: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    HandsetSheet(visible = visible, onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp)) {
            if (started) BookSheetRow(str("ab.start_over"), MusicIcon.PREVIOUS) {
                onDismiss()
                scope.launch { runCatching { api.setAudiobookFinished(d.id, false) }; resumeBook(api, d.id, fromStart = true); store.refresh(d.id) }
            }
            // FR-R332-1 — the same row, the other way: the lit check is the state, the words are what a tap does.
            BookSheetRow(str(if (finished) "ab.mark_unfinished" else "ab.mark_finished"), MusicIcon.CHECK, lit = finished) {
                onDismiss()
                scope.launch {
                    // FR-R332-2 — nothing plays: the engine lets go of the book, and the page reads *Start* again.
                    if (MusicEngine.state.value.book?.id == d.id) MusicEngine.clear()
                    runCatching { api.setAudiobookFinished(d.id, !finished) }
                    store.refresh(d.id)
                }
            }
            d.authors.firstOrNull()?.let { a -> BookSheetRow(str("ab.go_author"), MusicIcon.LISTEN) { onDismiss(); onOpenAuthor(a.id) } }
        }
    }
}

@Composable
internal fun BookSheetRow(label: String, icon: MusicIcon, trailing: String? = null, lit: Boolean = false, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).tap(onClick), verticalAlignment = Alignment.CenterVertically) {
        MusicGlyph(icon, if (lit) colors.accentSecondary else Color.White.copy(0.85f), 20.dp)
        Spacer(Modifier.width(14.dp))
        Text(label, color = if (lit) colors.accentSecondary else Color.White, fontSize = 15.sp, fontFamily = Sora, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = Color.White.copy(0.5f), fontSize = 12.5.sp, fontFamily = Sora)
    }
}

// ── the author page ──

@Composable
fun AudiobookAuthorScreen(store: AudiobookStore, id: String, onBack: () -> Unit, onOpenBook: (String) -> Unit) {
    val colors = RaviloTheme.colors
    val loader = remember(id) { store.author(id) }
    val state by loader.state.collectAsState()
    LaunchedEffect(id) { loader.load() }
    Box(Modifier.fillMaxSize().background(colors.background)) {
        val a = (state as? Load.Ready)?.value
        if (a != null) LazyColumn(contentPadding = PaddingValues(top = 60.dp, bottom = 32.dp)) {
            item(key = "head") {
                Column(Modifier.fillMaxWidth().padding(horizontal = raviloHPad), horizontalAlignment = Alignment.CenterHorizontally) {
                    ArtistCircle(null, a.name, Modifier.size(132.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(a.name, color = colors.text, fontSize = 23.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
                    Text(booksCount(a.books.size), color = colors.textSecondary, fontSize = 13.5.sp, fontFamily = Sora)
                }
                a.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                    Text(bio, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 21.sp, fontFamily = Sora, modifier = Modifier.padding(horizontal = raviloHPad).padding(top = 16.dp))
                }
                Spacer(Modifier.height(18.dp))
            }
            item(key = "books") { Grid(a.books, 2, 12.dp, {}) { c, w -> BookCell(c, w) { onOpenBook(c.id) } } }
        } else if (state is Load.Failed) EmptyLine(str("ab.empty"))
        DetailBack(onBack)
    }
}
