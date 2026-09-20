# Phase R276 — Give the brightness back when the player closes

> Owner report, Pixel 9, 2026-09-20: after playing something and swiping the player's brightness, the
> phone's own brightness slider in the notification shade stops working and says the brightness *is
> being controlled by an app*. Leaving the player does not give it back.

## Status

`✓ Built` — design-authored 2026-09-20 from an owner report on a Pixel 9 Pro, **built and measured on
that Pixel the same day**. Not dev-reviewed.

### Build (2026-09-20)

One `DisposableEffect` in `HandsetPlayerControlsAndroid.kt`, keyed on the activity's window, setting
`screenBrightness` back to `WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE` on dispose. Nothing
else changed; the web actual was not touched.

### Measured on the Pixel 9 Pro, 2026-09-20 (debug)

`dumpsys display`'s `mWindowManagerBrightnessOverride` is the ground truth, and it reads:

- `NaN` before playing — no override;
- **`0.02`** after a left-half swipe down inside the player — the override the owner was stuck with;
- **`NaN`** again after leaving the player with Back.

Acceptance 1, 2 and 4 pass. Acceptance 3 (leaving by casting from inside the player) was not run —
no cast was available — but the release is tied to composition, not to an exit path, so it cannot be
specific to Back.

## Context

R244 FR-R244-5's whole point was that the player's brightness is scoped to **the player's window**
and never writes the device default — and `HandsetPlayerControlsAndroid.kt` does exactly that: it
sets `window.attributes.screenBrightness` and nothing else. What it never does is **clear** it.

Ravilo is a single-Activity app. A window override set while the player was on screen therefore
outlives the player, the Home screen, and every screen after it, for the rest of the process. Android
shows the shade's slider as app-controlled for as long as any such override is set, which is exactly
what the owner saw — and from the viewer's side it is worse than a stuck slider, because the phone is
now sitting at whatever level a swipe left in a dark room, on every other screen.

The bug is not that the override exists. It is that its **lifetime** was never stated: the seam hands
out a setter and nobody owns the undo.

## Functional requirements

### FR-R276-1 — the override lives exactly as long as the player does

The window's `screenBrightness` returns to `BRIGHTNESS_OVERRIDE_NONE` when the composable that
acquired the controls leaves composition — `PlayerScreen`'s handset branch, the only caller. After
that the phone is back on its own automatic/system level and the shade's slider is the slider again.

It is released on **every** way out, because it is tied to composition and not to an exit path: Back,
the platform Back gesture, auto-advance ending the session, casting from inside the player
(`replaceTop` to the remote), a re-auth reset, or the process being torn down mid-playback.

### FR-R276-2 — the release is the seam's own business

The reset belongs in the Android actual beside the setter, not at the call site. `PlayerScreen` asks
for controls; it should not also have to remember to hand a window attribute back, and a second
future caller must not be able to forget. The web actual has nothing to release — it declines
brightness entirely (`null` setter) — and keeps its current shape.

### FR-R276-3 — nothing about the gesture changes

The swipe, its range (`0.02f..1f`), its read-back of the current level, and the fact that the device
default is never written all stand exactly as R244 FR-R244-5 specified. This phase adds an undo and
nothing else.

## Non-goals

- Remembering the chosen brightness for the next playback. A level chosen for one film in one room is
  not a preference, and R244 deliberately made this a per-session gesture.
- Anything about volume: `setStreamVolume` writes the device's real media volume, which is what a
  volume control is supposed to do and is not an override that needs releasing.

## Acceptance

On a Pixel 9 (debug build):

1. Play something, swipe the left half down to darken the picture, press Back to leave the player.
   Pull down the shade: the brightness slider works and carries no "controlled by an app" notice.
2. The screen's brightness visibly returns to the phone's own level on leaving, not on the next
   unlock.
3. Repeat, leaving by casting from inside the player instead of by Back: same result.
4. Re-enter the player: the swipe still works, and starts from the phone's current level.
