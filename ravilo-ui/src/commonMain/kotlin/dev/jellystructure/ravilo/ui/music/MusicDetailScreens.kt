package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.components.ArrowRow
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
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.widthIn
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

import androidx.compose.foundation.layout.heightIn
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
internal fun DetailBack(onBack: () -> Unit, title: String? = null) {
    if (dev.jellystructure.ravilo.ui.theme.isDesktopLayout) { dev.jellystructure.ravilo.ui.components.AppBar(title = title); return }
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

// ── Album (FR-R321-7; R373 — the official album, its extras, its singles and B-sides) ──

/** R373 — how the album page starts playback; tests catch it here so no engine runs. */
typealias PlayQueueFn = (tracks: List<MusicTrackItem>, startIndex: Int, context: MusicContext, shuffle: Boolean) -> Unit

@Composable
fun MusicAlbumScreen(
    loader: MusicLoader<MusicAlbumDetail>,
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onTrackMore: (MusicTrackItem) -> Unit,
    onPlayQueue: PlayQueueFn = { l, i, c, s -> MusicPlayback.playQueue(l, i, c, shuffle = s) },
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
            // R373 (FR-R373-2, dev review 4) — the ▾ pick, remembered per album on this device; an unmatched album
            // ignores it (today's page).
            val editions = hasEditions(d)
            var pick by remember(a.id) { mutableStateOf(effectivePick(d, AlbumPickStore.get(a.id))) }
            var bsidesOpen by remember(a.id) { mutableStateOf(false) }
            fun choose(p: AlbumPick) { pick = p; AlbumPickStore.set(a.id, p) }
            fun playPick(shuffle: Boolean) {
                val q = albumQueue(d, pick)
                if (q.isNotEmpty()) onPlayQueue(q, if (shuffle) q.indices.random() else 0, context, shuffle)
            }
            fun playFrom(t: MusicTrackItem) { val (q, i) = queueForTap(d, pick, t.id); onPlayQueue(q, i, context, false) }
            val official = officialTracks(d)
            val extras = extraTracks(d)
            val lang = dev.jellystructure.ravilo.ui.i18n.LocalLang.current
            // R337 — on the desktop the album's header is the mockup's `.alh`: a 210 dp cover, the kind and year above a
            // 36 sp title, the artist as a link, the facts, then Play and Shuffle; the page has the desktop's toolbar.
            val deskWide = dev.jellystructure.ravilo.ui.theme.isDesktopLayout
            val total = headerLengthMs(d)
            @Composable fun Facts(color: Color, size: androidx.compose.ui.unit.TextUnit, prefix: String?) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(listOfNotNull(prefix, songsCount(official.size), fmtTotal(total)).joinToString(" · "), color = color, fontSize = size, fontFamily = Sora)
                    // FR-R373-2 point 1 — *+ 1 extra* in the dimmer ink.
                    if (extras.isNotEmpty()) Text(" " + str(if (extras.size == 1) "ed.n_extra" else "ed.n_extras", mapOf("n" to extras.size.toString())), color = colors.textDim, fontSize = size, fontFamily = Sora)
                }
            }
            @Composable fun SingleFrom() {
                // FR-R373-2 point 2 — a single's own page: *Single from {album}*, tappable.
                d.singleFrom?.let { s ->
                    Text(str("ed.single_from", mapOf("album" to s.title)), color = colors.accentSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
                        modifier = Modifier.padding(top = 6.dp).tap { onOpenAlbum(s.id) })
                }
            }
            @Composable fun Buttons(desk: Boolean) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!editions) {
                        PillButton(str("music.play"), MusicIcon.PLAY, primary = true, if (desk) Modifier else pillWidth()) { onPlayQueue(d.tracks, 0, context, false) }
                        PillButton(str("music.shuffle"), MusicIcon.SHUFFLE, primary = false, if (desk) Modifier else pillWidth()) { onPlayQueue(d.tracks, d.tracks.indices.randomOrNull() ?: 0, context, true) }
                    } else {
                        val n1 = official.size
                        val n2 = albumQueue(d, AlbumPick.EXTRAS).size
                        SplitPlayButton(
                            label = str(if (pick == AlbumPick.EXTRAS) "ed.play_extras" else "ed.play_album"), icon = MusicIcon.PLAY, primary = true,
                            modifier = if (desk) Modifier else pillWidth(), pick = pick, albumSongs = n1, extendedSongs = n2,
                            onMain = { playPick(false) }, onPick = { p -> choose(p); playPick(false) },
                        )
                        SplitPlayButton(
                            label = str("music.shuffle"), icon = MusicIcon.SHUFFLE, primary = false,
                            modifier = if (desk) Modifier else pillWidth(), pick = pick, albumSongs = n1, extendedSongs = n2,
                            onMain = { playPick(true) }, onPick = { p -> choose(p); playPick(true) },
                        )
                    }
                }
            }
            LazyColumn(contentPadding = PaddingValues(top = if (deskWide) 52.dp else 0.dp, bottom = 24.dp)) {
                if (!deskWide) item(key = "cover") {
                    Box(Modifier.fillMaxWidth().background(groundTint())) {
                        MusicCover(a.imageUrl, a.title, Modifier.fillMaxWidth().aspectRatio(1f), corner = 0.dp, requestedWidth = 900, wordmarkSize = 30)
                    }
                }
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
                            SingleFrom()
                            Box(Modifier.padding(top = 6.dp, bottom = 16.dp)) { Facts(colors.textDim, 12.5.sp, null) }
                            Buttons(desk = true)
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
                        SingleFrom()
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Facts(colors.textSecondary, 13.sp, a.year?.toString())
                            musicTypeLabel(a.type)?.let { TypeBadge(it) }
                        }
                        Spacer(Modifier.height(14.dp))
                        Buttons(desk = false)
                        Spacer(Modifier.height(10.dp))
                    }
                    }
                }
                fun sub(t: MusicTrackItem): String {
                    val others = t.artists.filter { it.id !in albumArtistIds }
                    return when {
                        compilation -> artistLine(t)
                        others.isNotEmpty() -> dev.jellystructure.ravilo.ui.i18n.t("music.feat", lang, mapOf("x" to others.joinToString(", ") { it.name }))
                        else -> ""
                    }
                }
                if (!editions) {
                    itemsIndexed(d.tracks, key = { _, t -> t.id }) { i, t ->
                        Box(Modifier.padding(horizontal = raviloHPad)) {
                            TrackRow(t, number = (t.position ?: (i + 1)).toString(), subtitle = sub(t), striped = i % 2 == 0, onPlay = { onPlayQueue(d.tracks, i, context, false) }, onMore = { onTrackMore(t) })
                        }
                    }
                } else {
                    // FR-R373-2 points 4–5 — the official songs numbered in the official order; a thin divider; the extras
                    // unnumbered, with no Bonus chip (the divider says it).
                    itemsIndexed(official, key = { _, t -> t.id }) { i, t ->
                        Box(Modifier.padding(horizontal = raviloHPad)) {
                            TrackRow(t, number = (i + 1).toString(), subtitle = sub(t), striped = i % 2 == 0, showBonus = false, onPlay = { playFrom(t) }, onMore = { onTrackMore(t) })
                        }
                    }
                    if (extras.isNotEmpty()) {
                        item(key = "ed-div") { EditionDivider(editionLabel(d.editionTitle, d.editionCountry, lang)) }
                        itemsIndexed(extras, key = { _, t -> "x-" + t.id }) { i, t ->
                            Box(Modifier.padding(horizontal = raviloHPad)) {
                                TrackRow(t, number = "", subtitle = sub(t), striped = (official.size + i) % 2 == 0, showBonus = false, onPlay = { playFrom(t) }, onMore = { onTrackMore(t) })
                            }
                        }
                    }
                    // FR-R373-2 point 6 — Singles & B-sides: the single cards, then one fold row.
                    if (d.singles.isNotEmpty()) item(key = "ed-singles") {
                        Column {
                            Box(Modifier.padding(horizontal = raviloHPad)) { MusicSectionHeader(str("ed.singles")) }
                            ArrowRow(contentPadding = PaddingValues(horizontal = raviloHPad), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(d.singles, key = { it.id }) { s -> SingleCardView(s, 130.dp) { onOpenAlbum(s.id) } }
                            }
                        }
                    }
                    if (d.bsideTracks.isNotEmpty()) {
                        item(key = "ed-bfold") {
                            val n = d.bsideTracks.size
                            Row(Modifier.padding(horizontal = raviloHPad).fillMaxWidth().heightIn(min = 46.dp).tap { bsidesOpen = !bsidesOpen }, verticalAlignment = Alignment.CenterVertically) {
                                Text(if (n == 1) str("ed.one_bside") else str("ed.n_bsides", mapOf("n" to n.toString())), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
                                Text(" · ", color = colors.textDim, fontSize = 14.sp, fontFamily = Sora)
                                Text(str(if (bsidesOpen) "ed.hide" else "ed.show"), color = colors.accentSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
                            }
                        }
                        if (bsidesOpen) itemsIndexed(d.bsideTracks, key = { _, t -> "b-" + t.id }) { i, t ->
                            Box(Modifier.padding(horizontal = raviloHPad)) {
                                TrackRow(t, showCover = true, subtitle = dev.jellystructure.ravilo.ui.i18n.t("ed.from_single", lang, mapOf("single" to (t.album ?: ""))), striped = i % 2 == 0, onPlay = { playFrom(t) }, onMore = { onTrackMore(t) })
                            }
                        }
                    }
                }
                if (d.moreFromArtist.isNotEmpty()) item(key = "more") {
                    Column {
                        Box(Modifier.padding(horizontal = raviloHPad)) { MusicSectionHeader(str("music.more_from", mapOf("artist" to (a.artists.firstOrNull()?.name ?: "")))) }
                        ArrowRow(contentPadding = PaddingValues(horizontal = raviloHPad), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(d.moreFromArtist, key = { it.id }) { m -> AlbumCardView(m, 130.dp, onOpen = { onOpenAlbum(m.id) }, showYear = true) }
                        }
                    }
                }
            }
        } else if (state is Load.Failed) EmptyLine(str("mhome.empty"))
        DetailBack(onBack, title = d?.album?.title)
    }
}

/** R373 (FR-R373-2 point 5) — the thin divider above an album's extras: *Extras · {edition}*. */
@Composable
private fun EditionDivider(label: String) {
    val colors = RaviloTheme.colors
    Row(Modifier.padding(horizontal = raviloHPad).padding(top = 18.dp, bottom = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 0.4.sp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(colors.fg.copy(alpha = 0.14f)))
    }
}

/** R373 (FR-R373-2 point 6) — a single's card: cover, title, *{year} · Single*. */
@Composable
private fun SingleCardView(s: dev.jellystructure.shared.tv.MusicAlbumCard, width: androidx.compose.ui.unit.Dp, onOpen: () -> Unit) {
    val colors = RaviloTheme.colors
    Column(Modifier.width(width).tap(onOpen)) {
        MusicCover(s.imageUrl, s.title, Modifier.size(width))
        Spacer(Modifier.height(7.dp))
        Text(s.title, color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (s.year != null) str("ed.single_year", mapOf("year" to s.year.toString())) else (musicTypeLabel(s.type) ?: ""), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, maxLines = 1)
    }
}

/**
 * R373 (FR-R373-2 point 3) — *Play album ▾* / *Shuffle ▾*: the main part plays the current pick; ▾ opens a menu of
 * *Play album* (*{n} songs · the official order*) and *Play album + extras* (*{n} songs · extras, then the B-sides*),
 * the current one ticked. Picking from the menu remembers the pick and starts playback.
 */
@Composable
private fun SplitPlayButton(
    label: String, icon: MusicIcon, primary: Boolean, modifier: Modifier, pick: AlbumPick, albumSongs: Int, extendedSongs: Int,
    onMain: () -> Unit, onPick: (AlbumPick) -> Unit,
) {
    val colors = RaviloTheme.colors
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(label, icon, primary = primary, Modifier.weight(1f, fill = false), onClick = onMain)
            Spacer(Modifier.width(4.dp))
            val ink = if (primary && !dev.jellystructure.ravilo.ui.theme.isDesktopLayout) colors.onAccent else colors.text
            Box(
                Modifier.size(if (dev.jellystructure.ravilo.ui.theme.isDesktopLayout) 38.dp else 46.dp).clip(CircleShape)
                    .background(if (primary) colors.accent.copy(alpha = 0.35f) else colors.surfaceVariant).tap { open = true },
                contentAlignment = Alignment.Center,
            ) { ChevronGlyph(GlyphDirection.DOWN, ink, 14.dp, description = str("ed.more_play")) }
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surface) {
            for ((p, title, sub) in listOf(
                Triple(AlbumPick.ALBUM, str("ed.play_album"), str("ed.sub_album", mapOf("n" to albumSongs.toString()))),
                Triple(AlbumPick.EXTRAS, str("ed.play_extras"), str("ed.sub_extras", mapOf("n" to extendedSongs.toString()))),
            )) {
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column {
                            Text(title, color = colors.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
                            Text(sub, color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora)
                        }
                    },
                    leadingIcon = { Box(Modifier.width(18.dp)) { if (p == pick) MusicGlyph(MusicIcon.CHECK, colors.accentSecondary, 16.dp) } },
                    onClick = { open = false; onPick(p) },
                )
            }
        }
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
            // R337 — on the desktop the artist's header is the album header's shape (`.alh`) with the artist's round picture:
            // the kind and the years above a 36 sp name, the biography, then Play all and Shuffle; the artist's backdrop,
            // when there is one, lies faint behind it. The page has the desktop's toolbar.
            val deskWide = dev.jellystructure.ravilo.ui.theme.isDesktopLayout
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                if (deskWide) item(key = "head") {
                    Box(Modifier.fillMaxWidth()) {
                        if (d.backgroundUrl != null) {
                            RemoteImage(d.backgroundUrl!!, null, Modifier.matchParentSize().alpha(if (colors.isLight) 0.25f else 0.4f), requestedWidth = 1600)
                            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(colors.background.copy(alpha = 0.15f), colors.background))))
                        }
                        Row(Modifier.padding(horizontal = raviloHPad).padding(top = 70.dp, bottom = 20.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                            ArtistCircle(r.imageUrl, r.name, Modifier.size(180.dp).shadow(24.dp, CircleShape))
                            Column(Modifier.weight(1f)) {
                                Text(listOfNotNull(d.type ?: str("music.type.artist"), d.span).joinToString(" · "), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
                                Text(r.name, color = colors.text, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, letterSpacing = (-0.7).sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 6.dp))
                                d.biography?.takeIf { it.isNotBlank() }?.let { bio ->
                                    Text(bio, color = colors.textSecondary, fontSize = 13.5.sp, lineHeight = 19.sp, fontFamily = Sora, maxLines = if (bioOpen) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 640.dp).animateContentSize(tween(340)))
                                    Text(str(if (bioOpen) "music.less" else "music.more"), color = colors.accentSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, modifier = Modifier.tap { bioOpen = !bioOpen }.padding(vertical = 4.dp))
                                }
                                if (d.topTracks.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    PillButton(str("music.play_all"), MusicIcon.PLAY, primary = true) { MusicPlayback.playQueue(d.topTracks, 0, context) }
                                    PillButton(str("music.shuffle"), MusicIcon.SHUFFLE, primary = false) { MusicPlayback.playQueue(d.topTracks, d.topTracks.indices.randomOrNull() ?: 0, context, shuffle = true) }
                                }
                            }
                        }
                    }
                } else item(key = "head") {
                    Box(Modifier.fillMaxWidth().height(250.dp)) {
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
                if (!deskWide) item(key = "bio") {
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
                            ArrowRow(contentPadding = PaddingValues(horizontal = raviloHPad), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(g.albums, key = { it.id }) { a -> AlbumCardView(a, 132.dp, onOpen = { onOpenAlbum(a.id) }, showYear = true) }
                            }
                        }
                    }
                }
                // R373 (FR-R373-7) — the singles that left *Singles & EPs* live under their albums; each album opens.
                if (d.singlesUnder.isNotEmpty()) item(key = "singles-under") {
                    val n = d.singlesUnder.sumOf { it.count }
                    val template = str(if (n == 1) "ed.under_one" else "ed.under", mapOf("n" to n.toString()))
                    val before = template.substringBefore("{albums}")
                    val after = template.substringAfter("{albums}", "")
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = raviloHPad).padding(top = 6.dp, bottom = 4.dp)) {
                        Text(before, color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora)
                        d.singlesUnder.forEachIndexed { i, u ->
                            if (i > 0) Text(" · ", color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora)
                            Text(u.album.title, color = colors.accentSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, modifier = Modifier.tap { onOpenAlbum(u.album.id) })
                        }
                        if (after.isNotEmpty()) Text(after, color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora)
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
                            TrackRow(t, showCover = true, subtitle = t.album, striped = i % 2 == 0, onPlay = { MusicPlayback.playQueue(d.topTracks, i, context) }, onMore = { onTrackMore(t) })
                        }
                    }
                }
                // FR-R321-11 — present only when the server sends videos.
                if (d.videos.isNotEmpty()) item(key = "videos") {
                    Column {
                        Box(Modifier.padding(horizontal = raviloHPad)) { MusicSectionHeader(str("music.videos")) }
                        ArrowRow(contentPadding = PaddingValues(horizontal = raviloHPad), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(d.videos, key = { it.id }) { v -> VideoTile(v) { onPlayVideo(v) } }
                        }
                    }
                }
            }
        } else if (state is Load.Failed) EmptyLine(str("mhome.empty"))
        DetailBack(onBack, title = d?.artist?.name)
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
        val deskWide = dev.jellystructure.ravilo.ui.theme.isDesktopLayout
        LazyColumn(contentPadding = PaddingValues(top = if (deskWide) 52.dp else 64.dp, bottom = 24.dp)) {
            // R337 — the album header's shape (`.alh`) for a playlist: its collage, the kind, the name, the count, Play and Shuffle.
            if (deskWide) item(key = "head") {
                Row(Modifier.padding(horizontal = raviloHPad).padding(top = 18.dp, bottom = 20.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    Collage(l?.tracks?.mapNotNull { it.imageUrl }?.distinct()?.take(4).orEmpty(), name, 210.dp)
                    Column(Modifier.weight(1f)) {
                        Text(str("music.type.playlist"), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
                        Text(name, color = colors.text, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, letterSpacing = (-0.7).sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 6.dp))
                        if (l != null) Text(listOf(songsCount(l.tracks.size), fmtTotal(l.tracks.sumOf { it.durationMs ?: 0L })).joinToString(" · "), color = colors.textDim, fontSize = 12.5.sp, fontFamily = Sora, modifier = Modifier.padding(bottom = 16.dp))
                        if (l != null && l.tracks.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(str("music.play"), MusicIcon.PLAY, primary = true) { MusicPlayback.playQueue(l.tracks, 0, context) }
                            PillButton(str("music.shuffle"), MusicIcon.SHUFFLE, primary = false) { MusicPlayback.playQueue(l.tracks, l.tracks.indices.randomOrNull() ?: 0, context, shuffle = true) }
                        }
                    }
                }
            } else item(key = "head") {
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
                    TrackRow(t, showCover = true, striped = i % 2 == 0, onPlay = { MusicPlayback.playQueue(l.tracks, i, context) }, onMore = { onTrackMore(t) })
                }
            }
        }
        DetailBack(onBack, title = name)
    }
}
