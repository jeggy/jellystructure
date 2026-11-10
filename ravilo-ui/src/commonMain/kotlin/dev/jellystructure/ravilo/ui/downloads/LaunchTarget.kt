package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.TvApiError

/** Where the app opens (DEB-3995 FR-11). */
enum class LaunchTarget { HOME, DOWNLOADS_OFFLINE }

/**
 * DEB-4072 (DEB-3995 FR-11) — an unreachable launch **with** downloads opens the Downloads page with the offline line, not Home's
 * unreachable state; without downloads it is today's behaviour, unchanged. [onHome] is whether the viewer is still on the launch Home:
 * a viewer who already moved on is never moved again, and neither is one whose server came back (coming back online moves nobody).
 */
fun launchTarget(downloadsSurface: Boolean, serverUnreachable: Boolean, hasDownloads: Boolean, onHome: Boolean): LaunchTarget =
    if (downloadsSurface && serverUnreachable && hasDownloads && onHome) LaunchTarget.DOWNLOADS_OFFLINE else LaunchTarget.HOME

/**
 * Whether a failed call means *the server cannot be reached* (no answer at all, or a gateway that has nothing behind it) rather than an
 * answer. A 401/403/404 is an answer: the server is there.
 */
fun isServerUnreachable(t: Throwable?): Boolean = t != null && (t !is TvApiError.Http || t.status in 502..504)
