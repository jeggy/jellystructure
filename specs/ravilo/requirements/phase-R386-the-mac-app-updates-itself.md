# Phase R386 — The Mac app updates itself from GitHub Releases

> Owner, 2026-10-15 (DEB-1230): *"We want to introduce a update functionality, like other apps have for mac client.
> This update should somehow just work by getting updates from github releases."*

## Scope

Written 2026-10-15 (Bárður) from the investigation note and the owner's four answers on DEB-1230, and from design round 4
(`mac-update`), which the owner drew himself the same day and decided in the studio. The round is in quarantine at
`design/incoming/2026-10-15-mac-update/` (`02a8ab87`). Its directions doc, `ravilo/Mac Update - Directions.html`, and
its 58 PNGs are the reference for every pixel here. `ravilo/mac-update-report.md` holds the drawn state list and the
string table. Number allocated from the board on 2026-10-15: the highest Ravilo phase task was R385, and no task or spec
claimed R386.

Touches the Mac app (`ravilo-desktop`, its Swift library, `ravilo-ui`'s desktop Settings and chrome), `shared`'s
version logic, `deploy-macos.yml` and the string table. **No wire change and no backend change:** `/api/health` is
already public and already carries the version.

**Supersedes** R328 D6 and FR-R328-8 (*"a line, not an updater"*): see R328's 2026-10-15 amendment. **Amends** R331,
whose out-of-scope list named an auto-updater, and R385, whose FR-R385-6 sentence lived under the line this phase
removes: both carry a dated amendment of the same day. **Builds on** R252 (a client names its version), R331 (the
self-signed `.dmg` on every release), R337 (the desktop and mobile families) and R385 (the install-place states).

## Decisions

Owner, 2026-10-15, card `1a12e934` on DEB-1230:

| # | Question | Decision |
|---|---|---|
| D1 | What does the updating | **Sparkle 2**, the standard Mac updater, called through the Swift library the app already loads |
| D2 | Which version a Mac moves to | **The newest release, never newer than the server it is signed in to.** The files always come from GitHub Releases |
| D3 | How automatic | **Check daily, download in the background, then ask once: *Restart to update*.** It never restarts by itself and never interrupts playback |
| D4 | What is designed | **Sparkle's own macOS windows** do download and install; Ravilo designs the Settings block, the quiet notice and their states |

Owner, 2026-10-15, in the design studio (round 4):

| # | Question | Decision |
|---|---|---|
| D5 | Where the block lives | **Block A: its own *Software update* block at the top of Settings ▸ About.** It leaves Settings ▸ Account |
| D6 | The ready notice | **Notice 1: a dot on Settings and one row at the sidebar's foot.** In the mobile family, a dot on Profile and a row at the top of the Profile page |
| D7 | Release notes | **Notes A: a *What's new* link.** Sparkle's window shows the notes at install time |
| D8 | *Check for Updates…* | **Yes**, in the *Ravilo* app menu directly under *About Ravilo* |

Settled at adoption (Bárður, 2026-10-15). Round 4 drew these one way and left them open:

| # | Question | Settled |
|---|---|---|
| D9 | A manual download, with the toggle off | **Hands over to Sparkle's window** — a second handover point. D4 gives download and install to Sparkle's windows. The round drew every download inside Ravilo's block, but that only holds for a background download, which has no window at all |
| D10 | What × (*Hide*) on the notice row hides | **The row until the next launch; the dot until the update is installed**, as drawn |
| D11 | The Profile dot in the mobile family | **One dot means "your Profile page has something"; the page's first row says what.** Round 3's support lane draws the same dot for unread replies. That Ravilo screen is not adopted yet (phase 320 is backend-only), so this phase sets the rule and the support screen's phase follows it |
| D12 | What "never newer than the server" means for a build between releases | **The release the server's build is based on.** Prod normally runs a build between releases, reported as `v1.51-238-g…`. That build contains everything in 1.51, so 1.51 is not newer than it. Read strictly, D2 would offer nothing to any Mac most of the time |

## Requirements

**FR-R386-1 — Sparkle in the bundle.** Sparkle 2 (the current 2.x, pinned) ships as `Sparkle.framework` in
`Ravilo.app/Contents/Frameworks`, linked by the Swift library (FR-R328-4).

- `Info.plist` gains `SUPublicEDKey` (FR-R386-2's public key) and `SUEnableAutomaticChecks = NO`: Ravilo, not Sparkle,
  decides when to check (FR-R386-3). There is no `SUFeedURL`; the feed is set per check.
- `deploy-macos.yml` signs Sparkle's nested helpers with the Ravilo identity, inside-out, before the bundle's own
  signature. Its `codesign --verify --deep --strict` must still pass.
- Hardened runtime stays off (R331), so Library Validation never stops Sparkle loading.

**FR-R386-2 — Every release carries a one-item appcast.** `deploy-macos.yml` gains two steps after the `.dmg` exists:

1. It signs the `.dmg` with Sparkle's `sign_update` and the EdDSA private key from a new repository secret,
   **`SPARKLE_ED_PRIVATE_KEY`**.
2. It writes `appcast.xml` with exactly one item:
   - `sparkle:version` = the bundle's `CFBundleVersion` (`MAJOR.MINOR.0`)
   - `sparkle:shortVersionString` = `MAJOR.MINOR`
   - the enclosure is the release's own `ravilo-mac-<N>.dmg` URL, with its length and `sparkle:edSignature`
   - `sparkle:minimumSystemVersion` = `14.0`
   - `sparkle:releaseNotesLink` = the tag's release page

`appcast.xml` is uploaded beside the `.dmg` with `gh release upload --clobber`. On a `workflow_dispatch` run that
uploads no release, it goes to the run's artifacts instead.

**The key pair is made once.** The public key goes in the bundle configuration. The private key is set with
`gh secret set`, the way R331's two signing secrets were. A backup of the private key is kept wherever R331's
certificate backup is kept. The private key never appears in the repository, a comment or a log.

**A missing secret fails the job and names the secret. Nothing is uploaded**, as in R331's acceptance 5.

**FR-R386-3 — What the app offers (D2, D12).** `UpdateCheck` keeps its schedule: once it knows the server, every 24
hours after, and a quarter of an hour after a failure. On each check, three versions are read:

- **A** — the app's own release. A development build of the app (anything that is not a plain release) has no update
  block at all (FR-R386-6, state 10).
- **S** — the server's release floor, from `/api/health`:
  - `v1.51` or `1.51` is 1.51;
  - `v1.51-238-g188154f2` is also **1.51** (D12);
  - blank, `dev` or anything unparseable is unknown.

  This is a new function in `shared` beside `raviloNewerRelease`, with tests. `raviloNewerRelease`'s own behaviour for
  its other callers is unchanged.
- **L** — the newest release, read from `https://github.com/jeggy/jellystructure/releases/latest/download/appcast.xml`.

Then:

| When | The app |
|---|---|
| S > A | Points Sparkle at `…/releases/download/v<S>/appcast.xml` and runs a **background** check (FR-R386-4) |
| S ≤ A and L > A | Shows **Held back** (state 6) with L and S; Sparkle is not asked |
| otherwise | Shows **Up to date** |
| GitHub cannot be reached, or the server is unknown | Shows **Offline** (state 12), keeping the last check's time |

A release from before this phase has no `appcast.xml`. A 404 on `v<S>/appcast.xml` therefore means *nothing to
offer*, never an error. The first release that carries this phase is installed by hand, through the old line that
already-installed apps still show.

**FR-R386-4 — Driving Sparkle (D3, D4, D9).** One `SPUUpdater` with Sparkle's standard user driver, created by the
Swift library and reached over the existing JNI bridge.

- **Ravilo shows the scheduled reminder.** It opts into Sparkle's *gentle scheduled update reminders*: for a
  background check, Sparkle shows no window, and Ravilo shows its block and notice instead.
- **`automaticallyDownloadsUpdates` follows the toggle** (state 9), which defaults to **on**.
- **Toggle on:** an update downloads silently and the block goes to **Ready** (state 4).
  - *Restart to update* asks Sparkle to install and relaunch. The block shows the handover line for the moment before
    Ravilo closes, and from there the windows are Sparkle's (D4).
  - **A ready update also installs when the viewer quits Ravilo.** That is Sparkle's standard behaviour for a
    downloaded update, and the notice has already told the viewer. Ravilo never quits or restarts on its own.
- **Toggle off:** the block offers **Download** (state 3's offer form). Pressing it hands over to Sparkle's own window,
  which shows the release notes and the download progress and ends at *Install and Relaunch* (D9).
- **While anything plays or casts** (a film, music, a cast session this Mac controls), *Restart to update* is
  withheld everywhere (state 5). Nothing modal appears, and nothing takes focus.
- **When `/Applications` is not writable** for this user, Sparkle asks for an administrator's password in its own
  window. Ravilo draws nothing for it.
- **Background progress (state 3):** shows *{a} of {b} MB* only if Sparkle reports the bytes of a background download
  through a supported API. Otherwise the row shows its sentence without the figures. FR-R386-10's first task records
  which is true.

**FR-R386-5 — The app cannot update from where it runs.** When R385's install state (FR-R385-1) is `TRANSLOCATED` or
the app is running from the disk image, Sparkle is never asked. The block shows state 8 in **R385's shipped strings**:
`mac.install_place.title` and `mac.install_place.body_translocated` or `mac.install_place.body_volume`. Its only
action is **Quit Ravilo** (the shipped `mac.menu_quit`). There is no toggle and no *Try again*, because nothing the
viewer presses changes it.

**FR-R386-6 — Settings ▸ About (D5).** The desktop target's Settings gains an **About** section, after Account. In the
desktop family it is a fourth sidebar entry; in the mobile family it is a section of the Settings page. Top to bottom:

1. the **Software update** block (FR-R386-7) — on a Mac release build only;
2. **Version** — the app's version;
3. **Server** — the host it is signed in to.

On the Linux desktop target and on a development build, the block is **absent**: not greyed and not empty (state 10).
The section still shows Version and Server.

**FR-R386-7 — The block's states**, as drawn (`about-<family>-<state>.png`), in both families:

| # | State | What it says and offers |
|---|---|---|
| 1 | Up to date | *Ravilo 1.52 · Up to date · Checked today at 09:14* · **Check now** |
| 2 | Checking | *Checking for updates…* |
| 3 | Downloading | *Ravilo 1.53 is downloading* · progress if known (FR-R386-4) · *It downloads in the background. Ravilo asks before it restarts.* With the toggle off, the offer form instead: *Ravilo 1.53 for Mac is available* · **Download** |
| 4 | Ready | *Ravilo 1.53 is ready* · *Restart Ravilo to finish. It takes a few seconds.* · **Restart to update** · *What's new* (D7, FR-R386-9) |
| 5 | Ready while playing | as 4, without the button: *Something is playing, so the update waits. Restart to update appears here when it stops.* |
| 6 | Held back | *Ravilo 1.53 is out* · *Your server runs 1.52, so this Mac waits for the server. It updates once the server has 1.53.* |
| 7 | Failed | download: *The update didn't download* · *Check that this Mac is online, then try again.* — signature: *The update couldn't be confirmed* · *Ravilo installs only an update it can confirm is genuine. Try again to download it fresh.* · **Try again** on both |
| 8 | Cannot update from here | FR-R386-5 |
| 9 | Toggle | **Download updates automatically** · on: *Ravilo still asks before it restarts.* · off: *Ravilo tells you when an update is out and downloads it when you say so.* |
| 10 | Development build | the block is absent |
| 11 | Longest string | the Danish and Faroese frames hold without clipping |
| 12 | Offline | *Can't check while offline* · *Last checked today at 09:14. Ravilo checks again when this Mac is back online.* No **Check now** |

The handover line (`about-<family>-handover.png`) is *Closing Ravilo to install 1.53…* · *macOS takes it from here and
opens Ravilo again.*

Every state is server state or Sparkle state. Ravilo derives nothing optimistically: the block shows what the last
check or Sparkle's last callback said.

**FR-R386-8 — The ready notice (D6, D10, D11).** Shown in states 4 and 5 only.

- **Desktop family:**
  - a dot on the Settings entry of the main sidebar (on the rail when the sidebar is collapsed);
  - one row at the sidebar's foot: *Update ready* · **Restart** · × (**Hide**).
  - In state 5 the row's **Restart** becomes the dashed, inert *After playback*. Nothing moves, and nothing can stop
    playback.
- **Mobile family:** a dot on Profile in the bottom bar, and the same row at the top of the Profile page.
- **Hide** removes the row until the next launch. The dot stays until the update is installed.
- **Restart** does exactly what the block's *Restart to update* does.

**FR-R386-9 — *What's new* and *Check for Updates…* (D7, D8).**

- *What's new* opens the tag's GitHub release page in the browser.
- The *Ravilo* app menu gains **Check for Updates…** directly under *About Ravilo*, above the separator before
  *Settings…*. It opens Settings ▸ About and runs *Check now* there. A finished check is shown in the block, never
  in a Sparkle "you're up to date" alert.

**FR-R386-10 — Strings** × en · da · fo, from the round's string table (`mac-update-report.md`). The keys go in the
`mac.update.*` namespace, named at build time. The shipped table wins wherever a string already exists:

- **Reuse:** `mac.update_available` (state 3's offer), `mac.download`, the three `mac.install_place.*` strings,
  `mac.menu_quit`, `settings.account` and the version strings. Reuse, never a copy.
- **New:**
  - the block: title, the version line, *Up to date*, *Checked today at {t}*, *Check now*, *Checking for updates…*
  - downloading: the downloading line, the progress line, the background sentence
  - ready: the ready line, its sentence, *Restart to update*, *What's new*, the playing sentence
  - held back: both lines
  - failed: both failures with their sentences, *Try again*
  - offline: both lines
  - the toggle: its label and both sentences
  - the handover: both lines
  - the notice: *Update ready*, *Restart*, *After playback*, *Hide*
  - About: *About*, *Version*, *Server*
  - the menu item
- **Not taken:** the round's `su_move_*` (the shipped install-place strings serve), `su_ship_install`, `su_notes_title`
  and `su_n1`–`su_n3`. Notes B was not chosen, and those are stand-ins.
- `su_try_again` has the same words as the support lane's `sup_try_again`. Whether one key serves both is the
  builder's call.

Danish and Faroese are the round's drafts. Faroese keeps macOS's own labels (*Applications*) in English.

**FR-R386-11 — The old line goes.** FR-R328-8's line leaves Settings ▸ Account on the Mac. `AppUpdateLine`, the
`AppUpdateOffer` download URL and `mac.open_anyway` are removed once nothing uses them. Already-installed apps keep
their own line: it is compiled into them and reads only `/api/health`, which does not change.

## Out of scope

- The Linux Flatpak (Flathub updates it).
- The Android APK side-load.
- Notarisation or anything with an Apple bill (R331).
- Delta updates.
- Release notes inside the block (Notes B).
- A louder notice (Notice 3).
- Any update offer on the TV, phone, web, cast or Tizen.
- Installing an update while something plays.

## Acceptance

1. **Shared tests:** the server floor reads `v1.51`, `1.51` and `v1.51-238-g188154f2` as 1.51, and blank or `dev` as
   unknown. FR-R386-3's table holds for S > A, S = A with L > A, S < A, and a 404 appcast.
2. **CI:** a `workflow_dispatch` run of `deploy-macos.yml` produces a `.dmg` whose bundle carries `Sparkle.framework`
   and passes `codesign --verify --deep --strict`, plus an `appcast.xml` whose `sparkle:edSignature` verifies against
   `SUPublicEDKey`. With `SPARKLE_ED_PRIVATE_KEY` removed, the job fails naming it and uploads nothing.
3. **The spike, on a Mac** (QA queue; no release involved): a build of version A, installed to `/Applications` by a
   Finder drag, is pointed by a debug-only feed override at a local appcast for a build of version B signed with the
   same identity and key.
   - It downloads in the background, shows **Ready**, and on *Restart to update* relaunches as B, with **no
     Gatekeeper prompt and no translocation**. Record `xattr -l` on the installed copy.
   - The sign-in and the Local Network grant survive.
   - Record whether Sparkle reports background byte progress (FR-R386-4).
4. **With something playing**, state 5 shows and nothing modal appears. Stopping playback brings *Restart to update*
   back.
5. **With the toggle off**, *Download* opens Sparkle's window and the update installs from there.
6. **Held back:** the app on 1.52 against a server reporting 1.52, with a 1.53 appcast at `latest`, shows state 6
   and asks Sparkle nothing.
7. **Translocated, and from the disk image:** state 8 in R385's strings; *Quit Ravilo* only; Sparkle never asked.
8. **Development build and Linux:** About shows Version and Server, and there is no block.
9. **Every state in both families**, light and dark, en · da · fo, matches its round-4 frame. *Check for Updates…*
   sits under *About Ravilo* and opens About with a check running.
10. **The first real update** — the release after the first one carrying this phase — installs on the household Mac
    from the notice, with no prompt and nothing dragged. Release timing is the owner's, so this item waits on him.

## Amendment (2026-10-16): the spike found no silent download, and the engine is unreadable (DEB-1513)

Bárður, from the spike's verdict on DEB-1292 (Høgni's run on a Gatekeeper-on Mac and Óluva's of 10-15, consolidated
by Turið). Two Macs and two install paths gave the same result. A background check read `appcast.xml` (200) and then
never fetched the `.dmg`. The spike's question was whether Sparkle updates a quarantined, self-signed Ravilo with no
Gatekeeper prompt. It is **unanswered**: nothing was installed. Build 1's state could not be read from outside the
app either, because it had no screen and no log line for it. A fix slice, DEB-1513, now sits between the engine and
the screens. The screens (DEB-1280) wait for the re-run.

**FR-R386-1, the `Info.plist` bullet, amended.** What has to hold is that **Ravilo decides when Sparkle checks and
against which feed**. `SUEnableAutomaticChecks = NO` was only the means. If Sparkle 2 downloads silently only with
its automatic checks on, the builder may turn them on, provided Sparkle's own scheduler never starts a check Ravilo
did not ask for. A delegate that refuses every check Ravilo did not start satisfies that. The plist key and
`deploy-macos.yml`'s guard follow the choice. The commit cites Sparkle's source for the reason.

**FR-R386-4, toggle on, restated as testable.** A background check that finds an update downloads it with no window.
The engine's state then goes *checking* → *downloading* → *ready*. If it stays at *checking* or *available*, or
returns to *idle*, without fetching the update, FR-R386-4 is broken.

**FR-R386-12 (new): the engine logs its state.** Every change of the engine's state writes one line to `ravilo.log`:

- the old state, the new state and the version;
- with *failed*, the kind of failure; with *unavailable*, the reason.

Each check writes one line naming the feed it was pointed at. A poll that finds no change writes nothing. The lines
carry no account, no token and nothing from the library. QA reads the engine from outside through them, and the
block on screen still comes only from the engine's report (FR-R386-7).

**Acceptance 3, amended for the re-run.** The re-run happens before the screens exist, so it has no *Restart to
update*. Quitting Ravilo stands in for it, because that is the same Sparkle install (FR-R386-4). The FR-R386-12 lines
are how the spike sees *downloading* and *ready*. The toggle-off path that the spike plan carried as its step 5 is
acceptance 5, and the screens' QA ticket takes it.
