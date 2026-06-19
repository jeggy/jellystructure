package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.theme.RaviloTheme

@Composable
fun ServerSetupScreen(onUrlSaved: (String) -> Unit) {
    val colors = RaviloTheme.colors
    var useHttps by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }

    val scheme = if (useHttps) "https://" else "http://"
    val hasScheme = host.startsWith("http://") || host.startsWith("https://")
    val fullUrl = if (hasScheme) host else "$scheme$host"
    val canConnect = fullUrl.startsWith("http://") || fullUrl.startsWith("https://")

    val httpFR = remember { FocusRequester() }
    val httpsFR = remember { FocusRequester() }
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
                "Enter your jellystructure server address",
                color = colors.textSecondary,
                fontSize = 15.sp,
            )

            // Scheme selector
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SchemeButton(
                    label = "http://",
                    isActive = !useHttps,
                    focusRequester = httpFR,
                    onRight = { httpsFR.requestFocus() },
                    onSelect = { useHttps = false },
                )
                SchemeButton(
                    label = "https://",
                    isActive = useHttps,
                    focusRequester = httpsFR,
                    onLeft = { httpFR.requestFocus() },
                    onSelect = { useHttps = true },
                )
            }

            // URL input — focuses native Android TV keyboard on select
            TextField(
                value = host,
                onValueChange = { host = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) keyboardController?.show() },
                singleLine = true,
                label = { Text("Server address") },
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
                    "Connect",
                    color = if (connectFocused) colors.onAccent else if (canConnect) colors.text else colors.textSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SchemeButton(
    label: String,
    isActive: Boolean,
    focusRequester: FocusRequester,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    onSelect: () -> Unit,
) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .background(if (isActive) colors.accent else colors.surfaceVariant, RoundedCornerShape(8.dp))
            .then(
                if (focused && !isActive) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(8.dp))
                else Modifier
            )
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onLeft = onLeft,
                onRight = onRight,
                onSelect = onSelect,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (isActive) colors.onAccent else if (focused) colors.text else colors.textSecondary,
            fontSize = 14.sp,
            fontWeight = if (isActive || focused) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
