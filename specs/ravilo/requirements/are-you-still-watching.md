# Ravilo asks "Are you still watching?" before it plays on for nobody

Task: DEB-5843

> Owner, on DEB-5687: *"Let's try to find UI/UX improvements we could make to Ravilo. We want to make the app beautiful
> in our edge cases, but remember that QoE and performence is most important. We are also now getting more and more
> users."* — and on 2026-11-26 he picked this piece on the card *"Ravilo polish: what to build"*.

## Scope

A viewer who falls asleep during a series is asked, after a few episodes nobody touched, whether they are still
watching, and the next episode waits for the answer. Three separate players carry it, and each is specified on its
own: the `ravilo-ui` player (Android TV, phone, Ravilo Web, desktop Mac and Linux), the Samsung app (`ravilo-screen`)
and the Chromecast receiver (`ravilo-cast`).

- **In:** every series auto-advance, including a shuffle (R343) and a server hand-over queue (R370).
- **Out:** films (nothing auto-advances after a film), music, radio, audiobooks, Live TV, trailers. Autoplay off
  (R182 FR-RV-SKIP1-3): nothing advances, so nothing asks.
- **No server change.** No route, no schema, no config field. One additive field travels receiver to phone, inside the
  Cast channel (FR-7).

It amends three shipped specs, each with a dated note in its own file: R182 (the app's countdown), the Samsung app
(FR-48) and R384 (FR-R384-3, the receiver's countdown).

## Why

- Today, when the countdown runs out with autoplay on, the next episode always starts:
  `PlayerScreenState.kt:1161` (`advanceNext()`), Samsung `Screen.kt:951` (`playNext()`), Chromecast
  `Receiver.kt:1826` (`loadNext()`) and `Receiver.kt:1885` at the end of the file. A whole season plays to an empty room.
- On our encoder that is a running job all night. A paused player stops asking for segments, and the job is stopped
  after 60 s idle (`EncoderJobs.kt:44`, `IDLE_MS` at `:564`). So asking, and pausing while we ask, frees the encoder.
- Android holds the screen on for the player's whole life (`PlayerImmersiveEffect.kt:54`). A paused ask left open all
  night would keep the TV lit, so an unanswered ask must also close (FR-4).
- Netflix asks *"to keep you from losing your place or using data"*. On TVs and TV devices: *"after 3 episodes and 90
  minutes of uninterrupted watching"*. Elsewhere: *"after watching 3 episodes of a TV show in a row without using any
  video player controls"* ([Netflix help](https://help.netflix.com/en/node/114059), read 2026-11-27). We follow that
  rule.

## Requirements

### FR-1 — One rule, in `:shared`
A new `StillWatching` rule in `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/` (next to `NextUpTrigger.kt`)
decides whether an auto-advance is allowed or must ask. All three players call it.
- It counts **unattended auto-advances**: next episodes started by a countdown running out, since the viewer's last
  input (FR-2).
- **The ask is due** when this auto-advance would be the 3rd unattended one (episodes 1, 2 and 3 played; the 4th asks).
- **On a TV-class player** (Android TV, Samsung, Chromecast) it is also required that at least 90 minutes have passed
  since the last input. Three short episodes in 40 minutes do not ask on a TV.
- On phone, Ravilo Web and desktop, the episode count alone decides.
- A viewer's input sets the count to 0 and the clock to now. Starting a play by hand (Play, a rail pick, a cast or a
  hand-over from another device) is input.
- Constants: `STILL_WATCHING_EPISODES = 3`, `STILL_WATCHING_TV_MS = 90 min`, `STILL_WATCHING_CLOSE_MS = 30 min`.
  No setting in this spec.

### FR-2 — What counts as input
Any of these resets the count:
- **`ravilo-ui`:** any key press reaching the player (D-pad, Select, Back, a media key, Space), including one that only
  raises the chrome; a touch or a tap; a mouse click or wheel. A remote command through `RemoteVideoCommands`
  (`PlayerScreen.kt:518`: a phone remote, the Jellyfin dashboard, Home Assistant) counts, because a person sent it.
  Mouse movement alone does not count (a bumped desk is not a viewer).
- **Samsung:** any key in `onKey` (`Screen.kt:1490`) and any phone command in `onPlaystateCommand` (`Screen.kt:802`)
  or `onPlayerCommand` (`Screen.kt:820`).
- **Chromecast:** any sender command in `onCommand` (`Receiver.kt:1948`), and any PLAY, PAUSE or SEEK request the Cast
  framework hands the receiver (the TV remote reaches it this way over HDMI-CEC).

**Performance:** the input hook is one write of a plain `Long` and an `Int`. It is not Compose state, nothing reads it
while composing, and it never consumes the key. On `ravilo-ui` it sits as an `onPreviewKeyEvent` returning `false` on
the player's root, so TV focus motion and the existing key handling (`FocusModifiers.kt:181`) are unchanged. The rule
runs only when a countdown ends, never on the start path or on each poll tick.

### FR-3 — The ask, in the `ravilo-ui` player
When the next-up countdown runs out (`nextUpCountdown`, `PlayerScreenState.kt:1151`) and FR-1 says the ask is due:
- The next episode **does not start**. The current item **pauses** where it is (if it already ended under the
  countdown, R384, there is nothing to pause).
- The credits card stays and changes to a new `CreditsCardMode.STILL_WATCHING`: kicker `player.still_watching`, the
  same next-episode line as *Up next*, no countdown ring. Two buttons: **Continue watching**
  (`player.continue_watching`, focused) and **Back** (`player.back`). Never more than two (R182).
- **Continue watching**, Select on it, the play key or `MediaKey.NEXT`: `advanceNext()` as today. The episode left is
  marked watched by R347's rule, as today.
- **Back**, or the Back key: what Back does in the player today (`playerBack`).
- Other keys move focus between the two buttons and do not dismiss the card.
- On a phone the same card is used inside the handset chrome; a tap on either button answers.
- The count lives for the app run, not per screen: it must survive `replaceTop` from one episode to the next
  (`RaviloApp.kt:3146`). It is not saved and not sent anywhere.

### FR-4 — An unanswered ask closes the player
If nobody answers the ask within 30 minutes, the player closes as Back would: R372's end of play runs once (the
heartbeat stops), the item is marked watched if R347 calls it finished, and the app lands on the page below. The
screen hold is released with the player, so the device may sleep by its own rules. Samsung goes to what its Back does;
Chromecast to its idle view (`endedHere`, `Receiver.kt:1846`).

### FR-5 — The ask on Samsung (`ravilo-screen`)
Where the countdown would call `playNext()` (`Screen.kt:951`), with FR-1 due:
- Pause the stream; keep the card (`#nextup`) up in a new `CreditsMode.ASK`: `nu-k` = `player.still_watching`, `nu-t`
  the next episode's line, `nu-c` empty, `nu-p` = `player.continue_watching`, `nu-w` = `player.back`. Focus on `nu-p`.
- Enter on `nu-p`, or the phone's `next` / `nextup_play` / `Unpause`: `playNext()`. Enter on `nu-w`, Back, or the
  phone's `cancel_next_up` / `nextup_cancel`: what Back does in the player today. `Stop` stops, as today (`Screen.kt:810`).
- It is TV-class: the 90-minute clock applies. The status sent to a phone is unchanged: the phone sees a paused
  episode, and its play button continues.
- FR-4 applies.

### FR-6 — The ask on the Chromecast receiver (`ravilo-cast`)
Where the countdown ends (`startNextUp`, `Receiver.kt:1810-1828`, also when the film finished under it,
`Receiver.kt:1885`), with FR-1 due:
- Do not call `loadNext()`. Pause the item if it still plays. Keep the `#nextup` card with `nu-k` =
  `player.still_watching`, `nu-t` = the next title, and `nu-c` = `receiver.still_watching_hint`. The card has no
  buttons, as today (`cast-receiver/index.html`).
- A PLAY from any sender or the TV remote, or a sender's `next` / `nextup_play`, calls `loadNext()`. A sender's stop
  stops, as today. `cancel_next_up` ends at `endedHere()`.
- It is TV-class. FR-4 applies.

### FR-7 — A phone casting sees the ask (additive)
`CastReceiverMessage` gains `@SerialName("still_watching") val stillWatching: Boolean? = null`, set `true` on the
`status` sent while the ask is up. A phone that knows it shows the kicker `player.still_watching` and a
**Continue watching** button (sends `nextup_play`) in place of the countdown line on its cast remote
(`CastStatusMerge.kt:128`). **An old phone** ignores the unknown field (`RaviloWireJsonWithDefaults` ignores unknown
keys) and shows a paused episode; its play button continues (FR-6). An old receiver never sends the field.

### FR-8 — Viewer text
New keys in `i18n/en.json`, with Danish and Faroese added in the same commit, each noted *"DEB-5843 — draft, review
before release"*, following the repo's existing draft-note pattern:

| Key | English | Danish (draft) | Faroese (draft) |
|---|---|---|---|
| `player.still_watching` | Are you still watching? | Ser du stadig med? | Hyggur tú enn? |
| `player.continue_watching` | Continue watching | Fortsæt med at se | Halt fram at síggja |
| `receiver.still_watching_hint` | Press play to keep watching | Tryk på afspil for at se videre | Trýst spæl fyri at halda fram at hyggja |

*Back* reuses `player.back`. The Danish and Faroese forms follow `row.continue` and `radio.tune_in_again`.

## Design

No round. All three players reuse their existing credits card with a third text state: two buttons where the card has
buttons today, none on Chromecast. No new layout, colour or motion.

## What this does not do yet

- No setting to turn it off or change three episodes. If viewers ask, that is a new spec on R182's settings lane.
- The phone remote of a Samsung TV or Android TV does not show the ask; it shows a paused episode (FR-5).
- Nothing asks during a long film or a Live TV channel.
- No ask is drawn before the first auto-advance, whatever the hour.

## Acceptance

1. Android TV, autoplay on: start episode 1 and touch nothing. Episodes 2 and 3 start by themselves. At the end of 3,
   provided 90 minutes have passed, the card reads *Are you still watching?*, the picture is paused, and within 60 s
   our encoder has no job for the play.
2. The same, with one D-pad press during episode 3: episode 4 starts by itself; the ask comes three episodes later.
3. Phone: three 20-minute episodes untouched; the ask comes at the end of the 3rd (no 90-minute rule).
4. *Continue watching* starts the next episode at once; *Back* lands on the series page; the count is 0 after either.
5. Ask left alone 30 minutes: the player closes, the session row ends, and the screen may sleep.
6. Samsung and Chromecast: 1, 4 and 5 hold, and a play from the casting phone continues; an old phone sees a paused
   episode.
7. Autoplay off, a film, music and radio: nothing changes.
8. Start time and TV focus motion are unchanged (FR-2's hook is outside composition).

## Build order

One engineer. Each slice ≤ ~200 changed lines.

1. **FR-1, the rule.** `shared/.../tv/StillWatching.kt`, `shared/src/commonTest/.../tv/StillWatchingTest.kt`
   (count, the TV clock, reset, the 3rd advance). Proof:
   `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :shared:desktopTest --tests '*StillWatching*' --console=plain`.
   **Junior-ready.**
2. **FR-2 and FR-8, `ravilo-ui` input and strings.** An app-run `StillWatchingTracker` held in `RaviloApp.kt`, the root
   `onPreviewKeyEvent` and touch hook in `PlayerScreen.kt`, `RemoteVideoCommands`, the three keys in `i18n/*.json`.
   Proof: `:ravilo-ui:desktopTest --tests '*StillWatchingInput*'` (a key, a tap and a remote command reset it; a pointer
   move does not; the count survives an advance), and `scripts/check-ravilo-strings.sh`.
3. **FR-3 and FR-4, the app's ask.** `PlayerScreenState.kt` (`nextUpCountdown`, a new card mode, the 30-minute close),
   `PlayerScreen.kt` (`NextUpCard` text). Proof: `:ravilo-ui:desktopTest --tests '*StillWatchingPlayer*'`: the decisions as pure
   functions, in the style of `PlayerEndClosesTest` (ask due ⇒ pause, no advance; Continue advances; Back closes;
   30 min ⇒ close once).
4. **FR-5, Samsung.** `ravilo-screen/.../Screen.kt`. Proof: `:ravilo-screen:compileKotlinJs` and a new case in a
   `tests/e2e/ravilo-screen-*.spec.ts` with a short virtual clock. Device check on a QA row.
5. **FR-6, Chromecast receiver.** `ravilo-cast/.../Receiver.kt`. Proof: `:ravilo-cast:compileKotlinJs` and a case in
   `tests/e2e/cast-receiver.spec.ts`. Device check on a QA row.
6. **FR-7, the phone's cast remote.** `shared/.../tv/CastMessages.kt`, `ravilo-ui/.../seams/CastStatusMerge.kt`, the
   cast remote's line. Proof: `:ravilo-ui:desktopTest --tests '*CastStatusMerge*'` (field set ⇒ ask shown; absent ⇒
   today's state) and the wire contract test. Follows slice 5.

Slices 2–6 follow slice 1. Slice 3 follows slice 2.
