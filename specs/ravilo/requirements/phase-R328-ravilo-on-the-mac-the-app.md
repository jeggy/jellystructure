# Phase R328 — Ravilo on the Mac: a desktop target and the app around it

> Owner, 2026-09-29: *"Lets create specs for this. So when fully implemented, the dmg file will be attached to the
> github release, just like android and Tizen."*

## Status

`⚠ Partial` — **built 2026-09-29; runs on a real Mac the same day (signs in, keeps its tokens in the Keychain, plays) — the menu bar's handlers, the Dock reopen and the update line are still owed** (§Build notes). Written 2026-09-29 (dev-authored) from `research-reports/ravilo-macos-desktop-app-2026-09-29.md`
(road 2). **Dev-reviewed 2026-09-29** against `main` `4c67e49f` (§Dev review) — build from it. Number verified free: `origin/main` `e828c944` and local `main` top at R327.

**One of four, built in order:** **R328** (this — the app) → **R329** (it plays films and music) → **R330** (it
casts, speakers first) → **R331** (the `.dmg` on every GitHub release). R331 wires the release only once the first
three are built. **Builds on** R224 (one app, several entry points), R252 (a client names its platform and
version), R256 (the TV layout vs. the phone layout), R263 (the web app's update toast), R279 (the one string
table).

## Decisions (leans — the owner has not confirmed them yet; see Open questions)

| # | Question | Lean |
|---|---|---|
| D1 | Which toolkit | **Compose Desktop — a JVM target of the shared UI.** Compose supports macOS through it (*Desktop (JVM) — Stable*); a Kotlin/Native macOS UI does not exist |
| D2 | Which Macs | **Apple Silicon, macOS 14 (Sonoma) or later.** One architecture, a smaller bundle; Sonoma is the release that also added Safari's web apps in the Dock, so road 0 covers older Macs |
| D3 | Which layout | **The TV layout** (`isTvPlatform = false`, `isHandset = false` — R256), driven by the keyboard and the mouse. It is what the web app already shows on a desktop |
| D4 | What it says it is | `X-Ravilo-Platform: mac`; Users & devices reads **Mac** |
| D5 | Where secrets live | the device tokens in the **macOS Keychain**; everything else in `~/Library/Application Support/Ravilo/`. (Briefly a `0600` file on 2026-09-29, while the build was to be ad-hoc signed; restored the same day once R331 gained one signing identity of our own — a Keychain item's access list is bound to it and survives updates. Dev review 1a.) |
| D6 | Updates | **a line, not an updater**: when the server is newer than the app, Profile says so and links to that release's `.dmg` |
| D7 | Closing the window | quits, **unless music is playing** — then the app stays in the Dock and the music plays on (the Music app's rule); ⌘Q always quits |

## Requirements

**FR-R328-1 — A desktop target in the shared modules.** `shared`, `ravilo-i18n` and `ravilo-ui` gain
`jvm("desktop")`. Ktor uses **OkHttp** for REST and **CIO** for the events WebSocket (the split R210 made on
Android); Coil 3 uses its OkHttp network artefact. Nothing in `commonMain` changes to make this compile. On Debian,
`compileKotlinDesktop` and `desktopTest` run for all three modules, and `ci.yml`'s `checks` job runs both on every
push, so a change that breaks the Mac shows up before a release.

**FR-R328-2 — The app module.** New `:ravilo-desktop`: the Compose Desktop application plugin, one window hosting
`RaviloApp`, bundle id `dev.jellystructure.ravilo`, display name **Ravilo**, the R62 brand mark as the app icon (an
`.icns` generated once from `design/ravilo/assets/brand/` and checked in under `ravilo-desktop/icons/`). **It runs
on Debian too** (`./gradlew :ravilo-desktop:run`): Compose Desktop supports Linux, so UI work needs no Mac. There,
the native parts (FR-R328-4) are absent and say so honestly: no playback, tokens in a `0600` file.

**FR-R328-3 — The 62 seams.** Every `expect` in `ravilo-ui` gets a `desktopMain` actual. In this phase the ones
R329/R330 own give the honest absent answer (no player, no Cast routes); the rest:

| Seams | Mac answer |
|---|---|
| `isTvPlatform`, `isWebPlatform`, `isIOSWebPlatform`, `playsHlsForAirPlay`, `platformAirPlay` | `false` / `null` |
| the web-install seams (`installCardDismissed`, `dismissInstallCard`, `triggerNativeInstall`, `rememberInstallPromptAvailable`, `rememberIsStandaloneWebApp`, `installHashListener`, `rememberUpdateAvailable`, `reloadForUpdate`) | absent — FR-R328-8 is the Mac's own update line |
| `pushRoute`, `replaceRoute`, `rememberNotificationAsk` | no-ops |
| `TokenStore`, `MultiTokenStore` | the Keychain (FR-R328-4) |
| `DeviceIdStore`, `DeviceLanguageStore`, `PlaybackPrefsStore`, `ScreensSheetPrefs`, `HomeSnapshotCache`, `MusicDeviceStore`, `saveBaseUrl`/`raviloBaseUrl` | files in `~/Library/Application Support/Ravilo/` |
| `deviceDisplayName` | the Mac's *Computer Name* (System Settings → General → Sharing) |
| `detectLinkState`, `rememberAppOnScreen`, `rememberDeviceStateProbe`, `systemPrefersReducedMotion` | `NetworkInterface` (wired/wifi); the window is focused and not minimised; the system's *Reduce motion* |
| `PlatformBackHandler`, `rememberExitAction` | **Esc** is Back; the exit action closes the window (D7) |
| `Modifier.safeAreaPadding`, `Modifier.wakeOnPointerMove`, `setPointerCursorHidden`, `Modifier.reportTextFieldFocus` | no insets; mouse movement wakes the chrome; the cursor hides with it |
| `rememberChoosePhotoLauncher`, `rememberTakePhotoLauncher` | the system file dialog; no camera (the *Take* row is absent, never greyed) |
| `rememberCastSender`, `rememberCastRoutes`, `hasCastSdk` | R330 (until then: `ScreenSender` alone, no routes, `false`) |
| `RaviloPlayer`, `PlayerVideoSurface`, the capability probes, `MusicEngine`, … | R329 (until then: `MusicEngine.supported = false` and no Play) |

**FR-R328-4 — One native library.** `ravilo-desktop/native/` holds a small Swift library, `libravilo-mac.dylib`,
exporting C functions called over JNI. It is built by a Gradle task that runs only on macOS (`xcrun swiftc`,
`arm64`, deployment target 14.0) and is shipped inside the app bundle. In this phase it provides the Keychain
(read, write and delete one generic-password item per device token, service `dev.jellystructure.ravilo`) and the
Computer Name; R329 adds the player and Now Playing. Loading it is one place, and a missing library is one error
line in the log and the Linux fallbacks, never a crash.

**FR-R328-5 — Window, keyboard and mouse.** Minimum window size 960 × 600; the window's size and position are
remembered. Arrow keys move focus the way the D-pad does, **Return** selects, **Esc** is Back, **Space**
plays/pauses in the player (R329). The mouse hovers and clicks everything focusable and the scroll wheel scrolls
rows and pages. The menu bar is macOS's own, with nothing custom:

- **Ravilo:** About Ravilo · Settings… (⌘,) · Hide (⌘H) · Quit (⌘Q)
- **View:** Enter Full Screen (⌃⌘F)
- **Window:** Minimise (⌘M) · Close (⌘W)

*Settings…* opens Profile → Settings. About shows the version and *Signed in to {server}*.

**FR-R328-6 — It says it is a Mac.** Every request carries `X-Ravilo-Platform: mac` and the app's version (R252).
The admin's Users & devices maps `"mac" -> "Mac"` (`RaviloUsers.kt`). The header is free text: nothing on the wire
changes (R319).

**FR-R328-7 — Server setup and sign-in** are R225/R226's and R175's screens as they are, with the Mac's own text
fields and keyboard (no drawn keyboard — the standing rule).

**FR-R328-8 — A newer version is one line.** At sign-in and every 24 hours while running, the app reads the
server's version from `GET /api/health` (public by design). When the server's `MAJOR.MINOR` is newer than the app's,
Profile shows **Ravilo {version} for Mac is available** · **Download**, which opens that release's
`ravilo-mac-{version}.dmg` (R331) in the browser. A dev build on either side (a `-N-g{sha}` suffix) never shows it.
Nothing downloads or installs by itself.

**FR-R328-9 — Closing** follows D7. With music playing, closing the window keeps the app and the music, and clicking
the Dock icon brings the window back where it was. A film stops on close (phase 180's stop is reported). ⌘Q stops
everything and quits.

**FR-R328-10 — Strings** × en · da · fo in `i18n/*.json` (da/fo drafts; the shipped table wins):

- `mac.update_available` *Ravilo {version} for Mac is available*
- `mac.download` *Download*
- `mac.about_signed_in` *Signed in to {server}*
- the menu items macOS does not translate itself

## Out of scope

Intel Macs · the Mac App Store (it would need the App Sandbox) · Windows and Linux packages (the JVM target makes
them possible later; not asked) · an auto-updater · iCloud sync of settings.

## Acceptance

1. `./gradlew :ravilo-desktop:run` on Debian opens Ravilo, and it signs in to a server and browses Home.
2. On the MacBook the same command opens the app with the tokens in the Keychain (visible in Keychain Access under
   `dev.jellystructure.ravilo`).
3. Users & devices lists the Mac as **Mac** with its Computer Name and version.
4. Arrow keys, Return and Esc drive Home, a detail page and Search, and the mouse clicks the same targets.
5. With the app older than the server, Profile shows the update line; with the same version it does not.
6. `ci.yml`'s `checks` job compiles the desktop targets and runs `desktopTest`.

## Open questions

1. **The owner's four from the research report:** is the native app worth it (the speakers need it); ~~an Apple
   Developer ID~~ — **answered 2026-09-29: no Apple bills, a `.dmg` only** (R331 dev review 1); Apple Silicon only (D2); the
   Cast client written for reuse by the backend (R330, D1).
2. Should the TV layout get a desktop density by default (smaller tiles at a desk), or keep R174's grid-columns
   config and let the household set it? Lean: keep the config — no new setting.
3. Is *Computer Name* the right device name, given that Macs are often named *{person}'s MacBook Air*? It is what
   the owner sees in Users & devices, and it is renamable in both places.

## Dev notes

- 62 `expect`s in `ravilo-ui/src/commonMain` (counted 2026-09-29); for size, `androidMain` has 3 543 lines of
  actuals and `wasmJsMain` 1 576.
- `shared` already carries `parseVtt`/`activeCueText` (`ReceiverSubtitles.kt`), which R329 reuses.
- Android sends `platform = if (isTelevision) "tv" else "phone"` (`RaviloRootActuals.kt`); the web sends `"web"`.

## Dev review (2026-09-29, against `main` `4c67e49f`)

Buildable as written, with one owner decision folded in and one seam that needs more than a mapping. Eleven items.

1. **Owner, 2026-09-29: "I will not pay any apple bills. I only want dmg version of the app."** For this phase that
   retires the Keychain (D5): an ad-hoc signature differs on every build, and a Keychain item's access list is bound to
   the signing identity, so each update would prompt *Ravilo wants to use your confidential information* or refuse.
   Tokens go in `~/Library/Application Support/Ravilo/tokens.json`, mode `0600` — the same class of protection the
   web build has in `localStorage` and Android in its preferences. **With that, R328 has no native code at all**:
   FR-R328-4's Swift library moves whole to R329 (the player, Now Playing, the display-sleep assertion), and the
   Computer Name is `scutil --get ComputerName` (a subprocess, macOS only) with `InetAddress.getLocalHost().hostName`
   as the Linux fallback. R328 is then a pure-JVM phase that builds and runs on Debian end to end.
   **1a. Superseded the same day.** The owner's follow-up — one self-signed certificate of our own, kept in CI's secrets
   and used on every release (R331 FR-R331-3) — gives the app a stable designated requirement, which is what a
   Keychain item's access list is bound to. So D5 stands as first written: tokens in the Keychain through the Swift
   library (`SecItemAdd`/`SecItemCopyMatching`, service `dev.jellystructure.ravilo`), FR-R328-4 stays in this phase,
   and the Linux fallback is the `0600` file. The one price: a **development build signed with a different identity
   (ad-hoc, from `gradle run`) will prompt for the Keychain** — expected, and worth one line in the module's README.
2. **FR-R328-1 is plumbing, not porting.** `shared` and `ravilo-i18n` are Kotlin + Ktor + serialization only;
   `ravilo-ui`'s common dependencies (Compose 1.9.3, Material 3, Coil 3.2, Ktor 3.6, kotlinx-datetime) all publish
   JVM artefacts, and both modules already compile for four targets each (`androidTarget`, `linuxX64`, `wasmJs`,
   `js(IR)`), so a fifth is a line per module. Their JVM settings (`jvmTarget 11`) can stay; only R331's packaging
   needs JDK 17+.
3. **Back is the one seam that is not a mapping.** Common code reads `Key.Back` at 11 sites and `Key.Escape` at 5
   (`grep` 2026-09-29). On a Mac no key produces `Key.Back` — Compose Desktop has no physical key for it — so the
   desktop `PlatformBackHandler` actual must make **Esc** reach every Back handler: one `onPreviewKeyEvent` on the
   window that, when nothing below consumed Escape, calls the registered handler (R275's `BackToTopRegistry` is the
   shape the phone already uses). The 5 `Key.Escape` sites (the player, sheets) keep working as they are. The web
   build has the same gap and covers it with the browser's Back button, which a Mac window lacks.
4. **Arrow keys already are the D-pad.** `Key.DirectionUp/Down/Left/Right/DirectionCenter/Enter` are what the TV
   layout reads in common code, and Compose Desktop maps the arrow keys and Return to exactly those — the web build on
   a desktop proves the path today. Nothing to write.
5. **FR-R328-8 verified.** `GET /api/health` is exempt from auth by exact path match (`AuthPlugin.kt:130`) and answers
   `{"status":"ok","version":"v1.44-49-g429cffe9"}` (measured on prod today). The app's own string is
   `dev.jellystructure.shared.BuildInfo.version`, generated in `shared` from the root's `buildVersion`, so the desktop
   module inherits it with no wiring. Compare `MAJOR.MINOR` only when **both** are plain tags; a `-N-g…` suffix on
   either side hides the line, as the FR says.
6. **D7 (closing) has the JDK APIs it needs:** `java.awt.desktop.AppReopenedListener` for the Dock click,
   `QuitHandler` for ⌘Q, and Compose's `Window(onCloseRequest)` deciding hide-vs-exit while the `application` scope
   stays alive with the window `visible = false`. Nothing platform-specific to write in Swift.
7. **The menu bar** is Compose Desktop's `MenuBar` with `-Dapple.laf.useScreenMenuBar=true` in the JVM args (so it
   lands in the system menu bar, not the window); *About* and *Settings…* use `Desktop.setAboutHandler` /
   `setPreferencesHandler` (JDK 9+), which macOS places under the app menu with ⌘, itself. *Verify the property is
   still needed with Compose 1.9.3 — it may set it.*
8. **FR-R328-6 is one line:** `RaviloUsers.kt:94` gains `"mac" -> "Mac"`. The only server code that reads a platform
   value is 286's `cast-audio` check; free text on the wire (R252), so R319 is untouched.
9. **CI (FR-R328-1's last sentence):** `ci.yml`'s `checks` job runs only the fence scripts; the Kotlin builds live in
   the `linuxX64Test` step and the Android job. Add `./gradlew :shared:desktopTest :ravilo-i18n:desktopTest
   :ravilo-ui:desktopTest :ravilo-desktop:compileKotlin` to the Android job (it already has the JDK and the Gradle
   cache). Unit tests only — Compose Desktop's UI needs a display, which the runner has not.
10. **Acceptance 1 on Debian needs a display**: `:ravilo-desktop:run` opens a real window (X11/Wayland); the dev box
    has one, the headless verification stack of 2026-09-29 does not. Fine for development, not for CI.
11. **Wire:** none. **Value:** R328 alone browses and signs in but plays nothing; it is the milestone the other three
    stand on, and the owner's "when fully implemented" (R331 D1) already says the release waits for all of them.

## Build notes (2026-09-29)

Built from the dev review. **Run on Linux only** — this host has no Mac and no Swift compiler, so the Swift library
has never been compiled and nothing Mac-specific has run. `⚠ Partial` until a Mac confirms acceptance 2 and 4.

1. **FR-R328-1 — the targets.** `jvm("desktop")` (JVM 17) in `shared`, `ravilo-i18n` and `ravilo-ui`; `-Xexpect-actual-classes`
   in `ravilo-ui`. The Android job in `ci.yml` runs `:shared:desktopTest :ravilo-i18n:desktopTest :ravilo-ui:desktopTest
   :ravilo-desktop:compileKotlinDesktop` (the task is `compileKotlinDesktop`, not the review's `compileKotlin`).
2. **FR-R328-3 — the 62 seams**, in `ravilo-ui/src/desktopMain`. Stores are small JSON files (`PrefsFile`: in memory,
   written through by temporary file + atomic rename; corrupt reads as empty). Tokens go through `SecretStore`: the
   Keychain when the library loaded, else `tokens.json` at `0600`, served from memory after the first read (every
   request asks for the token). `MultiTokenStore` keeps who each session is in `ravilo_sessions.json` and each token
   under `session.<userId>`, never in the plain file. The player, music and cast seams give the absent answers (a
   load fails at once, so R237's card shows rather than a spinner; `MusicEngine.supported = false`; the screen sender
   alone) until R329/R330.
3. **FR-R328-4 — one native library, loaded through JNA.** `native/RaviloMac.swift` exports plain C functions
   (`@_cdecl`): the ABI number, the Keychain (`SecItemCopyMatching`/`SecItemUpdate`/`SecItemAdd`/`SecItemDelete`,
   generic password, service `dev.jellystructure.ravilo`, the login keychain) and the Computer Name
   (`SCDynamicStoreCopyComputerName`). Kotlin calls them through **JNA** (`MacNative`, the one load point) rather than
   hand-written JNI: no C glue and no `Java_…` names to keep in step, and a stale library is refused by its ABI
   number. `buildMacNative` runs `xcrun swiftc` only on a macOS host and drops the dylib into Compose's app
   resources, where `run` and the bundle both find it (`compose.application.resources.dir`). Missing: one log line,
   the file fallback, never a crash.
4. **FR-R328-5 — window, keys, menu.** Minimum 960 × 600, size and position remembered (`ravilo_window.json`; a
   position off every display opens centred). Arrows, Return and Esc needed nothing: every `Key.Back` site in
   common code also answers `Key.Escape` (checked), so the desktop `PlatformBackHandler` is empty. macOS draws the
   app menu itself; `Desktop.setAboutHandler`/`setPreferencesHandler`/`setQuitHandler` and `AppReopenedListener`
   receive it (`MacAppHooks`). View and Window are Compose's `MenuBar`. On Linux, where nothing draws an app menu,
   About · Settings… · Quit are a menu of our own. *Settings…* reaches the app through a new common `AppCommands`
   flow (never over a film or live TV, which it would stop). About is a small window: the mark, *Version …*,
   *Signed in to …*; Esc closes it.
5. **FR-R328-6.** `X-Ravilo-Platform: mac` — and `linux` for the development build, which is honest and free text
   (R252). `RaviloUsers.kt` maps both. **One more server reader than the review counted:** R314's
   `focusFactsReach` also reads the platform, and the desktop is not a TV (`isTvPlatform = false`), so `mac` and
   `linux` join `phone` and `web` in getting no focus facts (`HomeFeedFocusFactsPlatformTest`).
6. **FR-R328-8 — the update line** is in **Settings ▸ Account** (on the TV layout, Profile → Settings is the Settings
   page; there is no Profile page off a phone), between *Signed in as* and *Change password*, in the explicit
   D-pad chain. `UpdateCheck` asks `/api/health` once the app knows its server, every 24 hours after, a quarter of
   an hour after a failure; `raviloNewerRelease` (in `shared`, tested) ignores a leading `v` on both sides — prod
   reported `v1.44-49-g…`. Only a Mac is offered a `.dmg`, at R331's name on the tag's release. Under *Download*,
   R331's *Open Anyway* sentence (`mac.open_anyway`).
7. **FR-R328-9 — closing.** The red button and ⌘W hide the window while music plays, otherwise quit; the Dock icon
   brings it back; ⌘Q cancels the system's quit and runs ours, which gives registered stops two seconds
   (`DesktopShutdown`) before exiting.
8. **FR-R328-10 — strings.** `mac.update_available`, `mac.download`, `mac.open_anyway`, `mac.about_signed_in`,
   `mac.about_version` and seven menu items × en/da/fo (da/fo drafts; Faroese names macOS's own labels in English,
   since macOS has no Faroese).
9. **Two small changes beyond the spec.** The Account page's *Take a photo* row is absent on the desktop (a new
   `offersTakePhoto` seam, `true` elsewhere), as the seam table says; and Esc at Home does nothing — the table said
   the exit action closes the window, but a viewer pressing Esc a few times to get back to Home must not lose it.
   `rememberAppOnScreen` is *shown and not minimised*, not *focused*: a film on a second display while the viewer
   types elsewhere is on screen.
10. **Verified on Linux (Xvfb, the local stack):** server setup → sign-in → Home; arrows move focus, Return opens a
    detail, Esc comes back with focus restored and does nothing at Home; *Settings…* opens Settings; About; Quit
    exits 0; a relaunch goes straight to Home. The token sits only in `tokens.json` (`0600`); the server recorded the
    device as `linux` with its version and host name. Tests: `shared` 51, `ravilo-i18n` 24, `ravilo-ui` 271 on
    the desktop target; Android, both web targets and the admin compile; every fence script passes.
11. **Owed to a Mac:** the Swift library's first compile, the Keychain round trip (and the *confidential
    information* prompt a `gradle run` build is expected to show), the Computer Name, the system menu bar and its
    handlers, the Dock reopen, full screen. The macOS icon (`icons/ravilo.icns`) is rendered from the brand mark
    on Linux.
12. **First run on a real Mac (2026-09-29, the owner's MacBook Pro, macOS 27.0.1, Apple Silicon).** The signed app
    from the `.dmg` opens once approved, signs in and browses; its tokens are in the Keychain (service
    `dev.jellystructure.ravilo`, no `tokens.json` in the data directory), and the server recorded the device as `mac`,
    version 1.46, under the Mac's Computer Name. `--self-test` passes on the Mac. Quitting from the player sends the
    stop. Still owed: the menu bar's handlers, the Dock reopen, full screen by the menu, and the update line.

## D5 amended (owner, 2026-09-30): tokens in the app's own file, not the Keychain

D5 kept each device token as a Keychain item, on R331's premise that one signing certificate lets every later build
read it. It does not. macOS binds a Keychain item to a **team ID** when the app has one and to the **exact build**
(its code-directory hash) when it has none, and an app signed with a certificate of our own has none. *Always Allow*
therefore lasted until the next update; every update — and every test build — asked for the Mac's login password
again, which the owner met a dozen times in one morning. Tokens now live in `tokens.json` (mode `0600`) in the app's
data directory, as the Linux app's always have; a token left in the Keychain by an earlier build is moved across on
first use and its Keychain item removed. A token is a Ravilo device token, revocable in Users & devices — never the
viewer's password. `ravilo_keychain_*` stay in the Swift library for that one migration.

## Triage (2026-10-09, against `main` `9ea5da3c`)

- **Code: nothing left.** The four items the build notes still owed are in the code: the menu bar's handlers
  (`RaviloMenuBar.kt`, wired in `Main.kt`), the Dock reopen (`onReopen`), full screen by the menu
  (`onToggleFullScreen` → `setFullScreen`) and the update line (`mac.update_available` in `AppUpdate.kt` and
  Settings ▸ Account). They landed with R337's passes.
- **Owed: a Mac check** of each (see the test plan sent to main on 2026-10-09).
