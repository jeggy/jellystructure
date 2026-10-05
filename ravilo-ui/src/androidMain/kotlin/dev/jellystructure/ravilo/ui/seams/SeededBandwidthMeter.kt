package dev.jellystructure.ravilo.ui.seams

import android.os.Handler
import android.os.Looper
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import kotlin.concurrent.Volatile

/**
 * 308 (FR-308-3) — Media3's app-wide [DefaultBandwidthMeter], answering with a seed until it has measured this stream.
 *
 * Media3's adaptive selection picks a stream's first variant from the meter's estimate, and an untouched meter starts
 * from a table keyed by network type and country — a guess about where the phone is, which the owner ruled out. The
 * seed is what this device itself measured on its recent HLS plays (the ticket's `measured_bandwidth_bps`); after
 * [SAMPLES_TO_TRUST] fresh samples on this stream the meter's own live estimate answers again, and every later choice
 * — down before the buffer runs dry, up when the throughput holds — is Media3's own, from the live measurement.
 */
internal class SeededBandwidthMeter(private val inner: DefaultBandwidthMeter) : BandwidthMeter {
    @Volatile private var seed: Long? = null
    @Volatile private var samplesSinceSeed = 0

    private val counter = BandwidthMeter.EventListener { _, _, _ ->
        if (seed != null && ++samplesSinceSeed >= SAMPLES_TO_TRUST) seed = null
    }

    init { inner.addEventListener(Handler(Looper.getMainLooper()), counter) }

    /** Seeds the next stream's first choice; null leaves the meter's own estimate. */
    fun seed(bps: Long?) {
        samplesSinceSeed = 0
        seed = bps?.takeIf { it > 0 }
    }

    override fun getBitrateEstimate(): Long = seed ?: inner.bitrateEstimate
    override fun getTimeToFirstByteEstimateUs(): Long = inner.timeToFirstByteEstimateUs
    override fun getTransferListener(): TransferListener? = inner.transferListener
    override fun addEventListener(eventHandler: Handler, eventListener: BandwidthMeter.EventListener) = inner.addEventListener(eventHandler, eventListener)
    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) = inner.removeEventListener(eventListener)

    companion object {
        /** Two segments' worth: one short playlist or a first segment alone is too thin to overrule a measurement. */
        private const val SAMPLES_TO_TRUST = 2

        @Volatile private var instance: SeededBandwidthMeter? = null

        /** One for the app, like the meter it wraps (its listener on that meter is registered once). */
        fun shared(ctx: android.content.Context): SeededBandwidthMeter =
            instance ?: SeededBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(ctx)).also { instance = it }
    }
}
