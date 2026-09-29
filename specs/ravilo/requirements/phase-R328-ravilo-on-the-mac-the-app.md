# Phase R328 — Ravilo on the Mac: a desktop target and the app around it

> Owner, 2026-09-29: *"Lets create specs for this. So when fully implemented, the dmg file will be attached to the
> github release, just like android and Tizen."*

## Status

`Planned` — written 2026-09-29 (dev-authored) from `research-reports/ravilo-macos-desktop-app-2026-09-29.md`
(road 2). Not dev-reviewed. Number verified free: `origin/main` `e828c944` and local `main` top at R327.

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
| D5 | Where secrets live | the device tokens in the **macOS Keychain**; everything else in `~/Library/Application Support/Ravilo/` |
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

1. **The owner's four from the research report:** is the native app worth it (the speakers need it); an Apple
   Developer ID (R331); Apple Silicon only (D2); the Cast client written for reuse by the backend (R330, D1).
2. Should the TV layout get a desktop density by default (smaller tiles at a desk), or keep R174's grid-columns
   config and let the household set it? Lean: keep the config — no new setting.
3. Is *Computer Name* the right device name, given that Macs are often named *{person}'s MacBook Air*? It is what
   the owner sees in Users & devices, and it is renamable in both places.

## Dev notes

- 62 `expect`s in `ravilo-ui/src/commonMain` (counted 2026-09-29); for size, `androidMain` has 3 543 lines of
  actuals and `wasmJsMain` 1 576.
- `shared` already carries `parseVtt`/`activeCueText` (`ReceiverSubtitles.kt`), which R329 reuses.
- Android sends `platform = if (isTelevision) "tv" else "phone"` (`RaviloRootActuals.kt`); the web sends `"web"`.
