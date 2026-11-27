# An invite link sets up a new viewer's app, and a typed address is checked before it is saved

Task: DEB-5850

> Owner, on DEB-5687: *"Let's try to find UI/UX improvements we could make to Ravilo. We want to make the app beautiful
> in our edge cases, but remember that QoE and performence is most important. We are also now getting more and more
> users."* — and on 2026-11-26 he picked this piece on the card *"Ravilo polish: what to build"*.

## Scope

The admin gets a link and a QR for this server. A new viewer opens it and reaches a sign-in with nothing typed, then
signs in **themselves**, by password or single sign-on. **The link carries the server's address and nothing else:** no
credential, no account, no code (option S1 of the DEB-5687 research `research-invite-link`).

- **In:** the backend (one admin route, one public page), the admin frontend (*Users & devices*), the Compose setup
  screen on Android TV, phone, macOS and Linux, and the Android phone's link handling.
- **Ravilo Web** needs no client change: it already starts with no setup (`ravilo-web` `RaviloRootActuals.kt:76-79`),
  and the page's *Use Ravilo in the browser* opens it.
- **Out:** the Samsung app (it already probes before saving, `ravilo-screen/…/ScreenSetup.kt:57`, FR-R269-3), the
  Chromecast receiver (the phone tells it the server), iOS.
- **No sign-in path changes.** No route under `/api/tv/login`, `/api/tv/signin/*` or single sign-on is touched, no
  limit moves, no grant table is added (so DEB-2672 FR-4's deletion list is unchanged).
- Nothing here is on the playback start path or in TV focus motion. The probe (FR-7) runs once, on Connect, on the
  setup screen only.
- It adds a route, which Phase 227 FR-227-7 said that phase would not; this spec is the later one that does.
- Amends R226 (its probe and placeholder non-goals) and DEB-5002 (a pasted invite link), each with a dated note in its
  own file. It cites, and does not change, the wording of `setup.server_prompt` in DEB-5836.

## Why

- First run on a Compose client shows a raw IP as the example (`ServerSetupScreen.kt:175`, hard-coded) and saves
  whatever is typed (`:182`, `:221`). A typo surfaces only on the next screen, as *"Couldn't reach the server — try
  again"*, the same sentence as an outage. On a TV the next screen is the code screen.
- No link anywhere carries an address. The admin has nowhere to get one. The backend has no page for a person who has
  no app yet: every non-API path falls to the admin console's shell (`Server.kt:947`).
- More people are joining. Each one is told an address by voice or chat and types it.
- The main risk is phishing, not replay: the address decides where the app sends a username and password
  (`POST /api/tv/login`). So the shape of the feature is the defence (FR-6), not a limit.

## Requirements

### FR-1 — The invite answer (backend, additive)

- New admin route `GET /api/tv/admin/invite`, beside `/tv/admin/overview` (`TvRoutes.kt:1133`), with the same admin
  session check. It answers `RaviloInvite { url: String?, address: String? }`.
- `url` is `public_url + "/join"`. `address` is `public_url`'s host and port with no scheme (as
  `SingleSignOnService.publicHost()`), the text a viewer types on a TV (the scheme is inferred, R226).
- Both come from configuration only (`AppConfig.publicUrl`, through `PublicUrl.effective`), never from a request
  header (FR-227-4). With `public_url` unset or invalid, both are `null` (FR-227-6).
- Not in `OPEN_API_PATHS`. A non-admin or a device token is refused. No installed client calls it, so no old app
  is affected.

### FR-2 — The invite sheet (admin frontend)

- Layout: option A1 from design round DEB-5861 (owner, 2026-11-27): the link and Copy first, the QR below it.
- *Users & devices* gains an **Invite a viewer** button on its page bar (`RaviloUsers.kt:42`, beside *Refresh*).
- It opens a sheet that shows, from FR-1's answer only (no link is assembled in the frontend):
  - the link, with **Copy**;
  - a QR of the link, drawn from `QrEncoder.encode` (`:shared`) by a small new DOM drawer;
  - *On a TV, open Ravilo and type* followed by `address`;
  - one line: *The link holds no password. The viewer signs in with their own account, which must exist on this
    server first (or is made at their first single sign-on).* (Settled 2026-11-27, design round: an admin reads this
    line out to a viewer, and a viewer never hears the server software's name.)
  - **Copy** shows a local *Copied* for 2 s. It is a confirmation of the admin's own press, not server state, so it is
    outside the server-pushed-state rule.
  - The QR drawer sizes its modules from the matrix it is given. `QrEncoder.encode` already chooses the smallest
    version that holds the link, so a long host gives a larger version, never a failure. Proof: a 60-character host
    draws and decodes back.
- With `url` null: no link, no QR, no Copy. The sheet says *Set the Public address in Settings → Connections first*
  and links there.
- New classes go in `design/app/wf.css`. Admin text is English and is not in `i18n/`.

### FR-3 — The landing page, for someone with no app yet (backend)

- `GET /join` is a page rendered by the backend, in a new `InvitePages` object beside `SingleSignOnPages`. Same
  shape: one self-contained document, inline CSS and SVG, `noindex`, `no-referrer`, English, Danish or Faroese from
  `Accept-Language` (`SingleSignOnPages.languageFor`). The route sits ahead of the admin catch-all and sets
  `Referrer-Policy: no-referrer` and `Cache-Control: no-store`.
- It shows the word Ravilo and the server's host, and never the server software's name, a version or a status code
  (Key Invariant 12; DEB-2233 FR-15). Its query string is ignored.
- Four parts, in this order (owner, 2026-11-27, design round DEB-5861: option L1, *Open in Ravilo* first):
  There is no iPhone button: iOS is out of scope, and the browser part is the iPhone route.
  1. **Open in Ravilo**: `dev.jellystructure.ravilo:/join?s=<public_url>`, the value written from configuration.
     This is the page's one primary button.
  2. **Get the app**: one button per platform. **Android** links to `<webBase>/join?s=<public_url>` on the Ravilo
     Web address (FR-5a): a phone with Ravilo installed opens the app with the invite, and any other phone opens
     Ravilo Web (owner, 2026-11-27: *"link to [the Ravilo Web address] which in it self would link to the already
     installed android app, but if not, then just use ravilo web"*). With `webBase()` null the Android button is
     absent. macOS, Linux and Samsung TV each link to the newest release file, with one line: *After installing,
     open this link again.* (No install referrer exists, so a link never survives an install.)
  3. **Use Ravilo in the browser**: the Ravilo Web address (`SingleSignOnService.webBase()`). Absent when that is null.
  4. *On a TV, open Ravilo and type* followed by the host.
- With `public_url` unset, `/join` answers 404 with a one-line page in the same shape: *This invite link isn't set up.*
- Generated per request from configuration. It reveals nothing the open code mint (`linkUrl`) does not already reveal,
  so it has no rate limit of its own.

### FR-4 — A pasted invite link gives its address (Android TV, phone, macOS, Linux, Ravilo Web)

- `serverUrlFromAddress` (`ServerSetupScreen.kt:74`), after its whitespace clean-up, turns `https://<host>[:port]/join`
  (with or without a query) into `https://<host>[:port]`.
- Only `https`, and only the path `/join`. The address applied is the link's own origin. **A query never supplies a
  host.** Any other text is handled exactly as today.
- This is the whole desktop and Ravilo Web story: the viewer pastes the link into the address field.

### FR-5 — The phone app opens from the invite (Android phone)

- The phone activity's private-scheme filter (`AndroidManifest.xml:130`) gains `android:path="/join"`. The TV activity
  gets no filter.
- `MainActivity` hands `s` to a new `ServerInviteInbox` (pattern: `TvLinkInbox`, `TvApproveStore.kt:173`). The inbox
  keeps a value only if it is an `https` origin with no user info, path, query or fragment; anything else is dropped.
- `offerSsoReturn` (`MainActivity.kt:66`) also checks the path is `/signin`, so a `/join` link is never read as a
  sign-in return.
- In `RaviloRoot`, with no saved address, the inbox value becomes the setup field's `initialAddress`, taken once.
  Nothing is saved until the viewer presses Connect (FR-7 then applies).
- An old phone app has no `/join` filter, so the page's button does nothing there. Part 4 of the page still works.

### FR-6 — A link never sets an address on its own (every client in FR-4 and FR-5)

- A link only fills the field. The viewer sees the host and presses Connect.
- With a saved address, an arriving invite is dropped, whatever it names. Changing server stays *Wrong server?*.
- The address a link gives is `https` only. A typed `http://` LAN address still works; this rule is in the link
  parser, not in the platform (`usesCleartextTraffic` stays).
- Pre-filled from a link, the setup screen shows one line above the field: `setup.from_invite`. The line stays through
  a failed check (FR-7) until the viewer edits the field.
- Pre-filled from a link, **focus starts on Connect**, not the field (TV and pointer clients alike).

### FR-7 — Connect checks the address first (Android TV, phone, macOS, Linux)

- Connect sends `GET <url>/api/health` and saves only on a 200 whose body has `"status":"ok"`, as the Samsung app does
  (`ScreenSetup.kt:49-66`). Timeout 8 s.
- While it waits, the button reads `receiver.setup_trying` and a second press does nothing.
- On a failure the typed text stays, the line `receiver.setup_not_found` shows under the field, and focus stays on
  Connect, so trying again is one press. No status code, no protocol (R280).
- **Ravilo Web keeps today's save-at-once.** A cross-origin probe would meet CORS and report a working server as
  missing.
- The hard-coded `192.168.1.1:8097` example becomes the key `setup.server_example`.
- The prompt's wording is DEB-5836's (`setup.server_prompt`, *Enter your Ravilo server address*), not changed here.

### FR-8 — Viewer text

New keys in `i18n/en.json`, `da.json` and `fo.json` (R279). Danish and Faroese are drafts with the note
*draft, review before release* (the DEB-2078 pattern). Reused unchanged: `receiver.setup_trying`,
`receiver.setup_not_found`, `receiver.setup_connect`.

| Key | en | da (draft) | fo (draft) |
| --- | --- | --- | --- |
| `setup.server_example` | ravilo.example.com | ravilo.example.com | ravilo.example.com |
| `setup.from_invite` | From your invite. Check the address, then connect. | Fra din invitation. Tjek adressen, og forbind så. | Úr tíni innbjóðing. Kanna adressuna, og bind so í. |

The landing page's strings live in `InvitePages`, in three languages, as `SingleSignOnPages.kt:29-40` holds its own;
the Danish and Faroese there are drafts under the same note.

## Design

New layout, one round under `design/PROCESS.md`:

- **Admin invite sheet:** link and Copy, the QR, the TV line, the account line, and the unset state. New `wf.css`
  classes.
- **Landing page:** phone-first, in the single-sign-on pages' look (round DEB-2319). The host, the four parts, the
  *open this link again* line, the 404 state.
- **Setup screen, on TV, phone and desktop:** pre-filled from an invite with the `setup.from_invite` line, the
  waiting state, the not-found state, the new example. Draw it in the same round as DEB-5002 FR-4/FR-5's hidden-
  character line, which sits in the same place.

### FR-5a — The Android button opens the app, or Ravilo Web (Android phone, Ravilo Web)

- The phone's App Link filter (`ravilo-android/src/appLink/AndroidManifest.xml`, DEB-2233 FR-7) claims
  `https://<raviloAppLinkHost>/join` beside `/tv`. Same host, same `autoVerify`, same `assetlinks.json`; no new file
  and no new build property. A build without `-PraviloAppLinkHost` declares neither path, as today.
- In the app, `/join` on that host goes through FR-5's `ServerInviteInbox`, with the same checks on `s`, and FR-6
  holds: the address is shown and the viewer presses Connect.
- **Ravilo Web serves `/join` as its own start page** (the image's single-page fallback must answer it, not 404) and
  **ignores `s` entirely.** Ravilo Web already uses its saved address, then the operator default, then its own origin
  (`RaviloRootActuals.kt:76-79`), so a query from a link never chooses its server.
- An installed app that is not verified for the host (a sideloaded build signed with another key) opens Ravilo Web in
  the browser, which still works.

## What this does not do yet

- **A link that signs someone in (S2), and self-service accounts (S3).** Both contradict the Ravilo constitution's
  *Authentication & device sign-in* invariants: *"no device is ever signed in as someone who did not approve it"*
  (item 2), *"jellystructure's machine token never approves a sign-in"* (item 7), and the admitting group is the
  directory's and the plugin's (item 9). They are owner and
  constitution questions, not build items. Where single sign-on is set up, S1 already gives a self-service start.
- An App Link on the backend's own host. The only App Link host stays DEB-2233 FR-7's build-time Ravilo Web host, which now claims `/join` as well as `/tv` (FR-5a).
- A URL scheme on macOS or Linux, and a link handler on Android TV or Samsung. A TV has no camera and no browser.
- Sending an address from a phone to a TV (R269's non-goal stands).
- A server name or household name on the page. The server has none; the page shows the host.
- Telling a typo from a device that is offline on the not-found line.
- Whether stored profile tokens survive a server change in the Compose clients. Unverified; its own row.

## Acceptance

- `GET /api/tv/admin/invite` answers `url` and `address` from `public_url`, and both null with it unset; a forged
  `Host` header changes nothing; a non-admin is refused.
- `/join` in en, da and fo names no server software, carries `noindex` and `no-referrer`, answers 404 with no address,
  and ignores any query. The existing sign-in limit tests pass untouched.
- The admin sheet's link equals the route's answer, its QR has a drawn matrix, and the unset state shows with no
  address.
- `https://h.example/join?x=1` pasted gives `https://h.example`; `http://h.example/join` and
  `https://h.example/join?s=other.example` never give `other.example`; `https://h.example/other` is as today.
- On a phone with no address, the invite button fills the field and saves nothing; with an address, it changes nothing.
- On Android TV, a typo stays in the field with the not-found line; a good address saves and opens the code screen.

## Build order

One engineer. Each slice ≤ ~200 changed lines.

1. **FR-4, a pasted link.** `ravilo-ui/…/screens/ServerSetupScreen.kt` (`serverUrlFromAddress`), `ServerAddressTest.kt`
   (the four Acceptance cases). Proof:
   `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-ui:desktopTest --tests '*ServerAddress*' --console=plain`.
   **Junior-ready.**
2. **FR-1, the invite answer.** `src/linuxX64Main/…/server/routes/TvRoutes.kt`, the `RaviloInvite` DTO,
   `src/linuxX64Test/…/tv/RaviloInviteRouteTest.kt` (unset, forged `Host`, non-admin). Proof:
   `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew linuxX64Test --console=plain`.
3. **FR-3, the landing page.** After the design round. `src/linuxX64Main/…/tv/InvitePages.kt`, the route ahead of
   `Server.kt:947`, `InvitePagesTest.kt` (three languages, no server-software name, headers, 404, query ignored).
   Proof: `linuxX64Test`, and the `SingleSignOnTest` limit cases unchanged.
4. **FR-2, the admin sheet.** After the design round and slice 2. `src/wasmJsMain/…/ui/RaviloUsers.kt`,
   `api/RaviloApi.kt`, a QR drawer, `design/app/wf.css`. Proof: a case in `tests/e2e/ravilo-users-sessions.spec.ts`
   (pattern: `ravilo-screen-signin.spec.ts:210` for the QR) through `docker-compose.test.yml`.
5. **FR-5, FR-6 and `setup.from_invite`, the phone.** `ravilo-android/src/main/AndroidManifest.xml`, `phone/MainActivity.kt`,
   `ServerInviteInbox` beside `TvLinkInbox`, `RaviloRoot.kt`, `i18n/*.json`. Proof:
   `:ravilo-ui:desktopTest --tests '*ServerInviteInbox*'` (an origin kept once; a path, a query, `http` or user info
   dropped; a saved address drops it) and `:ravilo-i18n:desktopTest`. Device check on a QA row (the button from a
   browser page opens the app).
5a. **FR-5a, the Android button.** After slices 3 and 5. `ravilo-android/src/appLink/AndroidManifest.xml` (`/join`
   beside `/tv`), the Ravilo Web image's single-page fallback for `/join`, and FR-3's Android button. Proof: a case in
   `InvitePagesTest.kt` (the Android button is `<webBase>/join?s=…`, absent with `webBase()` null), and a Playwright
   case that opens `/join?s=https://other.example` on Ravilo Web and asserts the page starts against its own server.
   Device check on a QA row: on a phone with the release build, the button opens the app; on a phone without it,
   Ravilo Web.
6. **FR-7 and `setup.server_example`, the probe.** After the design round, with DEB-5002 FR-4..FR-7 in the same hands
   (same file). `ServerSetupScreen.kt`, a probe seam on Android and desktop (none on web), `i18n/*.json`. Proof:
   `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :ravilo-ui:testDebugUnitTest --tests '*ServerSetupProbe*' --console=plain`
   (a failed probe keeps the text and saves nothing; a good probe saves once; a second press while waiting does
   nothing) and `scripts/check-ravilo-strings.sh`. Device check on Android TV on a QA row.

Slice 4 follows slice 2. Slices 3, 4 and 6 follow the design round.
