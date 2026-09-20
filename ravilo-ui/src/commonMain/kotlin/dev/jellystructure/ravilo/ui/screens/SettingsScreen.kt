package dev.jellystructure.ravilo.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.jellystructure.ravilo.ui.components.LoadErrorState
import dev.jellystructure.ravilo.ui.components.LoadErrorKind
import dev.jellystructure.ravilo.ui.components.loadErrorKindOf
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.i18n.LastLanguage
import dev.jellystructure.ravilo.i18n.SUPPORTED_LANGUAGES
import dev.jellystructure.ravilo.ui.components.InstallCardIfEligible
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.isWebPlatform
import dev.jellystructure.ravilo.ui.theme.LocalCompact
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
    data class Error(val message: String, val kind: LoadErrorKind = LoadErrorKind.GENERIC) : SettingsState()
}

class SettingsStore(private val apiClient: TvApiClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<SettingsState>(SettingsState.Loading)
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init { load() }

    // R280 (FR-R280-3) — the error surface offers Retry, so the load has to be reachable.
    fun load() {
        _state.value = SettingsState.Loading
        scope.launch {
            _state.value = runCatching { SettingsState.Loaded(apiClient.getConfig()) }
                .getOrElse { SettingsState.Error(it.message ?: "", loadErrorKindOf(it)) }
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
        // R279 — remembered device-wide the moment it is chosen, not only when the config push comes
        // back: choosing a language and then signing out must not drop the screen back to English.
        LastLanguage.remember(lang)
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
    sessions.forEach {
        PlaybackPrefsStore.clearProfile(it.userId)   // R181 — local playback memory
        HomeSnapshotCache.clear(it.userId)           // R212 — cached Home snapshot
    }
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
    HomeSnapshotCache.clear(active.userId)           // R212 — cached Home snapshot doesn't outlive the profile either
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
    // R234 (FR-R234-4) — every platform (TV included); opens the dedicated Change password screen.
    onChangePassword: () -> Unit = {},
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
    // R263 (FR-R263-8) — the only way back to the install card once dismissed.
    var showInstallCard by remember { mutableStateOf(false) }

    // R33: silently re-pull settings when the user's config changes elsewhere.
    val live = dev.jellystructure.ravilo.ui.LocalLiveConfig.current
    LaunchedEffect(live) { live?.collect { store.refresh(silent = true) } }

    // Bug fix: this "‹ Back" label had no dpadFocusable/onClick at all — a purely decorative dead
    // affordance on every input platform. Also gives Loading/Error something to focus (previously
    // nothing was focused until SettingsContent's progressFR, once Loaded).
    val backFR = remember { FocusRequester() }
    var backFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { backFR.requestFocus() } }

    // R229 bug fix: 80dp/side was sized for a TV's 10-foot canvas and never adapted for a phone
    // window — on a ~360-400dp-wide handset it left ~200dp for content, which is what pushed the
    // Appearance/Language pill rows and the toggle pills into the squeezed/character-wrapped state
    // reported live. LocalCompact (< 600dp width, R145) already exists for exactly this.
    val compact = LocalCompact.current
    // Bug fix (found via live TV testing) — this Column had no scroll at all: on a display short
    // enough that Playback is the last visible section, the whole Account block (identity, Change
    // password, Sign out) and the Unpair section below it were composed but permanently off-screen
    // with no way to reveal them — not a D-pad dead-end alone (see the explicit onUp/onDown chain
    // added below), but a hard requirement for it, since even correct focus navigation can't show
    // what never scrolls into view.
    val scrollState = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = if (compact) 20.dp else 80.dp, vertical = 40.dp),
        ) {
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
                is SettingsState.Error   -> LoadErrorState(s.kind, onRetry = { store.load() }, onBack = onBack)
                is SettingsState.Loaded  -> SettingsContent(
                    config = s.config,
                    displayName = displayName,
                    store = store,
                    onSignOut = { showSignOutConfirm = true },
                    onSkinChange = onSkinChange,
                    onUnpairRequest = { showUnpairConfirm = true },
                    onChangePassword = onChangePassword,
                    onInstallRavilo = { showInstallCard = true },
                )
            }
        }
        if (showInstallCard) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
                    .dpadFocusable(onBack = { showInstallCard = false }),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.padding(40.dp).widthIn(max = 480.dp)) {
                    InstallCardIfEligible(forceShow = true, onDismiss = { showInstallCard = false })
                }
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
// R279 — the list is no longer written here. It is whatever `i18n/*.json` declares, so dropping an
// `es.json` in makes Spanish appear on this screen with no Kotlin edit.
private val UI_LANGUAGES: List<Pair<String, String>>
    get() = SUPPORTED_LANGUAGES.map { it.code to it.name }

@Composable
private fun SettingsContent(
    config: RaviloConfig,
    displayName: String,
    store: SettingsStore,
    onSignOut: () -> Unit,
    onSkinChange: (Skin) -> Unit,
    onUnpairRequest: () -> Unit,
    onChangePassword: () -> Unit = {},
    // R263 (FR-R263-8) — "re-surfaced only from Settings → Install Ravilo"; absent entirely off the
    // web (isWebPlatform), never a greyed/inert row.
    onInstallRavilo: () -> Unit = {},
) {
    val colors = RaviloTheme.colors

    // Bug fix (found via live TV testing) — none of this screen's sections wired an explicit
    // onUp/onDown chain; default Compose focus search never reliably crossed section boundaries once
    // the outer Column had no scroll to reveal what it jumped to (see SettingsScreen's own fix note),
    // leaving Account (identity, Change password, Sign out) and the whole Unpair section unreachable
    // by D-pad. Every section below now names its entry-point FocusRequester up front and links
    // explicitly to its neighbours, the same explicit-chain idiom ProfileMenu already uses.
    val skinFRs = if (config.allowSkinOverride) remember { Skin.entries.map { FocusRequester() } } else null
    val langFRs = remember { UI_LANGUAGES.map { FocusRequester() } }
    val progressFR = remember { FocusRequester() }
    val autoplayFR = remember { FocusRequester() }
    val changePwFR = remember { FocusRequester() }
    val installFR = remember { FocusRequester() }
    val signOutFR = remember { FocusRequester() }
    val unpairFR = remember { FocusRequester() }

    // Skin section
    if (config.allowSkinOverride) {
        SectionHeader(str("settings.appearance"))
        Spacer(Modifier.height(12.dp))
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
                            focusRequester = skinFRs!![i],
                            onFocused = { focused = true },
                            // Bug fix (found via live TV testing) — onBlurred was never wired here
                            // either, so a skin/language pill's focus ring never cleared on navigating
                            // away — the same stale-ring bug ToggleRow had.
                            onBlurred = { focused = false },
                            onLeft  = { if (i > 0) skinFRs[i - 1].requestFocus() },
                            onRight = { if (i < Skin.entries.lastIndex) skinFRs[i + 1].requestFocus() },
                            onDown = { langFRs[0].requestFocus() },
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
                        onBlurred = { focused = false },
                        onLeft  = { if (i > 0) langFRs[i - 1].requestFocus() },
                        onRight = { if (i < UI_LANGUAGES.lastIndex) langFRs[i + 1].requestFocus() },
                        onUp = skinFRs?.let { frs -> { frs[0].requestFocus() } },
                        onDown = { progressFR.requestFocus() },
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
    ToggleRow(
        label = str("settings.show_progress"),
        checked = config.showContinueProgress,
        focusRequester = progressFR,
        onToggle = { store.saveShowContinueProgress(!config.showContinueProgress) },
        onUp = { langFRs[0].requestFocus() },
        onDown = { autoplayFR.requestFocus() },
    )
    Spacer(Modifier.height(12.dp))
    ToggleRow(
        label = str("settings.autoplay_next"),
        checked = config.autoplayNext,
        focusRequester = autoplayFR,
        onToggle = { store.saveAutoplayNext(!config.autoplayNext) },
        onUp = { progressFR.requestFocus() },
        onDown = { changePwFR.requestFocus() },
    )
    // Land focus on a stable control on entry; up/down reach skin and sign-out.
    LaunchedEffect(Unit) { runCatching { progressFR.requestFocus() } }

    Spacer(Modifier.height(32.dp))

    // Account
    SectionHeader(str("settings.account"))
    Spacer(Modifier.height(12.dp))
    Text(str("profile.signed_in", mapOf("name" to displayName)), color = colors.textSecondary, fontSize = 15.sp)
    Spacer(Modifier.height(16.dp))
    // R234 (FR-R234-4) — a password is a credential, not a preference, so it lives here on every
    // platform (TV included) rather than gated like the phone/web-only "Your profile" photo screen.
    var changePwFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
            .then(if (changePwFocused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(8.dp)) else Modifier)
            .dpadFocusable(
                focusRequester = changePwFR,
                onFocused = { changePwFocused = true },
                onBlurred = { changePwFocused = false },
                onUp = { autoplayFR.requestFocus() },
                onDown = { (if (isWebPlatform) installFR else signOutFR).requestFocus() },
                onSelect = onChangePassword,
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(str("account.pw_change"), color = colors.text, fontSize = 14.sp)
    }
    Spacer(Modifier.height(16.dp))
    if (isWebPlatform) {
        var installFocused by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                .then(if (installFocused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(8.dp)) else Modifier)
                .dpadFocusable(
                    focusRequester = installFR,
                    onFocused = { installFocused = true },
                    onBlurred = { installFocused = false },
                    onUp = { changePwFR.requestFocus() },
                    onDown = { signOutFR.requestFocus() },
                    onSelect = onInstallRavilo,
                )
                .padding(horizontal = 24.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(str("settings.install"), color = colors.text, fontSize = 14.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
            .then(if (focused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(8.dp)) else Modifier)
            .dpadFocusable(
                focusRequester = signOutFR,
                onFocused = { focused = true },
                // Bug fix (found via live TV testing) — onBlurred was never wired; Sign out's focus
                // ring would have stuck the same way ToggleRow's did.
                onBlurred = { focused = false },
                onUp = { (if (isWebPlatform) installFR else changePwFR).requestFocus() },
                onDown = { unpairFR.requestFocus() },
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
                focusRequester = unpairFR,
                onFocused = { unpairFocused = true },
                onBlurred = { unpairFocused = false },
                onUp = { signOutFR.requestFocus() },
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
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant, RoundedCornerShape(10.dp))
            .then(if (focused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(10.dp)) else Modifier)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                // Bug fix (found via live TV testing) — onBlurred was never wired, so once a row was
                // focused its border never cleared: both Playback rows showed the focus ring
                // simultaneously after navigating away from the first one.
                onBlurred = { focused = false },
                onSelect = onToggle,
                onUp = onUp,
                onDown = onDown,
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // R229 bug fix: an unweighted label next to a fixed-size On/Off pill let a long label (e.g.
        // "Show progress on Continue Watching") claim the row's full width before the pill was ever
        // measured, squeezing the pill to near-zero width and wrapping its text one letter per line.
        // weight(1f) reserves the pill's own natural size first and lets the label wrap into the
        // remainder instead, on any screen width.
        Text(
            label,
            color = if (focused) colors.text else colors.textSecondary,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Box(
            modifier = Modifier
                .background(if (checked) colors.accent else colors.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(if (checked) str("on") else str("off"), color = if (checked) colors.onAccent else colors.textSecondary, fontSize = 12.sp)
        }
    }
}
