package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.LocalUserAvatarUrl
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.accentGradient

/**
 * R267 — the phone's page navigation: **Home · Library · Search · Discover · Profile**.
 *
 * ## Why a bottom bar at all
 *
 * `AppBar`'s compact mode "fixes" a crowded row on a phone by making the whole thing scroll
 * sideways, which pushes the brand, the cast button and the avatar off-screen **at rest**. The pages
 * moved to the bottom, where a thumb reaches them.
 *
 * ## Why these five, in this order
 *
 * Home is left because it is where the app opens and where Back ends up. **Search is the literal
 * centre**: it is the item reached without looking, and the one a viewer goes to from any other page.
 * Profile is bottom-right, drawn as the profile picture itself. Five is what fills a bar without
 * crowding it — a centre slot needs an odd count, and three read as empty on a 915 px screen.
 *
 * ## The selection is a shape, and the shape moves
 *
 * One pill in the brand gradient **slides** between items (~180 ms), so the bar reads as a single
 * object rather than five independent states. On a phone the selection may never be carried by focus,
 * by hover, or by two shades of grey alone: touch has none of the first two, and the third is what
 * direction A was rejected for.
 *
 * ## Profile takes the pill too (R304)
 *
 * R267 FR-R267-5d drew Profile as a menu that never took the pill. R304 made it a page like the other
 * four, so it does — and because a photo or initials on the gradient could be unreadable, the lit
 * avatar gets a white ring (2 dp gap, 1.5 dp ring; FR-R304-1).
 */
@Composable
fun RaviloBottomNav(
    selected: BottomNavItem?,
    onSelect: (BottomNavItem) -> Unit,
    userInitials: String,
    modifier: Modifier = Modifier,
) {
    val colors = RaviloTheme.colors
    val items = BottomNavItem.entries
    // The pill's horizontal position is derived from the selected item's index, so one animated value
    // moves one element rather than five states cross-fading (FR-R267-6).
    //
    // R278 (FR-R278-2) — [selected] is now genuinely nullable: My List and the account screens are
    // none of the four, and the bar is drawn on them. The pill is then not drawn at all rather than
    // defaulting to index 0, which would light Home on a page that is not Home. Its last position is
    // parked so that coming back to a page slides it from where it was, not in from the left.
    val selectedIndex = selected?.let { items.indexOf(it) }
    var parkedIndex by remember { mutableIntStateOf(selectedIndex ?: 0) }
    LaunchedEffect(selectedIndex) { if (selectedIndex != null) parkedIndex = selectedIndex }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // FR-R267-7 — opaque, with a hairline top edge. Not translucent and not floating: a
            // blurred capsule puts ink over a scrolling poster wall (the trap R245's remote
            // directions already rejected) and leaves the cast mini bar nothing to dock against.
            .background(colors.surface),
    ) {
        // No dedicated hairline token in the palette; a low-alpha ink is the idiom the rest of
        // the app uses for a divider, and it reads correctly in all three skins.
        Box(Modifier.fillMaxWidth().height(HAIRLINE).background(colors.textDim.copy(alpha = 0.25f)))
        // R274 (FR-R274-1) — bottomNavHeight is the WHOLE bar, so the row of items gets what is left
        // after the hairline. It used to be the row's own height while the token was also what
        // content offset by, which is how the bar ended up one dp shorter than the cell inside it.
        Box(Modifier.fillMaxWidth().height(RaviloDimens.bottomNavHeight - HAIRLINE)) {
            // The sliding pill, drawn under the items so an item's own icon and label sit on top of it.
            BoxWithItemWidth { itemWidth ->
                val pillX by animateDpAsState(
                    targetValue = itemWidth * parkedIndex + (itemWidth - PILL_WIDTH) / 2,
                    animationSpec = tween(PILL_SLIDE_MS),
                    label = "bottomNavPill",
                )
                if (selected != null) {
                    Box(
                        Modifier
                            .offset(x = pillX, y = PILL_TOP)
                            .size(PILL_WIDTH, PILL_HEIGHT)
                            .background(colors.accentGradient, RoundedCornerShape(PILL_HEIGHT / 2)),
                    )
                }
            }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                items.forEach { item ->
                    BottomNavCell(
                        item = item,
                        isSelected = item == selected,
                        userInitials = userInitials,
                        onClick = { onSelect(item) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** The five items, in the order they are drawn. Search is deliberately the middle one. */
enum class BottomNavItem { HOME, LIBRARY, SEARCH, DISCOVER, PROFILE }

/**
 * R267 (FR-R267-9) — runs [onReselect] each time the bar's item for the page on screen is tapped
 * again, i.e. each time [tick] changes **after this page composed**.
 *
 * A page composed with a tick already past zero is not being re-tapped: that is Back to a page that
 * was re-tapped before it was left, and firing then would scroll the position R295 just restored
 * straight back to the top. Comparing against the tick the page was composed with, rather than
 * against zero, is what tells the two apart.
 */
@Composable
fun OnReselect(tick: Int, onReselect: suspend () -> Unit) {
    val composedWith = remember { tick }
    LaunchedEffect(tick) { if (tick != composedWith) onReselect() }
}

@Composable
private fun BottomNavCell(
    item: BottomNavItem,
    isSelected: Boolean,
    userInitials: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RaviloTheme.colors
    Column(
        modifier = modifier
            // FR-R267-5 — the whole cell is the target, so it is comfortably past the 46 dp floor
            // even though the pill inside it is 56 dp wide and 32 dp tall.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            // R274 (FR-R274-1) — the top padding IS PILL_TOP, because it is the same edge: the pill is drawn in the parent Box, the glyph in this cell, and they line up
            // only while the two agree. The bottom padding is the point of the phase — the label used
            // to end at the bar's own edge, so on a phone whose gesture inset the keyboard had
            // subsumed it ended at the keys.
            .padding(top = PILL_TOP, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(PILL_HEIGHT), contentAlignment = Alignment.Center) {
            when (item) {
                // FR-R267-5d — the profile item's icon is the viewer's own avatar: the photo when
                // there is one, initials when there is not.
                BottomNavItem.PROFILE -> ProfileDot(userInitials, isSelected)
                else -> BottomNavGlyph(item, if (isSelected) colors.onAccent else colors.textSecondary)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = bottomNavLabel(item),
            color = if (isSelected) colors.text else colors.textSecondary,
            // FR-R267-6a — 11.5 sp, below the 13 sp floor R234/R244 set for body copy. These are
            // permanent, icon-paired, one-word labels in the platform's own idiom, and holding 13 sp
            // would make the bar taller than a platform bar for no gain in legibility. This is the
            // ONLY exception in the phone design and is not a precedent for content copy.
            fontSize = 11.5.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = Sora,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProfileDot(initials: String, selected: Boolean) {
    val colors = RaviloTheme.colors
    val avatarUrl = LocalUserAvatarUrl.current
    // R304 (FR-R304-1) — the ring only while lit: a 1.5 dp white ring with a 2 dp gap around the avatar,
    // so the photo never sits on the pill's gradient unreadably.
    val ring = if (selected) Modifier.border(RING_WIDTH, Color.White, CircleShape).padding(RING_WIDTH + RING_GAP) else Modifier.padding(RING_WIDTH + RING_GAP)
    Box(
        Modifier.size(GLYPH_BOX + (RING_WIDTH + RING_GAP) * 2).then(ring).background(colors.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarUrl != null) {
            RemoteImage(avatarUrl, null, Modifier.fillMaxSize().clip(CircleShape))
        } else {
            Text(
                text = initials.ifEmpty { "?" },
                color = colors.text,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = Sora,
            )
        }
    }
}

/**
 * R274 — the four page marks, drawn as **the design's own icons**: one stroked path set on a shared
 * 24-unit grid (`design/ravilo/Ravilo Mobile.html`'s `.bn svg`, `viewBox="0 0 24 24"`,
 * `stroke-width: 2`, round caps and joins), scaled into one fixed box.
 *
 * ## Why not text glyphs any more
 *
 * R267 drew these as Unicode characters — `⌂ ▤ ⌕ ✧` — to avoid an asset pipeline. They cost one
 * instead: **a font size is not an optical size**. Measured on a Pixel 9 at a shared 26 sp, the ink
 * came out `✧` 20.0 dp, `▤` 15.6 dp, `⌕` 14.2 dp — the magnifier at 71 % of the star, which is what
 * it looked like. Per-glyph sizes could paper over that on *this* phone, but the ratios are the
 * system font's, so any device that substitutes a different one brings the unevenness straight back.
 *
 * A path set has no such freedom: every icon is drawn into the same box, from the same grid, with the
 * same stroke, so their heights and their centres agree by construction. This is not an asset
 * pipeline either — no file, no loader, and `tint` still follows the skin exactly as before.
 *
 * It also puts Discover back: the design draws a **compass**, and `✧` had quietly become a sparkle.
 */
@Composable
private fun BottomNavGlyph(item: BottomNavItem, tint: Color) {
    Canvas(Modifier.size(GLYPH_BOX)) {
        val u = size.minDimension / 24f           // one viewBox unit
        val sw = 2f * u                           // the design's stroke-width: 2, the SAME for all four
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)

        // Each icon's own drawn extent, and the scale that makes every one of them exactly
        // GEOM_UNITS tall. The design's paths are not the same height on the grid — measured on the
        // device they rendered 19.6 / 20.9 / 21.8 / 23.6 dp — which is ordinary optical sizing (a
        // circle is drawn a little larger to *look* equal to a square). Equal-by-measurement is what
        // was asked for, so each icon is scaled about its own centre to one ink height and one centre
        // line. The stroke is deliberately left OUT of the scale: scaling it too would make the
        // compass's outline visibly thinner than the house's, which is the unevenness this is fixing.
        val (cx, cy, span) = when (item) {
            BottomNavItem.HOME -> Triple(12f, 11.5f, 15f)      // x 4..20, y 4..19
            BottomNavItem.LIBRARY -> Triple(11f, 12f, 16f)     // x 3..19, y 4..20
            BottomNavItem.SEARCH -> Triple(12.25f, 12.25f, 16.5f)
            BottomNavItem.DISCOVER -> Triple(12f, 12f, 18f)    // the r9 circle is the tallest
            BottomNavItem.PROFILE -> Triple(12f, 12f, 16f)     // unreachable; see below
        }
        val g = GEOM_UNITS / span
        fun at(x: Float, y: Float) =
            Offset(size.width / 2f + (x - cx) * g * u, size.height / 2f + (y - cy) * g * u)
        fun len(v: Float) = v * g * u
        fun path(vararg pts: Pair<Float, Float>, close: Boolean = false) = Path().apply {
            pts.forEachIndexed { i, (x, y) ->
                val o = at(x, y); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
            }
            if (close) close()
        }
        when (item) {
            // M4 11l8-7 8 7 · M6 10v9h12v-9
            BottomNavItem.HOME -> {
                drawPath(path(4f to 11f, 12f to 4f, 20f to 11f), tint, style = stroke)
                drawPath(path(6f to 10f, 6f to 19f, 18f to 19f, 18f to 10f), tint, style = stroke)
            }
            // rect 3,4 13x16 r2 · M19 6v14 · M7 8h5 · M7 12h5
            BottomNavItem.LIBRARY -> {
                drawRoundRect(tint, at(3f, 4f), Size(len(13f), len(16f)), CornerRadius(len(2f), len(2f)), stroke)
                drawLine(tint, at(19f, 6f), at(19f, 20f), sw, StrokeCap.Round)
                drawLine(tint, at(7f, 8f), at(12f, 8f), sw, StrokeCap.Round)
                drawLine(tint, at(7f, 12f), at(12f, 12f), sw, StrokeCap.Round)
            }
            // circle 11,11 r7 · M20.5 20.5l-4.2-4.2
            BottomNavItem.SEARCH -> {
                drawCircle(tint, len(7f), at(11f, 11f), style = stroke)
                drawLine(tint, at(16.3f, 16.3f), at(20.5f, 20.5f), sw, StrokeCap.Round)
            }
            // circle 12,12 r9 · M15.5 8.5l-2 5-5 2 2-5z — a compass, not a sparkle
            BottomNavItem.DISCOVER -> {
                drawCircle(tint, len(9f), at(12f, 12f), style = stroke)
                drawPath(
                    path(15.5f to 8.5f, 13.5f to 13.5f, 8.5f to 15.5f, 10.5f to 10.5f, close = true),
                    tint, style = stroke,
                )
            }
            // The profile item draws the viewer's own avatar instead (FR-R267-5d); this is unreachable.
            BottomNavItem.PROFILE -> Unit
        }
    }
}

@Composable
private fun bottomNavLabel(item: BottomNavItem): String = when (item) {
    BottomNavItem.HOME -> str("nav.home")
    BottomNavItem.LIBRARY -> str("nav.library")
    BottomNavItem.SEARCH -> str("nav.search")
    BottomNavItem.DISCOVER -> str("nav.discover")
    BottomNavItem.PROFILE -> str("nav.profile")
}

/** Gives its content the width of one of five equal cells, so the pill can be positioned in dp. */
@Composable
private fun BoxWithItemWidth(content: @Composable (Dp) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        content(maxWidth / BottomNavItem.entries.size)
    }
}

// R274 — sized on the device rather than from the mockup's 393 px frame: the pill grew so the icons
// read at arm's length, and RaviloDimens.bottomNavHeight grew with it.
// The label did not grow: 11.5 sp is R267 FR-R267-6a's one fenced exception to the 13 sp floor, and
// bigger glyphs are not a reason to reopen it.
/**
 * The box every page mark is drawn into — and the avatar's diameter, so all five items in the row are
 * the same height and share one centre line. The mockup holds the same relationship (`.bn svg` 23 px
 * beside `.avatar.sm` 24 px); this states it as one number so they cannot drift apart again.
 */
private val GLYPH_BOX = 28.dp

/**
 * The drawn height every page mark is scaled to, in the design's own 24-unit grid — so the four icons
 * have one ink height and one centre line, whatever each path's natural extent on the grid is. 16 of
 * 24 units leaves room for the 2-unit stroke on both sides without any icon touching its box.
 */
private const val GEOM_UNITS = 16f
private val RING_WIDTH = 1.5.dp
private val RING_GAP = 2.dp
private val PILL_WIDTH = 60.dp
private val PILL_HEIGHT = 36.dp
/** Also the cell's top padding — see [BottomNavCell]. The mockup's `.bnav { padding-top: 9px }`, +1. */
private val PILL_TOP = 10.dp
private val HAIRLINE = 1.dp
private const val PILL_SLIDE_MS = 180
