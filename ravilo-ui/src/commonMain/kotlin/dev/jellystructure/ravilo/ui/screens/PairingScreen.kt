package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.PairingChallenge
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.datetime.Clock
import dev.jellystructure.ravilo.ui.TokenStore
import dev.jellystructure.ravilo.ui.screens.LocalSession
import dev.jellystructure.ravilo.ui.screens.MultiTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class PairingState {
    data object Starting : PairingState()
    data class Waiting(val challenge: PairingChallenge) : PairingState()
    data class Approved(val displayName: String) : PairingState()
    data class Expired(val message: String) : PairingState()
    data class Errored(val message: String) : PairingState()
}

class PairingStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<PairingState>(PairingState.Starting)
    val state: StateFlow<PairingState> = _state.asStateFlow()

    init { startPairing() }

    fun retry() { startPairing() }

    private fun startPairing() {
        _state.value = PairingState.Starting
        scope.launch {
            val challenge = runCatching { apiClient.startPairing() }.getOrElse {
                _state.value = PairingState.Errored(it.message ?: "Failed to start pairing")
                return@launch
            }
            _state.value = PairingState.Waiting(challenge)
            pollForApproval(challenge.pollToken)
        }
    }

    private suspend fun pollForApproval(pollToken: String) {
        // Poll every 2s until approved; handle expiry by checking against expiresAt
        val challenge = (_state.value as? PairingState.Waiting)?.challenge
        while (true) {
            delay(2_000)
            val nowMs = Clock.System.now().toEpochMilliseconds()
            if (challenge != null && nowMs > challenge.expiresAt) {
                _state.value = PairingState.Expired("Code expired — please try again")
                return
            }
            val result = runCatching { apiClient.pollPairing(pollToken) }.getOrElse { null }
            if (result != null) {
                // Persist token so the session survives app restart
                TokenStore.set(result.deviceToken)
                MultiTokenStore.add(LocalSession(
                    userId = result.session.userId,
                    displayName = result.session.displayName,
                    deviceToken = result.deviceToken,
                    isAdmin = result.session.isAdmin,
                    avatarUrl = result.session.avatarUrl,
                ))
                _state.value = PairingState.Approved(result.session.displayName)
                return
            }
            // null = 202 Accepted (still pending) — keep polling
        }
    }
}

@Composable
fun PairingScreen(
    store: PairingStore,
    onPaired: () -> Unit,
    onChangeServer: () -> Unit = {},
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()

    LaunchedEffect(state) {
        if (state is PairingState.Approved) {
            delay(1_500)
            onPaired()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(40.dp)) {
            Text("Ravilo", color = colors.accent, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(48.dp))

            when (val s = state) {
                is PairingState.Starting -> {
                    Text(str("loading"), color = colors.textSecondary, fontSize = 16.sp)
                }
                is PairingState.Waiting -> {
                    Text(
                        str("pair.instructions"),
                        color = colors.textSecondary,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(32.dp))
                    Box(
                        modifier = Modifier
                            .background(colors.surfaceVariant, RoundedCornerShape(16.dp))
                            .border(2.dp, colors.accent, RoundedCornerShape(16.dp))
                            .padding(horizontal = 48.dp, vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = s.challenge.code.chunked(3).joinToString(" "),
                            color = colors.text,
                            fontSize = 52.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 8.sp,
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(str("pair.waiting"), color = colors.textSecondary, fontSize = 14.sp)
                }
                is PairingState.Approved -> {
                    Text("✓ ${str("pair.approved", mapOf("name" to s.displayName))}", color = colors.badgeWatched, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                is PairingState.Expired -> {
                    Text(str("pair.expired"), color = colors.badgeNew, fontSize = 16.sp)
                    Spacer(Modifier.height(24.dp))
                    PairingActionButton(label = "Try again", onSelect = { store.retry() })
                    Spacer(Modifier.height(12.dp))
                    PairingActionButton(label = "Change server", onSelect = onChangeServer)
                }
                is PairingState.Errored -> {
                    Text(str("pair.error", mapOf("message" to s.message)), color = colors.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    PairingActionButton(label = "Retry", onSelect = { store.retry() })
                    Spacer(Modifier.height(12.dp))
                    PairingActionButton(label = "Change server", onSelect = onChangeServer)
                }
            }
        }
    }
}

@Composable
private fun PairingActionButton(label: String, onSelect: () -> Unit) {
    val colors = RaviloTheme.colors
    val fr = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .background(
                if (focused) colors.accent else colors.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .then(if (focused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(8.dp)) else Modifier)
            .dpadFocusable(
                focusRequester = fr,
                onFocused = { focused = true },
                onSelect = onSelect,
            )
            .padding(horizontal = 32.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (focused) colors.onAccent else colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
