# Phase R385 — The Mac says why it cannot reach the server

> QA, 2026-10-12 (DEB-756, found on the Mac walk DEB-721): *"A translocated Ravilo cannot reach the backend at all, and
> the only thing the user sees is **Couldn't reach the server** · Check the connection and try again · Retry."*

## Status

`Planned` — written 2026-10-12 (PO-authored from DEB-756). Not dev-reviewed, not built. The Mac app (R328–R331, R337)
and the shared error classifier R237/R280 wrote.

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

**D4 — The release notes are not touched.** FR-R331-6 was withdrawn by the owner on 2026-09-30 (*"people downloading
dmg already know how to install a dmg"*) and stays withdrawn. The install sentence this phase adds goes **in the app**,
under the update line that already carries the *Open Anyway* sentence.

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
   *Move Ravilo to your Applications folder* with **Quit Ravilo** and **no Retry**, and says nothing about the network
   or the connection.
2. **The control.** The same build dragged to `/Applications` and de-quarantined: the library loads, and no install
   message appears anywhere in the app.
3. **The permission.** From `/Applications`, turn **Ravilo** off in System Settings → Privacy & Security → Local
   Network: a failed load reads *Ravilo needs Local Network access*, **Open Settings** opens that pane, and after
   turning it back on **Retry** loads the library. **Turn it back on afterwards.**
4. **No false positive.** With the server configured to a host that resolves to a public address and the backend
   stopped, the plain *Couldn't reach the server* with *Retry* appears, unchanged from today.
5. **No false positive, the other way.** A translocated copy pointed at a public-resolving server that is up signs in
   and plays, with no install message (D1).
6. The update line in Settings ▸ Account reads FR-R385-6's sentence, in en, da and fo.
7. A unit test covers FR-R385-3's table and FR-R385-4's mapping — install state × host class × failure shape →
   heading, body and buttons — and that every key it names exists in all three tables (R280's test precedent).

## Open questions

1. Is there any public way on macOS 27 to read whether Local Network consent was granted? **Lean no** — if one exists,
   FR-R385-3's `IN_PLACE` + `LAN` row should state the fact instead of inferring it, and FR-R385-4's body can stop
   hedging.
2. **Does the owner's MacBook translocate at all?** `spctl --status` reads `assessments disabled` there (DEB-721), and
   App Translocation is a Gatekeeper behaviour. Acceptance 1 and 3 may need `sudo spctl --master-enable` first — the
   owner's call, asked on DEB-756.
3. Should the install-state message also appear in the *Play on…* sheet in place of R330's Local Network line when the
   cause is translocation? **Lean no** — one full-screen explanation is enough, and the sheet's line is still true.

## Routing

Páll (the Ravilo clients). The Mac halves of R328–R331 are owed from a Mac walk already; acceptance 1–6 here belong on
the same walk rather than a trip of their own.
