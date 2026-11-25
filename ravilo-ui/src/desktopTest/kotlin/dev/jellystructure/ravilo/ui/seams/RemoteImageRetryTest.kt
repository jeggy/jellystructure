package dev.jellystructure.ravilo.ui.seams

import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.asImage
import org.jetbrains.skia.Bitmap
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.serviceLoaderEnabled
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-4396 (DEB-4390, FR-1) — a tile whose load fails twice and then succeeds ends up showing the image with nobody
 * recomposing it from outside: the retry is the tile's own. The network is a fake [Fetcher] that throws `IOException`
 * the first [failFirst] times it is asked, then returns a red image.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class RemoteImageRetryTest {
    private val calls = AtomicInteger()
    private var failFirst = 2

    private inner class FlakyFactory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
            if (calls.incrementAndGet() <= failFirst) throw IOException("the network is not back yet")
            val red = Bitmap().apply { allocN32Pixels(8, 8); erase(0xFFFF0000.toInt()) }
            ImageFetchResult(red.asImage(), false, DataSource.NETWORK)
        }
    }

    @Test fun `a load that fails twice is retried by the tile itself and then draws`() {
        SingletonImageLoader.setUnsafe { ctx ->
            ImageLoader.Builder(ctx).serviceLoaderEnabled(false).components { add(FlakyFactory()) }.build()
        }
        runComposeUiTest {
            setContent {
                RemoteImage(
                    url = "https://artwork.test/p.png", contentDescription = null,
                    modifier = Modifier.testTag("tile").size(40.dp), placeholderColor = Color.Blue,
                )
            }
            fun centre() = onNodeWithTag("tile").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
            waitUntil(timeoutMillis = 30_000) { calls.get() >= 3 && centre().red > 0.9f }
            assertEquals(3, calls.get(), "two failures and the retry that worked — no more loads than that")
            assertTrue(centre().blue < 0.1f, "the placeholder is gone")
        }
    }
}
