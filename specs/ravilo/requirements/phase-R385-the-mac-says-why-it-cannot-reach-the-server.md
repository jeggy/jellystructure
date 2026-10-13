# Phase R385 — The Mac says why it cannot reach the server

> QA, 2026-10-12 (DEB-756, found on the Mac walk DEB-721): *"A translocated Ravilo cannot reach the backend at all, and
> the only thing the user sees is **Couldn't reach the server** · Check the connection and try again · Retry."*

## Status

`✓ Built` 2026-10-12 (see Build notes; acceptance 1–6 are a Mac walk, partially done — see Walk notes, 2026-10-13 —
and the row in `STATUS.md` is the status). Written 2026-10-12
(PO-authored from DEB-756). Not dev-reviewed. The Mac app (R328–R331, R337) and the shared error classifier R237/R280
wrote.

## What was seen

MacBook Pro, macOS 27.0.1, official `ravilo-mac-1.50.dmg` and `ravilo-mac-1.51.dmg`, both directions tested twice:

- A quarantined `Ravilo.app` **not moved by Finder** is **App-Translocated** by macOS: it runs from
  `/private/var/folders/…/AppTranslocation/<uuid>/d/Ravilo.app`. In that state **no request reaches the backend**.
- The household's server host resolves **inside the house to a 10.x address** — a LAN address — so every call needs
  macOS **Local Network** consent, and the consent granted to `/Applications/Ravilo.app` does not cover a translocated
  copy: to TCC it is a different app (R330 dev review 8 already says this about discovery; it is true of every socket).
- The shell on the same Mac reached the host fine (`401` from the ping route), so nothing was wrong with the network,
  the DNS or the server.
- The same build, de-quarantined and run in place from `/Applications`: the library loads instantly and `cast`
  discovers all five targets.

So the app told the viewer to check their connection when their connection was fine, and offered a *Retry* that could
not succeed however many times it was pressed — R237's own mistake, in a state R237 never knew about.

**Who it reaches.** Anyone who double-clicks Ravilo inside the mounted `.dmg` rather than dragging it to Applications —
a common habit — and anyone who installs it by any means other than a Finder drag. The household's own installs were
Finder drags, so this has not bitten the household.

## Decisions

**D1 — Diagnose on failure, never at launch.** A translocated app whose server resolves to a *public* address works
perfectly well (there is no Local Network consent in the way), and a viewer away from the house is exactly that case.
So the install state is never a banner on startup and never blocks anything; it only ever *replaces the wrong
explanation* once a request has actually failed with no answer.

**D2 — Certainty first.** Where the app is running from is a fact the app can read. Whether Local Network consent was
granted is not: macOS has no public API for it (open question 1). So the two causes are ordered — an install-state
message is shown when the app can prove it, a permission message only as the best remaining explanation of a
LAN-resolved host that answers nothing.

**D3 — macOS's own labels stay macOS's.** *Local Network*, *Privacy & Security* and *Applications* are what the
viewer's Mac calls them, so the copy calls them that — the precedent is R331's `mac.open_anyway`, which names *Open
Anyway* and the pane for the same reason. The constitution's rule the copy still obeys: name what the viewer must do,
never the component that failed. No "TCC", no "translocation", no "bundle" in any string.

**D4 — The release notes are not touched, and neither is the download page.** FR-R331-6 was withdrawn by the owner on
2026-09-30 (*"people downloading dmg already know how to install a dmg"*) and stays withdrawn. The install sentence
this phase adds goes **in the app**, under the update line that already carries the *Open Anyway* sentence.
**Owner, 2026-10-12 (asked on DEB-756): the site's download page is not to say it either — "the in-app line is enough;
leave the site alone".** So FR-R385-6 is the only place the drag is written, and no change to `gamlihandil`'s download
copy belongs to this phase or to any other without the owner reopening it.

## Requirements

**FR-R385-1 — The app knows where it is running from.** `ravilo-desktop` resolves its own install state once at
startup, from the launcher path (`jpackage.app-path`, with `compose.application.resources.dir` as the fallback), into
three values:

| State | How it is recognised | What it means |
|---|---|---|
| `IN_PLACE` | anything else | the normal install; no message ever |
| `TRANSLOCATED` | the path contains `/AppTranslocation/` | a quarantined copy macOS is running from a throwaway location |
| `ON_VOLUME` | the path starts with `/Volumes/` | run straight out of the mounted disk image |

No new Swift and no change to the native library's ABI (the self-test's `library ABI 4` line stays as it is). The
value is exposed to shared code the way R335 exposes `PlaybackAvailability` — a platform sets it, `ravilo-ui` reads it,
every other platform leaves it `IN_PLACE`.

**FR-R385-2 — The app knows whether its server is on the local network.** The configured server host is classified
once per resolution into `LAN` (a private or link-local address: `10/8`, `172.16/12`, `192.168/16`, `169.254/16`,
`100.64/10`, loopback, or any `isSiteLocalAddress`) or `PUBLIC`. A host that does not resolve at all is `UNKNOWN` and
is treated as `PUBLIC` — an unresolvable name is a DNS answer, not a permission state.

**FR-R385-3 — Two new causes, in the one classifier.** `LoadErrorKind` gains `MAC_INSTALL_PLACE` and
`MAC_LOCAL_NETWORK`. They are **not** classified from the throwable; they refine an existing `UNREACHABLE` that had no
HTTP response at all, and only on the Mac:

| Install state | Server host | A transport failure becomes |
|---|---|---|
| `TRANSLOCATED` or `ON_VOLUME` | `LAN` | `MAC_INSTALL_PLACE` |
| `TRANSLOCATED` or `ON_VOLUME` | `PUBLIC` | `UNREACHABLE`, unchanged (D1 — this install works) |
| `IN_PLACE` | `LAN` | `MAC_LOCAL_NETWORK` |
| `IN_PLACE` | `PUBLIC` | `UNREACHABLE`, unchanged |

An HTTP answer of any status is never either of these: a reply proves the socket.

**FR-R385-4 — What each one says.**

| Cause | Heading | Body | Actions |
|---|---|---|---|
| `MAC_INSTALL_PLACE` | **Move Ravilo to your Applications folder** | *Ravilo is running from a temporary copy, which can't reach your home network. Quit Ravilo, drag it to your Applications folder, and open it from there.* (`ON_VOLUME`: *…running from the disk image…*) | **Quit Ravilo** only |
| `MAC_LOCAL_NETWORK` | **Ravilo needs Local Network access** | *Your server is on this network. Open System Settings → Privacy & Security → Local Network and turn Ravilo on.* | **Open Settings** · **Retry** |

No *Retry* on `MAC_INSTALL_PLACE`: nothing the viewer can press changes it, which is R280 FR-R280-3's whole rule.
*Retry* stays on `MAC_LOCAL_NETWORK`, because granting the permission is exactly a thing that changes the answer.
**Open Settings** is R330's existing button and pane.

**FR-R385-5 — Everywhere the wrong line appeared.** The refinement applies at all three places a Mac shows an
unreachable server: **server setup and sign-in** (R328 FR-R328-7, `login.error_unreachable`), **any failed screen
load** (`LoadError`), and the music pages' own failure (R345's `loadErrorKindOf`). The *Play on…* sheet keeps R330
FR-R330-8's one line unchanged — it is about discovery, is already right, and is not a full-screen error.

**FR-R385-6 — The update line says how to install.** `mac.open_anyway`, under the update line's *Download* (R328
FR-R328-8), gains the drag first: *"Open the disk image and drag Ravilo to your Applications folder — opening it from
inside the image stops it reaching your network. If macOS won't open it, choose Open Anyway in System Settings →
Privacy & Security."* One string, en · da · fo.

**FR-R385-7 — The test driver reports it.** The Mac test driver prints the install state and the server host's class
on connect, so a walk can record which state it was in without reading paths by hand.

**FR-R385-8 — Strings** × en · da · fo (da/fo drafts; the shipped table wins):
`mac.install_place.title`, `mac.install_place.body_translocated`, `mac.install_place.body_volume`,
`mac.local_network_blocked.title`, `mac.local_network_blocked.body`. `mac.menu_quit` and `mac.open_settings` are
reused, `mac.open_anyway` is rewritten (FR-R385-6).

## Out of scope

The web app's version of the same trap (a Chrome without the Local Network grant cannot reach a LAN-resolved host —
recorded in R376's notes; a browser permission the app cannot read or fix) · Linux and the Flatpak (no such permission
model) · notarisation or anything with an Apple bill (R331's out of scope) · the GitHub release notes (D4) · moving the
app for the viewer, or any write outside the app's own data directory.

## Acceptance

From the Mac, with the official `.dmg` — a QA walk, not a dev claim. Acceptance 1 and 3 need the owner's Mac to be
able to translocate at all (see open question 2).

1. **The bug.** Download the `.dmg`, mount it, double-click Ravilo **inside the image**: the first failed call shows
   *Move Ravilo to your Applications folder* with **Quit Ravilo** and **no Retry**, and **does not blame the viewer's
   connection or ask them to check it**.
   *(Amended 2026-10-13, DEB-763: this clause read "says nothing about the network or the connection", which
   contradicts FR-R385-4's own approved body — the sentence names the home network on purpose, because
   "which can't reach your home network" is the reason that makes the drag worth doing. The clause was shorthand for
   R237's mistake, not a ban on the word; the shipped string is correct and stays, the acceptance line moves. No
   viewer-visible change, so this is not an owner call.)*
2. **The control.** The same build dragged to `/Applications` and de-quarantined: the library loads, and no install
   message appears anywhere in the app.
3. **The permission.** From `/Applications`, turn **Ravilo** off in System Settings → Privacy & Security → Local
   Network: a failed load reads *Ravilo needs Local Network access*, **Open Settings** opens that pane, and after
   turning it back on **Retry** loads the library. **Turn it back on afterwards.**
4. **No false positive.** With the server configured to a host that resolves to a public address and the backend
   stopped, the plain *Couldn't reach the server* with *Retry* appears, unchanged from today.
   *(Amended 2026-10-13, DEB-763: **"and the backend stopped" is struck** — `macRefinedUnreachable` keys on
   `host != LAN` alone, so any public-resolving address with nothing answering is the same control. Walked with
   `192.0.2.1:9999` — TEST-NET-1, reserved, routes nowhere — so no third party is contacted and **prod is never
   stopped for this step**. An unresolvable name covers the `UNKNOWN` row the same way.)*
5. **No false positive, the other way.** A translocated copy pointed at a public-resolving server that is up signs in
   and plays, with no install message (D1).
   *(Note, 2026-10-13, DEB-763: this step cannot be self-served from inside the house. Every household name is
   split-horizon — all of them resolve to the `10/8` address from the LAN — and the WAN address does not answer from
   the LAN (no NAT loopback; the same root cause as the two Wi-Fi points that could not cast on 2026-09-30). The owner
   supplies the address (answered on this card 2026-10-12). Before reading the step as valid, confirm the driver
   prints `host=PUBLIC` for it: a `100.64/10` or `fc00::/7` address classifies as `LAN` under FR-R385-2 and would test
   the opposite of what the step means.)*
6. The update line in Settings ▸ Account reads FR-R385-6's sentence, in en, da and fo.
7. A unit test covers FR-R385-3's table and FR-R385-4's mapping — install state × host class × failure shape →
   heading, body and buttons — and that every key it names exists in all three tables (R280's test precedent).

## Build notes (2026-10-12)

**Built** — Kotlin only, no Swift and no native call, so the self-test's `library ABI 4` line is untouched (FR-R385-1).

- `ravilo-ui/.../components/MacDiagnosis.kt` (new) — `MacInstallState` (IN_PLACE · TRANSLOCATED · ON_VOLUME),
  `ServerHostClass` (LAN · PUBLIC · UNKNOWN) and `MacDiagnosis`, the object a platform sets and `ravilo-ui` reads,
  shaped like R335's `PlaybackAvailability`. FR-R385-3's table is the pure `macRefinedUnreachable(install, host)`:
  null when `installState` is null (every platform but the Mac), null for any host that is not `LAN`,
  `MAC_LOCAL_NETWORK` for `IN_PLACE`, `MAC_INSTALL_PLACE` otherwise. `UNKNOWN` sits in `PUBLIC`'s row.
- `LoadError.kt` — the two kinds, and the refinement inside the one `classifyLoadFailure` branch that already means
  *no HTTP response at all* (`answerlessFailure()`). That placement is what makes FR-R385-3's last line structural
  rather than a rule to remember: a reply of any status never reaches it. `retryable` follows the buttons — false for
  the install cause, so R237's auto-retry ladder stops too; true for the permission, so the loop keeps working across
  the grant. `loadErrorTitleKey` maps both; `loadErrorBodyKey` takes the install state as a defaulted parameter, since
  that is the one sentence that depends on a second fact (a copy macOS made, or the mounted image).
- **FR-R385-5 came free at two of its three places**: every store funnels through `loadErrorKindOf` →
  `LoadErrorState` (R280's doing, and R345's for the music pages), so neither needed a line. **Sign-in** was done by
  hand: `LoginScreen`'s one-line *Couldn't reach the server — try again* becomes the heading and the sentence, with a
  clickable *Open Settings* line under the permission cause — a line and not a focusable, so R349's explicit D-pad
  chain through the form is untouched, and this surface is a Mac, where a click needs no focus. The form itself is
  unchanged, so what the viewer typed survives either way. **Deliberate call:** *Sign in* is still there under the
  install-cause copy. It is the form's own button, not a Retry on an error surface, and taking it away would leave a
  sign-in screen that cannot be used at all. **Server setup needed nothing**: it never reaches the server — it saves
  the address and hands over to sign-in, which is where the failure has always appeared, so both halves of
  FR-R385-5's first bullet are the one change.
- `LoadErrorState` draws what each cause allows: **Quit Ravilo** alone (`mac.menu_quit`, wired to `Main.kt`'s own
  `quit`, so FR-R328-9's stops are reported before the process ends), or **Open Settings** above **Retry**. The Mac's
  Retry has its own `FocusRequester`, so Home's `extraAction` keeps the one it already used.
- **A fourth surface the spec did not name**: the player's failed-start card (`PlayerSessionErrorOverlay`, R237)
  renders a `LoadErrorKind`, so these two can now reach it. It uses the same two keys and `loadErrorOffersRetry`
  instead of its own copy of the retry rule (identical for every kind that was there before) — no new string, no new
  button. Saying *check the connection* there while another screen says the truth is the drift this phase is about.
- `ravilo-ui/.../desktop/MacInstall.kt` (new) — the two readings. `jpackage.app-path`, then
  `compose.application.resources.dir`; `/AppTranslocation/` is asked before `/Volumes/`. The host comes out of the
  configured base URL (IPv6 brackets handled) and is resolved on `Dispatchers.IO`: `LAN` if **any** answer is private
  — `isLoopbackAddress` / `isLinkLocalAddress` / `isSiteLocalAddress` / `isAnyLocalAddress`, plus `100.64/10` and
  IPv6 `fc00::/7`, which no JDK predicate knows. `start(scope)` is shaped like `UpdateCheck.start`: it follows the
  configured server and asks again every quarter of an hour while the answer is `UNKNOWN`. Called from `Main.kt`.
- R330's `CastPlatform.openLocalNetworkSettings` is **folded into `MacDiagnosis.openLocalNetworkSettings`** — one
  seam, set at startup instead of when the cast sender is first remembered, because sign-in offers the same button
  long before any sender exists. FR-R330-8's line in the sheet reads the new one; `CastDesktop` also calls
  `MacInstall.install()` (idempotent), so a sender can never find the seam unset.
- FR-R385-7 — `MacDiagnosis.describe()` → `install=… host=…`, in the driver's *listening on* line and on **`info`**,
  which is what a connecting client actually reads (the host is resolved a beat after the socket is up, so the startup
  line can honestly still say `host=UNKNOWN`). `install=n/a` on Linux.
- FR-R385-6/-8 — `mac.open_anyway` rewritten and the five new keys in `i18n/en.json`, `da.json` and `fo.json` (da/fo
  drafts). macOS has no Faroese, so the Faroese table names the viewer's own macOS labels in English, as it already
  did for *Open Anyway*.

**Tested (acceptance 7).** `:ravilo-ui:desktopTest` green on Linux.

- `ravilo-ui/src/commonTest/.../MacDiagnosisTest.kt` — FR-R385-3's eight rows; `UNKNOWN` as `PUBLIC`; nothing refined
  off the Mac; an answerless failure becoming each cause; **no HTTP status whatsoever becoming either** (401, 403,
  404, 409, 429, 500, 503, 418); Retry on the permission and never on the install, asserted on `retryable` as well as
  on the button; the heading and body mapping including `ON_VOLUME`'s own sentence; every key in all three tables;
  D3's banned words (*transloc*, *tcc*, *bundle*, *quarantin*) absent from all six strings in all three languages;
  and FR-R385-6's drag-before-Gatekeeper order.
- `ravilo-ui/src/desktopTest/.../MacInstallTest.kt` — the real translocated path from the walk, `/Volumes/…`,
  `/Applications/…`, a null path (run from Gradle), and a viewer's own folder called `Volumes` or
  `AppTranslocationNotes` (neither is a state); the host out of four URL shapes; every private range the FR names and
  five routable addresses; an unresolvable name as `UNKNOWN`.

**Not claimed here.** Acceptance 1–6 are the Mac walk — nothing was installed, launched or driven from this seat.

**One limit for that walk to watch.** The `LAN` reading needs the host to resolve. A unicast DNS lookup is made by
macOS's own resolver daemon rather than by the app's socket, so it is expected to answer even while the app's own
connections are being refused — but a server named with `.local` (mDNS) would have the lookup itself blocked, the
class would read `UNKNOWN`, and the app would deliberately fall back to the plain *Couldn't reach the server*. The
household's host is a real DNS name, so acceptance 1 and 3 should show `host=LAN` on the driver's `info`. If they show
`UNKNOWN`, that is the finding to write down.

## Walk notes (2026-10-13, DEB-763 — partial: 1, 2 and 4 passed, 3, 5 and 6 owed)

The first Mac walk ran 22:19Z–22:37Z on 2026-10-12 and was **cut off mid-walk**, not finished: the run ended on a
provider quota limit at 22:37:49Z, the owner withdrew the MacBook's board tick at 23:31:27Z, and the three board rows
were released at 01:05:30Z with the Mac left holding the test build (that residue is **DEB-763's own step 1** — it
was briefly a separate card, DEB-839, folded back in and cancelled the same night once the owner put the restore on
the same wake as the walk; and the next walk re-reads the Mac rather than trusting the 22:37Z snapshot). The residue
is not inert: prod's `ravilo_device` row for that Mac read `app_version 1.51` at 22:23Z and **`1.49` with
`last_seen` 2026-10-13 01:16:13Z**, so the test build has been launched and has talked to prod since. That row is
also the restore's proof — it should read `1.51` again, and it can be read without going near the Mac. MacBook Pro,
macOS 27.0.1 arm64. `spctl --status` read **assessments disabled throughout**: open question 2's granted
`--master-enable` / `--master-disable` pair was **never used, so the grant is unspent** and the Gatekeeper half of the
walk is untouched, not failed.

**What the build was, and why it was not the official `.dmg` this section's Acceptance asks for.** R385 is built but
not released, and no official `.dmg` carries it. The walk built from committed `main` `f48243d5` (R385's `7573d8c0`
is an ancestor) and had to version it **deliberately behind the latest release** — `-Pravilo.macPackageVersion=1.49.0
-Pjellystructure.version=1.49`. That is a precondition nobody had written down: `mac.open_anyway` is drawn only inside
`AppUpdateLine`, which exists only when a *newer* release than the running build is on GitHub, so **a build from
today's `main` cannot show acceptance 6's sentence at all**. The build is **ad-hoc signed** — the signing key and
certificate (`~/ravilo-signing.key`/`.crt`, and any "Ravilo" identity in the keychain) are **gone from that Mac**, so
a local build cannot carry a release's designated requirement. Consequences, recorded rather than glossed: acceptance
1, 2, 4 and 6 do not read a signature and are unaffected; acceptance 3 gets **its own Local Network row** because an
ad-hoc bundle is a different app to TCC; acceptance 5 and R331 acceptance 2b want a genuinely signed bundle and the
official `.dmg`s respectively.

| # | Driver line (FR-R385-7) | What the screen showed | Verdict |
|---|---|---|---|
| 1 | `install=ON_VOLUME host=LAN` | *Move Ravilo to your Applications folder* · the `body_volume` sentence · **Quit Ravilo** alone · **no Retry** | **✓ the `ON_VOLUME` leg** |
| 2 | `install=IN_PLACE host=LAN` | the library loaded in full, both Films & series and Music, no install message anywhere; the existing session survived the app swap | **✓** |
| 4 | `install=IN_PLACE host=PUBLIC` | the plain *Couldn't reach the server* · *Check the connection and try again.* · **Retry** | **✓** (`192.0.2.1:9999`) |

**The `LAN` hedge is resolved.** The *One limit for that walk to watch* above expected `host=LAN` and warned that a
`.local` name would read `UNKNOWN`. The household's host resolves private from inside the house, and the driver
printed `host=LAN` for it — so the hedge is confirmed good rather than assumed, and acceptance 1 and 3 can fire at
all.

**Acceptance 1's `TRANSLOCATED` leg is still owed.** Only `ON_VOLUME` was walked, and the two legs are not the same
test: they share the heading and the buttons but differ in body key, and translocation needs Gatekeeper on **and** a
quarantine flag the file must have acquired from a browser (`scp` and `curl` set none; setting one by hand is the
forcing this phase's open question 2 forbids).

**Acceptance 6's string half is proven, its on-screen half is not.** All five new keys plus the rewritten
`mac.open_anyway` exist in en · da · fo, with FR-R385-6's drag-before-Gatekeeper order intact and the Faroese table
naming macOS's own labels in English per D3 — checked off the repo. What is owed is Settings ▸ Account **rendering**
it in the three languages. The walk could not get there: the test driver's `OPEN_SETTINGS` answers `ok` but does not
navigate (the desktop collector ignores it unless a viewer is signed in and the top of the stack is not
Settings/Player/LiveTv), and the sidebar's *Settings* sits ~40 px from **Sign out**, which is not a click to take
unattended on the owner's signed-in Mac.

**A race for the next walk to watch, not to retry past.** `MacInstall.start()` is launched one line before
`application { }` and resolves the host on `Dispatchers.IO`; until it answers, `serverHostClass` is `UNKNOWN`, which
takes `PUBLIC`'s row and refines nothing. Acceptance 1 says *the first failed call*, so if a library call loses the
race the first message is the plain one and only the next says *Move Ravilo…*. It did not bite on the `ON_VOLUME` leg
— a cached unicast lookup beats a connection attempt comfortably — but the first message goes in the record verbatim
and **Retry is not pressed to reach the expected text**.

**Two mechanics that cost the first walk an hour, for the next one.** The owner's official `.dmg` was already mounted
at `/Volumes/Ravilo`, so `hdiutil attach` put the test image at `/Volumes/Ravilo 1` and the first three launches ran
**his release**, which has no R385 code — mount with an explicit `-volname` and confirm with `--self-test` before
reading anything on screen. And the driver cannot be started over SSH by environment variable (`open --env` does not
propagate it, `launchctl setenv` does not reach a LaunchServices-started app, a direct binary launch dies with
`HeadlessException`): append `java-options=-Dravilo.testdriver=<dir>` to `Contents/app/Ravilo.cfg`, re-sign ad-hoc,
`open -g`. Its startup line then carries FR-R385-7's evidence for free, and its `quiet` mode silences without moving
the owner's volume.

## Open questions

1. ~~Is there any public way on macOS 27 to read whether Local Network consent was granted?~~ **Answered while
   building, 2026-10-12: no. The lean was right, and the hedge stays.** Three separate facts, none of them ours:
   - Apple's own answer is that **there is no API that returns whether the current process has local network access**,
     and none to pre-flight the prompt — stated by DTS on the developer forums and unchanged across the Sequoia →
     Tahoe → 27 run of releases.
   - On macOS the consent is **not held by TCC** at all, so there is no authorisation-status call to make and no
     *undetermined* state to read back (`tccutil reset` does not clear it either).
   - The one home-made probe people reach for does not work here: `NWBrowser` reports **`.ready` whatever the privacy
     state is**, and sends no update when the setting is turned on or off.

   So FR-R385-3's `IN_PLACE` + `LAN` row stays an **inference**, which is exactly why D2 orders it after the state the
   app can prove, and FR-R385-4's body keeps saying *where the setting is* instead of claiming what it currently says.
   Read again if Apple ships an authorisation-status call for it.
   (Sources: [Apple DTS, *Network privacy permission check*](https://developer.apple.com/forums/thread/650648) ·
   [*Local Network Access Permission*](https://developer.apple.com/forums/thread/759955) ·
   [*First update to NWBrowser is always ready, irrespective of Local Networking privacy status*](https://developer.apple.com/forums/thread/770563) ·
   [Nonstrict, *Request and check local network permission*](https://nonstrict.eu/blog/2024/request-and-check-for-local-network-permission/).)
2. ~~**Does the owner's MacBook translocate at all?**~~ **Answered by the owner 2026-10-12 (DEB-756): yes, Gatekeeper
   may be enabled for one walk and must be put back.** `spctl --status` reads `assessments disabled` there (DEB-721)
   and App Translocation is a Gatekeeper behaviour, so acceptance 1 and 3 could not be observed as the Mac stands. The
   grant is: `sudo spctl --master-enable` before the walk, the walk, then `sudo spctl --master-disable` again — one
   walk, not a standing change, and the restore is part of the test, not a follow-up. It clears R331 acceptance 2b in
   the same sitting (R331's 2026-10-12 amendment). If translocation still does not happen with assessments enabled,
   that is a finding to write down, not a reason to force the path by hand.
3. Should the install-state message also appear in the *Play on…* sheet in place of R330's Local Network line when the
   cause is translocation? **Lean no** — one full-screen explanation is enough, and the sheet's line is still true.

## Routing

Páll (the Ravilo clients). The Mac halves of R328–R331 are owed from a Mac walk already; acceptance 1–6 here belong on
the same walk rather than a trip of their own.
