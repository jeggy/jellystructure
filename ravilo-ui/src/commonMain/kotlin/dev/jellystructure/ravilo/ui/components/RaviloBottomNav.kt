package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * ## Profile never takes the pill
 *
 * It opens a menu, not a page (FR-R267-5d) — nothing about *which page you are on* has changed when
 * you open it, so the pill stays where it was.
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
    val selectedIndex = selected?.let { items.indexOf(it) } ?: 0

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
                    targetValue = itemWidth * selectedIndex + (itemWidth - PILL_WIDTH) / 2,
                    animationSpec = tween(PILL_SLIDE_MS),
                    label = "bottomNavPill",
                )
                Box(
                    Modifier
                        .offset(x = pillX, y = PILL_TOP)
                        .size(PILL_WIDTH, PILL_HEIGHT)
                        .background(colors.accentGradient, RoundedCornerShape(PILL_HEIGHT / 2)),
                )
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
                BottomNavItem.PROFILE -> ProfileDot(userInitials)
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
private fun ProfileDot(initials: String) {
    val colors = RaviloTheme.colors
    val avatarUrl = LocalUserAvatarUrl.current
    Box(
        Modifier.size(24.dp).background(colors.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarUrl != null) {
            RemoteImage(avatarUrl, null, Modifier.fillMaxSize().clip(CircleShape))
        } else {
            Text(
                text = initials.ifEmpty { "?" },
                color = colors.text,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = Sora,
            )
        }
    }
}

/**
 * Text glyphs rather than vector icons, matching how this codebase already draws its small
 * affordances (`ScreensSheet`'s TV and AirPlay glyphs, the picker's chevrons) — no new asset
 * pipeline, and they follow the skin's ink automatically.
 */
@Composable
private fun BottomNavGlyph(item: BottomNavItem, tint: Color) {
    val glyph = when (item) {
        BottomNavItem.HOME -> "⌂"
        BottomNavItem.LIBRARY -> "▤"
        BottomNavItem.SEARCH -> "⌕"
        BottomNavItem.DISCOVER -> "✧"
        BottomNavItem.PROFILE -> "●"
    }
    Text(text = glyph, color = tint, fontSize = 18.sp)
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

private val PILL_WIDTH = 56.dp
private val PILL_HEIGHT = 32.dp
/** Also the cell's top padding — see [BottomNavCell]. Mirrors the mockup's `.bnav { padding-top: 9px }`. */
private val PILL_TOP = 9.dp
private val HAIRLINE = 1.dp
private const val PILL_SLIDE_MS = 180
