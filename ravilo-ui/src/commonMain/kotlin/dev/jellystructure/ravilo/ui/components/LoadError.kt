package dev.jellystructure.ravilo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jellystructure.ravilo.ui.i18n.str
import dev.jellystructure.ravilo.ui.theme.RaviloTheme
import dev.jellystructure.shared.tv.TvApiError

/**
 * R280 (FR-R280-1) — why a load failed, in the only terms that change what the viewer is told or can
 * do. Named [PlayerErrorKind] until R280; the five values were always generic and only the name said
 * otherwise. [GENERIC] is the honest fallback for a failure we could not classify; [UNREACHABLE]
 * means we could not get an answer, or exhausted a retryable failure's budget.
 */
enum class LoadErrorKind { REAUTH, FORBIDDEN, GONE, UNREACHABLE, GENERIC,
    /** R335 (FR-R335-7) — this build has no player engine at all (a Linux without libmpv); not a fault of the stream. */
    PLAYBACK_UNAVAILABLE,
    /** R385 (FR-R385-3) — a Mac running from a copy macOS will not let reach the household's network (`MacDiagnosis`). */
    MAC_INSTALL_PLACE,
    /** R385 (FR-R385-3) — a Mac in place, a server on this network, and nothing answering: the one explanation left. */
    MAC_LOCAL_NETWORK }

/** R335 — set by a platform whose player has no engine, so a failed start says so instead of *Something went wrong*. */
object PlaybackAvailability { var unavailable: Boolean = false }

/** R237 (FR-R237-1) — the verdict on a single failed attempt: can trying again plausibly change it? */
data class FailureClass(
    val retryable: Boolean,
    val kind: LoadErrorKind,
    val status: Int?,
    val retryAfterMs: Long?,
)

/**
 * R237 (FR-R237-1) / R280 (FR-R280-1) — classify before deciding what to say or whether to retry.
 *
 * R237 wrote this for the playback route and it was right there. It was wrong everywhere else for one
 * reason: on that route a session that no longer works is a `409`, so `401` was left to fall through
 * to [GENERIC] — "Something went wrong", offering a Retry. Every other TV route answers `401` for a
 * dead device token (nineteen sites in `TvRoutes.kt` respond `Unauthorized` + `"Not logged in"`), so
 * the single most common real failure in the app rendered the one verdict that is guaranteed not to
 * change, behind the one button that cannot change it.
 *
 * `401` and `409` differ in how the session died, which changes nothing the viewer is told or can do.
 */
fun classifyLoadFailure(t: Throwable?): FailureClass {
    // No HTTP response at all: the transport blip this retry loop was originally written for
    // (an auto-advance across a momentary network drop) — unless the Mac can name the reason (R385).
    val http = t as? TvApiError.Http ?: return answerlessFailure()
    val retryAfterMs = http.retryAfterSeconds?.takeIf { it in 0..60 }?.let { it * 1_000L }
    return when {
        // R280 — this device's session no longer works; signing in again is what fixes it. Not
        // retryable: it is a verdict, and it will be the same verdict for as long as anyone presses.
        http.status == 401 || http.status == 409 -> FailureClass(false, LoadErrorKind.REAUTH, http.status, null)
        http.status == 403 -> FailureClass(false, LoadErrorKind.FORBIDDEN, http.status, null)
        http.status == 404 -> FailureClass(false, LoadErrorKind.GONE, http.status, null)
        // "Busy, try again shortly" — including Phase 182's 503 + Retry-After on gate saturation.
        http.status == 408 || http.status == 429 -> FailureClass(true, LoadErrorKind.UNREACHABLE, http.status, retryAfterMs)
        http.status in 500..599 -> FailureClass(true, LoadErrorKind.UNREACHABLE, http.status, retryAfterMs)
        // Any other 4xx is an answer, not a fault. Retrying it is a delay with a spinner in front of it.
        http.status in 400..499 -> FailureClass(false, LoadErrorKind.GENERIC, http.status, null)
        else -> FailureClass(true, LoadErrorKind.UNREACHABLE, http.status, retryAfterMs)
    }
}

/**
 * R385 (FR-R385-3) — a failure that got no answer at all, which is the only shape either Mac cause refines: an HTTP
 * reply of any status proves the socket, so a reply is never one of them. Off the Mac, and on a Mac whose server is
 * not on this network, this is exactly what it has always been — the transport blip, worth another go.
 */
private fun answerlessFailure(): FailureClass {
    val refined = MacDiagnosis.refinedUnreachable()
        ?: return FailureClass(retryable = true, LoadErrorKind.UNREACHABLE, status = null, retryAfterMs = null)
    // FR-R385-4 — granting the permission is exactly a thing that changes the answer, so that one stays retryable
    // (and the retry loop goes on working across the grant). Moving the app is not something a retry can do.
    val retryable = refined == LoadErrorKind.MAC_LOCAL_NETWORK
    return FailureClass(retryable = retryable, refined, status = null, retryAfterMs = null)
}

/** R280 (FR-R280-2) — the cause alone. Stores that only need to say *what*, not *whether to retry*. */
fun loadErrorKindOf(t: Throwable?): LoadErrorKind = classifyLoadFailure(t).kind

/**
 * R280 (FR-R280-5) — the heading for a cause. Pure, and outside the composable, so the mapping and
 * the existence of every key it names are covered by a test rather than by looking at a screen.
 */
fun loadErrorTitleKey(kind: LoadErrorKind): String = when (kind) {
    LoadErrorKind.REAUTH -> "error.load.reauth.title"
    LoadErrorKind.FORBIDDEN -> "error.load.forbidden.title"
    LoadErrorKind.GONE -> "error.load.gone.title"
    // Reused verbatim: "Couldn't reach the server" is already exactly right for a failed screen load,
    // and a second key saying the same thing is the drift R279 spent a phase removing.
    LoadErrorKind.UNREACHABLE -> "error.play.unreachable.title"
    LoadErrorKind.GENERIC -> "error.generic"
    LoadErrorKind.PLAYBACK_UNAVAILABLE -> "player.unavailable"
    // R385 (FR-R385-4) — the heading names what the viewer must do, never what failed (D3).
    LoadErrorKind.MAC_INSTALL_PLACE -> "mac.install_place.title"
    LoadErrorKind.MAC_LOCAL_NETWORK -> "mac.local_network_blocked.title"
}

/**
 * R280 (FR-R280-5) — the sentence under the heading, or null where there is no honest next step.
 *
 * R385 — [install] is the one cause whose sentence depends on a second fact: a copy macOS made is *a temporary copy*,
 * one the viewer opened inside the mounted image is *the disk image*. It defaults to what the app knows about itself,
 * and is a parameter so the mapping is testable without touching a global.
 */
fun loadErrorBodyKey(kind: LoadErrorKind, install: MacInstallState? = MacDiagnosis.installState): String? = when (kind) {
    LoadErrorKind.REAUTH -> "error.load.reauth.body"
    LoadErrorKind.FORBIDDEN -> "error.load.forbidden.body"
    LoadErrorKind.UNREACHABLE -> "error.play.unreachable.body"
    LoadErrorKind.MAC_INSTALL_PLACE ->
        if (install == MacInstallState.ON_VOLUME) "mac.install_place.body_volume" else "mac.install_place.body_translocated"
    LoadErrorKind.MAC_LOCAL_NETWORK -> "mac.local_network_blocked.body"
    // GONE has no next step, and GENERIC has no honest sentence beyond its heading.
    LoadErrorKind.GONE, LoadErrorKind.GENERIC, LoadErrorKind.PLAYBACK_UNAVAILABLE -> null
}

/**
 * R280 (FR-R280-3) — Retry only where trying again can plausibly change the answer. Offering it for
 * a verdict that is deterministic is the same mistake as R237's retry loop, moved into the viewer's
 * hands: it is why 401 leaving GENERIC matters, since GENERIC is one of the two that offer it.
 */
fun loadErrorOffersRetry(kind: LoadErrorKind): Boolean =
    kind == LoadErrorKind.UNREACHABLE || kind == LoadErrorKind.GENERIC ||
        // R385 (FR-R385-4) — turning the permission on is a thing that changes the answer. Moving the app is not,
        // so MAC_INSTALL_PLACE offers Quit and nothing else: a Retry there is R237's mistake with a nicer heading.
        kind == LoadErrorKind.MAC_LOCAL_NETWORK

/**
 * R280 (FR-R280-3) — one error surface for every failed screen load.
 *
 * Before this, ten render sites drew a store's raw `message`, which is `TvApiError.Http.message` —
 * the HTTP **response body verbatim**. A household with an expired token read `{"error":"Not logged
 * in"}` on their Home screen, in every language. Eight of the ten drew that and nothing else: no
 * Retry, no sign-in, nothing focusable at all, so a D-pad had nowhere to go.
 *
 * Retry is offered only where trying again can plausibly change the answer (FR-R237-3). Something
 * focusable is always drawn and always takes focus — R207's rule, that a failed load is never a dead
 * end, applied to the eight screens it never reached.
 */
@Composable
fun LoadErrorState(
    kind: LoadErrorKind,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onSignIn: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    /**
     * Home's own second action — R237 already treats signing out as Home's case, not every screen's.
     * Receives both requesters so the D-pad chain closes in both directions: this composable wires
     * the primary button's Down to [secondary], and the caller wires its own Up back to [primary].
     */
    extraAction: (@Composable (primary: FocusRequester, secondary: FocusRequester) -> Unit)? = null,
) {
    val colors = RaviloTheme.colors
    val title = loadErrorTitleKey(kind)
    val body = loadErrorBodyKey(kind)
    // FR-R280-3 — the action the cause allows, and only that one. A REAUTH offers the thing that
    // resolves it; a deterministic 403/404 offers the way out rather than a button that re-asks.
    val showRetry = onRetry != null && loadErrorOffersRetry(kind)
    val showSignIn = onSignIn != null && kind == LoadErrorKind.REAUTH
    // R385 (FR-R385-4) — the Mac's two causes bring their own button from the platform: **Quit Ravilo** alone for the
    // one nothing in the app can change, **Open Settings** above the Retry for the one a permission fixes.
    val onQuit = if (kind == LoadErrorKind.MAC_INSTALL_PLACE) MacDiagnosis.quit else null
    val onOpenSettings = if (kind == LoadErrorKind.MAC_LOCAL_NETWORK) MacDiagnosis.openLocalNetworkSettings else null
    // Never a dead end: with no cause-appropriate action, Back is drawn so focus has somewhere to land.
    val showBack = onBack != null && !showRetry && !showSignIn && onQuit == null

    val primaryFR = remember { FocusRequester() }
    val secondaryFR = remember { FocusRequester() }
    // The Mac's Retry under its Open Settings: its own requester, so Home's `extraAction` keeps [secondaryFR].
    val macRetryFR = remember { FocusRequester() }
    LaunchedEffect(kind) { runCatching { primaryFR.requestFocus() } }
    // The chain has to close both ways. The first cut of this drew Home's Sign out with an Up back to
    // Retry and gave Retry no Down — so the second action was reachable only by never leaving it.
    val onDown: (() -> Unit)? = if (extraAction != null) ({ runCatching { secondaryFR.requestFocus() }; Unit }) else null

    Box(modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(str(title), color = colors.text, fontSize = 20.sp, textAlign = TextAlign.Center)
            if (body != null) {
                Spacer(Modifier.height(12.dp))
                Text(str(body), color = colors.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            // FR-R385-4 — the Mac's Retry is the second button, so Open Settings leads and the chain closes onto it.
            val macRetry = onOpenSettings != null && showRetry
            if (showRetry || showSignIn || showBack || onQuit != null || onOpenSettings != null) {
                Spacer(Modifier.height(24.dp))
                when {
                    showSignIn -> RaviloButton(str("action.sign_in"), focusRequester = primaryFR, onDown = onDown, onSelect = onSignIn)
                    onQuit != null -> RaviloButton(str("mac.menu_quit"), focusRequester = primaryFR, onDown = onDown, onSelect = onQuit)
                    onOpenSettings != null -> RaviloButton(
                        str("mac.open_settings"), focusRequester = primaryFR, onSelect = onOpenSettings,
                        onDown = if (macRetry) ({ runCatching { macRetryFR.requestFocus() }; Unit }) else onDown,
                    )
                    showRetry -> RaviloButton(str("action.retry"), focusRequester = primaryFR, onDown = onDown, onSelect = onRetry)
                    else -> RaviloButton(str("action.back"), focusRequester = primaryFR, onDown = onDown, onSelect = onBack)
                }
                if (macRetry) {
                    Spacer(Modifier.height(12.dp))
                    RaviloButton(
                        str("action.retry"), focusRequester = macRetryFR, onSelect = onRetry, onDown = onDown,
                        onUp = { runCatching { primaryFR.requestFocus() }; Unit },
                    )
                }
            }
            if (extraAction != null) {
                Spacer(Modifier.height(12.dp))
                extraAction(primaryFR, secondaryFR)
            }
        }
    }
}
