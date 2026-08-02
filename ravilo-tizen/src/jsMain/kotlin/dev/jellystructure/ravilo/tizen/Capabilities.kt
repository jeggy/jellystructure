package dev.jellystructure.ravilo.tizen

import dev.jellystructure.shared.tv.ClientCapabilities

/**
 * R189 — conservative capabilities for a 2016-2018 Tizen TV: H.264/HEVC video (AVPlay handles HEVC
 * hardware-accelerated on this era per the phase spec's research), AAC audio, HLS preferred (AVPlay's
 * strength; a raw MP4 container direct-play is also fine but HLS is the safer default for a
 * transcode-capable path). No Dolby Vision/HDR10+/stereo-beyond-5.1 assumptions — unverified against
 * real hardware, deliberately the safe/narrow starting point rather than an optimistic guess.
 */
fun defaultCapabilities(): ClientCapabilities = ClientCapabilities(
    containers = listOf("mp4", "ts"),
    videoCodecs = listOf("h264", "hevc"),
    audioCodecs = listOf("aac"),
    maxAudioChannels = 6,
    hlsOnly = true,
    supportsHdr10 = false,
    supportsHlg = false,
)
