package dev.jellystructure.ravilo.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.isDesktopLayout
import dev.jellystructure.shared.tv.MusicVersionDefaults
import dev.jellystructure.shared.tv.MusicVersionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * R344 — a song's version, read-only: the household's types and colours. The colours come on `MusicHome`
 * (`version_types`, dev review 2); until it has answered, and from a server that sends none, the nine built-in colours
 * stand in (292's palette). The names are Ravilo's own strings (`ver.<key>`), so a key this app has no string for draws
 * nothing.
 */
object MusicVersions {
    /** The nine types in 292's order, each with its default colour (`shared`'s [MusicVersionDefaults], kept equal to
     *  the admin's table by the server's test). */
    val DEFAULTS: Map<String, Color> =
        MusicVersionDefaults.TYPES.associate { t -> t.key to (parseVersionColor(t.color) ?: Color.Gray) }

    private val household = MutableStateFlow<Map<String, Color>>(emptyMap())
    val colors: StateFlow<Map<String, Color>> = household

    /** The household's colours from `MusicHome.version_types`; an unreadable colour keeps the default. An empty list
     *  (an older server) changes nothing. */
    fun learn(types: List<MusicVersionType>) {
        if (types.isEmpty()) return
        household.value = types.mapNotNull { t -> parseVersionColor(t.color)?.let { t.key to it } }.toMap()
    }

    fun colorOf(key: String, household: Map<String, Color>): Color? = household[key] ?: DEFAULTS[key]
}

/** `#rrggbb` (or `rrggbb`, or `#rgb`) → a colour; anything else → null. */
fun parseVersionColor(s: String?): Color? {
    val h = s?.trim()?.removePrefix("#") ?: return null
    val full = when (h.length) {
        6 -> h
        3 -> h.map { "$it$it" }.joinToString("")
        else -> return null
    }
    val v = full.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or v)
}

/** What a chip group draws: the [shown] keys, then *+[more]*. Unknown keys are dropped first (they draw nothing and do
 *  not count), and a key sent twice is drawn once. */
data class VersionFold(val shown: List<String>, val more: Int, val all: List<String>)

fun foldVersions(keys: List<String>, fold: Int): VersionFold {
    val known = keys.filter { it in MusicVersions.DEFAULTS }.distinct()
    val shown = known.take(fold.coerceAtLeast(0))
    return VersionFold(shown, known.size - shown.size, known)
}

/** FR-R344-3 — two then *+N* on a phone (and a desktop window under 600 dp), three on the desktop family. */
const val VERSION_FOLD_PHONE = 2
const val VERSION_FOLD_DESKTOP = 3

/**
 * Dev review 6 — the chip's ink is the type's colour mixed toward the theme's text (Compose's [lerp] works in Oklab,
 * near the mockup's `color-mix(in oklch, …)`). 58 % of the colour on a dark theme, as the mockup; 45 % on a light one,
 * because 58 % leaves Cover under 4.5 : 1 in Daylight.
 */
fun versionInk(c: Color, text: Color, light: Boolean): Color = lerp(c, text, if (light) 0.55f else 0.42f)

fun versionTint(c: Color): Color = c.copy(alpha = 0.15f)
fun versionBorder(c: Color): Color = c.copy(alpha = 0.42f)

/**
 * R344 — the chip group after a song's title. [fold] is how many before *+N*; `null` folds by the layout (two on a
 * phone, three on the desktop); [Int.MAX_VALUE] shows them all (Now playing). Screen readers hear one sentence,
 * *Version: Remix, Edit*, never the chips one by one. Nothing at all for a song with no (known) version.
 */
@Composable
fun VersionChips(keys: List<String>, modifier: Modifier = Modifier, fold: Int? = null, large: Boolean = false) {
    if (keys.isEmpty()) return
    val f = foldVersions(keys, fold ?: if (isDesktopLayout) VERSION_FOLD_DESKTOP else VERSION_FOLD_PHONE)
    if (f.all.isEmpty()) return
    val colors = RaviloTheme.colors
    val household by MusicVersions.colors.collectAsState()
    val names = f.all.associateWith { str("ver.$it") }
    val aria = str("ver.aria", mapOf("list" to f.all.joinToString(", ") { names.getValue(it) }))
    val fs = if (large) 11.5.sp else 10.5.sp
    val hPad = if (large) 7.dp else 6.dp
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier.clearAndSetSemantics { contentDescription = aria },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (k in f.shown) {
            val c = MusicVersions.colorOf(k, household) ?: continue
            Text(
                names.getValue(k),
                color = versionInk(c, colors.text, colors.isLight),
                fontSize = fs, lineHeight = fs, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 0.01.em,
                maxLines = 1, softWrap = false,
                modifier = Modifier.clip(shape).background(versionTint(c)).border(1.dp, versionBorder(c), shape).padding(horizontal = hPad, vertical = 3.dp),
            )
        }
        if (f.more > 0) {
            val line = colors.fg.copy(alpha = 0.22f)
            Text(
                "+${f.more}",
                color = colors.textSecondary,
                fontSize = fs, lineHeight = fs, fontWeight = FontWeight.Bold, fontFamily = Sora, maxLines = 1, softWrap = false,
                // `.rv-vm` — the +N chip is dashed.
                modifier = Modifier.drawBehind {
                    val w = 1.dp.toPx()
                    drawRoundRect(
                        line, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                        cornerRadius = CornerRadius(5.dp.toPx()),
                        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
                    )
                }.padding(horizontal = 5.dp, vertical = 3.dp),
            )
        }
    }
}

/** R352 (FR-R352-1) — how a line splits between a title and its chips: how many chips are named, and the title's width. */
data class VersionFit(val shown: Int, val titleWidth: Int)

/**
 * R352 (FR-R352-1) — the title takes its space first. [variants] are the chip groups the fold allows, largest first
 * (`shown` named chips and their measured width; the last names none and is *+N* alone). The first group that fits
 * beside the whole title wins. When the whole title does not fit beside even *+N*, the chips are *+N* alone and the
 * title truncates in the rest (amended 2026-10-02: a 40 % share for the chips left *Cave (acoustic…* beside *Live +2*
 * in the 300 dp queue panel; the spec always said *+N* alone).
 */
fun fitVersions(titleNatural: Int, gap: Int, variants: List<Pair<Int, Int>>, available: Int): VersionFit {
    if (variants.isEmpty()) return VersionFit(0, minOf(titleNatural, available))
    variants.firstOrNull { (_, w) -> titleNatural + gap + w <= available }?.let { (k, _) -> return VersionFit(k, titleNatural) }
    val (k, w) = variants.last()
    return VersionFit(k, (available - gap - w).coerceAtLeast(0))
}

/**
 * R352 (FR-R352-1) — a song's title followed by its version chips on one line, the title first: it keeps its natural
 * width when it can and the chips fold (fewer named, the rest in *+N*) to the space left — see [fitVersions]. R344's
 * own fold (two on a phone, three on a computer, [fold] when given) is the most it names. [title] is one line of text
 * that ellipsizes when it is given less than its width.
 */
@Composable
fun TitleWithVersions(keys: List<String>, modifier: Modifier = Modifier, gap: androidx.compose.ui.unit.Dp = 8.dp, fold: Int? = null, title: @Composable () -> Unit) {
    val most = fold ?: if (isDesktopLayout) VERSION_FOLD_DESKTOP else VERSION_FOLD_PHONE
    val known = foldVersions(keys, Int.MAX_VALUE).all
    if (known.isEmpty()) { androidx.compose.foundation.layout.Box(modifier) { title() }; return }
    val top = minOf(most, known.size)
    androidx.compose.ui.layout.SubcomposeLayout(modifier) { constraints ->
        val gapPx = gap.roundToPx()
        val loose = androidx.compose.ui.unit.Constraints()
        val titleM = subcompose("title") { androidx.compose.foundation.layout.Box { title() } }.first()
        val natural = titleM.maxIntrinsicWidth(constraints.maxHeight.takeIf { constraints.hasBoundedHeight } ?: androidx.compose.ui.unit.Constraints.Infinity)
        val groups = (top downTo 0).map { k -> k to subcompose("chips-$k") { VersionChips(keys, fold = k) }.first().measure(loose) }
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else natural + gapPx + groups.first().second.width
        val fit = fitVersions(natural, gapPx, groups.map { (k, p) -> k to p.width }, available)
        val chips = groups.first { it.first == fit.shown }.second
        val titleP = titleM.measure(androidx.compose.ui.unit.Constraints(maxWidth = fit.titleWidth.coerceAtLeast(0)))
        val h = maxOf(titleP.height, chips.height)
        val w = (titleP.width + gapPx + chips.width).coerceIn(constraints.minWidth, available.coerceAtLeast(constraints.minWidth))
        layout(w, h) {
            titleP.place(0, (h - titleP.height) / 2)
            chips.place(titleP.width + gapPx, (h - chips.height) / 2)
        }
    }
}

// ── R373 (FR-R373-5/6) — the Bonus chip, and the chips just left of the length ──

/** R373 (FR-R373-5) — *Bonus*'s ink and fill: no hue (the chip family's shape, the theme's own text on a neutral fill). */
fun bonusInk(textSecondary: Color): Color = textSecondary
fun bonusFill(fg: Color): Color = fg.copy(alpha = 0.07f)
fun bonusBorder(fg: Color): Color = fg.copy(alpha = 0.2f)

/** R373 (FR-R373-5) — *Bonus*: R344's chip family, no hue, never folded into *+N* (it is another axis). */
@Composable
fun BonusChip(modifier: Modifier = Modifier, large: Boolean = false) {
    val colors = RaviloTheme.colors
    val fs = if (large) 11.5.sp else 10.5.sp
    val shape = RoundedCornerShape(5.dp)
    Text(
        str("ed.bonus"), color = bonusInk(colors.textSecondary),
        fontSize = fs, lineHeight = fs, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 0.01.em, maxLines = 1, softWrap = false,
        modifier = modifier.clip(shape).background(bonusFill(colors.fg)).border(1.dp, bonusBorder(colors.fg), shape).padding(horizontal = if (large) 7.dp else 6.dp, vertical = 3.dp),
    )
}

/**
 * R373 (FR-R373-6, amends R344 FR-R344-3 and R352 FR-R352-1; owner 2026-10-04) — on Ravilo's song and track rows the
 * version chips and *Bonus* sit at the row's right, just left of the length. The title keeps its space: the version
 * chips fold first (down to *+N*, R352's [fitVersions]), then the title ellipsizes; *Bonus* is never cut. The title
 * starts at the left; the chips end at this layout's right edge. Give it the row's remaining width (a weight).
 */
@Composable
fun TitleThenChips(keys: List<String>, bonus: Boolean, modifier: Modifier = Modifier, gap: androidx.compose.ui.unit.Dp = 8.dp, fold: Int? = null, title: @Composable () -> Unit) {
    val most = fold ?: if (isDesktopLayout) VERSION_FOLD_DESKTOP else VERSION_FOLD_PHONE
    val known = foldVersions(keys, Int.MAX_VALUE).all
    if (known.isEmpty() && !bonus) { androidx.compose.foundation.layout.Box(modifier) { title() }; return }
    val top = minOf(most, known.size)
    androidx.compose.ui.layout.SubcomposeLayout(modifier) { constraints ->
        val gapPx = gap.roundToPx()
        val chipGap = 4.dp.roundToPx()
        val loose = androidx.compose.ui.unit.Constraints()
        val titleM = subcompose("title") { androidx.compose.foundation.layout.Box { title() } }.first()
        val natural = titleM.maxIntrinsicWidth(constraints.maxHeight.takeIf { constraints.hasBoundedHeight } ?: androidx.compose.ui.unit.Constraints.Infinity)
        val bonusP = if (bonus) subcompose("bonus") { BonusChip() }.first().measure(loose) else null
        val bonusW = bonusP?.let { it.width + (if (known.isNotEmpty()) chipGap else 0) } ?: 0
        val groups = if (known.isEmpty()) listOf(0 to null) else (top downTo 0).map { k -> k to subcompose("chips-$k") { VersionChips(keys, fold = k) }.first().measure(loose) }
        val full = if (constraints.hasBoundedWidth) constraints.maxWidth else natural + gapPx + (groups.first().second?.width ?: 0) + bonusW
        val available = (full - bonusW).coerceAtLeast(0)
        val fit = if (known.isEmpty()) VersionFit(0, minOf(natural, (available - gapPx).coerceAtLeast(0)))
            else fitVersions(natural, gapPx, groups.map { (k, p) -> k to (p?.width ?: 0) }, available)
        val chips = groups.first { it.first == fit.shown }.second
        val titleP = titleM.measure(androidx.compose.ui.unit.Constraints(maxWidth = fit.titleWidth.coerceAtLeast(0)))
        val h = maxOf(titleP.height, chips?.height ?: 0, bonusP?.height ?: 0)
        val w = full.coerceAtLeast(constraints.minWidth)
        layout(w, h) {
            titleP.place(0, (h - titleP.height) / 2)
            var x = w
            bonusP?.let { x -= it.width; it.place(x, (h - it.height) / 2); x -= chipGap }
            chips?.let { x = (if (bonusP != null) x else w) - it.width; it.place(x, (h - it.height) / 2) }
        }
    }
}
