package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.PairResult
import dev.jellystructure.shared.tv.TvApiClient
import dev.jellystructure.shared.tv.TvSignInCodeResponse
import dev.jellystructure.shared.tv.TvSignInPoll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** DEB-2233 (FR-2, FR-5) — the two open routes a TV signs in through. A seam so the state machine runs on a fake. */
interface TvSignInApi {
    suspend fun code(deviceId: String, deviceName: String?): TvSignInCodeResponse
    suspend fun poll(code: String, secret: String): TvSignInPoll
}

class TvClientSignInApi(private val client: TvApiClient) : TvSignInApi {
    override suspend fun code(deviceId: String, deviceName: String?) = client.signInCode(deviceId, deviceName)
    override suspend fun poll(code: String, secret: String) = client.signInPoll(code, secret)
}

sealed class TvSignInState {
    /** Asking for the first code. */
    data object Loading : TvSignInState()

    /** [approverName] is set while a viewer has the code open on their own device (FR-3's preview). [linkUrl] is null when the server has no web address (FR-1). */
    data class ShowingCode(val code: String, val linkUrl: String?, val approverName: String?) : TvSignInState()

    /** `available: false` — this server cannot sign a TV in with a code; the password form opens directly. */
    data object PasswordOnly : TvSignInState()

    /** The ordinary could-not-reach-the-server state. */
    data object Unreachable : TvSignInState()

    data class SignedIn(val displayName: String) : TvSignInState()
}

/**
 * DEB-2233 (FR-5) — the TV's sign-in: ask for a code, show it, poll every [pollIntervalMs] until a viewer approves it.
 *
 * - 202 `approving` carries the approver's name for *Approving on ‹name›'s phone…*; 202 `waiting` clears it.
 * - 200 is stored through [persist] exactly as a password sign-in stores its `PairResult`.
 * - 401 means the code is no longer any good (expired, or used); a new one is asked for without the screen changing.
 * - A failed request for a code is [TvSignInState.Unreachable]. A failed poll is not, until [MAX_POLL_FAILURES] in a row
 *   (one dropped packet must not replace a code someone is typing on their phone).
 *
 * Nothing runs until [run] is called, and it stops when its coroutine is cancelled — the screen runs it only while the code is showing.
 */
class TvSignInStore(
    private val api: TvSignInApi,
    private val deviceId: () -> String,
    private val deviceName: () -> String?,
    private val persist: (PairResult) -> Unit,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
) {
    private val _state = MutableStateFlow<TvSignInState>(TvSignInState.Loading)
    val state: StateFlow<TvSignInState> = _state.asStateFlow()

    suspend fun run() {
        _state.value = TvSignInState.Loading
        while (true) {
            val minted = try {
                api.code(deviceId(), deviceName())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = TvSignInState.Unreachable
                return
            }
            val code = minted.code
            val secret = minted.secret
            if (!minted.available) { _state.value = TvSignInState.PasswordOnly; return }
            if (code == null || secret == null) { _state.value = TvSignInState.Unreachable; return }
            _state.value = TvSignInState.ShowingCode(code, minted.linkUrl, approverName = null)

            var failures = 0
            polling@ while (true) {
                delay(pollIntervalMs)
                val polled = try {
                    api.poll(code, secret)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (++failures >= MAX_POLL_FAILURES) { _state.value = TvSignInState.Unreachable; return }
                    continue@polling
                }
                failures = 0
                when (polled) {
                    is TvSignInPoll.Waiting -> {
                        val name = if (polled.approving) polled.approverName else null
                        _state.value = TvSignInState.ShowingCode(code, minted.linkUrl, name)
                    }
                    is TvSignInPoll.Done -> {
                        persist(polled.result)
                        _state.value = TvSignInState.SignedIn(polled.result.session.displayName)
                        return
                    }
                    TvSignInPoll.Invalid -> break@polling
                }
            }
        }
    }

    companion object {
        /** FR-5: every 3 s. FR-16 allows 40 a minute per address; this is 20. */
        const val POLL_INTERVAL_MS = 3_000L
        const val MAX_POLL_FAILURES = 5
    }
}
