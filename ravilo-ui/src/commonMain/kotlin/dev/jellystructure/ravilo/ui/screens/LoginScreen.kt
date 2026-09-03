package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.DeviceIdStore
import dev.jellystructure.ravilo.ui.TokenStore
import dev.jellystructure.ravilo.ui.deviceDisplayName
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.reportTextFieldFocus
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Phase 141/R175 — replaces the retired code-pairing flow (see git history for `PairingScreen`). */
enum class LoginErrorKind { REQUIRED, INVALID, UNREACHABLE }

sealed class LoginState {
    data object Idle : LoginState()
    data object SigningIn : LoginState()
    data class Success(val displayName: String) : LoginState()
    data class Errored(val kind: LoginErrorKind) : LoginState()
}

class LoginStore(private val apiClient: TvApiClient) {
    // R225 — read by LoginScreen's server indicator, so it reflects exactly what this store's client
    // is actually using rather than being threaded a second value that could drift from it.
    val baseUrl: String get() = apiClient.baseUrl
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun signIn(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            _state.value = LoginState.Errored(LoginErrorKind.REQUIRED)
            return
        }
        _state.value = LoginState.SigningIn
        scope.launch {
            val result = runCatching {
                apiClient.login(username, password, DeviceIdStore.get(), deviceDisplayName())
            }
            result.fold(
                onSuccess = { r ->
                    // Persist token + append the profile so the session survives app restart —
                    // same shape the retired pairing flow wrote (MultiTokenStore is unchanged) — and
                    // switch to it: every entry point (first launch, ProfilePicker's Add-user tile,
                    // ProfileMenu's Add-user row) lands the viewer on the freshly signed-in profile.
                    TokenStore.set(r.deviceToken)
                    MultiTokenStore.add(LocalSession(
                        userId = r.session.userId,
                        displayName = r.session.displayName,
                        deviceToken = r.deviceToken,
                        isAdmin = r.session.isAdmin,
                        isKids = r.session.isKids,
                        avatarUrl = r.session.avatarUrl,
                    ))
                    MultiTokenStore.setActive(r.session.userId)
                    _state.value = LoginState.Success(r.session.displayName)
                },
                onFailure = { e ->
                    val invalid = (e as? TvApiError.Http)?.status == 401
                    _state.value = LoginState.Errored(if (invalid) LoginErrorKind.INVALID else LoginErrorKind.UNREACHABLE)
                },
            )
        }
    }

    /** Back to the editable form after an error, keeping whatever the user typed. */
    fun dismissError() { _state.value = LoginState.Idle }
}

@Composable
fun LoginScreen(
    store: LoginStore,
    onSignedIn: () -> Unit,
    onChangeServer: () -> Unit = {},
) {
    val colors = RaviloTheme.colors
    val state by store.state.collectAsState()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val usernameFR = remember { FocusRequester() }
    val passwordFR = remember { FocusRequester() }
    val signInFR = remember { FocusRequester() }
    val changeServerFR = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { usernameFR.requestFocus() }

    LaunchedEffect(state) {
        if (state is LoginState.Success) {
            delay(1_200)
            onSignedIn()
        }
    }

    fun submit() {
        keyboardController?.hide()
        store.signIn(username, password)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(40.dp).width(420.dp)) {
            Text("Ravilo", color = colors.accent, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(Modifier.height(16.dp))

            when (val s = state) {
                is LoginState.Success -> {
                    Spacer(Modifier.height(32.dp))
                    Text(
                        "✓ ${str("login.success", mapOf("name" to s.displayName))}",
                        color = colors.badgeWatched, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                else -> {
                    Text(str("login.title"), color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        str("login.subtitle"), color = colors.textSecondary, fontSize = 14.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(28.dp))

                    val locked = s is LoginState.SigningIn
                    LoginField(
                        label = str("login.username"),
                        value = username,
                        onValueChange = { username = it },
                        focusRequester = usernameFR,
                        enabled = !locked,
                        imeAction = ImeAction.Next,
                        onImeAction = { passwordFR.requestFocus() },
                        onMoveDown = { passwordFR.requestFocus() },
                    )
                    Spacer(Modifier.height(14.dp))
                    LoginField(
                        label = str("login.password"),
                        value = password,
                        onValueChange = { password = it },
                        focusRequester = passwordFR,
                        enabled = !locked,
                        masked = true,
                        imeAction = ImeAction.Done,
                        onImeAction = { submit() },
                        onMoveUp = { usernameFR.requestFocus() },
                        onMoveDown = { signInFR.requestFocus() },
                    )

                    if (s is LoginState.Errored) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            when (s.kind) {
                                LoginErrorKind.REQUIRED -> str("login.error_required")
                                LoginErrorKind.INVALID -> str("login.error_invalid")
                                LoginErrorKind.UNREACHABLE -> str("login.error_unreachable")
                            },
                            color = colors.badgeNew, fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }

                    Spacer(Modifier.height(28.dp))
                    LoginButton(
                        label = if (locked) str("login.signing_in") else str("login.sign_in"),
                        focusRequester = signInFR,
                        enabled = !locked,
                        onSelect = { submit() },
                    )

                    Spacer(Modifier.height(18.dp))
                    ChangeServerLink(focusRequester = changeServerFR, onSelect = onChangeServer)
                    Spacer(Modifier.height(4.dp))
                    ServerIndicator(store.baseUrl)
                }
            }
        }
    }
}

@Composable
private fun LoginField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    enabled: Boolean,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    masked: Boolean = false,
    // Bug fix (found via live TV testing) — the on-screen keyboard's own Next/Done glyph moved focus
    // fine, but with the keyboard dismissed (e.g. a remote's Back key closes it, common muscle memory)
    // a bare focused BasicTextField swallows Up/Down for cursor movement, so there was no way to reach
    // the other field with the D-pad alone — the viewer was stuck re-opening the same field's keyboard,
    // and a second Back press popped the whole Login screen (losing anything typed) instead of doing
    // nothing. These callbacks fire on a hardware Up/Down key regardless of keyboard visibility, letting
    // the D-pad move between fields the same way it does everywhere else in this app.
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = colors.textSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(colors.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            // Native IME text field (matches SearchScreen) — no reusable on-screen keyboard exists in
            // this codebase. The OS keyboard handles D-pad/remote text entry while it's shown; the
            // onPreviewKeyEvent below covers Up/Down once it's dismissed (see the onMoveUp/onMoveDown doc).
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .reportTextFieldFocus()
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.DirectionUp -> onMoveUp?.let { it(); true } ?: false
                            Key.DirectionDown -> onMoveDown?.let { it(); true } ?: false
                            else -> false
                        }
                    },
                textStyle = TextStyle(color = colors.text, fontSize = 16.sp),
                singleLine = true,
                visualTransformation = if (masked) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onNext = { onImeAction() },
                    onDone = { onImeAction() },
                ),
                cursorBrush = SolidColor(colors.accent),
            )
        }
    }
}

// R225 — read-only reflection of what LoginStore's TvApiClient is actually pointed at (host only, no
// scheme — matches ServerSetupScreen's separate host/useHttps fields). Never focusable/actionable;
// ChangeServerLink above it is the only way to act on this.
@Composable
private fun ServerIndicator(baseUrl: String) {
    val colors = RaviloTheme.colors
    val host = baseUrl.substringAfter("://").trimEnd('/')
    if (host.isBlank()) return
    Text(host, color = colors.textSecondary, fontSize = 11.sp)
}

@Composable
private fun ChangeServerLink(focusRequester: FocusRequester, onSelect: () -> Unit) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .dpadFocusable(focusRequester = focusRequester, onFocused = { focused = true }, onBlurred = { focused = false }, onSelect = onSelect)
            .padding(vertical = 6.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            str("login.change_server"),
            color = if (focused) colors.accent else colors.textSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun LoginButton(label: String, focusRequester: FocusRequester, enabled: Boolean, onSelect: () -> Unit) {
    val colors = RaviloTheme.colors
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (!enabled) colors.surfaceVariant else if (focused) colors.accent else colors.surfaceVariant,
                RoundedCornerShape(10.dp),
            )
            .then(if (focused && enabled) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(10.dp)) else Modifier)
            .then(
                if (enabled) {
                    Modifier.dpadFocusable(
                        focusRequester = focusRequester,
                        onFocused = { focused = true },
                        onBlurred = { focused = false },
                        onSelect = onSelect,
                    )
                } else Modifier
            )
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (!enabled) colors.textSecondary else if (focused) colors.onAccent else colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
