# Phase 247 — A sideloaded screen has no origin the CORS policy can name

## Status

`✓ Built` 2026-09-19 (FR-247-1, FR-247-2); **FR-247-3 built 2026-09-25** for the one route that needed
it, measured on the Tizen 10.0 TV emulator and against the real binary, not dev-reviewed, not yet on a real
Samsung TV.

**Open question 1 answered 2026-09-25 on the Tizen 10.0 TV emulator — in two halves, and the first answer
was half wrong.** The widget runs at `file://`. Once its `config.xml` declares
`<access origin="*" subdomains="true">` (it did not — without it the web runtime refused every external
request as `net::ERR_UNKNOWN_URL_SCHEME`), its **HTTP** requests reach the server **with no `Origin`
header at all**, and their responses are readable with no `Access-Control-Allow-Origin`; a preflighted
POST is sent directly. So REST needs no exception — that part stands.

**The events WebSocket does.** The same morning's note said *"no backend exception is needed"*; that was
written from `fetch` alone. The first time the emulator app was paired and left running, its
`/api/tv/events` socket was refused — `Error during WebSocket handshake: Unexpected response code: 403` —
and a listener on the host captured the handshake: **`Origin: file://`**. A WebSocket is not governed by
the browser's CORS, so Chromium sends the document's origin on every handshake whatever the widget's
`<access>` policy says, and the server's CORS plugin is the only thing that looks at it. A paired TV could
therefore never receive `play_item`, a command or a status push — pairing worked, casting to it could not.
Every e2e run missed it because the test stack serves the bundle from `ravilo-screen-cast:8080`, an
allow-listed origin (248). Tizen 5.0 (the RU7440) is unverified: it may send `file://`, `null`, or nothing.

**Open question 2 is answered, 2026-09-20, from the artefact rather than by experiment** (the same
technique 238's review used on the Curl engine's header handling). In
`ktor-server-cors-linuxX64Main-3.6.0.klib`:

- `io.ktor.server.plugins.cors.routing.CORS` — the one this project already imports
  (`Server.kt:72`) — is built with **`createRouteScopedPlugin`**. So **yes**: CORS can be installed
  on a route rather than the whole application, and the eventual FR-247-3 fix is a small scoped
  addition, not a custom intercept ahead of routing.
- The older `io.ktor.server.plugins.cors.CORS` is `createApplicationPlugin` and carries
  `@Deprecated(level = ERROR)` — *"This plugin was moved to io.ktor.server.plugins.cors.routing"*.
- Worth knowing before implementing: the route-scoped variant registers its own
  `options("{cors-options-wildcard...}")` handler on the route it is installed into, so installing a
  second, looser CORS on `/api/tv` + `/api/remote` adds a preflight handler under those prefixes
  rather than replacing the application-wide one.

Measured 2026-09-25 as well: the application-level and route-level installs cannot coexist — Ktor throws
`DuplicatePluginException: Installing RouteScopedPlugin to application and route is not supported.
Consider moving application level install to routing root.` — and a child route's CORS **replaces** its
parent's rather than adding to it. FR-247-3 is built around both facts.

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

**FR-247-3 — The one route a widget cannot reach gets exactly the one origin it sends, and finding M1
stays closed.** Built 2026-09-25.
- **Scope: `/api/tv/events` only.** It is the only socket `ravilo-screen` opens, and its only credential is
  the device token in the query (or `Authorization`) — no cookie is read on it. `/api/remote/events` is
  untouched (the receiver never opens it; phones are native or same-origin), and so is the admin `/ws`,
  which *is* cookie-authenticated: a cross-site WebSocket to it would carry `js_session`, which is exactly
  the class of hole the 2026-08-02 audit closed.
- **The origin is `file://`, exactly — never `null`.** `null` can be sent by any web page (a `sandbox`ed
  iframe), and desktop browsers serialise a `file:` document's origin as `null`, so no web page can send
  `file://`. Admitting it on a token-only route lets a widget in and lets no website in.
- **One policy, restated.** The global CORS install moves from the application to the routing root (Ktor
  refuses both); `/api/tv/events` installs its own, which is the shared policy plus the one origin — because
  a child's config replaces its parent's, anything less would quietly drop `CORS_ALLOWED_ORIGINS` (the dev
  server's origin) from that route. Both live in `server/CorsPolicy.kt`, so they cannot drift apart.
- **What the move changes, and why it is safe:** the CORS check now runs inside routing, after
  `AuthPlugin`. A disallowed origin still never reaches a handler (the route's own CORS runs first), but a
  request that fails *both* checks now answers 401 rather than 403, and a path no route matches answers
  404 without a CORS verdict. Preflights are unchanged: `AuthPlugin` already lets them through (R225's
  amendment), and the root's `options("{cors-options-wildcard...}")` answers them.
- **Tests:** `CorsPolicyTest` (backend) runs both policies in a real in-process application — `file://`
  admitted on the events route and refused everywhere else, `null` and `https://evil.example.com` refused
  everywhere, an allow-listed dev origin still admitted on both. `ravilo-screen-cors.spec.ts` asks the same
  questions as real WebSocket handshakes against the real binary: `file://` → 101 on `/api/tv/events` and
  403 on `/ws`; `null` and the evil origin → 403 on both.

## Non-goals

- Any change to `CORS_ALLOWED_ORIGINS`, or to the policy on any route but `/api/tv/events`. REST from a
  widget needs no exception (open question 1), and no other socket is opened from one.
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
5. (FR-247-3) A paired `ravilo-screen` on the Tizen emulator holds its `/api/tv/events` socket open and
   plays what a phone sends it; `/ws` and every other route refuse `file://` exactly as before.

## Open questions

1. ~~**What `Origin` does the real Tizen 10.0 WebKit runtime send?**~~ **Answered 2026-09-25 on the
   emulator:** none on `fetch`, `file://` on a WebSocket handshake. **Still open for Tizen 5.0** (the
   RU7440) — if it sends `null` on the handshake, this route needs a different answer, since `null` is the
   one value that cannot simply be admitted.
2. ~~**Can Ktor's CORS plugin be installed scoped to a route prefix**~~ (answered — see Status) (`/api/tv/**` + `/api/remote/**`
   only, leaving the cookie-authenticated admin API on today's stricter policy untouched), or does
   `install(CORS)` only apply application-wide in the Ktor version this project is on? Decides whether
   FR-247-3's eventual fix is a small, scoped addition or needs a custom intercept ahead of routing.
