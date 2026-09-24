package dev.jellystructure.ravilo.ui.components

import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.isTvPlatform
import dev.jellystructure.ravilo.ui.screens.MultiTokenStore
import dev.jellystructure.ravilo.ui.screens.SignOutConfirmOverlay
import dev.jellystructure.ravilo.ui.screens.UnpairConfirmOverlay
import dev.jellystructure.ravilo.ui.screens.signOutActiveSession
import dev.jellystructure.ravilo.ui.screens.unpairAllSessions
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.ravilo.ui.theme.Sora
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.launch

private val DANGER_RED = Color(0xFFE0393A)

/**
 * R170 — the avatar opens this dropdown instead of the full "Who's watching" grid: My List, Settings,
 * Sign out, Unpair this TV, plus a Switch-profile action in the header. Render-only re-routing of
 * existing destinations/actions (constitution: no new server state) — modeled on the detail screen's
 * modal overlay pattern (one focusable column, Back closes). [apiClient] is needed for the Sign out
 * ([signOutActiveSession]) and Unpair ([unpairAllSessions]) actions — the same logic `SettingsStore`
 * uses, called directly so opening this menu doesn't have to load all of Settings' state.
 */
@Composable
fun ProfileMenu(
    apiClient: TvApiClient,
    onClose: () -> Unit,
    onMyList: () -> Unit,
    onSettings: () -> Unit,
    onSwitchProfile: () -> Unit,
    onAddUser: () -> Unit,
    // R191 — fires after this ONE profile's session is revoked/forgotten; distinct from [onUnpaired],
    // which fires after every profile on the device is gone. The caller decides Login vs
    // ProfilePicker based on whether any session remains locally.
    onSignedOut: () -> Unit,
    onUnpaired: () -> Unit,
    // R234 (FR-R234-1) — phone/web only (gated below on isTvPlatform, never on window size); opens the
    // Your profile screen. Optional so a TV caller need not supply a destination that never shows.
    onYourProfile: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val scope = rememberCoroutineScope()
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var showUnpairConfirm by remember { mutableStateOf(false) }

    if (showSignOutConfirm) {
        SignOutConfirmOverlay(
            displayName = MultiTokenStore.getActive()?.displayName.orEmpty(),
            onCancel = { showSignOutConfirm = false },
            onConfirm = {
                showSignOutConfirm = false
                scope.launch { signOutActiveSession(apiClient); onSignedOut() }
            },
        )
        return
    }

    if (showUnpairConfirm) {
        UnpairConfirmOverlay(
            onCancel = { showUnpairConfirm = false },
            onConfirm = {
                showUnpairConfirm = false
                scope.launch { unpairAllSessions(apiClient); onUnpaired() }
            },
        )
        return
    }

    // R234 — a list-based focus chain (rather than the previous hand-linked FocusRequesters) so an
    // optional row (Your profile, phone/web only) can slot in without every other row's up/down needing
    // to be re-wired by hand. Row order matches the mockup: Switch profile · Your profile · My List ·
    // Settings · Add user · Sign out · Unpair.
    data class MenuItem(val label: String, val onSelect: () -> Unit, val bold: Boolean = false, val danger: Boolean = false)
    val items = buildList {
        add(MenuItem(str("pm.switch"), onSwitchProfile, bold = true))
        if (!isTvPlatform && onYourProfile != null) add(MenuItem(str("pm.your_profile"), onYourProfile))
        add(MenuItem(str("pm.my_list"), onMyList))
        add(MenuItem(str("pm.settings"), onSettings))
        add(MenuItem(str("pm.add_user"), onAddUser))
        add(MenuItem(str("pm.sign_out"), { showSignOutConfirm = true }))
        add(MenuItem(str("pm.unpair"), { showUnpairConfirm = true }, danger = true))
    }
    val focusRequesters = remember(items.size) { items.map { FocusRequester() } }
    LaunchedEffect(Unit) { runCatching { focusRequesters.first().requestFocus() } }

    // A fixed-position scrim + top-right-anchored panel — click-outside isn't modeled (no pointer
    // click-away convention elsewhere in the app); Back closes, matching every other overlay.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.overlay)
            .dpadFocusable(onBack = onClose),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 84.dp, end = 28.dp)
                .width(240.dp)
                .background(colors.surface, RoundedCornerShape(14.dp))
                .padding(8.dp),
        ) {
            items.forEachIndexed { i, item ->
                if (i == 1) Spacer(Modifier.height(4.dp))  // same gap the original layout had after "Switch profile"
                val upIdx = (i - 1 + items.size) % items.size
                val downIdx = (i + 1) % items.size
                ProfileMenuRow(
                    label = item.label,
                    focusRequester = focusRequesters[i],
                    onUp = { focusRequesters[upIdx].requestFocus() },
                    onDown = { focusRequesters[downIdx].requestFocus() },
                    onSelect = item.onSelect,
                    onBack = onClose,
                    bold = item.bold,
                    danger = item.danger,
                )
            }
        }
    }
}

@Composable
private fun ProfileMenuRow(
    label: String,
    focusRequester: FocusRequester,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onSelect: () -> Unit,
    onBack: () -> Unit,
    bold: Boolean = false,
    danger: Boolean = false,
) {
    val colors = RaviloTheme.colors
    var focused by rememberFocusVisual()
    val textColor = if (danger) DANGER_RED else colors.text
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) colors.surfaceVariant else Color.Transparent, RoundedCornerShape(9.dp))
            .then(if (focused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(9.dp)) else Modifier)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onUp = onUp,
                onDown = onDown,
                onSelect = onSelect,
                onBack = onBack,
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = Sora,
        )
    }
}
