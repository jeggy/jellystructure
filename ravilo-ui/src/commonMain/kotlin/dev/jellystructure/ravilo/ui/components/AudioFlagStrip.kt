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
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.Sora
import jellystructure.ravilo_ui.generated.resources.Res
import dev.jellystructure.ravilo.ui.seams.canonicalLanguage
import jellystructure.ravilo_ui.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

// ISO 639-1 (2-letter) AND ISO 639-2/B + /T (3-letter) → flag drawable.
// ffprobe and Jellyfin MediaStreams use 3-letter codes; include both so lookup never silently fails.
// Phase 139 — made internal (was private) so RequestLanguagePicker can reuse it for request-language
// flags (RequestLanguageIntent.flag is deliberately the same ISO-639-1 vocabulary, not a country code).
internal val LANG_CC: Map<String, DrawableResource> = mapOf(
    "en" to Res.drawable.flag_gb, "eng" to Res.drawable.flag_gb,
    "fr" to Res.drawable.flag_fr, "fra" to Res.drawable.flag_fr, "fre" to Res.drawable.flag_fr,
    "de" to Res.drawable.flag_de, "deu" to Res.drawable.flag_de, "ger" to Res.drawable.flag_de,
    "es" to Res.drawable.flag_es, "spa" to Res.drawable.flag_es,
    "da" to Res.drawable.flag_dk, "dan" to Res.drawable.flag_dk,
    "fo" to Res.drawable.flag_fo, "fao" to Res.drawable.flag_fo,
    "is" to Res.drawable.flag_is, "isl" to Res.drawable.flag_is, "ice" to Res.drawable.flag_is,
    "no" to Res.drawable.flag_no, "nor" to Res.drawable.flag_no, "nob" to Res.drawable.flag_no, "nno" to Res.drawable.flag_no,
    "sv" to Res.drawable.flag_se, "swe" to Res.drawable.flag_se,
    "fi" to Res.drawable.flag_fi, "fin" to Res.drawable.flag_fi,
    "nl" to Res.drawable.flag_nl, "nld" to Res.drawable.flag_nl, "dut" to Res.drawable.flag_nl,
    "it" to Res.drawable.flag_it, "ita" to Res.drawable.flag_it,
    "pt" to Res.drawable.flag_pt, "por" to Res.drawable.flag_pt,
    "pl" to Res.drawable.flag_pl, "pol" to Res.drawable.flag_pl,
    "ru" to Res.drawable.flag_ru, "rus" to Res.drawable.flag_ru,
    "ja" to Res.drawable.flag_jp, "jpn" to Res.drawable.flag_jp,
    "ko" to Res.drawable.flag_kr, "kor" to Res.drawable.flag_kr,
    "zh" to Res.drawable.flag_cn, "zho" to Res.drawable.flag_cn, "chi" to Res.drawable.flag_cn,
    "ar" to Res.drawable.flag_sa, "ara" to Res.drawable.flag_sa,
    "hi" to Res.drawable.flag_in, "hin" to Res.drawable.flag_in,
    "cs" to Res.drawable.flag_cz, "ces" to Res.drawable.flag_cz, "cze" to Res.drawable.flag_cz,
    "tr" to Res.drawable.flag_tr, "tur" to Res.drawable.flag_tr,
    "uk" to Res.drawable.flag_ua, "ukr" to Res.drawable.flag_ua,
    "el" to Res.drawable.flag_gr, "ell" to Res.drawable.flag_gr, "gre" to Res.drawable.flag_gr,
    "hu" to Res.drawable.flag_hu, "hun" to Res.drawable.flag_hu,
    "ro" to Res.drawable.flag_ro, "ron" to Res.drawable.flag_ro, "rum" to Res.drawable.flag_ro,
    "sk" to Res.drawable.flag_sk, "slk" to Res.drawable.flag_sk, "slo" to Res.drawable.flag_sk,
    "hr" to Res.drawable.flag_hr, "hrv" to Res.drawable.flag_hr,
    "he" to Res.drawable.flag_il, "heb" to Res.drawable.flag_il,
    "th" to Res.drawable.flag_th, "tha" to Res.drawable.flag_th,
    "vi" to Res.drawable.flag_vn, "vie" to Res.drawable.flag_vn,
    // Phase R239 (FR-R239-3) — added 2026-09-12: flags now exist for these; Tamil/Telugu are
    // deliberately still absent — no ISO-3166 country flag fits them, and India already maps to
    // Hindi's flag_in, so reusing it for Tamil/Telugu would collapse three DISTINCT languages onto one
    // flag_in drawable, undercounting them via this file's own `.distinct()` dedup — exactly the
    // FR-R239-1 miscount this phase exists to prevent. They stay in the honest "+N" unmapped count.
    //
    // CLOSED 2026-09-13, permanently — do not revisit without a product decision overriding this.
    // Candidate images were reviewed and rejected: no legitimate single flag exists (Tamil
    // Nadu/Andhra Pradesh/Telangana have no official state flags), and one submitted candidate for
    // Tamil was the flag of a proscribed militant organization — entirely unsuitable regardless of the
    // undercount problem above. "No flag" is the final, intended rendering for these two languages.
    "sr" to Res.drawable.flag_rs, "srp" to Res.drawable.flag_rs,
    "bg" to Res.drawable.flag_bg, "bul" to Res.drawable.flag_bg,
    "id" to Res.drawable.flag_id, "ind" to Res.drawable.flag_id,
    "ms" to Res.drawable.flag_my, "msa" to Res.drawable.flag_my, "may" to Res.drawable.flag_my,
    "sl" to Res.drawable.flag_si, "slv" to Res.drawable.flag_si,
    "et" to Res.drawable.flag_ee, "est" to Res.drawable.flag_ee,
    "lv" to Res.drawable.flag_lv, "lav" to Res.drawable.flag_lv,
    "lt" to Res.drawable.flag_lt, "lit" to Res.drawable.flag_lt,
    "tl" to Res.drawable.flag_ph, "fil" to Res.drawable.flag_ph,
    // R239 amendment (2026-09-13) — Catalonia's flag (Senyera) is a single, uncontested regional flag
    // unlike Tamil/Telugu's situation above, so Catalan gets its own dedicated drawable (flag_ct), not
    // a borrowed one.
    "ca" to Res.drawable.flag_ct, "cat" to Res.drawable.flag_ct,
)

/** R247 (FR-R247-5) — every flag lookup goes through [canonicalLanguage] first, so `hbs-srp` finds
 *  `flag_rs` and `gre`/`ell`/`el` all find the same drawable; the raw lower-cased key is the fallback
 *  for a code the canonicaliser does not know but the map happens to. */
internal fun flagFor(code: String?): DrawableResource? {
    if (code.isNullOrBlank()) return null
    return canonicalLanguage(code)?.let { LANG_CC[it] } ?: LANG_CC[code.trim().lowercase()]
}

private const val FLAG_MAX = 5

/** Pure result of counting [AudioFlagStrip]'s inputs — extracted so FR-R239-1/2's counting rules are
 *  unit-testable without a Composable. */
internal data class FlagStripCounts(
    val shownFlags: List<DrawableResource>,
    val extra: Int,
) {
    /** True whenever the group has ANY language at all, mapped or not — the signal
     *  [AudioSubtitleFlagLine] uses to decide whether a group renders (FR-R239-2). */
    val hasAnyLanguage: Boolean get() = shownFlags.isNotEmpty() || extra > 0
}

/** Phase R239 (FR-R239-1) — counts every language the group has, not every flag it can draw. */
internal fun countFlagStrip(languages: List<String>): FlagStripCounts {
    // R247 (FR-R247-2) — identity is the canonical code, so `da`+`dan` or `sr`+`hbs-srp` are one
    // language whether or not a flag exists for it.
    val keys = languages.map { canonicalLanguage(it) ?: it.lowercase() }
    // Deduplicate mapped languages by FLAG (same language, different alias, e.g. nor/nob/nno →
    // flag_no, should show once); an unmapped language is deduplicated by its canonical code.
    // Undercounting true duplicates there is the safe failure mode; over-counting (claiming fewer
    // languages than exist) is the one FR-R239-1 forbids.
    val mappedFlags = keys.mapNotNull { flagFor(it) }.distinct()
    val unmappedCount = keys.filter { flagFor(it) == null }.distinct().size
    val totalDistinct = mappedFlags.size + unmappedCount
    val shown = mappedFlags.take(FLAG_MAX)
    return FlagStripCounts(shown, totalDistinct - shown.size)
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
fun AudioFlagStrip(audioLanguages: List<String>, label: String = "AUDIO", modifier: Modifier = Modifier) {
    val counts = countFlagStrip(audioLanguages)
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
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hasAudio) AudioFlagStrip(audioLanguages, label = str("fd.audio"))
        if (hasAudio && hasSub) {
            Text("·", color = Color.White.copy(alpha = 0.35f), fontSize = 14.sp, fontFamily = Sora)
        }
        if (hasSub) AudioFlagStrip(subtitleLanguages, label = str("fd.subs"))
    }
}
