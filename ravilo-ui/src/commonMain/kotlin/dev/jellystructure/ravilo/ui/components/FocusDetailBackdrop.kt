package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import dev.jellystructure.ravilo.ui.focus.FocusDetailUi
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.LocalRaviloSkin
import dev.jellystructure.ravilo.ui.theme.RaviloMotion
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.Skin
import kotlinx.coroutines.delay

/**
 * Phase R242 — J's own backdrop. While a Home row is open (`fd?.mode == "rowOpen"`), the focused
 * title's own backdrop fills the whole screen behind everything (app bar, every row), fading in on
 * open and back to plain `--bg` on close (FR-R242-6). A lateral hop within the same open row
 * crossfades directly between the two backdrops (FR-R242-5) — it never blanks.
 *
 * No new payload, no new DTO (FR-R242-2): [MediaCard.backdropUrl] already rides every Home
 * content-row card, the same field the hero already renders via [RemoteImage]. `fieldsFor`'s
 * artwork-free `FocusDetailFacts` (Phase 202 FR-202-5) is untouched — this reads [FocusDetailUi.card]
 * directly, deliberately bypassing `facts` so that non-goal stays visible in the code, not just prose.
 *
 * Caller passes the raw, live `fd` (`store.focusDetail.current.collectAsState()`) — this composable
 * owns the "hold the last open card through a dwell gap" bookkeeping itself (mirroring
 * `ContentRowItem`'s own `panelKey`/`panelUi` snapshot pattern in HomeScreen.kt) so a hop's brief
 * `fd == null` reset (FocusDetailController.onFocus always clears before its dwell) never reads back
 * as a visible blank — only a settle onto something that ISN'T `rowOpen` actually clears it.
 */
@Composable
fun FocusDetailBackdrop(fd: FocusDetailUi?, modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    val noir = LocalRaviloSkin.current == Skin.NOIR

    val bgActive = fd?.mode == "rowOpen"
    var bgUi by remember { mutableStateOf<FocusDetailUi?>(null) }
    if (bgActive) {
        // Updated synchronously during composition (not inside an effect): the instant a new tile
        // commits to opening is the same frame `openPanel`/`open = true` starts tweening in
        // ContentRowItem, so the picture and the panel start their (differently-paced) fades together.
        bgUi = fd
    }
    // fd drops to null for the dwell on EVERY focus move, including a hop between two tiles in the
    // same already-open row — bgUi deliberately keeps the last frame's card through that gap (see
    // below) so the crossfade has something to hold while the new tile's own dwell runs. Only a
    // genuine settle onto a non-"rowOpen" mode (leaving the row, a line-only tile, off Home) actually
    // clears it, once the exit fade below has had time to finish.
    LaunchedEffect(bgActive) {
        if (!bgActive) {
            delay(RaviloMotion.ROW_OPEN_BG_FADE_OUT_MS.toLong() + 60L)
            bgUi = null
        }
    }

    // A dark top/bottom gradient (FR-R242-7) — not `--hero-tint`'s left-to-right shape, built for the
    // hero's own fixed-left text block; this sits behind full-width row content on every edge alike.
    // Noir goes darker still (less colour, not less picture — the skin's existing precedent), never
    // drops the image itself.
    val scrim = remember(colors.background, noir) {
        if (noir) {
            Brush.verticalGradient(
                0.0f to colors.background.copy(alpha = 0.74f),
                0.2f to colors.background.copy(alpha = 0.48f),
                0.62f to colors.background.copy(alpha = 0.56f),
                1.0f to colors.background.copy(alpha = 0.72f),
            )
        } else {
            Brush.verticalGradient(
                0.0f to colors.background.copy(alpha = 0.62f),
                0.2f to colors.background.copy(alpha = 0.36f),
                0.62f to colors.background.copy(alpha = 0.46f),
                1.0f to colors.background.copy(alpha = 0.6f),
            )
        }
    }

    AnimatedVisibility(
        visible = bgActive,
        enter = fadeIn(tween(RaviloMotion.ROW_OPEN_BG_FADE_IN_MS)),
        exit = fadeOut(tween(RaviloMotion.ROW_OPEN_BG_FADE_OUT_MS)),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize()) {
            // FR-R242-2 — a null backdropUrl (Jellyfin holds none for this item) falls back to a plain
            // surface fill, same as RemoteImage's own no-art grounding elsewhere; never a broken image,
            // never reserved/blocked layout.
            AnimatedContent(
                targetState = bgUi?.card?.backdropUrl,
                transitionSpec = {
                    fadeIn(tween(RaviloMotion.ROW_OPEN_BG_FADE_IN_MS)) togetherWith
                        fadeOut(tween(RaviloMotion.ROW_OPEN_BG_FADE_OUT_MS))
                },
                label = "jBg",
            ) { url ->
                if (url != null) {
                    RemoteImage(
                        url = url,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        placeholderColor = colors.surface,
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(colors.surface))
                }
            }
            Box(Modifier.fillMaxSize().background(scrim))
        }
    }
}
