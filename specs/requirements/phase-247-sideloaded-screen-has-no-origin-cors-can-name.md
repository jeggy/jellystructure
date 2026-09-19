# Phase 247 — A sideloaded screen has no origin the CORS policy can name

## Status

`Planned` — written 2026-09-19, **measured against a live local instance of the real binary the same
day** (not a mock, not a guess about Ktor internals), not built, not dev-reviewed.

## What is wrong

Since R264/R269 shipped, `ravilo-screen` (the sideloaded Tizen receiver) has never run on real Tizen
hardware or its emulator. Its only cross-origin verification was a one-off Playwright run during R269's
dev review, against a mock backend on a different port, that observed "the browser blocked the
cross-origin `fetch` with a CORS error" and filed it as an open question
(`reference-ravilo-screen-cors-open-question` in project memory) — never turned into a repeatable test,
never traced to a root cause.

**Measured 2026-09-19, running the real `linuxX64` debug binary directly** (no Docker, no mocks needed —
this is pure `install(CORS) {…}` plugin behavior, unrelated to Jellyfin/TMDB):

| Request | Result |
|---|---|
| `GET /api/health`, no `Origin` header | 200, full health payload (control) |
| `GET /api/health`, `Origin: null` | **403 Forbidden**, empty body |
| `OPTIONS /api/health` preflight, `Origin: null` | **403 Forbidden** |
| `OPTIONS /api/tv/screen/code` preflight, `Origin: null` | **403 Forbidden** |

(A plain `curl -X POST … -H "Origin: null"` against `/api/tv/screen/code` does reach the route handler
and gets an ordinary 401 — but curl does not implement browser preflight semantics. A real browser's
`fetch()` with a JSON body sends the preflight first, per the table above, and never issues the POST at
all once that preflight is refused. The curl-only data point is not something a real cross-origin caller
would ever see; it is not restated as a requirement below.)

Every one of these calls is a real call `ravilo-screen`'s shipped code makes today:
`probeClient.get("$url/api/health")` in `runServerSetup()` (R269, `Screen.kt:462`), and every
`TvApiClient` call in `pairingLoop()`/`onPlayItem()`/`sendStatus()` (R264/236) after setup completes.

**Why this is a real gap, not a maybe:**

- `Server.kt:269`'s `allowHost(origin, schemes = listOf("http", "https"))` can only ever match a real
  `http`/`https` host:port pair supplied via `CORS_ALLOWED_ORIGINS`. It has no representation for the
  literal string `"null"`, and no representation for a `file://`-style scheme either, since the scheme
  list handed to `allowHost` is hardcoded to `http`/`https`.
- A sideloaded Tizen `.wgt` does not run at a fixed `https://<host>` origin an admin could type into that
  setting. Depending on the runtime it is either the literal string `null` (the standard browser
  behaviour for a cross-origin fetch from an opaque-origin document — `file://`, or a packaged-app
  scheme with no registered origin) or some Tizen-internal scheme neither `allowHost` nor its hardcoded
  scheme list can express.
- So — independent of exactly which of those two shapes the real Tizen 10.0 WebKit runtime turns out to
  send — **the `CORS_ALLOWED_ORIGINS` mechanism as it exists today has no path to admitting a sideloaded
  widget at all.** This was measured against the plugin directly, not inferred from a single failed dev
  fetch.
- **The failure is also silent in a way that will mislead a household.** `runServerSetup()`'s catch-all
  (`Screen.kt:466`, `runCatching { … }.getOrDefault(false)`) shows the identical `setup_not_found` string
  — *"There's no Ravilo server at that address"* — whether the address is genuinely wrong or the address
  is exactly right and CORS is what refused it. A household hitting this bug would be told to re-check a
  correctly-typed address, forever.

**Why every existing e2e test missed this:** `ravilo-login.spec.ts`'s CORS coverage tests the
`ravilo-web` case, a *conventional* `https://<host>` origin the admin already can allowlist — a
structurally different origin shape from a sideloaded widget's. Nothing in the suite has ever sent
`Origin: null`, and nothing has ever run `ravilo-screen`'s own JS against a real backend from a real
second origin.

## Requirements

**FR-247-1 — A permanent e2e test locks in today's measured behaviour.** New
`tests/e2e/ravilo-screen-cors.spec.ts`, run against the real `app` service already in
`docker-compose.test.yml` (no new container needed for this half):
- A plain `GET /api/health` with no `Origin` header succeeds (control, matches every existing test's
  baseline assumption).
- A `GET /api/health` with `Origin: null` gets no `Access-Control-Allow-Origin` header at all.
- An `OPTIONS` preflight against both `/api/health` (unauthenticated — the setup screen's own probe) and
  an authenticated TV route (`/api/tv/screen/code` — the pairing/status calls made after setup) with
  `Origin: null` is refused, matching the measured table above.
- The existing `https://evil.example.com` case from `ravilo-login.spec.ts` is asserted again here,
  side by side with the `null`-origin case, so a future editor sees both refusal shapes in one file and
  cannot "fix" one by accident while leaving the other silently broken (or vice versa).

**FR-247-2 — Reproduce the finding through the product's own code, not just raw headers.** A new
`ravilo-screen` service in `docker-compose.test.yml`, built the same way `ravilo-web` already gets its
own origin in this stack (`ravilo-screen/Dockerfile`, `./gradlew :ravilo-screen:syncScreenReceiver`,
served by `:web-static-server` — the same generic static binary `ravilo-web`'s image already builds, just
pointed at `ravilo-screen/wgt` instead of the wasm bundle). A real Playwright browser page loads
`ravilo-screen`'s actual `index.html` + `ravilo-screen.js` from that origin, types the real `app`
service's address into R269's setup screen, submits, and asserts the setup screen **stays visible** with
the typed address preserved and its hint text changed from "trying" to the not-found string — the exact,
today-true, misleading symptom described above, proven end-to-end in the shipped client rather than
inferred from headers.

**FR-247-3 — The eventual fix must not reopen finding M1, and is not this phase's job.** Not implemented
here — recorded so it isn't guessed at under time pressure later, by whoever picks this up once hardware
answers open question 1:
- The TV/screen API (`/api/tv/**`, `/api/remote/**`) is Bearer-token-authenticated, never cookie-based.
  A page that forges `Origin: null` (trivially done from any site via a `sandbox`ed iframe with no
  `allow-same-origin`) still cannot produce a valid device token it was never given — unlike a cookie,
  which the browser attaches automatically. Allowing `Origin: null` (or whatever exact value Tizen turns
  out to send) on these routes is therefore not the same class of hole as the `anyHost()` +
  `allowCredentials` finding from the 2026-08-02 audit: that one was exploitable *because* credentials
  ride along for free.
- `/api/health` is unauthenticated but already fully readable by anyone who can reach the host directly
  (`curl`, another server) with no CORS involved at all — CORS on it only stops a browser script running
  on an unrelated page from reading it silently, and its payload is operational/diagnostic (queue depths,
  GC stats), not a secret.
- This reasoning is offered for whoever implements the fix to check against real measurements, not as a
  decision already made — see open question 2 on whether Ktor's CORS plugin can even be scoped to those
  two route prefixes, which decides whether this is a small change or needs a custom intercept.

## Non-goals

- Actually changing `CORS_ALLOWED_ORIGINS` / `Server.kt`'s CORS policy. Blocked on knowing the real
  `Origin` value a production Tizen device sends — see open question 1. Guessing at the string and
  shipping a fix for the wrong one is worse than leaving the gap named and tested.
- Publishing the new `ravilo-screen` test-stack image anywhere. It exists only inside
  `docker-compose.test.yml`; the real `.wgt` stays `deploy-tizen-tv.yml`'s job (Phase R272), untouched.
- Any change to `ravilo-web`'s or the admin frontend's CORS handling — both already work and are already
  tested in `ravilo-login.spec.ts`.
- webOS or any other receiver target. `ravilo-screen`'s build comment already names webOS as a future
  `.ipk` follow-on; this phase is Tizen-shaped because that's the only cert/hardware path that exists.

## Acceptance

1. `tests/e2e/ravilo-screen-cors.spec.ts` exists and passes in CI, asserting the exact refusal behaviour
   measured in the table above, for both the unauthenticated setup probe and an authenticated TV route.
2. The same file asserts the pre-existing `https://evil.example.com` refusal too, so both shapes are
   guarded in one place.
3. A real browser loading the actual `ravilo-screen.js` bundle from its own origin against the real
   (mocked) backend reaches R269's stuck-on-setup state with the not-found hint when CORS blocks it —
   proven in the product's own code, not just via `curl`.
4. `STATUS.md` gets a row for this phase; `reference-ravilo-screen-cors-open-question.md` is updated to
   point at it.

## Open questions

1. **What `Origin` (or lack of one) does the real Tizen 10.0 WebKit runtime send** for a cross-origin
   `fetch` from a packaged/sideloaded `.wgt`? Only answerable on the emulator/hardware described in
   `reference-tizen-docker-emulator-setup` — FR-247-3's reasoning is written to hold regardless of the
   answer, but the actual fix cannot be built until it's known.
2. **Can Ktor's CORS plugin be installed scoped to a route prefix** (`/api/tv/**` + `/api/remote/**`
   only, leaving the cookie-authenticated admin API on today's stricter policy untouched), or does
   `install(CORS)` only apply application-wide in the Ktor version this project is on? Decides whether
   FR-247-3's eventual fix is a small, scoped addition or needs a custom intercept ahead of routing.
