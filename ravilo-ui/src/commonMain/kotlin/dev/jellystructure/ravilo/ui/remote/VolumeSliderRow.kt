package dev.jellystructure.ravilo.ui.remote

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.CastController
import dev.jellystructure.ravilo.ui.components.DeskIcon
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.sessions.SessionRemote
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.SessionCommandRequest
import dev.jellystructure.shared.tv.SessionDetail
import androidx.compose.runtime.withFrameMillis
import kotlin.math.roundToInt
import kotlin.time.Clock

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * DEB-2185 (FR-10, FR-11, FR-12; `one-remote-a-vol-pending-film.png`, `-a-vol-reverted-film.png`) — the one volume
 * slider every remote draws, for a place, a group's master and each room alike: a glyph, the [label], the slider, the
 * level as a percent, then [trailing] (a room's mute and remove). What the thumb shows is [volumeShown]'s: the level
 * the place last reported, *—* until one is known (never 0), the finger while dragging, the viewer's own request
 * while it is in flight, and the place's own level again 3 s after a request it did not take — with
 * *{place} kept {n} %* under the slider for 3 s. `set_volume` goes through [send] at most once per 200 ms during a
 * drag and once more on release.
 *
 * A place that reports no level shows *—*, cannot be dragged, and says *{place} doesn't report its volume*.
 */
@Composable
fun VolumeSliderRow(
    label: String,
    placeName: String,
    reported: Int?,
    enabled: Boolean,
    send: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "remote-volume",
    glyph: Boolean = true,
    bold: Boolean = true,
    startPadding: Dp = 0.dp,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    /** The wall clock the hold reads; a test hands in its own. */
    clock: () -> Long = ::nowMs,
) {
    val colors = RaviloTheme.colors
    val hold = remember(tag) { VolumeHold() }
    var version by remember { mutableIntStateOf(0) }
    var drag by remember { mutableStateOf<Int?>(null) }
    // A report reaching this app ends a hold it matches (FR-11.4) and, with none in flight, moves the thumb (FR-12.1).
    LaunchedEffect(reported) { hold.onReport(reported, clock()); version++ }
    // While a request is out, or its *kept* line is up, the clock has to move the drawing (FR-11.5): a redraw every
    // few frames, on the frame clock so a test's clock drives it too, and nothing while a frame is not drawn.
    LaunchedEffect(version) {
        if (!hold.ticking(clock())) return@LaunchedEffect
        var frames = 0
        while (hold.ticking(clock())) { withFrameMillis { }; if (++frames % 6 == 0) version++ }
        version++   // one last redraw: the hold or its line just ended
    }
    @Suppress("UNUSED_EXPRESSION") version
    val shown = hold.shown(reported, clock())
    val level = shown.level
    val draggable = enabled && reported != null
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(start = startPadding).alpha(if (draggable) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.width(110.dp), verticalAlignment = Alignment.CenterVertically) {
                if (glyph) { DeskIcon(DeskIcon.VOLUME, colors.textSecondary, 18.dp); Spacer(Modifier.width(10.dp)) }
                Text(label, color = colors.text, fontSize = 14.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (level != null) {
                Slider(
                    value = level.toFloat(), valueRange = 0f..100f, enabled = draggable,
                    onValueChange = { f ->
                        val l = f.roundToInt().coerceIn(0, 100)
                        drag = l
                        hold.onDrag(l, clock())?.let(send)
                        version++
                    },
                    onValueChangeFinished = {
                        val l = drag ?: level
                        send(hold.onRelease(l, clock()))
                        drag = null
                        version++
                    },
                    modifier = Modifier.weight(1f).testTag(tag),
                    colors = SliderDefaults.colors(thumbColor = colors.fg, activeTrackColor = colors.accentSecondary, inactiveTrackColor = colors.fg.copy(0.2f)),
                )
                Text("$level %", color = if (shown.inFlight) colors.textSecondary else colors.text, fontSize = 13.sp, fontFamily = Sora,
                    textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(46.dp).testTag("$tag-level"))
            } else {
                Text("—", color = colors.textDim, fontSize = 14.sp, fontFamily = Sora, modifier = Modifier.weight(1f).testTag("$tag-none"))
            }
            trailing?.invoke(this)
        }
        val kept = shown.kept
        when {
            kept != null -> Text(str("volume.kept", mapOf("place" to placeName, "n" to kept.toString())), color = colors.accentSecondary,
                fontSize = 12.sp, fontFamily = Sora, modifier = Modifier.padding(start = startPadding).testTag("$tag-kept"))
            reported == null -> Text(str("volume.no_report", mapOf("place" to placeName)), color = colors.textDim,
                fontSize = 12.sp, fontFamily = Sora, modifier = Modifier.padding(start = startPadding).testTag("$tag-no-report"))
        }
    }
}

/**
 * DEB-2185 (FR-5.2, FR-10, FR-12.3) — the volume row over this app's own link. A Chromecast link reports the device's
 * own level and takes `setVolume` on it (the faster path); a paired TV over the screen link has no volume of its own,
 * so the row reads the session's reported level ([session]) and sends `set_volume` through the server. Before the
 * link's first report, the session's level is a report too.
 */
@Composable
internal fun LinkVolumeRow(cast: CastController, placeName: String, session: SessionDetail?, modifier: Modifier = Modifier) {
    val own by cast.sender.volume.collectAsState()
    val overLink = cast.sender.setsVolume
    val reported: Int? = own?.takeIf { overLink && !it.isNaN() }?.let { (it * 100).roundToInt().coerceIn(0, 100) } ?: session?.volume
    val send: (Int) -> Unit = if (overLink) { l -> cast.sender.setVolume(l / 100.0) } else { l ->
        session?.let { SessionRemote.command(it.session.id, SessionCommandRequest(op = "set_volume", level = l)) }
    }
    VolumeSliderRow(
        label = str("cast.volume"), placeName = placeName, reported = reported,
        enabled = reported != null && (overLink || session != null), send = send, modifier = modifier, tag = "remote-volume",
    )
}
