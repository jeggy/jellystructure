package dev.jellystructure.ravilo.ui.seams

import dev.jellystructure.ravilo.ui.seams.PlayerLifecycleGate.Action
import dev.jellystructure.ravilo.ui.seams.PlayerLifecycleGate.Signal
import kotlin.test.Test
import kotlin.test.assertEquals

/** R292 (FR-R292-1/8) — one Background per transition, one Foreground after it, and no play() after a background. */
class PlayerLifecycleGateTest {
    @Test
    fun `HOME while playing is pause then one background carrying the play intent then one foreground`() {
        val g = PlayerLifecycleGate()
        assertEquals(Action.Pause, g.on(Signal.PAUSE, playing = { true }))
        assertEquals(Action.Background(wasPlaying = true), g.on(Signal.STOP))
        assertEquals(Action.None, g.on(Signal.STOP), "a second STOP is nothing")
        assertEquals(Action.Foreground(), g.on(Signal.START))
        assertEquals(Action.None, g.on(Signal.RESUME), "the engine was released: RESUME never plays after a background")
        assertEquals(Action.None, g.on(Signal.START), "a replayed START is nothing")
    }

    @Test
    fun `an overlay is pause and resume with no background`() {
        val g = PlayerLifecycleGate()
        assertEquals(Action.None, g.on(Signal.START), "the observer's own sync-up START is not a return")
        assertEquals(Action.Pause, g.on(Signal.PAUSE, playing = { true }))
        assertEquals(Action.ResumePlayback, g.on(Signal.RESUME))
        assertEquals(Action.Pause, g.on(Signal.PAUSE, playing = { false }))
        assertEquals(Action.None, g.on(Signal.RESUME), "paused by the viewer stays paused")
    }

    @Test
    fun `standby with no ON_STOP is a background on SCREEN_OFF and a real ON_STOP after it is a no-op`() {
        val g = PlayerLifecycleGate()
        assertEquals(Action.Background(wasPlaying = false), g.on(Signal.SCREEN_OFF, playing = { true }), "FR-R292-8a: standby keeps no play intent")
        assertEquals(Action.None, g.on(Signal.STOP))
        assertEquals(Action.None, g.on(Signal.SCREEN_ON, onScreen = { false }), "screen on while stopped waits for START")
        assertEquals(Action.Foreground(afterStandby = true), g.on(Signal.START))
        assertEquals(Action.None, g.on(Signal.SCREEN_ON, onScreen = { true }))
    }

    @Test
    fun `screen off then on with the activity still started is one background and one foreground`() {
        val g = PlayerLifecycleGate()
        assertEquals(Action.Background(wasPlaying = false), g.on(Signal.SCREEN_OFF, playing = { false }))
        assertEquals(Action.Foreground(afterStandby = true), g.on(Signal.SCREEN_ON, onScreen = { true }))
        assertEquals(Action.None, g.on(Signal.START))
    }

    @Test
    fun `standby after ON_STOP still comes back paused`() {
        // FR-R292-8a: the order the BRAVIA sends is unknown (open question 3), so the screen going off after a
        // background already happened still marks the return as after standby.
        val g = PlayerLifecycleGate()
        assertEquals(Action.Pause, g.on(Signal.PAUSE, playing = { true }))
        assertEquals(Action.Background(wasPlaying = true), g.on(Signal.STOP))
        assertEquals(Action.None, g.on(Signal.SCREEN_OFF, playing = { false }))
        assertEquals(Action.Foreground(afterStandby = true), g.on(Signal.START))
        assertEquals(Action.Pause, g.on(Signal.PAUSE, playing = { true }))
        assertEquals(Action.Background(wasPlaying = true), g.on(Signal.STOP))
        assertEquals(Action.Foreground(afterStandby = false), g.on(Signal.START), "the standby mark is spent on one return")
    }
}
