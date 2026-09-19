# Phase 245 — a device_status push must never omit a field at its Kotlin default

> Found 2026-09-19 while verifying R272's CI pipeline: `tests/e2e/screens.spec.ts`'s Phase 236 test
> failed in CI with `subscribedPositionMs: undefined` (expected `30000`) — unrelated to the CI work
> itself, but blocking the whole release pipeline (`publish.yml` gates Docker/Play Store/Tizen behind a
> green `ci`), so fixed here rather than left for later.

## Status

`✓ Built` — fixed and verified locally 2026-09-19 (full `tests/e2e/` suite green, 34 passed / 2
pre-existing skips, against a from-scratch local build of the `app` image). Not yet re-verified in CI at
spec-write time — that's this fix's own next step.

## Root cause (two independent bugs, one symptom)

1. **The server's WS re-broadcast used the bare `Json.Default`** (`encodeDefaults = false`, kotlinx's own
   default) when re-serializing a `ScreenStatus` for `TvEventBus.notifyDeviceStatus` — at both call sites
   (`TvRoutes.kt`'s `/tv/playback/status` handler, `Main.kt`'s device-reaped path). Any field sitting at
   its Kotlin default (`position_ms = 0`, `buffering = false`, ...) was silently omitted from the JSON
   text frame. The receiving end is a JS/TS client with no concept of a Kotlin default, so a subscriber
   reading `status.position_ms` got `undefined`, not `0` — this project has already hit and fixed this
   exact footgun elsewhere (`HomeFeedService`/`PlaystateCache`/`AcquisitionService` each deliberately use
   `Json { encodeDefaults = true }` for their own wire-JSON), just missed at this newer call site. The
   client→server hop is unaffected (the DTO's own Kotlin defaults recover any field a sender omitted), so
   only the two re-broadcast sites needed the fix, not `TvApiClient`'s outbound encoder.
2. **The test's `waitForType` helper returned the first matching message in its buffer**, not the next
   *new* one — so once bug 1 was fixed and `position_ms` started arriving, the assertion still read stale
   `0` from the earlier (step 8) status push rather than the `30000` push that followed the seek. A test
   bug, not a product one, but it was masking (1)'s fix from being provable until also corrected.

## Fix

- `TvRoutes.kt` / `Main.kt`: a file-local `Json { encodeDefaults = true }` instance (matching this
  project's existing per-file convention for wire-JSON, not a new shared cross-file instance), used at
  both `notifyDeviceStatus` call sites.
- `screens.spec.ts`: `waitForType` takes an optional `fromIndex`, and the seek-status assertion now
  records `phoneMessages.length` immediately before triggering the action it's waiting to observe, so it
  can only match a message that arrives after that point.

## Note for later, not fixed here

The server's global `ContentNegotiation` plugin (`Server.kt:193`) is *also* configured with the bare
`Json { ignoreUnknownKeys = true }` — no `encodeDefaults = true` — meaning any plain REST response built
by `call.respond(dto)` (not just this WS path) can omit a default-valued field the same way. This
specifically affects `/api/remote/devices`' embedded `nowPlaying: ScreenStatus?` (`RemoteRoutes.kt`),
returned via ordinary `call.respond`. Not changed here: flipping a server-wide serialization default is a
much larger-radius change than an unattended late-night fix should make, and deserves its own look at
what else on the REST surface might be relying (even accidentally) on a field being absent rather than
present-at-default. Worth a dedicated phase if it ever causes a second symptom.
