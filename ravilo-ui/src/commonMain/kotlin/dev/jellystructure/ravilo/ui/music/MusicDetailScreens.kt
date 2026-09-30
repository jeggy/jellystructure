package dev.jellystructure.ravilo.ui.music

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.ChevronGlyph
import dev.jellystructure.ravilo.ui.components.GlyphDirection
import dev.jellystructure.ravilo.ui.components.MusicGlyph
import dev.jellystructure.ravilo.ui.components.MusicIcon
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.screens.HandsetSheet
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.LocalRaviloSkin
import dev.jellystructure.shared.tv.Skin
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.shared.tv.MusicAlbumDetail
import dev.jellystructure.shared.tv.MusicArtistDetail
import dev.jellystructure.shared.tv.MusicList
import dev.jellystructure.shared.tv.MusicTrackItem
import dev.jellystructure.shared.tv.MusicVideoCard
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

/** The floating Back on a detail page (the bar is hidden there, R278's rule). On the desktop the page has its toolbar (R337). */
@Composable
internal fun DetailBack(onBack: () -> Unit) {
    if (dev.jellystructure.ravilo.ui.theme.isDesktopLayout) { dev.jellystructure.ravilo.ui.components.AppBar(); return }
    Box(
        Modifier.padding(start = 10.dp, top = 10.dp).size(42.dp).clip(CircleShape).background(Color.Black.copy(0.45f)).tap(onBack),
        contentAlignment = Alignment.Center,
    ) { ChevronGlyph(GlyphDirection.LEFT, Color.White, 18.dp, description = str("music.close")) }
}

/** FR-R321-7 — the cover's colour tints the ground below (Aurora/Midnight); Noir keeps it flat. */
@Composable
private fun groundTint(): Brush {
    val colors = RaviloTheme.colors
    val noir = LocalRaviloSkin.current == Skin.NOIR
    return if (noir) Brush.verticalGradient(listOf(colors.background, colors.background))
    else Brush.verticalGradient(listOf(colors.accentDim.copy(alpha = 0.55f), colors.background))
}

// ── Album (FR-R321-7) ──

@Composable
fun MusicAlbumScreen(
    loader: MusicLoader<MusicAlbumDetail>,
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onTrackMore: (MusicTrackItem) -> Unit,
) {
    val colors = RaviloTheme.colors
    val state by loader.state.collectAsState()
    LaunchedEffect(Unit) { loader.load() }
    Box(Modifier.fillMaxSize().background(colors.background)) {
        val d = (state as? Load.Ready)?.value
        if (d != null) {
            val a = d.album
            val context = MusicContext("album", a.title, a.id)
            val albumArtistIds = a.artists.map { it.id }.toSet()
            val compilation = a.type == "compilation"
            // R337 — on the desktop the album's header is the mockup's `.alh`: a 210 dp cover, the kind and year above a
            // 36 sp title, the artist as a link, the facts, then Play and Shuffle; the page has the desktop's toolbar.
            val deskWide = dev.jellystructure.ravilo.ui.theme.isDesktopLayout
            LazyColumn(contentPadding = PaddingValues(top = if (deskWide) 52.dp else 0.dp, bottom = 24.dp)) {
                if (!deskWide) item(key = "cover") {
                    Box(Modifier.fillMaxWidth().background(groundTint())) {
                        MusicCover(a.imageUrl, a.title, Modifier.fillMaxWidth().aspectRatio(1f), corner = 0.dp, requestedWidth = 900, wordmarkSize = 30)
                    }
                }
                val total = d.tracks.sumOf { it.durationMs ?: 0L }
                if (deskWide) item(key = "head") {
                    Row(Modifier.padding(horizontal = raviloHPad).padding(top = 18.dp, bottom = 20.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                        MusicCover(a.imageUrl, a.title, Modifier.size(210.dp).shadow(24.dp, RoundedCornerShape(12.dp)), corner = 12.dp, requestedWidth = 600, wordmarkSize = 22)
                        Column(Modifier.weight(1f)) {
                            Text(listOfNotNull(musicTypeLabel(a.type) ?: str("music.type.album"), a.year?.toString()).joinToString(" · "), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
                            Text(a.title, color = colors.text, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, letterSpacing = (-0.7).sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                a.artists.forEach { r ->
                                    Text(r.name, color = colors.accentSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, modifier = Modifier.tap { onOpenArtist(r.id) })
                                }
                            }
                            Text(listOf(songsCount(d.tracks.size), fmtTotal(total)).joinToString(" · "), color = colors.textDim, fontSize = 12.5.sp, fontFamily = Sora, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PillButton(str("music.play"), MusicIcon.PLAY, primary = true) { MusicPlayback.playQueue(d.tracks, 0, context) }
                                PillButton(str("music.shuffle"), MusicIcon.SHUFFLE, primary = false) { MusicPlayback.playQueue(d.tracks, d.tracks.indices.randomOrNull() ?: 0, context, shuffle = true) }
                            }
                        }
                    }
                } else item(key = "head") {
                    Row(Modifier.padding(horizontal = raviloHPad).padding(top = 16.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(a.title, color = colors.text, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
                        Spacer(Modifier.height(4.dp))
                        // FR-R321-7 — the artist is a link; several are several links.
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            a.artists.forEach { r ->
                                Text(r.name, color = colors.accentSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, modifier = Modifier.tap { onOpenArtist(r.id) })
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(listOfNotNull(a.year?.toString(), songsCount(d.tracks.size), fmtTotal(total)).joinToString(" · "), color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora)
                            musicTypeLabel(a.type)?.let { TypeBadge(it) }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(str("music.play"), MusicIcon.PLAY, primary = true, pillWidth()) { MusicPlayback.playQueue(d.tracks, 0, context) }
                            PillButton(str("music.shuffle"), MusicIcon.SHUFFLE, primary = false, pillWidth()) { MusicPlayback.playQueue(d.tracks, d.tracks.indices.randomOrNull() ?: 0, context, shuffle = true) }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    }
                }
                itemsIndexed(d.tracks, key = { _, t -> t.id }) { i, t ->
                    // FR-R321-7 — *feat.* on an album; the credited artist on a compilation.
                    val others = t.artists.filter { it.id !in albumArtistIds }
                    val sub = when {
                        compilation -> artistLine(t)
                        others.isNotEmpty() -> str("music.feat", mapOf("x" to others.joinToString(", ") { it.name }))
                        else -> ""
                    }
                    Box(Modifier.padding(horizontal = raviloHPad)) {
                        TrackRow(t, number = (t.position ?: (i + 1)).toString(), subtitle = sub, striped = i % 2 == 0, onPlay = { MusicPlayback.playQueue(d.tracks, i, context) }, onMore = { onTrackMore(t) })
                    }
                }
                if (d.moreFromArtist.isNotEmpty()) item(key = "more") {
                    Column {
                        Box(Modifier.padding(horizontal = raviloHPad)) { MusicSectionHeader(str("music.more_from", mapOf("artist" to (a.artists.firstOrNull()?.name ?: "")))) }
                        LazyRow(contentPadding = PaddingValues(horizontal = raviloHPad), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(d.moreFromArtist, key = { it.id }) { m -> AlbumCardView(m, 130.dp, onOpen = { onOpenAlbum(m.id) }, showYear = true) }
                        }
                    }
                }
            }
        } else if (state is Load.Failed) EmptyLine(str("mhome.empty"))
        DetailBack(onBack)
    }
}

@Composable
private fun TypeBadge(label: String) {
    val colors = RaviloTheme.colors
    Text(label.uppercase(), color = colors.text, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 0.6.sp,
        modifier = Modifier.background(colors.surfaceVariant, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 3.dp))
}

// ── Artist (FR-R321-8, FR-R321-11) ──

@Composable
fun MusicArtistScreen(
    loader: MusicLoader<MusicArtistDetail>,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onPlayVideo: (MusicVideoCard) -> Unit,
    onTrackMore: (MusicTrackItem) -> Unit,
) {
    val colors = RaviloTheme.colors
    val state by loader.state.collectAsState()
    var bioOpen by remember { mutableStateOf(false) }
    var allSongs by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { loader.load() }
    Box(Modifier.fillMaxSize().background(colors.background)) {
        val d = (state as? Load.Ready)?.value
        if (d != null) {
            val r = d.artist
            val context = MusicContext("artist", r.name, r.id)
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "head") {
                    Box(Modifier.fillMaxWidth().height(if (dev.jellystructure.ravilo.ui.theme.isDesktopLayout) 280.dp else 250.dp)) {
                        if (d.backgroundUrl != null) RemoteImage(d.backgroundUrl!!, null, Modifier.fillMaxSize(), requestedWidth = 1080)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, colors.background))))
                        if (d.backgroundUrl == null) Box(Modifier.fillMaxSize().background(groundTint()))
                        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = raviloHPad), horizontalAlignment = Alignment.Start) {
                            ArtistCircle(r.imageUrl, r.name, Modifier.size(104.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(r.name, color = colors.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val facts = listOfNotNull(d.type, d.span).joinToString(" · ")
                            if (facts.isNotEmpty()) Text(facts, color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora)
                        }
                    }
                }
                item(key = "bio") {
                    Column(Modifier.padding(horizontal = raviloHPad).padding(top = 12.dp)) {
                        // FR-R321-8 — two lines and *More*; the source is never named.
                        d.biography?.takeIf { it.isNotBlank() }?.let { bio ->
                            // R326 (FR-R326-1) — three lines and *More*; tapped, the block grows in place (~340 ms) and reads *Less*. No sheet.
                            Text(bio, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = Sora, maxLines = if (bioOpen) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.animateContentSize(tween(340)))
                            Text(str(if (bioOpen) "music.less" else "music.more"), color = colors.accentSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, modifier = Modifier.tap { bioOpen = !bioOpen }.padding(vertical = 6.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        if (d.topTracks.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(str("music.play_all"), MusicIcon.PLAY, primary = true, pillWidth()) { MusicPlayback.playQueue(d.topTracks, 0, context) }
                            PillButton(str("music.shuffle"), MusicIcon.SHUFFLE, primary = false, pillWidth()) { MusicPlayback.playQueue(d.topTracks, d.topTracks.indices.randomOrNull() ?: 0, context, shuffle = true) }
                        }
                    }
                }
                d.groups.forEach { g ->
                    item(key = "g-" + g.type) {
                        Column {
                            Box(Modifier.padding(horizontal = raviloHPad)) { MusicSectionHeader(groupTitle(g.type), count = g.albums.size.toString()) }
                            LazyRow(contentPadding = PaddingValues(horizontal = raviloHPad), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(g.albums, key = { it.id }) { a -> AlbumCardView(a, 132.dp, onOpen = { onOpenAlbum(a.id) }, showYear = true) }
                            }
                        }
                    }
                }
                if (d.topTracks.isNotEmpty()) {
                    val shown = if (allSongs) d.topTracks else d.topTracks.take(5)
                    item(key = "songs-h") {
                        Box(Modifier.padding(horizontal = raviloHPad)) {
                            MusicSectionHeader(str("music.top_songs"), onSeeAll = if (!allSongs && d.topTracks.size > 5) ({ allSongs = true }) else null)
                        }
                    }
                    itemsIndexed(shown, key = { _, t -> "t-" + t.id }) { i, t ->
                        Box(Modifier.padding(horizontal = raviloHPad)) {
                            TrackRow(t, showCover = true, subtitle = t.album, onPlay = { MusicPlayback.playQueue(d.topTracks, i, context) }, onMore = { onTrackMore(t) })
                        }
                    }
                }
                // FR-R321-11 — present only when the server sends videos.
                if (d.videos.isNotEmpty()) item(key = "videos") {
                    Column {
                        Box(Modifier.padding(horizontal = raviloHPad)) { MusicSectionHeader(str("music.videos")) }
                        LazyRow(contentPadding = PaddingValues(horizontal = raviloHPad), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(d.videos, key = { it.id }) { v -> VideoTile(v) { onPlayVideo(v) } }
                        }
                    }
                }
            }
        } else if (state is Load.Failed) EmptyLine(str("mhome.empty"))
        DetailBack(onBack)
    }
}

@Composable
private fun groupTitle(type: String): String = when (type) {
    "single" -> str("music.singles"); "compilation" -> str("music.compilations"); "live" -> str("music.live")
    "soundtrack" -> str("music.soundtracks"); "appears_on" -> str("music.appears_on"); else -> str("music.albums")
}

@Composable
private fun VideoTile(v: MusicVideoCard, onPlay: () -> Unit) {
    val colors = RaviloTheme.colors
    Column(Modifier.width(220.dp).tap(onPlay)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(colors.surfaceVariant)) {
            if (v.imageUrl != null) RemoteImage(v.imageUrl!!, v.title, Modifier.fillMaxSize(), requestedWidth = 480)
            Box(Modifier.align(Alignment.Center).size(40.dp).clip(CircleShape).background(Color.Black.copy(0.5f)), contentAlignment = Alignment.Center) {
                MusicGlyph(MusicIcon.PLAY, Color.White, 18.dp)
            }
            v.durationMs?.let {
                Text(fmtLen(it), color = Color.White, fontSize = 11.sp, fontFamily = Sora,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(v.title, color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(str("music.music_video"), v.year?.toString()).joinToString(" · "), color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora)
    }
}

// ── Playlist (FR-R321-10) ──

@Composable
fun MusicPlaylistScreen(
    name: String,
    loader: MusicLoader<MusicList>,
    onBack: () -> Unit,
    onTrackMore: (MusicTrackItem) -> Unit,
) {
    val colors = RaviloTheme.colors
    val state by loader.state.collectAsState()
    LaunchedEffect(Unit) { loader.load() }
    Box(Modifier.fillMaxSize().background(colors.background)) {
        val l = (state as? Load.Ready)?.value
        val context = MusicContext("playlist", name)
        LazyColumn(contentPadding = PaddingValues(top = 64.dp, bottom = 24.dp)) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = raviloHPad), horizontalAlignment = Alignment.CenterHorizontally) {
                    Collage(l?.tracks?.mapNotNull { it.imageUrl }?.distinct()?.take(4).orEmpty(), name, 180.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(name, color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
                    if (l != null) Text(songsCount(l.tracks.size), color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora)
                    Spacer(Modifier.height(14.dp))
                    if (l != null && l.tracks.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(str("music.play"), MusicIcon.PLAY, primary = true, pillWidth()) { MusicPlayback.playQueue(l.tracks, 0, context) }
                        PillButton(str("music.shuffle"), MusicIcon.SHUFFLE, primary = false, pillWidth()) { MusicPlayback.playQueue(l.tracks, l.tracks.indices.randomOrNull() ?: 0, context, shuffle = true) }
                    }
                }
            }
            if (l != null) itemsIndexed(l.tracks, key = { i, t -> "$i-" + t.id }) { i, t ->
                Box(Modifier.padding(horizontal = raviloHPad)) {
                    TrackRow(t, showCover = true, onPlay = { MusicPlayback.playQueue(l.tracks, i, context) }, onMore = { onTrackMore(t) })
                }
            }
        }
        DetailBack(onBack)
    }
}
