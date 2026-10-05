package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.components.deskHover
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeat
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalPortrait
import dev.jellystructure.ravilo.ui.components.CastMarkGlyph
import dev.jellystructure.ravilo.ui.components.LocalCast
import dev.jellystructure.ravilo.ui.components.SpeakerGlyph
import dev.jellystructure.ravilo.ui.seams.CastLinkState
import dev.jellystructure.ravilo.ui.components.ChevronGlyph
import dev.jellystructure.ravilo.ui.components.GlyphDirection
import dev.jellystructure.ravilo.ui.components.MusicGlyph
import dev.jellystructure.ravilo.ui.components.MusicIcon
import dev.jellystructure.ravilo.ui.components.PlayingBars
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.screens.HandsetSheet
import dev.jellystructure.ravilo.ui.theme.LocalRaviloSkin
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.shared.tv.MusicTrackItem
import dev.jellystructure.shared.tv.Skin
import dev.jellystructure.shared.tv.TrackLyrics
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** The song's live position, sampled while a screen shows it (the engine publishes on changes, not every frame). */
@Composable
fun rememberLivePosition(): Long {
    var pos by remember { mutableLongStateOf(MusicPlayback.currentPositionMs()) }
    LaunchedEffect(Unit) { while (true) { pos = MusicPlayback.currentPositionMs(); delay(250) } }
    return pos
}

// ── the Playing tab (FR-R322-2..7) ──

@Composable
fun MusicPlayingScreen(
    api: TvApiClient,
    /** Landscape is full-screen, not a tab (FR-R322-6): this closes it. */
    onClose: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onTrackMore: (MusicTrackItem) -> Unit,
    onFavorite: (MusicTrackItem, Boolean) -> Unit,
    /** R323 — a book in the engine gets the book variant of this screen. */
    books: AudiobookStore? = null,
    onOpenBook: (String) -> Unit = {},
    onOpenBookAuthor: (String) -> Unit = {},
) {
    val colors = RaviloTheme.colors
    val st by MusicPlayback.state.collectAsState()
    if (st.book != null) { BookPlayingScreen(api, books, onClose, onOpenBook, onOpenBookAuthor); return }
    // R337 (FR-R337-7) — the desktop's Playing page: the cover beside the lyrics, the transport under the cover.
    if (dev.jellystructure.ravilo.ui.theme.isDesktopLayout) { DeskPlaying(api, st, onOpenAlbum, onOpenArtist, onTrackMore, onFavorite); return }
    val portrait = LocalPortrait.current
    var showLyrics by remember { mutableStateOf(false) }
    val t = st.current
    LaunchedEffect(t?.id) { if (t?.hasLyrics != true) showLyrics = false }
    Box(Modifier.fillMaxSize().background(playingGround())) {
        if (t == null) { EmptyLine(str("music.nothing_played")); return@Box }
        if (!portrait) {
            // FR-R322-6 — the cover on the left, everything else on the right, and a close chevron.
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                MusicCover(t.imageUrl, t.album ?: t.title, Modifier.fillMaxHeight(0.86f).aspectRatio(1f).shadow(18.dp, RoundedCornerShape(14.dp)), corner = 14.dp, requestedWidth = 720, wordmarkSize = 24)
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Credits(t, onOpenAlbum, onOpenArtist, onFavorite)
                    DeviceChip()
                    Spacer(Modifier.height(10.dp))
                    SeekBar(st.durationMs)
                    Transport(st)
                    BottomRow(t, showLyrics, { showLyrics = !showLyrics }) { onTrackMore(t) }
                }
            }
            Box(Modifier.padding(10.dp).size(42.dp).clip(CircleShape).background(Color.Black.copy(0.35f)).tap(onClose), contentAlignment = Alignment.Center) {
                MusicGlyph(MusicIcon.CHEVRON_DOWN, Color.White, 20.dp, description = str("music.close"))
            }
        } else Column(Modifier.fillMaxSize().padding(horizontal = raviloHPad).padding(top = 14.dp, bottom = 10.dp)) {
            // 1 — *Playing from* and ⋯.
            Row(verticalAlignment = Alignment.CenterVertically) {
                MusicGlyph(MusicIcon.NOTE, colors.accentSecondary, 15.dp)
                Spacer(Modifier.width(8.dp))
                Text(st.context?.let { str("music.from", mapOf("x" to contextLabel(it))) } ?: str("music.now_playing"),
                    color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            if (showLyrics) {
                // FR-R322-7 — the head shrinks to a small cover and the title while lyrics show.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MusicCover(t.imageUrl, t.album ?: t.title, Modifier.size(52.dp), corner = 8.dp, requestedWidth = 140, wordmarkSize = 8)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(artistLine(t), color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora, maxLines = 1)
                    }
                }
                LyricsView(api, t, Modifier.weight(1f).fillMaxWidth())
            } else {
                // 2 — the cover, 80 % of the width; a swipe is next/previous with the cover sliding off.
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { SwipeCover(t) }
                Spacer(Modifier.height(18.dp))
                Credits(t, onOpenAlbum, onOpenArtist, onFavorite)
                DeviceChip()
            }
            Spacer(Modifier.height(12.dp))
            SeekBar(st.durationMs)
            Transport(st)
            BottomRow(t, showLyrics, { showLyrics = !showLyrics }) { onTrackMore(t) }
        }
        FailureSheet(st.failed)
    }
}

/**
 * R337 (FR-R337-7) — Playing in a computer's window, drawn to `.np` in `design/ravilo/desktop-directions.css`: the cover
 * (380 dp at 1200 dp and wider, 280 below) with the title, the device chip, the seek line and the transport under it,
 * and the lyrics beside it in large type; between 600 and 839 dp the cover sits above the lyrics. The cover, blurred
 * and faint, is the page's ground. A song without lyrics keeps the cover's column alone, centred.
 */
@Composable
private fun DeskPlaying(
    api: TvApiClient,
    st: MusicPlayerState,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onTrackMore: (MusicTrackItem) -> Unit,
    onFavorite: (MusicTrackItem, Boolean) -> Unit,
) {
    val colors = RaviloTheme.colors
    val t = st.current
    val width = dev.jellystructure.ravilo.ui.theme.LocalWindowWidth.current
    val stacked = width < dev.jellystructure.ravilo.ui.theme.WindowWidths.EXPANDED
    val coverSize = if (width >= dev.jellystructure.ravilo.ui.theme.WindowWidths.LARGE) 380.dp else if (stacked) 220.dp else 280.dp
    Box(Modifier.fillMaxSize().background(colors.background)) {
        if (t == null) { EmptyLine(str("music.nothing_played")); dev.jellystructure.ravilo.ui.components.AppBar(); return@Box }
        // `.np .bl` — the cover as the page's ground.
        t.imageUrl?.let { RemoteImage(it, null, Modifier.fillMaxSize().blur(70.dp).alpha(if (colors.isLight) 0.18f else 0.35f), requestedWidth = 240) }
        @Composable
        fun head(modifier: Modifier) {
            Column(modifier) {
                MusicCover(t.imageUrl, t.album ?: t.title, Modifier.size(coverSize).shadow(30.dp, RoundedCornerShape(16.dp)), corner = 16.dp, requestedWidth = 900, wordmarkSize = 26)
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.title, color = colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, letterSpacing = (-0.5).sp, maxLines = 1, modifier = Modifier.weight(1f).basicMarquee())
                    val favs by MusicFavorites.overrides.collectAsState()
                    val fav = favs[t.id] ?: t.favorite
                    Box(Modifier.size(32.dp).tap { onFavorite(t, !fav) }, contentAlignment = Alignment.Center) {
                        MusicGlyph(if (fav) MusicIcon.HEART_FILLED else MusicIcon.HEART, if (fav) colors.accentSecondary else colors.textSecondary, 20.dp, description = str("nav.my_list"))
                    }
                    Box(Modifier.size(32.dp).tap { onTrackMore(t) }, contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.MORE, colors.textSecondary, 20.dp, description = str("music.more")) }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    t.artists.firstOrNull()?.let { a -> Text(artistLine(t), color = colors.textSecondary, fontSize = 15.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).tap { onOpenArtist(a.id) }) }
                    t.album?.let { al -> t.albumId?.let { id ->
                        Text("·", color = colors.textSecondary, fontSize = 15.sp, fontFamily = Sora)
                        Text(al, color = colors.textSecondary, fontSize = 15.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).tap { onOpenAlbum(id) })
                    } }
                }
                // R344 — the Playing page shows every version, on its own line under the artist · album line; R373 — *Bonus* after them.
                if (t.versions.isNotEmpty() || t.extra) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    VersionChips(t.versions, fold = Int.MAX_VALUE)
                    if (t.extra) BonusChip()
                }
                DeviceChip()
                Spacer(Modifier.height(18.dp))
                DeskSeek(st.durationMs)
                Spacer(Modifier.height(10.dp))
                DeskTransport(st)
            }
        }
        val pad = if (stacked) 28.dp else 48.dp
        if (stacked) Column(Modifier.fillMaxSize().padding(top = 60.dp, start = pad, end = pad)) {
            head(Modifier.width(coverSize).align(Alignment.CenterHorizontally))
            if (t.hasLyrics) LyricsView(api, t, Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp), large = true)
        } else Row(Modifier.fillMaxSize().padding(top = 70.dp, start = pad, end = pad), horizontalArrangement = if (t.hasLyrics) Arrangement.spacedBy(48.dp) else Arrangement.Center) {
            head(Modifier.width(coverSize).verticalScroll(rememberScrollState()))
            if (t.hasLyrics) LyricsView(api, t, Modifier.weight(1f).fillMaxHeight(), large = true)
        }
        dev.jellystructure.ravilo.ui.components.AppBar()
        FailureSheet(st.failed)
    }
}

/** `.np .seek` — a 5 dp line in ink with the two times under it; a click or a drag seeks. */
@Composable
private fun DeskSeek(durationMs: Long) {
    val colors = RaviloTheme.colors
    val live = rememberLivePosition()
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val dur = durationMs.coerceAtLeast(1L)
    val frac = dragFrac ?: (live.toFloat() / dur).coerceIn(0f, 1f)
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().height(17.dp)) {
            val wPx = with(LocalDensity.current) { maxWidth.toPx() }
            Canvas(Modifier.fillMaxSize().pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFrac = (o.x / wPx).coerceIn(0f, 1f) },
                    onDragEnd = { dragFrac?.let { dev.jellystructure.ravilo.ui.sessions.PlaybackSessions.touchLocal(); MusicPlayback.seekTo((it * dur).toLong()) }; dragFrac = null },
                    onDragCancel = { dragFrac = null },
                    onHorizontalDrag = { ch, _ -> dragFrac = (ch.position.x / wPx).coerceIn(0f, 1f) },
                )
            }.pointerInput(dur) { detectTapGestures { o -> dev.jellystructure.ravilo.ui.sessions.PlaybackSessions.touchLocal(); MusicPlayback.seekTo(((o.x / wPx).coerceIn(0f, 1f) * dur).toLong()) } }) {
                val y = size.height / 2
                val h = 5.dp.toPx()
                drawRoundRect(colors.fg.copy(alpha = 0.18f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2, h / 2))
                drawRoundRect(colors.text, Offset(0f, y - h / 2), Size(size.width * frac, h), CornerRadius(h / 2, h / 2))
                if (dragFrac != null) drawCircle(colors.text, 7.dp.toPx(), Offset(size.width * frac, y))
            }
        }
        Row(Modifier.padding(top = 1.dp)) {
            Text(fmtLen(dragFrac?.let { (it * dur).toLong() } ?: live), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
            Spacer(Modifier.weight(1f))
            Text(fmtLen(durationMs), color = colors.textDim, fontSize = 12.sp, fontFamily = Sora)
        }
    }
}

/** `.np .tp` — shuffle · previous · play/pause (58 dp, ink) · next · repeat, centred. */
@Composable
private fun DeskTransport(st: MusicPlayerState) {
    val colors = RaviloTheme.colors
    val graphite = dev.jellystructure.ravilo.ui.theme.LocalRaviloTheme.current == dev.jellystructure.ravilo.ui.theme.ThemeId.GRAPHITE
    val msgRepeat = mapOf(RepeatMode.OFF to str("music.repeat_all"), RepeatMode.ALL to str("music.repeat_one"), RepeatMode.ONE to str("music.repeat_off"))
    val msgShuffle = if (st.shuffle) str("music.shuffle_off") else str("music.shuffle_on")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
        Box(Modifier.size(36.dp).tap { MusicPlayback.toggleShuffle(); MusicToasts.show(msgShuffle) }, contentAlignment = Alignment.Center) {
            dev.jellystructure.ravilo.ui.components.DeskIcon(dev.jellystructure.ravilo.ui.components.DeskIcon.SHUFFLE, if (st.shuffle) colors.accentSecondary else colors.textSecondary, 22.dp)
        }
        Box(Modifier.size(36.dp).tap { dev.jellystructure.ravilo.ui.sessions.PlaybackSessions.touchLocal(); MusicPlayback.previous() }, contentAlignment = Alignment.Center) {
            dev.jellystructure.ravilo.ui.components.DeskIcon(dev.jellystructure.ravilo.ui.components.DeskIcon.PREVIOUS, colors.text, 22.dp)
        }
        val shownPlaying = st.playing   // what this glyph draws, captured at composition (MusicPlayback.togglePlay(Boolean))
        Box(Modifier.size(58.dp).clip(CircleShape).background(if (graphite) colors.accent else colors.text).tap { MusicPlayback.togglePlay(shownPlaying) }, contentAlignment = Alignment.Center) {
            val ink = if (graphite) Color.White else colors.background
            if (st.buffering) Pulse(ink) else dev.jellystructure.ravilo.ui.components.DeskIcon(if (st.playing) dev.jellystructure.ravilo.ui.components.DeskIcon.PAUSE else dev.jellystructure.ravilo.ui.components.DeskIcon.PLAY, ink, 22.dp)
        }
        Box(Modifier.size(36.dp).then(if (st.hasNext) Modifier.tap { dev.jellystructure.ravilo.ui.sessions.PlaybackSessions.touchLocal(); MusicPlayback.next() } else Modifier), contentAlignment = Alignment.Center) {
            dev.jellystructure.ravilo.ui.components.DeskIcon(dev.jellystructure.ravilo.ui.components.DeskIcon.NEXT, if (st.hasNext) colors.text else colors.textDim, 22.dp)
        }
        Box(Modifier.size(36.dp).tap { MusicToasts.show(msgRepeat.getValue(st.repeat)); MusicPlayback.cycleRepeat() }, contentAlignment = Alignment.Center) {
            MusicGlyph(MusicIcon.REPEAT, if (st.repeat != RepeatMode.OFF) colors.accentSecondary else colors.textSecondary, 20.dp, description = str("music.repeat_all"))
            if (st.repeat == RepeatMode.ONE) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(7.dp).background(colors.accentSecondary, CircleShape))
        }
    }
}

/** R324 (FR-R324-4) — *Playing on {device}* under the credits while a speaker plays; a tap opens the sheet. */
@Composable
private fun DeviceChip() {
    val colors = RaviloTheme.colors
    val linked by MusicCast.linked.collectAsState()
    val device by MusicCast.deviceName.collectAsState()
    val cast = LocalCast.current
    val name = device
    if (!linked || name == null) return
    val display = MusicCast.status.collectAsState().value?.lyricsOn != null
    Row(
        Modifier.padding(top = 8.dp).background(colors.surfaceVariant, RoundedCornerShape(16.dp)).tap { cast?.openSheet(music = true) }.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (display) CastMarkGlyph(tint = colors.accentSecondary, link = CastLinkState.CONNECTED, sizeDp = 16) else SpeakerGlyph(colors.accentSecondary, group = false, sizeDp = 16)
        Spacer(Modifier.width(8.dp))
        // 2026-10-05 — said *Playing on* while the speaker was paused (the remote said *Paused on*); the chip follows it.
        val playing = MusicPlayback.state.collectAsState().value.playing
        Text(str(if (playing) "cast.playing_on" else "cast.paused_on", mapOf("device" to name)), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun playingGround(): Brush {
    val colors = RaviloTheme.colors
    return if (LocalRaviloSkin.current == Skin.NOIR) Brush.verticalGradient(listOf(colors.background, colors.background))
    else Brush.verticalGradient(listOf(colors.accentDim.copy(alpha = 0.6f), colors.background, colors.background))
}

@Composable
private fun contextLabel(c: MusicContext): String = when (c.kind) {
    "played" -> str("mhome.recent_played"); else -> c.label   // R326 (FR-R326-6) — a stale "mix" context keeps its own label
}

@Composable
private fun SwipeCover(t: MusicTrackItem) {
    val scope = rememberCoroutineScope()
    val dx = remember(t.id) { Animatable(0f) }
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth(0.8f).aspectRatio(1f)) {
        val w = with(density) { maxWidth.toPx() }
        MusicCover(
            t.imageUrl, t.album ?: t.title,
            Modifier.fillMaxSize().offset { IntOffset(dx.value.roundToInt(), 0) }.alpha(1f - (abs(dx.value) / w).coerceIn(0f, 0.6f))
                .shadow(22.dp, RoundedCornerShape(16.dp))
                .pointerInput(t.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val v = dx.value
                            scope.launch {
                                when {
                                    v < -w * 0.25f -> { dx.animateTo(-w, tween(160)); dev.jellystructure.ravilo.ui.sessions.PlaybackSessions.touchLocal(); MusicPlayback.next() }
                                    v > w * 0.25f -> { dx.animateTo(w, tween(160)); dev.jellystructure.ravilo.ui.sessions.PlaybackSessions.touchLocal(); MusicPlayback.previous() }
                                    else -> dx.animateTo(0f, tween(160))
                                }
                            }
                        },
                        onHorizontalDrag = { _, d -> scope.launch { dx.snapTo(dx.value + d) } },
                    )
                },
            corner = 16.dp, requestedWidth = 900, wordmarkSize = 30,
        )
    }
}

@Composable
private fun Credits(t: MusicTrackItem, onOpenAlbum: (String) -> Unit, onOpenArtist: (String) -> Unit, onFavorite: (MusicTrackItem, Boolean) -> Unit) {
    val colors = RaviloTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.title, color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, maxLines = 1, modifier = Modifier.basicMarquee())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                // R344 — Now playing shows every version, at the start of the artist · album line; R373 — *Bonus* after them.
                if (t.versions.isNotEmpty()) VersionChips(t.versions, fold = Int.MAX_VALUE, large = true)
                if (t.extra) BonusChip(large = true)
                t.artists.firstOrNull()?.let { a -> Text(artistLine(t), color = colors.accentSecondary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).tap { onOpenArtist(a.id) }) }
                t.album?.let { al -> t.albumId?.let { id -> Text(al, color = colors.textSecondary, fontSize = 14.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).tap { onOpenAlbum(id) }) } }
            }
        }
        val favs by MusicFavorites.overrides.collectAsState()
        val fav = favs[t.id] ?: t.favorite
        Box(Modifier.size(44.dp).tap { onFavorite(t, !fav) }, contentAlignment = Alignment.Center) {
            MusicGlyph(if (fav) MusicIcon.HEART_FILLED else MusicIcon.HEART, if (fav) colors.accentSecondary else colors.textSecondary, 24.dp, description = str("nav.my_list"))
        }
    }
}

/** FR-R322-4 — the seek bar with R244's time bubble while dragging, and *0:42 · −3:10*. */
@Composable
private fun SeekBar(durationMs: Long) {
    val colors = RaviloTheme.colors
    val live = rememberLivePosition()
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val dur = durationMs.coerceAtLeast(1L)
    val frac = dragFrac ?: (live.toFloat() / dur).coerceIn(0f, 1f)
    val gradient = colors.accentGradient
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().height(34.dp)) {
            val wPx = with(LocalDensity.current) { maxWidth.toPx() }
            Canvas(Modifier.fillMaxSize().pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFrac = (o.x / wPx).coerceIn(0f, 1f) },
                    onDragEnd = { dragFrac?.let { dev.jellystructure.ravilo.ui.sessions.PlaybackSessions.touchLocal(); MusicPlayback.seekTo((it * dur).toLong()) }; dragFrac = null },
                    onDragCancel = { dragFrac = null },
                    onHorizontalDrag = { ch, _ -> dragFrac = (ch.position.x / wPx).coerceIn(0f, 1f) },
                )
            }.pointerInput(dur) { detectTapGestures { o -> dev.jellystructure.ravilo.ui.sessions.PlaybackSessions.touchLocal(); MusicPlayback.seekTo(((o.x / wPx).coerceIn(0f, 1f) * dur).toLong()) } }) {
                val y = size.height / 2
                val h = 4.dp.toPx()
                drawRoundRect(colors.textDim.copy(0.35f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2, h / 2))
                drawRoundRect(gradient, Offset(0f, y - h / 2), Size(size.width * frac, h), CornerRadius(h / 2, h / 2))
                drawCircle(colors.fg, (if (dragFrac != null) 9.dp else 6.dp).toPx(), Offset(size.width * frac, y))
            }
            dragFrac?.let { f ->
                Text(fmtLen((f * dur).toLong()), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Sora,
                    modifier = Modifier.offset { IntOffset((wPx * f).roundToInt() - 22, -36) }.background(Color.Black.copy(0.75f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        Row {
            Text(fmtLen(dragFrac?.let { (it * dur).toLong() } ?: live), color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora)
            Spacer(Modifier.weight(1f))
            Text("−" + fmtLen((dur - (dragFrac?.let { (it * dur).toLong() } ?: live)).coerceAtLeast(0L)), color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora)
        }
    }
}

/** FR-R322-4/5 — shuffle · previous · play/pause (64 dp) · next (absent at the queue's end) · repeat. */
@Composable
private fun Transport(st: MusicPlayerState) {
    val colors = RaviloTheme.colors
    val msgRepeat = mapOf(RepeatMode.OFF to str("music.repeat_all"), RepeatMode.ALL to str("music.repeat_one"), RepeatMode.ONE to str("music.repeat_off"))
    val msgShuffle = if (st.shuffle) str("music.shuffle_off") else str("music.shuffle_on")
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.size(48.dp).tap { MusicPlayback.toggleShuffle(); MusicToasts.show(msgShuffle) }, contentAlignment = Alignment.Center) {
            MusicGlyph(MusicIcon.SHUFFLE, if (st.shuffle) colors.accentSecondary else colors.textSecondary, 22.dp, description = str("music.shuffle"))
        }
        Box(Modifier.size(52.dp).tap { dev.jellystructure.ravilo.ui.sessions.PlaybackSessions.touchLocal(); MusicPlayback.previous() }, contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.PREVIOUS, colors.text, 26.dp) }
        val shownPlaying = st.playing   // what this glyph draws, captured at composition (MusicPlayback.togglePlay(Boolean))
        Box(Modifier.size(64.dp).clip(CircleShape).background(colors.accentGradient).tap { MusicPlayback.togglePlay(shownPlaying) }, contentAlignment = Alignment.Center) {
            // FR-R322-5 — buffering: the play glyph's place shows R218's pulse, and nothing else moves.
            if (st.buffering) Pulse(colors.onAccent) else MusicGlyph(if (st.playing) MusicIcon.PAUSE else MusicIcon.PLAY, colors.onAccent, 28.dp)
        }
        Box(Modifier.size(52.dp).then(if (st.hasNext) Modifier.tap { dev.jellystructure.ravilo.ui.sessions.PlaybackSessions.touchLocal(); MusicPlayback.next() } else Modifier), contentAlignment = Alignment.Center) {
            if (st.hasNext) MusicGlyph(MusicIcon.NEXT, colors.text, 26.dp)
        }
        Box(Modifier.size(48.dp).tap { MusicToasts.show(msgRepeat.getValue(st.repeat)); MusicPlayback.cycleRepeat() }, contentAlignment = Alignment.Center) {
            MusicGlyph(MusicIcon.REPEAT, if (st.repeat != RepeatMode.OFF) colors.accentSecondary else colors.textSecondary, 22.dp, description = str("music.repeat_all"))
            // Repeat one — the badge (drawn: a filled dot, the toast names it).
            if (st.repeat == RepeatMode.ONE) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(8.dp).background(colors.accentSecondary, CircleShape))
        }
    }
}

@Composable
internal fun Pulse(tint: Color) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), AnimRepeat.Restart), label = "p")
    Canvas(Modifier.size(34.dp, 12.dp)) {
        for (i in 0..2) {
            val phase = (p + i * 0.22f) % 1f
            val a = 0.35f + 0.65f * (if (phase < 0.5f) phase * 2 else (1 - phase) * 2)
            drawCircle(tint.copy(alpha = a), size.height / 2.6f, Offset(size.width * (0.17f + i * 0.33f), size.height / 2))
        }
    }
}

/** FR-R322-4 — lyrics (absent when there are none, never greyed) · ⋯. The queue is the Queue tab (open question 1). */
@Composable
private fun BottomRow(t: MusicTrackItem, lyricsOn: Boolean, onLyrics: () -> Unit, onMore: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (t.hasLyrics) Box(Modifier.size(46.dp).tap(onLyrics), contentAlignment = Alignment.Center) {
            MusicGlyph(MusicIcon.LYRICS, if (lyricsOn) colors.accentSecondary else colors.textSecondary, 22.dp, description = str("music.lyrics"))
        }
        // R324 (FR-R324-1/4) — the cast glyph on Now playing itself (the design's placement): opens the music-mode
        // sheet, lit while a speaker plays. Absent, never greyed, when there is nothing to cast to.
        dev.jellystructure.ravilo.ui.components.CastButton(Modifier.size(46.dp))
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(46.dp).tap(onMore), contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.MORE, colors.textSecondary, 22.dp, description = str("music.more")) }
    }
}

/** FR-R322-5 — *Couldn't play this* with Try again and Skip; no cause is named. */
@Composable
private fun FailureSheet(failed: Boolean) {
    HandsetSheet(visible = failed, onDismiss = { MusicPlayback.skip() }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
            Text(str("music.fail_t"), color = RaviloTheme.colors.fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            Spacer(Modifier.height(6.dp))
            Text(str("music.fail_p"), color = RaviloTheme.colors.fg.copy(0.7f), fontSize = 14.sp, fontFamily = Sora)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(str("music.try_again"), null, primary = true, Modifier.weight(1f)) { MusicPlayback.retry() }
                PillButton(str("music.skip"), null, primary = false, Modifier.weight(1f)) { MusicPlayback.skip() }
            }
        }
    }
}

// ── FR-R322-7: lyrics ──

@Composable
private fun LyricsView(api: TvApiClient, t: MusicTrackItem, modifier: Modifier, large: Boolean = false) {
    val colors = RaviloTheme.colors
    var lyrics by remember(t.id) { mutableStateOf<TrackLyrics?>(null) }
    LaunchedEffect(t.id) { lyrics = runCatching { api.getLyrics(t.id) }.getOrNull() }
    val l = lyrics ?: return Box(modifier)
    val synced = l.synced
    if (synced.isNullOrEmpty()) {
        LazyColumn(modifier, contentPadding = PaddingValues(vertical = 16.dp)) {
            item { Text(l.plain.orEmpty(), color = colors.text, fontSize = if (large) 19.sp else 17.sp, lineHeight = if (large) 30.sp else 26.sp, fontFamily = Sora) }
        }
        return
    }
    val live = rememberLivePosition()
    val current = synced.indexOfLast { it.tMs <= live + 200 }.coerceAtLeast(0)
    val list = rememberLazyListState()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    // Scrolling by hand stops the follow until the next song.
    var follow by remember(t.id) { mutableStateOf(true) }
    LaunchedEffect(dragged) { if (dragged) follow = false }
    BoxWithConstraints(modifier) {
        // The list is padded by 40 % of its height at each end, and an item scrolled to with no offset rests at that
        // padding's edge: the line being sung sits two fifths of the way down, with what has been sung above it and
        // more of what comes below. (It used to be pushed a further 40 % down — four fifths of the way, two lines from
        // the foot — on the phone and the desktop alike.)
        LaunchedEffect(current, follow) { if (follow) runCatching { list.animateScrollToItem(current, 0) } }
        // The desktop's lyrics (`.lyr`): 30 sp, the line being sung in ink and the rest dim, fading out at both ends.
        val lit = if (large || LocalRaviloSkin.current == Skin.NOIR) colors.text else colors.accentSecondary
        val fade = if (large) Modifier.graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen).drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.10f to Color.Black, 0.75f to Color.Black, 1f to Color.Transparent), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
        } else Modifier
        LazyColumn(state = list, contentPadding = PaddingValues(vertical = (maxHeight * 0.4f)), modifier = fade) {
            itemsIndexed(synced) { i, line ->
                Text(
                    line.line.ifBlank { "·" },
                    color = when { i == current -> lit; large -> colors.textDim; i < current -> colors.textDim; else -> colors.textSecondary },
                    fontSize = if (large) 30.sp else 24.sp, lineHeight = if (large) 38.sp else 31.sp,
                    fontWeight = if (i == current || large) FontWeight.Bold else FontWeight.SemiBold, fontFamily = SpaceGrotesk,
                    modifier = Modifier.fillMaxWidth().padding(vertical = if (large) 9.dp else 7.dp).tap { dev.jellystructure.ravilo.ui.sessions.PlaybackSessions.touchLocal(); MusicPlayback.seekTo(line.tMs) },
                )
            }
        }
    }
}

// ── FR-R322-8: the Queue tab ──

@Composable
fun MusicQueueScreen(onTrackMore: (MusicTrackItem) -> Unit, onProfile: () -> Unit, showAppBar: Boolean = true) {
    val colors = RaviloTheme.colors
    val st by MusicPlayback.state.collectAsState()
    val density = LocalDensity.current
    // R337 (FR-R337-8) — the desktop's queue panel (`.qp`, `.qr` in design/ravilo/desktop-directions.css): 300 dp, the
    // sidebar's shade, small headings, a row of cover · title · artist and length · grip, the playing one tinted.
    val panel = !showAppBar
    val hPad = if (panel) 12.dp else raviloHPad
    val rowPx = with(density) { (if (panel) 50.dp else 60.dp).toPx() }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().background(if (panel) colors.card else colors.background)) {
        val cur = st.current
        if (cur == null) EmptyLine(str("music.queue_empty"))
        // R337 — inside the desktop's queue panel there is no app bar to clear.
        else LazyColumn(contentPadding = PaddingValues(top = if (showAppBar) RaviloDimens.appBarHeight + 6.dp else 0.dp, bottom = 24.dp)) {
            item(key = "now-h") { if (panel) PanelHeading(str("music.now_playing")) else Box(Modifier.padding(horizontal = hPad)) { MusicSectionHeader(str("music.now_playing")) } }
            item(key = "now") {
                Box(Modifier.padding(horizontal = hPad)) {
                    if (panel) QueuePanelRow(cur, now = true, onPlay = { dev.jellystructure.ravilo.ui.sessions.PlaybackSessions.touchLocal(); MusicPlayback.togglePlay() }, onMore = { onTrackMore(cur) })
                    else TrackRow(cur, showCover = true, onPlay = { dev.jellystructure.ravilo.ui.sessions.PlaybackSessions.touchLocal(); MusicPlayback.togglePlay() }, onMore = { onTrackMore(cur) })
                }
            }
            val up = st.upNext
            item(key = "up-h") {
                Column(Modifier.padding(horizontal = if (panel) 0.dp else hPad)) {
                    // R352 (FR-R352-5) — the line under *Up next* counts the rows under it, and their lengths. It used to
                    // add the playing song (*4 songs* over three rows). Nothing up next: no heading, no line.
                    if (up.isEmpty()) return@Column
                    val left = up.sumOf { it.durationMs ?: 0L }
                    if (panel) PanelHeading(str("music.up_next")) else MusicSectionHeader(str("music.up_next"))
                    Row(Modifier.padding(horizontal = if (panel) 18.dp else 0.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // One song is "1 song", not "1 songs" (the last song of a queue on a speaker, 2026-09-30).
                        val leftText = fmtTotal(left.coerceAtLeast(0L))
                        Text(if (up.size == 1) songsCount(1) + " · " + str("ab.left", mapOf("t" to leftText)) else str("music.queue_left", mapOf("n" to up.size.toString(), "t" to leftText)), color = if (panel) colors.textDim else colors.textSecondary, fontSize = if (panel) 11.5.sp else 12.5.sp, fontFamily = Sora, modifier = Modifier.weight(1f))
                        if (up.isNotEmpty()) Text(str("music.clear_queue"), color = colors.accentSecondary, fontSize = if (panel) 12.sp else 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
                            modifier = Modifier.tap { up.indices.reversed().forEach { MusicPlayback.remove(st.index + 1 + it) } }.padding(vertical = if (panel) 4.dp else 8.dp))
                    }
                }
            }
            itemsIndexed(up, key = { i, t -> "u-$i-" + t.id }) { i, t ->
                val queueIndex = st.index + 1 + i
                val swipe = remember(t.id, queueIndex) { Animatable(0f) }
                val scope = rememberCoroutineScope()
                val lifted = dragging == i
                Row(
                    Modifier.padding(horizontal = hPad).fillMaxWidth().heightIn(min = if (panel) 50.dp else 60.dp)
                        .offset { IntOffset(swipe.value.roundToInt(), if (lifted) dragDy.roundToInt() else 0) }
                        .background(if (lifted) colors.surfaceVariant else Color.Transparent, RoundedCornerShape(10.dp))
                        // Swipe left to remove.
                        .pointerInput(t.id, queueIndex) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    scope.launch {
                                        if (swipe.value < -rowPx * 1.8f) { swipe.animateTo(-rowPx * 6, tween(140)); MusicPlayback.remove(queueIndex) }
                                        else swipe.animateTo(0f, tween(140))
                                    }
                                },
                                onHorizontalDrag = { _, d -> scope.launch { swipe.snapTo((swipe.value + d).coerceAtMost(0f)) } },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) {
                        if (panel) QueuePanelRow(t, now = false, onPlay = { dev.jellystructure.ravilo.ui.sessions.PlaybackSessions.touchLocal(); MusicPlayback.playAt(queueIndex) }, onMore = { onTrackMore(t) })
                        else TrackRow(t, showCover = true, onPlay = { dev.jellystructure.ravilo.ui.sessions.PlaybackSessions.touchLocal(); MusicPlayback.playAt(queueIndex) }, onMore = { onTrackMore(t) })
                    }
                    // The drag handle: lift and move; the row lands where it is dropped.
                    Box(Modifier.size(if (panel) 26.dp else 40.dp).pointerInput(queueIndex) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = i; dragDy = 0f },
                            onDragEnd = {
                                val steps = (dragDy / rowPx).roundToInt()
                                val to = (queueIndex + steps).coerceIn(st.index + 1, st.queue.lastIndex)
                                if (to != queueIndex) MusicPlayback.move(queueIndex, to)
                                dragging = null; dragDy = 0f
                            },
                            onDragCancel = { dragging = null; dragDy = 0f },
                            onVerticalDrag = { _, d -> dragDy += d },
                        )
                    }, contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.DRAG, colors.textDim, if (panel) 15.dp else 20.dp) }
                }
            }
        }
        if (showAppBar) dev.jellystructure.ravilo.ui.components.AppBar(onProfile = onProfile, scrolled = true, brandBadge = { MusicModeBadge() })
    }
}

/** `.qp h5` — a heading inside the desktop's queue panel. */
@Composable
private fun PanelHeading(title: String) {
    Text(title, color = RaviloTheme.colors.textDim, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 8.dp))
}

/** `.qr` — one song in the desktop's queue panel: cover · title over artist and length · ⋯; the playing one sits on a tint of the accent. */
@Composable
private fun QueuePanelRow(t: MusicTrackItem, now: Boolean, onPlay: () -> Unit, onMore: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).then(if (now) Modifier.background(colors.accent.copy(alpha = 0.22f)) else Modifier.deskHover()).tap(onPlay).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MusicCover(t.imageUrl, t.album ?: t.title, Modifier.size(38.dp), corner = 6.dp, requestedWidth = 120, wordmarkSize = 7)
        Column(Modifier.weight(1f)) {
            // R344 — the queue panel is not a TrackRow; its chips are placed here (three then +N, the desktop's fold).
            // R352 (FR-R352-1) — the title first: in a 300 dp panel the chips used to take the line and leave *C…*.
            // R373 (FR-R373-5/6) — the chips and *Bonus* at the row's right; *Bonus* is never folded or cut.
            TitleThenChips(t.versions, t.extra, Modifier.fillMaxWidth(), gap = 8.dp) {
                Text(t.title, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(listOf(artistLine(t), fmtLen(t.durationMs)).filter { it.isNotBlank() }.joinToString(" · "), color = colors.textDim, fontSize = 11.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(24.dp).tap(onMore), contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.MORE, colors.textDim, 15.dp, description = str("music.more")) }
    }
}

// ── FR-R322-10: the mini bar ──

@Composable
fun MusicMiniBar(onOpen: () -> Unit) {
    val colors = RaviloTheme.colors
    val st by MusicPlayback.state.collectAsState()
    if (st.book != null) { BookMiniBar(onOpen); return }   // R323 (FR-R323-7)
    val t = st.current ?: return
    val live = rememberLivePosition()
    val scope = rememberCoroutineScope()
    val dy = remember { Animatable(0f) }
    val density = LocalDensity.current
    val limit = with(density) { 60.dp.toPx() }
    // R324 (FR-R324-7) — while a speaker plays, swipe-down only hides the bar; a toast says so, with *Stop*.
    val linked by MusicCast.linked.collectAsState()
    val device by MusicCast.deviceName.collectAsState()
    val stillPlaying = str("cast.still_playing", mapOf("device" to (device ?: "")))
    val stopWord = str("cast.stop_room")
    Column(
        Modifier.fillMaxWidth().height(RaviloDimens.musicMiniBarHeight)
            .offset { IntOffset(0, dy.value.roundToInt()) }.alpha(1f - (dy.value / (limit * 1.6f)).coerceIn(0f, 0.9f))
            .background(colors.surface)
            // J5's lean — swipe down stops: the bar follows the finger, fades, and the queue is cleared.
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { scope.launch { if (dy.value > limit) { dy.animateTo(limit * 2, tween(140)); MusicPlayback.clear(); if (linked) MusicToasts.show(stillPlaying, stopWord to { MusicCast.stop() }); dy.snapTo(0f) } else dy.animateTo(0f, tween(140)) } },
                    onVerticalDrag = { _, d -> scope.launch { dy.snapTo((dy.value + d).coerceAtLeast(0f)) } },
                )
            }
            .tap(onOpen),
    ) {
        // The hairline progress on the top edge.
        val frac = if (st.durationMs > 0) (live.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(2.dp).background(colors.textDim.copy(0.25f))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(colors.accentGradient))
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicCover(t.imageUrl, t.album ?: t.title, Modifier.size(44.dp), corner = 6.dp, requestedWidth = 120, wordmarkSize = 8)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // FR-R324-7 — *{artist} · 🔈 Stue*: the device is never truncated before the artist.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(artistLine(t), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    val d = device
                    if (linked && d != null) {
                        Text(" · ", color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora)
                        SpeakerGlyph(colors.textSecondary, group = false, sizeDp = 12)
                        Spacer(Modifier.width(4.dp))
                        Text(d, color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, maxLines = 1)
                    }
                }
            }
            val shownPlaying = st.playing   // what this glyph draws, captured at composition (MusicPlayback.togglePlay(Boolean))
            Box(Modifier.size(46.dp).tap { MusicPlayback.togglePlay(shownPlaying) }, contentAlignment = Alignment.Center) {
                if (st.buffering) PlayingBars(true, colors.text, 18.dp) else MusicGlyph(if (st.playing) MusicIcon.PAUSE else MusicIcon.PLAY, colors.text, 22.dp)
            }
            if (st.hasNext) Box(Modifier.size(46.dp).tap { dev.jellystructure.ravilo.ui.sessions.PlaybackSessions.touchLocal(); MusicPlayback.next() }, contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.NEXT, colors.text, 20.dp) }
        }
    }
}
