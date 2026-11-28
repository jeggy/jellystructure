package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.focus.focusRing
import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.isTvPlatform
import dev.jellystructure.ravilo.ui.seams.reportTextFieldFocus
import dev.jellystructure.ravilo.ui.seams.safeAreaPadding
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora

/** R349 — test tags for the server-setup form. */
object ServerSetupTags {
    /** The address control as the D-pad sees it (focused without typing). */
    const val ADDRESS = "setup-address"
    /** The text field inside it, focusable only once OK has been pressed on [ADDRESS]. */
    const val ADDRESS_FIELD = "setup-address-field"
    const val CONNECT = "setup-connect"
    /** DEB-5013 (FR-4) — the hidden-character warning (sentence and mark line), and the mark line itself. */
    const val HIDDEN_WARNING = "setup-hidden-warning"
    const val HIDDEN_MARKS = "setup-hidden-marks"
    /** DEB-5013 (FR-5) — the *Use <address>* button. */
    const val FIX = "setup-fix"
    /** DEB-4912 (FR-12) — the way to Apps, drawn only while the TV's home-screen hook is on. */
    const val APPS = "setup-apps"
}

/** R349 (FR-R349-8) — how a saved server URL reads back in the address field: `https://` is what R226 infers, so it
 *  is left out; a typed `http://` stays (it is the one thing R226 cannot infer). */
internal fun addressForEditing(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    return if (trimmed.startsWith("https://", ignoreCase = true)) trimmed.substring("https://".length) else trimmed
}

/** DEB-5002 — the invisible format characters that [Char.isWhitespace] does not cover. */
private const val INVISIBLE_FORMAT_CHARS = "\u200B\u200C\u200D\u2060\uFEFF"

/** DEB-5002 — a *hidden character*: whitespace, or one of the invisible format characters. */
internal fun isHiddenChar(c: Char): Boolean = c.isWhitespace() || c in INVISIBLE_FORMAT_CHARS

/** DEB-5002 — a *line break*: `\r`, `\n`, U+2028 or U+2029. */
internal fun isLineBreakChar(c: Char): Boolean = c == '\r' || c == '\n' || c.code == 0x2028 || c.code == 0x2029

/** FR-1 — the text with hidden characters dropped at both ends, then any trailing `/`. */
private fun withoutHiddenEnds(text: String): String = text.trim(::isHiddenChar).trimEnd('/')

/**
 * DEB-5002 (FR-1, FR-2) — remove invisible characters around an address before
 * checking its scheme. A typed HTTP scheme remains the LAN escape hatch.
 */
internal fun serverUrlFromAddress(text: String): String? {
    val address = withoutHiddenEnds(text)
    if (address.isEmpty()) return null
    val hasScheme = address.startsWith("http://", ignoreCase = true) ||
        address.startsWith("https://", ignoreCase = true)
    return if (hasScheme) address else "https://$address"
}

/** One run of FR-4's mark line: [text] is either part of the address as typed, or the single mark for one hidden character. */
internal data class MarkRun(val text: String, val isMark: Boolean)

/**
 * DEB-5013 (FR-4, FR-5) — what the screen shows under the field when the text still holds a hidden character after FR-1.
 * [lineBreak] picks the sentence; [marks] is the address with every hidden character drawn as a mark (`¶` U+00B6 for a
 * line break, `·` U+00B7 for any other — U+23CE is in none of Ravilo's three fonts); [fixed] is what the fix button puts
 * in the field.
 */
internal data class HiddenAddressWarning(val lineBreak: Boolean, val marks: List<MarkRun>, val fixed: String)

internal fun hiddenAddressWarning(text: String): HiddenAddressWarning? {
    val address = withoutHiddenEnds(text)
    if (address.none(::isHiddenChar)) return null
    val fixed = address.split('\r', '\n', '\u2028', '\u2029')
        .firstOrNull { line -> line.any { !isHiddenChar(it) } }
        ?.filterNot(::isHiddenChar)
        ?.trimEnd('/')
    if (fixed.isNullOrEmpty()) return null
    val runs = mutableListOf<MarkRun>()
    val plain = StringBuilder()
    fun flush() { if (plain.isNotEmpty()) { runs += MarkRun(plain.toString(), false); plain.clear() } }
    var i = 0
    while (i < address.length) {
        val c = address[i]
        when {
            !isHiddenChar(c) -> plain.append(c)
            else -> {
                flush()
                // A Windows CRLF is one line break, so it is one mark.
                if (c == '\r' && address.getOrNull(i + 1) == '\n') i++
                runs += MarkRun(if (isLineBreakChar(c)) "\u00B6" else "\u00B7", true)
            }
        }
        i++
    }
    flush()
    return HiddenAddressWarning(lineBreak = address.any(::isLineBreakChar), marks = runs, fixed = fixed)
}

@Composable
fun ServerSetupScreen(
    onUrlSaved: (String) -> Unit,
    // R340 (FR-R340-3) — one line above the prompt after "Everyone on this TV" (the app that would have shown a
    // toast is gone by then); null otherwise.
    notice: String? = null,
    // R349 (FR-R349-8) — the address this device used until *Wrong server?*, so a one-letter typo is one edit away.
    // Empty on a first run and after *Everyone on this TV* (R340 forgets the server on purpose).
    initialAddress: String = "",
) {
    val colors = RaviloTheme.colors
    var host by remember { mutableStateOf(addressForEditing(initialAddress)) }

    // DEB-5013 (FR-6) — a hidden character inside the address is never connected to silently: while the warning
    // shows there is no URL to connect to, so Connect, OK on it and the keyboard's Go all do nothing.
    val warning = hiddenAddressWarning(host)
    val fullUrl = if (warning == null) serverUrlFromAddress(host) else null
    val canConnect = fullUrl != null

    val connectFR = remember { FocusRequester() }
    val fixFR = remember { FocusRequester() }
    // DEB-4912 (FR-12) — with the TV's home-screen hook on, Apps is one press away from here: Wi-Fi settings and the stock home stay reachable.
    val openApps = dev.jellystructure.ravilo.ui.components.LocalOpenApps.current
    val appsFR = remember { FocusRequester() }
    val addressFR = remember { FocusRequester() }
    val fieldFR = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    // R349 (FR-R349-7) — on a TV the address is focused like a button first and becomes a text field (and opens the
    // keyboard) only on OK, so arriving on this screen shows a focus without throwing the keyboard up.
    var editing by remember { mutableStateOf(false) }
    // DEB-2204 (FR-1) — through rememberFocusVisual like every other ring, so a phone draws none (R298).
    var addressHasFocus by rememberFocusVisual()
    LaunchedEffect(editing) { if (editing) runCatching { fieldFR.requestFocus() } }
    // A visible focus on arrival: the fix button when the address shows the warning (DEB-5013 FR-5: Connect is
    // disabled then), Connect when an address is already there, else the address.
    LaunchedEffect(Unit) {
        runCatching {
            when {
                warning != null -> fixFR.requestFocus()
                canConnect -> connectFR.requestFocus()
                else -> addressFR.requestFocus()
            }
        }
    }
    // DEB-5013 (FR-5) — field → fix → Connect, and Up the same way; without the warning the fix step is not there.
    val belowField = { (if (warning != null) fixFR else connectFR).requestFocus(); Unit }

    // R349 (FR-R349-1) — scrolls inside what the system keyboard leaves; centred as before while it all fits.
    // This screen is drawn outside RaviloApp, so it pads itself by the keyboard (and the system bars).
    KeyboardAwareForm(modifier = Modifier.background(colors.background).safeAreaPadding()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .padding(vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (notice != null) {
                Text(
                    notice,
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.background(colors.surfaceVariant, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Text("Ravilo", color = colors.accent, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text(
                str("setup.server_prompt"),
                color = colors.textSecondary,
                fontSize = 15.sp,
            )

            // URL input — R349 (FR-R349-7): a focusable frame around the field. Focused, it shows a thick focus ring
            // and waits; OK makes the field editable and the system keyboard opens. Leaving the field ends editing.
            val addressShape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp)
            // The warning belongs to the field: 10 dp from it, while the column's own gap is 20 (FR-4's measurements).
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .keepInViewWhileFocused()   // R349 (FR-R349-2)
                    .focusRing(addressHasFocus, addressShape, colors.focusRing, 3.dp)
                    .onFocusChanged { addressHasFocus = it.hasFocus }
                    .testTag(ServerSetupTags.ADDRESS)
                    .dpadFocusable(
                        focusRequester = addressFR,
                        onSelect = { editing = true },
                        onDown = belowField,   // R349 (FR-R349-3); DEB-5013 (FR-5) the fix button first

                    ),
            ) {
            TextField(
                value = host,
                onValueChange = { host = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFR)
                    // TV only: a touch screen or a mouse goes straight into the field, as before (a tap on a field that
                    // can't take focus would do nothing).
                    .focusProperties { canFocus = editing || !isTvPlatform }
                    .reportTextFieldFocus()
                    .testTag(ServerSetupTags.ADDRESS_FIELD)
                    // R349 (FR-R349-3) — a focused text field moves its cursor on Down; with the keyboard closed
                    // Down must reach Connect.
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) { belowField(); true } else false
                    }
                    .onFocusChanged { if (it.isFocused) keyboardController?.show() else if (editing) editing = false },
                singleLine = true,
                label = { Text(str("setup.server_label")) },
                placeholder = { Text("192.168.1.1:8097") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    autoCorrectEnabled = false,   // R348 (FR-R348-4) — an address is not prose
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = { fullUrl?.let(onUrlSaved) },
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceVariant,
                    unfocusedContainerColor = colors.surfaceVariant,
                    focusedTextColor = colors.text,
                    unfocusedTextColor = colors.text,
                    focusedLabelColor = colors.accent,
                    unfocusedLabelColor = colors.textSecondary,
                    focusedIndicatorColor = colors.accent,
                    unfocusedIndicatorColor = colors.accentDim,
                    cursorColor = colors.accent,
                    focusedPlaceholderColor = colors.textSecondary,
                    unfocusedPlaceholderColor = colors.textSecondary,
                ),
            )
            }
            if (warning != null) {
                HiddenAddressNotice(
                    warning = warning,
                    fixFR = fixFR,
                    onUp = { addressFR.requestFocus() },
                    onDown = { connectFR.requestFocus() },
                    onFix = { host = warning.fixed; connectFR.requestFocus() },
                )
            }
            }

            Spacer(Modifier.height(4.dp))

            // Connect button
            var connectFocused by rememberFocusVisual()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        when {
                            connectFocused -> colors.accent
                            canConnect -> colors.accentDim
                            else -> colors.surfaceVariant
                        },
                        RoundedCornerShape(8.dp),
                    )
                    .focusRing(connectFocused, RoundedCornerShape(8.dp), colors.focusRing, 2.dp)
                    .testTag(ServerSetupTags.CONNECT)
                    .dpadFocusable(
                        focusRequester = connectFR,
                        onFocused = { connectFocused = true },
                        onBlurred = { connectFocused = false },
                        onSelect = { fullUrl?.let(onUrlSaved) },
                        onUp = { (if (warning != null) fixFR else addressFR).requestFocus() },   // R349 (FR-R349-3); DEB-5013 (FR-5)
                        onDown = if (openApps != null) ({ appsFR.requestFocus() }) else null,   // DEB-4912 (FR-12)
                    )
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    // R279 — the same string the Tizen receiver's own setup screen draws.
                    str("receiver.setup_connect"),
                    color = if (connectFocused) colors.onAccent else if (canConnect) colors.text else colors.textSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (openApps != null) {
                var appsFocused by rememberFocusVisual()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                        .focusRing(appsFocused, RoundedCornerShape(8.dp), colors.focusRing, 2.dp)
                        .testTag(ServerSetupTags.APPS)
                        .dpadFocusable(
                            focusRequester = appsFR,
                            onFocused = { appsFocused = true },
                            onBlurred = { appsFocused = false },
                            onSelect = openApps,
                            onUp = { connectFR.requestFocus() },
                        )
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(str("nav.apps"), color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * DEB-5013 (FR-4, FR-5) — round DEB-5012's direction A: one column under the field with a warning-colour rail on its
 * left. The sentence, the address on a monospace mark line, then a full-width outlined *Use <address>* button.
 * Measurements are the spec's amendment table (dp; sp for text).
 */
@Composable
private fun HiddenAddressNotice(
    warning: HiddenAddressWarning,
    fixFR: FocusRequester,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onFix: () -> Unit,
) {
    val colors = RaviloTheme.colors
    // The marks' faces. JetBrains Mono is not bundled in the Compose resources (the design mirror's web font only), so
    // this is the platform monospace — the same stand-in the Samsung TV guide's version column uses.
    val mono = FontFamily.Monospace
    val sora = Sora
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag(ServerSetupTags.HIDDEN_WARNING),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.warningRule, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                str(if (warning.lineBreak) "setup.hidden_line_break" else "setup.hidden_space"),
                color = colors.warning,
                fontFamily = sora,
                fontSize = 13.5.sp,
                lineHeight = (13.5 * 1.35).sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            // A long line wraps (Compose breaks an over-long unbroken word between characters): it never clips or scrolls,
            // because either could hide the very mark this line is for, and a D-pad cannot scroll it.
            Text(
                buildAnnotatedString {
                    for (run in warning.marks) {
                        if (run.isMark) {
                            withStyle(SpanStyle(color = colors.warning, fontWeight = FontWeight.Bold, background = colors.warning.copy(alpha = 0.18f))) {
                                append(run.text)
                            }
                        } else append(run.text)
                    }
                },
                color = colors.textSecondary,
                fontFamily = mono,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.2.sp,
                modifier = Modifier.testTag(ServerSetupTags.HIDDEN_MARKS),
            )
            Spacer(Modifier.height(12.dp))
            var fixFocused by rememberFocusVisual()
            val shape = RoundedCornerShape(8.dp)
            val label = str("setup.use_fixed_address", mapOf("address" to warning.fixed))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 42.dp)
                    // Outlined: a 1 dp border in the warning colour at rest, the screen's 2 dp ring (and its colour) focused.
                    .focusRing(fixFocused, shape, colors.focusRing, 2.dp, resting = colors.warningRule, restingWidth = 1.dp)
                    .testTag(ServerSetupTags.FIX)
                    .dpadFocusable(
                        focusRequester = fixFR,
                        onFocused = { fixFocused = true },
                        onBlurred = { fixFocused = false },
                        onSelect = onFix,
                        onUp = onUp,
                        onDown = onDown,
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    addressSetInMono(label, warning.fixed, mono),
                    color = colors.text,
                    fontFamily = sora,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** The address inside a label is set in the mark line's monospace face, 600; the words around it stay in the label's own. */
private fun addressSetInMono(label: String, address: String, mono: FontFamily): AnnotatedString {
    val at = label.indexOf(address)
    if (at < 0) return AnnotatedString(label)
    return buildAnnotatedString {
        append(label.substring(0, at))
        withStyle(SpanStyle(fontFamily = mono, fontWeight = FontWeight.SemiBold)) { append(address) }
        append(label.substring(at + address.length))
    }
}
