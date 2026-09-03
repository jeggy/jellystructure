# Phase R230 — Fully disable Skip Intro / Skip Credits (Off means never shown)

> **R182**'s "Skip intros" / "Skip credits" settings each offer **Off / Prompt / Auto**. Skip Intro's
> Off already fully suppresses the pill. **Skip Credits' Off does nothing at all** — the resolved
> setting is read into client state and never consulted; the credits/Next-Episode card appears on
> every title regardless of what's configured. This phase closes that gap: Off must mean the popup is
> **never shown**, not just "don't auto-click it."

## Status
Implemented (2026-09-03). Not yet dev-reviewed, not yet on-device/live verified (no restart/deploy
granted this session — [[feedback-no-build-restart]]).

### Implementation notes (2026-09-03)
Single file, `PlayerScreen.kt`, exactly as scoped — no backend/admin changes were needed since the
settings-delivery plumbing was already correct.
- **FR-R230-1**: the `creditsReached` early-trigger `if` (segment `creditsStartMs` or the
  `NEXTUP_AT_MS` heuristic) gained `&& currentSkipCreditsMode != SkipMode.OFF`.
- **FR-R230-2**: the `player.isEnded` safety-net block now branches on the mode. Off + a next episode
  + Autoplay next episode on → same `nextUpVisible = true; nuFocus = PLAY` as before, just reached
  only at the real end instead of early. Off + anything else → calls the existing `skipCredits()`
  function directly (reused as-is: it already does `nextUpVisible = false`, marks watched at ≥90%,
  and calls `onBack()` — exactly the silent-exit fallback the spec calls for, no new code needed for
  it). Prompt/Auto path is untouched, same two-liner as before.
- **FR-R230-3**: `creditsCardMode`'s `when` gained a first branch — `currentSkipCreditsMode == OFF ->
  CreditsCardMode.NEXT_EPISODE` — ahead of the Stinger/next-episode/plain-skip-credits branches, so
  Off can only ever reach the Next-Episode card (the one case FR-R230-2 can still set
  `nextUpVisible = true` for).
- **FR-R230-4/6**: no other code path touched — Prompt/Auto and the unscanned-title heuristic both
  flow through the same `creditsReached`/`isEnded` checks unchanged except for the new `!= OFF` guard,
  which is a no-op for them.
- **FR-R230-5**: confirmed by re-reading the existing gates (`skipIntroPillVisible`,
  `skipIntroCountingDown` arm) — both already condition on `skipIntroMode != OFF` and needed no
  change; recorded here as the verified regression baseline.
- Compiles clean: `:ravilo-ui:compileDebugKotlinAndroid`, `:ravilo-ui:compileKotlinWasmJs`.

## Problem — verified in code, not guessed
`PlayerScreen.kt` resolves both settings from the server the same way:

```kotlin
var skipIntroMode by remember { mutableStateOf(SkipMode.PROMPT) }
var skipCreditsMode by remember { mutableStateOf(SkipMode.PROMPT) }
...
skipIntroMode = cfg.skipIntro
skipCreditsMode = cfg.skipCredits
```

**Skip Intro is correct today.** `skipIntroPillVisible` and the auto-skip countdown arm are both
gated on `skipIntroMode != SkipMode.OFF` (`PlayerScreen.kt:867`, `:953`) — Off already means the pill
never renders and the intro plays through untouched. No code change needed for this half; it's
recorded here as the target behavior for regression purposes.

**Skip Credits is not wired at all.** `currentSkipCreditsMode` (`rememberUpdatedState` over
`skipCreditsMode`) is declared and assigned but **never read anywhere else in the file** — grep
confirms exactly two other occurrences, both the declaration/assignment above. The credits card
(`nextUpVisible`, driving all three `CreditsCardMode` variants — Stinger / Next Episode / Skip
Credits) is triggered purely by `creditsReached` (segment `creditsStartMs`, or the `NEXTUP_AT_MS`
duration heuristic when unscanned) and, as a safety net, by `player.isEnded` — neither check consults
the setting at all (`PlayerScreen.kt:833-855`). A viewer who sets **Skip credits: Off** still gets
interrupted by the card at the end of every episode and movie, identically to Prompt. The setting has
been silently inert since R182 shipped.

The admin config editor (`design/app/ravilo-config.html:428-429`, and the real picker in
`RaviloConfig.kt:2447-2448`/`:2560-2562`) already offers Off end-to-end — global default, per-user
override (R162 overlay), resolution, delivery to the client (`ResolvedBehaviour.skipCredits`,
`RaviloConfigService.resolveBehaviour`) — all of that plumbing is correct and untouched by this
phase. **This is a client-only bug**, contained to `PlayerScreen.kt`.

## Design decision — Off vs. the separate "Autoplay next episode" setting
Confirmed with the product owner: Off must not disable autoplay. **Autoplay next episode** is an
independent, pre-existing setting (predates R182 — see R182's own dev-review dedup note) and stays
independent here. The two must compose as follows:

- **While Skip Credits is Off, the player ignores segment/heuristic position data for interruption
  purposes entirely** — no early trigger at `creditsStartMs`, no `NEXTUP_AT_MS`-before-end heuristic,
  no Stinger "Skip to scene" offer. The file simply plays straight through its credits (and any
  stinger scene, which plays out naturally as part of the file — nothing is skipped, nothing needs to
  be).
- **Only once the file reaches its real end (`player.isEnded`)** does anything happen:
  - If a next episode exists **and** Autoplay next episode is on: start the counter — the existing
    Next-Episode countdown-and-advance mechanism — now firing at the true end of the file instead of
    early, and auto-advance when it elapses. This is not a "skip intro/credits" popup; it's the
    ordinary next-episode transition, and the owner explicitly confirmed it should still appear.
  - Otherwise (no next episode, or Autoplay next episode is off): exit the player silently
    (`onBack()`) — the same fallback `skipCredits()` already performs today, and the same behavior
    R182's own comments describe as "the pre-R182 isEnded handler." No card, no counter, nothing left
    on screen.
- Stinger and the plain "Skip credits" (movie / last-episode-no-stinger) card variants are **not
  reachable at all** while Off — both exist only to offer an early jump past content the viewer would
  otherwise sit through, which is meaningless once already at the real end (there is nothing left to
  jump past).

Net effect: Off removes every mid-playback interruption ("Skip Intro" pill, "Skip to scene", "Skip
credits", the early "Next Episode" card) while leaving the seamless end-of-file → next-episode
transition intact when the viewer has separately asked for it via Autoplay.

## Requirements

### FR-R230-1 — Skip Credits Off suppresses the early trigger
The `creditsReached`-based trigger (`PlayerScreen.kt:836-843`) must not fire — must not set
`nextUpVisible = true` — while `currentSkipCreditsMode == SkipMode.OFF`. This is the core fix: today
this check has no dependency on the setting at all.

### FR-R230-2 — Real end-of-file becomes the only trigger while Off
The existing `player.isEnded` safety-net block (`:852-855`) remains active while Off (it is the *only*
active trigger in this mode) but its outcome branches on the state described above:
- Next episode exists **and** Autoplay next episode is on → show the Next-Episode countdown card only
  (never Stinger, never the plain Skip-Credits card) and auto-advance on elapse, exactly like the
  existing `CreditsCardMode.NEXT_EPISODE` countdown behavior today.
- Otherwise → call `onBack()` directly, no card rendered at any point.

### FR-R230-3 — Stinger and plain Skip-Credits variants never render while Off
`creditsCardMode` resolution (`:966-969`) must not be allowed to select `STINGER` or (the no-next-
episode) `SKIP_CREDITS` variant while `currentSkipCreditsMode == SkipMode.OFF` — per FR-R230-2, the
only variant reachable in Off mode is the Next-Episode countdown, and only from the `isEnded` trigger.

### FR-R230-4 — No regression to Prompt / Auto
With Skip Credits set to Prompt or Auto, behavior is byte-for-byte unchanged: early trigger at
`creditsStartMs`/`NEXTUP_AT_MS`, all three card variants reachable, existing Stinger/Skip-Credits/
Next-Episode actions unchanged. This phase adds a new branch, it does not touch the existing ones.

### FR-R230-5 — Skip Intro Off: no code change, add regression coverage
Confirm (via manual playthrough per this project's testing conventions, or a Compose test if a
harness already exists for `PlayerScreen`) that `skipIntroMode == OFF` continues to render no pill and
performs no auto-skip, across a title with detected intro segments. This is a verification item, not
an implementation item — the gating already exists and is correct.

### FR-R230-6 — Unscanned content unaffected
A title with no `creditsStartMs` already only has the `NEXTUP_AT_MS` heuristic as its early trigger;
FR-R230-1 suppresses that heuristic identically to a scanned title's segment-based trigger when Off.
An unscanned title behaves exactly like a scanned one under Off — no special case needed.

## Non-goals
- Any change to the Autoplay next episode setting itself, its storage, or its resolution — it is
  reused exactly as-is, just re-anchored to fire from the `isEnded` trigger instead of the early one
  while Off.
- Any change to Prompt or Auto mode behavior for either setting.
- Any change to the admin config editor, the per-user overlay mechanism (R162), or the wire format —
  `ResolvedBehaviour.skipCredits` already carries the right value; only its consumption in
  `PlayerScreen.kt` is fixed.
- Updating the admin config editor's help copy (`ravilo-config.html:429`'s "or Off" is currently
  vague compared to Skip Intro's "leave intros alone (Off)" wording) — worth a follow-up copy pass,
  not required for this phase to be correct.
- A visible countdown/UI change to the reused Next-Episode card itself — same component, same copy,
  just a different trigger point.

## Acceptance
- Skip Credits = Off, series with detected intro/credits segments and a next episode, Autoplay next
  episode = on: no Skip Intro pill, no credits card at the detected `creditsStartMs` point; at the
  file's real end, the Next-Episode countdown appears and auto-advances.
- Same setup, Autoplay next episode = off: no popups at any point; at the file's real end, the player
  exits to the previous screen with no card ever shown.
- Skip Credits = Off, movie (or series' last episode) with a TMDB-flagged stinger: no "Skip to scene"
  card at the detected stinger/credits point; the stinger plays out naturally as part of the file; at
  the real end, the player exits silently (no next episode to advance to).
- Skip Credits = Prompt or Auto: unchanged from today — verify no regression against R182's own
  acceptance criteria.
- Skip Intro = Off: no pill at any point during a title's detected intro window (regression check,
  no new behavior).

## Source references
- Bug: `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt:259`,
  `:263`, `:742` (`skipCreditsMode`/`currentSkipCreditsMode` — read, never consulted); `:833-855`
  (`creditsReached`/`player.isEnded` triggers, ungated); `:966-969` (`creditsCardMode` resolution).
- Correct reference implementation (Skip Intro, unchanged): `:867-874`, `:953-954`.
- Existing exit fallback to reuse for the "otherwise" branch: `skipCredits()`, `:512-516`.
- Settings plumbing (untouched, already correct): `shared/.../Models.kt:793-794` (`ResolvedBehaviour`
  fields), `src/linuxX64Main/.../RaviloConfigService.kt:216-217` + `:300-308` (resolution +
  admin-set), `design/app/ravilo-config.html:426-429` (admin copy).

## Relationships
- Fixes a latent client-side gap in **R182** (Skip Intro & Skip Credits) — the setting has existed
  and been fully wired server-side since R182 shipped; only the player's consumption of it was never
  finished.
- Composes with the pre-existing, independent **Autoplay next episode** setting (deduped by R182's own
  dev review as not a new setting) — this phase changes *when* that setting's countdown fires while
  Skip Credits is Off, not what it does.
- `scripts/check-phases.sh` will want a `STATUS.md` row once built — **STATUS.md is code-owned; do
  not add the row from the design side.**
