package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.components.handCursor
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.focus.focusRing
import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme

/** DEB-2233 (FR-12) — test tags for the single-sign-on controls. The provider buttons are `sso-button-<index>`. */
object SsoTags {
    const val CANCEL = "sso-cancel"
    const val WAITING = "sso-waiting"
    const val OR = "sso-or"
    const val ERROR = "sso-error"
    fun button(index: Int) = "sso-button-$index"
}

/**
 * DEB-2233 (FR-12, decision 4 as the design round recommends it) — one ***Sign in with ‹name›*** per name, full width, above the username
 * field, the name verbatim. Outlined, not filled: the form's own *Sign in* stays the screen's one filled control. An *or* divider closes the set.
 * [requesters] has one entry per name; Up from the first and Down from the last are the caller's ([onUpFromFirst], [onDownFromLast]).
 */
@Composable
internal fun SsoButtons(
    names: List<String>,
    requesters: List<FocusRequester>,
    enabled: Boolean,
    onChosen: (String) -> Unit,
    onUpFromFirst: () -> Unit,
    onDownFromLast: () -> Unit,
) {
    val colors = RaviloTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        names.forEachIndexed { i, name ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            SsoOutlineButton(
                label = str("sso.button", mapOf("name" to name)),
                tag = SsoTags.button(i),
                focusRequester = requesters[i],
                enabled = enabled,
                glyph = true,
                onSelect = { onChosen(name) },
                onUp = { if (i == 0) onUpFromFirst() else requesters[i - 1].requestFocus() },
                onDown = { if (i == names.lastIndex) onDownFromLast() else requesters[i + 1].requestFocus() },
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth().testTag(SsoTags.OR), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).background(colors.textDim.copy(alpha = 0.3f)))
            Text(
                str("sso.or").uppercase(), color = colors.textDim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Box(Modifier.weight(1f).height(1.dp).background(colors.textDim.copy(alpha = 0.3f)))
        }
        Spacer(Modifier.height(18.dp))
    }
}

/** While the browser is out (FR-12): *Finish signing in in your browser* and **Cancel**, which forgets the attempt. */
@Composable
internal fun SsoWaiting(cancelRequester: FocusRequester, onCancel: () -> Unit) {
    val colors = RaviloTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 36.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(36.dp), color = colors.accentSecondary, strokeWidth = 3.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            str("sso.waiting"), color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            modifier = Modifier.testTag(SsoTags.WAITING),
        )
        Spacer(Modifier.height(24.dp))
        Box(Modifier.width(190.dp)) {
            SsoOutlineButton(
                label = str("action.cancel"), tag = SsoTags.CANCEL, focusRequester = cancelRequester, enabled = true, glyph = false,
                onSelect = onCancel, onUp = {}, onDown = {}, onBack = onCancel,
            )
        }
    }
}

@Composable
private fun SsoOutlineButton(
    label: String,
    tag: String,
    focusRequester: FocusRequester,
    enabled: Boolean,
    glyph: Boolean,
    onSelect: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    var focused by rememberFocusVisual()
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .background(if (focused && enabled) colors.accent else colors.surfaceVariant, shape)
            .border(1.dp, colors.textDim.copy(alpha = 0.3f), shape)
            .focusRing(focused && enabled, shape, colors.focusRing, 2.dp)
            .then(
                if (enabled) {
                    Modifier.handCursor().dpadFocusable(
                        focusRequester = focusRequester, onFocused = { focused = true }, onBlurred = { focused = false },
                        onSelect = onSelect, onUp = onUp, onDown = onDown, onBack = onBack,
                    )
                } else Modifier
            )
            .padding(vertical = 14.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (!enabled) colors.textSecondary else if (focused) colors.onAccent else colors.text
        if (glyph) {
            GlobeGlyph(ink)
            Spacer(Modifier.width(10.dp))
        }
        Text(label, color = ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

/** A neutral globe, never a provider's mark: the operator names the provider and we are given no artwork (the design round). Drawn, so no font carries it. */
@Composable
private fun GlobeGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val w = 1.6.dp.toPx()
        val r = size.minDimension / 2 - w / 2
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(color, radius = r, center = c, style = Stroke(w))
        drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = w)
        drawOval(color, topLeft = Offset(c.x - r * 0.45f, c.y - r), size = Size(r * 0.9f, r * 2), style = Stroke(w))
    }
}
