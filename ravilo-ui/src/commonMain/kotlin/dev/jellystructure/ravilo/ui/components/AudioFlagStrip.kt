package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.theme.Sora
import jellystructure.ravilo_ui.generated.resources.Res
import jellystructure.ravilo_ui.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** ISO-639-1 language code → ISO-3166-1-alpha-2 country code for flag assets (R75 / Phase 87). */
private val LANG_CC: Map<String, DrawableResource?> = mapOf(
    "en" to Res.drawable.flag_gb,
    "fr" to Res.drawable.flag_fr,
    "de" to Res.drawable.flag_de,
    "es" to Res.drawable.flag_es,
    "da" to Res.drawable.flag_dk,
    "fo" to Res.drawable.flag_fo,
    "is" to Res.drawable.flag_is,
    "no" to Res.drawable.flag_no,
    "sv" to Res.drawable.flag_se,
    "fi" to Res.drawable.flag_fi,
    "nl" to Res.drawable.flag_nl,
    "it" to Res.drawable.flag_it,
    "pt" to Res.drawable.flag_pt,
    "pl" to Res.drawable.flag_pl,
    "ru" to Res.drawable.flag_ru,
    "ja" to Res.drawable.flag_jp,
    "ko" to Res.drawable.flag_kr,
    "zh" to Res.drawable.flag_cn,
    "ar" to Res.drawable.flag_sa,
    "hi" to Res.drawable.flag_in,
)

private const val FLAG_MAX = 5

/**
 * R75 — audio-language flag strip for the detail hero.
 * One flag per audio track that has a language, in physical track order.
 * Skips untagged/unmapped tracks; hidden entirely when none map to a flag; max 5 + "+N" pill.
 */
@Composable
fun AudioFlagStrip(audioLanguages: List<String>, modifier: Modifier = Modifier) {
    val mapped = audioLanguages.mapNotNull { lang -> LANG_CC[lang.lowercase()] }
    if (mapped.isEmpty()) return

    val shown = mapped.take(FLAG_MAX)
    val extra = mapped.size - shown.size
    val sora = Sora

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "AUDIO",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            fontFamily = sora,
        )
        Spacer(Modifier.width(2.dp))
        shown.forEach { res ->
            Image(
                painter = painterResource(res),
                contentDescription = null,
                modifier = Modifier
                    .size(width = 26.dp, height = 18.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(3.dp)),
            )
        }
        if (extra > 0) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(18.dp)
                    .padding(horizontal = 6.dp),
            ) {
                Text(
                    text = "+$extra",
                    color = Color.White.copy(alpha = 0.70f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = sora,
                )
            }
        }
    }
}
