# Phase 180 — Leaving a stream must actually stop it

> Companion to **Phase R218** (client buffering states). R218 draws the wait honestly and states that
> pressing Back during one ends the session. That promise is currently only half true: the client reports
> a stop and the server tells Jellyfin the session ended, but nothing releases work that is already in
> flight. On a 20-second 4K transcode spin-up — precisely the wait R218 exists to make survivable —
> walking away leaves the encode running for a viewer who has gone to watch something else.

**Status:** Planned (design-authored 2026-08-28; renumbered 179 → 180 the same day — the dev team took 179 for the subtitle-sideload transcode stall). Not yet dev-reviewed — the Jellyfin-side call in
FR-180-2 must be confirmed live against 10.11.11 before build (see Open questions).

## Root cause

`PlaybackService` owns the whole lifecycle and already has every identifier it needs:

- `startPlayback` mints and stores a per-item **`playSessionId`** (`tv/PlaybackService.kt:218`), which is
  the handle Jellyfin itself uses to identify an encoding session.
- `stopPlayback` (`:357`) reports the stop to Jellyfin and clears the entry from `PlaybackTracker`.
- The Phase 110 **stop watchdog** (`:324+`) does the same for a TV that vanished without saying goodbye.

What none of these do is tell Jellyfin to **stop encoding**. Reporting a playback stop updates "Now
Playing"; it does not, on its own, guarantee that an active transcode is torn down. Jellyfin's own
clients issue an explicit active-encoding delete. jellystructure never has, because until R218 nobody had
looked closely at what leaving *during* a wait should mean — a session that never produced a first frame
is exactly the case where a stop is most likely to be reported before Jellyfin considers the session
established.

The cost is concrete on this host: an abandoned 4K transcode holds an NVENC slot and reads hard from
`sda` — the same spindle Phase 178 just taught the rest of the server to stay off while a TV is watching.
An orphaned encode is background I/O that Phase 178 cannot see, because as far as the server is
concerned it is playback.

## Requirements

### FR-180-1 — A stop is a teardown, at every exit

Every path that ends a playback ends the work behind it, not just the bookkeeping:

- the client's explicit stop (`stopPlayback`, including when **no first frame was ever rendered** — the
  R218 case),
- the Phase 110 stop watchdog firing on a disconnected TV,
- a new session for the same device superseding an older one.

All three converge on one teardown routine. There must not be a path that clears the tracker without
attempting the release.

### FR-180-2 — Release the encode, keyed on the id we already hold

The teardown issues Jellyfin's active-encoding stop for the stored `playSessionId` (`:218`) alongside the
existing stop report, in that order: tell Jellyfin the session stopped, then ensure nothing is still
encoding for it.

- **Confirm the exact operation against the live 10.11.11 OpenAPI document before building.** Phase 163
  was rewritten late because a MediaSegments operation was assumed to exist and returned 405; do not
  repeat that. If no such operation exists on this server version, this requirement becomes "record the
  finding and close" — not "invent a workaround".
- **Idempotent and failure-tolerant.** A release for a session that already ended is a success, not an
  error. A failed release is logged and never retried in a storm, and never blocks the stop report.
- **Only ever our own sessions.** The release is issued for a `playSessionId` this server minted. It must
  never enumerate and kill encodes it does not own — another household client streaming from the same
  Jellyfin is not ours to interrupt.

### FR-180-3 — A stop during negotiation must not be lost

If Back is pressed before the session is fully established, the stop can race the start. The teardown
must handle the ordering rather than dropping the work:

- A stop for a `playSessionId` that is still being minted is **queued against that id** and issued once
  the start completes, rather than being discarded as unknown.
- A start whose stop already arrived does not begin streaming at all where it can be avoided.

This is the specific failure mode R218 introduces traffic for: the abandonment happens *during* the
window where the session is least established.

### FR-180-4 — An abandoned session must never look live

`PlaybackTracker` is the authority Phase 178 consults for `anyActive()`. A torn-down session is removed
from it at teardown, so:

- Phase 178's deferral and qBittorrent throttle release on their normal grace window rather than being
  held open by a viewer who left,
- the remote-control device list (`nowPlayingItem`, `:168`) stops naming a title nobody is watching,
- R216's QoE snapshot for that session is still posted (an abandoned start is a data point worth having),
  flagged as ended without a first frame.

## Invariants

- **Bookkeeping and work are torn down together.** No path may update one without attempting the other.
- **We only stop what we started.** Every release is keyed to a `playSessionId` this server minted.
- **Teardown never blocks or fails a user action.** Leaving is instant from the viewer's point of view;
  the release happens behind it, and a failed release is a log line, not an error state.
- **Idempotence throughout.** Stop, watchdog and supersede may all fire for the same session; the result
  must be the same as any one of them firing alone.
- **No playback path changes.** This phase touches only what happens *after* a session ends. Negotiation
  (177), streaming and the player are untouched.

## Out of scope

- **Pre-warming or reusing an abandoned transcode** if the viewer comes back. Tempting and wrong at this
  size — it means keeping work alive on a guess, which is the opposite of this phase.
- **A grace period before teardown.** Phase 178's 120 s grace window governs *background work resuming*;
  it is not a licence to keep an encode running. Leaving means leaving.
- **Driving Jellyfin's own scheduled tasks** — still out of scope, as in Phase 178.
- **Client-side changes.** R218 owns the client's obligation to report the stop promptly; this phase
  owns the server's obligation to act on it.
- **Session resumption across devices.** Unrelated feature, no bearing here.

## Source references

- `tv/PlaybackService.kt:218` — `startPlayback`, where the `playSessionId` is minted and stored (the key
  FR-180-2 uses); `:357` `stopPlayback` — the existing stop report, extended by FR-180-1/2; `:324+`
  `stopWatchdogTick` — the disconnected-TV path that must converge on the same teardown; `:88-162`
  `PlaybackTracker` — the liveness map FR-180-4 keeps honest.
- Related: **R218** (the client half and the reason this matters now), **Phase 178** (`anyActive()`
  consumes the tracker FR-180-4 protects; an orphaned encode is invisible background I/O to it),
  **Phase 110** (the stop watchdog), **Phase 177 / R216** (QoE — the abandoned-session data point),
  **Phase 163** (the precedent for verifying a Jellyfin operation exists before designing on it), **Phase 179** (same evening, same file: its FR-179-2 adds the player's first custom subtitle load policy while this phase adds the server's first teardown call — both hang off the same abandoned/contended-transcode mechanism).
- `specs/research-reports/ravilo-player-buffering-loading-states-2026-08-28.md` — the abandonment case.

## Open questions

1. **Does 10.11.11 expose an active-encoding stop at all, and under what shape?** Must be checked against
   the live OpenAPI document before build. This is the one thing that can turn this phase from
   "implement" into "record and close", and Phase 163 is the standing reminder to check first.
2. **Does reporting a playback stop already tear the encode down on its own?** Plausible for an
   established session and unlikely for one abandoned mid-negotiation. Worth measuring on the live host
   (start a 4K transcode, press Back at 5 s, watch NVENC) before writing any code — the answer may narrow
   this phase to FR-180-3 alone.
3. **How long does a queued stop (FR-180-3) stay queued?** A start that never completes must not leave a
   stop pending forever. A bound is needed; the watchdog's existing timing is the obvious reference.
4. **Should an abandoned-before-first-frame session count in QoE stats?** FR-180-4 says yes and flags it.
   If it turns out to skew the rebuffer metrics R216 is meant to expose, it should become its own counter
   rather than being dropped.
