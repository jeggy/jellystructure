package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.ui.theme.RaviloTheme
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
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.Sora
import jellystructure.ravilo_ui.generated.resources.Res
import dev.jellystructure.shared.tv.FLAG_STRIP_MAX
import dev.jellystructure.shared.tv.LANG_FLAG_CC
import dev.jellystructure.shared.tv.flagCountryFor
import jellystructure.ravilo_ui.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

// DEB-4889 (FR-19) — the language → flag table and the counting rule live in `:shared` (LanguageFlags.kt), keyed by the flag
// file's country code, so the Samsung app draws from the same table. This file keeps only country code → drawable.
// Phase 139 — LANG_CC stays internal so RequestLanguagePicker can reuse it for request-language flags
// (RequestLanguageIntent.flag is deliberately the same ISO-639-1 vocabulary, not a country code).
private val FLAG_DRAWABLES: Map<String, DrawableResource> = mapOf(
    "bg" to Res.drawable.flag_bg, "cn" to Res.drawable.flag_cn, "ct" to Res.drawable.flag_ct,
    "cz" to Res.drawable.flag_cz, "de" to Res.drawable.flag_de, "dk" to Res.drawable.flag_dk,
    "ee" to Res.drawable.flag_ee, "es" to Res.drawable.flag_es, "fi" to Res.drawable.flag_fi,
    "fo" to Res.drawable.flag_fo, "fr" to Res.drawable.flag_fr, "gb" to Res.drawable.flag_gb,
    "gr" to Res.drawable.flag_gr, "hr" to Res.drawable.flag_hr, "hu" to Res.drawable.flag_hu,
    "id" to Res.drawable.flag_id, "il" to Res.drawable.flag_il, "in" to Res.drawable.flag_in,
    "is" to Res.drawable.flag_is, "it" to Res.drawable.flag_it, "jp" to Res.drawable.flag_jp,
    "kr" to Res.drawable.flag_kr, "lt" to Res.drawable.flag_lt, "lv" to Res.drawable.flag_lv,
    "my" to Res.drawable.flag_my, "nl" to Res.drawable.flag_nl, "no" to Res.drawable.flag_no,
    "ph" to Res.drawable.flag_ph, "pl" to Res.drawable.flag_pl, "pt" to Res.drawable.flag_pt,
    "ro" to Res.drawable.flag_ro, "rs" to Res.drawable.flag_rs, "ru" to Res.drawable.flag_ru,
    "sa" to Res.drawable.flag_sa, "se" to Res.drawable.flag_se, "si" to Res.drawable.flag_si,
    "sk" to Res.drawable.flag_sk, "th" to Res.drawable.flag_th, "tr" to Res.drawable.flag_tr,
    "ua" to Res.drawable.flag_ua, "vn" to Res.drawable.flag_vn,
)

internal val LANG_CC: Map<String, DrawableResource> = LANG_FLAG_CC.mapNotNull { (code, cc) -> FLAG_DRAWABLES[cc]?.let { code to it } }.toMap()

/** R247 (FR-R247-5) — see [flagCountryFor]: every flag lookup canonicalises first. */
internal fun flagFor(code: String?): DrawableResource? = flagCountryFor(code)?.let { FLAG_DRAWABLES[it] }

/** The country codes the table names that have no drawable here — empty, and a test says so. */
internal fun flagCountriesWithoutDrawable(): Set<String> = LANG_FLAG_CC.values.toSet() - FLAG_DRAWABLES.keys

private const val FLAG_MAX = FLAG_STRIP_MAX

/** Pure result of counting [AudioFlagStrip]'s inputs, as drawables — the counting itself is `:shared`'s [countFlagStrip]. */
internal data class FlagStripCounts(
    val shownFlags: List<DrawableResource>,
    val extra: Int,
) {
    /** True whenever the group has ANY language at all, mapped or not — the signal
     *  [AudioSubtitleFlagLine] uses to decide whether a group renders (FR-R239-2). */
    val hasAnyLanguage: Boolean get() = shownFlags.isNotEmpty() || extra > 0
}

/** Phase R239 (FR-R239-1) — counts every language the group has, not every flag it can draw. */
internal fun countFlagStrip(languages: List<String>, maxFlags: Int = FLAG_MAX): FlagStripCounts {
    val counts = dev.jellystructure.shared.tv.countFlagStrip(languages, maxFlags)
    return FlagStripCounts(counts.shown.mapNotNull { FLAG_DRAWABLES[it] }, counts.extra)
}

/**
 * R75/R78 — language flag strip for the detail hero.
 * One flag per track that has a language, in physical track order.
 * Hidden entirely only when the group has no languages at all; max 5 flags + an honest "+N" pill.
 * [label] is the category label shown before the flags ("AUDIO" or "SUBTITLES").
 *
 * Phase R239 (FR-R239-1/2) — a language with no flag asset used to be dropped before anything was
 * counted, so a title with 6 mapped and 15 unmapped subtitle languages rendered five flags and "+1"
 * (there are twenty-one), and a title whose only subtitles were entirely unmapped languages rendered
 * identically to a title with none at all. `+N` now counts every language the group actually has —
 * mapped or not — and the label renders as a bare count when nothing maps, never nothing.
 */
@Composable
fun AudioFlagStrip(audioLanguages: List<String>, label: String = "AUDIO", modifier: Modifier = Modifier, maxFlags: Int = FLAG_MAX) {
    val counts = countFlagStrip(audioLanguages, maxFlags)
    if (!counts.hasAnyLanguage) return
    val (shown, extra) = counts
    val sora = Sora

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            color = RaviloTheme.colors.fg.copy(alpha = 0.55f),
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
                    .border(0.5.dp, RaviloTheme.colors.fg.copy(alpha = 0.18f), RoundedCornerShape(3.dp)),
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
                    color = RaviloTheme.colors.fg.copy(alpha = 0.70f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = sora,
                )
            }
        }
    }
}

/**
 * R134 — audio + subtitle flags on a single line: `AUDIO 🅐🅑 · SUBTITLES 🅒🅓`. Renders a group whenever
 * it has ANY language at all, mapped or not (Phase R239, FR-R239-2) — a group whose languages happen
 * to be entirely unmapped must never render identically to a group with no languages; nothing when
 * neither group has any language.
 */
@Composable
fun AudioSubtitleFlagLine(audioLanguages: List<String>, subtitleLanguages: List<String>, modifier: Modifier = Modifier) {
    val hasAudio = audioLanguages.isNotEmpty()
    val hasSub = subtitleLanguages.isNotEmpty()
    if (!hasAudio && !hasSub) return
    // 2026-09-24 — the line FITS its width by showing fewer flags, never by clipping. With five audio and
    // five subtitle flags the detail hero's column cut the subtitle strip's "+N" to a bare "+" at its
    // right edge (Creatures, Ltd., soveværelse TV) — the one number R239 exists to state honestly. Each
    // attempt that overflows moves one flag from the larger group into its "+N", which R239's counting
    // already folds in, until the line fits.
    SubcomposeLayout(modifier) { constraints ->
        var audioMax = FLAG_MAX
        var subMax = FLAG_MAX
        var attempt = 0
        var line: Placeable
        while (true) {
            val a = audioMax; val sm = subMax
            line = subcompose(attempt) { FlagLineContent(audioLanguages, subtitleLanguages, a, sm) }
                .first().measure(Constraints(maxHeight = constraints.maxHeight))
            if (!constraints.hasBoundedWidth || line.width <= constraints.maxWidth || (audioMax == 0 && subMax == 0)) break
            if (subMax >= audioMax && subMax > 0) subMax-- else audioMax--
            attempt++
        }
        layout(line.width.coerceAtMost(constraints.maxWidth), line.height) { line.place(0, 0) }
    }
}

@Composable
private fun FlagLineContent(audioLanguages: List<String>, subtitleLanguages: List<String>, audioMax: Int, subMax: Int) {
    val hasAudio = audioLanguages.isNotEmpty()
    val hasSub = subtitleLanguages.isNotEmpty()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hasAudio) AudioFlagStrip(audioLanguages, label = str("fd.audio"), maxFlags = audioMax)
        if (hasAudio && hasSub) {
            Text("·", color = RaviloTheme.colors.fg.copy(alpha = 0.35f), fontSize = 14.sp, fontFamily = Sora)
        }
        if (hasSub) AudioFlagStrip(subtitleLanguages, label = str("fd.subs"), maxFlags = subMax)
    }
}
