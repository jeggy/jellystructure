package dev.jellystructure.ravilo.ui.seams

import org.junit.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/** DEB-2509 (DEB-2205 FR-3) — a counter read off ExoPlayer's application thread is not a 0. */
class AppThreadReadTest {
    private val appThread = Executors.newSingleThreadExecutor()
    private val appThreadId = appThread.submit<Long> { Thread.currentThread().id }.get()

    /** Like ExoPlayer's verifyApplicationThread: throws anywhere but the application thread. */
    private val counter: () -> Long = {
        check(Thread.currentThread().id == appThreadId) { "Player is accessed on the wrong thread" }
        15_738L
    }

    private fun reader(timeoutMs: Long = 250, posts: Boolean = true) = AppThreadRead(
        onAppThread = { Thread.currentThread().id == appThreadId },
        runOnAppThread = { r -> if (posts) { appThread.execute(r); true } else false },
        timeoutMs = timeoutMs,
    )

    @Test fun `a read from another thread reaches the application thread and returns the count`() {
        assertEquals(15_738L, reader().read(counter))
    }

    @Test fun `a read on the application thread is direct`() {
        assertEquals(15_738L, appThread.submit<Long> { reader().read(counter) }.get())
    }

    @Test fun `a failed read answers the last good value not zero`() {
        val r = reader()
        assertEquals(15_738L, r.read(counter))
        assertEquals(15_738L, r.read { error("boom") })
    }

    @Test fun `an engine that cannot be posted to answers the last good value`() {
        assertEquals(0L, reader(posts = false).read(counter))
    }

    @Test fun `a read that does not come back in time answers the last good value`() {
        val r = reader(timeoutMs = 20)
        assertEquals(15_738L, r.read(counter))
        assertEquals(15_738L, r.read { Thread.sleep(500); 99L })
    }
}
