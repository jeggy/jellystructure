package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.shared.tv.AudioTrack

/**
 * 313 (found live 2026-10-09, Mac) — the audio a subtitle burn-in / un-burn restream must keep. A single-audio session
 * carries its track (`sessionAudioIndex`); a stream whose audio was switched *inside* it (R291's renditions, a direct
 * play) carries none, and the restream used to ask for the default track — the viewer's DTS pick was lost the moment a
 * PGS subtitle was picked. Then the track the viewer has picked ([selectedAudio], a position in [ticketAudio]) is sent.
 * Kept out of `PlayerScreen`'s body (its widest method sits at the Android register limit).
 */
internal fun restreamAudioIndex(sessionAudioIndex: Int?, ticketAudio: List<AudioTrack>, selectedAudio: Int): Int? =
    sessionAudioIndex ?: ticketAudio.getOrNull(selectedAudio)?.index
