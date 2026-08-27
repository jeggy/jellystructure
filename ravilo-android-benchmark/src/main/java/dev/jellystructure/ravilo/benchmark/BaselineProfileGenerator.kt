package dev.jellystructure.ravilo.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * R213 — generates `ravilo-android/src/main/baseline-prof.txt`, replacing the hand-authored wildcard
 * file. Exercises exactly the journey the hand-authored file's own rationale comment named as the
 * hot path: cold start → Home first paint → scroll rows → open a detail screen → back. Run via
 * `./gradlew :ravilo-android:generateBaselineProfile` against a real device/emulator — never a
 * household TV (see the phase spec's "never a household TV" invariant); this session ran it against
 * a personal test device (Pixel 9 Pro), not an emulator (none configured in this environment).
 */
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = "dev.jellystructure.ravilo",
        // 1, not the usual 3 — this runs against a household TV that may be in active use;
        // minimizes how long the journey takes over the screen. Can be raised for a richer profile
        // in a follow-up run.
        maxIterations = 1,
    ) {
        pressHome()
        startActivityAndWait()

        // Home first paint is already captured by startActivityAndWait(); give the feed a moment to
        // load over the network before driving input, same as the manual gfxinfo measurement method
        // documented in ravilo-tv-jank-measurement-2026-06-27.md ("warm-up passes").
        device.waitForIdle()

        // Scroll a few Home rows — the steady-state jank R96-R103 measured.
        repeat(4) {
            device.pressDPadDown()
            device.waitForIdle()
        }
        repeat(4) {
            device.pressDPadUp()
            device.waitForIdle()
        }

        // Open a detail screen from whatever's focused (hero or a tile) and back out — the
        // screen-transition cost ravilo-tv-aot-speed.md notes as the one residual, not-JIT cost.
        device.pressDPadCenter()
        device.wait(Until.hasObject(By.pkg(packageName)), 3_000)
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }
}
