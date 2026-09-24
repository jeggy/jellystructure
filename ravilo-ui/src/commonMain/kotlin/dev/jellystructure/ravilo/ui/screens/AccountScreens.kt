package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.focus.rememberFocusVisual
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.focus.dpadFocusable
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.seams.RemoteImage
import dev.jellystructure.ravilo.ui.seams.reportTextFieldFocus
import dev.jellystructure.ravilo.ui.theme.LocalCompact
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.AccountPasswordResult
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * R234 (FR-R234-4) — reused by every platform, TV included: a masked field that reads back through the
 * same [BasicTextField] + native-IME idiom [LoginScreen]'s `LoginField` already established rather than
 * a hand-rolled QWERTY grid — see that file's own comment for why ("no reusable on-screen keyboard
 * exists in this codebase"; the OS/Android-TV on-screen keyboard handles a focused text field, and a
 * paired physical keyboard's Tab/Backspace already work through the same IME path, satisfying
 * FR-R234-6's requirement without a bespoke component). onMoveUp/onMoveDown carry Up/Down between
 * fields once the OS keyboard is dismissed, exactly like `LoginField`.
 */
@Composable
private fun AccountField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    masked: Boolean = false,
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
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
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
                keyboardOptions = KeyboardOptions(imeAction = imeAction, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onNext = { onImeAction() }, onDone = { onImeAction() }),
                cursorBrush = SolidColor(colors.accent),
            )
        }
    }
}

/** Generic "‹ Back" header row, matching SettingsScreen's own. */
@Composable
private fun AccountScreenHeader(title: String, onBack: () -> Unit) {
    val colors = RaviloTheme.colors
    val backFR = remember { FocusRequester() }
    var backFocused by rememberFocusVisual()
    LaunchedEffect(Unit) { runCatching { backFR.requestFocus() } }
    Text(
        "‹ ${str("action.back")}",
        color = if (backFocused) colors.text else colors.textSecondary,
        fontSize = 13.sp,
        modifier = Modifier
            .then(if (backFocused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(6.dp)) else Modifier)
            .dpadFocusable(focusRequester = backFR, onFocused = { backFocused = true }, onBlurred = { backFocused = false }, onSelect = onBack)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
    Spacer(Modifier.height(8.dp))
    Text(title, color = colors.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(40.dp))
}

@Composable
private fun AccountActionButton(label: String, focusRequester: FocusRequester, onSelect: () -> Unit, danger: Boolean = false) {
    val colors = RaviloTheme.colors
    var focused by rememberFocusVisual()
    val textColor = if (danger) DangerRed else colors.text
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) colors.surfaceVariant else colors.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .then(if (focused) Modifier.border(2.dp, colors.focusRing, RoundedCornerShape(10.dp)) else Modifier)
            .dpadFocusable(focusRequester = focusRequester, onFocused = { focused = true }, onBlurred = { focused = false }, onSelect = onSelect)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

private val DangerRed = androidx.compose.ui.graphics.Color(0xFFE0393A)

// ─────────────────────────────────────────────────────────────────────────────
// FR-R234-4/5/6/7/8 — Change your password (every platform)
// ─────────────────────────────────────────────────────────────────────────────

private sealed class PwState {
    data object Idle : PwState()
    data object Busy : PwState()
    // Error carries a KEY, not a rendered string — str() is @Composable and this state is set from a
    // plain (non-composable) function and a coroutine callback, neither of which can call it.
    data class Error(val key: String) : PwState()
    data class Done(val tokenSurvived: Boolean) : PwState()
}

@Composable
fun ChangePasswordScreen(
    apiClient: TvApiClient,
    onBack: () -> Unit,
    // FR-R234-7 — fires only when Jellyfin reports the caller's own token did NOT survive; the caller
    // routes to sign-out/login exactly like ProfileMenu's own onSignedOut.
    onForceSignOut: () -> Unit,
) {
    val colors = RaviloTheme.colors
    val compact = LocalCompact.current
    val scope = rememberCoroutineScope()

    var current by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var repeatPw by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<PwState>(PwState.Idle) }

    val curFR = remember { FocusRequester() }
    val newFR = remember { FocusRequester() }
    val repFR = remember { FocusRequester() }
    val saveFR = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { curFR.requestFocus() } }

    fun submit() {
        when {
            current.isBlank() -> { state = PwState.Error("account.pw_err_cur"); return }
            newPw.length < 6 -> { state = PwState.Error("account.pw_err_new"); return }
            newPw != repeatPw -> { state = PwState.Error("account.pw_err_rep"); return }
        }
        state = PwState.Busy
        scope.launch {
            val result = runCatching { apiClient.changeAccountPassword(current, newPw) }
            result.fold(
                onSuccess = { r: AccountPasswordResult ->
                    if (r.ok) {
                        state = PwState.Done(r.tokenSurvived)
                    } else if (r.wrongCurrentPassword) {
                        // FR-R234-5 — clear only the field that was wrong; retyping a long new
                        // password because of a mistyped current one is a small cruelty to avoid.
                        current = ""
                        state = PwState.Error("account.pw_err_wrong")
                        curFR.requestFocus()
                    } else {
                        state = PwState.Error("error.generic")
                    }
                },
                onFailure = { state = PwState.Error("error.generic") },
            )
        }
    }

    LaunchedEffect(state) {
        val s = state
        if (s is PwState.Done) {
            delay(1200)
            if (s.tokenSurvived) onBack() else onForceSignOut()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compact) 20.dp else 80.dp, vertical = 40.dp)
                .width(if (compact) androidx.compose.ui.unit.Dp.Unspecified else 420.dp),
        ) {
            AccountScreenHeader(str("account.pw_title"), onBack)
            Text(str("account.pw_sub"), color = colors.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(28.dp))

            val busy = state is PwState.Busy || state is PwState.Done
            AccountField(
                label = str("account.pw_cur"), value = current, onValueChange = { if (!busy) current = it },
                focusRequester = curFR, imeAction = ImeAction.Next, onImeAction = { newFR.requestFocus() },
                masked = true, onMoveDown = { newFR.requestFocus() },
            )
            Spacer(Modifier.height(14.dp))
            AccountField(
                label = str("account.pw_new"), value = newPw, onValueChange = { if (!busy) newPw = it },
                focusRequester = newFR, imeAction = ImeAction.Next, onImeAction = { repFR.requestFocus() },
                masked = true, onMoveUp = { curFR.requestFocus() }, onMoveDown = { repFR.requestFocus() },
            )
            Spacer(Modifier.height(14.dp))
            AccountField(
                label = str("account.pw_rep"), value = repeatPw, onValueChange = { if (!busy) repeatPw = it },
                focusRequester = repFR, imeAction = ImeAction.Done, onImeAction = { submit() },
                masked = true, onMoveUp = { newFR.requestFocus() }, onMoveDown = { saveFR.requestFocus() },
            )
            Spacer(Modifier.height(20.dp))

            when (val s = state) {
                is PwState.Error -> Text(str(s.key), color = DangerRed, fontSize = 13.sp)
                is PwState.Done -> Text(str("account.pw_ok"), color = colors.accent, fontSize = 13.sp)
                else -> {}
            }
            Spacer(Modifier.height(if (state is PwState.Error || state is PwState.Done) 16.dp else 0.dp))

            AccountActionButton(
                label = if (state is PwState.Busy) str("account.pw_busy") else str("account.pw_save"),
                focusRequester = saveFR,
                onSelect = { if (!busy) submit() },
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FR-R234-1/3/8/9 — Your profile (phone/web only — caller gates on !isTvPlatform)
// ─────────────────────────────────────────────────────────────────────────────

private sealed class PhotoState {
    data object Idle : PhotoState()
    data object Busy : PhotoState()
    // Keys, not rendered strings — same reason as PwState.Error above: str() is @Composable and these
    // states are set from non-composable callbacks/coroutines.
    data class Error(val key: String) : PhotoState()
    data class Done(val key: String) : PhotoState()
}

@Composable
fun YourProfileScreen(
    apiClient: TvApiClient,
    displayName: String,
    initialAvatarUrl: String?,
    onBack: () -> Unit,
    // FR-R234-8 — repaints RaviloApp's LocalUserAvatarUrl the moment a change succeeds, no restart.
    onAvatarChanged: (String?) -> Unit,
) {
    val colors = RaviloTheme.colors
    val compact = LocalCompact.current
    val scope = rememberCoroutineScope()
    var avatarUrl by remember { mutableStateOf(initialAvatarUrl) }
    var state by remember { mutableStateOf<PhotoState>(PhotoState.Idle) }

    fun persistAndNotify(newUrl: String?) {
        avatarUrl = newUrl
        onAvatarChanged(newUrl)
        MultiTokenStore.getActive()?.let { active -> MultiTokenStore.add(active.copy(avatarUrl = newUrl)) }
    }

    fun upload(photo: PickedPhoto) {
        state = PhotoState.Busy
        scope.launch {
            runCatching { apiClient.setAccountPhoto(photo.dataBase64, photo.contentType) }.fold(
                onSuccess = { r -> persistAndNotify(r.avatarUrl); state = PhotoState.Done("account.photo_saved") },
                onFailure = { state = PhotoState.Error("error.generic") },
            )
        }
    }

    val launchChoose = rememberChoosePhotoLauncher(::upload)
    val launchTake = rememberTakePhotoLauncher(::upload)

    val chooseFR = remember { FocusRequester() }
    val takeFR = remember { FocusRequester() }
    val removeFR = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { chooseFR.requestFocus() } }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compact) 20.dp else 80.dp, vertical = 40.dp)
                .width(if (compact) androidx.compose.ui.unit.Dp.Unspecified else 420.dp),
        ) {
            AccountScreenHeader(str("account.profile_title"), onBack)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(88.dp).clip(CircleShape).background(colors.surfaceVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatarUrl != null) {
                        RemoteImage(avatarUrl!!, displayName, Modifier.fillMaxSize().clip(CircleShape))
                    } else {
                        Text(displayName.take(1).uppercase(), color = colors.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(str("account.photo_title"), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(str("account.photo_sub"), color = colors.textSecondary, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(28.dp))

            AccountActionButton(str("account.photo_choose"), chooseFR, onSelect = { if (state != PhotoState.Busy) launchChoose() })
            Spacer(Modifier.height(10.dp))
            AccountActionButton(str("account.photo_camera"), takeFR, onSelect = { if (state != PhotoState.Busy) launchTake() })
            if (avatarUrl != null) {
                Spacer(Modifier.height(10.dp))
                AccountActionButton(
                    str("account.photo_remove"), removeFR, danger = true,
                    onSelect = {
                        if (state != PhotoState.Busy) {
                            state = PhotoState.Busy
                            scope.launch {
                                runCatching { apiClient.deleteAccountPhoto() }.fold(
                                    onSuccess = { persistAndNotify(null); state = PhotoState.Done("account.photo_removed") },
                                    onFailure = { state = PhotoState.Error("error.generic") },
                                )
                            }
                        }
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            when (val s = state) {
                is PhotoState.Error -> Text(str(s.key), color = DangerRed, fontSize = 13.sp)
                is PhotoState.Done -> Text(str(s.key), color = colors.accent, fontSize = 13.sp)
                is PhotoState.Busy -> Text(str("account.pw_busy"), color = colors.textSecondary, fontSize = 13.sp)
                else -> {}
            }
        }
    }
}
