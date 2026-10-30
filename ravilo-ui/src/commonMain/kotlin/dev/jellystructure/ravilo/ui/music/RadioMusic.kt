package dev.jellystructure.ravilo.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.MusicGlyph
import dev.jellystructure.ravilo.ui.components.MusicIcon
import dev.jellystructure.ravilo.ui.components.PlayingBars
import dev.jellystructure.ravilo.ui.components.RadioMarkPlate
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.screens.HandsetSheet
import dev.jellystructure.ravilo.ui.screens.formatGuideTime
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.shared.tv.LiveTvChannel
import dev.jellystructure.shared.tv.MusicRow

/*
 * DEB-2659 (DEB-2628 FR-9, FR-10) — what radio looks like in Music mode: the Radio row's tiles on Music Home, the live Playing
 * page, the mini bar's radio shape and the LIVE mark. The decisions (which row is which, what a station says) are plain
 * functions so they are tests; the engine's side is `RadioLive.kt`. Drawn after `design/ravilo/radio/png/radio-rowD-*` and
 * `radio-np-*` (owner's picks, 2026-10-27: option D, the last row).
 */

/** What Music Home's `ListenRow` draws a row as. A `radio` row is drawn from [MusicRow.stations] and from nothing else. */
internal enum class ListenRowKind { TRACKS, ARTISTS, RADIO, ALBUMS }

internal fun listenRowKind(row: MusicRow): ListenRowKind = when {
    row.key == "radio" -> ListenRowKind.RADIO
    row.tracks.isNotEmpty() -> ListenRowKind.TRACKS
    row.artists.isNotEmpty() -> ListenRowKind.ARTISTS
    else -> ListenRowKind.ALBUMS
}

/** A `radio` row with no stations draws nothing, not an empty *Radio* heading: an installed phone would draw one (FR-9), a new one does not. */
internal fun listenRowShown(row: MusicRow): Boolean = row.key != "radio" || row.stations.isNotEmpty()

/** FR-9 — a tile's second line: the programme now, or *Live radio* with no guide. */
internal fun stationTileLine(station: LiveTvChannel, liveRadio: String): String = station.currentProgram?.name ?: liveRadio

/** The programme's times, *20:00–20:30*, when the station has a programme now. */
internal fun radioProgrammeTimes(radio: RadioLive): String? =
    radio.programme?.takeIf { it.endMs > it.startMs }?.let { formatGuideTime(it.startMs) + "–" + formatGuideTime(it.endMs) }

private val LiveRed = Color(0xFFFF5252)   // `.rd-live` in design/ravilo/radio/radio.css

/** FR-10 — the LIVE mark that takes the seek bar's place: a white dot and the word on the red plate. White is on its own red fill. */
@Composable
internal fun RadioLiveMark(compact: Boolean = false) {
    Row(
        Modifier.background(LiveRed, RoundedCornerShape(if (compact) 5.dp else 7.dp)).padding(horizontal = if (compact) 7.dp else 11.dp, vertical = if (compact) 2.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp),
    ) {
        Box(Modifier.size(if (compact) 5.dp else 7.dp).background(Color.White, CircleShape))
        Text(str("radio.live_badge"), color = Color.White, fontSize = if (compact) 10.sp else 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontFamily = Sora)
    }
}

/** A station's square: the logo (kept whole) on the station's own gradient, with the radio mark in the top-right corner. The caller gives it its size. */
@Composable
internal fun StationSquare(station: LiveTvChannel, modifier: Modifier, corner: Dp, mark: Boolean = true) {
    val hue = ((station.name.fold(0L) { h, c -> (h * 31 + c.code) and 0xFFFFFFFFL }) % 360L).toFloat()
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(corner)).background(Brush.linearGradient(listOf(Color.hsl(hue, 0.46f, 0.36f), Color.hsl((hue + 40f) % 360f, 0.52f, 0.14f)))),
        contentAlignment = Alignment.Center,
    ) {
        val size = maxWidth
        val logo = station.logoUrl
        if (!logo.isNullOrBlank()) RemoteImage(logo, station.name, Modifier.fillMaxSize().padding(size / 7), contentScale = ContentScale.Fit, requestedWidth = 360)
        else Text(station.name.take(3).uppercase(), color = Color.White, fontSize = (size.value / 4.2f).sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
        if (mark) RadioMarkPlate(Modifier.align(Alignment.TopEnd).padding(size / 14), plateSize = (size.value / 5.2f).coerceIn(22f, 40f).dp, glyphSize = (size.value / 8f).coerceIn(14f, 24f).dp)
    }
}

/** FR-9 — one tile of the Radio row: the logo with the corner mark, the name, the programme now or *Live radio*. Tapping plays at once; there is no station page. */
@Composable
internal fun RadioStationTile(station: LiveTvChannel, width: Dp, onPlay: () -> Unit) {
    val colors = RaviloTheme.colors
    Column(Modifier.width(width).tap(onPlay)) {
        StationSquare(station, Modifier.size(width), corner = colors.tileRadius)
        Spacer(Modifier.height(7.dp))
        Text(station.name, color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stationTileLine(station, str("radio.live_radio")), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * FR-10 — Playing, for a station (`radio-np-phone-*`): the logo as the cover with the corner mark, the programme as the title and the
 * station under it, the LIVE mark with the programme's times in the seek bar's place, and ⏯ alone. A paused, stopped or failed
 * station shows no LIVE mark. Everything that does not apply is left out, never greyed: no seek, previous, next, shuffle, repeat,
 * lyrics, heart or ⋯.
 */
@Composable
internal fun RadioPlayingScreen(st: MusicPlayerState, onClose: () -> Unit) {
    val colors = RaviloTheme.colors
    val radio = st.radio ?: return
    val desk = dev.jellystructure.ravilo.ui.theme.isDesktopLayout
    Box(Modifier.fillMaxSize().background(playingGround())) {
        Column(Modifier.fillMaxSize().padding(horizontal = raviloHPad).padding(top = if (desk) 64.dp else 14.dp, bottom = 24.dp), horizontalAlignment = Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).tap(onClose), contentAlignment = Alignment.Center) { MusicGlyph(MusicIcon.CHEVRON_DOWN, colors.text, 20.dp, description = str("music.close")) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(str("radio.label"), color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora)
                    Text(radio.station.name, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(42.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                BoxCover(radio, desk)
            }
            Spacer(Modifier.height(18.dp))
            Text(radio.title, color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(radio.subtitle(str("radio.live_radio")), color = colors.textSecondary, fontSize = 15.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            // The seek bar's place: LIVE, and the programme's times; a stopped or failed station shows no LIVE.
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (st.showsLive) {
                    RadioLiveMark()
                    radioProgrammeTimes(radio)?.let { Text(it, color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora) }
                } else if (!st.playing && !st.failed) {
                    Text(str("radio.stopped"), color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora)
                    Text("·", color = colors.textDim, fontSize = 13.sp)
                    Text(str("radio.tune_in_again"), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RadioPlayButton(st, 64.dp) }
        }
        RadioFailureSheet(st)
    }
}

@Composable
private fun BoxCover(radio: RadioLive, desk: Boolean) {
    StationSquare(radio.station, (if (desk) Modifier.size(300.dp) else Modifier.fillMaxWidth(0.8f).aspectRatio(1f)).shadow(18.dp, RoundedCornerShape(14.dp)), corner = 14.dp)
}

/** ⏯ — the transport's only button. Pause stops the station and play re-tunes it at the live edge. */
@Composable
internal fun RadioPlayButton(st: MusicPlayerState, size: Dp) {
    val colors = RaviloTheme.colors
    val shownPlaying = st.playing   // what this glyph draws, captured at composition (MusicPlayback.togglePlay(Boolean))
    Box(Modifier.size(size).clip(CircleShape).background(colors.accentGradient).tap { MusicPlayback.togglePlay(shownPlaying) }, contentAlignment = Alignment.Center) {
        if (st.buffering) Pulse(colors.onAccent) else MusicGlyph(if (st.playing) MusicIcon.PAUSE else MusicIcon.PLAY, colors.onAccent, (size.value * 0.44f).dp)
    }
}

/** FR-10 — the engine's existing *couldn't play* state, with the station's name; *Try again* is the one choice (there is no next to skip to). */
@Composable
private fun RadioFailureSheet(st: MusicPlayerState) {
    val radio = st.radio ?: return
    HandsetSheet(visible = st.failed, onDismiss = { MusicPlayback.skip() }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp)) {
            Text(str("cast.failed", mapOf("device" to radio.station.name)), color = RaviloTheme.colors.fg, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            Spacer(Modifier.height(16.dp))
            PillButton(str("music.try_again"), null, primary = true, Modifier.fillMaxWidth()) { MusicPlayback.retry() }
        }
    }
}

/** FR-10 — the phone's mini bar for a station: the logo, the programme and the station, ⏯ — no next and no progress line. */
@Composable
internal fun RadioMiniBar(st: MusicPlayerState, onOpen: () -> Unit) {
    val colors = RaviloTheme.colors
    val radio = st.radio ?: return
    Column(Modifier.fillMaxWidth().height(dev.jellystructure.ravilo.ui.theme.RaviloDimens.musicMiniBarHeight).background(colors.surface).tap(onOpen)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (st.showsLive) LiveRed else colors.textDim.copy(0.25f)))
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StationSquare(radio.station, Modifier.size(44.dp), corner = 6.dp, mark = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(radio.title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (st.showsLive) RadioLiveMark(compact = true)
                    Text(radio.subtitle(str("radio.live_radio")), color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
            val shownPlaying = st.playing
            Box(Modifier.size(46.dp).tap { MusicPlayback.togglePlay(shownPlaying) }, contentAlignment = Alignment.Center) {
                if (st.buffering) PlayingBars(true, colors.text, 18.dp) else MusicGlyph(if (st.playing) MusicIcon.PAUSE else MusicIcon.PLAY, colors.text, 22.dp)
            }
        }
    }
}
