package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.RaviloThemes
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.horizontalScroll
import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import dev.jellystructure.ravilo.ui.theme.LocalHandset
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

    /**
     * R338 (FR-R338-3/5) — the theme settings; only the fields given change. Applied here at once (the drawn theme
     * follows the returned config) and confirmed by the server's push. Returns the updated config, or null when
     * Settings has not loaded.
     */
    fun saveTheme(theme: String? = null, follow: Boolean? = null, light: String? = null, dark: String? = null): RaviloConfig? {
        val cur = (_state.value as? SettingsState.Loaded)?.config ?: return null
        val updated = cur.copy(
            theme = theme ?: cur.theme, themeFollow = follow ?: cur.themeFollow,
            themeLight = light ?: cur.themeLight, themeDark = dark ?: cur.themeDark,
        )
        _state.value = SettingsState.Loaded(updated)
        scope.launch { runCatching { apiClient.putViewerSettings(theme = theme, themeFollow = follow, themeLight = light, themeDark = dark) } }
        return updated
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
    dev.jellystructure.ravilo.ui.music.forgetListening()   // R340 — as signOutActiveSession does; the next viewer starts in video mode
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
    dev.jellystructure.ravilo.ui.music.forgetListening()   // R321 (FR-R321-1) — the next viewer starts in video mode
    return MultiTokenStore.getAll().isNotEmpty()
}

@Composable
fun SettingsScreen(
    store: SettingsStore,
    displayName: String,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    onSkinChange: (Skin) -> Unit = {},
    // R338 — the theme settings changed here; the caller redraws in them at once.
    onThemeChange: (RaviloConfig) -> Unit = {},
    // R340 (FR-R340-3) — fires after "Everyone on this TV": every session this device holds is revoked
    // (store.unpairDevice() has completed); the caller forgets the server and returns to server setup.
    onSignedOutEveryone: () -> Unit = {},
    // R234 (FR-R234-4) — every platform (TV included); opens the dedicated Change password screen.
    onChangePassword: () -> Unit = {},
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
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
    var backFocused by rememberFocusVisual()
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
                    onThemeChange = onThemeChange,
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
        // R340 (FR-R340-2/3) — in the TV layout Settings' Sign out opens the same dialog as the profile menu:
        // this profile only, or everyone on this TV (the old Unpair, plus the server forgotten). The phone
        // keeps R304's one-profile sign-out, worded as its Profile page words it.
        if (showSignOutConfirm) {
            if (!LocalHandset.current) {
                SignOutChoiceOverlay(
                    displayName = displayName,
                    onCancel = { showSignOutConfirm = false },
                    // R191 — revoke + forget only this profile; onSignOut decides Login vs ProfilePicker.
                    onOnly = { showSignOutConfirm = false; scope.launch { store.signOutActiveSession(); onSignOut() } },
                    onEveryone = { showSignOutConfirm = false; scope.launch { store.unpairDevice(); onSignedOutEveryone() } },
                )
            } else {
                ConfirmOverlay(
                    title = str("profile.signout_title"),
                    description = str("profile.signout_body"),
                    confirmLabel = str("profile.signout_confirm"),
                    onCancel = { showSignOutConfirm = false },
                    onConfirm = {
                        showSignOutConfirm = false
                        scope.launch { store.signOutActiveSession(); onSignOut() }
                    },
                )
            }
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
                var cancelFocused by rememberFocusVisual()
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
                var confirmFocused by rememberFocusVisual()
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

/**
 * R340 (FR-R340-3) — the one way out of a TV: Sign out asks whom. Two radio rows — **Only {name}** (the default, and
 * where focus lands) and **Everyone on this TV** — then Cancel · the confirm button, whose label says what it will do.
 * Used by Settings and by R170's avatar [dev.jellystructure.ravilo.ui.components.ProfileMenu]. D-pad: ↑/↓ between the
 * rows, OK picks one, ↓ from the second row reaches the buttons, Back cancels. It is the only confirmation — there is
 * no second one for Everyone. [onOnly] is R191's per-profile sign-out; [onEveryone] is what *Unpair this TV* did, and
 * the caller also forgets the server.
 */
@Composable
fun SignOutChoiceOverlay(displayName: String, onCancel: () -> Unit, onOnly: () -> Unit, onEveryone: () -> Unit) {
    val colors = RaviloTheme.colors
    val signedIn = remember { MultiTokenStore.getAll().size.coerceAtLeast(1) }
    var everyone by remember { mutableStateOf(false) }
    val oneFR = remember { FocusRequester() }
    val allFR = remember { FocusRequester() }
    val cancelFR = remember { FocusRequester() }
    val confirmFR = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { oneFR.requestFocus() } }
    val confirm = { if (everyone) onEveryone() else onOnly() }
    Box(
        modifier = Modifier.fillMaxSize().background(colors.overlay),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 620.dp)
                .background(colors.surface, RoundedCornerShape(14.dp))
                .padding(32.dp),
        ) {
            Text(str("signout.title"), color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            SignOutChoiceRow(
                title = str("signout.one", mapOf("name" to displayName)),
                sub = str(if (signedIn > 1) "signout.one_sub" else "signout.one_sub_last"),
                selected = !everyone,
                focusRequester = oneFR,
                onUp = null,
                onDown = { allFR.requestFocus() },
                onSelect = { everyone = false },
                onBack = onCancel,
            )
            Spacer(Modifier.height(12.dp))
            SignOutChoiceRow(
                title = str("signout.all"),
                sub = if (signedIn > 1) str("signout.all_sub", mapOf("n" to signedIn.toString())) else str("signout.all_sub_one"),
                selected = everyone,
                focusRequester = allFR,
                onUp = { oneFR.requestFocus() },
                onDown = { confirmFR.requestFocus() },
                onSelect = { everyone = true },
                onBack = onCancel,
            )
            Spacer(Modifier.height(26.dp))
            Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                var cancelFocused by rememberFocusVisual()
                Box(
                    modifier = Modifier
                        .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
                        .then(if (cancelFocused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(8.dp)) else Modifier)
                        .dpadFocusable(
                            focusRequester = cancelFR,
                            onFocused = { cancelFocused = true },
                            onBlurred = { cancelFocused = false },
                            onUp = { allFR.requestFocus() },
                            onRight = { runCatching { confirmFR.requestFocus() } },
                            onSelect = onCancel,
                            onBack = onCancel,
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) { Text(str("action.cancel"), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                var confirmFocused by rememberFocusVisual()
                Box(
                    modifier = Modifier
                        .background(Color(0xFFE0393A), RoundedCornerShape(8.dp))
                        .then(if (confirmFocused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(8.dp)) else Modifier)
                        .dpadFocusable(
                            focusRequester = confirmFR,
                            onFocused = { confirmFocused = true },
                            onBlurred = { confirmFocused = false },
                            onUp = { allFR.requestFocus() },
                            onLeft = { runCatching { cancelFR.requestFocus() } },
                            onSelect = confirm,
                            onBack = onCancel,
                        )
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Text(
                        str(if (everyone) "signout.yes_all" else "settings.sign_out_yes"),
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** R340 — one radio row of [SignOutChoiceOverlay]: a ring that fills with the accent when chosen, the title, one line. */
@Composable
private fun SignOutChoiceRow(
    title: String,
    sub: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    onUp: (() -> Unit)?,
    onDown: () -> Unit,
    onSelect: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = RaviloTheme.colors
    var focused by rememberFocusVisual()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused || selected) colors.surfaceVariant else Color.Transparent, RoundedCornerShape(12.dp))
            .border(2.dp, if (focused) colors.focusRing else colors.textDim.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onUp = onUp,
                onDown = onDown,
                onSelect = onSelect,
                onBack = onBack,
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.padding(top = 2.dp).size(22.dp)
                .border(2.dp, if (selected) colors.accent else colors.textDim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(12.dp).background(colors.accent, CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(sub, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
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
    onThemeChange: (RaviloConfig) -> Unit = {},
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
    // R328 (FR-R328-8) — the Mac's update line, between the identity and Change password when there is one.
    val updateOffer by dev.jellystructure.ravilo.ui.components.AppUpdate.offer.collectAsState()
    val updateFR = remember { FocusRequester() }
    val changePwFR = remember { FocusRequester() }
    val installFR = remember { FocusRequester() }
    val signOutFR = remember { FocusRequester() }

    // R338 — the theme settings, when the server has them (FR-R338-5); an older server keeps the three skins below.
    val themeEntryFR = remember { FocusRequester() }
    if (config.allowSkinOverride && config.hasThemes()) {
        ThemeSection(config, store, entryFR = themeEntryFR, downFR = langFRs[0], onThemeChange = onThemeChange)
        Spacer(Modifier.height(32.dp))
    }
    // Skin section
    if (config.allowSkinOverride && !config.hasThemes()) {
        SectionHeader(str("settings.appearance"))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Skin.entries.forEachIndexed { i, skin ->
                val isActive = config.effectiveSkin() == skin
                var focused by rememberFocusVisual()

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
            var focused by rememberFocusVisual()
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
                        onUp = if (config.allowSkinOverride && config.hasThemes()) ({ themeEntryFR.requestFocus() })
                            else skinFRs?.let { frs -> { frs[0].requestFocus() } },
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
        onDown = { (if (updateOffer != null) updateFR else changePwFR).requestFocus() },
    )
    // R322 (FR-R322-9, J6's lean) — Settings ▸ Listening: *Even out volume*, on by default. Wherever music plays
    // and the device is not a TV: the phone, and (R329) the Mac.
    if ((dev.jellystructure.ravilo.ui.theme.LocalHandset.current || !dev.jellystructure.ravilo.ui.isTvPlatform) && dev.jellystructure.ravilo.ui.music.MusicEngine.supported) {
        Spacer(Modifier.height(32.dp))
        SectionHeader(str("music.listening"))
        Spacer(Modifier.height(12.dp))
        var even by remember { mutableStateOf(dev.jellystructure.ravilo.ui.music.MusicPrefs.evenVolume) }
        ToggleRow(
            label = str("music.even_volume"),
            checked = even,
            focusRequester = remember { FocusRequester() },
            onToggle = { even = !even; dev.jellystructure.ravilo.ui.music.MusicEngine.setEvenVolume(even) },
        )
        Spacer(Modifier.height(6.dp))
        Text(str("music.even_volume_sub"), color = colors.textSecondary, fontSize = 13.sp)
        // R323 (FR-R323-9) — the two book settings: skip silences (off) and the sleep timer's fade (on).
        // R329 — skipping silences is a player's feature, and only where the player has it (not AVPlayer).
        if (dev.jellystructure.ravilo.ui.music.playerSkipsSilence) {
            Spacer(Modifier.height(16.dp))
            var silence by remember { mutableStateOf(dev.jellystructure.ravilo.ui.music.BookPrefs.skipSilence) }
            ToggleRow(
                label = str("ab.skip_silence"),
                checked = silence,
                focusRequester = remember { FocusRequester() },
                onToggle = { silence = !silence; dev.jellystructure.ravilo.ui.music.MusicEngine.setSkipSilence(silence) },
            )
            Spacer(Modifier.height(6.dp))
            Text(str("ab.skip_silence_sub"), color = colors.textSecondary, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
        var fade by remember { mutableStateOf(dev.jellystructure.ravilo.ui.music.BookPrefs.sleepFade) }
        ToggleRow(
            label = str("ab.sleep_fade"),
            checked = fade,
            focusRequester = remember { FocusRequester() },
            onToggle = { fade = !fade; dev.jellystructure.ravilo.ui.music.BookPrefs.sleepFade = fade },
        )
        Spacer(Modifier.height(6.dp))
        Text(str("ab.sleep_fade_sub"), color = colors.textSecondary, fontSize = 13.sp)
    }
    // Land focus on a stable control on entry; up/down reach skin and sign-out.
    LaunchedEffect(Unit) { runCatching { progressFR.requestFocus() } }

    Spacer(Modifier.height(32.dp))

    // Account
    SectionHeader(str("settings.account"))
    Spacer(Modifier.height(12.dp))
    Text(str("profile.signed_in", mapOf("name" to displayName)), color = colors.textSecondary, fontSize = 15.sp)
    Spacer(Modifier.height(16.dp))
    updateOffer?.let { offer ->
        dev.jellystructure.ravilo.ui.components.AppUpdateLine(
            offer, updateFR,
            onUp = { autoplayFR.requestFocus() },
            onDown = { changePwFR.requestFocus() },
        )
        Spacer(Modifier.height(16.dp))
    }
    // R234 (FR-R234-4) — a password is a credential, not a preference, so it lives here on every
    // platform (TV included) rather than gated like the phone/web-only "Your profile" photo screen.
    var changePwFocused by rememberFocusVisual()
    Box(
        modifier = Modifier
            .background(colors.surfaceVariant, RoundedCornerShape(8.dp))
            .then(if (changePwFocused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(8.dp)) else Modifier)
            .dpadFocusable(
                focusRequester = changePwFR,
                onFocused = { changePwFocused = true },
                onBlurred = { changePwFocused = false },
                onUp = { (if (updateOffer != null) updateFR else autoplayFR).requestFocus() },
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
        var installFocused by rememberFocusVisual()
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
    var focused by rememberFocusVisual()
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
                onSelect = onSignOut,
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(str("profile.sign_out"), color = colors.text, fontSize = 14.sp)
    }

    // R340 (FR-R340-2) — the separate "Unpair this TV" section is gone: Sign out above asks whom, and
    // "Everyone on this TV" is what it did (plus the server forgotten). One way out, and it always asks.
}

/**
 * R338 (FR-R338-5) — Settings ▸ Theme.
 *
 * - **A TV-layout device** (a TV, the web app in a desktop browser) is dark-only (D6): one row of the four dark themes,
 *   *This TV is always dark*. A pick is the dark pick, and also the one pick while the viewer is not following the
 *   system and their one pick is dark — so a pick on the TV is what the viewer sees on every dark screen.
 * - **A phone, or the desktop**: *Match the phone's light and dark* (the computer's wording on the desktop); on, a
 *   light row (Daylight) and a dark row (the four), the row in use marked; off, one row of all five. A dark one pick
 *   is also written as the dark pick, so the TV follows it.
 *
 * The foot line says the settings are the viewer's, on every device.
 */
@Composable
private fun ThemeSection(
    config: RaviloConfig,
    store: SettingsStore,
    entryFR: FocusRequester,
    downFR: FocusRequester,
    onThemeChange: (RaviloConfig) -> Unit,
) {
    val colors = RaviloTheme.colors
    val desk = dev.jellystructure.ravilo.ui.isDesktopPlatform
    val darkOnly = !(LocalHandset.current || desk)
    val follow = config.themeFollow == true
    val drawnLight = dev.jellystructure.ravilo.ui.theme.LocalRaviloTheme.current.isLight
    fun save(theme: String? = null, follow: Boolean? = null, light: String? = null, dark: String? = null) {
        store.saveTheme(theme, follow, light, dark)?.let(onThemeChange)
    }
    class ThemeRow(val caption: String?, val ids: List<String>, val active: String?, val pick: (String) -> Unit)
    val rows: List<ThemeRow> = when {
        darkOnly -> listOf(ThemeRow(null, RaviloThemes.darkThemes, config.themeDark) { id ->
            save(dark = id, theme = if (!follow && RaviloThemes.isDark(config.theme)) id else null)
        })
        follow -> listOf(
            ThemeRow(
                str(if (desk) "theme.light" else "theme.when_light") + if (drawnLight) " · " + str("theme.in_use") else "",
                RaviloThemes.lightThemes, config.themeLight,
            ) { id -> save(light = id) },
            ThemeRow(
                str(if (desk) "theme.dark" else "theme.when_dark") + if (!drawnLight) " · " + str("theme.in_use") else "",
                RaviloThemes.darkThemes, config.themeDark,
            ) { id -> save(dark = id) },
        )
        else -> listOf(ThemeRow(null, RaviloThemes.all, config.theme) { id ->
            save(theme = id, dark = id.takeIf { RaviloThemes.isDark(it) })
        })
    }
    val pillFRs = remember(rows.map { it.ids.size }) { rows.map { r -> r.ids.map { FocusRequester() } } }
    val followFR = entryFR

    SectionHeader(str("settings.appearance"))
    Spacer(Modifier.height(12.dp))
    if (!darkOnly) {
        ToggleRow(
            label = str(if (desk) "theme.follow_desk" else "theme.follow"),
            checked = follow,
            focusRequester = followFR,
            onToggle = { save(follow = !follow) },
            onDown = { pillFRs.firstOrNull()?.firstOrNull()?.requestFocus() },
        )
        if (!desk) {
            Spacer(Modifier.height(6.dp))
            Text(str("theme.follow_sub"), color = colors.textSecondary, fontSize = 13.sp)
        }
        Spacer(Modifier.height(16.dp))
    }
    rows.forEachIndexed { r, row ->
        row.caption?.let {
            Text(it, color = colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            row.ids.forEachIndexed { i, id ->
                val fr = if (darkOnly && r == 0 && i == 0) entryFR else pillFRs[r][i]
                ThemePill(
                    id = id,
                    active = id == row.active,
                    focusRequester = fr,
                    onLeft = { if (i > 0) pillFRs[r][i - 1].requestFocus() },
                    onRight = { if (i < row.ids.lastIndex) pillFRs[r][i + 1].requestFocus() },
                    onUp = when {
                        r > 0 -> ({ pillFRs[r - 1][0].requestFocus() })
                        !darkOnly -> ({ followFR.requestFocus() })
                        else -> null
                    },
                    onDown = { if (r < rows.lastIndex) pillFRs[r + 1][0].requestFocus() else downFR.requestFocus() },
                    onSelect = { row.pick(id) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    if (darkOnly) {
        Text(str(if (dev.jellystructure.ravilo.ui.seams.isWebPlatform) "theme.dark_only_web" else "theme.dark_only"), color = colors.textSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
    }
    Text(str("theme.synced"), color = colors.textDim, fontSize = 13.sp)
}

/** R338 — one theme: a small two-tone swatch (the theme's page and its accent), then its name. */
@Composable
private fun ThemePill(
    id: String,
    active: Boolean,
    focusRequester: FocusRequester,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onUp: (() -> Unit)?,
    onDown: () -> Unit,
    onSelect: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val swatch = (dev.jellystructure.ravilo.ui.theme.ThemeId.of(id) ?: dev.jellystructure.ravilo.ui.theme.ThemeId.AURORA).colors()
    var focused by rememberFocusVisual()
    Row(
        modifier = Modifier
            .background(if (active) colors.accent else colors.surfaceVariant, RoundedCornerShape(10.dp))
            .then(if (focused && !active) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(10.dp)) else Modifier)
            .dpadFocusable(
                focusRequester = focusRequester,
                onFocused = { focused = true },
                onBlurred = { focused = false },
                onLeft = onLeft,
                onRight = onRight,
                onUp = onUp,
                onDown = onDown,
                onSelect = onSelect,
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.size(18.dp).clip(CircleShape).border(1.dp, colors.fg.copy(alpha = 0.25f), CircleShape)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(swatch.background))
            Box(Modifier.weight(1f).fillMaxHeight().background(swatch.accent))
        }
        Text(
            text = str("theme.$id"),
            color = if (active) colors.onAccent else if (focused) colors.text else colors.textSecondary,
            fontSize = 14.sp,
            fontWeight = if (active || focused) FontWeight.SemiBold else FontWeight.Normal,
        )
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
    var focused by rememberFocusVisual()

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
