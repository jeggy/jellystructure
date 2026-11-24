package dev.jellystructure.tv

import dev.jellystructure.shared.tv.ClientCapabilities

/**
 * DEB-5506 (DEB-5503, FR-46) — `[ravilo] samsung_direct_play = false` negotiates a Samsung's (`platform = tizen`) start, prepare,
 * prewarm and restream as if its capabilities said `hls_only = true`, which is the negotiation every `.wgt` had before FR-43.
 * Every other platform, and a Samsung with the switch on, keeps what it sent. No wire change: it is read per request.
 */
internal fun capabilitiesForPlatform(caps: ClientCapabilities, platform: String?, samsungDirectPlay: Boolean): ClientCapabilities =
    if (platform == "tizen" && !samsungDirectPlay) caps.copy(hlsOnly = true) else caps
