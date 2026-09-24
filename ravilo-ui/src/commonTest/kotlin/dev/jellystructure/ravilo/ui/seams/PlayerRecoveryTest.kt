package dev.jellystructure.ravilo.ui.seams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** R292 (FR-R292-11, rung 2) — the recovery seek must MOVE; a same-position seek is ignored by ExoPlayer. */
class PlayerRecoveryTest {
    @Test fun at_the_start_it_steps_forward() = assertEquals(1L, recoverySeekTargetMs(0L))
    @Test fun below_one_second_it_steps_forward() = assertEquals(501L, recoverySeekTargetMs(500L))
    @Test fun otherwise_it_steps_back() = assertEquals(59_999L, recoverySeekTargetMs(60_000L))
    @Test fun it_never_seeks_to_where_the_player_already_is() {
        for (pos in listOf(0L, 1L, 999L, 1_000L, 1_001L, 3_600_000L)) assertNotEquals(pos, recoverySeekTargetMs(pos), "position $pos")
    }
    @Test fun it_never_goes_negative() = assertEquals(1L, recoverySeekTargetMs(0L).coerceAtLeast(0L).let { recoverySeekTargetMs(0L) })
}
