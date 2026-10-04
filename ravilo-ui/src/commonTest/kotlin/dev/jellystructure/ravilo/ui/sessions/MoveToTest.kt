package dev.jellystructure.ravilo.ui.sessions

import dev.jellystructure.shared.tv.PlaybackTarget
import dev.jellystructure.shared.tv.SessionOwner
import dev.jellystructure.shared.tv.SessionTarget
import dev.jellystructure.shared.tv.SessionView
import dev.jellystructure.shared.tv.TargetCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals

/** R372 — *Move to…*'s places and every move's 2 s rewind. */
class MoveToTest {
    private fun v(kind: String, targetId: String) = SessionView("s", 1, SessionOwner("u", "Anna"), mine = true, kind = kind,
        target = SessionTarget("cast", targetId, "Office", "speaker"), state = "playing")
    private val tv = PlaybackTarget("tv", "app", "Den TV", "tv", TargetCapabilities(video = true, audio = true, display = true))
    private val speaker = PlaybackTarget("cast:a", "cast", "Office", "speaker", TargetCapabilities(audio = true), castDeviceId = "a")
    private val gone = PlaybackTarget("cast:z", "cast", "Attic", "speaker", TargetCapabilities(audio = true), reachable = false, castDeviceId = "z")

    @Test fun `the current place is ticked and a film lists only video places`() {
        assertEquals(listOf("tv" to false, "cast:a" to true), moveTargets(listOf(tv, speaker, gone), v("music", "cast:a")).map { it.first.id to it.second })
        assertEquals(listOf("tv"), moveTargets(listOf(tv, speaker), v("film", "pixel")).map { it.first.id })
    }

    @Test fun `every move rewinds 2 s and never below 0`() {
        assertEquals(2_888_000, moveStartMs(2_890_000))
        assertEquals(0, moveStartMs(900))
    }
}
