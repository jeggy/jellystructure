package dev.jellystructure.ravilo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.DeviceIdStore
import dev.jellystructure.ravilo.ui.components.BrandMark
import dev.jellystructure.ravilo.ui.components.ButtonStyle
import dev.jellystructure.ravilo.ui.components.RaviloButton
import dev.jellystructure.ravilo.ui.deviceDisplayName
import dev.jellystructure.ravilo.ui.focus.requestFocusRetrying
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.qr.QrCodeImage
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.TvApiClient
import kotlinx.coroutines.delay

/** DEB-2233 (FR-5) — tags for the TV sign-in screen's parts. */
object TvSignInTags {
    const val SCREEN = "tv-signin"
    const val CODE = "tv-signin-code"
    const val QR = "tv-signin-qr"
    const val APPROVING = "tv-signin-approving"
    const val PASSWORD = "tv-signin-password"
    const val RETRY = "tv-signin-retry"
}

/**
 * DEB-2233 (FR-5) — a TV (Android TV and Google TV) opens on a code and a QR a viewer approves in Ravilo on another device;
 * today's [LoginScreen], passed in as [passwordForm], is one press away and Back from it returns here. The design is the
 * round's direction A (`design/incoming/2026-10-23-tv-signin/`), drawn on a 1920 × 1080 stage and laid out here at half
 * its pixels.
 *
 * [onBack] is the *Add user* case (the profile picker is underneath): Back returns to it, and the screen says so. Null on a
 * TV with no profiles. [signedOutNotice] keeps DEB-1933's line (this device was signed out by a password change elsewhere),
 * which [LoginScreen] shows under its title and the code would otherwise hide.
 *
 * The first focus target is *Sign in with a password instead*; the QR and the code are not focusable and there is nothing to type.
 * No single-sign-on button, ever: that happens on the device that approves.
 */
@Composable
fun TvSignInScreen(
    apiClient: TvApiClient,
    onSignedIn: () -> Unit,
    onBack: (() -> Unit)? = null,
    signedOutNotice: Boolean = false,
    passwordForm: @Composable () -> Unit,
) {
    val store = remember { TvSignInStore(TvClientSignInApi(apiClient), { DeviceIdStore.get() }, { deviceDisplayName() }, ::persistSignIn) }
    val state by store.state.collectAsState()
    var passwordOpen by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    // The code is asked for, and polled, only while it is on screen. Opening the password form stops it; coming back asks for a fresh one.
    LaunchedEffect(passwordOpen, attempt) { if (!passwordOpen) store.run() }

    // `available: false` — no code to show, so the password form is the screen.
    val passwordShown = passwordOpen || state is TvSignInState.PasswordOnly
    val codeScreenExists = state !is TvSignInState.PasswordOnly

    if (passwordShown) {
        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { ev ->
                if (codeScreenExists && ev.key == Key.Back) {
                    if (ev.type == KeyEventType.KeyDown) passwordOpen = false
                    true
                } else false
            },
        ) { passwordForm() }
        return
    }

    val signedIn = state as? TvSignInState.SignedIn
    if (signedIn != null) LaunchedEffect(signedIn) { delay(1_200); onSignedIn() }

    val colors = RaviloTheme.colors
    val scope = rememberCoroutineScope()
    val passwordFR = remember { FocusRequester() }
    val retryFR = remember { FocusRequester() }
    val unreachable = state is TvSignInState.Unreachable
    // Retry is the way out of an error; otherwise the password button is where focus starts and stays.
    LaunchedEffect(unreachable, signedIn != null) {
        if (signedIn == null) requestFocusRetrying(scope, if (unreachable) retryFR else passwordFR)
    }
    val back: (() -> Unit)? = onBack
    val backKey = Modifier.onKeyEvent { ev ->
        if (back != null && ev.key == Key.Back) { if (ev.type == KeyEventType.KeyDown) back(); true } else false
    }

    Box(Modifier.fillMaxSize().background(colors.background).testTag(TvSignInTags.SCREEN)) {
        // Vertical padding keeps the focus ring clear of the overscan a set may still crop (design report, defect 3).
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 28.dp)) {
            Row(Modifier.height(46.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandMark(size = 22.dp)
                Text("Ravilo", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                when (val s = state) {
                    is TvSignInState.ShowingCode -> CodeBody(s, signedOutNotice)
                    is TvSignInState.SignedIn -> Text(
                        "✓ ${str("login.success", mapOf("name" to s.displayName))}",
                        color = colors.badgeWatched, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    )
                    is TvSignInState.Unreachable -> Text(
                        str("login.error_unreachable"), color = colors.text, fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    )
                    // Loading: the footer's button is already there, so focus does not move when the code arrives.
                    else -> Unit
                }
            }
            if (signedIn == null) Row(
                Modifier.height(84.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (unreachable) RaviloButton(
                    label = str("action.retry"), focusRequester = retryFR, style = ButtonStyle.PRIMARY,
                    modifier = Modifier.testTag(TvSignInTags.RETRY).then(backKey),
                    onSelect = { attempt++ },
                )
                RaviloButton(
                    label = str("tvsignin.password_instead"), focusRequester = passwordFR, style = ButtonStyle.GHOST,
                    modifier = Modifier.testTag(TvSignInTags.PASSWORD).then(backKey),
                    onSelect = { passwordOpen = true },
                )
                if (onBack != null) Text(str("tvsignin.back_to_profiles"), color = colors.textDim, fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun CodeBody(s: TvSignInState.ShowingCode, signedOutNotice: Boolean) {
    val colors = RaviloTheme.colors
    val hasQr = s.linkUrl != null
    // A composable value, not `val text = Column(...)`: that would emit the column here, beside the QR's row, not inside it.
    val text: @Composable () -> Unit = { Column(
        Modifier.widthIn(max = if (hasQr) 530.dp else 620.dp),
        horizontalAlignment = if (hasQr) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        Text(str("tvsignin.title"), color = colors.text, fontSize = 29.sp, fontWeight = FontWeight.SemiBold)
        if (signedOutNotice) {
            Spacer(Modifier.height(8.dp))
            Text(str("account.pw_signed_out"), color = colors.accent, fontSize = 13.sp)
        }
        Spacer(Modifier.height(15.dp))
        Text(str("tvsignin.code_label").uppercase(), color = colors.textSecondary, fontSize = 11.sp, letterSpacing = 0.7.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            groupCode(s.code), color = colors.text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
            // With the QR gone the code is the one thing left to read across the room, so it grows (design: 136 px against 112).
            fontSize = if (hasQr) 56.sp else 68.sp, letterSpacing = 7.sp, modifier = Modifier.testTag(TvSignInTags.CODE),
        )
        Spacer(Modifier.height(13.dp))
        val name = s.approverName
        // Two lines tall either way, so the code does not move when a viewer opens it and the line changes.
        Box(Modifier.heightIn(min = 44.dp), contentAlignment = if (hasQr) Alignment.CenterStart else Alignment.Center) {
            if (name != null) Row(Modifier.testTag(TvSignInTags.APPROVING), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                // Three still dots: no infinite animation on a screen that sits for minutes.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (a in listOf(1f, 0.6f, 0.28f)) Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(colors.accent))
                }
                Text(str("tvsignin.approving", mapOf("name" to name)), color = colors.text, fontSize = 15.sp)
            } else Text(
                str(if (hasQr) "tvsignin.how" else "tvsignin.how_noqr"), color = colors.textSecondary, fontSize = 15.sp,
                lineHeight = 22.sp, textAlign = if (hasQr) TextAlign.Start else TextAlign.Center,
            )
        }
        Spacer(Modifier.height(13.dp))
        Row(
            Modifier.clip(RoundedCornerShape(7.dp)).background(colors.card).padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(str("tvsignin.this_tv").uppercase(), color = colors.textDim, fontSize = 9.sp, letterSpacing = 0.5.sp)
            Text(deviceDisplayName(), color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    } }
    if (s.linkUrl != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        // flex: none in the design — a squashed QR does not scan, so the tile is a fixed square.
        QrCodeImage(s.linkUrl, Modifier.size(222.dp).clip(RoundedCornerShape(10.dp)).testTag(TvSignInTags.QR))
        text()
    } else text()
}

/** FR-5: the code in large type, grouped 3 + 3. */
internal fun groupCode(code: String): String = if (code.length > 3) code.take(3) + " " + code.drop(3) else code
