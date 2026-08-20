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

    /** R161: a per-user viewer override, resolved server-side (R162) against the admin override then
     *  the global default. Re-localizing the whole UI happens automatically: RaviloApp's live-config
     *  push re-pulls `getConfig()` after any settings write and re-sets `WithLocale(cfg.uiLanguage)`. */
    fun saveUiLanguage(lang: String) {
        val cur = (_state.value as? SettingsState.Loaded)?.config ?: return
        val updated = cur.copy(uiLanguage = lang)
        _state.value = SettingsState.Loaded(updated)
        scope.launch { runCatching { apiClient.putViewerSettings(uiLanguage = lang) } }
    }

    /** R161 — "Unpair this TV". Delegates to [unpairAllSessions] (also used by R170's avatar
     *  ProfileMenu, which unpairs without loading the rest of Settings' state). */
    suspend fun unpairDevice() = unpairAllSessions(apiClient)

    /** R191 — "Sign out" (this profile only). Delegates to [signOutActiveSession]. */
    suspend fun signOutActiveSession() = dev.jellystructure.ravilo.ui.screens.signOutActiveSession(apiClient)
}

/**
 * R161/R170 — revokes every session this device holds (not just the active one), then clears the
 * local store. Distinct from per-session Sign out ([signOutActiveSession]). Best-effort per token —
 * a failed revoke for one session doesn't stop the others; the local store is cleared regardless so
 * the device always ends up back at the pairing gate.
 */
suspend fun unpairAllSessions(apiClient: TvApiClient) {
    val sessions = MultiTokenStore.getAll()
    for (session in sessions) {
        runCatching { apiClient.unpair(session.deviceToken) }
    }
    MultiTokenStore.clear()
    sessions.forEach { PlaybackPrefsStore.clearProfile(it.userId) }   // R181 — local playback memory
}

/**
 * R191 — signs out ONLY the currently active profile: best-effort revokes its session server-side
 * (`TvApiClient.signOutSession`) and forgets it locally, leaving every other cached profile on this
 * device untouched — unlike [unpairAllSessions], which tears down every profile. The revoke is
 * best-effort (mirroring [unpairAllSessions]'s own `runCatching`): a network failure never blocks
 * the local sign-out, it just leaves a stale token to be cleaned up later (e.g. by the Phase 143
 * admin console). Returns whether any other profile is still cached locally, so the caller can
 * route to the profile picker instead of all the way back to the login gate.
 */
suspend fun signOutActiveSession(apiClient: TvApiClient): Boolean {
    val active = MultiTokenStore.getActive() ?: return MultiTokenStore.getAll().isNotEmpty()
    runCatching { apiClient.signOutSession(active.userId) }
    MultiTokenStore.remove(active.userId)
    PlaybackPrefsStore.clearProfile(active.userId)   // R181 — local playback memory doesn't outlive the profile
    return MultiTokenStore.getAll().isNotEmpty()
}

@Composable
fun SettingsScreen(
    store: SettingsStore,
    displayName: String,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    onSkinChange: (Skin) -> Unit = {},
    // R161: fires after the device's sessions are actually revoked (store.unpairDevice() has
    // completed) — the caller navigates to the pairing gate, mirroring onSignOut's role.
    onUnpair: () -> Unit = {},
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var showUnpairConfirm by remember { mutableStateOf(false) }
    // Bug fix: Sign out used to fire immediately on Select, no confirmation at all — unlike Unpair,
    // even though "Sign out" here means the same thing (the string is literally "Sign out / Unpair")
    // and needs the Jellyfin username+password to recover. Confirmed live on soveværelse TV: an
    // ordinary D-pad navigation slip signed the device out with zero warning. Same confirm-overlay
    // treatment as Unpair now applies.
    var showSignOutConfirm by remember { mutableStateOf(false) }

    // R33: silently re-pull settings when the user's config changes elsewhere.
    val live = dev.jellystructure.ravilo.ui.LocalLiveConfig.current
    LaunchedEffect(live) { live?.collect { store.refresh(silent = true) } }

    // Bug fix: this "‹ Back" label had no dpadFocusable/onClick at all — a purely decorative dead
    // affordance on every input platform. Also gives Loading/Error something to focus (previously
    // nothing was focused until SettingsContent's progressFR, once Loaded).
    val backFR = remember { FocusRequester() }
    var backFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { backFR.requestFocus() } }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 80.dp, vertical = 40.dp)) {
            Text(
                "‹ ${str("action.back")}",
                color = if (backFocused) colors.text else colors.textSecondary,
                fontSize = 13.sp,
                modifier = Modifier
                    .then(if (backFocused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(6.dp)) else Modifier)
                    .dpadFocusable(
                        focusRequester = backFR,
                        onFocused = { backFocused = true }, onBlurred = { backFocused = false },
                        onSelect = onBack,
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
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
                    onSignOut = { showSignOutConfirm = true },
                    onSkinChange = onSkinChange,
                    onUnpairRequest = { showUnpairConfirm = true },
                )
            }
        }
        // R161: 2-D nav is "steps out one level" for Back/Esc — the confirm overlay owns input while
        // shown (dpadFocusable's onBack on Cancel closes it) and defaults focus to the non-destructive
        // Cancel choice.
        if (showSignOutConfirm) {
            ConfirmOverlay(
                title = str("settings.sign_out_confirm", mapOf("name" to displayName)),
                description = str("settings.sign_out_desc", mapOf("name" to displayName)),
                confirmLabel = str("settings.sign_out_yes"),
                onCancel = { showSignOutConfirm = false },
                onConfirm = {
                    showSignOutConfirm = false
                    // R191 — revoke + forget only this profile before navigating (mirrors onUnpair's
                    // "store.unpairDevice() then onUnpair()" shape); onSignOut decides Login vs
                    // ProfilePicker based on whether any session remains.
                    scope.launch { store.signOutActiveSession(); onSignOut() }
                },
            )
        }
        if (showUnpairConfirm) {
            ConfirmOverlay(
                title = str("settings.unpair_confirm"),
                description = str("settings.unpair_desc"),
                confirmLabel = str("settings.unpair_yes"),
                onCancel = { showUnpairConfirm = false },
                onConfirm = {
                    showUnpairConfirm = false
                    scope.launch { store.unpairDevice(); onUnpair() }
                },
            )
        }
    }
}

/**
 * Generic danger-confirm overlay — extracted so Sign out gets the same "are you sure" treatment as
 * Unpair (it used to fire immediately on Select with zero confirmation; confirmed live on
 * soveværelse TV that an ordinary D-pad navigation slip can sign the device out with no warning).
 */
@Composable
fun ConfirmOverlay(title: String, description: String, confirmLabel: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    val colors = RaviloTheme.colors
    val cancelFR = remember { FocusRequester() }
    val confirmFR = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFR.requestFocus() } }
    Box(
        modifier = Modifier.fillMaxSize().background(colors.overlay),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(colors.surface, RoundedCornerShape(14.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(description, color = colors.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                var cancelFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                        .then(if (cancelFocused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(8.dp)) else Modifier)
                        .dpadFocusable(
                            focusRequester = cancelFR,
                            onFocused = { cancelFocused = true },
                            onBlurred = { cancelFocused = false },
                            onRight = { runCatching { confirmFR.requestFocus() } },
                            onSelect = onCancel,
                            onBack = onCancel,
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) { Text(str("action.cancel"), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                var confirmFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .background(Color(0xFFE0393A), RoundedCornerShape(8.dp))
                        .then(if (confirmFocused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(8.dp)) else Modifier)
                        .dpadFocusable(
                            focusRequester = confirmFR,
                            onFocused = { confirmFocused = true },
                            onBlurred = { confirmFocused = false },
                            onLeft = { runCatching { cancelFR.requestFocus() } },
                            onSelect = onConfirm,
                            onBack = onCancel,
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) { Text(confirmLabel, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Non-private: reused by R170's avatar ProfileMenu (a different file), not just this screen. */
@Composable
fun UnpairConfirmOverlay(onCancel: () -> Unit, onConfirm: () -> Unit) {
    ConfirmOverlay(
        title = str("settings.unpair_confirm"),
        description = str("settings.unpair_desc"),
        confirmLabel = str("settings.unpair_yes"),
        onCancel = onCancel,
        onConfirm = onConfirm,
    )
}

/** R191 — non-private: reused by R170's avatar ProfileMenu, same shape as [UnpairConfirmOverlay]
 *  but for the single-profile "Sign out" action; names the profile being signed out. */
@Composable
fun SignOutConfirmOverlay(displayName: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    ConfirmOverlay(
        title = str("settings.sign_out_confirm", mapOf("name" to displayName)),
        description = str("settings.sign_out_desc", mapOf("name" to displayName)),
        confirmLabel = str("settings.sign_out_yes"),
        onCancel = onCancel,
        onConfirm = onConfirm,
    )
}

// R161 — endonyms, not translated (a language picker names languages in themselves regardless of
// the currently active UI language, same convention the admin editor's language list already uses).
private val UI_LANGUAGES = listOf("en" to "English", "da" to "Dansk", "fo" to "Føroyskt")

@Composable
private fun SettingsContent(
    config: RaviloConfig,
    displayName: String,
    store: SettingsStore,
    onSignOut: () -> Unit,
    onSkinChange: (Skin) -> Unit,
    onUnpairRequest: () -> Unit,
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

    // R161: interface language — a per-user viewer override (server-owned, R162), never written back
    // to the Jellystructure profile itself. Endonyms, current highlighted, left/right within the row.
    SectionHeader(str("settings.language"))
    Spacer(Modifier.height(12.dp))
    val langFRs = remember { UI_LANGUAGES.map { FocusRequester() } }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        UI_LANGUAGES.forEachIndexed { i, (code, label) ->
            val isActive = config.uiLanguage == code
            var focused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .background(if (isActive) colors.accent else colors.surfaceVariant, RoundedCornerShape(10.dp))
                    .then(
                        if (focused && !isActive) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(10.dp))
                        else Modifier
                    )
                    .dpadFocusable(
                        focusRequester = langFRs[i],
                        onFocused = { focused = true },
                        onLeft  = { if (i > 0) langFRs[i - 1].requestFocus() },
                        onRight = { if (i < UI_LANGUAGES.lastIndex) langFRs[i + 1].requestFocus() },
                        onSelect = { store.saveUiLanguage(code) },
                    )
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isActive) colors.onAccent else if (focused) colors.text else colors.textSecondary,
                    fontSize = 14.sp,
                    fontWeight = if (isActive || focused) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
    Spacer(Modifier.height(32.dp))

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

    Spacer(Modifier.height(32.dp))

    // R161 — "Unpair this TV": a device-scoped action distinct from the per-session Sign out above
    // (revokes every session this device holds). Danger-styled; requires the confirm overlay.
    SectionHeader(str("settings.unpair").uppercase())
    Spacer(Modifier.height(12.dp))
    var unpairFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .background(Color(0xFFE0393A).copy(alpha = 0.14f), RoundedCornerShape(8.dp))
            .then(if (unpairFocused) Modifier.border(2.dp, Color(0xFFE0393A), RoundedCornerShape(8.dp)) else Modifier)
            .dpadFocusable(
                onFocused = { unpairFocused = true },
                onBlurred = { unpairFocused = false },
                onSelect = onUnpairRequest,
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Column {
            Text(str("settings.unpair"), color = Color(0xFFE0393A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(str("settings.unpair_desc"), color = colors.textSecondary, fontSize = 12.sp)
        }
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
