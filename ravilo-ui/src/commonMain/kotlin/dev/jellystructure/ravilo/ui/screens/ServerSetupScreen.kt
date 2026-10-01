package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.isTvPlatform
import dev.jellystructure.ravilo.ui.seams.safeAreaPadding
import dev.jellystructure.ravilo.ui.theme.RaviloTheme

/** R349 — test tags for the server-setup form. */
object ServerSetupTags {
    /** The address control as the D-pad sees it (focused without typing). */
    const val ADDRESS = "setup-address"
    /** The text field inside it, focusable only once OK has been pressed on [ADDRESS]. */
    const val ADDRESS_FIELD = "setup-address-field"
    const val CONNECT = "setup-connect"
}

/** R349 (FR-R349-8) — how a saved server URL reads back in the address field: `https://` is what R226 infers, so it
 *  is left out; a typed `http://` stays (it is the one thing R226 cannot infer). */
internal fun addressForEditing(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    return if (trimmed.startsWith("https://", ignoreCase = true)) trimmed.substring("https://".length) else trimmed
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

    // R226 — a typed scheme always wins outright (the one escape hatch for a plain-HTTP LAN box);
    // otherwise https:// is inferred, since virtually every real deployment sits behind TLS.
    val hasScheme = host.startsWith("http://", ignoreCase = true) || host.startsWith("https://", ignoreCase = true)
    val fullUrl = if (hasScheme) host else "https://$host"
    val canConnect = host.isNotBlank()

    val connectFR = remember { FocusRequester() }
    val addressFR = remember { FocusRequester() }
    val fieldFR = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    // R349 (FR-R349-7) — on a TV the address is focused like a button first and becomes a text field (and opens the
    // keyboard) only on OK, so arriving on this screen shows a focus without throwing the keyboard up.
    var editing by remember { mutableStateOf(false) }
    var addressHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(editing) { if (editing) runCatching { fieldFR.requestFocus() } }
    // A visible focus on arrival: Connect when an address is already there, else the address.
    LaunchedEffect(Unit) { runCatching { if (host.isNotBlank()) connectFR.requestFocus() else addressFR.requestFocus() } }

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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .keepInViewWhileFocused()   // R349 (FR-R349-2)
                    .then(if (addressHasFocus) Modifier.border(3.dp, colors.focusRing, addressShape) else Modifier)
                    .onFocusChanged { addressHasFocus = it.hasFocus }
                    .testTag(ServerSetupTags.ADDRESS)
                    .dpadFocusable(
                        focusRequester = addressFR,
                        onSelect = { editing = true },
                        onDown = { connectFR.requestFocus() },   // R349 (FR-R349-3)
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
                    .testTag(ServerSetupTags.ADDRESS_FIELD)
                    // R349 (FR-R349-3) — a focused text field moves its cursor on Down; with the keyboard closed
                    // Down must reach Connect.
                    .onPreviewKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) { connectFR.requestFocus(); true } else false
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
                    onGo = { if (canConnect) onUrlSaved(fullUrl) },
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
                    .then(
                        if (connectFocused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(8.dp))
                        else Modifier
                    )
                    .testTag(ServerSetupTags.CONNECT)
                    .dpadFocusable(
                        focusRequester = connectFR,
                        onFocused = { connectFocused = true },
                        onBlurred = { connectFocused = false },
                        onSelect = { if (canConnect) onUrlSaved(fullUrl) },
                        onUp = { addressFR.requestFocus() },   // R349 (FR-R349-3)
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
        }
    }
}
