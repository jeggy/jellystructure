package dev.jellystructure.auth

/**
 * Security fix (2026-08-02 review, finding L5) — a plain `a != b` comparison on a secret short-
 * circuits at the first mismatched character, which leaks a (small, but nonzero) timing signal an
 * attacker could in principle use to recover a secret byte-by-byte. Used for the *arr webhook secret
 * compare (`WebhookRoutes.kt`); session/device tokens are compared via a SQLite index lookup instead
 * (B-tree timing, not a simple linear scan) and API keys via their SHA-256 hash, so this is the one
 * remaining plain-string secret comparison in the codebase.
 */
fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}

// Phase 111 — a small, dependency-free SHA-256 (FIPS 180-4) for API-key hashing: the plaintext key is
// shown once at creation and never stored; only this hash is persisted, so a DB leak alone can't be
// used to authenticate as an API key. No existing crypto dependency in this Kotlin/Native target
// (Curl/libcurl isn't exposed for hashing), so this is a plain-Kotlin implementation rather than a new
// cinterop surface.
private val K = uintArrayOf(
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
    0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
    0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
    0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
    0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
    0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
)

private fun UInt.rotr(n: Int): UInt = (this shr n) or (this shl (32 - n))

/** Returns the SHA-256 digest of [input] (UTF-8) as a lowercase hex string. */
fun sha256Hex(input: String): String {
    val msg = input.encodeToByteArray()
    val bitLen = msg.size.toULong() * 8u

    // Pad: 0x80, then zeros until length ≡ 56 mod 64, then the 8-byte big-endian bit length.
    val padded = ArrayList<Byte>(msg.size + 72)
    padded.addAll(msg.toList())
    padded.add(0x80.toByte())
    while (padded.size % 64 != 56) padded.add(0)
    for (i in 7 downTo 0) padded.add(((bitLen shr (i * 8)) and 0xFFu).toByte())

    var h0 = 0x6a09e667u; var h1 = 0xbb67ae85u; var h2 = 0x3c6ef372u; var h3 = 0xa54ff53au
    var h4 = 0x510e527fu; var h5 = 0x9b05688cu; var h6 = 0x1f83d9abu; var h7 = 0x5be0cd19u

    val w = UIntArray(64)
    var offset = 0
    while (offset < padded.size) {
        for (t in 0 until 16) {
            val base = offset + t * 4
            w[t] = (padded[base].toUInt() and 0xFFu shl 24) or
                (padded[base + 1].toUInt() and 0xFFu shl 16) or
                (padded[base + 2].toUInt() and 0xFFu shl 8) or
                (padded[base + 3].toUInt() and 0xFFu)
        }
        for (t in 16 until 64) {
            val s0 = w[t - 15].rotr(7) xor w[t - 15].rotr(18) xor (w[t - 15] shr 3)
            val s1 = w[t - 2].rotr(17) xor w[t - 2].rotr(19) xor (w[t - 2] shr 10)
            w[t] = w[t - 16] + s0 + w[t - 7] + s1
        }

        var a = h0; var b = h1; var c = h2; var d = h3
        var e = h4; var f = h5; var g = h6; var h = h7

        for (t in 0 until 64) {
            val s1 = e.rotr(6) xor e.rotr(11) xor e.rotr(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + K[t] + w[t]
            val s0 = a.rotr(2) xor a.rotr(13) xor a.rotr(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj

            h = g; g = f; f = e; e = d + temp1
            d = c; c = b; b = a; a = temp1 + temp2
        }

        h0 += a; h1 += b; h2 += c; h3 += d; h4 += e; h5 += f; h6 += g; h7 += h
        offset += 64
    }

    return listOf(h0, h1, h2, h3, h4, h5, h6, h7).joinToString("") { it.toString(16).padStart(8, '0') }
}
