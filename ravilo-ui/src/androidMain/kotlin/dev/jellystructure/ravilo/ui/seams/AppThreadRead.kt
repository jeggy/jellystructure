package dev.jellystructure.ravilo.ui.seams

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * DEB-2509 (DEB-2205 FR-3) — reads a value that ExoPlayer only hands out on its application thread
 * (`getVideoDecoderCounters()` calls `verifyApplicationThread()` and throws anywhere else).
 *
 * The QoE snapshot is taken from PlayerStore's `Dispatchers.Default` coroutines, so a direct read threw, the catch
 * answered 0, and every periodic and stop report carried `rendered_frames = 0`. A caller already on the application
 * thread reads directly; any other posts the read there and waits [timeoutMs]. A read that fails or times out answers
 * the last good value, never 0: a wrong zero is what stops a clean play lifting a learned ceiling.
 */
internal class AppThreadRead(
    private val onAppThread: () -> Boolean,
    private val runOnAppThread: (Runnable) -> Boolean,
    private val timeoutMs: Long = 250,
) {
    @Volatile private var last: Long = 0

    fun read(block: () -> Long): Long {
        if (onAppThread()) return readNow(block)
        var result: Long? = null
        val done = CountDownLatch(1)
        val posted = runOnAppThread(Runnable {
            result = readNow(block)
            done.countDown()
        })
        if (!posted || !done.await(timeoutMs, TimeUnit.MILLISECONDS)) return last
        return result ?: last
    }

    private fun readNow(block: () -> Long): Long = try {
        block().also { last = it }
    } catch (e: Exception) {
        last
    }
}
