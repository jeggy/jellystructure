package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.LocalRaviloSkin
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.PlaybackNote
import dev.jellystructure.shared.tv.Skin

// R222 (FR-R222-7) — a fixed amber, the same across Aurora/Midnight regardless of either skin's own
// accent (Aurora purple, Midnight teal) — this is a "slow to start" cue, not a brand color, so it does
// not follow RaviloTheme.colors.accent. Deliberately equal to NoirColors.accent (#F5B542): that equality
// is exactly why Noir needs its own case below (an amber-on-amber tint reads as decoration, not urgency).
private val WarnAmber = Color(0xFFF5B542)

/**
 * R222 (Phase 185) — render-never-compute: takes the already-resolved [note] and renders one sentence.
 * No thresholds, bitrates or ceilings ever reach this composable — see [PlaybackNote]'s own doc for why.
 * Not focusable, not in the D-pad order, no action — a label, like the age badge (FR-R222-6). Callers
 * never invoke this when the note is null (FR-R222-1: absent field ⇒ nothing renders, no reserved space).
 *
 * [compact] switches the movie-hero scale (FR-R222-4's own type, `this_tv` fallback) to the episode-row
 * scale (FR-R222-5, `this_phone` fallback on the phone target) — both share this one implementation
 * rather than duplicating the amber-bar/Noir-override logic per placement.
 */
@Composable
fun PlaybackNoteLine(note: PlaybackNote, compact: Boolean = false) {
    val colors = RaviloTheme.colors
    val noir = LocalRaviloSkin.current == Skin.NOIR
    val sora = Sora

    val device = note.device.ifBlank { str(if (compact) "this_phone" else "this_tv") }
    val lead = str("slow_lead", mapOf("device" to device))
    val tail = if (note.basis == "measured" && note.seconds != null)
        str("slow_tail_measured", mapOf("n" to note.seconds.toString()))
    else
        str("slow_tail_expected")

    val barColor = if (noir) colors.text else WarnAmber
    val leadColor = if (noir) colors.text else WarnAmber
    val leadWeight = if (noir) FontWeight.Bold else FontWeight.SemiBold
    val fontSize = if (compact) 13.sp else 16.sp

    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = leadColor, fontWeight = leadWeight)) { append(lead) }
        append(" ")
        withStyle(SpanStyle(color = colors.textSecondary)) { append(tail) }
    }

    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(barColor),
        )
        Text(
            text = text,
            fontSize = fontSize,
            lineHeight = fontSize * 1.35,
            fontFamily = sora,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
