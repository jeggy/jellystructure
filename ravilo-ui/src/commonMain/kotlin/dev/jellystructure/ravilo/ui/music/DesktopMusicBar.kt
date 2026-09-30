package dev.jellystructure.ravilo.ui.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.LocalCast
import dev.jellystructure.ravilo.ui.components.MusicGlyph
import dev.jellystructure.ravilo.ui.components.MusicIcon
import dev.jellystructure.ravilo.ui.components.PlayingBars
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.isMacPlatform
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.accentGradient

/** R337 (FR-R337-6) — how much of the content's foot the desktop bar takes, so the page can scroll clear of it. */
fun desktopMusicBarInset(): Dp = if (isMacPlatform) 62.dp + 16.dp * 2 else 72.dp

/**
 * R337 (FR-R337-6) — the desktop's player bar. **macOS:** a floating capsule at the content's foot (16 dp from its edges,
 * 62 dp tall, fully rounded). **GNOME:** a bar docked across the window (72 dp, a thin progress line along its top
 * edge) that also carries shuffle. Artwork · title/artist (opens Playing) · previous/play/next · seek with the times ·
 * lyrics · queue · *Play on…* · volume. At the medium width shuffle, lyrics and the volume slider go (the keys still
 * work). A book shows its title and chapter transport the same way.
 */
@Composable
fun DesktopMusicBar(
    medium: Boolean,
    queueOpen: Boolean,
    onOpenPlaying: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaviloTheme.colors
    val mac = isMacPlatform
    val st by MusicPlayback.state.collectAsState()
    val t = st.current
    val b = st.book
    if (t == null && b == null) return
    val title = t?.title ?: b?.detail?.title.orEmpty()
    val sub = t?.let { artistLine(it) } ?: b?.detail?.authors?.joinToString(", ") { it.name }.orEmpty()
    val image = t?.imageUrl ?: b?.detail?.coverUrl
    val live = rememberLivePosition()
    val cast = LocalCast.current
    val frame = if (mac) {
        modifier.padding(16.dp).fillMaxWidth().height(62.dp)
            .shadow(14.dp, RoundedCornerShape(31.dp))
            .clip(RoundedCornerShape(31.dp))
            .background(colors.surface.copy(alpha = 0.96f))
            .border(1.dp, colors.fg.copy(alpha = 0.08f), RoundedCornerShape(31.dp))
    } else {
        modifier.fillMaxWidth().height(72.dp).background(colors.surface)
    }
    Box(frame) {
        if (!mac) {
            val frac = if (st.durationMs > 0) (live.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(2.dp).background(colors.fg.copy(alpha = 0.10f))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(colors.accentGradient))
            }
        }
        Row(Modifier.fillMaxSize().padding(horizontal = if (mac) 10.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.widthIn(max = 280.dp).weight(1f, fill = false).tap(onOpenPlaying), verticalAlignment = Alignment.CenterVertically) {
                MusicCover(image, t?.album ?: title, Modifier.size(if (mac) 42.dp else 48.dp), corner = if (mac) 21.dp else 6.dp, requestedWidth = 120, wordmarkSize = 8)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(title, color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(12.dp))
            if (!mac && !medium && b == null) BarButton(MusicIcon.SHUFFLE, if (st.shuffle) colors.accent else colors.textSecondary) { MusicPlayback.toggleShuffle() }
            BarButton(if (b != null) MusicIcon.BACK30 else MusicIcon.PREVIOUS, colors.text) { if (b != null) MusicPlayback.skipBy(-30_000) else MusicPlayback.previous() }
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(colors.accentGradient).tap { MusicPlayback.togglePlay() }, contentAlignment = Alignment.Center) {
                if (st.buffering) PlayingBars(true, colors.onAccent, 16.dp)
                else MusicGlyph(if (st.playing) MusicIcon.PAUSE else MusicIcon.PLAY, colors.onAccent, 18.dp)
            }
            BarButton(if (b != null) MusicIcon.FWD30 else MusicIcon.NEXT, if (b != null || st.hasNext) colors.text else colors.textDim) { if (b != null) MusicPlayback.skipBy(30_000) else if (st.hasNext) MusicPlayback.next() }
            Spacer(Modifier.width(12.dp))
            SeekLine(live, st.durationMs, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            if (!medium) BarButton(MusicIcon.LYRICS, colors.textSecondary, onOpenPlaying)
            BarButton(MusicIcon.QUEUE, if (queueOpen) colors.accent else colors.textSecondary, onQueue)
            if (cast != null) BarButton(MusicIcon.MORE, colors.textSecondary) { cast.openSheet(music = true) }
            if (!medium) VolumeLine(Modifier.width(90.dp))
        }
    }
}

@Composable
private fun BarButton(icon: MusicIcon, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).tap(onClick), contentAlignment = Alignment.Center) { MusicGlyph(icon, tint, 18.dp) }
}

/** The seek line with its two times; a click or a drag seeks, the time following the thumb while it is held. */
@Composable
private fun SeekLine(positionMs: Long, durationMs: Long, modifier: Modifier) {
    val colors = RaviloTheme.colors
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val frac = dragFrac ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(fmtLen(if (dragFrac != null) (frac * durationMs).toLong() else positionMs), color = colors.textDim, fontSize = 11.sp, fontFamily = Sora)
        Spacer(Modifier.width(8.dp))
        BoxWithConstraints(Modifier.weight(1f).height(20.dp)) {
            val wPx = with(LocalDensity.current) { maxWidth.toPx() }
            val gradient = colors.accentGradient
            Canvas(Modifier.fillMaxSize().pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFrac = (o.x / wPx).coerceIn(0f, 1f) },
                    onDragEnd = { dragFrac?.let { MusicPlayback.seekTo((it * durationMs).toLong()) }; dragFrac = null },
                    onDragCancel = { dragFrac = null },
                    onHorizontalDrag = { ch, _ -> dragFrac = (ch.position.x / wPx).coerceIn(0f, 1f) },
                )
            }.pointerInput(durationMs) { detectTapGestures { o -> MusicPlayback.seekTo(((o.x / wPx).coerceIn(0f, 1f) * durationMs).toLong()) } }) {
                val y = size.height / 2
                val h = 3.dp.toPx()
                drawRoundRect(colors.fg.copy(alpha = 0.14f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2, h / 2))
                drawRoundRect(gradient, Offset(0f, y - h / 2), Size(size.width * frac, h), CornerRadius(h / 2, h / 2))
                if (dragFrac != null) drawCircle(colors.fg, 5.dp.toPx(), Offset(size.width * frac, y))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(fmtLen(durationMs), color = colors.textDim, fontSize = 11.sp, fontFamily = Sora)
    }
}

/** R337 (FR-R337-6/10) — the desktop's music volume, shared by the bar's slider and ⌘↑/⌘↓ (Ctrl on GNOME). */
object MusicVolume {
    private val _level = kotlinx.coroutines.flow.MutableStateFlow(1f)
    val level: kotlinx.coroutines.flow.StateFlow<Float> = _level
    fun set(v: Float) { _level.value = v.coerceIn(0f, 1f); MusicEngine.setUserVolume(_level.value) }
    fun nudge(delta: Float) = set(_level.value + delta)
}

/** The bar's volume: the desktop engine's own level, multiplied into the song's gain (R322's *even out volume*). */
@Composable
private fun VolumeLine(modifier: Modifier) {
    val colors = RaviloTheme.colors
    val level by MusicVolume.level.collectAsState()
    BoxWithConstraints(modifier.height(20.dp)) {
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }
        fun set(x: Float) = MusicVolume.set(x / wPx)
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectHorizontalDragGestures(onDragStart = { o -> set(o.x) }, onHorizontalDrag = { ch, _ -> set(ch.position.x) })
        }.pointerInput(Unit) { detectTapGestures { o -> set(o.x) } }) {
            val y = size.height / 2
            val h = 3.dp.toPx()
            drawRoundRect(colors.fg.copy(alpha = 0.14f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2, h / 2))
            drawRoundRect(colors.textSecondary, Offset(0f, y - h / 2), Size(size.width * level, h), CornerRadius(h / 2, h / 2))
            drawCircle(colors.text, 5.dp.toPx(), Offset(size.width * level, y))
        }
    }
}

/**
 * R337 (FR-R337-8) — the queue as a panel: 300 dp, pushing the content at 1200 dp and wider, over it (with a shadow)
 * from 600 to 1199 dp; the phone's Queue page below. Drag to reorder and remove work as they do on the page.
 */
@Composable
fun DesktopQueuePanel(overlay: Boolean, onTrackMore: (dev.jellystructure.shared.tv.MusicTrackItem) -> Unit, modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    Box(
        modifier.width(300.dp).fillMaxHeight()
            .then(if (overlay) Modifier.shadow(18.dp) else Modifier)
            .background(colors.surface)
            .border(1.dp, colors.fg.copy(alpha = 0.06f)),
    ) {
        MusicQueueScreen(onTrackMore = onTrackMore, onProfile = {}, showAppBar = false)
    }
}
