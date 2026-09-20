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
enum class LoadErrorKind { REAUTH, FORBIDDEN, GONE, UNREACHABLE, GENERIC }

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
    // (an auto-advance across a momentary network drop). Still retryable, exactly as before.
    val http = t as? TvApiError.Http
        ?: return FailureClass(retryable = true, LoadErrorKind.UNREACHABLE, status = null, retryAfterMs = null)
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
}

/** R280 (FR-R280-5) — the sentence under the heading, or null where there is no honest next step. */
fun loadErrorBodyKey(kind: LoadErrorKind): String? = when (kind) {
    LoadErrorKind.REAUTH -> "error.load.reauth.body"
    LoadErrorKind.FORBIDDEN -> "error.load.forbidden.body"
    LoadErrorKind.UNREACHABLE -> "error.play.unreachable.body"
    // GONE has no next step, and GENERIC has no honest sentence beyond its heading.
    LoadErrorKind.GONE, LoadErrorKind.GENERIC -> null
}

/**
 * R280 (FR-R280-3) — Retry only where trying again can plausibly change the answer. Offering it for
 * a verdict that is deterministic is the same mistake as R237's retry loop, moved into the viewer's
 * hands: it is why 401 leaving GENERIC matters, since GENERIC is one of the two that offer it.
 */
fun loadErrorOffersRetry(kind: LoadErrorKind): Boolean =
    kind == LoadErrorKind.UNREACHABLE || kind == LoadErrorKind.GENERIC

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
    // Never a dead end: with no cause-appropriate action, Back is drawn so focus has somewhere to land.
    val showBack = onBack != null && !showRetry && !showSignIn

    val primaryFR = remember { FocusRequester() }
    val secondaryFR = remember { FocusRequester() }
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
            if (showRetry || showSignIn || showBack) {
                Spacer(Modifier.height(24.dp))
                when {
                    showSignIn -> RaviloButton(str("action.sign_in"), focusRequester = primaryFR, onDown = onDown, onSelect = onSignIn)
                    showRetry -> RaviloButton(str("action.retry"), focusRequester = primaryFR, onDown = onDown, onSelect = onRetry)
                    else -> RaviloButton(str("action.back"), focusRequester = primaryFR, onDown = onDown, onSelect = onBack)
                }
            }
            if (extraAction != null) {
                Spacer(Modifier.height(12.dp))
                extraAction(primaryFR, secondaryFR)
            }
        }
    }
}
