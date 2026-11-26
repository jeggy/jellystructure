package dev.jellystructure.ravilo.screen

import dev.jellystructure.shared.tv.TvApiError

/**
 * DEB-5696 (DEB-5685, FR-60) — what a failed load says and what it offers, Android TV's `LoadError.kt` (`classifyLoadFailure`,
 * `loadErrorTitleKey`, `loadErrorBodyKey`, `loadErrorOffersRetry`) for the causes a Samsung can meet. A 401 or 409 is a sign-in that
 * is gone, so Retry would ask the same thing again; 403 and 404/410 are verdicts; the rest is the network or the server's own trouble.
 */
internal enum class ScreenLoadKind(val titleKey: String, val bodyKey: String?, val action: String) {
    REAUTH("error.load.reauth.title", "error.load.reauth.body", "sign-in"),
    FORBIDDEN("error.load.forbidden.title", "error.load.forbidden.body", "back"),
    GONE("error.load.gone.title", null, "back"),
    UNREACHABLE("error.play.unreachable.title", "error.play.unreachable.body", "retry");

    /** The label key of the one button under the lines. */
    val actionKey get() = when (this) { REAUTH -> "action.sign_in"; FORBIDDEN, GONE -> "action.back"; UNREACHABLE -> "action.retry" }

    companion object {
        /** An HTTP [status] (0 for no answer at all, a timeout included) to the cause it says. */
        fun of(status: Int): ScreenLoadKind = when (status) {
            401, 409 -> REAUTH
            403 -> FORBIDDEN
            404, 410 -> GONE
            else -> UNREACHABLE
        }

        fun of(error: Throwable?): ScreenLoadKind = of((error as? TvApiError.Http)?.status ?: 0)
    }
}
