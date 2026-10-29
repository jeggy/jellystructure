package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.CheckGlyph
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.reportTextFieldFocus
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk
import dev.jellystructure.ravilo.ui.theme.accentGradient
import kotlinx.coroutines.launch

/** DEB-2233 (FR-6) — test tags for *Sign in a TV*. */
object TvApproveTags {
    const val ROW = "profile-sign-in-tv"
    const val FIELD = "tvcode-field"
    const val CONTINUE = "tvcode-continue"
    const val CONFIRM_TITLE = "tvcode-confirm-title"
    const val SIGN_IN = "tvcode-sign-in"
    const val CANCEL = "tvcode-cancel"
    const val SWITCH = "tvcode-switch"
    const val OUTCOME = "tvcode-outcome"
    const val OUTCOME_ACTION = "tvcode-outcome-action"
}

private val WARN = Color(0xFFF5B73B)
private val BAD = Color(0xFFF5667A)
private val GOOD = Color(0xFF3DD6A0)

/**
 * DEB-2233 (FR-6) — *Sign in a TV* on the phone, Ravilo Web and the desktop: the code field, the confirm screen that names the TV
 * and the viewer, and the five outcomes. Takes the layout of the design round `2026-10-23-tv-signin` (surface 2).
 *
 * [linkCode] is a code that arrived in a link; the TV is looked up at once. [displayName] is the active profile, the one that
 * approves. [onSwitch] is null when there is no other profile to switch to; it leaves for the profile picker and the held link
 * brings the code back (the caller holds it).
 */
@Composable
fun TvApproveScreen(
    store: TvApproveStore,
    displayName: String,
    linkCode: String?,
    onBack: () -> Unit,
    onSwitch: ((code: String) -> Unit)?,
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(store, linkCode) { store.start(linkCode) }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "‹", color = colors.text, fontSize = 26.sp,
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Text(str("tvcode.row"), color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk)
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
                when (val s = state) {
                    is TvApproveState.Entering -> CodeField(s, store) { scope.launch { store.submit() } }
                    is TvApproveState.Checking -> Busy(s.code)
                    is TvApproveState.Confirm -> Confirm(s.code, s.tvName, displayName, onSwitch, onCancel = store::reset) { scope.launch { store.confirm() } }
                    is TvApproveState.Approving -> Busy(s.code)
                    is TvApproveState.Done -> Outcome(
                        glyph = { CheckGlyph(GOOD, 40.dp) },
                        title = str("tvcode.done", mapOf("tv" to s.tvName)), body = str("tvcode.done_body"),
                        action = str("tvcode.done_cta"), onAction = onBack,
                    )
                    is TvApproveState.Failed -> when (s.kind) {
                        TvApproveFailure.EXPIRED -> Outcome({ Mark("!", WARN) }, str("tvcode.expired"), null, str("tvcode.get_new"), store::reset)
                        TvApproveFailure.USED -> Outcome({ Mark("!", WARN) }, str("tvcode.used"), null, str("tvcode.get_new"), store::reset)
                        TvApproveFailure.UNAVAILABLE -> Outcome({ Mark("!", WARN) }, str("tvcode.unavailable"), null, str("action.retry")) { scope.launch { store.retry() } }
                        TvApproveFailure.UNREACHABLE -> Outcome({ Mark("!", BAD) }, str("login.error_unreachable"), null, str("action.retry")) { scope.launch { store.retry() } }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeField(s: TvApproveState.Entering, store: TvApproveStore, onContinue: () -> Unit) {
    val colors = RaviloTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Text(
        str("tvcode.body"), color = if (s.problem) BAD else colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = Sora,
        modifier = Modifier.testTag("${TvApproveTags.FIELD}-body"),
    )
    Spacer(Modifier.height(16.dp))
    Text(str("tvcode.field"), color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora)
    Spacer(Modifier.height(4.dp))
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(colors.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = s.code,
            onValueChange = store::type,
            visualTransformation = TvCodeGrouping,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(TvApproveTags.FIELD).reportTextFieldFocus(),
            textStyle = TextStyle(color = colors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Text, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onContinue() }),
            cursorBrush = SolidColor(colors.accent),
        )
    }
    Spacer(Modifier.height(12.dp))
    PrimaryButton(str("tvcode.continue"), TvApproveTags.CONTINUE, onContinue)
}

@Composable
private fun Busy(code: String) {
    val colors = RaviloTheme.colors
    Text(groupTvCode(code), color = colors.textSecondary, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 4.sp)
}

@Composable
private fun Confirm(
    code: String, tvName: String, displayName: String, onSwitch: ((String) -> Unit)?, onCancel: () -> Unit, onSignIn: () -> Unit,
) {
    val colors = RaviloTheme.colors
    Row(
        Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tvName, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora, modifier = Modifier.weight(1f))
        Text(groupTvCode(code), color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
    Spacer(Modifier.height(22.dp))
    Text(
        str("tvcode.confirm", mapOf("tv" to tvName, "name" to displayName)),
        color = colors.text, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk,
        modifier = Modifier.testTag(TvApproveTags.CONFIRM_TITLE),
    )
    Spacer(Modifier.height(8.dp))
    Text(str("tvcode.confirm_body"), color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = Sora)
    Spacer(Modifier.height(20.dp))
    PrimaryButton(str("login.sign_in"), TvApproveTags.SIGN_IN, onSignIn)
    Spacer(Modifier.height(10.dp))
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(TvApproveTags.CANCEL)
            .background(colors.fg.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) { Text(str("action.cancel"), color = colors.fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora) }
    if (onSwitch != null) {
        Spacer(Modifier.height(22.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.textDim.copy(alpha = 0.18f)))
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(colors.accentGradient), contentAlignment = Alignment.Center) {
                Text(displayName.take(2).uppercase(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
            }
            Text(displayName, color = colors.textSecondary, fontSize = 14.sp, fontFamily = Sora, modifier = Modifier.weight(1f).padding(start = 10.dp))
            Text(
                str("tvcode.not_you"), color = colors.accentSecondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora,
                modifier = Modifier.testTag(TvApproveTags.SWITCH)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSwitch(code) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Outcome(glyph: @Composable () -> Unit, title: String, body: String?, action: String, onAction: () -> Unit) {
    val colors = RaviloTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 36.dp).testTag(TvApproveTags.OUTCOME), horizontalAlignment = Alignment.CenterHorizontally) {
        glyph()
        Spacer(Modifier.height(18.dp))
        Text(title, color = colors.text, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, fontFamily = SpaceGrotesk, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(body, color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(action, TvApproveTags.OUTCOME_ACTION, onAction)
    }
}

@Composable
private fun Mark(glyph: String, tint: Color) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Text(glyph, color = tint, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = Sora)
    }
}

@Composable
private fun PrimaryButton(label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
            .clip(RoundedCornerShape(24.dp)).background(RaviloTheme.colors.accentGradient)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora) }
}
