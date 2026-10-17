package dev.jellystructure.tv

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CpuArchitecture
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEB-1490 FR-4 — the first-start jellyfin-ffmpeg download names the build for the architecture this binary runs on.
 * CI runs `linuxX64Test` only, so the arm64 asset is checked by the arm64 smoke in publish.yml and, here, by name.
 */
class EncoderFfmpegPinTest {
    @OptIn(ExperimentalNativeApi::class)
    private val arm64 = Platform.cpuArchitecture == CpuArchitecture.ARM64

    @Test
    fun theUrlNamesThePinnedVersionAndThisArchitecturesAsset() {
        val asset = if (arm64) "portable_linuxarm64-gpl" else "portable_linux64-gpl"
        assertEquals(
            "https://github.com/jellyfin/jellyfin-ffmpeg/releases/download/v${Encoder.JELLYFIN_FFMPEG_VERSION}/" +
                "jellyfin-ffmpeg_${Encoder.JELLYFIN_FFMPEG_VERSION}_$asset.tar.xz",
            Encoder.JELLYFIN_FFMPEG_URL,
        )
    }

    @Test
    fun theChecksumIsAPinnedSha256ForThatAsset() {
        assertTrue(Regex("[0-9a-f]{64}").matches(Encoder.JELLYFIN_FFMPEG_SHA256), Encoder.JELLYFIN_FFMPEG_SHA256)
        val pinned = if (arm64) "1bd4fafaf4c309cad896d3479152c31d6b7604d4be8b8526882ea16cc6d5f589"
        else "1fd859927053c44a4f2dbf67ae8b9ba8d29fb3b8930df0dd57d91aa60589363d"
        assertEquals(pinned, Encoder.JELLYFIN_FFMPEG_SHA256)
    }
}
