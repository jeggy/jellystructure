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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.DeskIcon
import dev.jellystructure.ravilo.ui.components.LocalCast
import dev.jellystructure.ravilo.ui.components.MusicGlyph
import dev.jellystructure.ravilo.ui.components.MusicIcon
import dev.jellystructure.ravilo.ui.components.PlayingBars
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.isMacPlatform
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SystemUiFont
import dev.jellystructure.ravilo.ui.theme.accentGradient

/** R337 (FR-R337-6) — how much of the content's foot the desktop bar takes, so the page can scroll clear of it. */
fun desktopMusicBarInset(): Dp = if (isMacPlatform) 62.dp + 14.dp + 12.dp else 72.dp

/**
 * R337 (FR-R337-6) — the desktop's player bar, drawn to `.cap-bar` / `.gbar` in `design/ravilo/desktop-directions.css`.
 * **macOS:** a floating glass capsule at the content's foot (16 dp from its sides, 14 from its foot, 62 dp tall, fully
 * rounded). **GNOME:** a bar docked across the window (72 dp, a thin progress line along its top edge) that also
 * carries shuffle. Artwork · title/artist (opens Playing) · previous/play/next · seek with the times · lyrics · queue ·
 * *Play on…* · volume. At the medium width shuffle, lyrics and the volume slider go (the keys still work). A book
 * shows its title and its ±30 s transport the same way.
 */
@Composable
fun DesktopMusicBar(
    medium: Boolean,
    queueOpen: Boolean,
    onOpenPlaying: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
    /** The queue is open over the content (600–1199 dp): the capsule shortens to stay clear of it (`.qopen .cap-bar`). */
    queueOverlay: Boolean = false,
) {
    val colors = RaviloTheme.colors
    val mac = isMacPlatform
    val st by MusicPlayback.state.collectAsState()
    val t = st.current
    val b = st.book
    if (t == null && b == null) return
    val title = t?.title ?: b?.detail?.title.orEmpty()
    // `.pb-t .s` — the artist, and where it plays when that is not this computer ("Harbour Lights · Stue").
    val castDevice = if (MusicCast.linked.collectAsState().value) MusicCast.deviceName.collectAsState().value else null
    val sub = listOfNotNull(t?.let { artistLine(it) } ?: b?.detail?.authors?.joinToString(", ") { it.name }, castDevice).filter { it.isNotBlank() }.joinToString(" · ")
    val image = t?.imageUrl ?: b?.detail?.coverUrl
    val live = rememberLivePosition()
    val cast = LocalCast.current
    val castLink = cast?.sender?.link?.collectAsState()?.value
    val casting = castLink != null && castLink != dev.jellystructure.ravilo.ui.seams.CastLinkState.NONE
    // Graphite's one blue carries play; every other theme's play button is ink on the page's colour.
    val graphite = dev.jellystructure.ravilo.ui.theme.LocalRaviloTheme.current == dev.jellystructure.ravilo.ui.theme.ThemeId.GRAPHITE
    val playBg = if (graphite) colors.accent else colors.text
    val playInk = if (graphite) androidx.compose.ui.graphics.Color.White else colors.background
    val capsule = RoundedCornerShape(31.dp)
    // `.cap-bar.cmp` — with the queue beside it, or in a window just past the rail's width, the bar is the medium one.
    val contentWidth = dev.jellystructure.ravilo.ui.theme.LocalWindowWidth.current -
        (if (medium) 84.dp else 262.dp) - (if (queueOpen && !queueOverlay) 300.dp else 0.dp)
    val medium = medium || contentWidth < 820.dp
    // Narrower still (the queue lying over a window just past the rail's width): the title gives way and the seek line
    // loses its two times, so nothing is ever squeezed into a column of digits.
    val tight = contentWidth - (if (queueOverlay) 310.dp else 0.dp) < 560.dp
    val frame = if (mac) {
        modifier.padding(start = 16.dp, end = if (queueOverlay) 326.dp else 16.dp, bottom = 14.dp).fillMaxWidth().height(62.dp)
            .shadow(20.dp, capsule)
            .clip(capsule)
            // The mockup's glass is 72 % of this colour over a blur; nothing is blurred here, so it is drawn denser.
            .background(if (colors.isLight) androidx.compose.ui.graphics.Color(0xF5FAFAFC) else colors.surface.copy(alpha = 0.95f))
            .border(1.dp, colors.fg.copy(alpha = if (colors.isLight) 0.08f else 0.13f), capsule)
    } else {
        modifier.fillMaxWidth().height(72.dp).background(colors.card)
    }
    Box(frame) {
        if (!mac) {
            val frac = if (st.durationMs > 0) (live.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(3.dp).background(colors.fg.copy(alpha = 0.08f))) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(colors.accentSecondary))
            }
        }
        Row(
            Modifier.fillMaxSize().padding(start = if (mac) 8.dp else 12.dp, end = if (mac) 18.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (mac) 14.dp else 16.dp),
        ) {
            Row(Modifier.tap(onOpenPlaying), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (mac) 14.dp else 16.dp)) {
                MusicCover(image, t?.album ?: title, Modifier.size(if (mac) 46.dp else 50.dp), corner = if (mac) 23.dp else 6.dp, requestedWidth = 120, wordmarkSize = 8)
                Column(Modifier.width(if (tight) 96.dp else if (medium) 130.dp else 190.dp)) {
                    Text(title, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = SystemUiFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(sub, color = colors.textSecondary, fontSize = 12.sp, fontFamily = SystemUiFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!mac && !medium && b == null) BarButton(DeskIcon.SHUFFLE, if (st.shuffle) colors.accentSecondary else colors.text, str("music.shuffle")) { MusicPlayback.toggleShuffle() }
                if (b != null) BookSkip(MusicIcon.BACK30, colors.text) { MusicPlayback.skipBy(-30_000) }
                else BarButton(DeskIcon.PREVIOUS, colors.text, str("desk.key_prev")) { MusicPlayback.previous() }
                Box(Modifier.size(36.dp).clip(CircleShape).background(playBg).tap { MusicPlayback.togglePlay() }, contentAlignment = Alignment.Center) {
                    if (st.buffering) PlayingBars(true, playInk, 14.dp)
                    else DeskIcon(if (st.playing) DeskIcon.PAUSE else DeskIcon.PLAY, playInk, 16.dp)
                }
                if (b != null) BookSkip(MusicIcon.FWD30, colors.text) { MusicPlayback.skipBy(30_000) }
                else BarButton(DeskIcon.NEXT, if (st.hasNext) colors.text else colors.textDim, str("desk.key_next"), enabled = st.hasNext) { MusicPlayback.next() }
            }
            SeekLine(live, st.durationMs, Modifier.weight(1f), times = !tight)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!medium) BarButton(DeskIcon.LYRICS, colors.textSecondary, str("desk.show_lyrics"), onClick = onOpenPlaying)
                BarButton(DeskIcon.QUEUE, if (queueOpen) colors.accentSecondary else colors.textSecondary, str(if (queueOpen) "desk.close_queue" else "desk.show_queue"), on = queueOpen, onClick = onQueue)
                if (cast != null) BarButton(DeskIcon.CAST, if (casting) colors.accentSecondary else colors.textSecondary, str("cast.sheet_music"), on = casting) { cast.openSheet(music = true) }
                if (!medium) {
                    DeskIcon(DeskIcon.VOLUME, colors.textSecondary, 18.dp)
                    VolumeLine(Modifier.padding(start = 2.dp).width(70.dp))
                }
            }
        }
    }
}

/** `.pb-ctl .b` / `.pb-r .b` — a 30 dp button; a lit one (the queue while it is open) sits on a faint plate. */
@Composable
private fun BarButton(icon: DeskIcon, tint: androidx.compose.ui.graphics.Color, label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    Box(
        Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).then(if (on) Modifier.background(colors.fg.copy(alpha = 0.07f)) else Modifier)
            .then(if (enabled) Modifier.tap(onClick) else Modifier)   // a dimmed button takes no click and shows no hand
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { DeskIcon(icon, tint, 18.dp) }
}

/** A book's ±30 s, in the listening mode's own glyphs (the mockup's bar is drawn for a song). */
@Composable
private fun BookSkip(icon: MusicIcon, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).tap(onClick), contentAlignment = Alignment.Center) { MusicGlyph(icon, tint, 18.dp) }
}

/** The seek line with its two times (`.pb-seek`); a click or a drag seeks, the time following the pointer while it is held. */
@Composable
private fun SeekLine(positionMs: Long, durationMs: Long, modifier: Modifier, times: Boolean = true) {
    val colors = RaviloTheme.colors
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val frac = dragFrac ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (times) Text(fmtLen(if (dragFrac != null) (frac * durationMs).toLong() else positionMs), color = colors.textDim, fontSize = 11.5.sp, fontFamily = SystemUiFont, maxLines = 1, softWrap = false)
        BoxWithConstraints(Modifier.weight(1f).height(20.dp)) {
            val wPx = with(LocalDensity.current) { maxWidth.toPx() }
            Canvas(Modifier.fillMaxSize().pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFrac = (o.x / wPx).coerceIn(0f, 1f) },
                    onDragEnd = { dragFrac?.let { MusicPlayback.seekTo((it * durationMs).toLong()) }; dragFrac = null },
                    onDragCancel = { dragFrac = null },
                    onHorizontalDrag = { ch, _ -> dragFrac = (ch.position.x / wPx).coerceIn(0f, 1f) },
                )
            }.pointerInput(durationMs) { detectTapGestures { o -> MusicPlayback.seekTo(((o.x / wPx).coerceIn(0f, 1f) * durationMs).toLong()) } }) {
                val y = size.height / 2
                val h = 4.dp.toPx()
                drawRoundRect(colors.fg.copy(alpha = 0.16f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2, h / 2))
                drawRoundRect(colors.text, Offset(0f, y - h / 2), Size(size.width * frac, h), CornerRadius(h / 2, h / 2))
                if (dragFrac != null) drawCircle(colors.text, 6.dp.toPx(), Offset(size.width * frac, y))
            }
        }
        if (times) Text(fmtLen(durationMs), color = colors.textDim, fontSize = 11.5.sp, fontFamily = SystemUiFont, maxLines = 1, softWrap = false)
    }
}

/**
 * R337 (FR-R337-6/10) — the desktop's music volume, shared by the bar's slider and ⌘↑/⌘↓ (Ctrl on GNOME). On this
 * computer it is the engine's own level, remembered between launches; **while a speaker or TV plays it is that
 * device's volume** — the slider shows what the device reports and moves it, and the computer's own level is left as
 * it was for when the music comes back.
 */
object MusicVolume {
    private val _level = kotlinx.coroutines.flow.MutableStateFlow(
        runCatching { MusicDeviceStore.get("desk_volume")?.toFloatOrNull() }.getOrNull()?.coerceIn(0f, 1f) ?: 1f,
    )
    val level: kotlinx.coroutines.flow.StateFlow<Float> = _level
    private var applied = false
    /**
     * The test driver's `quiet`: the engine plays at nothing while the level the viewer set — shown, and remembered —
     * stays theirs. A build checked on someone's computer must not be heard, and must not leave their volume at zero.
     */
    @kotlin.concurrent.Volatile var silenced = false
        set(value) { field = value; MusicEngine.setUserVolume(if (value) 0f else _level.value) }
    /** The remembered level reaches the engine once, before the first song. */
    fun applyRemembered() { if (!applied) { applied = true; MusicEngine.setUserVolume(if (silenced) 0f else _level.value) } }
    fun set(v: Float) {
        val to = v.coerceIn(0f, 1f)
        if (MusicCast.linked.value) { MusicCast.setVolume(to.toDouble()); return }
        _level.value = to
        MusicEngine.setUserVolume(if (silenced) 0f else to)
        runCatching { MusicDeviceStore.put("desk_volume", to.toString()) }
    }
    fun nudge(delta: Float) = set((if (MusicCast.linked.value) (MusicCast.deviceVolume?.value?.toFloat() ?: 0.3f) else _level.value) + delta)
}

/** The bar's volume (`.vol`): the desktop engine's own level, multiplied into the song's gain (R322's *even out volume*). */
@Composable
private fun VolumeLine(modifier: Modifier) {
    val colors = RaviloTheme.colors
    val own by MusicVolume.level.collectAsState()
    // While casting the slider is the device's: what it reports (or, until it has, a guess it is quiet).
    val casting by MusicCast.linked.collectAsState()
    val device = MusicCast.deviceVolume?.collectAsState()?.value
    val level = if (casting) (device?.toFloat() ?: 0f) else own
    LaunchedEffect(Unit) { MusicVolume.applyRemembered() }
    var held by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.height(20.dp)) {
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }
        fun set(x: Float) = MusicVolume.set(x / wPx)
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectHorizontalDragGestures(onDragStart = { o -> held = true; set(o.x) }, onDragEnd = { held = false }, onDragCancel = { held = false }, onHorizontalDrag = { ch, _ -> set(ch.position.x) })
        }.pointerInput(Unit) { detectTapGestures { o -> set(o.x) } }) {
            val y = size.height / 2
            val h = 4.dp.toPx()
            drawRoundRect(colors.fg.copy(alpha = 0.16f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2, h / 2))
            drawRoundRect(colors.textSecondary, Offset(0f, y - h / 2), Size(size.width * level, h), CornerRadius(h / 2, h / 2))
            if (held) drawCircle(colors.text, 6.dp.toPx(), Offset(size.width * level, y))
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
    // `.qp.ov` — over the content the Mac's panel is a sheet of its own: clear of the toolbar, 10 dp from the window's
    // edge, 14 dp corners; GNOME's is the split view's end pane, flush. Docked (≥ 1200 dp) it is a plain side pane.
    val sheet = overlay && isMacPlatform
    val shape = RoundedCornerShape(if (sheet) 14.dp else 0.dp)
    Box(
        modifier.then(if (sheet) Modifier.padding(top = 58.dp, end = 10.dp, bottom = 10.dp) else Modifier)
            .width(300.dp).fillMaxHeight()
            .then(if (overlay) Modifier.shadow(24.dp, shape) else Modifier)
            .clip(shape)
            .background(colors.card)
            .then(if (sheet) Modifier.border(1.dp, colors.fg.copy(alpha = 0.16f), shape) else Modifier),
    ) {
        if (!sheet) Box(Modifier.width(1.dp).fillMaxHeight().background(colors.fg.copy(alpha = 0.09f)))   // `.qp { border-left }`
        MusicQueueScreen(onTrackMore = onTrackMore, onProfile = {}, showAppBar = false)
    }
}
