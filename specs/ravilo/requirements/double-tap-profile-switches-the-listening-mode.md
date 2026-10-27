# Double-tap Profile on the phone's bar to switch between Music and Films & series

Task: DEB-2686

> Owner, 2026-10-27 (DEB-1076): *"Can you create a task for the bottom navbar in ravilo mobile view. I want to be
> able to switch between the two modes with a shortcut. Where I can double tap the profile tab at the bottom and it
> will switch between music and film/series mode. Just make the ticket and make sure it will be picked up at some
> point, but it does not have a priority right now."*

## Scope

**Phone only**: the gesture belongs to R267's bottom bar (`RaviloBottomNav`), so it exists wherever that bar is
drawn (the handset layout) and nowhere else.
- **The TV** has no bottom bar.
- **The desktop app and the web app at desktop widths** already have their switch at the head of the sidebar and on
  Ctrl+1 / Ctrl+2 (R337 FR-R337-3).
- **The Cast receiver and Tizen** have no listening mode.

None of those change.

This is a second way to make R326 FR-R326-3's switch. It changes nothing about the Profile page's mode card, and it
lands where the card lands. It amends **R267 FR-R267-9** (re-tapping the page you are on scrolls it to its top) with
one exception, written as a dated amendment in that file.

- **No wire change and no backend change.** The mode is the per-device `ListeningMode` flag (R321 FR-R321-1).
- **No design round.** Nothing new is drawn: the bar, the pill, the card and the toast all exist.
- **Two strings × en/da/fo**, for the accessibility action (FR-6).

**Priority: low** (owner, on the ask).

## Today (read from `main` at `313fc9d3`, 2026-10-27)

- **The bar.** Profile is the last item of both bars (`VIDEO_BAR` and `MUSIC_BAR`, `RaviloBottomNav.kt:158–159`).
  A switch therefore never moves the pill: Profile is index 4 either way.
- **A tap on Profile** (`RaviloApp.kt:~3387`) goes to `Dest.Profile` from any other page. On Profile it bumps
  `reselectTick`, and the page scrolls to its top (`ProfileScreen.kt:131`, `OnReselect`).
- **So a double tap today** is *go to Profile, then scroll Profile to the top* from another page, and two
  scroll-to-tops on Profile. Neither is useful, so nothing a viewer relies on is lost.
- **The mode card** (`RaviloApp.kt:~3048`) is offered only when
  `handset && musicAvailable == true && MusicEngine.supported`. Its `onSwitch` writes `musicMode` and
  `ListeningMode`. `ListeningModeCard` (`MusicCommon.kt:530–532`) shows the toast through `MusicToasts.show`:
  `mode.music_books` or `mode.music` (by `withBooks`), or `mode.video`.
- `switchMode(music)` (`RaviloApp.kt:~1581`) is the **desktop's** switch. It resets to the mode's first page, which
  is R337's rule and not the phone's, so the phone does not call it.

## Requirements

**FR-1 — The gesture.** Two taps on the bar's Profile item close enough together switch the listening mode: to Music
from Films & series, and back. "Close enough" means the second tap comes within the platform's double-tap timeout of
the first (`LocalViewConfiguration.current.doubleTapTimeoutMillis`, which is 300 ms on Android). The gap is measured
between the two taps' click events, on a monotonic clock.

**FR-2 — The single tap is never delayed.** The first tap does what it does today, at once: from another page it
opens Profile, and on Profile it scrolls the page to its top (FR-R267-9). Nothing waits to see whether a second tap
follows. Two things follow from that, and both are intended:
- a double tap from another page lands on Profile and switches;
- a double tap on Profile scrolls it to the top and switches.

**FR-3 — The second tap switches and does nothing else.** It does not bump `reselectTick`. Two rules keep it
predictable:
- **A tap after a switch starts over.** A third tap inside the window counts as a new first tap, so it scrolls to
  the top and does not switch back. A switch always takes two taps of its own.
- **Another item breaks the pair.** A tap on any other bar item between two Profile taps means the next Profile
  tap is a first tap.

**FR-4 — Where it lands: exactly where the mode card lands** (R326 FR-R326-3, owner 2026-09-28):
- the viewer stays on Profile;
- the bar's other four items become the new mode's, and the pill stays on Profile;
- the card's toast names the new mode (`mode.music_books` / `mode.music` / `mode.video`, by the same `withBooks`
  rule);
- the Profile page's mode card re-renders with the new half lit.

**One code path.** The card's switch and the gesture call one function, which writes the mode and shows the toast,
so the two ways can never disagree. Today the toast lives inside `ListeningModeCard`. It moves into that shared
function, or the card's `onSwitch` becomes it.

*Why not land on the new mode's first page:* the ticket first guessed Home / Listen. The owner already chose
"switching stays on Profile" for the card on 2026-09-28, and a shortcut for the same act should not land somewhere
else. Staying on Profile also means a second double tap undoes a mistaken one without moving the viewer.

**FR-5 — Offered exactly when the card is.** The gesture works only while the mode card would be drawn:
`handset && musicAvailable == true && MusicEngine.supported`. That covers a viewer with no music library, a server
whose answer is still unknown (R345), and a platform that cannot play. In those cases a double tap is two ordinary
taps, as today. There is no message and nothing greyed (R321 FR-R321-2: *absent, never greyed*).

**FR-6 — TalkBack.** Under TalkBack a double tap *is* the activate gesture, so FR-1 cannot be reached with it on.
While FR-5 holds, the Profile cell carries a **custom accessibility action**. It does exactly what FR-4 does, and its
label depends on the mode:
- in Films & series mode: `mode.switch_music`, *Switch to music*;
- in Music mode: `mode.switch_video`, *Switch to films & series*.

| key | en | da | fo |
|---|---|---|---|
| `mode.switch_music` | Switch to music | Skift til musik | Skift til tónleik |
| `mode.switch_video` | Switch to films & series | Skift til film og serier | Skift til filmar og seriur |

The da/fo `switch_video` lines are R326 FR-R326-3's drafts. R326 drafted those keys for a row it then decided not to
build, so the keys were never created and are free. The Danish and Faroese are drafts.

**FR-7 — Nothing else changes.** Untouched:
- a single tap on every other item, and FR-R267-9's re-tap on them;
- the Profile page itself;
- the mode card;
- the desktop's switch and its shortcuts;
- the TV.

## Non-goals

- No visible hint, coach mark or onboarding for the gesture. The card stays the visible way to switch.
- No haptic beyond what the platform gives a tap. The toast is the confirmation, as for the card.
- No double tap on any other item.
- No delay-and-disambiguate on the single tap: that would make every Profile tap slower so that a rare one can be
  faster.

## What the code makes cheap

The rule is a small pure object, testable on the JVM with no Compose:

```kotlin
/** DEB-2686 (FR-1, FR-3) — a Profile tap is either a first tap or the second of a double tap. */
class DoubleTapGate(private val timeoutMs: Long) {
    private var firstAt: Long? = null
    /** True when the tap at [nowMs] completes a double tap; the gate is then empty again (FR-3). */
    fun tap(nowMs: Long): Boolean {
        val first = firstAt
        return if (first != null && nowMs - first <= timeoutMs) { firstAt = null; true }
        else { firstAt = nowMs; false }
    }
    /** FR-3 — a tap on any other item breaks the pair. */
    fun reset() { firstAt = null }
}
```

The wiring:
- In `RaviloApp`'s bar `onSelect`, the `PROFILE` branch asks the gate first. A double, while FR-5 holds, calls the
  shared switch. Anything else runs today's branch unchanged.
- Every other branch calls `reset()`.
- The clock is `kotlin.time.TimeSource.Monotonic`.
- `RaviloBottomNav` gains one optional parameter for FR-6's action, a label plus a callback, `null` when FR-5 does
  not hold. It is applied as `semantics { customActions = … }` on the Profile cell only.

## Build order (one engineer; each piece blocked by the one before)

1. **The gesture, the shared switch and the accessibility action** (FR-1 to FR-7). Small, with a JVM-provable core,
   so it is junior-sized.
2. **QA on a phone** after the build reaches a phone, with the plan below.

## Acceptance

**Automated (`:ravilo-ui:desktopTest`, `DoubleTapGateTest`):**
- a single tap is not a double;
- a second tap at the timeout or inside it is a double, and one at timeout + 1 ms is a new first tap;
- a third tap straight after a double is a first tap;
- `reset()` between two taps makes the second a first tap.

**On a phone (QA row), with a viewer who has the music library:**
1. **From Home, double tap Profile.** It lands on Profile and the bar changes to Listen · Browse · Playing · Queue ·
   Profile. The pill stays on Profile, the toast names the mode, and the page's mode card shows Music lit.
2. **Double tap again.** It goes back to Films & series and stays on Profile.
3. **On Profile, scrolled down, single tap Profile.** It scrolls to the top at once, with no visible delay against
   the build before, and no switch.
4. **Triple tap quickly.** It switches once, not twice.
5. **Tap Profile, then Home, then Profile, quickly.** It does not switch.
6. **With TalkBack on**, the Profile item's actions menu offers *Switch to music* (or *Switch to films & series*),
   and choosing it does what step 1 does.
7. **With a viewer who has no music library**, a double tap on Profile is two plain taps, with no toast and no
   switch.
8. **Back after a switch** behaves as it does after a switch made from the card.

## Amendments

None yet.
