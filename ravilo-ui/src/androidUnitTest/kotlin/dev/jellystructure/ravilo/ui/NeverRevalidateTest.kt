package dev.jellystructure.ravilo.ui

import androidx.test.core.app.ApplicationProvider
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.Options
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertNull
import kotlin.test.assertSame

/** DEB-4396 (DEB-4390, FR-4.2) — Coil's read step answers a cached image with the cached image, never with a request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NeverRevalidateTest {
    @Test fun `a cached image is used as it is, never revalidated`() = runBlocking {
        val cached = NetworkResponse(code = 200)
        val read = NeverRevalidateCacheStrategy.read(cached, NetworkRequest(url = "https://x.test/p.png"), Options(ApplicationProvider.getApplicationContext()))
        assertSame(cached, read.response)
        assertNull(read.request)
    }
}
