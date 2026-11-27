package dev.jellystructure.ravilo.ui.samsungtv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient

const val SAMSUNG_GUIDE_TAG = "samsung-guide"
const val SAMSUNG_GUIDE_PRIMARY_TAG = "samsung-guide-primary"
/** DEB-5905 — the overview page; its cards are `samsung-card-<index>`, each card's one action `-action`, an up-to-date card's *Reinstall* `samsung-card-reinstall`. */
const val SAMSUNG_OVERVIEW_TAG = "samsung-overview"
/** DEB-5905 — the overview's cards are `-<index>`; each card's one action (button or quiet link) is `-action`, and `-reinstall` the *Reinstall* on an up-to-date card. */
const val SAMSUNG_CARD_TAG = "samsung-card"
const val SAMSUNG_CARD_ACTION_TAG = "samsung-card-action"
const val SAMSUNG_CARD_REINSTALL_TAG = "samsung-card-reinstall"
const val SAMSUNG_OVERVIEW_SEARCH_TAG = "samsung-overview-search"
/** DEB-5504 (FR-13) — `-keys` / `-nokeys` on the two cards of the question and on the two tabs of the instruction. */
const val SAMSUNG_GUIDE_REMOTE_CARD_TAG = "samsung-guide-remote-card"
const val SAMSUNG_GUIDE_REMOTE_TAB_TAG = "samsung-guide-remote-tab"

/**
 * DEB-4843 (FR-3) — the guide. One action per screen, one big button, a drawing, under about 40 words. A screen is a value of
 * [GuideScreen]; this draws it and forwards the button to the [SamsungGuide], and holds no state of its own beyond whether the
 * quiet *It didn't work?* line is open.
 */
@Composable
fun SamsungGuideScreen(guide: SamsungGuide, onBack: () -> Unit) {
    val colors = RaviloTheme.colors
    val screen by guide.screen.collectAsState()
    LaunchedEffect(guide) { guide.open() }
    Column(Modifier.fillMaxSize().background(colors.background).testTag(SAMSUNG_GUIDE_TAG)) {
        val ov = screen as? GuideScreen.Overview
        if (ov != null) OverviewPage(ov, guide, onBack) else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = colors.text, fontSize = 26.sp,
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack).padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GuideBody(screen, guide)
            }
        }
    }
}

/** The guide's screen for [screen], without the page chrome — what the screenshots render. The overview is a list page and draws its own head. */
@Composable
internal fun GuideBody(screen: GuideScreen, guide: SamsungGuide) {
    when (val s = screen) {
        GuideScreen.Loading, GuideScreen.Searching, is GuideScreen.Checking ->
            Page(null, if (s is GuideScreen.Checking) "samsungtv.checking.title" else if (s == GuideScreen.Searching) "samsungtv.searching.title" else "samsungtv.searching.title", art = Art.WAIT)
        is GuideScreen.Intro -> Page(
            null, "samsungtv.intro.title", art = Art.TV,
            body = if (s.wifi) "samsungtv.intro.body" else "samsungtv.intro.nowifi",
            cta = if (s.wifi) "samsungtv.intro.start" else "samsungtv.retry", onCta = { if (s.wifi) guide.find() else guide.open() },
            outline = if (s.wifi) listOf("samsungtv.intro.s1", "samsungtv.intro.s2", "samsungtv.intro.s3", "samsungtv.intro.s4") else null,
        )
        is GuideScreen.Pick -> Page(
            null, if (s.tvs.size == 1) "samsungtv.found.title" else "samsungtv.found.pick", art = Art.TV,
            cta = if (s.tvs.size == 1) "samsungtv.found.confirm" else null, onCta = { guide.choose(s.tvs[0]) },
        ) {
            s.tvs.forEach { tv -> TvChoice(tv, selectable = s.tvs.size > 1) { guide.choose(tv) } }
        }
        GuideScreen.NotFound -> Page(null, "samsungtv.notfound.title", body = "samsungtv.notfound.body", art = Art.TV, cta = "samsungtv.retry", onCta = { guide.find() })
        is GuideScreen.Step1 -> if (s.remote == null) {
            // DEB-5504 (FR-13a) — the question: no illustration slot, no primary button; the tap is the step's whole action.
            Page(1, "samsungtv.step1.title", art = null, body = "samsungtv.step1.which") { RemoteCards { guide.pickRemote(it) } }
        } else {
            val keys = s.remote == Remote.KEYS
            Page(
                1, "samsungtv.step1.title", art = if (keys) Art.REMOTE_KEYS else Art.REMOTE_NOKEYS,
                body = "samsungtv.step1." + (if (keys) "keys_body" else "nokeys_body") + (if (s.tv.appSettingsMenu) "_new" else ""),
                cta = "samsungtv.next", onCta = { guide.next() },
                aboveArt = { RemoteTabs(s.remote) { guide.pickRemote(it) } },
            )
        }
        is GuideScreen.Step2 -> Page(
            2, "samsungtv.step2.title", art = Art.SWITCH, body = "samsungtv.step2.body", hint = "samsungtv.step2.why",
            cta = "samsungtv.next", onCta = { guide.next() }, trouble = "samsungtv.step2.trouble",
        ) { BigAddress(s.phoneIp) }
        is GuideScreen.Step3 -> Page(
            3, "samsungtv.step3.title", art = Art.POWER, body = "samsungtv.step3.body",
            cta = "samsungtv.step3.cta", onCta = { guide.next() }, trouble = "samsungtv.step3.trouble",
        )
        is GuideScreen.Ask -> Page(
            4, "samsungtv.ask.title", art = Art.TV, body = "samsungtv.ask.body",
            cta = "samsungtv.ask.cta", onCta = { if (!s.busy) guide.sendRequest() }, busy = s.busy,
        )
        is GuideScreen.Overview -> OverviewPage(s, guide, onBack = {})
        is GuideScreen.Installing -> Page(null, "samsungtv.installing.title", art = Art.WAIT) {
            val label = when (s.stage) {
                GuideScreen.Stage.DOWNLOADING -> "samsungtv.installing.downloading"
                GuideScreen.Stage.SENDING -> "samsungtv.installing.sending"
                GuideScreen.Stage.INSTALLING -> "samsungtv.installing.installing"
            }
            Text(str(label), color = RaviloTheme.colors.textSecondary, fontSize = 15.sp, fontFamily = Sora)
            Spacer(Modifier.height(14.dp))
            ProgressBar(s.fraction)
        }
        is GuideScreen.Done -> Page(null, "samsungtv.done.title", art = Art.DONE, body = "samsungtv.done.body", cta = "samsungtv.done.cta", onCta = { guide.done() })
        is GuideScreen.Problem -> Page(
            null, null, art = Art.TV, bodyText = str(s.key, s.vars),
            cta = when (s.back) {
                Target.STEP1 -> "samsungtv.back_to_step"; Target.STEP2 -> "samsungtv.back_to_step"; Target.STEP3 -> "samsungtv.back_to_step"
                else -> "samsungtv.retry"
            },
            ctaVars = when (s.back) { Target.STEP1 -> mapOf("n" to "1"); Target.STEP2 -> mapOf("n" to "2"); Target.STEP3 -> mapOf("n" to "3"); else -> emptyMap() },
            onCta = { guide.back(s.back) },
        )
    }
}

private enum class Art { TV, REMOTE_KEYS, REMOTE_NOKEYS, SWITCH, POWER, WAIT, DONE }

@Composable
private fun Page(
    step: Int?,
    title: String?,
    art: Art?,
    body: String? = null,
    bodyText: String? = null,
    hint: String? = null,
    cta: String? = null,
    ctaVars: Map<String, String> = emptyMap(),
    onCta: () -> Unit = {},
    busy: Boolean = false,
    trouble: String? = null,
    quiet: String? = null,
    onQuiet: () -> Unit = {},
    outline: List<String>? = null,
    /** Directly under the step indicator (DEB-5504, FR-13b: the remote tabs). */
    aboveArt: (@Composable () -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    var troubleOpen by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    if (step != null) {
        Text(str("samsungtv.step", mapOf("n" to step.toString())).uppercase(), color = colors.textDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(10.dp))
    }
    aboveArt?.let { it(); Spacer(Modifier.height(18.dp)) }
    if (art != null) {
        Illustration(art)
        Spacer(Modifier.height(22.dp))
    }
    if (title != null) {
        Text(str(title), color = colors.text, fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
    }
    val text = bodyText ?: body?.let { str(it) }
    if (text != null) Text(rich(text, colors.text), color = colors.textSecondary, fontSize = 16.sp, lineHeight = 23.sp, fontFamily = Sora, textAlign = TextAlign.Center)
    if (outline != null) {
        Spacer(Modifier.height(18.dp))
        Column(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            outline.forEachIndexed { i, key ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(22.dp).background(colors.accent, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = colors.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
                    }
                    Spacer(Modifier.size(10.dp))
                    Text(str(key), color = colors.text, fontSize = 14.sp, lineHeight = 19.sp, fontFamily = Sora)
                }
            }
        }
    }
    extra?.let { Spacer(Modifier.height(18.dp)); it() }
    if (hint != null) { Spacer(Modifier.height(14.dp)); Text(rich(str(hint), colors.text), color = colors.textDim, fontSize = 13.5.sp, lineHeight = 19.sp, fontFamily = Sora, textAlign = TextAlign.Center) }
    Spacer(Modifier.height(28.dp))
    if (cta != null) GuideButton(str(cta, ctaVars), busy = busy, onClick = onCta)
    if (quiet != null) {
        Spacer(Modifier.height(14.dp))
        Text(str(quiet), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora,
            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onQuiet).padding(8.dp))
    }
    if (trouble != null) {
        Spacer(Modifier.height(10.dp))
        if (!troubleOpen) Text(str("samsungtv.trouble"), color = colors.textDim, fontSize = 13.sp, fontFamily = Sora,
            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { troubleOpen = true }.padding(8.dp))
        else Text(rich(str(trouble), colors.text), color = colors.textSecondary, fontSize = 13.5.sp, lineHeight = 19.sp, fontFamily = Sora, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(32.dp))
}

/** `**bold**` runs, nothing else. */
private fun rich(text: String, bold: Color): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { i, part ->
        if (i % 2 == 1) withStyle(SpanStyle(color = bold, fontWeight = FontWeight.Bold)) { append(part) } else append(part)
    }
}

/** FR-3 — the phone's own address, big, its parts spaced for reading off a screen while typing with a remote. */
@Composable
private fun BigAddress(ip: String) {
    val colors = RaviloTheme.colors
    Box(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(16.dp)).padding(vertical = 18.dp, horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(Subnet.spaced(ip), color = colors.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, letterSpacing = 1.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun TvChoice(tv: SamsungTv, selectable: Boolean, onPick: () -> Unit) {
    val colors = RaviloTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).background(colors.surface, RoundedCornerShape(14.dp))
            .let { if (selectable) it.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onPick) else it }
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(tv.name, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, textAlign = TextAlign.Center)
        if (tv.model != tv.name) Text(tv.model, color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GuideButton(label: String, busy: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RaviloTheme.colors
    Box(
        modifier.fillMaxWidth().heightIn(min = 54.dp).testTag(SAMSUNG_GUIDE_PRIMARY_TAG)
            .background(colors.accentGradient, RoundedCornerShape(27.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = colors.onAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, modifier = Modifier.padding(horizontal = 16.dp)) }
}

@Composable
private fun ProgressBar(fraction: Float?) {
    val colors = RaviloTheme.colors
    Box(Modifier.fillMaxWidth().height(8.dp).background(colors.fg.copy(alpha = 0.12f), CircleShape)) {
        if (fraction != null) Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(8.dp).background(colors.accent, CircleShape))
        else Box(Modifier.fillMaxWidth(0.35f).height(8.dp).background(colors.accent, CircleShape))
    }
}

/** Our own vector drawings in the Ravilo theme — no photographs and no Samsung screenshots. */
@Composable
private fun Illustration(art: Art) {
    val colors = RaviloTheme.colors
    if (art == Art.REMOTE_KEYS || art == Art.REMOTE_NOKEYS) {
        Box(Modifier.size(width = 220.dp, height = 150.dp), contentAlignment = Alignment.Center) {
            RemoteDrawing(if (art == Art.REMOTE_KEYS) Remote.KEYS else Remote.NOKEYS, lit = true, height = 150.dp, face = colors.card, bottom = false)
        }
        return
    }
    val ink = colors.fg.copy(alpha = 0.55f)
    val face = colors.surface
    val accent = colors.accent
    Canvas(Modifier.size(width = 220.dp, height = 150.dp)) {
        val w = size.width; val h = size.height
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        when (art) {
            Art.TV, Art.WAIT, Art.DONE -> {
                drawRoundRect(face, Offset(w * 0.1f, h * 0.08f), Size(w * 0.8f, h * 0.62f), CornerRadius(14.dp.toPx()))
                drawRoundRect(ink, Offset(w * 0.1f, h * 0.08f), Size(w * 0.8f, h * 0.62f), CornerRadius(14.dp.toPx()), style = stroke)
                drawLine(ink, Offset(w * 0.38f, h * 0.9f), Offset(w * 0.62f, h * 0.9f), 3.dp.toPx(), StrokeCap.Round)
                drawLine(ink, Offset(w * 0.5f, h * 0.7f), Offset(w * 0.5f, h * 0.9f), 3.dp.toPx(), StrokeCap.Round)
                val c = Offset(w * 0.5f, h * 0.39f)
                when (art) {
                    Art.DONE -> { drawLine(accent, Offset(c.x - 24.dp.toPx(), c.y), Offset(c.x - 6.dp.toPx(), c.y + 18.dp.toPx()), 6.dp.toPx(), StrokeCap.Round); drawLine(accent, Offset(c.x - 6.dp.toPx(), c.y + 18.dp.toPx()), Offset(c.x + 26.dp.toPx(), c.y - 18.dp.toPx()), 6.dp.toPx(), StrokeCap.Round) }
                    Art.WAIT -> for (i in 0..2) drawCircle(accent.copy(alpha = 0.35f + 0.3f * i), 6.dp.toPx(), Offset(c.x + (i - 1) * 24.dp.toPx(), c.y))
                    else -> drawCircle(accent, 14.dp.toPx(), c)
                }
            }
            Art.REMOTE_KEYS, Art.REMOTE_NOKEYS -> {}   // drawn above, by RemoteDrawing
            Art.SWITCH -> {
                drawRoundRect(accent, Offset(w * 0.25f, h * 0.3f), Size(w * 0.5f, h * 0.38f), CornerRadius(h * 0.19f))
                drawCircle(Color.White, h * 0.14f, Offset(w * 0.75f - h * 0.19f, h * 0.49f))
            }
            Art.POWER -> {
                val r = h * 0.3f; val c = Offset(w * 0.5f, h * 0.5f)
                drawArc(accent, -60f, 300f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
                drawLine(accent, Offset(c.x, c.y - r - 4.dp.toPx()), Offset(c.x, c.y - 4.dp.toPx()), 6.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

private val CARD_GAP = 12.dp
private val CARD_PAD = 10.dp
@Composable private fun cardLabelStyle() = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)

private fun Remote.labelKey() = if (this == Remote.KEYS) "samsungtv.step1.keys" else "samsungtv.step1.nokeys"

/**
 * DEB-5504 (FR-13a) — the question's two cards, side by side. When a label no longer fits its half — over two lines, or a word wider
 * than the card — they stack, one per row, each drawing at the left. The decision is a measurement of the label, not a read of the font
 * setting (the 200 % font setting is just the usual way to get there).
 */
@Composable
private fun RemoteCards(onPick: (Remote) -> Unit) {
    val colors = RaviloTheme.colors
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = cardLabelStyle()
    val labels = listOf(Remote.KEYS, Remote.NOKEYS).map { it to str(it.labelKey()) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inner = with(density) { ((maxWidth - CARD_GAP) / 2 - CARD_PAD * 2 - 2.dp).roundToPx() }
        val sideBySide = labels.all { (_, label) ->
            measurer.measure(label, labelStyle, constraints = Constraints(maxWidth = inner)).lineCount <= 2 &&
                label.split(' ').all { measurer.measure(it, labelStyle).size.width <= inner }
        }
        @Composable fun card(r: Remote, label: String, modifier: Modifier) {
            val base = modifier
                .background(colors.card, RoundedCornerShape(14.dp))
                .border(1.dp, colors.fg.copy(alpha = 0.09f), RoundedCornerShape(14.dp))
                .testTag("$SAMSUNG_GUIDE_REMOTE_CARD_TAG-${r.name.lowercase()}")
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = { onPick(r) })
            if (sideBySide) Column(base.padding(start = CARD_PAD, end = CARD_PAD, top = 16.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.height(140.dp), contentAlignment = Alignment.BottomCenter) { RemoteDrawing(r, lit = false, height = 140.dp, face = colors.background, bottom = true) }
                Text(label, color = colors.text, style = labelStyle, textAlign = TextAlign.Center)
            } else Row(base.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RemoteDrawing(r, lit = false, height = 124.dp, face = colors.background, bottom = false)
                Text(label, color = colors.text, style = labelStyle, modifier = Modifier.weight(1f))
            }
        }
        if (sideBySide) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(CARD_GAP)) {
            labels.forEach { (r, label) -> card(r, label, Modifier.weight(1f).fillMaxHeight()) }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            labels.forEach { (r, label) -> card(r, label, Modifier.fillMaxWidth()) }
        }
    }
}

/**
 * DEB-5504 (FR-13b) — the choice after the tap: both labels, the chosen one raised. The chosen tab takes the card surface, a hairline and
 * a small shadow and never the accent, which on this screen means "the key to press".
 */
@Composable
private fun RemoteTabs(chosen: Remote, onPick: (Remote) -> Unit) {
    val colors = RaviloTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.fg.copy(alpha = 0.06f), RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (r in listOf(Remote.KEYS, Remote.NOKEYS)) {
            val on = r == chosen
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp)
                    .testTag("$SAMSUNG_GUIDE_REMOTE_TAB_TAG-${r.name.lowercase()}")
                    .let { if (on) it.shadow(1.dp, shape).background(colors.card, shape).border(1.dp, colors.fg.copy(alpha = 0.16f), shape) else it }
                    .selectable(selected = on, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = { onPick(r) })
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(str(r.labelKey()), color = if (on) colors.text else colors.textDim, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, textAlign = TextAlign.Center)
            }
        }
    }
}

/** FR-13c — the four coloured keys every Samsung remote carries. Fixed data, never the accent: a key's colour is the key's own identity. */
private val KEY_COLOURS = listOf(Color(0xFFE05260), Color(0xFF3FBF7F), Color(0xFFE8C045), Color(0xFF4AA3E8))

/** The basic remote is 300 units long and the Smart Remote 212, on one scale, so the long one looks longer at every size (FR-13c). */
private const val REMOTE_UNITS_LONG = 300f
private const val REMOTE_UNITS_SMART = 212f

/**
 * DEB-5504 (FR-13c) — one of the two remotes as our own vectors on a canvas [height] tall (the long remote's full length; the Smart
 * Remote takes its share of it, [bottom]- or centre-aligned in it). [lit] draws what the viewer presses: keys 1–5 filled with the accent,
 * or an accent ring on the Number/Color key. [face] is the surface the remote is not standing on.
 */
@Composable
private fun RemoteDrawing(kind: Remote, lit: Boolean, height: Dp, face: Color, bottom: Boolean) {
    val colors = RaviloTheme.colors
    val fg = colors.fg; val accent = colors.accent
    Canvas(Modifier.size(width = height / 3, height = height)) {
        val u = size.height / REMOTE_UNITS_LONG
        val dy = if (kind == Remote.KEYS) 0f else (REMOTE_UNITS_LONG - REMOTE_UNITS_SMART) * u * (if (bottom) 1f else 0.5f)
        if (kind == Remote.KEYS) drawKeypadRemote(u, face, fg, accent, lit) else drawSmartRemote(u, dy, face, fg, accent, lit)
    }
}

private fun DrawScope.disc(u: Float, dy: Float, x: Float, y: Float, r: Float, color: Color) = drawCircle(color, r * u, Offset(x * u, y * u + dy))

private fun DrawScope.drawKeypadRemote(u: Float, face: Color, fg: Color, accent: Color, lit: Boolean) {
    val key = fg.copy(alpha = 0.14f)
    drawRoundRect(face, Offset(20 * u, 3 * u), Size(60 * u, 294 * u), CornerRadius(18 * u))
    drawRoundRect(fg.copy(alpha = 0.5f), Offset(20 * u, 3 * u), Size(60 * u, 294 * u), CornerRadius(18 * u), style = Stroke(2.4f * u))
    disc(u, 0f, 31f, 22f, 5f, key); disc(u, 0f, 69f, 22f, 5f, key)                       // power, source
    for (n in 1..9) disc(u, 0f, 31f + ((n - 1) % 3) * 19f, 56f + ((n - 1) / 3) * 24f, 7.6f, if (lit && n <= 5) accent else key)
    disc(u, 0f, 50f, 128f, 7.6f, key)                                                      // 0
    drawCircle(fg.copy(alpha = 0.26f), 25 * u, Offset(50 * u, 188 * u), style = Stroke(4.6f * u))
    disc(u, 0f, 50f, 188f, 9.5f, key)                                                      // D-pad
    KEY_COLOURS.forEachIndexed { i, c -> disc(u, 0f, 28.5f + i * 14.3f, 240f, 5.2f, c) }
    drawRoundRect(key, Offset(24 * u, 258 * u), Size(18 * u, 34 * u), CornerRadius(9 * u))   // volume, channel
    drawRoundRect(key, Offset(58 * u, 258 * u), Size(18 * u, 34 * u), CornerRadius(9 * u))
}

private fun DrawScope.drawSmartRemote(u: Float, dy: Float, face: Color, fg: Color, accent: Color, lit: Boolean) {
    val key = fg.copy(alpha = 0.14f)
    drawRoundRect(face, Offset(22 * u, 3 * u + dy), Size(56 * u, 206 * u), CornerRadius(26 * u))
    drawRoundRect(fg.copy(alpha = 0.5f), Offset(22 * u, 3 * u + dy), Size(56 * u, 206 * u), CornerRadius(26 * u), style = Stroke(2.4f * u))
    disc(u, dy, 33f, 24f, 4.6f, key); disc(u, dy, 67f, 24f, 4.6f, key)
    // The Number/Color key, drawn larger than true scale because it is the subject of the sentence. Ringed, not filled: its own marking stays readable.
    val k = Offset(23 * u, 37 * u + dy); val ks = Size(34 * u, 27 * u)
    drawRoundRect(fg.copy(alpha = if (lit) 0.07f else 0.14f), k, ks, CornerRadius(13.5f * u))
    if (lit) drawRoundRect(accent, k, ks, CornerRadius(13.5f * u), style = Stroke(2.6f * u))
    KEY_COLOURS.forEachIndexed { i, c -> disc(u, dy, 31f + i * 6f, 50.5f, 2.1f, c) }
    disc(u, dy, 69f, 50.5f, 5f, key)
    drawCircle(fg.copy(alpha = 0.26f), 25 * u, Offset(50 * u, 104 * u + dy), style = Stroke(4.6f * u))
    disc(u, dy, 50f, 104f, 9f, key)
    disc(u, dy, 32f, 148f, 4.8f, key); disc(u, dy, 50f, 148f, 4.8f, key); disc(u, dy, 68f, 148f, 4.8f, key)
    drawRoundRect(key, Offset(26 * u, 166 * u + dy), Size(17 * u, 34 * u), CornerRadius(8.5f * u))
    drawRoundRect(key, Offset(57 * u, 166 * u + dy), Size(17 * u, 34 * u), CornerRadius(8.5f * u))
}

// ─── DEB-5905 — Your Samsung TVs (FR-10a–FR-10g), direction A, *Stack* ───────────────────────────────────────

/** The hairline every card carries: on Daylight the card is 2 % from the page and without an edge does not read as an object. */
@Composable private fun hairline() = RaviloTheme.colors.fg.copy(alpha = 0.09f)

/** FR-10a — the page: a 44 dp back target, the title left-aligned on the 18 dp gutter, then the list and its footer. */
@Composable
internal fun OverviewPage(ov: GuideScreen.Overview, guide: SamsungGuide, onBack: () -> Unit) {
    val colors = RaviloTheme.colors
    Column(Modifier.fillMaxSize().testTag(SAMSUNG_OVERVIEW_TAG)) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 18.dp, top = 2.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("‹", color = colors.text, fontSize = 28.sp) }
            Spacer(Modifier.size(2.dp))
            Text(str("samsungtv.ov.title"), color = colors.text, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, modifier = Modifier.weight(1f))
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp)) {
            if (ov.sent) { Notice("samsungtv.waiting.title", check = true); Spacer(Modifier.height(14.dp)) }
            if (!ov.wifi) { Notice("samsungtv.intro.nowifi", check = false); Spacer(Modifier.height(14.dp)) }
            if (ov.scanning) ScanLine()
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ov.cards.forEachIndexed { i, card -> OverviewCard(ov, card, guide, Modifier.testTag("$SAMSUNG_CARD_TAG-$i")) }
                if (ov.scanning) Box(Modifier.fillMaxWidth().height(72.dp).background(colors.fg.copy(alpha = 0.05f), RoundedCornerShape(14.dp)))
            }
            if (!ov.scanning) OverviewFooter(ov, guide)
        }
    }
}

/** FR-10e, FR-10g — a notice: 14 sp, `fg` at 6 %, radius 14, with a hairline. [check] is *Request sent*; otherwise it is the no-Wi‑Fi sentence. */
@Composable
private fun Notice(key: String, check: Boolean) {
    val colors = RaviloTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().background(colors.fg.copy(alpha = 0.06f), shape).border(1.dp, hairline(), shape).padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top,
    ) {
        val ink = if (check) colors.good else colors.textDim
        Canvas(Modifier.padding(top = 1.dp).size(20.dp)) {
            val w = 2.dp.toPx(); val st = Stroke(width = w, cap = StrokeCap.Round)
            if (check) {
                drawCircle(ink, size.minDimension / 2 - w, style = st)
                drawLine(ink, Offset(size.width * 0.3f, size.height * 0.52f), Offset(size.width * 0.45f, size.height * 0.66f), w, StrokeCap.Round)
                drawLine(ink, Offset(size.width * 0.45f, size.height * 0.66f), Offset(size.width * 0.72f, size.height * 0.36f), w, StrokeCap.Round)
            } else {
                val c = Offset(size.width / 2, size.height * 0.84f)
                for (r in listOf(0.22f, 0.42f, 0.62f).map { it * size.width }) drawArc(ink, 225f, 90f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = st)
                drawCircle(ink, 1.6.dp.toPx(), c)
                drawLine(ink, Offset(size.width * 0.12f, size.height * 0.1f), Offset(size.width * 0.9f, size.height * 0.92f), w, StrokeCap.Round)
            }
        }
        Text(str(key), color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = Sora, modifier = Modifier.weight(1f))
    }
}

/** FR-10d — the scan line, a turning arc and *Looking for TVs on this Wi‑Fi…*. The cards below it are already the server's. */
@Composable
private fun ScanLine() {
    val colors = RaviloTheme.colors
    val turn by rememberInfiniteTransition(label = "stvScan")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "stvScanRot")
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(18.dp)) {
            drawArc(colors.accentSecondary, turn - 45f, 270f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
        Text(str("samsungtv.ov.searching"), color = colors.textSecondary, fontSize = 14.5.sp, lineHeight = 20.sp, fontFamily = Sora)
    }
    Spacer(Modifier.height(10.dp))
}

/** FR-10 item 4, FR-10e — *n TVs found on this Wi‑Fi* with *Search again*; with no Wi‑Fi, the shipped *Try again* alone. */
@Composable
private fun OverviewFooter(ov: GuideScreen.Overview, guide: SamsungGuide) {
    val colors = RaviloTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ov.wifi) {
            val count = when (ov.found) { 0 -> str("samsungtv.ov.found_none"); 1 -> str("samsungtv.ov.found_one"); else -> str("samsungtv.ov.found_n", mapOf("n" to ov.found.toString())) }
            Text(count, color = colors.textDim, fontSize = 12.5.sp, lineHeight = 17.sp, fontFamily = Sora, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
        }
        Text(
            str(if (ov.wifi) "samsungtv.ov.search_again" else "samsungtv.retry"), color = colors.accentSecondary, fontSize = 12.5.sp, lineHeight = 17.sp,
            fontWeight = FontWeight.SemiBold, fontFamily = Sora, softWrap = false, maxLines = 1,
            modifier = Modifier.testTag(SAMSUNG_OVERVIEW_SEARCH_TAG)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { guide.refresh() }.padding(horizontal = 4.dp, vertical = 12.dp),
        )
    }
}

/** FR-10b — one card: the big card for an Approved or Ready TV, a small one for every other. */
@Composable
private fun OverviewCard(ov: GuideScreen.Overview, card: TvCard, guide: SamsungGuide, modifier: Modifier) {
    val colors = RaviloTheme.colors
    val notFound = ov.notFound(card)
    val showVersions = card.big && (card.tv != null || ov.scanning) && !notFound
    val shape = RoundedCornerShape(if (card.big) 18.dp else 14.dp)
    Column(
        modifier.fillMaxWidth().background(colors.card, shape).border(1.dp, hairline(), shape)
            .padding(start = if (card.big) 16.dp else 14.dp, end = if (card.big) 16.dp else 14.dp, top = if (card.big) 15.dp else 13.dp, bottom = if (card.big) 16.dp else 12.dp),
    ) {
        CardHead(card)
        if (notFound) Text(str("samsungtv.ov.not_found"), color = colors.textDim, fontSize = 13.sp, lineHeight = 18.sp, fontFamily = Sora, modifier = Modifier.padding(top = 7.dp))
        if (card.badge == Badge.REJECTED) card.request?.decisionNote?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = colors.textSecondary, fontSize = 13.5.sp, lineHeight = 19.sp, fontFamily = Sora, modifier = Modifier.padding(top = 10.dp))
        }
        if (showVersions) Versions(card, scanning = ov.scanning && card.tv == null)
        CardAction(card, guide)
    }
}

/** FR-10b — name over model code, the badge at the row's end; the name keeps 130 sp and the badge drops to its own line when it would not fit beside it. */
@Composable
private fun CardHead(card: TvCard) {
    val colors = RaviloTheme.colors
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val badgeText = str(when (card.badge) {
        Badge.APPROVED -> "samsungtv.badge.approved"; Badge.WAITING -> "samsungtv.badge.waiting"
        Badge.REJECTED -> "samsungtv.badge.rejected"; Badge.NOT_SET_UP -> "samsungtv.badge.notsetup"
    })
    val badgeStyle = badgeTextStyle()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val badgeWidth = with(density) { measurer.measure(badgeText.uppercase(), badgeStyle, softWrap = false).size.width.toDp() } + 12.dp
        val beside = maxWidth - badgeWidth - 10.dp >= with(density) { 130.sp.toDp() }
        val name: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                Text(card.name, color = colors.text, fontSize = if (card.big) 18.5.sp else 17.sp, lineHeight = if (card.big) 24.sp else 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (card.model.isNotBlank()) Text(card.model, color = colors.textDim, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.24.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (beside) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            name(Modifier.weight(1f)); BadgePill(card.badge, badgeText)
        } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { name(Modifier.fillMaxWidth()); BadgePill(card.badge, badgeText) }
    }
}

@Composable private fun badgeTextStyle() = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 0.8.sp)

/** FR-10f — the geometry of the shipped *Experimental* badge. Never wraps inside itself. */
@Composable
private fun BadgePill(badge: Badge, text: String) {
    val colors = RaviloTheme.colors
    val shape = RoundedCornerShape(6.dp)
    val (ink, fill) = when (badge) {
        Badge.APPROVED -> colors.good to colors.good.copy(alpha = if (colors.isLight) 0.14f else 0.16f)
        Badge.WAITING -> colors.textSecondary to colors.fg.copy(alpha = 0.10f)
        Badge.REJECTED -> colors.bad to colors.bad.copy(alpha = if (colors.isLight) 0.12f else 0.15f)
        Badge.NOT_SET_UP -> colors.textDim to Color.Transparent
    }
    Text(
        text.uppercase(), color = ink, style = badgeTextStyle(), softWrap = false, maxLines = 1,
        modifier = Modifier.background(fill, shape).let { if (badge == Badge.NOT_SET_UP) it.border(1.dp, colors.fg.copy(alpha = 0.16f), shape) else it }.padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** FR-10b — *On the TV* and *Latest* under a hairline, label left and value right; or the sentence for a release that does not cover the TV yet. */
@Composable
private fun Versions(card: TvCard, scanning: Boolean) {
    val colors = RaviloTheme.colors
    Spacer(Modifier.height(13.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(hairline()))
    Spacer(Modifier.height(12.dp))
    if (scanning) VersionRow("samsungtv.ov.on_tv") { Box(Modifier.size(width = 54.dp, height = 11.dp).background(colors.fg.copy(alpha = 0.14f), RoundedCornerShape(6.dp))) }
    else if (card.tv != null) when (val on = card.onTv) {
        is InstalledRavilo.Version -> VersionRow("samsungtv.ov.on_tv") { VersionValue(on.version.toString()) }
        InstalledRavilo.NotInstalled -> VersionRow("samsungtv.ov.on_tv") { VersionValue(str("samsungtv.ov.not_installed"), word = true) }
        else -> VersionRow("samsungtv.ov.on_tv") { VersionValue(str("samsungtv.ov.unknown"), dim = true) }
    }
    card.latestText?.let { latest -> Spacer(Modifier.height(3.dp)); VersionRow("samsungtv.ov.latest") { VersionValue(latest, hi = card.action == CardAction.UPDATE) } }
    if (card.nextRelease) Text(str("samsungtv.ov.next_release"), color = colors.textSecondary, fontSize = 13.5.sp, lineHeight = 19.sp, fontFamily = Sora, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun VersionRow(label: String, value: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(str(label), color = RaviloTheme.colors.textSecondary, fontSize = 13.5.sp, lineHeight = 19.sp, fontFamily = Sora, modifier = Modifier.weight(1f))
        value()
    }
}

/** A version is JetBrains Mono's job; a value that is a word (*Not installed*) is Sora, or it reads as something the TV returned. */
@Composable
private fun VersionValue(text: String, word: Boolean = false, dim: Boolean = false, hi: Boolean = false) {
    val colors = RaviloTheme.colors
    Text(
        text, color = if (hi) colors.accentSecondary else if (dim || word) colors.textDim else colors.text, fontSize = 15.sp, lineHeight = 20.sp,
        fontWeight = if (word || dim) FontWeight.Medium else FontWeight.SemiBold, fontFamily = if (word) Sora else FontFamily.Monospace,
    )
}

/** FR-10 item 3 and FR-10g — the card's one action: a primary button on a big card, a quiet link on the text column's left edge on a small one. */
@Composable
private fun CardAction(card: TvCard, guide: SamsungGuide) {
    val colors = RaviloTheme.colors
    when (card.action) {
        CardAction.INSTALL, CardAction.UPDATE -> {
            Spacer(Modifier.height(14.dp))
            GuideButton(
                if (card.action == CardAction.UPDATE) str("samsungtv.ov.update_to", mapOf("v" to card.latestText.orEmpty())) else str("samsungtv.ready.cta"),
                busy = false, onClick = { guide.install(card) },
                modifier = Modifier.testTag(SAMSUNG_CARD_ACTION_TAG),
            )
        }
        CardAction.UP_TO_DATE -> Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(17.dp)) {
                val w = 2.2.dp.toPx()
                drawLine(colors.good, Offset(size.width * 0.15f, size.height * 0.55f), Offset(size.width * 0.4f, size.height * 0.8f), w, StrokeCap.Round)
                drawLine(colors.good, Offset(size.width * 0.4f, size.height * 0.8f), Offset(size.width * 0.88f, size.height * 0.22f), w, StrokeCap.Round)
            }
            Text(str("samsungtv.ov.up_to_date"), color = colors.good, fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
            Spacer(Modifier.weight(1f))
            QuietLink("samsungtv.ov.reinstall", Modifier.testTag(SAMSUNG_CARD_REINSTALL_TAG)) { guide.install(card) }
        }
        CardAction.SET_UP -> QuietLink("samsungtv.ov.set_up", Modifier.testTag(SAMSUNG_CARD_ACTION_TAG)) { guide.setUp(card) }
        CardAction.ASK_AGAIN -> QuietLink("samsungtv.rejected.again", Modifier.testTag(SAMSUNG_CARD_ACTION_TAG)) { guide.askAgain(card) }
        CardAction.WITHDRAW -> QuietLink("samsungtv.waiting.withdraw", Modifier.testTag(SAMSUNG_CARD_ACTION_TAG)) { guide.withdraw(card) }
        CardAction.NONE -> {}
    }
}

@Composable
private fun QuietLink(key: String, modifier: Modifier, onClick: () -> Unit) {
    Text(
        str(key), color = RaviloTheme.colors.accentSecondary, fontSize = 13.5.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
        modifier = modifier.heightIn(min = 44.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick).wrapContentHeight(Alignment.CenterVertically),
    )
}
