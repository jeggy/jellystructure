package dev.jellystructure.ravilo.ui.screens

import dev.jellystructure.ravilo.ui.seams.PlayerAudioTrack
import kotlin.test.Test
import kotlin.test.assertEquals

// 2026-09-24 — two picker labels found wrong on the soveværelse TV (Creatures, Ltd.).
class AudioBadgesTest {
    private fun badges(label: String, channels: Int?) =
        audioBadges(PlayerAudioTrack(0, label, "eng", channels), originalLanguage = "en", lang = "en")

    @Test fun eightChannelsIsSurround71NotSurround51() {
        assertEquals(listOf("Surround 7.1"), badges("Engelsk - TrueHD 7.1 Atmos", 8))
    }

    @Test fun sixChannelsStaysSurround51() {
        assertEquals(listOf("Surround 5.1"), badges("Engelsk - AC-3 Dolby Surround EX 5.1", 6))
    }

    @Test fun aNorwegianTitledCommentaryTrackIsBadgedCommentary() {
        assertEquals(listOf("Stereo", "Commentary"), badges("Engelsk - Kommentarspor med regissører og manusforfatter", 2))
    }

    @Test fun commentaryIsRecognisedInTheLibrarysOtherLanguages() {
        for (title in listOf("Kommentar", "Kommentti", "Commentaire du réalisateur", "Comentario", "Commento", "Commentaar"))
            assertEquals(listOf("Commentary"), badges(title, null), title)
    }
}
