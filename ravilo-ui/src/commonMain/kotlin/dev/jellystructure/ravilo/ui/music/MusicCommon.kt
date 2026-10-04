package dev.jellystructure.ravilo.ui.music

import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.isDesktopLayout
import dev.jellystructure.ravilo.ui.components.deskHover
import dev.jellystructure.ravilo.ui.components.handCursor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.shared.tv.MusicAlbumCard
import dev.jellystructure.shared.tv.MusicArtistCard
import dev.jellystructure.shared.tv.MusicTrackItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ── loading ──

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data object Failed : Load<Nothing>
}

/** One fetch, kept across navigation (R40's retained-store idiom): Back shows what was there and refreshes quietly. */
class MusicLoader<T>(private val fetch: suspend () -> T) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<Load<T>>(Load.Loading)
    val state: StateFlow<Load<T>> = _state
    /** R345 — why the last fetch failed, so a failed page can say *couldn't reach the server* rather than *no music*. */
    var lastFailure: Throwable? = null
        private set
    fun load() {
        scope.launch {
            runCatching { fetch() }
                .onSuccess { lastFailure = null; _state.value = Load.Ready(it) }
                .onFailure { lastFailure = it; if (_state.value !is Load.Ready) _state.value = Load.Failed }
        }
    }
}

// ── words ──

/** `3:59`. */
fun fmtLen(ms: Long?): String {
    val s = ((ms ?: 0L) + 500) / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
    return (if (h > 0) "$h:${m.toString().padStart(2, '0')}" else "$m") + ":" + x.toString().padStart(2, '0')
}

@Composable
fun fmtTotal(ms: Long): String {
    val m = ((ms + 30_000) / 60_000).toInt()
    return if (m >= 60) str("music.hours_minutes", mapOf("h" to (m / 60).toString(), "m" to (m % 60).toString()))
    else str("music.minutes", mapOf("n" to m.toString()))
}

@Composable
fun songsCount(n: Int): String = if (n == 1) str("music.songs_one") else str("music.songs_n", mapOf("n" to n.toString()))

@Composable
fun albumsCount(n: Int): String = if (n == 1) str("music.albums_one") else str("music.albums_n", mapOf("n" to n.toString()))

/** FR-R321-7 — the type badge, shown only when an album is not an album. */
@Composable
fun musicTypeLabel(type: String): String? = when (type) {
    "live" -> str("music.type.live"); "compilation" -> str("music.type.compilation"); "single" -> str("music.type.single")
    "ep" -> str("music.type.ep"); "soundtrack" -> str("music.type.soundtrack"); else -> null
}

fun artistLine(t: MusicTrackItem): String = t.artists.joinToString(", ") { it.name }

// ── covers ──

private fun wordmarkBrush(title: String): Brush {
    var h = 0L
    for (c in title) h = (h * 31 + c.code) and 0xFFFFFFFFL
    val hue = (h % 360).toFloat()
    return Brush.linearGradient(listOf(Color.hsl(hue, 0.46f, 0.36f), Color.hsl((hue + 40f) % 360f, 0.52f, 0.14f)))
}

/**
 * 277 FR-277-3 — a cover, or the title set as a wordmark on a gradient: there is no "no cover" state on the viewer's
 * side. Noir keeps the corners squarer (FR-R321-12) through the skin's own radius.
 */
@Composable
fun MusicCover(url: String?, title: String, modifier: Modifier = Modifier, corner: Dp? = null, requestedWidth: Int = 360, wordmarkSize: Int = 15) {
    val r = corner ?: RaviloTheme.colors.tileRadius
    Box(modifier.clip(RoundedCornerShape(r)).background(wordmarkBrush(title))) {
        if (url != null) RemoteImage(url, title, Modifier.fillMaxSize(), requestedWidth = requestedWidth)
        else Text(
            title, color = Color.White, fontSize = wordmarkSize.sp, lineHeight = (wordmarkSize * 1.15f).sp, fontWeight = FontWeight.Bold,
            fontFamily = SpaceGrotesk, maxLines = 4, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
        )
    }
}

@Composable
fun ArtistCircle(url: String?, name: String, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(wordmarkBrush(name)), contentAlignment = Alignment.Center) {
        if (url != null) RemoteImage(url, name, Modifier.fillMaxSize(), requestedWidth = 320)
        else Text(initials(name), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
    }
}

private fun initials(name: String) = name.split(' ').map { w -> w.filter { it.isLetter() } }.filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase()

// ── rows and cards ──

@Composable
fun MusicSectionHeader(title: String, count: String? = null, onSeeAll: (() -> Unit)? = null) {
    val colors = RaviloTheme.colors
    val desk = isDesktopLayout   // R337 — `.rh` in design/ravilo/desktop-directions.css
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = if (desk) 12.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = colors.text, fontSize = if (desk) 18.sp else 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
        if (count != null) { Spacer(Modifier.width(8.dp)); Text(count, color = colors.textDim, fontSize = 12.sp, fontFamily = Sora) }
        Spacer(Modifier.weight(1f))
        if (onSeeAll != null) Text(
            str("music.see_all"), color = if (desk) colors.textDim else colors.accentSecondary, fontSize = if (desk) 12.5.sp else 13.sp,
            fontWeight = if (desk) FontWeight.Normal else FontWeight.SemiBold, fontFamily = Sora,
            modifier = Modifier.tap(onSeeAll).padding(vertical = 6.dp, horizontal = 2.dp),
        )
    }
}

/** R337 — a music page's title on the desktop (`.h1`): the sidebar names the page, the page says it large. */
@Composable
fun DeskPageTitle(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = raviloHPad).padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = RaviloTheme.colors.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, letterSpacing = (-0.6).sp, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun AlbumCardView(a: MusicAlbumCard, width: Dp, onOpen: () -> Unit, showYear: Boolean = false) {
    val colors = RaviloTheme.colors
    Column(Modifier.width(width).tap(onOpen)) {
        MusicCover(a.imageUrl, a.title, Modifier.size(width))
        Spacer(Modifier.height(7.dp))
        Text(a.title, color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(a.artists.joinToString(" & ") { it.name }.ifBlank { null }, if (showYear) a.year?.toString() else null).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, color = if (isDesktopLayout) colors.textDim else colors.textSecondary, fontSize = if (isDesktopLayout) 12.sp else 12.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ArtistCardView(r: MusicArtistCard, width: Dp, onOpen: () -> Unit) {
    val colors = RaviloTheme.colors
    Column(Modifier.width(width).tap(onOpen), horizontalAlignment = Alignment.CenterHorizontally) {
        ArtistCircle(r.imageUrl, r.name, Modifier.size(width))
        Spacer(Modifier.height(7.dp))
        Text(r.name, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (r.albumCount > 0) Text(albumsCount(r.albumCount), color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora, maxLines = 1)
    }
}

/**
 * A song as every list draws it (FR-R321-7): number (or the cover in a mixed list), title (+ *feat.* / the credited
 * artist), a lyrics mark when there are lyrics, length, and ⋯. The song that is playing shows the bars in place of
 * its number.
 */
@Composable
fun TrackRow(
    t: MusicTrackItem,
    onPlay: () -> Unit,
    onMore: () -> Unit,
    number: String? = null,
    showCover: Boolean = false,
    subtitle: String? = null,
    /** R337 — the desktop's track list is striped (`.trk .tr:nth-child(odd)`); true for every other row. */
    striped: Boolean = false,
    /** R373 (FR-R373-5) — *Bonus* on this row when the song is one; false on an album's own extras (the divider says it). */
    showBonus: Boolean = true,
) {
    val colors = RaviloTheme.colors
    val st by MusicPlayback.state.collectAsState()
    val isCurrent = st.current?.id == t.id
    // R337 — the desktop's row (`.trk .tr`): 38 dp, 13 sp, 8 dp corners, the number in a 34 dp column.
    val desk = isDesktopLayout
    val frame = if (desk) {
        Modifier.fillMaxWidth().heightIn(min = if (showCover) 44.dp else 38.dp).clip(RoundedCornerShape(8.dp))
            .then(if (striped) Modifier.background(colors.fg.copy(alpha = 0.025f)) else Modifier).deskHover().tap(onPlay).padding(start = 10.dp)
    } else Modifier.fillMaxWidth().heightIn(min = 54.dp).tap(onPlay).padding(vertical = 4.dp)
    Row(frame, verticalAlignment = Alignment.CenterVertically) {
        val lead = if (desk) 24.dp else 28.dp
        when {
            showCover -> MusicCover(t.imageUrl, t.album ?: t.title, Modifier.size(if (desk) 32.dp else 44.dp), corner = if (desk) 5.dp else 6.dp, requestedWidth = 120, wordmarkSize = if (desk) 7 else 9)
            isCurrent -> Box(Modifier.width(lead), contentAlignment = Alignment.CenterStart) { PlayingBars(st.playing, colors.accentSecondary, if (desk) 13.dp else 16.dp) }
            else -> Text(number ?: "", color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, modifier = Modifier.width(lead))
        }
        if (showCover && isCurrent) { Spacer(Modifier.width(8.dp)); PlayingBars(st.playing, colors.accentSecondary, if (desk) 12.dp else 14.dp) }
        Spacer(Modifier.width(if (desk) 10.dp else 12.dp))
        val bonus = showBonus && t.extra
        Column(Modifier.weight(1f)) {
            // R373 (FR-R373-6, amends R344/R352) — the version chips and *Bonus* sit at the row's right, just left of the
            // length: on a phone at the right end of the title area (the chips fold first, then the title ellipsizes;
            // *Bonus* is never cut); on the desktop in a column of their own before the length.
            val titleText: @Composable () -> Unit = {
                Text(
                    t.title, color = if (isCurrent) colors.accentSecondary else colors.text, fontSize = if (desk) 13.sp else 14.5.sp,
                    fontWeight = if (desk && !isCurrent) FontWeight.Normal else if (desk) FontWeight.SemiBold else FontWeight.Medium,
                    fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (desk) titleText() else TitleThenChips(t.versions, bonus, Modifier.fillMaxWidth(), gap = 7.dp, title = titleText)
            val sub = subtitle ?: artistLine(t)
            if (sub.isNotBlank()) Text(sub, color = if (desk) colors.textDim else colors.textSecondary, fontSize = if (desk) 11.5.sp else 12.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // R373 (FR-R373-6) — the desktop's chip column: a fixed width so the columns line up down the list.
        if (desk) Box(Modifier.width(DESK_CHIP_COLUMN), contentAlignment = Alignment.CenterEnd) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                VersionChips(t.versions)
                if (bonus) BonusChip()
            }
        }
        // R352 (FR-R352-2) — a gap between the title column (its chips can reach its end) and the lyrics mark or length.
        Spacer(Modifier.width(8.dp))
        if (t.hasLyrics) { MusicGlyph(MusicIcon.LYRICS, colors.textDim, if (desk) 13.dp else 15.dp); Spacer(Modifier.width(8.dp)) }
        Text(fmtLen(t.durationMs), color = colors.textDim, fontSize = if (desk) 12.sp else 12.5.sp, fontFamily = Sora)
        Box(Modifier.size(if (desk) 34.dp else 40.dp).tap(onMore), contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.MORE, colors.textSecondary, if (desk) 16.dp else 18.dp, description = str("music.more")) }
    }
}

/** R373 (FR-R373-6) — the desktop's version-chip column on a song row (three chips and *Bonus*). */
val DESK_CHIP_COLUMN = 188.dp

/** A plain tap target without a ripple (the app's idiom on the phone); under a mouse it shows the hand ([handCursor]). */
@Composable
fun Modifier.tap(onClick: () -> Unit): Modifier = this.handCursor().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

@Composable
fun PillButton(label: String, icon: MusicIcon?, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    if (isDesktopLayout) {
        // R337 — the desktop's button (`.bt` / `.bt.pri`): 38 dp, 10 dp corners, the primary one ink on the page's colour.
        val ink = if (primary) colors.background else colors.text
        Row(
            modifier.height(38.dp).clip(RoundedCornerShape(10.dp)).background(if (primary) colors.text else colors.fg.copy(alpha = 0.10f)).tap(onClick).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) { MusicGlyph(icon, ink, 15.dp); Spacer(Modifier.width(8.dp)) }
            Text(label, color = ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1)
        }
        return
    }
    val bg = if (primary) Modifier.background(colors.accentGradient, RoundedCornerShape(24.dp)) else Modifier.background(colors.surfaceVariant, RoundedCornerShape(24.dp))
    Row(
        modifier.heightIn(min = 46.dp).then(bg).tap(onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { MusicGlyph(icon, if (primary) colors.onAccent else colors.text, 17.dp); Spacer(Modifier.width(8.dp)) }
        Text(label, color = if (primary) colors.onAccent else colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
    }
}

/** A phone's two buttons share the row's width; a computer's keep their own (R337). */
@Composable
fun androidx.compose.foundation.layout.RowScope.pillWidth(): Modifier = if (isDesktopLayout) Modifier else Modifier.weight(1f)

// ── the track sheet (FR-R322-11) ──

data class TrackSheetRequest(val track: MusicTrackItem)

/** ⋯ on a song: Play next · Add to queue · Add to playlist… (phase 2) · Go to album · Go to artist · My List. */
@Composable
fun TrackActionsSheet(
    request: TrackSheetRequest?,
    onDismiss: () -> Unit,
    onGoAlbum: (String) -> Unit,
    onGoArtist: (String) -> Unit,
    onFavorite: (MusicTrackItem, Boolean) -> Unit,
    /** R373 (FR-R373-4) — the other copies of the song (`GET /tv/music/track/{id}/copies`); null = not offered. */
    loadCopies: (suspend (String) -> dev.jellystructure.shared.tv.MusicTrackCopies?)? = null,
) {
    val t = request?.track
    val msgNext = str("music.queued_next")
    val msgQueued = str("music.queued")
    HandsetSheet(visible = t != null, onDismiss = onDismiss) {
        if (t == null) return@HandsetSheet
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MusicCover(t.imageUrl, t.album ?: t.title, Modifier.size(52.dp), corner = 6.dp, requestedWidth = 140, wordmarkSize = 9)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = RaviloTheme.colors.fg, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(artistLine(t).ifBlank { null }, t.album).joinToString(" — "), color = RaviloTheme.colors.fg.copy(0.65f), fontSize = 13.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(10.dp))
            CastBlock(t, onDismiss)
            SheetRow(str("music.play_next"), MusicIcon.NEXT) { MusicPlayback.playNext(t); MusicToasts.show(msgNext); onDismiss() }
            SheetRow(str("music.add_queue"), MusicIcon.QUEUE) { MusicPlayback.addToQueue(t); MusicToasts.show(msgQueued); onDismiss() }
            SheetRow(str("music.add_playlist"), MusicIcon.LISTEN, dim = true, trailing = str("music.phase2")) { onDismiss() }
            t.albumId?.let { id -> SheetRow(str("music.go_album"), MusicIcon.BROWSE) { onDismiss(); onGoAlbum(id) } }
            t.artists.firstOrNull()?.let { a -> SheetRow(str("music.go_artist"), MusicIcon.LISTEN) { onDismiss(); onGoArtist(a.id) } }
            val favs by MusicFavorites.overrides.collectAsState()
            val fav = favs[t.id] ?: t.favorite
            SheetRow(str("nav.my_list"), if (fav) MusicIcon.HEART_FILLED else MusicIcon.HEART) { onFavorite(t, !fav); onDismiss() }
            // R373 (FR-R373-4) — the sheet ends with *Also on {n} releases* and one row per other copy.
            if (t.alsoOn > 0 && loadCopies != null) AlsoOnSection(t, loadCopies) { id -> onDismiss(); onGoAlbum(id) }
        }
    }
}

/** R373 (FR-R373-4) — *Also on {n} releases*: one row per other copy (cover · title · *{kind} · {year}*), each opening
 *  its release. Asked for on open; a failed call leaves the rest of the sheet as it is. */
@Composable
fun AlsoOnSection(t: MusicTrackItem, loadCopies: suspend (String) -> dev.jellystructure.shared.tv.MusicTrackCopies?, onOpen: (String) -> Unit) {
    val colors = RaviloTheme.colors
    var copies by remember(t.id) { mutableStateOf<List<dev.jellystructure.shared.tv.MusicTrackCopy>?>(null) }
    LaunchedEffect(t.id) { copies = runCatching { loadCopies(t.id) }.getOrNull()?.copies }
    val list = copies ?: return
    if (list.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.fg.copy(0.1f)))
        Text(if (list.size == 1) str("ed.also_on_one") else str("ed.also_on", mapOf("n" to list.size.toString())),
            color = colors.fg.copy(0.5f), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 1.sp, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        for (c in list) {
            val al = c.album
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).then(if (al != null) Modifier.tap { onOpen(al.id) } else Modifier), verticalAlignment = Alignment.CenterVertically) {
                MusicCover(al?.imageUrl ?: c.track.imageUrl, al?.title ?: c.track.title, Modifier.size(40.dp), corner = 5.dp, requestedWidth = 120, wordmarkSize = 7)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(al?.title ?: c.track.title, color = colors.fg, fontSize = 15.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(al?.type?.let { musicTypeLabel(it) ?: str("music.type.album") }, al?.year?.toString()).joinToString(" · ")
                    if (sub.isNotEmpty()) Text(sub, color = colors.fg.copy(0.6f), fontSize = 12.5.sp, fontFamily = Sora, maxLines = 1)
                }
            }
        }
    }
}

/**
 * R324 (FR-R324-5) — while a speaker plays, a block titled with the device above the song's own entries: a volume
 * slider in 5 % steps · *Lyrics on {device}* (displays only; disabled when the song has no timed lyrics) · *Play on
 * this phone* · *Stop casting* in the warning colour.
 */
@Composable
private fun CastBlock(t: MusicTrackItem, onDismiss: () -> Unit) {
    val linked by MusicCast.linked.collectAsState()
    val device by MusicCast.deviceName.collectAsState()
    val st by MusicCast.status.collectAsState()
    val name = device
    if (!linked || name == null) return
    val colors = RaviloTheme.colors
    // R353 (FR-R353-4) — the slider starts where the device's volume IS and follows it (the volume keys move it too); it
    // used to start at 50 % whatever the speaker was at, so the first touch could jump a quiet room to half volume.
    val deviceVolume = MusicCast.deviceVolume?.collectAsState()?.value
    var volume by remember { mutableStateOf(deviceVolume?.toFloat() ?: 0.5f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(deviceVolume) { if (deviceVolume != null && !dragging) volume = deviceVolume.toFloat() }
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(name.uppercase(), color = RaviloTheme.colors.fg.copy(0.5f), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 1.sp, modifier = Modifier.padding(vertical = 6.dp))
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(str("cast.volume"), color = RaviloTheme.colors.fg, fontSize = 15.sp, fontFamily = Sora, modifier = Modifier.width(88.dp))
            androidx.compose.material3.Slider(
                value = volume, onValueChange = { v -> dragging = true; volume = (v * 20).roundToInt() / 20f }, onValueChangeFinished = { dragging = false; MusicCast.setVolume(volume.toDouble()) },
                steps = 19, modifier = Modifier.weight(1f),
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = RaviloTheme.colors.fg, activeTrackColor = colors.accentSecondary, inactiveTrackColor = RaviloTheme.colors.fg.copy(0.2f), activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            )
        }
        val lyricsOn = st?.lyricsOn
        if (lyricsOn != null) {
            val can = t.hasLyrics
            SheetRow(str("cast.lyrics_on", mapOf("device" to name)), MusicIcon.LYRICS, dim = !can, trailing = if (!can) str("cast.no_timed_lyrics") else if (lyricsOn) str("on") else str("off")) {
                if (can) MusicCast.setLyrics(!lyricsOn)
            }
        }
        SheetRow(str(dev.jellystructure.ravilo.ui.seams.CastPlatform.playHereKey), MusicIcon.PLAY) { onDismiss(); MusicCast.playHere() }
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).tap { onDismiss(); MusicCast.stop() }, verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(34.dp))
            Text(str("cast.stop"), color = Color(0xFFFF9B8A), fontSize = 15.sp, fontFamily = Sora)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(RaviloTheme.colors.fg.copy(0.1f)))
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SheetRow(label: String, icon: MusicIcon, dim: Boolean = false, trailing: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 50.dp).tap(onClick), verticalAlignment = Alignment.CenterVertically) {
        MusicGlyph(icon, RaviloTheme.colors.fg.copy(if (dim) 0.4f else 0.85f), 20.dp)
        Spacer(Modifier.width(14.dp))
        Text(label, color = RaviloTheme.colors.fg.copy(if (dim) 0.45f else 1f), fontSize = 15.sp, fontFamily = Sora, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = RaviloTheme.colors.fg.copy(0.4f), fontSize = 12.sp, fontFamily = Sora)
    }
}

// ── My List (FR-R321-9) ──

/** What the viewer changed since the lists were fetched, so a heart answers at once everywhere it is drawn. */
object MusicFavorites {
    private val _overrides = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val overrides: StateFlow<Map<String, Boolean>> = _overrides
    fun set(id: String, favorite: Boolean) { _overrides.value = _overrides.value + (id to favorite) }
    fun of(t: MusicTrackItem): Boolean = _overrides.value[t.id] ?: t.favorite
}

// ── toasts ──

/** A short confirmation over the page (*Plays next*, *Repeat one*…), above the mini bar and the bottom bar. */
object MusicToasts {
    private val _msg = MutableStateFlow<Pair<String, Long>?>(null)
    val message: StateFlow<Pair<String, Long>?> = _msg
    /** R324 (FR-R324-7) — an action beside the text (*Still playing on Stue* · **Stop**); the toast then stays 5 s. */
    private val _action = MutableStateFlow<Pair<String, () -> Unit>?>(null)
    val action: StateFlow<Pair<String, () -> Unit>?> = _action
    private var n = 0L
    fun show(text: String, action: Pair<String, () -> Unit>? = null) { _action.value = action; _msg.value = text to ++n }
    fun hide(id: Long) { if (_msg.value?.second == id) { _msg.value = null; _action.value = null } }
}

@Composable
fun MusicToastHost(bottomInset: Dp) {
    val m by MusicToasts.message.collectAsState()
    val action by MusicToasts.action.collectAsState()
    LaunchedEffect(m?.second) { m?.let { delay(if (action != null) 5_000 else 2200); MusicToasts.hide(it.second) } }
    Box(Modifier.fillMaxSize().padding(bottom = bottomInset + 12.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = m != null, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.background(Color(0xE6202433), RoundedCornerShape(20.dp)).border(1.dp, Color.White.copy(0.08f), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(m?.first ?: "", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
                val a = action
                if (a != null) {
                    Spacer(Modifier.width(14.dp))
                    Text(a.first, color = RaviloTheme.colors.accentSecondary, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Sora,
                        modifier = Modifier.tap { a.second(); m?.let { MusicToasts.hide(it.second) } })
                }
            }
        }
    }
}

// ── FR-R321-3: the mode card on Profile ──

/**
 * J1's lean: two halves under the photo and name. The chosen half takes the brand gradient (Noir: the skin's own
 * accent, flat); a tap switches, lands on that mode's first tab, and asks nothing.
 */
@Composable
fun ListeningModeCard(musicMode: Boolean, withBooks: Boolean = false, onSwitch: (Boolean) -> Unit) {
    val colors = RaviloTheme.colors
    // R326 (FR-R326-3) — a switch stays on Profile; a short toast names the mode the bar now shows.
    val videoName = str("mode.video"); val musicName = if (withBooks) str("mode.music_books") else str("mode.music")
    fun switch(on: Boolean) { onSwitch(on); MusicToasts.show(if (on) musicName else videoName) }
    Column {
        Text(str(if (dev.jellystructure.ravilo.ui.isDesktopPlatform) "mode.label_desk" else "mode.label").uppercase(), color = colors.textDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(16.dp)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            ModeHalf(str("mode.video"), str("mode.video_sub"), MusicIcon.FILM, selected = !musicMode, Modifier.weight(1f)) { if (musicMode) switch(false) }
            // R323 (R321 FR-R321-3) — *Music & audiobooks* when the viewer has books.
            ModeHalf(if (withBooks) str("mode.music_books") else str("mode.music"), str("mode.music_sub"), MusicIcon.NOTE, selected = musicMode, Modifier.weight(1f)) { if (!musicMode) switch(true) }
        }
    }
}

@Composable
private fun ModeHalf(title: String, sub: String, icon: MusicIcon, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    val bg = if (selected) Modifier.background(colors.accentGradient, RoundedCornerShape(12.dp)) else Modifier
    Column(modifier.then(bg).tap(onClick).padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MusicGlyph(icon, if (selected) colors.onAccent else colors.text, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(title, color = if (selected) colors.onAccent else colors.text, fontSize = 14.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Text(sub, color = if (selected) colors.onAccent.copy(0.85f) else colors.textSecondary, fontSize = 11.5.sp, lineHeight = 15.sp, fontFamily = Sora, maxLines = 2)
    }
}
