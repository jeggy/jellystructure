package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

class ProfilePickerStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is ProfilePickerState.Picking -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Who's watching?", color = colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(40.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        val frs = remember(s.sessions.size) { List(s.sessions.size + 1) { FocusRequester() } }
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
                            focusRequester = frs[s.sessions.size],
                            onLeft  = { if (s.sessions.isNotEmpty()) frs[s.sessions.lastIndex].requestFocus() },
                            onSelect = { store.showAddUser() },
                        )
                    }
                }
            }

            is ProfilePickerState.AddingUser -> {
                // Reuse PairingScreen to add a second user, then cache the resulting token
                val pairingStore = remember {
                    PairingStoreWithCallback(apiClient) { result ->
                        val session = LocalSession(
                            userId = result.session.userId,
                            displayName = result.session.displayName,
                            deviceToken = result.deviceToken,
                            isAdmin = result.session.isAdmin,
                        )
                        MultiTokenStore.add(session)
                        store.cancelAdd()
                    }
                }
                PairingScreen(store = pairingStore.pairing, onPaired = {})
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
            onLeft = onLeft,
            onRight = onRight,
            onSelect = onSelect,
        ),
    ) {
        val initials = session.displayName.split(' ').take(2).joinToString("") { it.firstOrNull()?.uppercase() ?: "" }
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(if (focused) colors.accent else colors.surfaceVariant, CircleShape)
                .then(if (focused) Modifier.border(3.dp, colors.focusRing, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials.ifEmpty { "?" }, color = if (focused) colors.onAccent else colors.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        Text(session.displayName, color = if (focused) colors.text else colors.textSecondary, fontSize = 14.sp)
        if (session.isAdmin) {
            Spacer(Modifier.height(4.dp))
            Text("Admin", color = colors.accent, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AddUserTile(
    focusRequester: FocusRequester,
    onLeft: () -> Unit,
    onSelect: () -> Unit,
) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.dpadFocusable(
            focusRequester = focusRequester,
            onFocused = { focused = true },
            onLeft = onLeft,
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
            Text("+", color = if (focused) colors.text else colors.textSecondary, fontSize = 36.sp, fontWeight = FontWeight.Thin)
        }
        Spacer(Modifier.height(12.dp))
        Text("Add user", color = if (focused) colors.text else colors.textSecondary, fontSize = 14.sp)
    }
}

// ─── PairingStoreWithCallback ─────────────────────────────────────────────────

/** Wraps PairingStore to expose the PairResult on approval. */
class PairingStoreWithCallback(apiClient: TvApiClient, val onApproved: (dev.jellystructure.shared.tv.PairResult) -> Unit) {
    val pairing = PairingStore(apiClient)
}
