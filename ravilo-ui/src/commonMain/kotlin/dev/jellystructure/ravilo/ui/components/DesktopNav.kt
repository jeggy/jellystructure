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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRequester
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
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.ravilo.ui.seams.windowDragArea

/**
 * R337 — every page the desktop's sidebar and rail can light. [SEARCH] is the rail's first item (the sidebar draws
 * the search field instead); [QUEUE] opens the queue panel rather than a page (FR-R337-8).
 */
enum class DesktopPage { SEARCH, HOME, DISCOVER, MY_LIST, FILMS, SERIES, LISTEN, PLAYING, QUEUE, ARTISTS, ALBUMS, SONGS, GENRES, PLAYLISTS, AUDIOBOOKS }

/** R337 (dev review 7, Q13) — the music Library is the phone's Browse chips, one list, so the two cannot drift. */
private val MUSIC_LIBRARY = listOf(DesktopPage.ARTISTS, DesktopPage.ALBUMS, DesktopPage.SONGS, DesktopPage.GENRES, DesktopPage.PLAYLISTS)

@Composable
fun desktopPageLabel(p: DesktopPage): String = label(p)

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

private fun icon(p: DesktopPage): DeskIcon = when (p) {
    DesktopPage.SEARCH -> DeskIcon.SEARCH
    DesktopPage.HOME -> DeskIcon.HOME
    DesktopPage.DISCOVER -> DeskIcon.COMPASS
    DesktopPage.MY_LIST -> DeskIcon.BOOKMARK
    DesktopPage.FILMS -> DeskIcon.FILM
    DesktopPage.SERIES -> DeskIcon.TV
    DesktopPage.LISTEN -> DeskIcon.PHONES
    DesktopPage.PLAYING -> DeskIcon.WAVE
    DesktopPage.QUEUE -> DeskIcon.QUEUE
    DesktopPage.ARTISTS -> DeskIcon.PERSON
    DesktopPage.ALBUMS -> DeskIcon.DISC
    DesktopPage.SONGS -> DeskIcon.NOTE
    DesktopPage.GENRES -> DeskIcon.GENRES
    DesktopPage.PLAYLISTS -> DeskIcon.PLAYLIST
    DesktopPage.AUDIOBOOKS -> DeskIcon.BOOK
}

/** The Mac sidebar's outer width: an 8 dp inset and the 254 dp glass panel (`.mside`: 232 + its padding and hairline). */
val DESK_SIDEBAR_MAC = 262.dp
/** The Mac rail's outer width: the inset and the 76 dp panel. */
val DESK_RAIL_MAC = 84.dp

/**
 * R337 (FR-R337-3/4/5) — the desktop's navigation: a sidebar at 840 dp and wider, a rail from 600 to 839 dp, drawn to
 * `design/ravilo/desktop-directions.css` (`.mside`, `.gside`, `.seg`, `.sfield`, `.nav`, `.who`, `.rail`).
 *
 * - **macOS:** an inset glass panel (8 dp from the window's edge, 12 dp corners, a hairline) with the traffic lights at
 *   its head; the selected row tinted with the theme's accent (filled with it in Graphite and Daylight, as the Mac
 *   draws selection); icons in the theme's second accent.
 * - **GNOME (every Linux desktop):** a split-view sidebar a notch lighter than the content under a header bar titled
 *   *Ravilo* with the primary menu ☰; a neutral selection.
 *
 * Ravilo draws the content: the switch at the head (absent without music, R321), the search field, the mode's pages,
 * the viewer at the foot.
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
    /** The sidebar's search text: typing here searches the mode (the rail has no field; its Search item opens the page's own). */
    searchQuery: String = "",
    onSearchQuery: (String) -> Unit = {},
    /** Emits when ⌘F / Ctrl+F asks for the field. */
    searchFocus: kotlinx.coroutines.flow.Flow<Unit>? = null,
) {
    val colors = RaviloTheme.colors
    val mac = isMacPlatform
    val width = when {
        rail && mac -> DESK_RAIL_MAC
        rail -> 84.dp
        mac -> DESK_SIDEBAR_MAC
        else -> 264.dp   // .gside: 248 + 8 + 8
    }
    val panel = if (mac) {
        // Dev review 10 — the Mac's glass drawn, not an NSVisualEffectView: the page's colour with a veil of ink, a
        // hairline and a lighter top edge. Nothing scrolls behind the panel, so there is nothing to blur.
        val glass = if (colors.isLight) Color(0xDBEEEEF2) else colors.fg.copy(alpha = 0.055f)
        Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp).fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(glass)
            .border(1.dp, colors.fg.copy(alpha = if (colors.isLight) 0.07f else 0.08f), RoundedCornerShape(12.dp))
            .padding(start = if (rail) 7.dp else 11.dp, end = if (rail) 7.dp else 11.dp, top = 13.dp, bottom = 11.dp)
    } else {
        Modifier.fillMaxSize().background(colors.card).padding(start = if (rail) 6.dp else 8.dp, end = if (rail) 6.dp else 8.dp, bottom = if (rail) 10.dp else 8.dp)
    }
    val pages: List<Pair<String?, List<DesktopPage>>> = if (inMusic) listOf(
        null to listOf(DesktopPage.LISTEN, DesktopPage.PLAYING, DesktopPage.QUEUE),
        str("nav.library") to (MUSIC_LIBRARY + listOfNotNull(if (booksAvailable) DesktopPage.AUDIOBOOKS else null)),
    ) else listOf(
        null to listOf(DesktopPage.HOME, DesktopPage.DISCOVER, DesktopPage.MY_LIST),
        str("nav.library") to listOf(DesktopPage.FILMS, DesktopPage.SERIES),
    )
    Box(modifier.width(width).fillMaxHeight()) {
        Column(panel, horizontalAlignment = Alignment.CenterHorizontally) {
            // The window's controls: macOS draws the traffic lights over this space (a transparent, full-size title
            // bar), and it moves the window as a title bar does; GNOME gets a header bar titled Ravilo with the menu.
            if (mac) Spacer(Modifier.fillMaxWidth().height(if (rail) 22.dp else 20.dp).windowDragArea())
            else if (rail) {
                // The rail holds the window's start buttons when the desktop puts them on the left (small, to fit
                // three); the menu moves under them. Empty header moves the window, as a header bar does.
                val startButtons = dev.jellystructure.ravilo.ui.seams.windowControlsWidth(true, compact = true) > 0.dp
                if (startButtons) Box(Modifier.fillMaxWidth().height(46.dp).windowDragArea(), contentAlignment = Alignment.Center) {
                    dev.jellystructure.ravilo.ui.seams.WindowControlButtons(true, compact = true)
                }
                Box(Modifier.fillMaxWidth().height(46.dp).then(if (startButtons) Modifier else Modifier.windowDragArea()), contentAlignment = Alignment.Center) { MenuButton(onMenu) }
            }
            else Row(Modifier.fillMaxWidth().height(46.dp).windowDragArea().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                // The window's start buttons, when the desktop keeps them on the left, sit first in the leftmost header bar.
                if (dev.jellystructure.ravilo.ui.seams.windowControlsWidth(true) > 0.dp) {
                    dev.jellystructure.ravilo.ui.seams.WindowControlButtons(true, Modifier.padding(start = 4.dp, end = 10.dp))
                }
                BrandMark(size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text("Ravilo", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = SystemUiFont, modifier = Modifier.weight(1f))
                MenuButton(onMenu)
            }
            val gap = if (mac) 10.dp else 8.dp
            if (mac) Spacer(Modifier.height(gap))
            if (musicAvailable) {
                ModeSwitch(rail, inMusic, onMode)
                Spacer(Modifier.height(gap))
            }
            if (!rail) {
                SearchField(str(if (inMusic) "desk.search_music" else "desk.search_video"), searchQuery, onSearchQuery, searchFocus)
                Spacer(Modifier.height(gap))
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(if (rail) 2.dp else 1.dp)) {
                if (rail) RailItem(DesktopPage.SEARCH, lit == DesktopPage.SEARCH, dot = false) { onPage(DesktopPage.SEARCH) }
                pages.forEachIndexed { g, (heading, group) ->
                    if (rail && g > 0) Rule()
                    if (heading != null && !rail) {
                        Text(
                            heading, color = colors.textDim, fontSize = if (mac) 11.sp else 12.sp, fontWeight = FontWeight.Bold, fontFamily = SystemUiFont,
                            letterSpacing = if (mac) 0.22.sp else 0.sp,
                            modifier = Modifier.padding(start = if (mac) 8.dp else 10.dp, top = if (mac) 10.dp else 12.dp, bottom = 4.dp),
                        )
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
        // GNOME's split view: a hairline between the sidebar and the content.
        if (!mac) Box(Modifier.align(Alignment.CenterEnd).width(1.dp).fillMaxHeight().background(colors.fg.copy(alpha = 0.09f)))
    }
}

@Composable
private fun MenuButton(onMenu: () -> Unit) {
    val colors = RaviloTheme.colors
    val menuLabel = str("desk.menu")
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onMenu).semantics { contentDescription = menuLabel },
        contentAlignment = Alignment.Center,
    ) { DeskIcon(DeskIcon.MENU, colors.text, 18.dp) }
}

@Composable
private fun Rule() {
    Box(Modifier.padding(vertical = 6.dp, horizontal = 10.dp).fillMaxWidth().height(1.dp).background(RaviloTheme.colors.fg.copy(alpha = 0.09f)))
}

/** How the platform draws a selected row: the Mac tints it with the accent (fills it, in the two system-like themes); GNOME stays neutral. */
@Composable
private fun selection(): Pair<Color, Color> {
    val colors = RaviloTheme.colors
    if (!isMacPlatform) return colors.fg.copy(alpha = 0.10f) to colors.text
    val theme = dev.jellystructure.ravilo.ui.theme.LocalRaviloTheme.current
    val solid = theme == dev.jellystructure.ravilo.ui.theme.ThemeId.GRAPHITE || theme == dev.jellystructure.ravilo.ui.theme.ThemeId.DAYLIGHT
    return if (solid) colors.accent to Color.White else colors.accent.copy(alpha = 0.34f) to colors.text
}

/** FR-R337-3 item 2 / FR-R337-4 — *Films & series | Music*: two equal segments across the sidebar, two stacked icons on the rail. */
@Composable
private fun ModeSwitch(rail: Boolean, music: Boolean, onMode: (Boolean) -> Unit) {
    val colors = RaviloTheme.colors
    val mac = isMacPlatform
    @Composable
    fun segment(isMusic: Boolean, modifier: Modifier) {
        val on = music == isMusic
        val shape = RoundedCornerShape(if (mac) 7.dp else 6.dp)
        val fill = when {
            !on -> Modifier
            colors.isLight -> Modifier.shadow(1.dp, shape).background(Color.White, shape)
            mac -> Modifier.shadow(1.5.dp, shape).background(colors.fg.copy(alpha = 0.16f), shape)
            else -> Modifier.background(colors.fg.copy(alpha = 0.15f), shape)
        }
        val ink = if (on) colors.text else colors.textSecondary
        val name = str(if (isMusic) "mode.music" else "mode.video")
        Box(
            modifier.then(fill).clip(shape).clickable { onMode(isMusic) }.semantics { contentDescription = name },
            contentAlignment = Alignment.Center,
        ) {
            if (rail) DeskIcon(if (isMusic) DeskIcon.NOTE else DeskIcon.FILM, ink, 17.dp)
            else Text(name, color = ink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = SystemUiFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val track = Modifier.clip(RoundedCornerShape(if (mac) 10.dp else 9.dp)).background(colors.fg.copy(alpha = if (mac) 0.07f else 0.06f)).padding(3.dp)
    if (rail) Column(track.width(44.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        segment(false, Modifier.fillMaxWidth().height(32.dp))
        segment(true, Modifier.fillMaxWidth().height(32.dp))
    } else Row(track.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        segment(false, Modifier.weight(1f).height(if (mac) 28.dp else 30.dp))
        segment(true, Modifier.weight(1f).height(if (mac) 28.dp else 30.dp))
    }
}

/**
 * `.sfield` — the sidebar's search field, a real one: what is typed searches the mode and the results take the content
 * pane. Esc empties it; the ✕ does too.
 */
@Composable
private fun SearchField(placeholder: String, query: String, onQuery: (String) -> Unit, focus: kotlinx.coroutines.flow.Flow<Unit>?) {
    val colors = RaviloTheme.colors
    val mac = isMacPlatform
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(focus) { focus?.collect { runCatching { fr.requestFocus() } } }
    Row(
        Modifier.fillMaxWidth().height(if (mac) 30.dp else 34.dp).clip(RoundedCornerShape(if (mac) 8.dp else 6.dp)).background(colors.fg.copy(alpha = 0.06f))
            .padding(start = 9.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        DeskIcon(DeskIcon.SEARCH, colors.textDim, 14.dp)
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.weight(1f).focusRequester(fr).onPreviewKeyEvent { ev ->
                if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && ev.key == androidx.compose.ui.input.key.Key.Escape && query.isNotEmpty()) { onQuery(""); true } else false
            },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = colors.text, fontSize = 12.5.sp, fontFamily = SystemUiFont),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accentSecondary),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(placeholder, color = colors.textDim, fontSize = 12.5.sp, fontFamily = SystemUiFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            val clear = str("search.clear")
            Box(Modifier.size(22.dp).clip(CircleShape).clickable { onQuery("") }.semantics { contentDescription = clear }, contentAlignment = Alignment.Center) {
                DeskIcon(DeskIcon.CLOSE, colors.textDim, 12.dp, stroke = 2.4f)
            }
        }
    }
}

@Composable
private fun SidebarRow(p: DesktopPage, on: Boolean, live: Boolean, count: Int?, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    val mac = isMacPlatform
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val (selBg, selInk) = selection()
    val bg = when {
        on -> selBg
        hovered -> colors.fg.copy(alpha = 0.05f)
        else -> Color.Transparent
    }
    val ink = if (on) selInk else colors.textSecondary
    val glyph = when {
        !mac -> colors.textSecondary
        on -> selInk
        else -> colors.accentSecondary
    }
    Row(
        Modifier.fillMaxWidth().height(if (mac) 28.dp else 36.dp).clip(RoundedCornerShape(if (mac) 7.dp else 6.dp)).background(bg)
            .hoverable(hover).clickable(interactionSource = hover, indication = null, onClick = onClick).padding(horizontal = if (mac) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (mac) 9.dp else 12.dp),
    ) {
        DeskIcon(icon(p), glyph, 16.dp)
        Text(label(p), color = ink, fontSize = if (mac) 13.sp else 13.5.sp, fontFamily = SystemUiFont, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (live) PlayingBars(true, if (on && mac) selInk else colors.accentSecondary, 12.dp)
        else if (count != null) Text(count.toString(), color = if (on && selInk == Color.White) Color.White.copy(alpha = 0.8f) else colors.textDim, fontSize = 11.5.sp, fontFamily = SystemUiFont)
    }
}

/** FR-R337-4 — icon over a short label; a dot on *Playing* replaces the equaliser. */
@Composable
private fun RailItem(p: DesktopPage, on: Boolean, dot: Boolean, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    val mac = isMacPlatform
    val (selBg, selInk) = selection()
    val ink = if (on) selInk else colors.textSecondary
    val glyph = when {
        !mac -> colors.textSecondary
        on -> selInk
        else -> colors.accentSecondary
    }
    Box(
        Modifier.fillMaxWidth().height(if (mac) 50.dp else 52.dp).clip(RoundedCornerShape(if (mac) 7.dp else 6.dp)).background(if (on) selBg else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            DeskIcon(icon(p), glyph, 18.dp)
            Text(label(p), color = ink, fontSize = if (mac) 10.5.sp else 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = SystemUiFont, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        if (dot) Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 14.dp).size(7.dp).background(colors.accentSecondary, CircleShape))
    }
}

/** FR-R337-3 item 5 — the viewer; the photo and the name open Profile (R304's page). No *Switch profile*: a computer holds one viewer (Q12). */
@Composable
private fun ViewerFoot(rail: Boolean, name: String, onViewer: () -> Unit, onSettings: () -> Unit, onSignOut: () -> Unit) {
    val colors = RaviloTheme.colors
    val avatar = LocalUserAvatarUrl.current
    @Composable
    fun photo(size: Dp) {
        Box(Modifier.size(size).clip(CircleShape).background(colors.accentGradient).clickable(onClick = onViewer), contentAlignment = Alignment.Center) {
            if (avatar != null) RemoteImage(avatar, name, Modifier.fillMaxSize())
            else Text(name.take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
        }
    }
    if (rail) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) { photo(26.dp) }
        return
    }
    Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        photo(26.dp)
        Column(Modifier.weight(1f)) {
            Text(name, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = SystemUiFont, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onViewer))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(str("pm.settings"), color = colors.textDim, fontSize = 11.5.sp, fontFamily = SystemUiFont, modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSettings))
                Text("·", color = colors.textDim, fontSize = 11.5.sp, fontFamily = SystemUiFont)
                Text(str("pm.sign_out"), color = colors.textDim, fontSize = 11.5.sp, fontFamily = SystemUiFont, modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onSignOut))
            }
        }
        Box(Modifier.size(22.dp).clip(CircleShape).clickable(onClick = onViewer), contentAlignment = Alignment.Center) { DeskIcon(DeskIcon.UP, colors.textDim, 14.dp) }
    }
}
