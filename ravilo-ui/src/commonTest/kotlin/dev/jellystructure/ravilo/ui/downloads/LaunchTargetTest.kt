package dev.jellystructure.ravilo.ui.downloads

import dev.jellystructure.shared.tv.TvApiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEB-4072 (DEB-3995 FR-11) — where the app opens when the server cannot be reached. */
class LaunchTargetTest {
    @Test fun `unreachable with downloads opens the Downloads page`() =
        assertEquals(LaunchTarget.DOWNLOADS_OFFLINE, launchTarget(downloadsSurface = true, serverUnreachable = true, hasDownloads = true, onHome = true))

    @Test fun `unreachable without downloads is today's behaviour`() =
        assertEquals(LaunchTarget.HOME, launchTarget(true, serverUnreachable = true, hasDownloads = false, onHome = true))

    @Test fun `a reachable server never moves the viewer`() =
        assertEquals(LaunchTarget.HOME, launchTarget(true, serverUnreachable = false, hasDownloads = true, onHome = true))

    @Test fun `a viewer who already moved on is not moved again`() =
        assertEquals(LaunchTarget.HOME, launchTarget(true, serverUnreachable = true, hasDownloads = true, onHome = false))

    @Test fun `a device with no download surface keeps Home`() =
        assertEquals(LaunchTarget.HOME, launchTarget(downloadsSurface = false, serverUnreachable = true, hasDownloads = true, onHome = true))

    @Test fun `no answer and a bad gateway are unreachable, an answer is not`() {
        assertTrue(isServerUnreachable(RuntimeException("UnknownHost")))
        assertTrue(isServerUnreachable(TvApiError.Http(502, "")))
        assertTrue(isServerUnreachable(TvApiError.Http(504, "")))
        assertFalse(isServerUnreachable(TvApiError.Http(401, "")))
        assertFalse(isServerUnreachable(TvApiError.Http(404, "")))
        assertFalse(isServerUnreachable(null))
    }
}
