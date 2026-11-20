# Phase 247 — A sideloaded screen has no origin the CORS policy can name

## Scope

Backend CORS for the sideloaded Tizen widget (`ravilo-screen`), plus the e2e tests that pin it. FR-247-1 and
FR-247-2 built 2026-09-19; FR-247-3 built 2026-09-25 for the one route that needed it, measured on the
Tizen 10.0 TV emulator and against the real binary. **Amended by DEB-4654, DEB-4719 and DEB-5129** — see the amendments
at the foot.

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

## Amendment 2026-11-15 (DEB-4654) — the full Tizen app needs nothing more

The Tizen app becomes a full app (`specs/ravilo/requirements/ravilo-on-a-samsung-tv-is-a-full-app.md`). It
makes more REST calls (Home, browse, search, detail, played state, My List) and loads artwork with `<img>`,
but it opens no other socket, so **this phase's FRs and non-goals stand unchanged**:

- REST from the widget needs no exception (open question 1). The artwork routes (`/api/tv/image/`,
  `/api/tv/channel-logos/`) are open and an `<img>` is not CORS-checked.
- `/api/tv/events` stays the only socket. A build piece that needs another socket, `/api/remote/events` or
  `/ws`, stops and amends this phase first. The cookie-authenticated `/ws` never admits `file://`.
- **Open question 1 stays open for Tizen 5.0 and is carried to the owner's real-TV test** (the full app's
  FR-16). If the set sends `Origin: null` on the handshake, the events route needs a different answer.
  Browsing and playing from the TV would still work; a phone sending to it would not.


## Amendment 2026-11-16 (DEB-4719) — on Tizen 5.0 a widget's POST carries an Origin

**Measured** by Turið on DEB-4704, 2026-11-16: Tizen 5.0 TV emulator (`ravilo-tv-5`, Chromium 63), `.wgt`
built from `e377880b`, against prod `v1.67-35-gf1bded57`.

- Every POST from the widget got an empty **403**: `/api/tv/login`, `/api/tv/signin/code` and
  `/api/tv/playback/start`. An empty 403 is the CORS plugin's refusal (the table above). `AuthPlugin`'s
  refusals carry a JSON body.
- Every GET with the device token got **200** from the same page, including `/api/tv/subtitles/…`.
- By curl, `Origin: file://` and `Origin: null` both got 403. With no `Origin`, the request reached the route
  and got its own 400 or 401.

So open question 1's *"REST needs no exception"* holds on 10.0 only. On 5.0 the runtime still lets the widget
past the browser's CORS. That part is inferred, not measured: an authenticated GET would otherwise be
preflighted and refused, and it is not. But Chromium 63 puts the document's origin on every non-GET request,
and the server's CORS plugin refuses it. On the owner's set, sign-in and every play from the TV are refused.
DEB-4654's *"REST from a widget needs no CORS exception (247)"* is false there.

**Not yet measured: the value the page itself sends.** The curl runs prove that both candidates are refused.
They do not show which one the page sends, and the page-side capture was not kept. The 5.0 events handshake
(open question 1) was never reached either, because the screen could not sign in. **Step 1 of FR-247-4's
acceptance measures both before anything else is verified.** The expected answer is `file://`, for two
reasons. It is what the same runtime family sent on 10.0's handshake. And Tizen's web runtime gives a widget
universal file access, under which Chromium writes a `file:` document's origin as `file://`, not `null`. That
is an expectation, not a fact.

**FR-247-4 — Every token-only route admits the widget's origin, and every cookie route keeps refusing it.**

- **Scope: the routes `AuthPlugin` authenticates by device token or API key and never by a cookie, plus the
  open paths beneath them.**
  - `/api/tv/**`, except `/api/tv/admin/**`, which is cookie-authenticated and keeps the shared policy.
  - `/api/remote/**`. The widget POSTs `/api/remote/play` for the next episode (`Screen.kt`, `playNext`).
    That branch accepts an API key or a device token and nothing else: no token, no request.
  - `/api/tv/events` moves into this scope. `/api/remote/events` now admits the widget too. This replaces
    FR-247-3's *"`/api/remote/events` is untouched"* and the first non-goal's *"or to the policy on any route
    but `/api/tv/events`"*. Both of those routes read only a token.
- **Unchanged, still the shared policy:** `/ws`, `/api/tv/admin/**`, `/api/auth/**`, the SSO routes, and every
  other `/api/**` route. `/api/health`, the setup probe, is a GET, and on 5.0 a GET carries no `Origin`.
- **A prefix, not a named list.** The 247 reasoning holds: a route that reads no cookie gains nothing from an
  origin gate. That property belongs to the `AuthPlugin` branch, and the branch is chosen by prefix. A named
  list would go stale the first time the full app adds a POST (My List, played state). That POST would then
  403 on the owner's set and nowhere in CI. In return the prefix carries one rule: **a route under these
  prefixes that ever reads a cookie re-installs the shared policy**, as `/api/tv/admin/**` does.
- **The origin is `file://` exactly**: FR-247-3's `TIZEN_WIDGET_ORIGIN`. `null` stays refused everywhere. If
  step 1 measures `null`, the build stops and the security call below is taken first.
- **One policy, restated.** FR-247-3's events policy becomes the widget policy: the shared policy plus the one
  origin, still in `server/CorsPolicy.kt`. It is installed over both prefixes, and `/api/tv/admin` re-installs
  the shared policy, because a child's CORS replaces its parent's (measured 2026-09-25). Today the routes are
  registered as `post("/tv/login")` under `route("/api")`. How the install reaches them is the build's
  choice; the requirement is the outcome.
- **Tests.** `CorsPolicyTest` in a real in-process application:
  - `file://` is admitted on a POST to `/api/tv/login`, `/api/tv/signin/code`, `/api/tv/playback/start` and
    `/api/remote/play`, and on the `/api/tv/events` handshake. Admitted means no 403; the route's own answer
    comes back.
  - `file://` is refused with 403 on an `/api/tv/admin/` route, on `/ws`, on a cookie `/api/` route and on
    `/api/auth/login`.
  - `null` and `https://evil.example.com` are refused on all of them.
  - An allow-listed dev origin is still admitted on all of them.

  `ravilo-screen-cors.spec.ts` asks the same questions with real requests and handshakes against the real
  binary.

**If 5.0 sends `null` (the security answer, for Tróndur and not the owner; the Tizen screen is in no prod
image).** 247 refused `null` on a token-only route as a precaution, not because of a named attack: a page that
forges `null` can only use credentials it already holds, since these routes read no cookie. On the wider
scope it gains exactly one thing: **the viewer's network address**. A hostile page could make each visitor's
browser send `/api/tv/login` (or `/api/tv/account/password`) password guesses and read the answers, spreading
`LoginRateLimiter`'s per-address window across its visitors. The fresh codes that `/api/tv/signin/code` and
`/api/tv/screen/code` answer are no gain: one curl gets the same code. **Recommendation, if it comes to that:**
admit `null` on the scope except the two routes that take a password, and on such a set the Tizen screen signs
in by code only.

**Acceptance (FR-247-4)**, on the Tizen 5.0 emulator first:

1. **Measure before verifying.** Point the installed widget at a listener on the host, as on 2026-09-25, and
   record the `Origin` header of one POST (`/api/tv/signin/code`) and of the `/api/tv/events` handshake. Write
   both values on the task. This closes open question 1 for 5.0.
2. Against a build with FR-247-4, the widget signs in by both routes, plays an item, and holds its events
   socket. A phone's play reaches it, and so does the next episode (`/api/remote/play`).
3. A curl matrix against the same build, with `file://`, `null` and `https://evil.example.com` × a POST
   route, `/ws` and an admin route. `file://` passes only on the POST route. Everything else gets 403.

## Amendment 2026-11-16, second (DEB-4719) — the 5.0 POST sends `null`, and `null` is admitted on the token routes

**Measured** by Óluva on DEB-4721, 2026-11-16. Tizen 5.0 TV emulator `ravilo-tv-5`, `ravilo-screen` from `e377880b`,
document `file:///index.html`, captured by a listener on the host:

```
POST: Origin: null      (the widget's own POST /api/tv/signin/code; no preflight)
handshake: Origin: file://   (/api/tv/events)
GET: no Origin header   (the setup probe, /api/health)
```

The same page sends two values. Chromium 63 writes a `file:` document's origin as `null` on a fetch or XHR POST, and
as `file://` on a WebSocket handshake. **Open question 1 is closed for 5.0.** The handshake sends what 10.0 sends,
so FR-247-3 holds on the owner's set. The first amendment's expectation (*"`file://`, not `null`"*) holds for the
handshake and is wrong for the POST. FR-247-4 as built (`8e0d90a6`) is correct and stays, but on its own it unblocks
nothing on 5.0.

**Tróndur's security call** (on DEB-4719, 2026-11-16; not an owner question, because `ravilo-screen` is in no prod
image). It replaces the first amendment's *"`null` stays refused everywhere"* and narrows the recommendation made
there.

**FR-247-5 — `null` is admitted on the token-only TV routes, except the two that take a password.**

- **Scope: `/api/tv/**`**, minus `/api/tv/admin/**`, `/api/tv/login` and `/api/tv/account/password`. Those three keep
  FR-247-4's widget policy: `file://` is admitted and `null` is refused.
- **`/api/remote/**` does not admit `null`.** The widget's next episode (`POST /api/remote/play`) is therefore refused
  on 5.0, which is accepted (see the full-app spec's amendment of the same date). FR-247-4's `file://` stays there.
- **Why it is safe, and the condition it rests on.** None of these routes accepts a cookie as a credential. So a
  page that forges `null` (a sandboxed iframe) can only use credentials it already holds. What such a page *would*
  gain is the visitor's network address. On the two password routes, that would spread password guesses across
  `LoginRateLimiter`'s per-address window, so those two stay out. The fresh codes that `/api/tv/signin/code` and
  `/api/tv/screen/code` answer are no gain, since one curl gets the same code.
- **The condition is pinned by a test, not by this prose.** On every route that admits `null`, a request carrying
  only a valid `js_session` cookie is treated exactly as one with no credentials:
  - 401 on a device-token route;
  - the anonymous answer on an open path (`/api/tv/signin/code`, `/api/tv/image/…`).

  The test walks the routes the server actually registers under the `null` scope, not a hand-kept list. A route
  added under `/api/tv` that ever reads the cookie therefore fails the build instead of silently inheriting `null`.
  Such a route re-installs the widget policy, as the two password routes do.
- **Still refused everywhere:** `https://evil.example.com`, `file://evil`, and any origin that is not `file://`,
  `null` (in scope) or `CORS_ALLOWED_ORIGINS`. `/ws`, `/api/auth/**`, the SSO routes and every cookie `/api/**`
  route keep the shared policy.
- **Tests.** `CorsPolicyTest` and `ravilo-screen-cors.spec.ts` gain a `null` row across signin/code, playback/start,
  the events handshake, `/api/tv/login`, `/api/tv/account/password`, an `/api/tv/admin/` route, `/ws`,
  `/api/remote/play` and `/api/auth/login`:
  - admitted on signin/code, playback/start and the events handshake (on the handshake the browser sends
    `file://`, but the policy admits `null` there too);
  - an empty 403 on every other cell.

  The cookie-only test above comes on top. FR-247-4's `file://` cells are unchanged.

**Acceptance (FR-247-5), on the Tizen 5.0 emulator:**

1. With `AbortController` fixed (DEB-4727; without it no request leaves the page), the widget signs in **by code**,
   plays an item it chose itself, holds its events socket, and plays what a phone sends it.
2. Password sign-in on the 5.0 set shows the full-app spec's code-only message, not "Couldn't reach the server".
3. The curl matrix against the same build: `Origin` ∈ {`file://`, `null`, `https://evil.example.com`} × {POST
   `/api/tv/signin/code`, POST `/api/tv/login`, `/ws`, an `/api/tv/admin/` route, POST `/api/remote/play`}.
   - `file://` passes on signin/code, login and remote/play.
   - `null` passes on signin/code only.
   - Every other cell gets the empty 403.
4. A cookie-only request (an admin `js_session`, no bearer) to a device-token `/api/tv/` route sent with
   `Origin: null` answers 401.

## Amendment 2026-11-20 (DEB-5129) — `null` is admitted on `/api/remote/**` too

**Found** by Turið's QA on DEB-5125, Tizen 5.0 emulator `ravilo-tv-5`, `.wgt` 1.70.100 from `0fe2282de`, against prod.
On a start a phone pushed, the control bar's **Next** (and the next-up card) stops the item and then gets an empty
**403** from `POST /api/remote/play`; nothing plays and the viewer lands on Home. From the host with the same device
token: `Origin: null` → 403, `Origin: file://` → past CORS. This is the refusal the second DEB-4719 amendment chose
(*"`/api/remote/**` does not admit `null`"*). DEB-4865 moved only a start the TV made itself off that route, so a
phone's push still goes through it. That is also why DEB-5076 saw Next work on 5.0: its start came from the TV, and
the TV's own path never calls `/api/remote/play`.

**Tróndur's call** (on DEB-5129, 2026-11-20). It replaces the second DEB-4719 amendment's *"`/api/remote/**` does not
admit `null`"* bullet.

**FR-247-6 — `/api/remote/**` admits `null`, the same way FR-247-5's `/api/tv` scope does.**

- **Scope:** every route under `/api/remote`. `CorsPolicy.kt` installs `tizenWidgetNullPolicy` there in place of
  `tizenWidgetPolicy`. `/api/tv/login`, `/api/tv/account/password` and `/api/tv/admin/**` are unchanged: `null` is
  still refused on all three.
- **FR-247-5's condition holds there. Checked route by route at `0fe2282de`.** AuthPlugin's `/api/remote/` branch takes
  only a `Bearer` (or `X-JS-Api-Key`) API key or device token, and answers 401 to anything else. It never falls through
  to the cookie branch. `/api/remote/events` is an open path that validates `token` or `api_key` from the query string
  or a `Bearer` header inside the route. No route takes a password or reads `js_session`, and none of them has a
  per-address rate limiter. The six routes are: `GET /devices`, `GET /devices/{device_id}`, `POST /play`,
  `POST /command`, `POST /pair` (a device token only, and the claim code is checked only after it), and
  `DELETE /devices/{device_id}/sessions/me`, plus the `/events` socket. So a page that forges `null` can use only a
  credential it already holds. The network address it borrows from its visitor gains it nothing it could not get with
  that credential from anywhere: the most it changes is the *nearby* flag on the device list.
  `/api/settings/api-keys` is not under `/api/remote` and keeps the shared policy.
- **A route added under `/api/remote` that ever reads the cookie or takes a password** re-installs the `file://`-only
  widget policy, the way the two `/api/tv` password routes do. The test below is what catches it.
- **No wire change and no client change.** The screen's `playNext()` already calls `/api/remote/play` for a phone's push.
  It starts working on 5.0 once the server admits the origin. An installed `.wgt` needs no update.
- **Tests.**
  - `CorsPolicyTest`:
    - `/api/remote/play` moves from the `null`-refused list into the `null`-admitted list (`nullPosts`). The test must
      assert both that `null` is admitted on `/api/remote/play` and that it is still refused on `/api/tv/login`,
      `/api/tv/account/password` and `/api/tv/admin/config`, with no `Access-Control-Allow-Origin` header.
    - The cookie-only walk (*"a cookie alone is no credential on any route that admits null"*) gains
      `/api/remote/play`, with AuthPlugin wired with a device-token validator that rejects everything. A request that
      carries only an admin `js_session` and `Origin: null` answers exactly what a bare request does: 401.
    - The `file://` cells and the foreign-origin cells are unchanged.
  - `ravilo-screen-cors.spec.ts`: no new admitted cell. `/api/remote/play` is not an open path, so a credential-less
    request gets AuthPlugin's 401 before CORS can be seen there. That is the reason the file already gives.
  - `ravilo-screen-play-from-tv.spec.ts`'s DEB-4865 case keeps mocking `/api/remote/play` as a 403. It proves that the TV's own
    path never asks the route, which stays true.

**Acceptance (FR-247-6):**

1. `CorsPolicyTest` is green with the pins above.
2. The curl matrix from the second DEB-4719 amendment changes in one cell: `null` now passes on `POST /api/remote/play`
   (a device token and an unknown `device_id` answer 404 `device not found`, not the empty 403). `null` is still the
   empty 403 on `/api/tv/login`, an `/api/tv/admin/` route and `/ws`.
3. On the Tizen 5.0 emulator, Turið re-runs DEB-5125's Next and next-up legs on a start a phone pushed, against a
   backend with the fix. The next episode plays and the set does not drop to Home. Samsung Next is not called working
   until this passes.

**Not in this amendment:** a password sign-in from a 5.0 set stays refused (`/api/tv/login` and
`/api/tv/account/password` keep `file://` only), and the screen still stores *code-only* for that server. Whether that
should change is a separate security-versus-UX question, on its own row.
