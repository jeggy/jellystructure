package dev.jellystructure.ravilo.ui.components

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

    val switchFR = remember { FocusRequester() }
    val myListFR = remember { FocusRequester() }
    val settingsFR = remember { FocusRequester() }
    val addUserFR = remember { FocusRequester() }
    val signOutFR = remember { FocusRequester() }
    val unpairFR = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { switchFR.requestFocus() } }

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
            ProfileMenuRow(
                label = str("pm.switch"),
                focusRequester = switchFR,
                onUp = { unpairFR.requestFocus() },
                onDown = { myListFR.requestFocus() },
                onSelect = onSwitchProfile,
                onBack = onClose,
                bold = true,
            )
            Spacer(Modifier.height(4.dp))
            ProfileMenuRow(
                label = str("pm.my_list"),
                focusRequester = myListFR,
                onUp = { switchFR.requestFocus() },
                onDown = { settingsFR.requestFocus() },
                onSelect = onMyList,
                onBack = onClose,
            )
            ProfileMenuRow(
                label = str("pm.settings"),
                focusRequester = settingsFR,
                onUp = { myListFR.requestFocus() },
                onDown = { addUserFR.requestFocus() },
                onSelect = onSettings,
                onBack = onClose,
            )
            ProfileMenuRow(
                label = str("pm.add_user"),
                focusRequester = addUserFR,
                onUp = { settingsFR.requestFocus() },
                onDown = { signOutFR.requestFocus() },
                onSelect = onAddUser,
                onBack = onClose,
            )
            ProfileMenuRow(
                label = str("pm.sign_out"),
                focusRequester = signOutFR,
                onUp = { addUserFR.requestFocus() },
                onDown = { unpairFR.requestFocus() },
                onSelect = { showSignOutConfirm = true },
                onBack = onClose,
            )
            ProfileMenuRow(
                label = str("pm.unpair"),
                focusRequester = unpairFR,
                onUp = { signOutFR.requestFocus() },
                onDown = { switchFR.requestFocus() },
                onSelect = { showUnpairConfirm = true },
                onBack = onClose,
                danger = true,
            )
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
    var focused by remember { mutableStateOf(false) }
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
