package dev.jellystructure.ravilo.ui.samsungtv

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * DEB-4843 (FR-7) — which TVs a Tizen package's distributor certificate covers, read from its `signature1.xml`. Mirrors the
 * backend's reading (DEB-4842 FR-4): the device ids are `URN:tizen:deviceid=<id>` URIs, each an ASN.1 GeneralName (tag 0x86)
 * with its own length, so exactly the declared bytes are taken.
 */
object WgtSignature {
    private val certificate = Regex("<X509Certificate\\s*>([A-Za-z0-9+/=\\s]+)</X509Certificate>")
    private const val URI = "URN:tizen:deviceid="
    private val duid = Regex("[A-Z0-9]{8,32}")

    /** Null for a malformed signature; an empty set for a valid one that names no device. */
    @OptIn(ExperimentalEncodingApi::class)
    fun deviceIds(signatureXml: String): Set<String>? {
        val encoded = certificate.find(signatureXml)?.groupValues?.get(1)?.filterNot { it.isWhitespace() } ?: return null
        val der = runCatching { Base64.decode(encoded) }.getOrNull() ?: return null
        if (der.size < 4 || der[0] != 0x30.toByte()) return null
        val ids = mutableSetOf<String>()
        for (at in 0 until der.size - 2) {
            if (der[at] != 0x86.toByte()) continue
            val length = der[at + 1].toInt() and 255
            if (length !in (URI.length + 8)..(URI.length + 32) || at + 2 + length > der.size) continue
            val uri = der.copyOfRange(at + 2, at + 2 + length).decodeToString()
            if (uri.startsWith(URI)) uri.removePrefix(URI).takeIf { it.matches(duid) }?.let(ids::add)
        }
        return ids
    }
}
