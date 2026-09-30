package dev.jellystructure.ravilo.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.MusicGlyph
import dev.jellystructure.ravilo.ui.components.MusicIcon
import dev.jellystructure.ravilo.ui.components.OnReselect
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.screens.HandsetSheet
import dev.jellystructure.ravilo.ui.seams.reportTextFieldFocus
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.shared.tv.MusicAlbumCard
import dev.jellystructure.shared.tv.MusicArtistCard
import dev.jellystructure.shared.tv.MusicGenreCount
import dev.jellystructure.shared.tv.MusicList
import dev.jellystructure.shared.tv.MusicPlaylist
import dev.jellystructure.shared.tv.MusicSearch
import dev.jellystructure.shared.tv.MusicTrackItem
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The Browse page's chips, in the strip's order. Audiobooks joins with R323. R339 (FR-R339-1) — the admin's Library →
 *  Music order (287): Artists first, and Artists is the chip shown on arrival ([Dest.MusicBrowse]'s default). */
val MUSIC_CHIPS = listOf("artists", "albums", "songs", "genres", "playlists")
private val SORTS = listOf("added" to "music.sort_added", "title" to "music.sort_az", "year" to "music.sort_year", "played" to "music.sort_played")

/** A browse list that grows a page at a time as the viewer scrolls to its end. */
class MusicPagedList(private val fetch: suspend (Int) -> MusicList) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<Load<MusicList>>(Load.Loading)
    val state: StateFlow<Load<MusicList>> = _state
    private var page = 0
    private var busy = false
    fun load() {
        if (busy) return
        busy = true
        scope.launch {
            runCatching { fetch(0) }.onSuccess { page = 0; _state.value = Load.Ready(it) }.onFailure { if (_state.value !is Load.Ready) _state.value = Load.Failed }
            busy = false
        }
    }
    fun more() {
        val cur = (_state.value as? Load.Ready)?.value ?: return
        val have = cur.albums.size + cur.artists.size + cur.tracks.size
        if (busy || have >= cur.total) return
        busy = true
        scope.launch {
            runCatching { fetch(page + 1) }.onSuccess { next ->
                page++
                _state.value = Load.Ready(cur.copy(albums = cur.albums + next.albums, artists = cur.artists + next.artists, tracks = cur.tracks + next.tracks))
            }
            busy = false
        }
    }
}

/** R321 (FR-R321-6) — Browse = the library + search: lists per chip and sort, the genre drill-in, and the query. */
class MusicBrowseStore(private val api: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sorts = MutableStateFlow(mapOf("albums" to "added", "artists" to "title", "songs" to "added"))
    val genre = MutableStateFlow<String?>(null)
    private val lists = HashMap<String, MusicPagedList>()
    fun list(chip: String, sort: String, genre: String?): MusicPagedList = lists.getOrPut("$chip|$sort|$genre") {
        val what = if (chip == "songs") "tracks" else chip
        MusicPagedList { p -> api.browseMusic(what, sort, p, genre) }
    }
    val genres = MusicLoader { api.getMusicGenres() }
    val playlists = MusicLoader { api.getMusicPlaylists() }

    val query = MutableStateFlow("")
    private val _results = MutableStateFlow<Load<MusicSearch>?>(null)
    val results: StateFlow<Load<MusicSearch>?> = _results
    private var searchJob: Job? = null
    fun onQuery(q: String) {
        query.value = q
        searchJob?.cancel()
        if (q.isBlank()) { _results.value = null; return }
        searchJob = scope.launch {
            delay(250)
            _results.value = runCatching { Load.Ready(api.searchMusic(q)) }.getOrElse { Load.Failed }
        }
    }
    fun setSort(chip: String, sort: String) { sorts.value = sorts.value + (chip to sort) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicBrowseScreen(
    store: MusicBrowseStore,
    chip: String,
    onChip: (String) -> Unit,
    focusInput: Boolean,
    onFocusInputConsumed: () -> Unit,
    onProfile: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenPlaylist: (MusicPlaylist) -> Unit,
    onTrackMore: (MusicTrackItem) -> Unit,
    scrollToTopTick: Int,
    /** R323 (FR-R323-1) — the Audiobooks chip, absent without the audiobook library. */
    books: AudiobookStore? = null,
    onResumeBook: (String) -> Unit = {},
    onOpenBook: (String) -> Unit = {},
    onOpenBookAuthor: (String) -> Unit = {},
) {
    val colors = RaviloTheme.colors
    val query by store.query.collectAsState()
    val bookSort by remember(books) { books?.sort ?: kotlinx.coroutines.flow.MutableStateFlow("added") }.collectAsState()
    val bookView by remember(books) { books?.view ?: kotlinx.coroutines.flow.MutableStateFlow("books") }.collectAsState()
    val results by store.results.collectAsState()
    val sorts by store.sorts.collectAsState()
    val genre by store.genre.collectAsState()
    val list = rememberLazyListState()
    val fieldFR = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var sortOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    // FR-R321-4 — a re-tap of Browse focuses the field and raises the keyboard (R277's rule: on re-tap, never on arrival).
    LaunchedEffect(focusInput) {
        if (focusInput) {
            runCatching { list.animateScrollToItem(0) }
            runCatching { fieldFR.requestFocus() }
            keyboard?.show()
            onFocusInputConsumed()
        }
    }
    OnReselect(scrollToTopTick) { runCatching { list.animateScrollToItem(0) } }
    val sortable = query.isBlank() && chip in sorts.keys && !(chip == "albums" && genre != null)
    val bookSortable = query.isBlank() && chip == "audiobooks" && books != null && bookView == "books"

    Box(Modifier.fillMaxSize().background(colors.background)) {
        LazyColumn(state = list, contentPadding = PaddingValues(top = RaviloDimens.appBarHeight + 6.dp, bottom = 24.dp)) {
            item(key = "field") {
                SearchField(query, store::onQuery, fieldFR) { keyboard?.hide() }
            }
            if (query.isNotBlank()) {
                searchResults(results, query, expanded, { expanded = it }, onOpenAlbum, onOpenArtist, onTrackMore)
            } else {
                item(key = "chips") {
                    LazyRow(contentPadding = PaddingValues(horizontal = raviloHPad), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                        items(MUSIC_CHIPS + listOfNotNull(if (books != null) "audiobooks" else null), key = { it }) { c -> Chip(chipLabel(c), c == chip) { expanded = null; onChip(c) } }
                    }
                }
                when (chip) {
                    "audiobooks" -> if (books != null) audiobookShelf(books, bookSort, bookView, onResumeBook, onOpenBook, onOpenBookAuthor) else Unit
                    "genres" -> if (genre == null) genresChip(store) { store.genre.value = it; onChip("albums") } else Unit
                    "playlists" -> playlistsChip(store, onOpenPlaylist)
                    else -> {
                        val sort = sorts[chip] ?: "added"
                        val g = if (chip == "albums") genre else null
                        listChip(store.list(chip, sort, g), chip, g, { store.genre.value = null; onChip("genres") }, onOpenAlbum, onOpenArtist, onTrackMore)
                    }
                }
            }
        }
        AppBar(
            onProfile = onProfile,
            scrolled = list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0,
            brandBadge = { MusicModeBadge() },
            // FR-R321-6 — the sort pill sits in the top row's page slot.
            handsetTopSlot = when {
                sortable -> ({ SortPill(str(SORTS.first { it.first == (sorts[chip] ?: "added") }.second)) { sortOpen = true } })
                bookSortable -> ({ SortPill(str(AUDIOBOOK_SORTS.first { it.first == bookSort }.second)) { sortOpen = true } })
                else -> null
            },
        )
        HandsetSheet(visible = sortOpen, onDismiss = { sortOpen = false }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp)) {
                if (chip == "audiobooks" && books != null) AUDIOBOOK_SORTS.forEach { (key, label) ->
                    val on = bookSort == key
                    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).tap { books.sort.value = key; sortOpen = false }, verticalAlignment = Alignment.CenterVertically) {
                        Text(str(label), color = if (on) colors.accentSecondary else Color.White, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, fontFamily = Sora)
                    }
                } else SORTS.forEach { (key, label) ->
                    val on = (sorts[chip] ?: "added") == key
                    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).tap { store.setSort(chip, key); sortOpen = false }, verticalAlignment = Alignment.CenterVertically) {
                        Text(str(label), color = if (on) colors.accentSecondary else Color.White, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, fontFamily = Sora)
                    }
                }
            }
        }
    }
}

@Composable
private fun chipLabel(c: String): String = when (c) {
    "albums" -> str("mlib.albums"); "artists" -> str("mlib.artists"); "songs" -> str("mlib.songs"); "genres" -> str("mlib.genres")
    "audiobooks" -> str("mnav.audiobooks"); else -> str("mlib.playlists")
}

@Composable
internal fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    val m = if (on) Modifier.background(colors.accentGradient, RoundedCornerShape(18.dp)) else Modifier.background(colors.surfaceVariant, RoundedCornerShape(18.dp))
    Text(label, color = if (on) colors.onAccent else colors.textSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
        modifier = m.tap(onClick).padding(horizontal = 14.dp, vertical = 9.dp))
}

@Composable
internal fun SortPill(label: String, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(Modifier.border(1.dp, colors.textDim.copy(0.4f), RoundedCornerShape(16.dp)).tap(onClick).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
        Spacer(Modifier.width(4.dp))
        MusicGlyph(MusicIcon.CHEVRON_DOWN, colors.textSecondary, 14.dp)
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, fr: FocusRequester, onSearch: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(
        Modifier.padding(horizontal = raviloHPad).fillMaxWidth().height(50.dp).background(colors.surfaceVariant, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MusicGlyph(MusicIcon.SEARCH, colors.textSecondary, 18.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = query, onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().focusRequester(fr).reportTextFieldFocus(),
                textStyle = TextStyle(color = colors.text, fontSize = 15.sp, fontFamily = Sora),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                cursorBrush = SolidColor(colors.accent),
                decorationBox = { inner -> if (query.isEmpty()) Text(str("music.search_ph"), color = colors.textSecondary, fontSize = 15.sp, fontFamily = Sora); inner() },
            )
        }
        if (query.isNotEmpty()) Text(str("search.clear"), color = colors.accent, fontSize = 14.sp, fontFamily = Sora, modifier = Modifier.tap { onQuery("") }.padding(vertical = 10.dp, horizontal = 4.dp))
    }
}

// ── FR-R321-6: a query replaces the chips with grouped results ──

private fun LazyListScope.searchResults(
    results: Load<MusicSearch>?,
    query: String,
    expanded: String?,
    onExpand: (String?) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onTrackMore: (MusicTrackItem) -> Unit,
) {
    val r = (results as? Load.Ready)?.value ?: return
    if (r.songs.isEmpty() && r.albums.isEmpty() && r.artists.isEmpty()) {
        item(key = "none") { Box(Modifier.fillMaxWidth().height(160.dp)) { EmptyLine(noResults(query)) } }
        return
    }
    if (r.songs.isNotEmpty()) {
        val shown = if (expanded == "songs") r.songs else r.songs.take(3)
        item(key = "h-songs") { Group(str("mlib.songs"), r.songsTotal, if (r.songsTotal > 3 && expanded != "songs") ({ onExpand("songs") }) else null) }
        itemsIndexed(shown, key = { _, t -> "s-" + t.id }) { i, t ->
            Box(Modifier.padding(horizontal = raviloHPad)) {
                TrackRow(t, showCover = true, onPlay = { MusicPlayback.playQueue(r.songs, i, MusicContext("search", query)) }, onMore = { onTrackMore(t) })
            }
        }
    }
    if (r.albums.isNotEmpty()) {
        val shown = if (expanded == "albums") r.albums else r.albums.take(3)
        item(key = "h-albums") { Group(str("mlib.albums"), r.albumsTotal, if (r.albumsTotal > 3 && expanded != "albums") ({ onExpand("albums") }) else null) }
        items(shown, key = { "a-" + it.id }) { a -> AlbumLine(a) { onOpenAlbum(a.id) } }
    }
    if (r.artists.isNotEmpty()) {
        val shown = if (expanded == "artists") r.artists else r.artists.take(3)
        item(key = "h-artists") { Group(str("mlib.artists"), r.artistsTotal, if (r.artistsTotal > 3 && expanded != "artists") ({ onExpand("artists") }) else null) }
        items(shown, key = { "r-" + it.id }) { a -> ArtistLine(a) { onOpenArtist(a.id) } }
    }
}

@Composable
private fun noResults(q: String) = str("music.no_results", mapOf("q" to q))

@Composable
private fun Group(title: String, total: Int, onSeeAll: (() -> Unit)?) {
    Box(Modifier.padding(horizontal = raviloHPad)) { MusicSectionHeader(title, count = total.toString(), onSeeAll = onSeeAll) }
}

@Composable
private fun AlbumLine(a: MusicAlbumCard, onOpen: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(Modifier.padding(horizontal = raviloHPad, vertical = 5.dp).fillMaxWidth().tap(onOpen), verticalAlignment = Alignment.CenterVertically) {
        MusicCover(a.imageUrl, a.title, Modifier.width(50.dp).height(50.dp), corner = 6.dp, requestedWidth = 140, wordmarkSize = 8)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, color = colors.text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, fontFamily = Sora, maxLines = 1)
            Text(listOfNotNull(a.artists.joinToString(" & ") { it.name }.ifBlank { null }, a.year?.toString()).joinToString(" · "), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, maxLines = 1)
        }
    }
}

@Composable
private fun ArtistLine(a: MusicArtistCard, onOpen: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(Modifier.padding(horizontal = raviloHPad, vertical = 5.dp).fillMaxWidth().tap(onOpen), verticalAlignment = Alignment.CenterVertically) {
        ArtistCircle(a.imageUrl, a.name, Modifier.width(50.dp).height(50.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, color = colors.text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, fontFamily = Sora, maxLines = 1)
            if (a.albumCount > 0) Text(albumsCount(a.albumCount), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora)
        }
    }
}

// ── the chips' content ──

private fun LazyListScope.listChip(
    paged: MusicPagedList,
    chip: String,
    genre: String?,
    onBackToGenres: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onTrackMore: (MusicTrackItem) -> Unit,
) {
    item(key = "load-$chip-$genre") { LaunchedEffect(paged) { paged.load() } }
    if (genre != null) item(key = "genre-back") {
        Row(Modifier.padding(horizontal = raviloHPad, vertical = 6.dp).tap(onBackToGenres), verticalAlignment = Alignment.CenterVertically) {
            dev.jellystructure.ravilo.ui.components.ChevronGlyph(dev.jellystructure.ravilo.ui.components.GlyphDirection.LEFT, RaviloTheme.colors.accentSecondary, 14.dp)
            Spacer(Modifier.width(4.dp))
            Text(str("music.all_genres"), color = RaviloTheme.colors.accentSecondary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
            Spacer(Modifier.width(10.dp))
            Text(genre, color = RaviloTheme.colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
        }
    }
    item(key = "list-$chip-$genre") {
        val state by paged.state.collectAsState()
        val l = (state as? Load.Ready)?.value
        when {
            l == null -> Box(Modifier.fillMaxWidth().height(120.dp))
            chip == "albums" -> Grid(l.albums, 2, 12.dp, { paged.more() }) { a, w -> AlbumCardView(a, w, onOpen = { onOpenAlbum(a.id) }, showYear = true) }
            chip == "artists" -> Grid(l.artists, 3, 12.dp, { paged.more() }) { a, w -> ArtistCardView(a, w) { onOpenArtist(a.id) } }
            else -> Column(Modifier.padding(horizontal = raviloHPad)) {
                l.tracks.forEachIndexed { i, t ->
                    TrackRow(t, showCover = true, onPlay = { MusicPlayback.playQueue(l.tracks, i, MusicContext("songs", t.title)) }, onMore = { onTrackMore(t) })
                    if (i == l.tracks.lastIndex) LaunchedEffect(l.tracks.size) { paged.more() }
                }
            }
        }
    }
}

/** A grid inside the page's one scrolling column (the chip strip and the field scroll away with it). */
@Composable
internal fun <T> Grid(items: List<T>, columns: Int, gap: Dp, onEnd: () -> Unit, cell: @Composable (T, Dp) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(horizontal = raviloHPad).fillMaxWidth()) {
        val w = (maxWidth - gap * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.chunked(columns).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) { row.forEach { cell(it, w) } }
                if (r == (items.size - 1) / columns) LaunchedEffect(items.size) { onEnd() }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.genresChip(store: MusicBrowseStore, onGenre: (String) -> Unit) {
    item(key = "genres") {
        LaunchedEffect(Unit) { store.genres.load() }
        val state by store.genres.state.collectAsState()
        val g: List<MusicGenreCount> = (state as? Load.Ready)?.value.orEmpty()
        val colors = RaviloTheme.colors
        FlowRow(Modifier.padding(horizontal = raviloHPad, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            g.forEach { genre ->
                Row(Modifier.background(colors.surfaceVariant, RoundedCornerShape(18.dp)).tap { onGenre(genre.name) }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(genre.name, color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
                    Spacer(Modifier.width(6.dp))
                    Text(genre.albumCount.toString(), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
                }
            }
        }
    }
}

private fun LazyListScope.playlistsChip(store: MusicBrowseStore, onOpen: (MusicPlaylist) -> Unit) {
    item(key = "playlists") {
        LaunchedEffect(Unit) { store.playlists.load() }
        val state by store.playlists.state.collectAsState()
        val colors = RaviloTheme.colors
        val pl = (state as? Load.Ready)?.value
        Column(Modifier.padding(horizontal = raviloHPad)) {
            when {
                pl == null -> Box(Modifier.height(80.dp))
                // FR-R321-10 (J7 lean: two states) — creating a playlist is phase 2.
                pl.isEmpty() -> Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(14.dp)).padding(16.dp)) {
                    Text(str("music.no_playlists"), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
                    Spacer(Modifier.height(4.dp))
                    Text(str("music.no_playlists_hint"), color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora)
                }
                else -> pl.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).tap { onOpen(p) }, verticalAlignment = Alignment.CenterVertically) {
                        Collage(p.covers, p.name, 56.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = colors.text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, fontFamily = Sora, maxLines = 1)
                            Text(songsCount(p.trackCount), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(str("music.new_playlist"), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora)
                Text(str("music.phase2"), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
            }
        }
    }
}

/** A playlist's four-cover collage (FR-R321-10). */
@Composable
internal fun Collage(covers: List<String>, title: String, size: Dp) {
    Column(Modifier.width(size).height(size)) {
        for (r in 0..1) Row(Modifier.weight(1f)) {
            for (c in 0..1) MusicCover(covers.getOrNull(r * 2 + c), title, Modifier.weight(1f).fillMaxSize(), corner = 2.dp, requestedWidth = 120, wordmarkSize = 6)
        }
    }
}
