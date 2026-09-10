package dev.jellystructure.media

import dev.jellystructure.auth.JellyfinMediaStream
import dev.jellystructure.resolver.LanguageResolver

/**
 * Phase 200 (FR-200-6) — the guard on the "scan the directory ourselves" decision: our per-title
 * subtitle language set and Jellyfin's own `MediaStreams` (which the player picker already reads,
 * embedded and external alike) must agree. Report, don't auto-correct — a divergence is a parser bug
 * to fix, not a value to paper over.
 */
object SubtitleReconciliation {

    data class Divergence(
        val jellyfinId: String,
        /** In Jellyfin's `MediaStreams` but missing from our own subtitle language set. */
        val onlyJellyfin: Set<String>,
        /** In our own subtitle language set but absent from Jellyfin's `MediaStreams`. */
        val onlyOurs: Set<String>,
    ) {
        val agrees: Boolean get() = onlyJellyfin.isEmpty() && onlyOurs.isEmpty()
    }

    /** Pure comparison — no network. Both sides are normalized (2-letter ISO 639-1, B/T-agnostic)
     *  before comparing so `fao` ≡ `fo` and `ger`/`deu` ≡ `de` never register as a false divergence. */
    fun compare(jellyfinId: String, ourSubtitleLanguages: Collection<String>, jellyfinStreams: List<JellyfinMediaStream>): Divergence {
        val jellyfinLangs = jellyfinStreams
            .filter { it.type.equals("Subtitle", ignoreCase = true) }
            .mapNotNull { it.language?.takeIf { l -> l.isNotBlank() } }
            .map { LanguageResolver.normalize(it) }
            .toSet()
        val ourLangs = ourSubtitleLanguages.map { LanguageResolver.normalize(it) }.toSet()
        return Divergence(jellyfinId, onlyJellyfin = jellyfinLangs - ourLangs, onlyOurs = ourLangs - jellyfinLangs)
    }
}
