package dev.jellystructure.ravilo.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.CastRemoteStatus
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.seams.safeAreaPadding
import dev.jellystructure.ravilo.ui.sessions.drawnPositionMs
import dev.jellystructure.ravilo.ui.sessions.sessionRowTitle
import dev.jellystructure.ravilo.ui.theme.RaviloDimens
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.accentGradient
import dev.jellystructure.shared.tv.SessionView

/**
 * DEB-2185 (FR-9) — what the one mini bar draws, whether it was read from this app's own Cast link or from the
 * server's row: the thumbnail, the title, the place in accent, ⏯ and a hairline of progress.
 */
data class MiniBarItem(
    val title: String,
    val artUrl: String?,
    val placeName: String,
    val placeIcon: String?,
    val playing: Boolean,
    /** 0..1 */
    val progress: Float,
    /** ⏯ is drawn only where the app can press it (the link; or a row the server says this app controls). */
    val controllable: Boolean,
    /** The next-track button (a song, an episode or a book's next part); never for a film. */
    val nextShown: Boolean = false,
)

/** FR-9 — the bar for a session the server lists (R368 FR-R368-8's pick). */
@Composable
fun sessionMiniBarItem(session: SessionView): MiniBarItem {
    val state = rememberSessionsState()
    val now = rememberSessionClock()
    val pos = drawnPositionMs(session, state.serverNowMs, state.receivedAtMs, now)
    val d = session.durationMs
    return MiniBarItem(
        title = sessionRowTitle(session) ?: str(if (session.kind == "music" || session.kind == "audiobook") "session.person_listening" else "session.person_watching", mapOf("person" to session.owner.name)),
        artUrl = session.artwork, placeName = session.target.name, placeIcon = session.target.icon,
        playing = session.state == "playing",
        progress = if (pos != null && d != null && d > 0) (pos.toFloat() / d).coerceIn(0f, 1f) else 0f,
        controllable = dev.jellystructure.ravilo.ui.sessions.barControlsShown(session),
        nextShown = session.kind == "music" || session.kind == "episode" || session.kind == "audiobook",
    )
}

/** FR-9 — the bar for the cast this app holds a link to: what the receiver reports. */
fun linkMiniBarItem(s: CastRemoteStatus, placeName: String, placeIcon: String?): MiniBarItem = MiniBarItem(
    title = s.title.orEmpty(), artUrl = s.artUrl, placeName = placeName, placeIcon = placeIcon, playing = s.playing,
    progress = if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f, controllable = true,
)

/**
 * DEB-2185 (FR-9) — the one mini bar on the phone: R245 FR-R245-6's (a tap opens the remote; no swipe to dismiss, there
 * is no other way back to it) and R368 FR-R368-8's (the place in accent, **+N** for what else plays) in one component.
 * [card] is the floating card the cast's slot has always been; without it the bar is flat, docked in the music bar's
 * slot. Either way the tap opens FR-1's remote.
 */
@Composable
fun OneMiniBar(
    item: MiniBarItem,
    more: Int,
    onOpen: () -> Unit,
    onMore: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: (() -> Unit)? = null,
    card: Boolean = false,
    visible: Boolean = true,
) {
    val colors = RaviloTheme.colors
    AnimatedVisibility(visible = visible, enter = slideInVertically { it } + fadeIn(tween(180)), exit = slideOutVertically { it } + fadeOut(tween(160))) {
        val frame = if (card) {
            // R274 (FR-R274-4) — the seam with the IME excluded: the bar docks above the nav bar and the pair moves as one.
            Modifier.fillMaxWidth().safeAreaPadding(includeIme = false).padding(horizontal = 12.dp, vertical = RaviloDimens.castMiniBarMargin)
                .height(RaviloDimens.castMiniBarRowHeight + 2.dp).clip(RoundedCornerShape(12.dp)).background(colors.surface)
        } else Modifier.fillMaxWidth().height(RaviloDimens.musicMiniBarHeight).background(colors.surface)
        Column(
            frame.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen).testTag("one-mini-bar"),
        ) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(colors.textDim.copy(0.25f))) {
                Box(Modifier.fillMaxWidth(item.progress).fillMaxHeight().background(colors.accentGradient))
            }
            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp, 36.dp).clip(RoundedCornerShape(6.dp)).background(colors.surfaceVariant)) {
                    val art = item.artUrl
                    if (art != null) RemoteImage(url = art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, requestedWidth = 320)
                    else Text(item.title.take(2).uppercase(), color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DeskIcon(placeIcon(item.placeIcon ?: "tv"), colors.accentSecondary, 12.dp)
                        Spacer(Modifier.width(4.dp))
                        Text(item.placeName, color = colors.accentSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (more > 0) {
                    Box(Modifier.height(30.dp).clip(RoundedCornerShape(15.dp)).background(colors.fg.copy(alpha = 0.10f))
                        .clickable { onMore() }.padding(horizontal = 10.dp).testTag("one-mini-bar-more"), contentAlignment = Alignment.Center) {
                        Text("+$more", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
                    }
                }
                if (item.controllable) {
                    val label = str(if (item.playing) "session.pause" else "action.play")
                    Box(Modifier.size(46.dp).clickable { onPlayPause() }.semantics { contentDescription = label }.testTag("one-mini-bar-pp"), contentAlignment = Alignment.Center) {
                        MusicGlyph(if (item.playing) MusicIcon.PAUSE else MusicIcon.PLAY, colors.text, 22.dp)
                    }
                    if (onNext != null && item.nextShown) {
                        Box(Modifier.size(46.dp).clickable { onNext() }.testTag("one-mini-bar-next"), contentAlignment = Alignment.Center) {
                            MusicGlyph(MusicIcon.NEXT, colors.text, 20.dp)
                        }
                    }
                }
            }
        }
    }
}
