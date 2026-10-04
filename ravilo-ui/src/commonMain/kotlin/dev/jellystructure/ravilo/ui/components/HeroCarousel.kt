package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import dev.jellystructure.ravilo.ui.i18n.str
import androidx.compose.foundation.clickable
import dev.jellystructure.ravilo.ui.LocalServerBaseUrl
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.raviloHPad
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.shared.tv.Hero
import dev.jellystructure.shared.tv.MediaCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** R338 (FR-R338-2) — the hero sits on artwork, so it keeps dark tokens in a light theme ([KeepDark]). */
@Composable
fun HeroCarousel(
    items: List<Hero>,
    focusRequester: FocusRequester,
    heightDp: Dp = 460.dp,
    autoAdvanceSeconds: Int = 7,
    onOpenDetail: (MediaCard) -> Unit = {},
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    driftEnabled: () -> Boolean = { true },
    /** R337 — a computer's hero carries *Play* beside *More Info* (the TV's is button-less: OK opens the title). */
    onPlay: ((MediaCard) -> Unit)? = null,
    /** R365 (FR-R365-10) — the slide to start on (a hero id) and where the showing slide is reported. */
    initialHeroId: String? = null,
    onActiveChanged: (String) -> Unit = {},
) {
    // The layout is read here, outside KeepDark (which only swaps the colours).
    val desk = dev.jellystructure.ravilo.ui.theme.isDesktopLayout
    dev.jellystructure.ravilo.ui.theme.KeepDark {
        HeroCarouselContent(items, focusRequester, heightDp, autoAdvanceSeconds, onOpenDetail, onUp, onDown, driftEnabled, desk, onPlay,
            initialHeroId, onActiveChanged)
    }
}

@Composable
private fun HeroCarouselContent(
    items: List<Hero>,
    focusRequester: FocusRequester,
    heightDp: Dp = 460.dp,
    autoAdvanceSeconds: Int = 7,
    onOpenDetail: (MediaCard) -> Unit = {},
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    /** R101: Ken Burns drifts only while this is true (false ⇒ frozen). HomeScreen passes
     *  `!isScrollInProgress` so the hero stops its per-frame scaled redraw during a scroll gesture. */
    driftEnabled: () -> Boolean = { true },
    desk: Boolean = false,
    onPlay: ((MediaCard) -> Unit)? = null,
    /** R365 (FR-R365-10) — the slide to start on (a hero's id, kept by the page's store across the detail page and
     *  back); a hero that is gone (a refresh re-ordered or dropped it) starts at slide 1. */
    initialHeroId: String? = null,
    /** R365 (FR-R365-10) — told the id of the slide now showing, so the store can keep it. */
    onActiveChanged: (String) -> Unit = {},
) {
    val colors = RaviloTheme.colors
    val sora = Sora

    // R257 (FR-R257-4) — shared with the detail heroes; see HeroScrims.
    val heroScrims = rememberHeroScrims()
    val tintGradient = heroScrims.tint
    val floorGradient = heroScrims.homeFloor
    val dotInactiveColor = remember(colors.textSecondary) { colors.textSecondary.copy(alpha = 0.35f) }

    var activeIndex by remember { mutableIntStateOf(heroStartIndex(items.map { it.item.id }, initialHeroId)) }
    var resetTick   by remember { mutableIntStateOf(0) }
    // R374 — the hero draws no focus ring (reverses R350 FR-R350-8's hero ring, owner 2026-10-04). It is still the
    // focus target on arrival (R53); Left/Right page it, Up/Down/OK as before.

    if (items.isEmpty()) return
    // The heroes list can change size under us (R33 live config push). Never index past its end —
    // coerce on read (guards the frame before the effect runs) and snap a now-stale cursor back to
    // the start so the dots / auto-advance stay consistent.
    LaunchedEffect(items.size) { if (activeIndex > items.lastIndex) { activeIndex = 0; resetTick++ } }
    val active = items[activeIndex.coerceIn(0, items.lastIndex)]

    // R91: Ken Burns drift — each slide starts at 1.0 and drifts to 1.05 over ~9s.
    // Animatable.value is a snapshot State read inside graphicsLayer (draw-only, no recompose per frame).
    // R101: frozen while the home list is actively scrolling — collectLatest cancels the in-flight
    // drift the moment driftEnabled() flips false (kbScale holds its value), and resumes it over the
    // remaining distance when scrolling settles. Removes the full-width hero's per-frame scaled redraw
    // from competing with the scroll's frame budget.
    val kbScale = remember { Animatable(1.0f) }
    LaunchedEffect(activeIndex) {
        items.getOrNull(activeIndex)?.let { onActiveChanged(it.item.id) }   // R365 (FR-R365-10)
        kbScale.snapTo(1.0f)  // instant reset; the 600ms crossfade covers the snap
        snapshotFlow { driftEnabled() }
            .collectLatest { enabled ->
                if (!enabled) return@collectLatest
                val span = RaviloMotion.HERO_KEN_BURNS_SCALE - 1.0f
                val remaining = RaviloMotion.HERO_KEN_BURNS_SCALE - kbScale.value
                if (remaining <= 0f) return@collectLatest
                val ms = (RaviloMotion.HERO_KEN_BURNS_TRAVEL_MS * (remaining / span)).toInt()
                kbScale.animateTo(RaviloMotion.HERO_KEN_BURNS_SCALE, tween(ms, easing = LinearEasing))
            }
    }

    // R88: warm the next 2 slides' backdrop + logo before auto-advance fires.
    if (items.size > 1) {
        val prefetchCtx  = LocalPlatformContext.current
        val prefetchBase = LocalServerBaseUrl.current
        LaunchedEffect(activeIndex, prefetchBase) {
            val loader = SingletonImageLoader.get(prefetchCtx)
            for (offset in 1..2) {
                val next = items[(activeIndex + offset) % items.size]
                listOfNotNull(next.backdropUrl, next.logoUrl).forEach { url ->
                    val r = if (url.startsWith("/") && prefetchBase.isNotBlank()) "$prefetchBase$url" else url
                    loader.enqueue(ImageRequest.Builder(prefetchCtx).data(r).build())
                }
            }
        }
    }

    // Auto-advance; resets when the user manually changes slide. 0 seconds = off.
    if (autoAdvanceSeconds > 0 && items.size > 1) {
        LaunchedEffect(resetTick, autoAdvanceSeconds) {
            delay(autoAdvanceSeconds * 1_000L)
            activeIndex = (activeIndex + 1) % items.size
            resetTick++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp)
            // R114: clip the hero to its bounds so the Ken Burns scale on the backdrop can't bleed
            // past the box — otherwise the scaled image overflows below the scrim gradients
            // (which only fill the box) and shows a thin un-darkened line at the very bottom edge.
            .clipToBounds()
            // R53: the whole hero is one focusable surface — select opens detail, Left/Right page the
            // carousel (cyclic, since a full-bleed hero has no horizontal neighbour).
            // Bug fix: onDown used to be omitted, relying on native focus search to drop into the
            // channel rail / first content row — but the hero's focus box spans the full width while
            // the row below is left-anchored, so the "nearest" candidate was often the 2nd tile, not
            // the 1st (confirmed live on soveværelse TV). The caller now bridges explicitly, like onUp.
            .dpadFocusable(
                focusRequester = focusRequester,
                onLeft  = { activeIndex = (activeIndex - 1 + items.size) % items.size; resetTick++ },
                onRight = { activeIndex = (activeIndex + 1) % items.size; resetTick++ },
                onUp    = onUp,
                onDown  = onDown,
                onSelect = { onOpenDetail(active.item) },
            )
            // R121: touch-swipe paging for the phone/web targets — drag left → next, right → previous
            // (same cyclic step as the D-pad). Inert on TV (no pointer drag); horizontal-only so it
            // doesn't fight the home list's vertical scroll.
            .pointerInput(items.size) {
                if (items.size <= 1) return@pointerInput
                val threshold = 56.dp.toPx()
                var dragTotal = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onDragEnd = {
                        when {
                            dragTotal <= -threshold -> { activeIndex = (activeIndex + 1) % items.size; resetTick++ }
                            dragTotal >=  threshold -> { activeIndex = (activeIndex - 1 + items.size) % items.size; resetTick++ }
                        }
                    },
                ) { change, dragAmount -> dragTotal += dragAmount; change.consume() }
            },
    ) {
        // Backdrop image, crossfades between slides. R87: a neutral surface paints under the backdrop
        // while it loads so the hero never flashes blank (Hero carries no brand color of its own).
        AnimatedContent(
            targetState = active.backdropUrl,
            transitionSpec = {
                fadeIn(tween(RaviloMotion.HERO_CROSSFADE_IN_MS)) togetherWith
                    fadeOut(tween(RaviloMotion.HERO_CROSSFADE_OUT_MS))
            },
            label = "heroBg",
        ) { url ->
            RemoteImage(
                url = url,
                contentDescription = null,
                // R91: Ken Burns scale applied draw-only so it never recomposes the carousel on
                // animation frames.
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = kbScale.value
                        scaleY = kbScale.value
                    },
                alignment = RaviloDimens.heroBackdropAlignment,
                placeholderColor = colors.surface,
            )
        }

        // Horizontal tint — pulls the left third dark so text stays readable
        Box(modifier = Modifier.fillMaxSize().background(tintGradient))
        // Vertical floor — fades bottom to bg colour for seamless row entry
        Box(modifier = Modifier.fillMaxSize().background(floorGradient))

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start  = raviloHPad,
                    bottom = if (desk) 30.dp else RaviloDimens.heroBodyBot,
                    end    = 40.dp,
                ),
        ) {
            // Operator badge — "New Season", "4K", "Top 10", etc.
            val badge = active.badge
            if (!badge.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .background(colors.accentGradient, RoundedCornerShape(5.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = badge.uppercase(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = sora,
                        letterSpacing = 0.8.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            // Kicker — tagline from Hero or genre fallback
            val kicker = active.taglineKicker ?: active.item.genre
            if (!kicker.isNullOrEmpty()) {
                Text(
                    text = kicker.uppercase(),
                    color = colors.accentSecondary,
                    fontSize = if (desk) 11.5.sp else 14.sp,
                    fontWeight = if (desk) FontWeight.Bold else FontWeight.SemiBold,
                    fontFamily = sora,
                    letterSpacing = if (desk) 1.6.sp else 1.5.sp,
                )
                Spacer(Modifier.height(if (desk) 10.dp else 8.dp))
            }

            // R130: clearlogo when it loads, else the title as readable text — a missing or 404 logo
            // (the server always sends a logo proxy URL) no longer leaves a blank space.
            TitleLogoOrText(
                logoUrl = active.logoUrl, logoInk = active.logoInk,
                title = active.item.title,
                logoModifier = if (desk) Modifier.height(74.dp).width(280.dp) else Modifier.height(80.dp).width(300.dp),
            )

            // Meta: year · rating
            // Bug fix: remember()ed like the equivalent computation in SeriesDetailScreen/
            // MovieDetailScreen — this recomputed on every recomposition (every activeIndex change
            // from D-pad paging or the auto-advance timer) despite being trivial, stable input.
            val meta = remember(active.item.year, active.item.rating) {
                listOfNotNull(active.item.year?.toString(), active.item.rating).joinToString(" · ")
            }
            if (desk) {
                // `.meta` — the year in plain type, the rating as a tag.
                if (active.item.year != null || !active.item.rating.isNullOrBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        active.item.year?.let { Text(it.toString(), color = colors.textSecondary, fontSize = 13.sp, fontFamily = sora) }
                        active.item.rating?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = colors.text, fontSize = 12.sp, fontFamily = sora,
                                modifier = Modifier.background(colors.fg.copy(alpha = 0.10f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                }
            } else if (meta.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = meta,
                    color = colors.textDim,
                    fontSize = 15.sp,
                    fontFamily = sora,
                )
            }

            // Synopsis — overview from the hero item (server-provided)
            val synopsis = active.synopsis
            if (!synopsis.isNullOrBlank()) {
                Spacer(Modifier.height(if (desk) 12.dp else 10.dp))
                Text(
                    text = synopsis,
                    color = colors.textSecondary,
                    fontSize = if (desk) 13.5.sp else 14.sp,
                    lineHeight = 20.sp,
                    fontFamily = sora,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(if (desk) 480.dp else 560.dp),
                )
            }

            // R337 — a computer's hero has its buttons (`.btns`): a pointer wants something to press.
            if (desk) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (onPlay != null) DeskButton(str("action.play"), DeskIcon.PLAY, primary = true) { onPlay(active.item) }
                    DeskButton(str("action.more_info"), primary = onPlay == null) { onOpenDetail(active.item) }
                }
            }

            // R53: no action buttons — the whole hero opens detail; Left/Right pages the carousel.
        }

        // R53: page dots moved to the bottom-right (design `.hero-dots`); active = accent pill.
        if (items.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = if (desk) 28.dp else 48.dp, bottom = if (desk) 34.dp else RaviloDimens.heroBodyBot),
                horizontalArrangement = Arrangement.spacedBy(if (desk) 6.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { i, _ ->
                    val dotWidth: Dp by animateDpAsState(
                        targetValue = if (i == activeIndex) (if (desk) 20.dp else 28.dp) else (if (desk) 6.dp else 8.dp),
                        animationSpec = tween(RaviloMotion.HERO_DOT_TWEEN_MS),
                        label = "dot$i",
                    )
                    Box(
                        modifier = Modifier
                            .size(dotWidth, if (desk) 6.dp else 8.dp)
                            .clip(CircleShape)
                            .background(if (i == activeIndex) colors.accent else dotInactiveColor)
                            // R337 — a pointer picks a slide by its dot.
                            .then(if (desk) Modifier.clickable { activeIndex = i; resetTick++ } else Modifier),
                    )
                }
            }
        }
    }
}

/** R365 (FR-R365-10) — the slide the hero starts on: the saved hero's index, else (gone, or none saved) the first. */
fun heroStartIndex(heroIds: List<String>, savedId: String?): Int =
    savedId?.let { heroIds.indexOf(it) }?.takeIf { it >= 0 } ?: 0
