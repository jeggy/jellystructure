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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.PairingChallenge
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.datetime.Clock
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
                    Text("Starting…", color = colors.textSecondary, fontSize = 16.sp)
                }
                is PairingState.Waiting -> {
                    Text(
                        "To pair this device, open jellystructure on another device,\ngo to Ravilo → Pair a TV, and enter this code:",
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
                    Text("Waiting for approval…", color = colors.textSecondary, fontSize = 14.sp)
                }
                is PairingState.Approved -> {
                    Text("✓ Signed in as ${s.displayName}", color = colors.badgeWatched, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                is PairingState.Expired -> {
                    Text("Code expired.", color = colors.badgeNew, fontSize = 16.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("Tap any key to try again.", color = colors.textSecondary, fontSize = 14.sp)
                }
                is PairingState.Errored -> {
                    Text("Error: ${s.message}", color = colors.textSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("Tap any key to retry.", color = colors.textSecondary, fontSize = 14.sp)
                }
            }
        }
    }
}
