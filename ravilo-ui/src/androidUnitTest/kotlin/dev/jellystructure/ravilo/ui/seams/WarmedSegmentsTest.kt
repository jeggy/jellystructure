@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.jellystructure.ravilo.ui.seams

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/** DEB-3171 (DEB-3103 FR-6) — a segment the warm-up read is served to the player from the cache, with no read of the network. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WarmedSegmentsTest {
    private lateinit var dir: File
    private lateinit var cache: SimpleCache
    private val url = "https://example.invalid/hls/v/1/seg0.ts?token=abc"

    @Before fun open() {
        dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "warm-test-${System.nanoTime()}")
        cache = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(8L shl 20), StandaloneDatabaseProvider(ApplicationProvider.getApplicationContext()))
    }

    @After fun close() { cache.release(); dir.deleteRecursively() }

    /** An upstream that fails every open: a read that reaches it went to the network. */
    private val network = DataSource.Factory { FileDataSource().let { _ -> object : DataSource by FileDataSource() {
        override fun open(dataSpec: DataSpec): Long = throw IOException("network read of ${dataSpec.uri}")
    } } }

    private fun reader() = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(network)
        .setCacheWriteDataSinkFactory(null).createDataSource()

    @Test fun `a kept segment is read from the cache whole and never from the network`() {
        val bytes = ByteArray(300_000) { (it % 251).toByte() }   // more than one 16 KB drain chunk
        runBlocking { WarmedSegments.write(cache, url, ByteReadChannel(bytes)) }
        val source = reader()
        source.open(DataSpec(Uri.parse(url)))
        val got = ByteArray(bytes.size + 10)
        var n = 0
        while (true) { val r = source.read(got, n, got.size - n); if (r < 0) break; n += r }
        source.close()
        assertContentEquals(bytes, got.copyOf(n))
    }

    @Test fun `a segment that was not kept goes to the network`() {
        assertFailsWith<IOException> { reader().open(DataSpec(Uri.parse("https://example.invalid/hls/v/1/seg9.ts?token=abc"))) }
    }
}
