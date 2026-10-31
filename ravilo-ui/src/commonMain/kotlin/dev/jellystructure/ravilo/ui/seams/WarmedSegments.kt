package dev.jellystructure.ravilo.ui.seams

import io.ktor.utils.io.ByteReadChannel

/**
 * DEB-3171 (DEB-3103 FR-6) — the first segments of a replacement stream, kept where the player reads them. The store fetches them
 * behind the picture to warm the server; without this the player fetched the same bytes again after the engine reset, over the same
 * slow link, inside the hold (a hand-over only happens on a bad link). Android keeps them in a small cache the player's data source
 * reads for that one stream; a miss is just a network read, so nothing here can make a start fail. A no-op elsewhere (the bytes are
 * read and dropped).
 */
expect object WarmedSegments {
    /** A replacement for [masterUrl] is about to be warmed: anything kept for another stream is dropped. */
    fun begin(masterUrl: String)

    /** Reads [body] to its end (never held whole), keeping it as the segment at [segmentUrl] of the stream begun with [begin]. */
    suspend fun keep(segmentUrl: String, body: ByteReadChannel)
}
