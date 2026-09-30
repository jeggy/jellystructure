package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.components.ArrowRow
import dev.jellystructure.ravilo.ui.theme.isDesktopLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.AppBar
import dev.jellystructure.ravilo.ui.components.MusicGlyph
import dev.jellystructure.ravilo.ui.components.MusicIcon
import dev.jellystructure.ravilo.ui.components.OnReselect
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.shared.tv.MusicHome
import dev.jellystructure.shared.tv.MusicRow
import dev.jellystructure.shared.tv.MusicTrackItem

/** J3 — the note beside the brand: the one always-visible sign that the phone is in music mode. */
@Composable
fun MusicModeBadge() = MusicGlyph(MusicIcon.NOTE, RaviloTheme.colors.accentSecondary, 18.dp)

/**
 * R321 (FR-R321-5) — Listen: the server's rows in order (279 FR-279-2). No Now-playing card (owner): the mini bar
 * already shows it. An empty library is one sentence.
 */
@Composable
fun MusicListenScreen(
    loader: MusicLoader<MusicHome>,
    onProfile: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onSeeAllPlayed: () -> Unit,
    onTrackMore: (MusicTrackItem) -> Unit,
    scrollToTopTick: Int,
    /** R323 (FR-R323-2) — *Continue listening* above the rows while a book is in progress. */
    books: AudiobookStore? = null,
    onResumeBook: (String) -> Unit = {},
) {
    val colors = RaviloTheme.colors
    val state by loader.state.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { loader.load() }
    OnReselect(scrollToTopTick) { runCatching { list.animateScrollToItem(0) } }
    val desk = isDesktopLayout
    val listenTitle = str("mnav.listen")
    Box(Modifier.fillMaxSize().background(colors.background)) {
        when (val s = state) {
            is Load.Ready -> if (s.value.rows.isEmpty() && books == null) EmptyLine(str("mhome.empty")) else LazyColumn(
                state = list,
                contentPadding = PaddingValues(top = if (isDesktopLayout) 52.dp else RaviloDimens.appBarHeight + 4.dp, bottom = 24.dp),
            ) {
                if (desk) item(key = "title") { DeskPageTitle(listenTitle) }
                if (books != null) continueListeningRow(books, onResumeBook)
                items(s.value.rows, key = { it.key + ":" + it.title }) { row -> ListenRow(row, onOpenAlbum, onOpenArtist, onSeeAllPlayed, onTrackMore) }
            }
            Load.Failed -> EmptyLine(str("mhome.empty"))
            Load.Loading -> Unit
        }
        AppBar(onProfile = onProfile, scrolled = list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0, brandBadge = { MusicModeBadge() })
    }
}

@Composable
internal fun EmptyLine(text: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = RaviloTheme.colors.textSecondary, fontSize = 15.sp, fontFamily = Sora, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ListenRow(
    row: MusicRow,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onSeeAllPlayed: () -> Unit,
    onTrackMore: (MusicTrackItem) -> Unit,
) {
    val title = when (row.key) {
        "recent" -> str("mhome.recent_albums"); "played" -> str("mhome.recent_played"); "artists" -> str("mhome.artists")
        else -> row.title   // R326 (FR-R326-6) — no Mix row
    }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = raviloHPad)) {
            MusicSectionHeader(title, onSeeAll = if (row.key == "played") onSeeAllPlayed else null)
        }
        when {
            row.tracks.isNotEmpty() -> Column(Modifier.padding(horizontal = raviloHPad)) {
                row.tracks.forEachIndexed { i, t ->
                    TrackRow(t, showCover = true, striped = i % 2 == 0, onPlay = { MusicPlayback.playQueue(row.tracks, i, MusicContext("played", title)) }, onMore = { onTrackMore(t) })
                }
            }
            row.artists.isNotEmpty() -> ArrowRow(contentPadding = PaddingValues(horizontal = raviloHPad), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(row.artists, key = { it.id }) { r -> ArtistCardView(r, if (isDesktopLayout) 120.dp else 96.dp) { onOpenArtist(r.id) } }
            }
            else -> ArrowRow(contentPadding = PaddingValues(horizontal = raviloHPad), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // FR-R321-5 — square cards two and a half across.
                items(row.albums, key = { it.id }) { a -> AlbumCardView(a, if (isDesktopLayout) 150.dp else 140.dp, onOpen = { onOpenAlbum(a.id) }) }
            }
        }
    }
}

