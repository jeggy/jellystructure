# Phase 178 — Background work must not storm the disk a TV is streaming from

> Two of the three stue TV stutter investigations traced back to disk contention, not to the app. On
> 2026-08-20/21 a qBittorrent download was writing to `/mnt/series` while the Offboarding file was being
> streamed **off that same spindle**. Measured again during the 2026-08-28 investigation, the mechanism
> is unambiguous: `sda` read latency goes from **8.17 ms idle to 73.43 ms** under a 154 MB/s concurrent
> write (`%util` 4 → 56, `/proc/pressure/io` avg300 = 23.29). Meanwhile Jellyfin runs its own heavy
> passes over the same disk on its own schedule — on the evening of 2026-08-27 a 65-minute tone-mapped
> 4K trickplay job (16:00:22→17:05:30 UTC) over a 10.4 GB DV file on `sda`.
>
> jellystructure already knows, precisely and in real time, when a TV is playing. Nothing consults it.

**Status:** Implemented (2026-08-28, same day as design) — built end to end: `PlaybackTracker.anyActive()`/
`activeDevices()` (120s grace window) + `GET /api/playback/active`; `runPipeline()`'s new `deferEligible`
gates scan_files' run-start and fetch_artwork via `awaitPlaybackClear`, broadcasting `JobEvent.Deferred`/
`Resumed`; `MediaJobParams.deferWhilePlaying` gates the segments-lane worker per-job; `PlaybackThrottleStore`/
`Service` apply/restore qBittorrent's alternative speed limits (crash-safe, restore-only-if-still-ours);
`[scan] defer_while_playing` / `[qbittorrent] throttle_while_playing` config flags + Settings UI;
Dashboard's "Paused — TV is watching {name}" banner + "Run anyway" (`PipelineDeferOverride`,
`POST /api/pipeline/{jobId}/run-anyway`). Compiles clean. Independent of Phase 177 / R216; they address
the negotiation and the client, this addresses the server's own housekeeping. **Not yet verified live**
— no real scheduled run has actually collided with real playback under this code; see Open questions.

## Root cause

`PlaybackTracker` (`tv/PlaybackService.kt:88-162`) maintains an authoritative live map of every active
playback — `started()` / `heartbeat()` / `stopped()` plus a watchdog that force-stops sessions whose TV
disconnected (Phase 110). It exists to keep Jellyfin's "Now Playing" honest, and it is exposed to exactly
one consumer: `nowPlayingItem(deviceId)` for the remote-control device list (`:168`).

So the server has a perfect signal for *"is anyone watching right now"* and spends it on a UI label.
Every heavy background activity ignores it:

1. **jellystructure's own pipeline.** `detect_segments` (`fpcalc` + `blackdetect`/`silencedetect`) and
   `fetch_artwork` read media files at full tilt. Phase 170 gave segment detection its own `ProcessGate`
   and Phase 145's CPU work added `nice`/`ionice` + `-threads 2`, which fixed *CPU* starvation — but
   nothing throttles or defers on the basis that a TV is mid-episode.
2. **Jellyfin's scheduled tasks.** Trickplay and chapter-image generation are configured inside Jellyfin
   and run on its schedule, blind to our playback state. The 2026-08-27 trickplay pass is the concrete
   example.
3. **qBittorrent.** `QBittorrentClient` (`torrent/QBittorrentClient.kt`) already authenticates
   (`login()`) and reads torrent state (`getTorrents()`) against a configured instance
   (`[qbittorrent]`, `AppConfig.kt`) — the connection, credentials and path mappings all exist today for
   the cross-seed guard. It has never been asked to *change* anything.

The aggravating factor is topology, and it is worth stating plainly because a code fix alone will not
remove it: on this host `/mnt/series` (`sda`, 23.6 TB) holds TV series **and** qBittorrent's download
target **and** the cross-seed hardlink tree. The streamed Offboarding file has link count 2 — it is
simultaneously a library file and a seeded torrent. Playback reads, seeding reads and download writes
all land on one spindle by construction.

## Requirements

### FR-178-1 — A single authoritative "playback is live" signal

Promote the existing tracker to a first-class, queryable server signal:

- `playbackTracker` gains `anyActive(): Boolean` and `activeDevices(): List<String>`, alongside the
  existing `nowPlaying()`. No new state, no new bookkeeping — these read the same `snapshot` the class
  already publishes.
- A short **grace window** (proposed 120 s) after the last stop before the server considers playback
  finished, so a between-episodes gap or a brief pause does not immediately unleash a download burst
  that the next episode then has to fight.
- Exposed read-only at `GET /api/playback/active` for the admin UI and for FR-178-4's manual override.

### FR-178-2 — Defer jellystructure's own heavy steps while a TV is watching

Steps that read media files at volume — `detect_segments`, `fetch_artwork`, and the probe-heavy portion
of `scan_files` — consult FR-178-1 before starting a **new** item.

- **Deferral, not cancellation.** An in-flight item finishes; the runner simply does not pick up the
  next one while playback is live, and resumes when the grace window expires. A pipeline run that
  defers reports it as such in the scan log rather than looking stalled.
- Applies to **scheduled and event-driven** runs only. An operator who clicks "Scan library" or "Run
  pipeline now" while a TV is playing gets their run — an explicit human action is never silently
  deferred; it warns instead (FR-178-4).
- Governed by one config flag, `[pipeline] defer_while_playing` (default **on**), because a single-user
  household and a many-viewer one want opposite answers.

### FR-178-3 — Throttle qBittorrent while a TV is watching

Using the existing authenticated client and connection:

- On the first active playback, apply qBittorrent's **alternative speed limits** (its own built-in
  mechanism — `/api/v2/transfer/setSpeedLimitsMode`) rather than rewriting the user's configured rate
  numbers. This is deliberate: it is one reversible toggle, it is what the alternative-limit feature
  exists for, and it cannot corrupt the operator's real settings if we crash mid-flight.
- On the grace window expiring with nothing playing, restore the previous mode — **only if we were the
  one who changed it**, tracked by remembering the pre-change mode. If the operator toggled it manually
  in the meantime, leave it alone.
- **Restore must be crash-safe.** The pre-change mode is persisted, not held in memory, and the restore
  is attempted on startup — otherwise a backend restart mid-playback leaves the household's downloads
  throttled forever with no visible cause.
- Governed by `[qbittorrent] throttle_while_playing` (default **off** — this reaches into a service the
  operator owns, and must be opted into, not assumed).

### FR-178-4 — Make the deferral visible, and overridable

Invisible automation that slows things down is worse than no automation.

- The dashboard's ambient scan dock shows a **"Paused — TV is watching"** state, naming the device,
  whenever a run is deferred by FR-178-2. It is a normal resting state, not an error.
- The same state offers **"Run anyway"**, which sets a one-run override — matching Phase 154's
  established pattern of a pre-run choice that is not written to config.
- An operator-initiated run started while playback is live proceeds, with a non-blocking notice that a
  TV is watching.
- Settings → Advanced surfaces both new flags with copy explaining the tradeoff.

## Invariants

- **Deferral never drops work.** Nothing is skipped, cancelled or marked done because a TV was on; the
  work is postponed and picked up afterwards. A deferred pipeline must converge to the same end state as
  an undeferred one.
- **We only ever restore what we changed.** Every external mutation (FR-178-3) records its prior value
  and is reverted only from that record — never to a value we assumed.
- **An explicit human action always wins.** Operator-initiated runs are never silently deferred.
- **Defaults are conservative in opposite directions on purpose:** deferring our *own* work defaults on
  (it is ours to schedule); mutating a *third-party service* defaults off (it is not).
- **This phase changes no playback path.** Nothing here touches negotiation, streaming or the player.

## Out of scope

- **Moving qBittorrent's download target off `sda`, splitting the SSID, or wiring the TV to Ethernet.**
  These are the highest-leverage fixes for the reported problem and they are all infrastructure, not
  code. Recorded in the research report; deliberately not automated here, because the server should not
  be rearranging the operator's storage layout.
- **Jellyfin's own scheduled tasks.** Deferring those means driving Jellyfin's task scheduler over its
  API, which is a real integration with its own failure modes (and its own risk of fighting the
  operator's configuration). Worth doing, but only once FR-178-1 has proven itself on our own work
  first — see Open questions.
- **I/O priority tuning** (`ionice` beyond what Phase 145 already applies). Deferral is a blunter and
  more predictable instrument; if it proves insufficient, priority tuning is the follow-up.
- **Per-disk awareness.** This phase treats "a TV is watching" as global. Deferring work on `sdc` while
  a stream is served from `sda` is unnecessary but harmless, and modelling which file lives on which
  spindle is a large amount of machinery for a three-disk host.

## Source references

- `tv/PlaybackService.kt:88-162` — `PlaybackTracker` (the existing signal FR-178-1 promotes); `:168`
  `nowPlayingItem` (its sole current consumer); `:324+` `stopWatchdogTick` (the precedent for
  server-side reasoning about liveness, including disconnected TVs).
- `torrent/QBittorrentClient.kt` — existing authenticated client (`login`, `getTorrents`), extended by
  FR-178-3 with its first mutating call.
- `config/AppConfig.kt` — `[qbittorrent]` block (url/credentials/path mappings already present).
- `media/PipelineEngine.kt` — Phase 175's single step dispatcher; FR-178-2's deferral belongs at its one
  step loop, not scattered across triggers.
- Related: **Phase 154** (one-run-only pre-run choice — the pattern FR-178-4's override follows),
  **Phase 170** (`SegmentProcessGate`), **Phase 145** (`nice`/`ionice`/`-threads 2` — the CPU-side
  precedent this extends to I/O scheduling), **Phase 110** (the stop watchdog).
- `specs/research-reports/stue-tv-4k-playback-stutter-2026-08-28.md` §4.2 — the latency measurements.

## Amendment (2026-09-05) — deferral was invisible past the moment it started, and blocked manual runs

Answers Open question #5: a real scheduled run finally collided with real playback in production, and it
exposed two bugs neither compile-time review nor the original design caught.

**Live incident.** `docker logs jellystructure`, prod, 2026-09-05: the 15:30:00 hourly scheduled pipeline
deferred at 15:30:00.454 ("Pipeline deferred — TV playing (BRAVIA 4K VH21)") and never resumed — the
16:30:00 run logged "Scheduled run skipped — a scan is already running" against the *same* still-deferred
job. `PlaybackInfo:` negotiation logs show a new title starting roughly every 7 minutes from 15:06 through
at least 16:47 — consistent with a real back-to-back children's-show marathon (Æbler i natkjole,
Tellytots, Hoppy Hare Builders — all short-episode content this household has in its library), not a
stuck session; the stop watchdog never fired in the same 48h window. The deferral logic itself worked
exactly as designed. What broke was everything downstream of it:

1. **The Activity/Scan-console page had no `JobEvent.Deferred`/`Resumed` handling at all** (only
   Dashboard.kt did — `is JobEvent.Deferred ->` at what was then `Dashboard.kt:322`). An operator on that
   page during a defer saw the `scan_files` step chip spinning at 0 items / 0 workers, indefinitely, with
   no explanation and no "Run anyway" button — indistinguishable from a genuine hang.
2. **Even Dashboard.kt's handling was live-WS-only.** `JobEvent.Deferred`/`Resumed` fire once, at the
   instant they happen (by design — see FR-178-4's doc), and are never otherwise recorded. A page
   loaded/reloaded *after* that moment — the common case, since nobody keeps the admin UI open for a whole
   TV session — had no way to reconstruct "deferred, waiting on X" from `GET /scan/status`
   (`ScanStatusResponse` carried no such field), so it silently fell back to a generic "Scanning…" banner
   with no override offered, same practical effect as bug 1.
3. **`POST /scan` and `POST /pipeline/run` 409'd on any `scanTracker.running`, including merely
   deferred.** This is a direct violation of FR-178-2's own stated invariant — "An operator who clicks
   'Scan library' or 'Run pipeline now' while a TV is playing gets their run" and the Invariants section's
   "An explicit human action always wins" — a manual click during a defer got flatly rejected instead of
   preempting it, for as long as the TV kept playing (observed: 3+ hours straight).

**Fix.** `ScanTracker` gained persistent `deferred: Boolean` / `deferredDevices: List<String>` fields
(cleared on every terminal transition: `startNew`, `startResume`, `cancel`, `complete`, `reset`), set by
`awaitPlaybackClear` (`PipelineEngine.kt`) at the same point it broadcasts `JobEvent.Deferred`/`Resumed`,
and surfaced on `ScanStatusResponse`/`ScanStatus` alongside the existing Phase-135
late-joining-client-reconstruction fields (`activeStep`, `stepPlan`, `trigger`/`scope`/`type`) — the exact
pattern that section's own doc comment already described but never extended to cover this case. Both
Activity.kt (new) and Dashboard.kt (extended) now render the "Paused — TV is watching {name}" banner +
"Run anyway" from *polled* status, not only a live WS push — Activity.kt via its existing 2s `pollWorkers`
tick, Dashboard.kt via its page-load hydration path. `POST /scan` / `POST /pipeline/run` now check
`scanTracker.running && !scanTracker.deferred` for the 409 — a merely-deferred run is preempted via the
existing `cancelRun` (safe: nothing has scanned yet, so nothing is lost; `cancelRun` sets `CANCELLED`
synchronously before the route returns, and the old job's own wind-down never touches `scan_state`, so
there's no race with the fresh `startNew()`/`launchScanRun()` that follows).

Compiles clean (`compileKotlinLinuxX64`, `compileKotlinWasmJs`). **Not yet live/device-verified** — no
backend restart or deploy has happened for this fix; the diagnosis above came from reading prod's existing
logs, not from reproducing against the patched build.

## Open questions

1. **Is 120s the right grace window?** Long enough to bridge auto-advance between episodes, short enough
   that an evening of viewing doesn't starve the pipeline indefinitely. Still untested — no real
   between-episode gap has exercised this yet.
2. **Should Jellyfin's scheduled tasks be driven too?** Unchanged — still out of scope; the 2026-08-27
   trickplay pass remains the one heavy reader this phase doesn't control.
3. **Does throttling qBittorrent help enough on its own?** Unchanged — Phase 177's QoE telemetry
   (now built and recording) is what should eventually answer this; no data has accumulated yet.
4. **Implementation note — a known simplification against the spec's literal wording.** FR-178-2 reads
   "does not pick up the next one" (per-item). `scan_files`' probe-heavy work isn't itself a skippable
   step in Phase 175's engine (it runs unconditionally to resolve the working set before the step loop
   starts) — deferral there is coarser than per-item: the whole run's start is gated once, before
   `scan_files` begins, and `fetch_artwork` is re-checked once at its own step's start. `detect_segments`
   is the one step gated at true per-item (per-job) granularity, since MediaJobQueue's segments lane
   already dequeues one job at a time. Real value either way (no burst competes with a live stream), but
   worth knowing before assuming scan_files can be interrupted mid-item.
5. **Not yet done — live verification.** No real scheduled/event-driven run has actually collided with
   real playback under this code yet: the "Paused — TV is watching" banner, "Run anyway", the
   qBittorrent throttle-and-restore cycle, and the crash-safe startup recovery are all compile-verified
   only. The user has authorized stue TV access for this work.
