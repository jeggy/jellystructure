package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalUserAvatarUrl
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.isMacPlatform
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SystemUiFont

/**
 * R337 — every page the desktop's sidebar and rail can light. [SEARCH] is the rail's first item (the sidebar draws
 * the search field instead); [QUEUE] opens the queue panel rather than a page (FR-R337-8).
 */
enum class DesktopPage { SEARCH, HOME, DISCOVER, MY_LIST, FILMS, SERIES, LISTEN, PLAYING, QUEUE, ARTISTS, ALBUMS, SONGS, GENRES, PLAYLISTS, AUDIOBOOKS }

/** R337 (dev review 7, Q13) — the music Library is the phone's Browse chips, one list, so the two cannot drift. */
private val MUSIC_LIBRARY = listOf(DesktopPage.ARTISTS, DesktopPage.ALBUMS, DesktopPage.SONGS, DesktopPage.GENRES, DesktopPage.PLAYLISTS)

@Composable
private fun label(p: DesktopPage): String = when (p) {
    DesktopPage.SEARCH -> str("nav.search")
    DesktopPage.HOME -> str("nav.home")
    DesktopPage.DISCOVER -> str("nav.discover")
    DesktopPage.MY_LIST -> str("nav.my_list")
    DesktopPage.FILMS -> str("nav.movies")
    DesktopPage.SERIES -> str("nav.series")
    DesktopPage.LISTEN -> str("mnav.listen")
    DesktopPage.PLAYING -> str("mnav.now_short")
    DesktopPage.QUEUE -> str("mnav.queue")
    DesktopPage.ARTISTS -> str("mlib.artists")
    DesktopPage.ALBUMS -> str("mlib.albums")
    DesktopPage.SONGS -> str("mlib.songs")
    DesktopPage.GENRES -> str("mlib.genres")
    DesktopPage.PLAYLISTS -> str("mlib.playlists")
    DesktopPage.AUDIOBOOKS -> str("mnav.audiobooks")
}

@Composable
private fun PageGlyph(p: DesktopPage, tint: Color, size: Dp) {
    when (p) {
        DesktopPage.SEARCH -> MusicGlyph(MusicIcon.SEARCH, tint, size)
        DesktopPage.HOME -> Box(Modifier.size(size)) { BottomNavGlyph(BottomNavItem.HOME, tint) }
        DesktopPage.DISCOVER -> Box(Modifier.size(size)) { BottomNavGlyph(BottomNavItem.DISCOVER, tint) }
        DesktopPage.MY_LIST -> MusicGlyph(MusicIcon.BOOKMARK, tint, size)
        DesktopPage.FILMS -> MusicGlyph(MusicIcon.FILM, tint, size)
        DesktopPage.SERIES -> Box(Modifier.size(size)) { BottomNavGlyph(BottomNavItem.LIBRARY, tint) }
        DesktopPage.LISTEN -> MusicGlyph(MusicIcon.LISTEN, tint, size)
        DesktopPage.PLAYING -> MusicGlyph(MusicIcon.PLAYING, tint, size)
        DesktopPage.QUEUE -> MusicGlyph(MusicIcon.QUEUE, tint, size)
        DesktopPage.ARTISTS -> MusicGlyph(MusicIcon.NOTE, tint, size)
        DesktopPage.ALBUMS -> MusicGlyph(MusicIcon.BROWSE, tint, size)
        DesktopPage.SONGS -> MusicGlyph(MusicIcon.PLAY, tint, size)
        DesktopPage.GENRES -> GridGlyph(tint, size)
        DesktopPage.PLAYLISTS -> MusicGlyph(MusicIcon.CHAPTERS, tint, size)
        DesktopPage.AUDIOBOOKS -> MusicGlyph(MusicIcon.BOOKMARK, tint, size)
    }
}

/**
 * R337 (FR-R337-3/4/5) — the desktop's navigation: a sidebar at 840 dp and wider, a rail from 600 to 839 dp. The
 * platform draws the shape — macOS: an inset panel with the traffic lights at its top and the selected row filled with
 * the accent; GNOME (every Linux desktop): a split-view sidebar a notch lighter than the content under a header bar
 * titled *Ravilo* with the primary menu ☰, and a neutral selection. Ravilo draws the content: the switch at the head
 * (absent without music, R321), the search field, the mode's pages, the viewer at the foot.
 */
@Composable
fun DesktopNav(
    rail: Boolean,
    inMusic: Boolean,
    musicAvailable: Boolean,
    booksAvailable: Boolean,
    lit: DesktopPage?,
    queueOpen: Boolean,
    musicPlaying: Boolean,
    counts: Map<DesktopPage, Int>,
    viewerName: String,
    onPage: (DesktopPage) -> Unit,
    onMode: (music: Boolean) -> Unit,
    onViewer: () -> Unit,
    onSettings: () -> Unit,
    onSignOut: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaviloTheme.colors
    val mac = isMacPlatform
    val width = when {
        rail && mac -> 76.dp
        rail -> 84.dp
        mac -> 232.dp
        else -> 248.dp
    }
    val panel = if (mac) {
        // Dev review 10 — the Mac's glass drawn, not an NSVisualEffectView: one step lighter than the page, a hairline.
        Modifier.padding(8.dp).width(width - 16.dp).fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface.copy(alpha = 0.92f))
            .border(1.dp, colors.fg.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
    } else {
        Modifier.width(width).fillMaxHeight().background(colors.surface)
    }
    val pages: List<Pair<String?, List<DesktopPage>>> = if (inMusic) listOf(
        null to listOf(DesktopPage.LISTEN, DesktopPage.PLAYING, DesktopPage.QUEUE),
        str("nav.library") to (MUSIC_LIBRARY + listOfNotNull(if (booksAvailable) DesktopPage.AUDIOBOOKS else null)),
    ) else listOf(
        null to listOf(DesktopPage.HOME, DesktopPage.DISCOVER, DesktopPage.MY_LIST),
        str("nav.library") to listOf(DesktopPage.FILMS, DesktopPage.SERIES),
    )
    Box(modifier.width(width).fillMaxHeight()) {
        Column(panel) {
            // The window's controls: macOS draws the traffic lights over this space (a transparent, full-size title
            // bar); GNOME gets a header bar titled Ravilo with the primary menu.
            if (mac) Spacer(Modifier.height(if (rail) 40.dp else 38.dp))
            else if (rail) Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) { MenuButton(onMenu) }
            else Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(34.dp))
                Text("Ravilo", color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = SystemUiFont, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                MenuButton(onMenu)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = if (rail) 6.dp else 10.dp)) {
                if (musicAvailable) {
                    ModeSwitch(rail, inMusic, onMode)
                    Spacer(Modifier.height(10.dp))
                }
                if (rail) {
                    RailItem(DesktopPage.SEARCH, lit == DesktopPage.SEARCH, dot = false) { onPage(DesktopPage.SEARCH) }
                    Rule()
                } else {
                    SearchField(str(if (inMusic) "desk.search_music" else "desk.search_video")) { onPage(DesktopPage.SEARCH) }
                    Spacer(Modifier.height(10.dp))
                }
                pages.forEachIndexed { g, (heading, group) ->
                    if (g > 0) { if (rail) Rule() else Spacer(Modifier.height(12.dp)) }
                    if (heading != null && !rail) {
                        Text(heading.uppercase(), color = colors.textDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = SystemUiFont, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp))
                    }
                    group.forEach { p ->
                        val on = if (p == DesktopPage.QUEUE) queueOpen else lit == p
                        val live = p == DesktopPage.PLAYING && musicPlaying
                        if (rail) RailItem(p, on, dot = live) { onPage(p) }
                        else SidebarRow(p, on, live, counts[p]) { onPage(p) }
                    }
                }
            }
            ViewerFoot(rail, viewerName, onViewer, onSettings, onSignOut)
        }
    }
}

@Composable
private fun MenuButton(onMenu: () -> Unit) {
    val colors = RaviloTheme.colors
    val menuLabel = str("desk.menu")
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onMenu).semantics { contentDescription = menuLabel },
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { Box(Modifier.width(16.dp).height(2.dp).background(colors.text, RoundedCornerShape(1.dp))) }
        }
    }
}

@Composable
private fun Rule() {
    Box(Modifier.padding(vertical = 8.dp, horizontal = 10.dp).fillMaxWidth().height(1.dp).background(RaviloTheme.colors.fg.copy(alpha = 0.10f)))
}

/** FR-R337-3 item 2 / FR-R337-4 — *Films & series | Music*: two segments across the sidebar, two stacked icons on the rail. */
@Composable
private fun ModeSwitch(rail: Boolean, music: Boolean, onMode: (Boolean) -> Unit) {
    val colors = RaviloTheme.colors
    val mac = isMacPlatform
    @Composable
    fun segment(isMusic: Boolean, modifier: Modifier) {
        val on = music == isMusic
        val bg = when {
            !on -> Color.Transparent
            mac -> colors.accent
            else -> colors.fg.copy(alpha = 0.12f)
        }
        val ink = if (on && mac) colors.onAccent else if (on) colors.text else colors.textSecondary
        val name = str(if (isMusic) "mode.music" else "mode.video")
        Box(
            modifier.clip(RoundedCornerShape(if (mac) 7.dp else 6.dp)).background(bg).clickable { onMode(isMusic) }
                .semantics { contentDescription = name },
            contentAlignment = Alignment.Center,
        ) {
            if (rail) MusicGlyph(if (isMusic) MusicIcon.NOTE else MusicIcon.FILM, ink, 18.dp)
            else Text(str(if (isMusic) "mode.music" else "mode.video"), color = ink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = SystemUiFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val frame = Modifier.clip(RoundedCornerShape(9.dp)).background(colors.fg.copy(alpha = 0.06f)).padding(2.dp)
    if (rail) Column(frame.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        segment(false, Modifier.fillMaxWidth().height(34.dp))
        segment(true, Modifier.fillMaxWidth().height(34.dp))
    } else Row(frame.fillMaxWidth().height(34.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        segment(false, Modifier.weight(1.3f).fillMaxHeight())
        segment(true, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun SearchField(placeholder: String, onOpen: () -> Unit) {
    val colors = RaviloTheme.colors
    Row(
        Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(8.dp)).background(colors.fg.copy(alpha = 0.07f)).clickable(onClick = onOpen).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MusicGlyph(MusicIcon.SEARCH, colors.textDim, 15.dp)
        Text(placeholder, color = colors.textDim, fontSize = 13.sp, fontFamily = SystemUiFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SidebarRow(p: DesktopPage, on: Boolean, live: Boolean, count: Int?, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    val mac = isMacPlatform
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg = when {
        on && mac -> colors.accent
        on -> colors.fg.copy(alpha = 0.12f)
        hovered -> colors.fg.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    val ink = if (on && mac) colors.onAccent else if (on) colors.text else colors.textSecondary
    Row(
        Modifier.fillMaxWidth().height(if (mac) 30.dp else 36.dp).clip(RoundedCornerShape(if (mac) 7.dp else 6.dp)).background(bg)
            .hoverable(hover).clickable(interactionSource = hover, indication = null, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PageGlyph(p, ink, 17.dp)
        Text(label(p), color = ink, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, fontFamily = SystemUiFont, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (live) PlayingBars(true, if (on && mac) colors.onAccent else colors.accent, 14.dp)
        else if (count != null) Text(count.toString(), color = if (on && mac) colors.onAccent.copy(alpha = 0.8f) else colors.textDim, fontSize = 12.sp, fontFamily = SystemUiFont)
    }
}

/** FR-R337-4 — icon over a short label; a dot on *Playing* replaces the equaliser. */
@Composable
private fun RailItem(p: DesktopPage, on: Boolean, dot: Boolean, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    val mac = isMacPlatform
    val bg = when {
        on && mac -> colors.accent
        on -> colors.fg.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val ink = if (on && mac) colors.onAccent else if (on) colors.text else colors.textSecondary
    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(bg).clickable(onClick = onClick).padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            PageGlyph(p, ink, 20.dp)
            if (dot) Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(colors.accent, CircleShape))
        }
        Spacer(Modifier.height(3.dp))
        Text(label(p), color = ink, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, fontFamily = SystemUiFont, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** FR-R337-3 item 5 — the viewer; the photo opens Profile (R304's page). No *Switch profile*: a computer holds one viewer (Q12). */
@Composable
private fun ViewerFoot(rail: Boolean, name: String, onViewer: () -> Unit, onSettings: () -> Unit, onSignOut: () -> Unit) {
    val colors = RaviloTheme.colors
    val avatar = LocalUserAvatarUrl.current
    @Composable
    fun photo(size: Dp) {
        Box(Modifier.size(size).clip(CircleShape).background(colors.surfaceVariant).clickable(onClick = onViewer), contentAlignment = Alignment.Center) {
            if (avatar != null) RemoteImage(avatar, name, Modifier.fillMaxSize())
            else Text(name.take(2).uppercase().ifEmpty { "?" }, color = colors.text, fontSize = (size.value * 0.36f).sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
        }
    }
    if (rail) {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { photo(34.dp) }
        return
    }
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        photo(34.dp)
        Column(Modifier.weight(1f)) {
            Text(name, color = colors.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = SystemUiFont, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable(onClick = onViewer))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(str("pm.settings"), color = colors.textSecondary, fontSize = 12.sp, fontFamily = SystemUiFont, modifier = Modifier.clickable(onClick = onSettings))
                Text("·", color = colors.textDim, fontSize = 12.sp, fontFamily = SystemUiFont)
                Text(str("pm.sign_out"), color = colors.textSecondary, fontSize = 12.sp, fontFamily = SystemUiFont, modifier = Modifier.clickable(onClick = onSignOut))
            }
        }
    }
}
