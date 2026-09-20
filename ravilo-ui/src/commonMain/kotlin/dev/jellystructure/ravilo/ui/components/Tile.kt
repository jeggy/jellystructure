package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloColors
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.accentGradient

enum class TileVariant { POSTER, LANDSCAPE, SQUARE }

/** Map the operator's configured tile shape (R32 §F) to a tile variant. */
fun dev.jellystructure.shared.tv.TileShape.toTileVariant(): TileVariant = when (this) {
    dev.jellystructure.shared.tv.TileShape.LANDSCAPE -> TileVariant.LANDSCAPE
    dev.jellystructure.shared.tv.TileShape.SQUARE -> TileVariant.SQUARE
    else -> TileVariant.POSTER
}

private val POSTER_W    = 155.dp
private val POSTER_H    = 232.dp
private val LANDSCAPE_W = 256.dp
private val LANDSCAPE_H = 144.dp
private val SQUARE_W    = 180.dp
private val SQUARE_H    = 180.dp

// R96: backdrop request width for LANDSCAPE tiles — covers a 256dp slot up to ~tileScale 1.25 on a
// 2× panel while staying ~9× smaller in memory/decode than the 1920px proxy default.
private const val LANDSCAPE_IMAGE_W = 640

/** R96: the proxy `?w=` a tile of [variant] requests, or null for poster/square (already ~320px).
 *  Exposed so a row's prefetch resolver requests the SAME width the tile will (shared cache key). */
fun tileRequestedWidth(variant: TileVariant): Int? =
    if (variant == TileVariant.LANDSCAPE) LANDSCAPE_IMAGE_W else null

/** A tile's own resting width, before [dev.jellystructure.ravilo.ui.LocalTileScale] and before
 *  R240's open-growth. Exposed so J can size its panel to the space a grown tile of this variant
 *  actually leaves — see [focusDetailPanelWidthFor]. */
fun tileBaseWidth(variant: TileVariant): Dp = when (variant) {
    TileVariant.POSTER -> POSTER_W
    TileVariant.LANDSCAPE -> LANDSCAPE_W
    TileVariant.SQUARE -> SQUARE_W
}

@Composable
fun Tile(
    title: String,
    posterUrl: String?,
    focusRequester: FocusRequester? = null,
    variant: TileVariant = TileVariant.POSTER,
    /** R177 follow-up: channel logos are small brand marks (often with transparent/white padding),
     *  not photographic backdrops — Crop cropped their top/bottom and let a white logo canvas bleed
     *  through the semi-transparent progress-bar track. OnNowRow passes Fit; every poster/backdrop
     *  caller keeps the old Crop default. */
    contentScale: ContentScale = ContentScale.Crop,
    subtitle: String? = null,
    /** User request ("show timestamps on the channels home screen") — a third, dimmer line below
     *  [subtitle] for the On Now row's current-program time range. Null = no line (every other
     *  caller). */
    caption: String? = null,
    progressPct: Float = 0f,
    watched: Boolean = false,
    isNew: Boolean = false,
    /** R113: small season/episode indicator (e.g. "S1:E3") overlaid on the image for TV shows in
     *  Continue Watching. Null = no badge. */
    episodeBadge: String? = null,
    /** R171 — overrides [episodeBadge]'s background; defaults to the existing neutral black pill.
     *  Lets Request tiles color-code by acquisition status (available/failed/in-progress) while every
     *  other caller is unaffected. */
    episodeBadgeColor: Color = Color.Black.copy(alpha = 0.6f),
    /** R149: "Soon • SxxExx" badge for continuing series with a scheduled episode. Null = no badge. */
    upcomingLabel: String? = null,
    /** Phase R240 (FR-R240-3/7) — true for the one tile J has opened. Grows the tile's actual layout
     *  width (not a draw-only scale — the row band's reflow IS the point here, unlike the focus-scale
     *  effect below) by [dev.jellystructure.ravilo.ui.theme.RaviloMotion.ROW_OPEN_WIDTH_SCALE], and
     *  hides the label/subtitle the way the mockup's `.jopen` rule does (the panel beside it already
     *  states the title). Never true together with a [FocusRequester] on this tile or any ancestor
     *  reaching into it via `focusRestorer()` — FR-R240-5 forbids both. */
    open: Boolean = false,
    onFocused: () -> Unit = {},
    onBlurred: () -> Unit = {},
    onSelect: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    // R87: per-title placeholder tint — the poster crossfades in over a related color, not blank.
    val fallbackHue = remember(title) { (title.hashCode().toLong() and 0xFFFFFFFFL) % 360L }
    val posterPlaceholder = remember(fallbackHue) { Color.hsl(fallbackHue.toFloat(), 0.30f, 0.18f) }
    var focused by remember { mutableStateOf(false) }
    // Snappier focus feel (R43): StiffnessMedium settles fast; soft StiffnessMediumLow read laggy.
    val focusSpec = remember { RaviloMotion.focusSpring<Float>() }
    val dpSpec    = remember { RaviloMotion.focusSpring<Dp>() }
    val scale         by animateFloatAsState(if (focused) RaviloMotion.TILE_FOCUS_SCALE else 1f, focusSpec, label = "tileScale")
    val ringWidth     by animateDpAsState(if (focused) 3.dp else 0.dp, dpSpec, label = "tileBorder")
    val glowElevation by animateDpAsState(if (focused) 24.dp else 0.dp, dpSpec, label = "tileShadow")
    val tileShape = remember(colors.tileRadius) { RoundedCornerShape(colors.tileRadius) }

    // Operator-configured content size (R: ui_density) scales every grid/row tile uniformly.
    val tileScale = dev.jellystructure.ravilo.ui.LocalTileScale.current
    val (baseW, baseH) = when (variant) {
        TileVariant.POSTER -> POSTER_W to POSTER_H
        TileVariant.LANDSCAPE -> LANDSCAPE_W to LANDSCAPE_H
        TileVariant.SQUARE -> SQUARE_W to SQUARE_H
    }.let { (bw, bh) -> bw * tileScale to bh * tileScale }

    // R240 (FR-R240-7) — unlike the focus scale below, this DOES change real layout width: J's whole
    // point is the row band growing, so the lazy row's measured bounds must actually move. Only ever
    // non-1x for the one tile J has opened.
    val openSpec = remember { tween<Dp>(RaviloMotion.ROW_OPEN_TWEEN_MS) }
    val w by animateDpAsState(if (open) baseW * RaviloMotion.ROW_OPEN_WIDTH_SCALE else baseW, openSpec, label = "tileOpenWidth")
    val h by animateDpAsState(if (open) baseH * RaviloMotion.ROW_OPEN_WIDTH_SCALE else baseH, openSpec, label = "tileOpenHeight")

    // R54: the focusable wraps the WHOLE tile (poster + label) so the vertical bring-into-view reveals
    // the title/subtitle below the poster instead of clipping it. R42 still holds for the FOCUS-SCALE
    // effect specifically — it's a draw-only graphicsLayer on the inner poster box below, so the lazy
    // list's focused-bounds tracking never chases IT (no viewport jump while merely focusing).
    Column(
        modifier = Modifier
            .width(w)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true; onFocused() },
                onBlurred = { focused = false; onBlurred() },
                onSelect = onSelect,
            ),
    ) {
        Box(
            modifier = Modifier
                .width(w)
                .height(h),
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    // R43: the whole focus animation runs in the draw phase — scale + shadow in the
                    // graphicsLayer lambda, the ring in drawWithCache — so no animated value is read at
                    // composition and the tile never recomposes per frame while focusing.
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        this.shadowElevation = glowElevation.toPx()
                        shape = tileShape
                        clip = true
                        ambientShadowColor = colors.focusGlow
                        spotShadowColor = colors.focusGlow
                    }
                    .drawWithCache {
                        val radius = CornerRadius(colors.tileRadius.toPx())
                        onDrawWithContent {
                            drawContent()
                            val bw = ringWidth.toPx()
                            if (bw > 0f) drawRoundRect(
                                color = colors.focusRing,
                                cornerRadius = radius,
                                style = Stroke(width = bw),
                                topLeft = Offset(bw / 2f, bw / 2f),
                                size = Size(size.width - bw, size.height - bw),
                            )
                        }
                    },
            ) {
            if (posterUrl != null) {
                // A persistent backdrop under the image, not just a load-time placeholder: a Crop
                // image fully covers it either way, but a Fit image (a logo) only paints its fitted
                // area, and without this the rest of the tile — and the progress bar's semi-transparent
                // track drawn over it below — would show raw black/transparent instead of a neutral card.
                if (contentScale == ContentScale.Fit) {
                    Box(modifier = Modifier.matchParentSize().background(colors.surfaceVariant))
                }
                RemoteImage(
                    url = posterUrl,
                    contentDescription = title,
                    modifier = Modifier.matchParentSize(),
                    contentScale = contentScale,
                    placeholderColor = posterPlaceholder,
                    // R96: LANDSCAPE tiles render a backdrop (proxy default 1920px) into a ~256dp slot —
                    // request ~640px so Coil decodes ~0.9 MB, not ~8.3 MB. POSTER/SQUARE already use
                    // poster-type proxy images sized to ~320px, so they pass null (backend default).
                    requestedWidth = tileRequestedWidth(variant),
                )
            } else {
                // HSL-style gradient fallback derived from title hash
                val fallbackGradient = remember(fallbackHue, colors.surface) {
                    Brush.verticalGradient(
                        listOf(
                            Color.hsl(fallbackHue.toFloat(), 0.38f, 0.22f),
                            colors.surface,
                        )
                    )
                }
                Box(
                    modifier = Modifier.matchParentSize().background(fallbackGradient),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title.take(2).uppercase(),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = sora,
                    )
                }
            }

            // R142: dim a watched poster (~brightness .62 via a dark scrim); un-dims on focus.
            if (watched && !focused) {
                Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.38f)))
            }

            // Progress bar (bottom, overlaid)
            if (progressPct > 0f && !watched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(colors.progressBg),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressPct)
                            .height(4.dp)
                            .background(colors.progressFill),
                    )
                }
            }

            // R249 (FR-R249-3) — one precedence table for the two top corners; see [resolveCornerBadges].
            // Two badges never share an anchor: `S17:E8` and `Soon • S18E07` used to be painted at the
            // same TopStart, and only the second one drawn was visible — on the one row whose tiles exist
            // to say which episode you are on.
            val corners = resolveCornerBadges(episodeBadge = episodeBadge, upcomingLabel = upcomingLabel, isNew = isNew, watched = watched)
            CornerBadgeContent(corners.topStart, Modifier.align(Alignment.TopStart), episodeBadge, episodeBadgeColor, upcomingLabel, colors, sora)
            CornerBadgeContent(corners.topEnd, Modifier.align(Alignment.TopEnd), episodeBadge, episodeBadgeColor, upcomingLabel, colors, sora)
            }
        }

        // R240 (FR-R240-3) — the mockup's `.jopen .label,.sub{display:none}`: the panel beside this
        // tile already states the title, so the caption underneath it would just repeat it.
        if (!open) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                color = if (focused) colors.text else colors.textSecondary,
                fontSize = 18.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                fontFamily = sora,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(w),
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    color = colors.textDim,
                    fontSize = 14.sp,
                    fontFamily = sora,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(w),
                )
            }
            if (!caption.isNullOrEmpty()) {
                Text(
                    text = caption,
                    color = colors.textDim,
                    fontSize = 12.sp,
                    fontFamily = sora,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(w),
                )
            }
        }
    }
}

/**
 * R187 (FR-RV-BROWSE1-1) — the "→ See all" end-of-row tile: a real tile in the row's own focus track,
 * matching [Tile]'s size/shape/focus-animation language exactly (same [variant] dimensions, the same
 * focus scale/ring/shadow spring) so it reads as one more card, not a bolted-on link. [count] is the
 * seed's true total (see [dev.jellystructure.shared.tv.Row.seedTotalCount]) — shown as-is once known;
 * pass null while it's still loading rather than a wrong/stale number.
 */
@Composable
fun SeeAllTile(
    count: Int?,
    variant: TileVariant = TileVariant.POSTER,
    focusRequester: FocusRequester? = null,
    onSelect: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val sora = Sora
    var focused by remember { mutableStateOf(false) }
    val focusSpec = remember { RaviloMotion.focusSpring<Float>() }
    val dpSpec    = remember { RaviloMotion.focusSpring<Dp>() }
    val scale         by animateFloatAsState(if (focused) RaviloMotion.TILE_FOCUS_SCALE else 1f, focusSpec, label = "seeAllScale")
    val ringWidth     by animateDpAsState(if (focused) 3.dp else 0.dp, dpSpec, label = "seeAllBorder")
    val glowElevation by animateDpAsState(if (focused) 24.dp else 0.dp, dpSpec, label = "seeAllShadow")
    val tileShape = remember(colors.tileRadius) { RoundedCornerShape(colors.tileRadius) }

    val tileScale = dev.jellystructure.ravilo.ui.LocalTileScale.current
    val (w, h) = when (variant) {
        TileVariant.POSTER -> POSTER_W to POSTER_H
        TileVariant.LANDSCAPE -> LANDSCAPE_W to LANDSCAPE_H
        TileVariant.SQUARE -> SQUARE_W to SQUARE_H
    }.let { (bw, bh) -> bw * tileScale to bh * tileScale }

    Column(
        modifier = Modifier
            .width(w)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onSelect = onSelect,
            ),
    ) {
        Box(
            modifier = Modifier
                .width(w).height(h)
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    this.shadowElevation = glowElevation.toPx()
                    shape = tileShape
                    clip = true
                    ambientShadowColor = colors.focusGlow
                    spotShadowColor = colors.focusGlow
                }
                .drawWithCache {
                    val radius = CornerRadius(colors.tileRadius.toPx())
                    onDrawWithContent {
                        drawContent()
                        val bw = ringWidth.toPx()
                        if (bw > 0f) drawRoundRect(
                            color = colors.focusRing,
                            cornerRadius = radius,
                            style = Stroke(width = bw),
                            topLeft = Offset(bw / 2f, bw / 2f),
                            size = Size(size.width - bw, size.height - bw),
                        )
                    }
                }
                .background(if (focused) colors.surfaceVariant else colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("→", color = colors.accent, fontSize = 26.sp, fontFamily = sora, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = str("browse.see_all_short"),
                    color = colors.text, fontSize = 14.sp, fontFamily = sora, fontWeight = FontWeight.SemiBold,
                )
                if (count != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(text = count.toString(), color = colors.textDim, fontSize = 12.sp, fontFamily = sora)
                }
            }
        }
        // Blank label lines so the tile's total height matches its poster/landscape siblings exactly
        // (Tile always reserves a title line below the image) — keeps the row's baseline aligned.
        Spacer(Modifier.height(2.dp))
        Text(text = "", fontSize = 15.sp, fontFamily = sora, modifier = Modifier.width(w))
    }
}

/** R249 (FR-R249-3) — which badge a tile's top corner shows. */
internal enum class CornerBadge { EPISODE, UPCOMING, NEW, WATCHED }

internal data class CornerBadges(val topStart: CornerBadge?, val topEnd: CornerBadge?)

/**
 * R249 (FR-R249-3) — the one precedence table for a tile's two top corners (first wins):
 *
 * | Corner    | Priority                                                        |
 * |-----------|-----------------------------------------------------------------|
 * | Top-start | `episodeBadge` → `upcomingLabel` → `NEW`                        |
 * | Top-end   | `watched ✓` → `upcomingLabel` (only when displaced from top-start) |
 *
 * A badge that loses its corner moves to its fallback corner if the table gives it one (`Soon` → top-end
 * on a Continue Watching tile, FR-R249-2) and is omitted otherwise (`NEW` has no fallback). `NEW` also
 * keeps its pre-existing "not on a watched title" guard — that is a different axis from the corner.
 */
internal fun resolveCornerBadges(episodeBadge: String?, upcomingLabel: String?, isNew: Boolean, watched: Boolean): CornerBadges {
    val topStart = when {
        episodeBadge != null -> CornerBadge.EPISODE
        upcomingLabel != null -> CornerBadge.UPCOMING
        isNew && !watched -> CornerBadge.NEW
        else -> null
    }
    val topEnd = when {
        watched -> CornerBadge.WATCHED
        upcomingLabel != null && topStart != CornerBadge.UPCOMING -> CornerBadge.UPCOMING
        else -> null
    }
    return CornerBadges(topStart, topEnd)
}

/** R249 — draws whichever badge [resolveCornerBadges] assigned to one corner; [modifier] carries the anchor. */
@Composable
private fun CornerBadgeContent(
    badge: CornerBadge?,
    modifier: Modifier,
    episodeBadge: String?,
    episodeBadgeColor: Color,
    upcomingLabel: String?,
    colors: RaviloColors,
    sora: FontFamily,
) {
    when (badge) {
        null -> {}
        // R142: watched ✓.
        CornerBadge.WATCHED -> Box(
            modifier = modifier.padding(8.dp).size(24.dp).background(colors.badgeWatched, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        // "NEW" gradient badge.
        CornerBadge.NEW -> Box(
            modifier = modifier.padding(8.dp).background(colors.accentGradient, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Text(text = str("tile.badge_new"), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = sora, letterSpacing = 0.5.sp)
        }
        // R113: season/episode badge — small dark pill over the image for TV shows in Continue Watching.
        // Neutral translucent black so it reads on any backdrop.
        CornerBadge.EPISODE -> Box(
            modifier = modifier.padding(8.dp).background(episodeBadgeColor, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(text = episodeBadge.orEmpty(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = sora, letterSpacing = 0.3.sp)
        }
        // R149: "Soon • SxxExx" badge. Solid accent background so it reads on any poster.
        CornerBadge.UPCOMING -> Row(
            modifier = modifier.padding(8.dp).background(colors.accent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(5.dp).background(Color.White, CircleShape))
            Spacer(Modifier.width(4.dp))
            Text(text = str("tile.badge_soon", mapOf("label" to upcomingLabel.orEmpty())), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = sora)
        }
    }
}
