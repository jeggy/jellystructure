package dev.jellystructure.ravilo.ui.samsungtv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient

const val SAMSUNG_GUIDE_TAG = "samsung-guide"
const val SAMSUNG_GUIDE_PRIMARY_TAG = "samsung-guide-primary"

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

/** The guide's screen for [screen], without the page chrome — what the screenshots render. */
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
            cta = if (s.tvs.size == 1) "samsungtv.found.confirm" else null, onCta = { guide.choose(s.tvs[0], s.forInstall) },
        ) {
            s.tvs.forEach { tv -> TvChoice(tv, selectable = s.tvs.size > 1) { guide.choose(tv, s.forInstall) } }
        }
        GuideScreen.NotFound -> Page(null, "samsungtv.notfound.title", body = "samsungtv.notfound.body", art = Art.TV, cta = "samsungtv.retry", onCta = { guide.find() })
        is GuideScreen.Step1 -> Page(
            1, "samsungtv.step1.title", art = Art.REMOTE,
            body = if (s.tv.appSettingsMenu) "samsungtv.step1.body_new" else "samsungtv.step1.body", hint = "samsungtv.step1.hint",
            cta = "samsungtv.next", onCta = { guide.next() },
        )
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
        is GuideScreen.Waiting -> {
            val approved = s.request.state == "APPROVED"
            Page(
                null, if (approved) "samsungtv.waiting.approved_title" else "samsungtv.waiting.title", art = Art.WAIT,
                body = if (approved) "samsungtv.waiting.approved_body" else "samsungtv.waiting.body",
                quiet = if (s.request.state == "REQUESTED") "samsungtv.waiting.withdraw" else null, onQuiet = { guide.withdraw() },
            )
        }
        is GuideScreen.Ready -> Page(null, "samsungtv.ready.title", art = Art.TV, body = "samsungtv.ready.body", cta = "samsungtv.ready.cta", onCta = { guide.install() })
        is GuideScreen.Rejected -> Page(
            null, "samsungtv.rejected.title", art = Art.TV, body = if (s.request.decisionNote.isNullOrBlank()) "samsungtv.rejected.body" else null,
            bodyText = s.request.decisionNote?.takeIf { it.isNotBlank() }, cta = "samsungtv.rejected.again", onCta = { guide.askAgain() },
        )
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

private enum class Art { TV, REMOTE, SWITCH, POWER, WAIT, DONE }

@Composable
private fun Page(
    step: Int?,
    title: String?,
    art: Art,
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
    extra: (@Composable () -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    var troubleOpen by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    if (step != null) {
        Text(str("samsungtv.step", mapOf("n" to step.toString())).uppercase(), color = colors.textDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Sora, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(10.dp))
    }
    Illustration(art)
    Spacer(Modifier.height(22.dp))
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
private fun GuideButton(label: String, busy: Boolean, onClick: () -> Unit) {
    val colors = RaviloTheme.colors
    Box(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag(SAMSUNG_GUIDE_PRIMARY_TAG)
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
            Art.REMOTE -> {
                drawRoundRect(face, Offset(w * 0.36f, h * 0.02f), Size(w * 0.28f, h * 0.96f), CornerRadius(22.dp.toPx()))
                drawRoundRect(ink, Offset(w * 0.36f, h * 0.02f), Size(w * 0.28f, h * 0.96f), CornerRadius(22.dp.toPx()), style = stroke)
                for (i in 0 until 5) drawCircle(accent, 5.dp.toPx(), Offset(w * (0.43f + 0.07f * (i % 3)), h * (0.2f + 0.14f * (i / 3))))
                for (i in 0 until 3) drawCircle(ink.copy(alpha = 0.35f), 5.dp.toPx(), Offset(w * (0.43f + 0.07f * i), h * 0.52f))
                for (i in 0 until 3) drawCircle(ink.copy(alpha = 0.35f), 5.dp.toPx(), Offset(w * (0.43f + 0.07f * i), h * 0.68f))
            }
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
