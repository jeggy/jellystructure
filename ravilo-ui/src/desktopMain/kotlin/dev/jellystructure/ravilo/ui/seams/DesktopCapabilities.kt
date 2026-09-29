package dev.jellystructure.ravilo.ui.seams

import java.net.NetworkInterface

/*
 * R328 — what the desktop can decode and how it is connected. Until R329's player, nothing plays, so the codec
 * answers are the conservative base lists and nothing is claimed about HDR.
 */

actual fun supportedAudioCodecs(): List<String> = listOf("aac", "mp3")
actual fun supportsHevcOverHls(): Boolean = false
actual fun supportedVideoCodecs(): List<String> = listOf("h264")
actual fun playsHlsForAirPlay(): Boolean = false
actual fun switchesHlsAudioRenditions(): Boolean = false
actual fun supportsEmbeddedTextSubtitles(): Boolean = false
actual fun warmAudioRendition(index: Int) {}

actual val platformAirPlay: AirPlay? = null

actual fun detectHdrSupport(): HdrSupport = HdrSupport.NONE

/** Phase 185's honest *not measured yet*: the desktop never guesses a decoder ceiling (R329 dev review 10). */
actual fun detectDecoderLimits(): DecoderLimits = DecoderLimits.UNKNOWN

/**
 * FR-R328-3 — wired or Wi-Fi, from the interfaces that are up and carry an address. macOS names Wi-Fi `en0` on a
 * MacBook, so the name alone cannot tell; an interface whose display name says Wi-Fi/wireless/AirPort is Wi-Fi, any
 * other `en*`/`eth*` is wired. No speed is ever claimed (0 = unknown, the "never guess" rule).
 */
actual fun detectLinkState(): LinkState = runCatching {
    val up = NetworkInterface.getNetworkInterfaces().toList().filter { nif ->
        nif.isUp && !nif.isLoopback && !nif.isVirtual && nif.inetAddresses.toList().any { !it.isLinkLocalAddress && !it.isLoopbackAddress }
    }
    fun isWifi(nif: NetworkInterface): Boolean {
        val label = "${nif.name} ${nif.displayName}".lowercase()
        return listOf("wi-fi", "wifi", "wlan", "wireless", "airport", "wlp").any { it in label }
    }
    when {
        up.any(::isWifi) && up.none { !isWifi(it) && (it.name.startsWith("en") || it.name.startsWith("eth")) } -> LinkState("wifi", 0)
        up.any { it.name.startsWith("eth") || (it.name.startsWith("en") && !isWifi(it)) } -> LinkState("ethernet", 0)
        else -> LinkState.UNKNOWN
    }
}.getOrDefault(LinkState.UNKNOWN)
