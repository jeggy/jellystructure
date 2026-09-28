package dev.jellystructure.ravilo.ui.music

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
    var pos by remember { mutableLongStateOf(MusicEngine.currentPositionMs()) }
    LaunchedEffect(Unit) { while (true) { pos = MusicEngine.currentPositionMs(); delay(250) } }
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
    val st by MusicEngine.state.collectAsState()
    if (st.book != null) { BookPlayingScreen(api, books, onClose, onOpenBook, onOpenBookAuthor); return }
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
            }
            Spacer(Modifier.height(12.dp))
            SeekBar(st.durationMs)
            Transport(st)
            BottomRow(t, showLyrics, { showLyrics = !showLyrics }) { onTrackMore(t) }
        }
        FailureSheet(st.failed)
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
                                    v < -w * 0.25f -> { dx.animateTo(-w, tween(160)); MusicEngine.next() }
                                    v > w * 0.25f -> { dx.animateTo(w, tween(160)); MusicEngine.previous() }
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                    onDragEnd = { dragFrac?.let { MusicEngine.seekTo((it * dur).toLong()) }; dragFrac = null },
                    onDragCancel = { dragFrac = null },
                    onHorizontalDrag = { ch, _ -> dragFrac = (ch.position.x / wPx).coerceIn(0f, 1f) },
                )
            }.pointerInput(dur) { detectTapGestures { o -> MusicEngine.seekTo(((o.x / wPx).coerceIn(0f, 1f) * dur).toLong()) } }) {
                val y = size.height / 2
                val h = 4.dp.toPx()
                drawRoundRect(colors.textDim.copy(0.35f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2, h / 2))
                drawRoundRect(gradient, Offset(0f, y - h / 2), Size(size.width * frac, h), CornerRadius(h / 2, h / 2))
                drawCircle(Color.White, (if (dragFrac != null) 9.dp else 6.dp).toPx(), Offset(size.width * frac, y))
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
        Box(Modifier.size(48.dp).tap { MusicEngine.toggleShuffle(); MusicToasts.show(msgShuffle) }, contentAlignment = Alignment.Center) {
            MusicGlyph(MusicIcon.SHUFFLE, if (st.shuffle) colors.accentSecondary else colors.textSecondary, 22.dp, description = str("music.shuffle"))
        }
        Box(Modifier.size(52.dp).tap { MusicEngine.previous() }, contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.PREVIOUS, colors.text, 26.dp) }
        Box(Modifier.size(64.dp).clip(CircleShape).background(colors.accentGradient).tap { MusicEngine.togglePlay() }, contentAlignment = Alignment.Center) {
            // FR-R322-5 — buffering: the play glyph's place shows R218's pulse, and nothing else moves.
            if (st.buffering) Pulse(colors.onAccent) else MusicGlyph(if (st.playing) MusicIcon.PAUSE else MusicIcon.PLAY, colors.onAccent, 28.dp)
        }
        Box(Modifier.size(52.dp).then(if (st.hasNext) Modifier.tap { MusicEngine.next() } else Modifier), contentAlignment = Alignment.Center) {
            if (st.hasNext) MusicGlyph(MusicIcon.NEXT, colors.text, 26.dp)
        }
        Box(Modifier.size(48.dp).tap { MusicToasts.show(msgRepeat.getValue(st.repeat)); MusicEngine.cycleRepeat() }, contentAlignment = Alignment.Center) {
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
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(46.dp).tap(onMore), contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.MORE, colors.textSecondary, 22.dp, description = str("music.more")) }
    }
}

/** FR-R322-5 — *Couldn't play this* with Try again and Skip; no cause is named. */
@Composable
private fun FailureSheet(failed: Boolean) {
    HandsetSheet(visible = failed, onDismiss = { MusicEngine.skip() }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
            Text(str("music.fail_t"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            Spacer(Modifier.height(6.dp))
            Text(str("music.fail_p"), color = Color.White.copy(0.7f), fontSize = 14.sp, fontFamily = Sora)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(str("music.try_again"), null, primary = true, Modifier.weight(1f)) { MusicEngine.retry() }
                PillButton(str("music.skip"), null, primary = false, Modifier.weight(1f)) { MusicEngine.skip() }
            }
        }
    }
}

// ── FR-R322-7: lyrics ──

@Composable
private fun LyricsView(api: TvApiClient, t: MusicTrackItem, modifier: Modifier) {
    val colors = RaviloTheme.colors
    var lyrics by remember(t.id) { mutableStateOf<TrackLyrics?>(null) }
    LaunchedEffect(t.id) { lyrics = runCatching { api.getLyrics(t.id) }.getOrNull() }
    val l = lyrics ?: return Box(modifier)
    val synced = l.synced
    if (synced.isNullOrEmpty()) {
        LazyColumn(modifier, contentPadding = PaddingValues(vertical = 16.dp)) {
            item { Text(l.plain.orEmpty(), color = colors.text, fontSize = 17.sp, lineHeight = 26.sp, fontFamily = Sora) }
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
        val h = with(LocalDensity.current) { maxHeight.toPx() }
        LaunchedEffect(current, follow) { if (follow) runCatching { list.animateScrollToItem(current, -(h * 0.4f).roundToInt()) } }
        val lit = if (LocalRaviloSkin.current == Skin.NOIR) colors.text else colors.accentSecondary
        LazyColumn(state = list, contentPadding = PaddingValues(vertical = (maxHeight * 0.4f))) {
            itemsIndexed(synced) { i, line ->
                Text(
                    line.line.ifBlank { "·" },
                    color = when { i == current -> lit; i < current -> colors.textDim; else -> colors.textSecondary },
                    fontSize = 24.sp, lineHeight = 31.sp, fontWeight = if (i == current) FontWeight.Bold else FontWeight.SemiBold, fontFamily = SpaceGrotesk,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp).tap { MusicEngine.seekTo(line.tMs) },
                )
            }
        }
    }
}

// ── FR-R322-8: the Queue tab ──

@Composable
fun MusicQueueScreen(onTrackMore: (MusicTrackItem) -> Unit, onProfile: () -> Unit) {
    val colors = RaviloTheme.colors
    val st by MusicEngine.state.collectAsState()
    val density = LocalDensity.current
    val rowPx = with(density) { 60.dp.toPx() }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().background(colors.background)) {
        val cur = st.current
        if (cur == null) EmptyLine(str("music.queue_empty"))
        else LazyColumn(contentPadding = PaddingValues(top = RaviloDimens.appBarHeight + 6.dp, bottom = 24.dp)) {
            item(key = "now-h") { Box(Modifier.padding(horizontal = raviloHPad)) { MusicSectionHeader(str("music.now_playing")) } }
            item(key = "now") { Box(Modifier.padding(horizontal = raviloHPad)) { TrackRow(cur, showCover = true, onPlay = { MusicEngine.togglePlay() }, onMore = { onTrackMore(cur) }) } }
            val up = st.upNext
            item(key = "up-h") {
                Column(Modifier.padding(horizontal = raviloHPad)) {
                    val left = (cur.durationMs ?: 0L) - st.positionMs + up.sumOf { it.durationMs ?: 0L }
                    MusicSectionHeader(str("music.up_next"))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(str("music.queue_left", mapOf("n" to (up.size + 1).toString(), "t" to fmtTotal(left.coerceAtLeast(0L)))), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, modifier = Modifier.weight(1f))
                        if (up.isNotEmpty()) Text(str("music.clear_queue"), color = colors.accentSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
                            modifier = Modifier.tap { up.indices.reversed().forEach { MusicEngine.remove(st.index + 1 + it) } }.padding(vertical = 8.dp))
                    }
                }
            }
            itemsIndexed(up, key = { i, t -> "u-$i-" + t.id }) { i, t ->
                val queueIndex = st.index + 1 + i
                val swipe = remember(t.id, queueIndex) { Animatable(0f) }
                val scope = rememberCoroutineScope()
                val lifted = dragging == i
                Row(
                    Modifier.padding(horizontal = raviloHPad).fillMaxWidth().heightIn(min = 60.dp)
                        .offset { IntOffset(swipe.value.roundToInt(), if (lifted) dragDy.roundToInt() else 0) }
                        .background(if (lifted) colors.surfaceVariant else Color.Transparent, RoundedCornerShape(10.dp))
                        // Swipe left to remove.
                        .pointerInput(t.id, queueIndex) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    scope.launch {
                                        if (swipe.value < -rowPx * 1.8f) { swipe.animateTo(-rowPx * 6, tween(140)); MusicEngine.remove(queueIndex) }
                                        else swipe.animateTo(0f, tween(140))
                                    }
                                },
                                onHorizontalDrag = { _, d -> scope.launch { swipe.snapTo((swipe.value + d).coerceAtMost(0f)) } },
                            )
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f)) { TrackRow(t, showCover = true, onPlay = { MusicEngine.playAt(queueIndex) }, onMore = { onTrackMore(t) }) }
                    // The drag handle: lift and move; the row lands where it is dropped.
                    Box(Modifier.size(40.dp).pointerInput(queueIndex) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = i; dragDy = 0f },
                            onDragEnd = {
                                val steps = (dragDy / rowPx).roundToInt()
                                val to = (queueIndex + steps).coerceIn(st.index + 1, st.queue.lastIndex)
                                if (to != queueIndex) MusicEngine.move(queueIndex, to)
                                dragging = null; dragDy = 0f
                            },
                            onDragCancel = { dragging = null; dragDy = 0f },
                            onVerticalDrag = { _, d -> dragDy += d },
                        )
                    }, contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.DRAG, colors.textDim, 20.dp) }
                }
            }
        }
        dev.jellystructure.ravilo.ui.components.AppBar(onProfile = onProfile, scrolled = true, brandBadge = { MusicModeBadge() })
    }
}

// ── FR-R322-10: the mini bar ──

@Composable
fun MusicMiniBar(onOpen: () -> Unit) {
    val colors = RaviloTheme.colors
    val st by MusicEngine.state.collectAsState()
    if (st.book != null) { BookMiniBar(onOpen); return }   // R323 (FR-R323-7)
    val t = st.current ?: return
    val live = rememberLivePosition()
    val scope = rememberCoroutineScope()
    val dy = remember { Animatable(0f) }
    val density = LocalDensity.current
    val limit = with(density) { 60.dp.toPx() }
    Column(
        Modifier.fillMaxWidth().height(RaviloDimens.musicMiniBarHeight)
            .offset { IntOffset(0, dy.value.roundToInt()) }.alpha(1f - (dy.value / (limit * 1.6f)).coerceIn(0f, 0.9f))
            .background(colors.surface)
            // J5's lean — swipe down stops: the bar follows the finger, fades, and the queue is cleared.
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { scope.launch { if (dy.value > limit) { dy.animateTo(limit * 2, tween(140)); MusicEngine.clear(); dy.snapTo(0f) } else dy.animateTo(0f, tween(140)) } },
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
                Text(artistLine(t), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(46.dp).tap { MusicEngine.togglePlay() }, contentAlignment = Alignment.Center) {
                if (st.buffering) PlayingBars(true, colors.text, 18.dp) else MusicGlyph(if (st.playing) MusicIcon.PAUSE else MusicIcon.PLAY, colors.text, 22.dp)
            }
            if (st.hasNext) Box(Modifier.size(46.dp).tap { MusicEngine.next() }, contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.NEXT, colors.text, 20.dp) }
        }
    }
}
