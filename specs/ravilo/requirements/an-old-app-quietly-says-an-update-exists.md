# An old app quietly says that an update exists

Task: DEB-5851

> Owner, DEB-5687: *"Let's try to find UI/UX improvements we could make to Ravilo. We want to make the app beautiful
> in our edge cases, but remember that QoE and performence is most important. We are also now getting more and more
> users."* He picked this piece on the card *"Ravilo polish: what to build"* on 2026-11-26, and chose the gentle form:
> a quiet "update available" line that never blocks playing.

## Scope

Clients only. **No server change and no wire change**: the one input is the `version` field `GET /api/health` has
answered since 224 (FR-224-1), public, the same read the Mac's updater makes (R386 FR-R386-3).

- **In:** Android TV, Android phone, Ravilo Web, the Linux desktop, Samsung.
- **Out:** the Mac, which updates itself (R386; nothing in it changes). The Chromecast receiver, which the server
  serves itself under `/cast/**` (`src/linuxX64Main/kotlin/dev/jellystructure/server/Server.kt:918-922`), so it is
  always the server's build.

It amends R263 (FR-R263-5) and R386 (FR-R386-6 and its out-of-scope list), each with a dated note in its own file.
It reuses R386's version rule (`raviloServerFloor`, `shared/src/commonMain/kotlin/dev/jellystructure/shared/tv/RaviloVersion.kt:73`)
and the admin's own count of devices behind (259, FR-259-9). Nothing here is a minimum version: an old app is told,
never stopped. A forced floor is not part of this piece.

## Why

- Phones and TVs seen in the last 30 days run v1.60 to v1.72 while v1.73 is out. Nothing tells them.
- The server keeps each device's version and nothing more (`TvRoutes.kt:1253-1266` counts them for the admin only).
- Only the Mac checks. Android has no prompt. Linux has none, and its About still says *Flathub updates the Flatpak*
  (`ravilo-ui/.../screens/SettingsScreen.kt:713`), though R334 withdrew Flathub.
- A Ravilo Web tab left open never asks its service worker for a new build: `boot.js` registers the worker once and
  never calls `update()` (`ravilo-web/src/wasmJsMain/resources/boot.js:253-268`). When a build does wait, the
  *Ravilo updated · Reload* toast is mounted at the app root over every screen, the player included
  (`ravilo-ui/.../RaviloApp.kt:3965`).
- Samsung's code is packed in the `.wgt` (`ravilo-screen/wgt/ravilo-screen.js`), so it is only updated by an install
  from the phone (`samsung-tv-install-from-the-phone.md`, FR-7 and FR-10/11).

## Requirements

### FR-1 — One rule decides "a newer Ravilo exists"

A pure function in `shared` (beside `raviloServerFloor`) takes the app's own version A (`raviloVersion()`), the
server's `/api/health` `version` and the local first-seen record (FR-3), and answers *nothing* or *release S*.
- S is `raviloServerFloor(server)`. A is A's plain release (a leading `v` ignored).
- It answers S only when both are known, A is a plain release, S has the same MAJOR and S > A.
- A development or preview build (`-N-g<sha>`, `dev`) never gets a notice, and neither does an unknown server.
- Proof: a table test in `shared`, including `v1.73-81-g921645877` against 1.72 (S = 1.73) and against 1.73 (nothing).

### FR-2 — When the check runs, and when it never runs

Android TV, phone and Linux ask `GET /api/health` once per app start and then once every 24 h while the app is open.
- The first ask waits until Home has drawn its first content. It never runs during sign-in, the Play wait or a play.
- While a player (film, episode, live channel, music, audiobook) is on screen, a due check waits until it closes.
- It is one small GET on the shared HTTP client, off the main thread, with the normal 10 s deadline.
  A failure shows nothing and keeps the last answer. It retries at the next start or the next 24 h.
- It adds no work to the playback start path and no work to TV focus motion.

### FR-3 — The notice waits two days

A newer release is first written down, on this device, with the time it was first seen. The notice appears only
when the same S has been seen for **48 hours**. Reason: prod can report a new floor before every platform's file is
published, and the Play closed testing track is reviewed by Google before testers get it. Most Play installs with
auto-update on will update inside that window and never see the notice.
A larger S restarts the clock. An app that has reached S clears the record.

### FR-4 — What the notice says

One quiet line, never a dialog, never a sheet, never over the player. Text by install source:

| Where it came from | Line | Second line |
|---|---|---|
| Google Play (Android) | `update.available` | `update.available_play` |
| Anything else on Android, or the Linux `.flatpak` | `update.available` | `update.available_download` |
| Samsung | `update.available` | `update.available_samsung` |

The Android install source is the installer package name the platform reports (`getInstallSourceInfo` on API 30+,
`getInstallerPackageName` below; minSdk is 24). `com.android.vending` is Google Play; anything else, or nothing, is
a download.

### FR-5 — Android phone

- The line sits on Profile, directly above the version line at the bottom (`ProfileScreen.kt:283-287`).
- With the download source, tapping it opens `https://github.com/jeggy/jellystructure/releases/latest` in the browser.
  With Play, it opens the app's Play Store page. Neither starts anything inside Ravilo.
- The existing `UpdateDot` shows on the Profile tab of the bottom bar (`RaviloBottomNav.kt:224`) until the viewer
  has opened Profile once for that S. The line itself stays until the app is updated.

### FR-6 — Android TV

- Settings gains an **About** section at its end, under the existing `mac.update.about` heading, with the version, and
  the line when there is one. It is one focus stop, not actionable on the TV (the TV has no browser to send anyone to).
- The existing `UpdateDot` shows on the app bar avatar (`AppBar.kt:600-603`) until Settings has been opened once for
  that S. It is drawn, not focusable, and changes nothing about focus order or motion.

### FR-7 — Linux desktop

- The line sits in Settings ▸ About, between Version and Server (`SettingsScreen.kt:713-724`, the R386 About),
  with the download source. Clicking it opens the releases page above.
- The desktop nav's `UpdateDot` (`DesktopNav.kt:415, 428`) shows until About has been opened once for that S.
- The code comment and any text that says Flathub updates the Flatpak go.

### FR-8 — Ravilo Web checks while it is open

- `boot.js` calls `registration.update()` when the tab becomes visible and at least an hour has passed since the
  last ask, and once every 6 h while it stays visible. No other request is added.
- The FR-1 rule does not apply on the web: the page's version is the server's own (R252, 224 FR-224-6), and the
  service worker remains the only signal (R263 FR-R263-5).

### FR-9 — Ravilo Web: the toast never covers a play

- The existing *Ravilo updated · Reload* toast is held while a player is on screen and shows when it closes.
- Choosing Reload is the viewer's act. The toast is never automatic over a play.

### FR-10 — Ravilo Web: a hidden, idle tab reloads itself

- When a new build is waiting, the tab has been hidden for 30 minutes, and no player is open, the page reloads to
  the same URL. The viewer comes back to the new build on the page they left (the web keeps most pages in the URL).
- Never while visible, never with a player open, never twice for one build.

### FR-11 — Samsung

- Settings ▸ About already reads the server's version (`ravilo-screen/.../ScreenSettings.kt:111-127`). When FR-1
  answers S, a line under the two versions says `update.available` and `update.available_samsung`.
- Samsung has no dot and no other surface. It reads the server version only when Settings opens, as today.
- FR-3's 48 h hold applies, its record kept in the TV's `localStorage`. The phone's Samsung overview is where the
  install happens and already says whether a newer `.wgt` covers this TV (`samsung-tv-install-from-the-phone.md`,
  FR-10/11).

### FR-12 — The words

New keys in `i18n/en.json`, with `da.json` and `fo.json` added in the same commit as drafts (notes say *Draft da/fo*,
the pattern of `mac.update.about`). No key names Jellyfin.

| Key | English |
|---|---|
| `update.available` | `Ravilo {version} is available` |
| `update.available_play` | `Update it in the Google Play Store.` |
| `update.available_download` | `Download it from the Ravilo releases page.` |
| `update.available_samsung` | `Update it from Ravilo on your Android phone: Profile ▸ Samsung TV.` |

`{version}` is MAJOR.MINOR. The TV and Linux About heading reuses `mac.update.about` (*About*).

## What this does not do yet

- No minimum version. An app too old for the server is still not told it is broken.
- No in-app install on Android (Play's in-app update API, or an APK download). Ravilo also runs on phones without
  Google Play services.
- No *What's new* text. No notice on Home, the player or any row.
- The Mac is untouched.

## Design

No design round. Every surface reuses what is built: the `UpdateDot`, the R263 toast, the R386 About section on
Linux, the Profile version line's text style on the phone, the Settings section header and row on the TV, and
Samsung's existing About lines and their CSS. If a reviewer finds the TV About row needs its own look, that is a
round for the designer before slice 5, not a guess in code.

## Acceptance

1. A release app at 1.72 against a server reporting `v1.73-81-g…`: no line before 48 h, the line and the dot after.
2. The same app against `v1.72-…`, `dev`, a blank or no answer: nothing. A preview APK `-g<sha>`: nothing.
3. Opening a film with the check due: no `/api/health` request until the player closes.
4. Phone from Play: the Play text, and tapping opens the Play Store. Sideloaded: the download text and the releases page.
5. Android TV: the About section, one focus stop, the avatar dot; focus timing on Home is unchanged.
6. Linux: the line in About, the dot in the nav, no Flathub text.
7. Web: a tab left visible for 6 h asks its worker; the toast never shows over the player; a hidden idle tab reloads
   once, onto the same page.
8. Samsung: the line in About against a newer server, nothing against the same one.
9. Old installed apps: unchanged, since nothing on the wire moved.

## Build order

One engineer, in this order. Strings go in with the slice that first shows them.

1. **The rule (FR-1, FR-3).** `shared/.../tv/RaviloVersion.kt`, a new `UpdateNotice` value. Proof:
   `JAVA_HOME=~/.jdks/jbr-21.0.11 ./gradlew :shared:desktopTest --tests '*RaviloVersionTest*' --console=plain`.
   ~80 lines. **Junior-ready.**
2. **The check and its record (FR-2, FR-3).** `ravilo-ui/.../components/ClientUpdate.kt` (state, 24 h timer,
   player gate, first-seen store), the `/api/health` read moved out of `UpdateCheck.kt` into common code, all four
   `update.*` keys. Proof: `./gradlew :ravilo-ui:desktopTest --tests '*ClientUpdate*' --console=plain` with an
   injected clock and a fake player-open flag. ~180 lines.
3. **Install source (FR-4).** An `expect fun installSource()` seam, the Android actual, `Download` elsewhere. Proof:
   `./gradlew :ravilo-ui:testDebugUnitTest --tests '*InstallSource*' --console=plain` (Robolectric). ~60 lines.
4. **Phone (FR-5).** `ProfileScreen.kt`, `RaviloBottomNav.kt`. Proof: `:ravilo-ui:testDebugUnitTest --tests
   '*ProfileUpdateLine*'`. ~100 lines.
5. **Android TV (FR-6).** `SettingsScreen.kt` (`SettingsContent`), `AppBar.kt`. Proof: `:ravilo-ui:testDebugUnitTest
   --tests '*TvAboutSection*'`, plus a device check of focus on the Stue TV through the board. ~120 lines.
6. **Linux (FR-7).** `SettingsScreen.kt` (`GnomePreferences` About), `DesktopNav.kt`, the Flathub comment. Proof:
   `:ravilo-ui:desktopTest --tests '*LinuxAboutUpdate*'`. ~80 lines. **Junior-ready.**
7. **Web (FR-8, FR-9, FR-10).** `ravilo-web/.../resources/boot.js`, `UpdateToast.kt`, the wasm `InstallPrompt.kt`
   seam. Proof: a new `tests/e2e/ravilo-web-update-check.spec.ts` (fake clock, a second build's `sw.js`, the player
   open). ~150 lines.
8. **Samsung (FR-11).** `ravilo-screen/.../ScreenSettings.kt`, `screen.css` if a class is needed (existing line
   class first). Proof: a case added to `tests/e2e/ravilo-screen-settings.spec.ts`. ~60 lines.
