package dev.jellystructure.ravilo.ui.components

import androidx.compose.runtime.withFrameNanos
import dev.jellystructure.ravilo.ui.focus.requestFocusAwaiting
import androidx.compose.ui.platform.testTag
import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.LocalUserAvatarUrl
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.seams.windowDragArea
import dev.jellystructure.ravilo.ui.theme.LocalHandset
import dev.jellystructure.ravilo.ui.theme.LocalCompact
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * navFR — entry-point FocusRequester; callers focus this to bring focus into the bar.
 *          It is used as the first nav item's focusRequester so requesting it lands
 *          directly on the first item.
 * onDown — called when D-pad DOWN is pressed from any nav item; typically returns
 *           focus to the screen's hero/content area.
 */
@Composable
fun AppBar(
    navItems: List<String>? = null,
    activeNav: Int = 0,
    onNavSelect: (Int) -> Unit = {},
    navFR: FocusRequester = remember { FocusRequester() },
    onDown: () -> Unit = {},
    userInitials: String = "",
    onProfile: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    scrolled: Boolean = false,
    // R250 (FR-R250-3) — fully opaque to whatever scrolls under it while J's backdrop is showing: a
    // rail lit by the backdrop used to show through the 0.95 surface as clutter behind the nav.
    opaque: Boolean = false,
    title: String? = null,   // R136: page context (e.g. channel name) shown after the brand lockup
    /**
     * R267 (FR-R267-2) — the one slot in the handset top row that belongs to a **page**: today the
     * Library type dropdown, and nothing else. It is deliberately a slot and not a growing list of
     * optional controls: the whole difference between this and a permanent *Kategorier* button is
     * that it is **empty on every page that does not own it**, so it can never become a home for
     * controls with nowhere else to live.
     *
     * Ignored when [LocalHandset] is false — the TV and the web app keep the single row.
     */
    handsetTopSlot: (@Composable () -> Unit)? = null,
    /** R321 (FR-R321-4, J3) — a mark beside the brand on a handset: music mode's note, the one always-visible sign of
     *  which mode the phone is in. Null everywhere else. */
    brandBadge: (@Composable () -> Unit)? = null,
    /** R364 (FR-R364-3) / R365 (FR-R365-5) — the avatar's requester, so a page can put focus on the control it was
     *  opened from (My List and Settings come from the profile menu) instead of the active tab. Null = the bar's own. */
    avatarFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    // R337 (FR-R337-5, dev review 5) — on the desktop the page's top bar is the platform's toolbar: the sidebar or rail
    // holds the pages, the search and the viewer, so every screen's AppBar call stays as it is.
    if (dev.jellystructure.ravilo.ui.theme.LocalLayoutFamily.current == dev.jellystructure.ravilo.ui.theme.LayoutFamily.DESKTOP) {
        DesktopToolbar(title = title, solid = opaque || scrolled, modifier = modifier)
        return
    }
    val colors = RaviloTheme.colors
    val sora = Sora
    val spaceGrotesk = SpaceGrotesk
    val ownAvatarFR = remember { FocusRequester() }
    val avatarFR = avatarFocusRequester ?: ownAvatarFR
    // R365 (FR-R365-5) — Back from a page the profile menu opened (Settings, My List, Your profile): the page underneath
    // gets its focus back on the avatar, not on its own default (the active tab). One shot, consumed here; it waits two
    // frames so the page's own arrival focus has run first. A TV/remote affair: a phone draws no focus (R298).
    val avatarReturn = LocalAvatarReturn.current
    if (avatarReturn != null && onProfile != null && !LocalHandset.current) {
        LaunchedEffect(avatarReturn) {
            withFrameNanos { }
            withFrameNanos { }
            avatarFR.requestFocusAwaiting()
            avatarReturn()
        }
    }
    val searchFR = remember { FocusRequester() }
    // R52: Search is no longer a nav tab — it lives as the magnifier icon in the right cluster.
    val items = navItems ?: listOf(
        str("nav.home"), str("nav.movies"), str("nav.series"), str("nav.my_list"),
    )
    // R352 (FR-R352-3) — the vignette follows the theme: black on a dark one (unchanged), the page's own colour on a
    // light one. A fixed black drew a grey band across Daylight's top bar.
    val light = colors.isLight
    val pageColor = colors.background
    val barGradient = remember(light, pageColor) {
        Brush.verticalGradient(
            0f to (if (light) pageColor.copy(alpha = 0.55f) else Color(0x8C000000)),
            1f to Color.Transparent,
        )
    }
    // R62: animate from transparent (hero mode) to solid surface (scrolled mode)
    val solidBg by animateColorAsState(
        targetValue = when {
            opaque -> colors.surface
            scrolled -> colors.surface.copy(alpha = 0.95f)
            else -> Color.Transparent
        },
        animationSpec = tween(180),
        label = "appBarBg",
    )

    // R67: place navFR at the activeNav slot so that callers calling navFR.requestFocus() land on
    // the currently active tab instead of always landing on item 0 (Home).
    val innerFRs = remember(items.size) { List(items.size) { FocusRequester() } }
    val allFRs: List<FocusRequester> = remember(navFR, innerFRs, activeNav) {
        innerFRs.mapIndexed { i, fr -> if (i == activeNav.coerceIn(0, innerFRs.lastIndex)) navFR else fr }
    }
    var focusedIdx by remember { mutableIntStateOf(-1) }

    // Bug fix: on a narrow/portrait screen the nav items + search + clock + avatar don't all fit and
    // used to just clip off the edge. horizontalScroll can't coexist with the weight(1f) spacer below
    // (it needs a bounded width to compute leftover space; a scrollable Row gives unbounded width) —
    // so compact mode drops the flexible spacer and scrolls instead. Wide (TV/desktop) is untouched:
    // no scroll, same weight-based layout as before.
    val compact = LocalCompact.current
    val navScrollState = rememberScrollState()
    // R267 (FR-R267-1) — the handset layout, gated on R256's platform-class seam. Not a width
    // breakpoint: a phone in landscape is still a phone, and a narrow `ravilo-web` window is not one.
    // When false, everything below is exactly what shipped.
    val handset = LocalHandset.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(RaviloDimens.appBarHeight)
            .background(solidBg)         // R62: solid layer (transparent when at top)
            .background(barGradient),    // gradient vignette on top
    ) {
        if (handset) {
            // R267 (FR-R267-2) — brand · the page's own control if it has one · cast. Nothing else:
            // search and the profile are bottom-bar items now (FR-R267-5), there is no page-context
            // text, and there is no clock (FR-R267-3 — the platform draws one in the status bar
            // directly above this, and a second one 40 px below it is furniture).
            //
            // No horizontal scroll here either. `LocalCompact`'s scroll was the defect this phase was
            // written against: it "fixes" a crowded bar by letting the brand, the cast button and the
            // avatar slide off-screen at rest.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = raviloHPad).matchParentSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BrandMark(size = 26.dp)
                    Text(
                        text = "Ravilo",
                        color = colors.text,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = spaceGrotesk,
                        letterSpacing = (-1).sp,
                    )
                    brandBadge?.invoke()
                }
                Box(modifier = Modifier.weight(1f))
                handsetTopSlot?.invoke()
                // FR-R267-4 — present or absent, never a gap. CastButton renders nothing when
                // Chromecast is not set up or the platform has no sender, and the spacing closes up.
                CastButton()
            }
            return@Box
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = raviloHPad)
                .matchParentSize()
                .then(if (compact) Modifier.horizontalScroll(navScrollState) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),   // R52: denser (was 28)
        ) {
            // Brand lockup (R51): gradient jellyfish mark + ink wordmark (not accent-purple text).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BrandMark(size = 26.dp)
                Text(
                    text = "Ravilo",
                    color = colors.text,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = spaceGrotesk,
                    letterSpacing = (-1).sp,
                )
            }

            // R136: page context (channel name) between the brand and the nav tabs.
            if (title != null) {
                Text(
                    text = "· $title",
                    color = colors.textSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = sora,
                    maxLines = 1,
                )
            }

            items.forEachIndexed { i, label ->
                val isFocused = focusedIdx == i
                val isActive  = i == activeNav

                val bgColor by animateColorAsState(
                    targetValue = if (isFocused) colors.text else Color.Transparent,
                    animationSpec = tween(120),
                    label = "navBg$i",
                )
                val textColor by animateColorAsState(
                    targetValue = when {
                        isFocused -> colors.background
                        isActive  -> colors.text
                        else      -> colors.textSecondary
                    },
                    animationSpec = tween(120),
                    label = "navText$i",
                )

                Text(
                    text = label,
                    color = textColor,
                    fontSize = 15.sp,                                   // R52: compacter (was 16)
                    fontWeight = if (isFocused || isActive) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = sora,
                    modifier = Modifier
                        .onFocusChanged { state ->
                            focusedIdx = if (state.isFocused) i
                                         else if (focusedIdx == i) -1
                                         else focusedIdx
                        }
                        .background(bgColor, RoundedCornerShape(11.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)  // R52: compacter (was 14×6)
                        .dpadFocusable(
                            focusRequester = allFRs[i],
                            onFocused  = { focusedIdx = i },
                            onLeft     = { if (i > 0) allFRs[i - 1].requestFocus() },
                            onRight    = {
                                // R52: rightmost nav item → search icon → avatar.
                                if (i < items.lastIndex) allFRs[i + 1].requestFocus()
                                else if (onSearch != null) searchFR.requestFocus()
                                else if (onProfile != null) avatarFR.requestFocus()
                            },
                            onDown     = onDown,
                            onSelect   = { onNavSelect(i) },
                        ),
                )
            }

            // Wide: push the right cluster to the far edge. Compact: no bounded width to weight
            // against (see above) — the Row's own spacedBy(18.dp) gap is enough; items just scroll.
            if (!compact) Box(modifier = Modifier.weight(1f))
            // R245 (FR-R245-1) — the cast button, present only when the server says Chromecast is set up
            // and this platform has a sender (CastButton renders nothing otherwise — never greyed).
            CastButton()
            // R52 right cluster: search icon · clock · avatar (gaps from the Row's spacedBy).
            if (onSearch != null) {
                SearchIcon(
                    focusRequester = searchFR,
                    onLeft = { allFRs.last().requestFocus() },
                    onRight = { if (onProfile != null) avatarFR.requestFocus() },
                    onDown = onDown,
                    onSelect = onSearch,
                )
            }
            ClockDisplay()
            if (onProfile != null) {
                ProfileAvatar(
                    initials = userInitials.ifEmpty { "?" },
                    focusRequester = avatarFR,
                    onLeft = { if (onSearch != null) searchFR.requestFocus() else allFRs.last().requestFocus() },
                    onDown = onDown,
                    onSelect = onProfile,
                )
            }
        }

        // Bug fix: a discoverable hint that there's more to scroll to — gesture-only horizontalScroll
        // has no visible affordance on its own. Only rendered when content actually overflows
        // (maxValue > 0); a wide screen where everything fits shows nothing, same as before this fix.
        if (compact && navScrollState.maxValue > 0) {
            NavScrollIndicator(navScrollState, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** R337 — whether this page has somewhere to go back to (the stack is deeper than one), provided by the frame. */
val LocalDesktopBack = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }
/** R337 — the page's name for GNOME's header bar (the sidebar's own name for it), when the page does not give one. */
val LocalDesktopTitle = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }
/** R337 — room at the toolbar's start for the Mac's traffic lights when no sidebar is there to hold them. */
val LocalDesktopChromeStart = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

/** R337 — the page Back left, for the toolbar's Forward; null when there is none. */
val LocalDesktopForward = androidx.compose.runtime.staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * R337 (FR-R337-5) — the desktop's toolbar over a page (`.mtb` / `.ghb` in `design/ravilo/desktop-directions.css`).
 * **macOS:** 52 dp, transparent, a glass pill with Back and Forward at its start and *Play on…* in a glass pill at its
 * end, laid over the page's hero; empty toolbar moves the window, as a title bar does. **GNOME:** the content's header
 * bar (46 dp), a flat Back, the page's title centred, the close button on the right (drawn by the window frame).
 * Solid once the page scrolls under it.
 */
@Composable
private fun DesktopToolbar(title: String?, solid: Boolean, modifier: Modifier) {
    val colors = RaviloTheme.colors
    val mac = dev.jellystructure.ravilo.ui.isMacPlatform
    val back = LocalDesktopBack.current
    val forward = LocalDesktopForward.current
    // The Mac's toolbar floats over the page (a hero runs up under it) and turns solid once the page scrolls. GNOME's
    // is a header bar (`.ghb`): always there, the page's colour, the page's name in the middle — the page starts under it.
    val bg by animateColorAsState(if (!mac) colors.background else if (solid) colors.background.copy(alpha = 0.94f) else Color.Transparent, tween(180), label = "deskBar")
    val shownTitle = title ?: LocalDesktopTitle.current
    Box(modifier.fillMaxWidth().height(if (mac) 52.dp else 46.dp).background(bg).windowDragArea()) {
        Row(
            Modifier.matchParentSize().padding(start = (if (mac) 14.dp else 6.dp) + LocalDesktopChromeStart.current, end = if (mac) 14.dp else dev.jellystructure.ravilo.ui.seams.windowControlsWidth(false).let { if (it > 0.dp) it + 20.dp else 8.dp }),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (mac) 8.dp else 6.dp),
        ) {
            val backLabel = str("action.back")
            val forwardLabel = str("desk.forward")
            if (mac) {
                // `.gl.two` — one glass pill, two chevrons; each is dimmed until there is a page that way.
                Row(Modifier.height(32.dp).deskGlass(RoundedCornerShape(16.dp)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 26.dp, height = 32.dp).then(if (back != null) Modifier.handCursor() else Modifier).clickable(enabled = back != null, interactionSource = remember { MutableInteractionSource() }, indication = null) { back?.invoke() }
                        .semantics { contentDescription = backLabel }, contentAlignment = Alignment.Center) {
                        DeskIcon(DeskIcon.BACK, colors.text.copy(alpha = if (back != null) 1f else 0.4f), 16.dp)
                    }
                    Box(Modifier.size(width = 26.dp, height = 32.dp).then(if (forward != null) Modifier.handCursor() else Modifier).clickable(enabled = forward != null, interactionSource = remember { MutableInteractionSource() }, indication = null) { forward?.invoke() }
                        .semantics { contentDescription = forwardLabel }, contentAlignment = Alignment.Center) {
                        DeskIcon(DeskIcon.FORWARD, colors.text.copy(alpha = if (forward != null) 1f else 0.4f), 16.dp)
                    }
                }
            } else if (back != null) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).handCursor().clickable(onClick = back).semantics { contentDescription = backLabel }, contentAlignment = Alignment.Center) {
                    DeskIcon(DeskIcon.BACK, colors.text, 18.dp)
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            DeskCastButton()
        }
        // `.ghb.center .tt` — the title sits in the middle of the bar, whatever is on either side of it.
        if (!mac && shownTitle != null) Text(shownTitle, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            fontFamily = dev.jellystructure.ravilo.ui.theme.SystemUiFont, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp))
    }
}

/** The Mac's glass, drawn: a translucent fill and a hairline (`.gl`). Nothing is blurred — Skia's backdrop blur costs a layer per button. */
@Composable
fun Modifier.deskGlass(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val colors = RaviloTheme.colors
    val fill = if (colors.isLight) Color.White.copy(alpha = 0.72f) else colors.surface.copy(alpha = 0.62f)
    return clip(shape).background(fill).border(1.dp, colors.fg.copy(alpha = 0.12f), shape)
}

/** R337 — *Play on…* in the desktop toolbar: a glass pill on the Mac, a flat button on GNOME; absent where nothing can be cast to. */
@Composable
private fun DeskCastButton() {
    val musicMode = LocalMusicMode.current
    // R360 (FR-R360-6, dev review item 7) — the shared rule, not the capability alone.
    val cast = LocalCast.current
    if (!rememberCastIconShown(cast, musicMode) || cast == null) return
    val colors = RaviloTheme.colors
    val mac = dev.jellystructure.ravilo.ui.isMacPlatform
    val link by cast.sender.link.collectAsState()
    val label = str("cast.sheet_music")
    val tint = if (link == dev.jellystructure.ravilo.ui.seams.CastLinkState.NONE) colors.text else colors.accentSecondary
    val shape = if (mac) RoundedCornerShape(16.dp) else RoundedCornerShape(6.dp)
    Box(
        (if (mac) Modifier.size(width = 46.dp, height = 32.dp).deskGlass(shape) else Modifier.size(34.dp).clip(shape))
            .handCursor().clickable { cast.openSheet(null, music = musicMode) }.semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { DeskIcon(DeskIcon.CAST, tint, if (mac) 16.dp else 18.dp) }
}

@Composable
private fun NavScrollIndicator(scrollState: ScrollState, modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = raviloHPad, vertical = 3.dp)
            .height(3.dp),
    ) {
        val viewport = scrollState.viewportSize.toFloat()
        val totalContent = viewport + scrollState.maxValue.toFloat()
        val thumbFraction = if (totalContent > 0f) (viewport / totalContent).coerceIn(0.12f, 1f) else 1f
        val scrollFraction = if (scrollState.maxValue > 0) scrollState.value.toFloat() / scrollState.maxValue.toFloat() else 0f
        val thumbOffset = maxWidth * (1 - thumbFraction) * scrollFraction

        Box(Modifier.fillMaxSize().background(colors.textSecondary.copy(alpha = 0.15f), RoundedCornerShape(2.dp)))
        Box(
            Modifier
                .fillMaxWidth(thumbFraction)
                .fillMaxHeight()
                .offset(x = thumbOffset)
                .background(colors.textSecondary.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun ProfileAvatar(
    initials: String,
    focusRequester: FocusRequester,
    onLeft: () -> Unit,
    onDown: () -> Unit,
    onSelect: () -> Unit,
) {
    val colors = RaviloTheme.colors
    var focused by rememberFocusVisual()
    val avatarUrl = LocalUserAvatarUrl.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(if (focused) colors.accent else colors.surfaceVariant, CircleShape)
            .then(if (focused) Modifier.border(2.dp, colors.focusRing, CircleShape) else Modifier)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onLeft = onLeft,
                onDown = onDown,
                onSelect = onSelect,
            )
            .testTag(APP_BAR_AVATAR_TAG),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarUrl != null) {
            RemoteImage(avatarUrl, null, Modifier.fillMaxSize().clip(CircleShape))
        } else {
            Text(
                text = initials,
                color = if (focused) colors.onAccent else colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = Sora,
            )
        }
    }
}

/**
 * R52 — the right-cluster search affordance: a 40 dp circle with a drawn magnifier glyph
 * (`design/ravilo/ravilo.css` `.search-ic`: circle r7 + 16.5,16.5→21,21 handle). Inverts on focus like
 * the avatar; `onSelect` opens the Search screen.
 */
@Composable
private fun SearchIcon(
    focusRequester: FocusRequester,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onDown: () -> Unit,
    onSelect: () -> Unit,
) {
    val colors = RaviloTheme.colors
    var focused by rememberFocusVisual()
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(if (focused) colors.accent else Color.Transparent, CircleShape)
            .then(if (focused) Modifier.border(2.dp, colors.focusRing, CircleShape) else Modifier)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onLeft = onLeft,
                onRight = onRight,
                onDown = onDown,
                onSelect = onSelect,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = if (focused) colors.onAccent else colors.text
        Canvas(modifier = Modifier.size(22.dp)) {
            val s = size.minDimension / 24f          // glyph authored in a 24×24 viewBox
            drawCircle(
                color = glyph,
                radius = 7f * s,
                center = Offset(11f * s, 11f * s),
                style = Stroke(width = 2f * s, cap = StrokeCap.Round),
            )
            drawLine(
                color = glyph,
                start = Offset(16.5f * s, 16.5f * s),
                end = Offset(21f * s, 21f * s),
                strokeWidth = 2f * s,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun ClockDisplay() {
    // R267 (FR-R267-3) — never on a handset. The handset row does not call this, but the guard lives
    // here too so a future call site cannot reintroduce a second clock 40 px under the platform's own.
    // On a TV it is the only clock in the room, so nothing changes there.
    if (LocalHandset.current) return
    val colors = RaviloTheme.colors
    val sora = Sora
    var timeStr by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
            timeStr = "${now.hour.toString().padStart(2, '0')}:${now.minute.toString().padStart(2, '0')}"
            delay(30_000L)
        }
    }

    Text(
        text = timeStr,
        color = colors.textSecondary,
        fontSize = 15.sp,
        fontFamily = sora,
        textAlign = TextAlign.End,
    )
}

/** R364 / R365 — the app bar's avatar, by tag for the focus tests. */
const val APP_BAR_AVATAR_TAG = "app-bar-avatar"

/**
 * R365 (FR-R365-5) — provided by the app frame to the page the profile menu was opened from, once that page is on top
 * again: a one-shot "put focus back on the avatar", consumed (called) by the page's [AppBar]. Null everywhere else.
 */
val LocalAvatarReturn = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }
