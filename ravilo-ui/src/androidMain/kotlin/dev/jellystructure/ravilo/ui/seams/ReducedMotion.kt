package dev.jellystructure.ravilo.ui.seams

import android.animation.ValueAnimator
import android.os.Build
import android.provider.Settings
import dev.jellystructure.ravilo.ui.RaviloAppContext

/**
 * `ValueAnimator.areAnimatorsEnabled()` (API 26+) is the same signal Android TV's own launcher and
 * every system animation respect for the "Remove animations" accessibility toggle and for Developer
 * options' animator duration scale — it's already false whenever a viewer (or an automated test
 * harness) has turned system animations off, no separate opt-in needed. Below API 26, fall back to
 * reading `Settings.Global.ANIMATOR_DURATION_SCALE` directly (0 == disabled), the same value that
 * API on top of it.
 */
actual fun systemPrefersReducedMotion(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        !ValueAnimator.areAnimatorsEnabled()
    } else {
        runCatching {
            Settings.Global.getFloat(RaviloAppContext.get().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
