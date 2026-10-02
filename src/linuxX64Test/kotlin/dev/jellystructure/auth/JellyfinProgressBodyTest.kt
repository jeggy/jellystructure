package dev.jellystructure.auth

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * R357 (FR-R357-2) — the progress body Jellyfin gets. With the player's volume it carries `VolumeLevel` and
 * `IsMuted` (what the dashboard shows as the session's `PlayState`); without, it is byte for byte the body every
 * report had before R357, so an app that predates the phase changes nothing on the Jellyfin side.
 */
class JellyfinProgressBodyTest {

    @Test
    fun withoutAVolumeTheBodyIsUnchanged() {
        assertEquals(
            """{"ItemId":"abc","PositionTicks":420000000,"IsPaused":false,"MediaSourceId":"abc","EventName":"timeupdate","PlaySessionId":"ps1"}""",
            jellyfinProgressBody("abc", 420_000_000L, false, "abc", "ps1"),
        )
        assertEquals(
            """{"ItemId":"abc","PositionTicks":1,"IsPaused":true,"MediaSourceId":"abc","EventName":"timeupdate"}""",
            jellyfinProgressBody("abc", 1L, true, "abc", null, null, null),
        )
    }

    @Test
    fun aVolumeAddsItsTwoFields() {
        assertEquals(
            """{"ItemId":"abc","PositionTicks":1,"IsPaused":false,"MediaSourceId":"abc","EventName":"timeupdate","PlaySessionId":"ps1","VolumeLevel":35,"IsMuted":true}""",
            jellyfinProgressBody("abc", 1L, false, "abc", "ps1", 35, true),
        )
        assertEquals(
            """{"ItemId":"abc","PositionTicks":1,"IsPaused":false,"MediaSourceId":"abc","EventName":"timeupdate","VolumeLevel":35,"IsMuted":false}""",
            jellyfinProgressBody("abc", 1L, false, "abc", null, 35, false),
        )
    }

    @Test
    fun theLevelIsClamped() {
        assertEquals(
            """{"ItemId":"abc","PositionTicks":1,"IsPaused":false,"MediaSourceId":"abc","EventName":"timeupdate","VolumeLevel":100,"IsMuted":false}""",
            jellyfinProgressBody("abc", 1L, false, "abc", null, 250, false),
        )
        assertEquals(
            """{"ItemId":"abc","PositionTicks":1,"IsPaused":false,"MediaSourceId":"abc","EventName":"timeupdate","VolumeLevel":0,"IsMuted":false}""",
            jellyfinProgressBody("abc", 1L, false, "abc", null, -5, false),
        )
    }
}
