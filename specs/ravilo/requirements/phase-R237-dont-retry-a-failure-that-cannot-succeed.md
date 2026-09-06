# Phase R237 — The player spends 15 seconds retrying a failure that cannot succeed, and shows a spinner the whole time

> Live report, 2026-09-06: *"I tried to play some media on soveværelse TV this morning, but it didn't
> start fast enough, so I stopped."*

## Status
`✓ Built` 2026-09-06 — all six FRs. Design-authored the same day, not dev-reviewed, not device-tested.
Client half of the 2026-09-06 playback incident; the server-side cause is **Phase 194**, built
alongside it.

### What was built
- `classifyStartFailure` (FR-R237-1) — 409/403/404 and any other 4xx terminal; transport failures,
  408/429 and 5xx retryable, honouring `Retry-After` in place of the computed backoff. `TvApiError.Http`
  gained `retryAfterSeconds`, read from the response header in `assertSuccess`; a value outside 0–60 s
  falls back to our own backoff rather than parking the viewer indefinitely.
- `PlayerSessionState.Error` carries a `PlayerErrorKind` and the status; `PlayerScreen` renders a
  per-cause heading and body (FR-R237-2) instead of `error.generic` over `"HTTP 409: {json body}"`.
  Nine new strings × en/da/fo. No product name, protocol name or status code appears in any of them.
- Retry is shown only where retrying can change the answer (FR-R237-3). `REAUTH` gets a **Sign in**
  action wired to the same exit R234's forced sign-out already uses — revoke this profile, land on the
  picker or the login gate. This answers **open question 2**: rather than a login takeover layered over
  a dead player, the player is left entirely, which is a flow that already exists and is already
  tested. `403`/`404` get Back alone.
- FR-R237-5 — `PlayerSessionState.Loading(retrying)` adds one *"Still trying…"* line to R218's existing
  cold-start treatment once a first attempt has failed. Nothing else about R218's states changed.
- FR-R237-6 — `PlaybackQoeReport.startFailureStatus` + `playback_qoe.start_failure_status` (migration
  `42.sqm`), posted on any start that never reached `Ready`. `0` means "failed with no HTTP response at
  all". `QoeSummary.hasIssue` now includes it, so a failed start is badged in the admin surfaces.

### Answered while building
- **FR-R237-4 needed no code.** Auto-advance is `advanceRequestedForItemId`-latched to one attempt per
  episode and navigates to a fresh `PlayerScreen`; a screen whose session errored never reaches
  `player.isEnded`, so it cannot advance again. The season cannot be walked one 409 at a time.
- **Open question 1 — `ravilo-tizen` does not share this loop.** Its `PlayerScreen.advanceTo` /
  `DetailScreen.startPlayback` make a single attempt and `finish()` on failure: no retry, so no 15 s
  spinner, but also no message at all. A separate, smaller gap; left alone per R190's per-target
  precedent.
- One incidental layout correction: the loading overlay's spinner, spacer and text were direct children
  of a centre-aligned `Box`, so the 48 dp spacer did nothing and *"Loading…"* drew on top of the
  spinner. They are a `Column` now — which FR-R237-5's second line needs to be legible at all.

Six new `PlayerStartFailureClassificationTest` cases; `:ravilo-ui:compileDebugKotlinAndroid`,
`:ravilo-ui:testDebugUnitTest`, `:ravilo-web:compileKotlinWasmJs`, `:ravilo-tizen:compileKotlinJs`,
`compileKotlinLinuxX64` and `compileKotlinWasmJs` all clean.

## What the viewer saw

Nothing. That is the entire problem.

Soveværelse TV, 2026-09-06 local time — three Play presses, from `playback_qoe` on device
`52ff71d00407f87b14846d9f1520a04d`:

| Time | Title | Gap | Outcome |
|---|---|---|---|
| 07:52:23 | Babblarna | — | no decoder, `direct_play=0`, no stream |
| 07:52:27 | Babblarna | +4 s | same |
| 07:52:33 | Mickeys Klubhus | +6 s | same |

Server-side, each of those was an immediate `HTTP 409` — `"This device's Jellyfin sign-in has expired
— re-pair it to continue watching."` The server knew, within milliseconds, the exact reason and that
it would not change for the next ten minutes.

The viewer was shown a spinner, and backed out after 4 and 6 seconds.

## Root cause

`PlayerStore.startSession` (`screens/PlayerStore.kt:87-158`) sets `PlayerSessionState.Loading` and
then:

```kotlin
var lastErr = "Failed to start playback"
var delayMs = 1_000L
repeat(5) { attempt ->
    val result = runCatching { … apiClient.startPlayback(itemId, capabilities = …) }
    if (result.isSuccess) { … return@launch }
    lastErr = result.exceptionOrNull()?.message ?: lastErr
    if (attempt < 4) { delay(delayMs); delayMs *= 2 }
}
_state.value = PlayerSessionState.Error(lastErr)
```

Five attempts, 1 s + 2 s + 4 s + 8 s of backoff — **~15 seconds of unbroken spinner** before the
screen says anything at all. The comment above it is honest about the intent:

> *"~15s of quiet retry survives a blip without leaving a spinner up for minutes."*

That reasoning is sound for the case it was written for — a transient network failure during an
auto-advance to the next episode, where the viewer is already watching and a silent recovery is
strictly better than an error card. It is wrong for every other case, because the loop **does not look
at why it failed.**

`result.exceptionOrNull()?.message` throws away a value the client already has.
`TvApiClient.assertSuccess` (`shared/…/TvApiClient.kt:550-552`) throws
`TvApiError.Http(status.value, bodyAsText())` and `TvApiError.Http` (`shared/…/Models.kt:1061`) carries
`val status: Int`. The status code is right there, one `as?` away, and is never consulted.

So a `409` — deterministic, unchanged for ten minutes, with a human-readable explanation in the body —
is retried on the identical schedule as a dropped packet. All five attempts were guaranteed to fail
identically before the first one was sent.

### The two failures compound

The viewer pressed Play three times in ten seconds. Each press started a fresh 15-second retry budget
that would end in the same error. Nothing on screen distinguished "still working on it" from
"definitely never going to work", so the only information available was elapsed time — and the honest
read of a spinner that has been up for 4 seconds is *"this is slow"*, not *"this is broken"*.

They gave up before any of the three sessions reached its error card. **The re-pair message was
rendered zero times.** A precise, actionable diagnosis existed on the server, travelled to the client,
and was displayed to nobody.

## Problem, stated plainly

The player treats every failure as transient because it never asks what the failure was. Retrying a
deterministic error is not resilience — it is a delay with a spinner in front of it, and it converts a
clear error into an ambiguous wait.

## Goal

A failure that cannot succeed on retry is shown immediately, in words the viewer can act on. A failure
that plausibly can succeed is still retried quietly, exactly as R218 intended. The player never asks
the viewer to infer the difference from how long a spinner has been up.

## Requirements

### FR-R237-1 — Classify the failure before deciding to retry

`startSession`'s loop inspects the thrown error. `TvApiError.Http` carries `status`; use it.

**Terminal — do not retry, surface immediately:**

- `409` — re-authentication required (`JellyfinReauthRequiredException`)
- `403` — playback forbidden (`PlaybackForbiddenException`; a Kids/visibility restriction — a real
  answer, not a fault)
- `404` — the item is gone
- any other `4xx` except `408` and `429`

**Retryable — keep the existing backoff:**

- a thrown transport error (no HTTP response at all — the auto-advance blip case this loop was written
  for)
- `408`, `429`, and any `5xx` — including the `503 + Retry-After` the server returns on gate saturation
  (Phase 182 FR-182-8), which is explicitly "busy, try again shortly"

When the server sends `Retry-After`, honour it in place of the computed backoff.

### FR-R237-2 — Say what is wrong, not "Something went wrong"

`PlayerScreen.kt:1424-1450` renders `str("error.generic")` — *"Something went wrong"* — above
`sessionError.message`, which is the raw exception text (`"HTTP 409: {json body}"`). Neither line is
usable: the first says nothing, the second is a status code and a JSON blob.

Each terminal class gets its own heading and, where one exists, its own next step:

| Cause | Heading | Body |
|---|---|---|
| `409` re-auth | **This TV needs to be signed in again** | *Sign in again on this TV to keep watching.* |
| `403` forbidden | **Not available on this profile** | *This title isn't part of what this profile can watch.* |
| `404` gone | **This title isn't available any more** | — |
| retries exhausted | **Couldn't reach the server** | *Check the connection and try again.* |

Three new strings per row × en/da/fo, alongside the existing `error.generic`
(`i18n/Strings.kt:278` / `:554` / `:830`), which stays as the fallback for an unclassified failure.

Copy rules, per the Ravilo constitution: name the thing the viewer must do, never the component that
failed. **"Jellyfin"**, **"token"**, **"HTTP 409"** and **"the server"** must not appear in any of these
strings — the viewer has no idea what any of them are, and the constitution's own precedent here is
R234's rejected *"Your Jellyfin password"*. "Couldn't reach the server" is the one borderline case and
is kept only because it is the sole message where the viewer's action genuinely concerns the
network — revisit it in review.

### FR-R237-3 — The retry button retries; it does not repeat a verdict

The existing **Retry** control (`PlayerScreen.kt:1431-1450`) stays for retryable failures. On a
terminal failure it is replaced by the action that can actually resolve it (for `409`, an entry into
the sign-in flow) or, where there is none (`403`/`404`), by **Back** alone.

Offering "Retry" for a condition that is deterministic for ten minutes is the same mistake as the
retry loop, moved into the viewer's hands.

### FR-R237-4 — Terminal means terminal, for this session

A terminal classification for an item is not re-attempted by auto-advance. If the next episode fails
with `409`, advancing further will fail the same way — R218's auto-advance must stop on a terminal
error rather than walking the season one 409 at a time.

### FR-R237-5 — The wait is bounded and legible

Even a legitimately retryable failure should not present as an ordinary cold start for 15 seconds.
R218 already owns this vocabulary: its cold-start treatment (Direction B "Grounded" — black, three-dot
brand pulse, title context, indeterminate sweep) is what is on screen now, and R218 specifies
deepening at **60 s without changing a word**.

That deepen threshold is far past the point where a viewer has given up — the measured behaviour here
is 4 seconds. After **~5 s of retrying** (i.e. once the first retry has failed, which is already proof
this is not a normal start), the existing R218 presentation gains one line: *"Still trying…"* — no new
visual language, no new component, no progress count, no attempt number. It changes a wait the viewer
cannot interpret into one they can.

This is the only change to R218's states and it is additive.

### FR-R237-6 — A failed start is diagnosable afterwards

The three attempts wrote `playback_qoe` rows with every field null or zero — enough to prove *that*
they failed, not *why*. The client already knows the status code by FR-R237-1. Post it: a nullable
`startFailureStatus` on the QoE report, set only when the session never reached `Ready`.

This is what would have made the 2026-09-06 incident readable from the database alone, without
correlating three log lines by hand.

## Non-goals

- No change to R218's three moments (A negotiation, B cold start, C stall, D seek), their ~400 ms
  debounce, or the 60 s deepen. FR-R237-5 adds one line to an existing state and nothing else.
- No change to the retry policy for **retryable** failures — 5 attempts, 1/2/4/8 s stands.
- No new escape hatch. Back remains always available and never prompted (R218's rule).
- No client-side caching of a terminal verdict across items or sessions. Each Play press asks the
  server fresh; it just believes the answer the first time.
- Nothing about *why* the 409 was issued — that is Phase 194.
- No change to `HomeStore`'s 10-attempt browse-path policy.

## Acceptance

1. With the server returning `409` for `/api/tv/playback/start`, press Play: the sign-in message
   appears in **under a second**, not after 15. Its wording contains no product or protocol name.
2. With the server returning `503 + Retry-After: 2`, press Play: the player retries quietly, honours
   the 2 s interval, and succeeds once the server recovers — no error card.
3. Kill the network entirely and press Play: the R218 cold-start treatment shows, gains *"Still
   trying…"* at ~5 s, and lands on **Couldn't reach the server** with a working Retry.
4. On a Kids profile, deep-link an out-of-scope item: **Not available on this profile**, with Back and
   no Retry.
5. Auto-advance into an episode that returns `409`: the player stops with the sign-in message and does
   not attempt the episode after it.
6. All new strings render in en, da and fo, and none wraps to a single character per line on the phone
   layout (R229).
7. A failed start leaves a `playback_qoe` row carrying the HTTP status that caused it.

## Source references

- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerStore.kt:87-158` — the
  retry loop, its `repeat(5)` / 1-2-4-8 s backoff, and the comment stating the ~15 s intent.
- `…/PlayerStore.kt:30` — `PlayerSessionState.Error(message)`.
- `…/screens/PlayerScreen.kt:1410` — the `Loading` branch (R218's presentation).
- `…/screens/PlayerScreen.kt:1424-1450` — the `Error` branch: `error.generic` over the raw exception
  message, Retry + Back.
- `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/TvApiClient.kt:176-183` — `startPlayback`;
  `:550-552` — `assertSuccess`, which mints the error carrying the status.
- `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/Models.kt:1060-1061` — `TvApiError.Http`
  and its `status: Int`, available and unused.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/i18n/Strings.kt:278`, `:554`, `:830` —
  `error.generic` in en/da/fo.
- `src/linuxX64Main/kotlin/dev/jellystructure/server/Server.kt:203-224` — the server's four mapped
  statuses: 409 re-auth, 403 forbidden, 503+Retry-After ×2.
- `specs/ravilo/requirements/phase-R218-player-loading-buffering-states.md` — the four waiting moments
  and the deepen-at-60 s rule.
- Production evidence: `playback_qoe` rows for device `52ff71d0…` at 2026-09-06 07:52:23/27/33 local,
  all with `video_decoder = NULL` and `direct_play = 0`.

## Open questions

1. **Does `ravilo-tizen` share this loop?** `ravilo-tizen/src/jsMain/…/PlayerScreen.kt` has its own
   play path; confirm whether it needs the same classification or has a different one already. R190's
   Tizen omission is the precedent for deciding this per-target rather than assuming parity.
2. **Is `409` reachable in a state the viewer can actually fix from the TV?** FR-R237-3 wants the
   sign-in flow as the action, but R175's login is a full-screen takeover mid-playback. Confirm whether
   it can be entered and returned from cleanly, or whether the honest copy is "sign in again from the
   Ravilo app on your phone" — which R234 FR-R234-2 would push back on, since a TV screen explaining
   where a setting lives is still a TV screen talking about settings.
3. **Should `403` be reachable at all?** A Kids profile should not be able to focus an item it may not
   play, so a `403` at the play step suggests a visibility filter leaked upstream. Worth checking
   whether this branch is dead in practice before writing copy for it.
4. **Is the phone/web treatment identical?** R218 drew two phone frames; this phase assumes the same
   error card on all three targets. Confirm the phone's native back behaviour doesn't make Retry+Back
   redundant there.
5. FR-R237-6 adds a field to the QoE payload, which is a shared model — confirm whether it needs a
   Phase 194-side migration or rides the existing nullable-field tolerance.
