@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.jellystructure.ravilo.ui.seams

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** DEB-3102 (FR-4) — what a failed load does: the rung below at once for any status, then the ladder's own retries. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LadderLoadErrorPolicyTest {
    private var nowMs = 1_000L
    private val buffer = LadderBuffer { nowMs }
    private val policy = LadderLoadErrorPolicy(buffer)

    private val spec = DataSpec(Uri.parse("https://example.invalid/seg1.ts"))

    private fun error(e: IOException, count: Int = 1) = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo(1, spec, spec.uri, emptyMap(), 0, 0, 0), MediaLoadData(C.DATA_TYPE_MEDIA), e, count,
    )

    private fun status(code: Int) = HttpDataSource.InvalidResponseCodeException(code, "status $code", null, emptyMap(), spec, ByteArray(0))
    private val timeout = HttpDataSource.HttpDataSourceException(SocketTimeoutException("read timed out"), spec, 2002, HttpDataSource.HttpDataSourceException.TYPE_READ)
    private val reset = HttpDataSource.HttpDataSourceException(IOException("Connection reset"), spec, 2001, HttpDataSource.HttpDataSourceException.TYPE_READ)

    /** Three rungs, [excluded] of them already out. */
    private fun rungs(excluded: Int = 0) = LoadErrorHandlingPolicy.FallbackOptions(1, 0, 3, excluded)

    @Test fun `every status and a timeout and a reset fall to the next rung for 15 s`() {
        for (e in listOf(status(400), status(401), status(403), status(404), status(416), status(429), status(500), status(502), status(503), status(504), timeout, reset)) {
            val selection = assertNotNull(policy.getFallbackSelectionFor(rungs(), error(e)), "no fallback for $e")
            assertEquals(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK, selection.type, "$e")
            assertEquals(15_000L, selection.exclusionDurationMs, "$e")
        }
    }

    @Test fun `on the last rung nothing falls and the load is retried`() {
        for (e in listOf(status(404), status(502), status(504), timeout, reset)) {
            assertNull(policy.getFallbackSelectionFor(rungs(excluded = 2), error(e)), "$e")
            assertEquals(500L, policy.getRetryDelayMsFor(error(e, 1)), "$e")
        }
    }

    @Test fun `a parse failure keeps Media3's answer and is not retried`() {
        val parse = ParserException.createForMalformedContainer("bad", null)
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(error(parse)))
    }

    @Test fun `retries wait 0_5 1 2 4 then 5 s`() {
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 5_000L, 5_000L), (1..6).map { policy.getRetryDelayMsFor(error(status(503), it)) })
    }

    @Test fun `a load is retried without limit while the buffer holds picture`() {
        buffer.note(20_000)
        assertEquals(Int.MAX_VALUE, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        nowMs += 19_000   // 1 s left
        assertEquals(Int.MAX_VALUE, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test fun `with the buffer gone only the scheduled retries run`() {
        buffer.note(3_000)
        nowMs += 3_000
        assertEquals(5, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
        buffer.reset()
        assertEquals(5, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA), "a new stream starts with none")
    }
}
