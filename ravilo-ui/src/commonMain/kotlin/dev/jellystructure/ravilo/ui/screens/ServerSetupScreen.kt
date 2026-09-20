package dev.jellystructure.ravilo.ui.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme

@Composable
fun ServerSetupScreen(onUrlSaved: (String) -> Unit) {
    val colors = RaviloTheme.colors
    var host by remember { mutableStateOf("") }

    // R226 — a typed scheme always wins outright (the one escape hatch for a plain-HTTP LAN box);
    // otherwise https:// is inferred, since virtually every real deployment sits behind TLS.
    val hasScheme = host.startsWith("http://", ignoreCase = true) || host.startsWith("https://", ignoreCase = true)
    val fullUrl = if (hasScheme) host else "https://$host"
    val canConnect = host.isNotBlank()

    val connectFR = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Box(
        modifier = Modifier.fillMaxSize().background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .padding(vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Ravilo", color = colors.accent, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text(
                str("setup.server_prompt"),
                color = colors.textSecondary,
                fontSize = 15.sp,
            )

            // URL input — focuses native Android TV keyboard on select
            TextField(
                value = host,
                onValueChange = { host = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) keyboardController?.show() },
                singleLine = true,
                label = { Text(str("setup.server_label")) },
                placeholder = { Text("192.168.1.1:8097") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
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

            Spacer(Modifier.height(4.dp))

            // Connect button
            var connectFocused by remember { mutableStateOf(false) }
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
                    .dpadFocusable(
                        focusRequester = connectFR,
                        onFocused = { connectFocused = true },
                        onSelect = { if (canConnect) onUrlSaved(fullUrl) },
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
