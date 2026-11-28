package dev.jellystructure.tv

/**
 * DEB-5531 (FR-279-12) — the line figure of a device: the `observed=<bps>` of its latest song `waiting` report, kept in
 * memory (a restart forgets it). It counts for 30 minutes and only for the client address that sent it; the cap a song
 * start sends Jellyfin is 0.8 × the figure, so a file above it gets the lighter AAC copy.
 */
class SongLineFigure {
    private class Figure(val address: String, val bps: Long, val atMs: Long)
    private val figures = HashMap<String, Figure>()

    /** Records the `observed=` of [waitDetail] (`phase=… pos=… buffered=… observed=… [why=…]`); none there ⇒ nothing recorded. */
    fun record(deviceId: String, clientAddress: String, waitDetail: String?, nowMs: Long) {
        val bps = waitDetail?.split(' ')?.firstOrNull { it.startsWith("observed=") }
            ?.removePrefix("observed=")?.toLongOrNull()?.takeIf { it > 0 } ?: return
        figures[deviceId] = Figure(clientAddress, bps, nowMs)
    }

    /** The `MaxStreamingBitrate` for a song start, or null (no figure, 30 min old or older, another address). */
    fun capFor(deviceId: String, clientAddress: String, nowMs: Long): Long? {
        val f = figures[deviceId] ?: return null
        if (f.address != clientAddress || nowMs - f.atMs >= EXPIRY_MS) return null
        return f.bps * 8 / 10
    }

    companion object { const val EXPIRY_MS = 30 * 60 * 1000L }
}
