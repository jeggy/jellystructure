package dev.jellystructure.ravilo.ui.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R372 (FR-R372-11's desktop companion, amendment 2026-10-12) — a log-out, a shutdown or a `SIGTERM` now reports
 * through a JVM shutdown hook as well as ⌘Q. Both call [DesktopShutdown.runAll], so it has to run its steps once:
 * without the latch an ordinary quit would post its stops and then the hook would post them all again.
 */
class DesktopShutdownOnceTest {

    @Test fun `the steps run once however many ends ask for them`() {
        var film = 0
        var song = 0
        val dropFilm = DesktopShutdown.register { film++ }
        val dropSong = DesktopShutdown.register { song++ }
        try {
            DesktopShutdown.runAll(timeoutMs = 2_000)   // ⌘Q
            DesktopShutdown.runAll(timeoutMs = 2_000)   // then the hook, on the way out
            assertEquals(1, film)
            assertEquals(1, song)
        } finally {
            dropFilm()
            dropSong()
        }
    }
}
