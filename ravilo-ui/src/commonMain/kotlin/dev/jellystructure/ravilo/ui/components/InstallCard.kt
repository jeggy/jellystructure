package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.dismissInstallCard
import dev.jellystructure.ravilo.ui.seams.installCardDismissed
import dev.jellystructure.ravilo.ui.seams.isIOSWebPlatform
import dev.jellystructure.ravilo.ui.seams.isWebPlatform
import dev.jellystructure.ravilo.ui.seams.rememberInstallPromptAvailable
import dev.jellystructure.ravilo.ui.seams.rememberIsStandaloneWebApp
import dev.jellystructure.ravilo.ui.seams.triggerNativeInstall
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.ravilo.ui.theme.SpaceGrotesk

private const val INSTALL_CARD_MAX_WIDTH_DP = 900f

/**
 * R263 (FR-R263-8) — "the install is taught, once, where there is no prompt." Never on the TV build
 * or above a 900 dp viewport; never in an already-installed standalone web app; auto-shown at most
 * once per device (dismissal is remembered in `localStorage`) unless [forceShow] re-surfaces it —
 * Settings' own "Install Ravilo" row does that, per the spec's "re-surfaced only from Settings".
 */
@Composable
fun InstallCardIfEligible(forceShow: Boolean = false, onDismiss: () -> Unit = {}) {
    if (!isWebPlatform) return
    if (rememberIsStandaloneWebApp()) return
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val widthDp = remember(windowInfo.containerSize.width, density) { windowInfo.containerSize.width / density.density }
    if (widthDp > INSTALL_CARD_MAX_WIDTH_DP) return
    var dismissed by remember { mutableStateOf(installCardDismissed()) }
    if (dismissed && !forceShow) return

    InstallCard(onDismiss = {
        dismissInstallCard()
        dismissed = true
        onDismiss()
    })
}

@Composable
private fun InstallCard(onDismiss: () -> Unit) {
    val colors = RaviloTheme.colors
    val isIOS = isIOSWebPlatform
    val canPromptInstall = rememberInstallPromptAvailable()
    val dismissFR = remember { FocusRequester() }
    val ctaFR = remember { FocusRequester() }

    Box(
        Modifier.fillMaxWidth().background(colors.surfaceVariant, RoundedCornerShape(12.dp)).padding(20.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⇧", color = colors.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(
                    str("install.title"), color = colors.text, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, fontFamily = SpaceGrotesk, modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .dpadFocusable(focusRequester = dismissFR, onSelect = onDismiss)
                        .padding(6.dp),
                ) {
                    Text("✕", color = colors.textSecondary, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (isIOS) {
                // FR-R263-8 — iOS Safari has no install API at all; these are the only real steps.
                Text(
                    "1. ${str("install.step_share")}   →   2. ${str("install.step_add")}",
                    color = colors.textSecondary, fontSize = 13.sp, fontFamily = Sora,
                )
            } else if (canPromptInstall) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .background(colors.accent, RoundedCornerShape(8.dp))
                        .dpadFocusable(focusRequester = ctaFR, onSelect = { triggerNativeInstall() })
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(str("install.cta"), color = colors.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Sora)
                }
            }
            // FR-R263-8 — an installed web app has its own storage; shown regardless of which branch
            // above rendered, since it's true either way.
            Spacer(Modifier.height(10.dp))
            Text(str("install.signin_again"), color = colors.textSecondary, fontSize = 12.sp, fontFamily = Sora)
        }
    }
}
