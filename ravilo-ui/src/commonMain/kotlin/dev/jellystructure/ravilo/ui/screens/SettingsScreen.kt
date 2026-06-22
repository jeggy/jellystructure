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
import dev.jellystructure.shared.tv.RaviloConfig
import dev.jellystructure.shared.tv.Skin
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class SettingsState {
    data object Loading : SettingsState()
    data class Loaded(val config: RaviloConfig) : SettingsState()
    data class Error(val message: String) : SettingsState()
}

class SettingsStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<SettingsState>(SettingsState.Loading)
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init { load() }

    private fun load() {
        _state.value = SettingsState.Loading
        scope.launch {
            _state.value = runCatching { SettingsState.Loaded(apiClient.getConfig()) }
                .getOrElse { SettingsState.Error(it.message ?: "Failed to load settings") }
        }
    }

    /** R33 live refresh: re-pull config in place (no Loading flash) when the layout changes elsewhere. */
    fun refresh(silent: Boolean = true) {
        if (!silent) { load(); return }
        scope.launch {
            runCatching { apiClient.getConfig() }.getOrNull()?.let { _state.value = SettingsState.Loaded(it) }
        }
    }

    fun saveSkin(skin: Skin) {
        val cur = (_state.value as? SettingsState.Loaded)?.config ?: return
        val updated = cur.copy(viewerSkinOverride = skin)
        _state.value = SettingsState.Loaded(updated)
        scope.launch { runCatching { apiClient.putViewerSettings(skin = skin) } }
    }

    fun saveShowContinueProgress(v: Boolean) {
        val cur = (_state.value as? SettingsState.Loaded)?.config ?: return
        val updated = cur.copy(showContinueProgress = v)
        _state.value = SettingsState.Loaded(updated)
        scope.launch { runCatching { apiClient.putViewerSettings(showContinueProgress = v) } }
    }

    fun saveAutoplayNext(v: Boolean) {
        val cur = (_state.value as? SettingsState.Loaded)?.config ?: return
        val updated = cur.copy(autoplayNext = v)
        _state.value = SettingsState.Loaded(updated)
        scope.launch { runCatching { apiClient.putViewerSettings(autoplayNext = v) } }
    }
}

@Composable
fun SettingsScreen(
    store: SettingsStore,
    displayName: String,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    onSkinChange: (Skin) -> Unit = {},
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()

    // R33: silently re-pull settings when the user's config changes elsewhere.
    val live = dev.jellystructure.ravilo.ui.LocalLiveConfig.current
    LaunchedEffect(live) { live?.collect { store.refresh(silent = true) } }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 80.dp, vertical = 40.dp)) {
            Text("‹ ${str("action.back")}", color = colors.textSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text(str("nav.settings"), color = colors.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(40.dp))

            when (val s = state) {
                is SettingsState.Loading -> Text(str("loading"), color = colors.textSecondary, fontSize = 16.sp)
                is SettingsState.Error   -> Text(s.message, color = colors.textSecondary, fontSize = 14.sp)
                is SettingsState.Loaded  -> SettingsContent(
                    config = s.config,
                    displayName = displayName,
                    store = store,
                    onSignOut = onSignOut,
                    onSkinChange = onSkinChange,
                )
            }
        }
    }
}

@Composable
private fun SettingsContent(
    config: RaviloConfig,
    displayName: String,
    store: SettingsStore,
    onSignOut: () -> Unit,
    onSkinChange: (Skin) -> Unit,
) {
    val colors = RaviloTheme.colors

    // Skin section
    if (config.allowSkinOverride) {
        SectionHeader(str("settings.appearance"))
        Spacer(Modifier.height(12.dp))
        val skinFRs = remember { Skin.entries.map { FocusRequester() } }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Skin.entries.forEachIndexed { i, skin ->
                val isActive = config.effectiveSkin() == skin
                var focused by remember { mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .background(
                            if (isActive) colors.accent else colors.surfaceVariant,
                            RoundedCornerShape(10.dp),
                        )
                        .then(
                            if (focused && !isActive) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(10.dp))
                            else Modifier
                        )
                        .dpadFocusable(
                            focusRequester = skinFRs[i],
                            onFocused = { focused = true },
                            onLeft  = { if (i > 0) skinFRs[i - 1].requestFocus() },
                            onRight = { if (i < Skin.entries.lastIndex) skinFRs[i + 1].requestFocus() },
                            onSelect = { store.saveSkin(skin); onSkinChange(skin) },
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = skin.name.lowercase().replaceFirstChar { it.uppercaseChar() },
                        color = if (isActive) colors.onAccent else if (focused) colors.text else colors.textSecondary,
                        fontSize = 14.sp,
                        fontWeight = if (isActive || focused) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    // Playback prefs
    SectionHeader(str("settings.playback"))
    Spacer(Modifier.height(12.dp))
    val progressFR = remember { FocusRequester() }
    ToggleRow(
        label = str("settings.show_progress"),
        checked = config.showContinueProgress,
        focusRequester = progressFR,
        onToggle = { store.saveShowContinueProgress(!config.showContinueProgress) },
    )
    Spacer(Modifier.height(12.dp))
    ToggleRow(
        label = str("settings.autoplay_next"),
        checked = config.autoplayNext,
        focusRequester = remember { FocusRequester() },
        onToggle = { store.saveAutoplayNext(!config.autoplayNext) },
    )
    // Land focus on a stable control on entry; up/down reach skin and sign-out.
    LaunchedEffect(Unit) { runCatching { progressFR.requestFocus() } }

    Spacer(Modifier.height(32.dp))

    // Account
    SectionHeader(str("settings.account"))
    Spacer(Modifier.height(12.dp))
    Text(str("profile.signed_in", mapOf("name" to displayName)), color = colors.textSecondary, fontSize = 15.sp)
    Spacer(Modifier.height(16.dp))
    val signOutFR = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
            .then(if (focused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(8.dp)) else Modifier)
            .dpadFocusable(
                focusRequester = signOutFR,
                onFocused = { focused = true },
                onSelect = onSignOut,
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(str("profile.sign_out"), color = colors.text, fontSize = 14.sp)
    }
}

@Composable
private fun SectionHeader(title: String) {
    val colors = RaviloTheme.colors
    Text(title, color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    focusRequester: FocusRequester,
    onToggle: () -> Unit,
) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant, RoundedCornerShape(10.dp))
            .then(if (focused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(10.dp)) else Modifier)
            .dpadFocusable(focusRequester = focusRequester, onFocused = { focused = true }, onSelect = onToggle)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = if (focused) colors.text else colors.textSecondary, fontSize = 15.sp)
        Box(
            modifier = Modifier
                .background(if (checked) colors.accent else colors.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(if (checked) str("on") else str("off"), color = if (checked) colors.onAccent else colors.textSecondary, fontSize = 12.sp)
        }
    }
}
