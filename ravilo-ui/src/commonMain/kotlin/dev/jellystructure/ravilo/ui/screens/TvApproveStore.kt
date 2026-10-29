package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvApiError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Phase 236's alphabet, which FR-2 reuses: no 0, O, 1 or I. */
private const val TV_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
const val TV_CODE_LENGTH = 6

/** DEB-2233 (FR-6) — what a viewer typed or pasted, as the code it names: upper case, every space dropped. */
fun normalizeTvCode(raw: String): String = raw.filterNot { it.isWhitespace() }.uppercase()

/** A normalised code the server could have minted: six characters, all from [TV_CODE_ALPHABET]. */
fun isValidTvCode(code: String): Boolean = code.length == TV_CODE_LENGTH && code.all { it in TV_CODE_ALPHABET }

/** The code as the TV shows it, grouped 3 + 3. */
fun groupTvCode(code: String): String = if (code.length > 3) code.substring(0, 3) + " " + code.substring(3) else code

/**
 * DEB-2233 (FR-6, DEB-2866) — the same 3 + 3 grouping, drawn by the field instead of typed into it. The field's text stays the bare
 * code, so the caret index of a key typed after the fourth is the index of the code and not of a string one space longer.
 */
object TvCodeGrouping : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val code = text.text
        val shown = if (code.length > 3) AnnotatedString(groupTvCode(code)) else text
        return TransformedText(shown, object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = if (code.length > 3 && offset > 3) offset + 1 else offset
            override fun transformedToOriginal(offset: Int) = (if (code.length > 3 && offset > 3) offset - 1 else offset).coerceIn(0, code.length)
        })
    }
}

/** DEB-2233 (FR-6) — the two calls the confirm flow makes. A seam so the state machine runs on a fake. */
interface TvApproveApi {
    suspend fun preview(code: String): String
    suspend fun approve(code: String)
}

class TvClientApproveApi(private val client: TvApiClient) : TvApproveApi {
    override suspend fun preview(code: String) = client.signInPreview(code).deviceName
    override suspend fun approve(code: String) = client.signInApprove(code)
}

/** Why a code did not sign a TV in. Kinds, never a body: nothing here is rendered from what the server said. */
enum class TvApproveFailure { EXPIRED, USED, UNAVAILABLE, UNREACHABLE }

sealed class TvApproveState {
    /** The code field. [code] is what was typed, normalised. [problem] is set after Continue on a code that cannot be one. */
    data class Entering(val code: String = "", val problem: Boolean = false) : TvApproveState()

    /** Reading the TV's name. */
    data class Checking(val code: String) : TvApproveState()

    /** *Sign in on ‹tvName› as ‹name›?* */
    data class Confirm(val code: String, val tvName: String) : TvApproveState()

    /** Signing the TV in. */
    data class Approving(val code: String, val tvName: String) : TvApproveState()

    data class Done(val tvName: String) : TvApproveState()

    /** [step] is where Retry goes back to: the preview, or the approval (the TV's name is known by then). */
    data class Failed(val code: String, val kind: TvApproveFailure, val tvName: String?) : TvApproveState()
}

/** Which failure a thrown error is. 404 is a code that is gone, 409 one that was used; a server that says no for any other
 *  reason (503, 429, 403, 5xx) is [TvApproveFailure.UNAVAILABLE]; anything that is not an answer is [TvApproveFailure.UNREACHABLE]. */
internal fun classifyApproveFailure(e: Throwable): TvApproveFailure = when ((e as? TvApiError.Http)?.status) {
    null -> TvApproveFailure.UNREACHABLE
    404 -> TvApproveFailure.EXPIRED
    409 -> TvApproveFailure.USED
    else -> TvApproveFailure.UNAVAILABLE
}

/**
 * DEB-2233 (FR-6) — *Sign in a TV* on a phone, Ravilo Web or the desktop: a code is typed (or arrives in a link, [start]
 * with a code), the TV is named ([TvApproveApi.preview]), the viewer confirms, and [TvApproveApi.approve] signs it in as them.
 *
 * Whether anyone is signed in at all is not this class's business: the screen is only ever reached with a session, and a
 * link that arrives without one is held by the app until after the ordinary sign-in (DEB-1738 FR-4's mechanism).
 */
class TvApproveStore(private val api: TvApproveApi) {
    private val _state = MutableStateFlow<TvApproveState>(TvApproveState.Entering())
    val state: StateFlow<TvApproveState> = _state.asStateFlow()

    /** The first state: the field empty, or a code that came with the link already checked. */
    suspend fun start(linkCode: String?) {
        val code = linkCode?.let(::normalizeTvCode)
        if (code == null) { _state.value = TvApproveState.Entering(); return }
        check(code)
    }

    fun type(raw: String) {
        _state.value = TvApproveState.Entering(normalizeTvCode(raw).take(TV_CODE_LENGTH))
    }

    /** Continue. */
    suspend fun submit() {
        val s = _state.value as? TvApproveState.Entering ?: return
        if (!isValidTvCode(s.code)) { _state.value = s.copy(problem = true); return }
        check(s.code)
    }

    /** Sign in. */
    suspend fun confirm() {
        val s = _state.value as? TvApproveState.Confirm ?: return
        approve(s.code, s.tvName)
    }

    /** Cancel on the confirm screen, and *Enter a new code* after an outcome: back to the field with nothing typed. */
    fun reset() { _state.value = TvApproveState.Entering() }

    /** Retry after *unavailable* or *could not reach the server*. */
    suspend fun retry() {
        val s = _state.value as? TvApproveState.Failed ?: return
        if (s.tvName != null) approve(s.code, s.tvName) else check(s.code)
    }

    private suspend fun check(code: String) {
        if (!isValidTvCode(code)) { _state.value = TvApproveState.Failed(code, TvApproveFailure.EXPIRED, null); return }
        _state.value = TvApproveState.Checking(code)
        _state.value = try {
            TvApproveState.Confirm(code, api.preview(code))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TvApproveState.Failed(code, classifyApproveFailure(e), null)
        }
    }

    private suspend fun approve(code: String, tvName: String) {
        _state.value = TvApproveState.Approving(code, tvName)
        _state.value = try {
            api.approve(code)
            TvApproveState.Done(tvName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            TvApproveState.Failed(code, classifyApproveFailure(e), tvName)
        }
    }
}

/**
 * DEB-2233 (FR-6, FR-7) — a code that arrived in a link (`/tv?c=` on Ravilo Web, the App Link on the phone) before the app could
 * act on it. A platform writes it with [offer]; the app takes it once it has somewhere to put it.
 */
object TvLinkInbox {
    private val _code = MutableStateFlow<String?>(null)
    val code: StateFlow<String?> = _code.asStateFlow()

    /** Takes anything a link carried; a value that cannot be a code is dropped, so a link with a bad `c` is just the app. */
    fun offer(raw: String?) {
        val code = raw?.let(::normalizeTvCode)
        if (code != null && isValidTvCode(code)) _code.value = code
    }

    /** The code, once: the next call answers null. */
    fun take(): String? { val c = _code.value; _code.value = null; return c }
}

/** The route a held link waits under while the viewer signs in or picks a profile (DEB-1738 FR-4's `heldRoute`). */
internal fun tvLinkRoute(code: String): String = "/account/tv/$code"

/**
 * DEB-2233 (FR-6) — the code in Ravilo Web's address `/tv?c=<code>`, or null for any other address (or a `c` that cannot be one).
 * Only the exact path `/tv` counts: the backend's own copy of the app lives under `/tv/`, where a query means nothing.
 */
fun tvCodeFromLocation(pathname: String, search: String): String? {
    if (pathname != "/tv") return null
    val raw = search.removePrefix("?").split('&').firstOrNull { it.startsWith("c=") }?.removePrefix("c=") ?: return null
    return normalizeTvCode(raw.replace("%20", "").replace("+", "")).takeIf(::isValidTvCode)
}

/** DEB-2875 (FR-6, FR-7) — what to do when a TV link code arrives while the app runs. */
internal sealed class TvLinkAction {
    /** Hold: the stack is empty, or its top is [Dest.Login] or [Dest.ProfilePicker]. */
    internal object Hold : TvLinkAction()
    /** Replace: the top is [Dest.SignInTv]; push a new one over it. */
    internal object Replace : TvLinkAction()
    /** Push: any other viewer page (Home, Browse, Search, Discover, Profile, etc.). */
    internal object Push : TvLinkAction()
    /** Leave it: the top is [Dest.Player]; do nothing. */
    internal object LeaveIt : TvLinkAction()
}

/** DEB-2875 (FR-6, FR-7) — decide what to do with an arriving TV link code given the current stack. */
internal fun tvLinkAction(stack: List<dev.jellystructure.ravilo.ui.Dest>): TvLinkAction {
    val top = stack.lastOrNull() ?: return TvLinkAction.Hold
    return when (top) {
        is dev.jellystructure.ravilo.ui.Dest.Login -> TvLinkAction.Hold
        is dev.jellystructure.ravilo.ui.Dest.ProfilePicker -> TvLinkAction.Hold
        is dev.jellystructure.ravilo.ui.Dest.SignInTv -> TvLinkAction.Replace
        is dev.jellystructure.ravilo.ui.Dest.Player -> TvLinkAction.LeaveIt
        else -> TvLinkAction.Push
    }
}
