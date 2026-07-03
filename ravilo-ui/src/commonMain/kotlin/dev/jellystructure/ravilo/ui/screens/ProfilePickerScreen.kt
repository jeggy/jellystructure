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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import dev.jellystructure.ravilo.ui.seams.RemoteImage
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ─── Multi-user session model ─────────────────────────────────────────────────

/**
 * A locally-cached user session: the device token + display info.
 * The token is persisted via [MultiTokenStore]; only the active token is passed to TvApiClient.
 */
data class LocalSession(
    val userId: String,
    val displayName: String,
    val deviceToken: String,
    val isAdmin: Boolean,
    val isKids: Boolean = false,
    val avatarUrl: String? = null,
)

/** Platform-specific: persists multiple device tokens per device. */
expect object MultiTokenStore {
    fun getAll(): List<LocalSession>
    fun add(session: LocalSession)
    fun remove(userId: String)
    fun getActive(): LocalSession?
    fun setActive(userId: String)
    fun clear()
}

// ─── ProfilePickerStore ───────────────────────────────────────────────────────

sealed class ProfilePickerState {
    data class Picking(val sessions: List<LocalSession>) : ProfilePickerState()
    data object AddingUser : ProfilePickerState()
}

class ProfilePickerStore {
    private val _state = MutableStateFlow<ProfilePickerState>(
        ProfilePickerState.Picking(MultiTokenStore.getAll())
    )
    val state: StateFlow<ProfilePickerState> = _state.asStateFlow()

    fun selectProfile(session: LocalSession) {
        MultiTokenStore.setActive(session.userId)
    }

    fun showAddUser() { _state.value = ProfilePickerState.AddingUser }
    fun cancelAdd() { _state.value = ProfilePickerState.Picking(MultiTokenStore.getAll()) }

    fun removeSession(userId: String) {
        MultiTokenStore.remove(userId)
        _state.value = ProfilePickerState.Picking(MultiTokenStore.getAll())
    }
}

// ─── ProfilePickerScreen ──────────────────────────────────────────────────────

@Composable
fun ProfilePickerScreen(
    store: ProfilePickerStore,
    apiClient: TvApiClient,
    onProfileSelected: (LocalSession) -> Unit,
    onSettings: () -> Unit = {},
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is ProfilePickerState.Picking -> {
                // Settings is only offered once a user is active (it needs the active token);
                // at a cold-start gate with no active session it is hidden.
                val showSettings = remember(s.sessions) { MultiTokenStore.getActive() != null }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(str("profile.who"), color = colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(40.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        val extras = if (showSettings) 2 else 1   // Add user (+ Settings)
                        val frs = remember(s.sessions.size, showSettings) {
                            List(s.sessions.size + extras) { FocusRequester() }
                        }
                        val addIdx = s.sessions.size
                        val settingsIdx = addIdx + 1
                        LaunchedEffect(Unit) { runCatching { frs.firstOrNull()?.requestFocus() } }

                        s.sessions.forEachIndexed { i, session ->
                            ProfileTile(
                                session = session,
                                focusRequester = frs[i],
                                onLeft  = { if (i > 0) frs[i - 1].requestFocus() },
                                onRight = { frs[i + 1].requestFocus() },
                                onSelect = { store.selectProfile(session); onProfileSelected(session) },
                            )
                        }

                        // "Add user" tile
                        AddUserTile(
                            focusRequester = frs[addIdx],
                            onLeft  = { if (s.sessions.isNotEmpty()) frs[s.sessions.lastIndex].requestFocus() },
                            onRight = { if (showSettings) frs[settingsIdx].requestFocus() },
                            onSelect = { store.showAddUser() },
                        )

                        // "Settings" tile (only with an active session)
                        if (showSettings) {
                            ActionTile(
                                glyph = "⚙",
                                label = str("nav.settings"),
                                focusRequester = frs[settingsIdx],
                                onLeft  = { frs[addIdx].requestFocus() },
                                onSelect = onSettings,
                            )
                        }
                    }
                }
            }

            is ProfilePickerState.AddingUser -> {
                // Reuse PairingScreen to add a user; PairingStore caches the token via
                // MultiTokenStore, then we return to the picker (now showing the new user).
                val pairingStore = remember { PairingStore(apiClient) }
                val cancelFR = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { cancelFR.requestFocus() } }
                Box(Modifier.fillMaxSize()) {
                    PairingScreen(store = pairingStore, onPaired = { store.cancelAdd() })
                    // Always-focusable cancel so the flow is escapable (incl. Back at a cold-start gate)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 48.dp)
                            .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                            .dpadFocusable(
                                focusRequester = cancelFR,
                                onSelect = { store.cancelAdd() },
                                onBack = { store.cancelAdd() },
                            )
                            .padding(horizontal = 28.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(str("profile.cancel"), color = colors.textSecondary, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileTile(
    session: LocalSession,
    focusRequester: FocusRequester,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onSelect: () -> Unit,
) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.dpadFocusable(
            focusRequester = focusRequester,
            onFocused = { focused = true },
            onBlurred = { focused = false },
            onLeft = onLeft,
            onRight = onRight,
            onSelect = onSelect,
        ),
    ) {
        val initials = session.displayName.split(' ').take(2).joinToString("") { it.firstOrNull()?.uppercase() ?: "" }
        val avatarUrl = session.avatarUrl
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(if (focused) colors.accent else colors.surfaceVariant, CircleShape)
                .then(if (focused) Modifier.border(3.dp, colors.focusRing, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl != null) {
                RemoteImage(avatarUrl, session.displayName, Modifier.fillMaxWidth().clip(CircleShape))
            } else {
                Text(initials.ifEmpty { "?" }, color = if (focused) colors.onAccent else colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(session.displayName, color = if (focused) colors.text else colors.textSecondary, fontSize = 14.sp)
        if (session.isAdmin) {
            Spacer(Modifier.height(4.dp))
            Text(str("profile.admin"), color = colors.accent, fontSize = 11.sp)
        } else if (session.isKids) {
            Spacer(Modifier.height(4.dp))
            Text(str("profile.kids"), color = colors.accent, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AddUserTile(
    focusRequester: FocusRequester,
    onLeft: () -> Unit,
    onSelect: () -> Unit,
    onRight: () -> Unit = {},
) = ActionTile(
    glyph = "+",
    label = str("profile.add_user"),
    focusRequester = focusRequester,
    onLeft = onLeft,
    onRight = onRight,
    onSelect = onSelect,
)

@Composable
private fun ActionTile(
    glyph: String,
    label: String,
    focusRequester: FocusRequester,
    onLeft: () -> Unit,
    onSelect: () -> Unit,
    onRight: () -> Unit = {},
) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.dpadFocusable(
            focusRequester = focusRequester,
            onFocused = { focused = true },
            onBlurred = { focused = false },
            onLeft = onLeft,
            onRight = onRight,
            onSelect = onSelect,
        ),
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(colors.surfaceVariant, CircleShape)
                .then(if (focused) Modifier.border(3.dp, colors.focusRing, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = if (focused) colors.text else colors.textSecondary, fontSize = 36.sp, fontWeight = FontWeight.Thin)
        }
        Spacer(Modifier.height(12.dp))
        Text(label, color = if (focused) colors.text else colors.textSecondary, fontSize = 14.sp)
    }
}
