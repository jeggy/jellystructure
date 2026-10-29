# A TV signs in from your phone, and a single-sign-on account signs in everywhere

Task: DEB-2233

> Owner, 2026-10-23: *"We want the login in Ravilo apps to work properly (including TV) … support jellyfin users that
> are using OIDC … it should just work across our apps. And I guess our biggest issue is TVs."*
>
> And, the same day: *"users would just use their phone via the Ravilo Mobile app or Ravilo Web app and authenticate
> the TV through that"*, and *"I don't want my ravilo users know what jellyfin even is. It's just the backend behind
> the whole thing."*

## Scope

The work touches both trees:

- **Ravilo clients:** the Android TV app, the phone app, Ravilo Web and the desktop app (Mac and Linux).
- **The backend.**
- **The admin web:** its sign-in page and two Settings fields.

Tizen and the Cast receiver do not change. The investigation, with every measurement this text relies on, is the
`investigation` document on DEB-2233.

**Decided by the owner on DEB-2233's card (`3f471512`, 2026-10-23):**
1. **Amend both constitutions.**
   - A TV may be signed in by a code or QR that a viewer approves, as themselves, in Ravilo on another device.
   - Every client and the admin may sign in through the server's single sign-on.
   - Owner's words: *"on the TV itself it should just be TV code / QR and then the authentication happens on the
     ravilo side on the phone (web or app etc), here it's fine to show sign in with username / password, or login
     via SSO button."*
2. **jellystructure needs to know where Ravilo Web lives:** *"a new config in the admin settings to have a url input to
   point to ravilo web."* This becomes FR-1.
3. **The TV's screen:** the code and QR first, and *Sign in with a password instead* below them.
4. **Single sign-on on the admin web is in.** It ships with the rest.
5. **Android TV and Google TV now.** Tizen waits until Tizen work resumes.

**Decided by the owner before the card, not asked:**
- The approval happens inside Ravilo, never on a Jellyfin page (DEB-13, `24dbe2fc`).
- Jellyfin is invisible to viewers (DEB-13, `d0eb0dda`). This is FR-15, and it now lives in the Ravilo constitution.

**Taken as a reading, flagged to the owner on DEB-2233:**
- Answer 1 ("on the TV itself it should just be TV code / QR") and answer 3 (a password form below the code) are read
  together. The TV offers the code and QR first, keeps the password form one press away, and **never shows a
  single-sign-on button**: single sign-on happens on the approving device.
- If he meant "no password form on the TV at all", FR-5's last bullet goes and nothing else changes.

**Supersedes and amends:**
- The Ravilo constitution's *Authentication & device sign-in*: items 2 and 6 change, and items 7–9 and Key Invariant 12
  are new.
- jellystructure's constitution, *Authentication* item 1.
- Phase 141's and R175's "no Quick Connect" non-goals.
- R360 D2 ("*Add a TV* is removed entirely"). FR-6 brings back a way to sign a TV in from the phone, and it is a
  sign-in, not a cast target.
- DEB-1854's non-goals "No change to sign-in" and "No QR or device-code sign-in".

Each of these carries a dated amendment in its own file.

**Not in scope:**
- The identity-provider and plugin configuration itself. The owner does it once this ships; see *Server setup* below.
- Signing out an account the directory has disabled. This is DEB-1854's non-goal, unchanged.
- Tizen.
- A camera QR scanner inside Ravilo. The phone's own camera opens the link.

## Today (read from `main` at `f5417fa1`, 2026-10-23)

- **Every client signs in one way.** `POST /api/tv/login` relays a username and password to Jellyfin's
  `AuthenticateByName` (`TvRoutes.kt:339-424`) and refuses a blank password. The admin uses `POST /api/auth/login`
  (`AuthRoutes.kt:30-113`) and additionally requires `IsAdministrator`.
- **Accounts without a usable password are locked out of Ravilo.** The server's single-sign-on plugin
  (jellyfin-plugin-sso) creates such accounts with a random sealed password and
  `AuthenticationProviderId = Jellyfin.Plugin.SSO_Auth.Api.SSOController`. `AuthenticateByName` answers 401 for
  them whatever is sent (measured).
- **The admitting group is enforced by the directory, not by jellystructure.** The LDAP plugin's search filter
  requires membership. The single-sign-on plugin's `Roles` option does the same for its own path: an outsider is
  refused at its callback, and no account is created (measured).
- **DEB-1854 already treats a single-sign-on account as managed elsewhere.** Its rule is "any provider id other than
  Jellyfin's default", so the phone, web and desktop show *Change password ↗* or the `pw_elsewhere_none` line, never a
  form. The TV shows no row, and `OtherDevicesResign` never runs for such an account. Nothing in this spec changes that.
- **The pieces that already exist:**
  - **Phase 236's screen pairing** (`ScreenPairingService`): six-character codes, a ten-minute life, a claim secret,
    a single-use poll. Its screen *borrows* the phone's token. FR-3 instead gives the TV a token of its own.
  - **The Cast hand-off redeem**, which has its own rate-limit bucket.
- **Polling the shared login bucket is a likely defect, read but not measured.** The Tizen screen polls every 3 s
  against the login limiter, which allows 5 per minute per IP. That would answer 429 to most polls, and behind one
  household IP it would also block password sign-in. FR-16 keeps the new polls out of that bucket. Phase 236 is not
  changed here; it is paused with Tizen.
- **No client has a QR encoder.** No client registers an App Link or URL scheme. The only browser opening is
  fire-and-forget (`LocalUriHandler.openUri`).

## Functional requirements

### Settings

**FR-1 — Two new settings in the admin's Settings ▸ Server, beside `public_url` and `password_change_url`.**

- **`ravilo_web_url`**: where Ravilo Web is served (for example `https://ravilo.example.net`). It is used for:
  - the TV's QR link (FR-5);
  - the return address of a web single-sign-on (FR-11);
  - the phone app's App Link host (FR-7).

  When it is blank, the TV shows the code without a QR, and Ravilo Web's single sign-on returns to the page it started
  from only if that page's origin is the backend's own.
- **`jellyfin_internal_url`**: the address jellystructure reaches Jellyfin at **without** passing a host-routing
  proxy (for example `http://jellyfin:8096` on a shared Docker network). When blank, `jellyfin_url` is used. FR-9
  explains why single sign-on needs it.
- Both settings are validated like `password_change_url` (DEB-1854 FR-4): blank, or an absolute `http(s)` URL with
  no query or fragment. A bad value is refused field by field.
- Both go into the admin's own `AppConfig` DTO **and** the backend's in the same change. A hand-built
  `PUT /api/config` drops fields the admin DTO does not carry.

### The TV signs in from another device

**FR-2 — A TV gets a sign-in code.**

- `POST /api/tv/signin/code` is open (no token). Its body is `{deviceId, deviceName, platform}`.
- It answers `{code, expiresAt, secret, linkUrl, available}`:
  - `code`: six characters from Phase 236's alphabet (no 0/O/1/I).
  - `expiresAt`: ten minutes from now.
  - `secret`: 32 random bytes in hex. It is stored only as its SHA-256.
  - `linkUrl`: `<ravilo_web_url>/tv?c=<code>`, or null when `ravilo_web_url` is blank.
- Before minting, jellystructure reads `GET /QuickConnect/Enabled`.
  - When it is false, the reply is `{available: false}` with no code, and the TV shows only the password form (FR-5).
  - This read is cached for 60 s.
- `POST /api/tv/signin/poll {code, secret}` answers one of:
  - **202** `{state: "waiting" | "approving", approverName?}`;
  - **200** with the ordinary `PairResult` once approved;
  - **401** for an unknown, expired or used code, or a wrong secret.

  A 200 deletes the row, so a code signs a TV in exactly once.
- A second request for a code from the same `deviceId` replaces that device's earlier unexpired code.

**FR-3 — A signed-in viewer approves it as themselves.**

- `GET /api/tv/signin/preview?code=` (device token) answers `{deviceName, platform}`, or **404** for an unknown or
  expired code. A successful preview moves the code to `approving`, with the approver's display name, so the TV can
  show FR-5's *Approving on ‹name›'s phone…*.
- `POST /api/tv/signin/approve {code}` (device token) signs the TV in **as the caller**. In this order, jellystructure:
  1. calls `POST /QuickConnect/Initiate` with the TV's identity (FR-4);
  2. calls `POST /QuickConnect/Authorize?code=` with the **caller's own `jellyfin_user_token`**;
  3. calls `POST /Users/AuthenticateWithQuickConnect {Secret}`;
  4. passes the result into the unchanged `loginDevice(...)` with `kind = "tv"` and the code row's platform;
  5. marks the code approved.
- The Quick Connect secret never leaves jellystructure, and the whole cycle runs inside the one request.
- **Who may approve:**
  - Only a device-token caller whose row is its own sign-in: a TV, phone, web or desktop row.
  - A borrowing row (`cast`, `screen`) and an API key get **403**.
  - Approval is only ever for the caller. The route takes no user parameter.
- **Never the admin key.** The machine token can approve a Quick Connect code for *any* user (measured: 200). It
  must never be passed to `Authorize`. A unit test asserts that the token `Authorize` receives is the caller row's.
- **Answers:**
  - **204** when signed in;
  - **404** when the code has expired or is unknown;
  - **409** when it was already used;
  - **503** `{reason: "unavailable"}` when Quick Connect is off or Jellyfin cannot be reached;
  - **429** when rate-limited (FR-16).

**FR-4 — The TV's identity, and several people on one TV.**

- The Jellyfin device identity is `ravilo-<tvDeviceId>-<approverUsername>`. This is the same shape a password sign-in
  uses (`TvRoutes.kt:369`). The username is known at step 1 because approval comes first.
- On Jellyfin 12.1 a sign-in replaces only an earlier token with the **same device and the same user** (measured, for
  both `AuthenticateByName` and Quick Connect). So:
  - a second viewer approving the same TV adds a profile and evicts nobody;
  - the same viewer approving again replaces their own earlier token.
- The TV's *Add user* opens FR-5's screen. The profile picker, *Switch profile* and R340's sign-out choices are
  unchanged.

**FR-5 — The TV's sign-in screen (Android TV and Google TV).**

- A TV with no profiles opens on this screen. *Add user* opens it too.
- **The screen shows:**
  - the QR of `linkUrl`;
  - the code in large type, grouped 3 + 3;
  - one instruction naming both ways in: scan with the phone's camera, or open Ravilo on a phone or computer and
    choose *Sign in a TV*;
  - this TV's name.
- **Below them, *Sign in with a password instead*** opens today's `LoginScreen`, unchanged, and Back returns to the
  code.
- It polls every 3 s. While a viewer is approving it says *Approving on ‹name›'s phone…*.
- When the code expires it fetches a new one without saying so. If the next request fails, it shows the ordinary
  could-not-reach-the-server state.
- **Without `linkUrl`** (FR-1 blank) it shows the code and the *Sign in a TV* instruction with no QR.
- **When `available` is false** it opens the password form directly, with no code.
- **The TV never shows a single-sign-on button** (see the reading under *Scope*).
- **Focus:** the first focus target is *Sign in with a password instead*. The QR and code are not focusable, and
  there is nothing to type.
- Needs a QR encoder in `:ravilo-ui` common code: pure Kotlin, drawn on a Compose `Canvas`, with no platform
  dependency.

**Amendment 2026-10-24 (DEB-2324) — what FR-5's build settled.** None of this contradicts FR-5; it fixes what the text left open.

- **The layout is the round's direction A** (`design/incoming/2026-10-23-tv-signin/`): the QR on the left at 222 dp, the title, code, instruction and TV name
  beside it. DEB-2319 records no owner pick between A and B; A is the round's recommendation and the one that holds the 3-metre rule.
  Without `linkUrl` it is one centred column and the code grows.
- **The web address is not printed** under the instruction (the round's decision 2). It is open with the owner. If he picks it, FR-5 gains one line.
- **While a viewer is approving**, *Approving on ‹name›'s phone…* replaces the instruction, on the same two-line height so the code does not move.
- **The could-not-reach-the-server state** shows the ordinary line with **Retry** focused and *Sign in with a password instead* beside it.
  - A failed request for a code is that state.
  - A failed *poll* is not, until five fail in a row (15 s). One dropped packet does not replace a code someone is typing on their phone.
- **DEB-1933's signed-out line** (*the password changed elsewhere*) is shown on the code screen too, under the title, because the password form it used to sit on is now one press away.
- **Opening the password form stops the polling.** Back from it asks for a fresh code.
- **The QR is black on white in every skin**, a whole number of pixels per module, with the standard's four-module quiet zone inside the tile.
- **The design round's QR does not scan.** Its `ts-qr.js` writes the format information transposed (bit 0 at row 8, column 0 instead of column 8, row 0), and `verify-qr.py`
  reads the same layout, so it passes. OpenCV's decoder cannot read that matrix and reads this slice's encoder's output on every size from version 1 to 16. The pictures
  in the round show a code that will not scan; the shipped screen's does.

**FR-6 — *Sign in a TV* on the phone, Ravilo Web and the desktop.**

- **Two ways in:**
  - An entry *Sign in a TV* on the account/profile page of each, beside the account rows R304 placed.
  - Ravilo Web's route `/tv?c=<code>`. The web image serves the app shell at `/tv`, and the app reads the code on
    load (building on DEB-1738).
- **The flow:**
  1. The entry opens a code field (six characters, case-insensitive, spaces ignored). A link arrives with the code
     already filled.
  2. **Not signed in?** The ordinary sign-in comes first: password, or the single-sign-on button (FR-12). The code is
     kept and the flow resumes.
  3. **Several profiles?** The active one approves. The confirm screen names it and offers *Not you? Switch*.
  4. The confirm screen reads ***Sign in on ‹Living room TV› as ‹name›?*** with **Sign in** and **Cancel**. It names
     the TV so a viewer can tell a code that is not on a screen in front of them.
- **Outcomes:**
  - done: *‹Living room TV› is signed in*;
  - expired or unknown: *That code has expired — get a new one on the TV*;
  - already used;
  - unavailable;
  - could not reach the server.

  None of them names a component (FR-15).
- **Wire:** this reuses no route of Phase 236. `POST /api/remote/pair` keeps its R360 role (API compatibility only).

**FR-7 — The QR opens the phone app when it is installed.**

- The Android phone activity declares an `autoVerify` App Link for `https://<host>/tv`. `<host>` is a build property
  (`-PraviloAppLinkHost=`), and the release workflow sets it to the household's Ravilo Web host.
- The Ravilo Web image serves `/.well-known/assetlinks.json` naming the package and its signing certificates: the
  Play signing key and the upload key.
- For any other server's QR, or with no app installed, the link opens Ravilo Web in the browser. That is FR-6's path,
  and it works the same.

**Amendment 2026-10-24 (DEB-2325) — what FR-6's and FR-7's build settled.** None of this contradicts FR-6 or FR-7; it fixes what the text left open.

- **The entry is a row on the Profile page**, *Sign in a TV*, after the password row and before Settings (round `2026-10-23-tv-signin`, surface 2). The Profile
  page is the account page of the phone, Ravilo Web and the desktop, so there is one row, not three. A TV has no such row.
  - Its route is `/account/tv`. **The code is never written into the URL.** A held link uses `/account/tv/<code>` internally, to wait through a sign-in.
- **The code field takes six characters.** Case and spaces are ignored on typing and pasting, and a seventh character is dropped. *Continue* on anything that is not six
  characters of Phase 236's alphabet (no 0, O, 1, I) sends nothing and shows the field's own line, *Type the six characters shown on the TV*, in the error colour.
- **A link's code is looked up at once.** The field is skipped and the confirm screen opens, so *Sign in on ‹TV› as ‹name›?* is the first thing seen. A link whose
  code cannot be one is the *expired* outcome without asking the server.
- **Ravilo Web reads `/tv?c=<code>` once, at load.** Only the exact path `/tv` counts; the backend's own copy of the app under `/tv/` does not. The code goes to an
  in-memory inbox, and the address becomes the app's own (`/` and the hash), so a reload opens the app and not a code that has since been used. The image's SPA
  fallback already serves the shell at `/tv`; nothing about the static server changes for it.
- **Signed out, or on the profile picker:** the code is held as DEB-1738 FR-4 holds a page, and opens on the first Home after the ordinary sign-in or profile pick.
  This slice's sign-in is the password form; the single-sign-on button joins it in slice 5.
- **Several profiles.** *Not you? Switch* is shown only when this device holds more than one profile. It goes to the picker, and the held code comes back on the
  profile picked.
- **Outcomes, by kind.** The wording is the round's:
  - an unknown or expired code (404), at the lookup or at the approval, is *expired*;
  - 409 is *already used*;
  - any other refusal (503, 429, 403, a 5xx) is *unavailable*;
  - no answer at all is the shipped *Couldn't reach the server — try again*.

  *Expired* and *used* end on **Enter a new code**, which returns to an empty field. *Unavailable* and *could not reach* end on **Retry**, which repeats the call that
  failed. *Done* ends on **Done** and returns to Profile. No outcome renders a response body.
- **The phone's App Link is declared only when the build is given `-PraviloAppLinkHost=<host>`.** The property must be a bare host name; anything else fails the
  build. With it, the phone activity declares an `autoVerify` filter for `https://<host>/tv` and **becomes `singleTask`**, so a link opened while the app runs reaches
  that instance. Without it the manifest is unchanged. Both build types take it, so a debug build can be tried.
  - The release workflow passes the repository **variable** `RAVILO_WEB_HOST`. It is a variable, not a secret and not in the tree, because this repository is public.
    Unset, no link is declared.
- **The Ravilo Web image serves `/.well-known/assetlinks.json` from two environment variables:**
  - `ANDROID_APP_CERTS`: the SHA-256 fingerprints of the Play signing key **and** the upload key, as `keytool` prints them, separated by commas or spaces;
  - `ANDROID_APP_PACKAGE`: defaults to `dev.jellystructure.ravilo`.

  An entry that is not a fingerprint is dropped, and with none left the file answers 404, so a typo cannot publish a statement that names nothing.
  A debug build's package is `….debug`; to verify one, run a private image with that package.
- **What CI cannot prove, and QA must:** that Android verifies the link on a real phone (`adb shell pm get-app-links`), that a tap on the QR lands in the app and not the
  browser, and that the confirm screen then names the right TV. CI proves the code normaliser, the confirm flow's states on a fake API, the held route, the strings in
  three languages, the address reader, and the web image's two answers.

**Amendment 2026-10-29 (DEB-2371, finding F4) — where the row is in a wide Ravilo Web window.** QA found the amendment above wrong about one layout. The avatar opens
the Profile page on a phone, in a narrow browser window and in the desktop app (R304, R337). **A wide Ravilo Web window opens the profile menu instead** (R340's
*Switch profile · Your profile · My List · Settings · Support · Sign out*), and nothing in that menu reaches the Profile page. At desktop width *Sign in a TV* could not
be found. FR-6 is unchanged: the entry sits beside the account rows. In a wide Ravilo Web window those rows are in **Settings › Account**, where R234 (FR-R234-4) keeps
*Change password* on every platform. So:

- **A wide Ravilo Web window: Settings › Account has *Sign in a TV* directly after the password place.** That place is *Change password*, or DEB-1854's link or
  sentence for an account managed elsewhere, or nothing when that is hidden. The row comes before *Install Ravilo*, uses the same `tvcode.row` string, and opens the
  same `/account/tv` screen. No new copy.
- **Only there.** A phone, a narrow browser window and the desktop app keep the one row on Profile, and their Settings does not repeat it. A TV draws nothing, as
  before: FR-6's "a TV has no such row" still holds.
- **Keyboard and D-pad order** in that Settings: password place → *Sign in a TV* → *Install Ravilo* → *Sign out*. Where the row is not drawn, the order is today's.
- **A window resized across the layout switch** shows the row in the place its current layout has it.
- **Not chosen:** a row in the profile menu (R340's list is fixed and shared with the TV), and making a wide window's avatar open the Profile page. That would
  change navigation for every wide-window viewer, which is the owner's call if it is ever wanted.

### Single sign-on on every client and the admin

**FR-8 — Discovering it.**

- `GET /api/tv/login/providers` is open. It answers `{providers: [name, …]}` from the plugin's `GET /SSO/OID/GetNames`.
- The answer is cached for 60 s, and it is `[]` when the plugin is absent, errors or lists nothing.
- The admin's sign-in page uses the same list (`GET /api/auth/providers`).

**FR-9 — Starting a sign-in (server to server).**

- `POST /api/tv/login/sso/start {provider, deviceId, deviceName, platform, returnTo}` is open.
  - `returnTo` is `"app"` (with an app scheme or loopback address, FR-11) or `"web"`.
  - jellystructure calls `GET <jellyfin_internal_url>/SSO/OID/start/<provider>` **without following the redirect**.
    It sends the header `Host: <public_url's host>`.
- **How the plugin uses the Host.** It builds its `redirect_uri` as `https://<request Host>/sso/OID/redirect/<provider>`
  (`SSOController.cs:263`, with `SchemeOverride=https`). The identity provider therefore returns the viewer to
  jellystructure, and the browser never visits a Jellyfin address (measured).
  - Through a host-routing proxy the Host header picks the site, not the request, so `jellyfin_url` will not do.
    Hence FR-1's `jellyfin_internal_url`.
- **The answer is `{authorizeUrl, attemptId, secret}`.**
  - `authorizeUrl` is the identity provider's address from the plugin's `Location`.
  - jellystructure keeps the attempt, keyed by the OIDC `state` parameter, for ten minutes. It holds the device
    fields and `returnTo`, and the secret only as its SHA-256.
- **A redirect URI that is not ours.**
  - If `redirect_uri` in `Location` does not name `public_url`'s host, jellystructure answers **503**
    `{reason: "unavailable"}`.
  - It raises the admin dashboard row *Single sign-on is set up wrong: the identity provider would return viewers
    to ‹host›*.
  - The clients hide the button while `providers` is non-empty and start fails this way. This is checked once, at
    the first start after a config change.
- **The admin's start** is `POST /api/auth/sso/start {provider}` (open). It sets a short-lived `HttpOnly; SameSite=Lax`
  cookie, `js_sso`, holding the attempt secret, and answers `{authorizeUrl}`.

**FR-10 — jellystructure receives the identity provider's return.**

- `GET /sso/OID/redirect/{provider}` is a new open route on the backend's public origin. The plugin fixes the path.
- jellystructure:
  1. replays the request to `<jellyfin_internal_url>/sso/OID/redirect/{provider}?<the same query>`, with the same
     `Host` header (the plugin exchanges the code itself);
  2. on 200, calls `POST /sso/OID/Auth/{provider}` with
     `{DeviceID: "ravilo-<deviceId>-sso", DeviceName, AppName: "Ravilo", AppVersion, Data: <state>}`, which returns
     the account's `AuthenticationResult`;
  3. mints the device's real token with a Quick Connect self-approval (FR-3's steps 1–3) under
     `ravilo-<deviceId>-<username>`, then logs the bootstrap token out (`POST /Sessions/Logout`, measured 204, then
     401);
  4. calls `loginDevice(...)` and marks the attempt complete.
- **An admin attempt** instead:
  - requires the `js_sso` cookie to match;
  - requires `IsAdministrator`;
  - creates the ordinary admin session (`sessionService.create`);
  - sets `js_session` as `/api/auth/login` does, and redirects to `/admin`.
- **The plugin's HTML is read and discarded, never forwarded.** Every outcome is a page jellystructure renders, in the
  viewer's language from `Accept-Language` (en, da, fo):
  - **signed in:** *You're signed in — go back to Ravilo* (with FR-11's hand-off);
  - **not allowed:** the plugin answered 401 (role mismatch): *This account can't use Ravilo — ask the person who runs
    it*;
  - **took too long:** an unknown or expired state, including the plugin's one-minute window: *That took too long — try
    again*;
  - **cancelled:** the identity provider returned `error=access_denied`;
  - **unavailable:** Jellyfin or the plugin cannot be reached.

  The admin's pages may name Jellyfin; the viewer pages may not (FR-15).

**FR-11 — The return to the app, bound to the device that started it.**

- **Phishing an attempt is possible without this.** A sign-in started by one device and finished in someone else's
  browser must not sign the first device in. Polling with the secret alone would allow that: start an attempt, send
  the `authorizeUrl` to a victim, collect their session.
- **So completion needs two things:**
  - a one-time **hand-off** value that the success page delivers only by redirect to the starting client;
  - the client's **secret**.
- **Where the hand-off goes:**
  - **Android:** the success page redirects to `dev.jellystructure.ravilo:/signin?attempt=…&h=…`. This is a
    reverse-domain private-use scheme in the RFC 8252 style, declared on the phone activity. It works for any server,
    with no App Link needed.
  - **Desktop:** the app passes a loopback address it is listening on (`http://127.0.0.1:<port>/signin`, RFC 8252 §7.3)
    as `returnTo`. The success page redirects there, and the app's tiny listener answers with a *You can close this
    tab* page and closes.
  - **Ravilo Web:** the success page redirects to `<ravilo_web_url>/?signin=<attempt>&h=…`. It never redirects to any
    other host: `returnTo` cannot carry an address for the web.
  - **The admin:** none. Its cookie is the binding.
- `POST /api/tv/login/sso/claim {attemptId, secret, handoff}` answers **200** `PairResult`, or **401**. It is single
  use, and the attempt is deleted on success.
- The secret lives only in memory: Android's activity state, and Ravilo Web's `sessionStorage` across the round trip.

**FR-12 — The button.**

- The phone, Ravilo Web and the desktop sign-in screens show ***Sign in with ‹name›*** for each name FR-8 lists, above
  the username field. With several names there are several buttons.
- The name is the provider name the server's operator chose, shown verbatim. The *Server setup* section requires it to
  be one viewers recognise.
- Tapping a button calls FR-9, then opens `authorizeUrl`:
  - Android: a Custom Tab;
  - desktop: the default browser;
  - web: the same tab.
- While it waits, the screen shows *Finish signing in in your browser* and **Cancel**. Cancel forgets the attempt.
- The admin's sign-in page shows the same button (FR-9's admin start).
- No TV screen ever shows it (FR-5).

**FR-13 — The admin signs in with single sign-on.**

- This is FR-9 and FR-10's admin path. The rest of the admin session is unchanged: seven days, `HttpOnly`, the same
  401 behaviour.
- A non-administrator gets the existing refusal sentence on the admin's own page.

### Rules that hold across all of it

**FR-14 — A single-sign-on account behaves as an account managed elsewhere, everywhere.**

- DEB-1854 FR-1's rule already covers it. No account path ever relays a password for it, and `OtherDevicesResign`
  never runs for it.
- A directory account later linked to single sign-on keeps its own provider id, and with it password sign-in on the
  TV. The plugin adopts it by name and does not rewrite the provider while `DefaultProvider` is empty (measured).
- This FR restores nothing and needs no code. It is stated so a test pins it: a row whose provider id is
  `Jellyfin.Plugin.SSO_Auth.Api.SSOController` reads `passwordManagedElsewhere = true`.

**FR-15 — Jellyfin is invisible to viewers.**

- **No page and no address.** No viewer path shows a page rendered by Jellyfin or its plugins, or an address on
  Jellyfin's host. The single exception is media bytes, unchanged.
- **No words.** No viewer string, page or error names Jellyfin, LDAP, Quick Connect, the plugin or a status code, in
  any of the three languages. Copy says what the viewer can do.
- **Server error bodies too.** The new routes' error bodies follow the same rule, because a client might one day show
  them. Clients keep classifying failures by kind and never render a raw body.
- **Tests:** a string test in the style of `ProfileStringsTest` covers every new key and every FR-10 page.
- **The admin is exempt.**
- Today's viewer copy already holds this. The two strings that name Jellyfin, `migrate.others` and `migrate.waitIdx`,
  are rendered only by the admin.

**FR-16 — Limits and lifetimes.**

| What | Limit | Lifetime |
|---|---|---|
| Code mint | 10 per minute per IP, in its own bucket | 10 min |
| Code poll | 40 per minute per IP, in its own bucket. A 3 s poll is 20. | — |
| Approve | 5 per minute per approving device | — |
| Single-sign-on start | 10 per minute per IP | the attempt lives 10 min |
| Single-sign-on claim | 40 per minute per IP. A success refunds, as `cast-redeem` does. | — |

- None of these share `/api/tv/login`'s bucket.

**FR-17 — The wire is additive.** New routes and new optional fields only. Nothing an installed client reads is
retyped or removed. An old TV keeps today's password screen and works unchanged against the new server.

## Server setup (the operator's part, once this ships)

This is written for any server. The household's concrete values, with host names, are on DEB-2233's thread.

- **Identity provider:**
  - one OIDC application with a confidential client;
  - **exactly one redirect URI:** `<public_url>/sso/OID/redirect/<P>`;
  - scopes `openid profile`;
  - group claims in `groups`.
- **Plugin (`OidConfigs[<P>]`):**
  - endpoint, client id and secret;
  - `SchemeOverride=https`;
  - `RoleClaim=groups`, and `Roles` = the admitted group;
  - `EnableAuthorization=true`, `EnableAllFolders=true`, `PreserveAdminPermissions=true`, `AdminRoles=[]`;
  - `DefaultProvider` **empty**;
  - `DefaultUsernameClaim` = the claim that matches the directory's usernames.

  Why these values: without `EnableAuthorization`, new accounts get no libraries (measured). Without
  `PreserveAdminPermissions`, an adopted administrator is demoted at the next login. A non-empty `DefaultProvider`
  rewrites every adopted account's provider on each login.
- **`<P>` is the button's text.** It must be a name viewers recognise, never the server software's name. It also
  sits in a URL path, so no spaces.
- **jellystructure:** set `jellyfin_internal_url` and `ravilo_web_url` (FR-1). No identity-provider credential is ever
  given to jellystructure.

## Acceptance

1. A throwaway single-sign-on account (created by the plugin, password unknown) signs in on the Google TV Streamer and
   the Sony BRAVIA without typing on the remote, in both of these ways:
   - by scanning the QR, landing in the phone app if installed, else Ravilo Web;
   - by typing the code in Ravilo Web on a laptop.
2. The same account signs in on Ravilo Web, the Android phone and the desktop with the button.
3. A second profile joins a shared TV the same way, and neither viewer's TV session is evicted.
4. Local and directory password accounts sign in exactly as before on every client, including the password form on
   the TV.
5. A single-sign-on account never sees a password form that can fail, and is never re-signed with a password.
6. An account outside the admitted group cannot get in by any path: the button reaches *This account can't use
   Ravilo*, and approving a TV is impossible without a Ravilo session.
7. An existing directory administrator who signs in with single sign-on keeps:
   - administrator rights;
   - watch history;
   - password sign-in.
8. On every viewer path, including failures, expired codes and wrong-group refusals, no Jellyfin or plugin page, no
   Jellyfin address and no word from FR-15's list appears.
9. The admin signs in with the button. A non-administrator gets the refusal.
10. **Started on one device, finished on another: refused.** An `authorizeUrl` started on one device and finished in
    another browser does not sign the first device in (FR-11).
11. An old installed TV app signs in with its password against the new server (FR-17).
