package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.theme.Sora
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The no-photo face of a profile (FR-1): initials from the display name on a gradient picked by a hash of
 * the user id, so the colour survives a rename and is the same on every device. The hash and the ramp are
 * the design's `gradientFor()` / `AV_RAMP` in `design/ravilo/ravilo-data.js`.
 */

/** The ramp, in the design's order; the first colour is the top-left of the 145° line. */
internal val ProfileRamp: List<Pair<Color, Color>> = listOf(
    Color(0xFF7B6EF0) to Color(0xFF3FB6F5),
    Color(0xFF19D6C6) to Color(0xFF2A8CF0),
    Color(0xFFF5B542) to Color(0xFFE0792F),
    Color(0xFFE0567A) to Color(0xFF7B6EF0),
    Color(0xFFE0639A) to Color(0xFFB15CD0),
    Color(0xFF2DD49A) to Color(0xFF12A3A0),
)

/**
 * One letter for a single word, first-of-first plus first-of-last for two or more; a word with no letter
 * or digit left is dropped; nothing at all gives `?`. `uppercaseChar()` on the one kept character, never
 * `String.uppercase()` (`"ß"` would become two letters).
 */
internal fun profileInitials(displayName: String): String {
    val words = displayName.split(' ', '\t', '\n', '\r', '\u000B', '\u000C', ' ')
        .map { word -> word.filter { it.isLetterOrDigit() } }
        .filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    val first = words.first()[0].uppercaseChar()
    return if (words.size == 1) first.toString() else "$first${words.last()[0].uppercaseChar()}"
}

/** `h = h * 31 + code` over UTF-16 code units in 32-bit unsigned arithmetic, like JS `>>> 0`. */
internal fun profileRampIndex(userId: String): Int {
    var h = 0u
    for (c in userId) h = h * 31u + c.code.toUInt()
    return (h % ProfileRamp.size.toUInt()).toInt()
}

@Composable
fun ProfileFace(
    userId: String,
    displayName: String,
    avatarUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val (from, to) = ProfileRamp[profileRampIndex(userId)]
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .drawBehind {
                // CSS 145deg: the line through the centre along (sin a, -cos a), length s*(|sin a|+|cos a|).
                val a = 145.0 * PI / 180.0
                val dx = sin(a).toFloat()
                val dy = (-cos(a)).toFloat()
                val half = this.size.width * (abs(sin(a)) + abs(cos(a))).toFloat() / 2f
                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                drawRect(
                    Brush.linearGradient(
                        listOf(from, to),
                        start = Offset(c.x - dx * half, c.y - dy * half),
                        end = Offset(c.x + dx * half, c.y + dy * half),
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = profileInitials(displayName),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontFamily = Sora,
            fontSize = with(LocalDensity.current) { (size * 0.4f).toSp() },
        )
        // Drawn after the text so a photo wins; a transparent placeholder keeps the letter showing while
        // it loads or if it fails, never the grey surfaceVariant circle.
        if (avatarUrl != null) {
            RemoteImage(avatarUrl, null, Modifier.matchParentSize(), placeholderColor = Color.Transparent)
        }
    }
}
