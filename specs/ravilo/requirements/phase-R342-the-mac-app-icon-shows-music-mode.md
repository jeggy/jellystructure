# Phase R342 — The Mac app icon shows music mode

> Owner, 2026-10-01: *"On desktop, I would like to be able to change the app icon dynamically … when on mobile and in
> the Music mode, there is an additional music tone icon next to the Ravilo logo … I would like to get this music icon
> as well to be part of the Ravilo App Icon and then we will dynamically on macos … have it switch to this when in
> music mode."* Picked: **M7b**. Q2–Q7 below.

## Status

`Planned`. Written 2026-10-01 (design-authored) from `design/ravilo/Desktop - Music App Icon.html`. **Dev-reviewed
2026-10-01** (§Dev review: FR-R342-6 is dropped, the plug-in needs a spike on the Mac first, and items 6 and 9 need the
owner).
**Numbering:** checked against `main` (tree `f88c706e`) the same day, after R341.

**Builds on:**
- R328: the Mac app and its Swift library
- R337: the desktop's films ⇄ music switch
- R338: the macOS appearance, read through `effectiveAppearance`
- R341 FR-R341-1: the 66 % Mac size

**macOS only** (Q5).

## Decisions

| # | Question | Decision |
|---|---|---|
| Q1 | The drawing | **M7b**: the films icon unchanged, plus M7's bubble (a thin gradient ring with a white ♪) in the bottom-right corner. The jellyfish stays centred at full size. M3 and M4 were rejected outright. |
| Q2 | What it follows | **The mode only**, whether or not anything is playing. Music mode shows ♪ even when nothing plays. Films mode shows the films icon even while music plays on in the bar. |
| Q3 | Audiobooks | **The same ♪.** There is no audiobooks mode: books are part of music mode. |
| Q4 | Closed and reopened | **The mode is remembered.** Ravilo opens in the mode it was closed in, and the closed Dock icon shows that mode. |
| Q5 | Linux / Windows | **Mac only for now.** GNOME can't change a running app's icon. KDE and Windows can come later as their own phases. |
| Q6 | macOS 26 icon styles | **Draw every style** for both icons, so Ravilo looks right in Default, Dark, Clear and Tinted. |
| Q7 | Finder, Launchpad, Spotlight | **Nice to have, not required.** Do it only if it is safe for the signature and for updates (FR-R342-6). |

## Requirements

**FR-R342-1 — The music icon (M7b).** It is drawn on R341's Mac placement, on a 100-unit tile:

- **The jellyfish:** R341 FR-R341-1's Mac transform, centred and unchanged, with a **circle of radius 17 at (78, 78)
  cut out of it**. That is a real cut, not a navy disc painted on top, so it holds on glass (FR-R342-5).
- **The ring:** a circle at (78, 78) with radius 15, `fill` white at 8 %, and a 2.6-unit stroke in the brand gradient
  `#AA5CC3 → #00A4DC`.
- **The note:** ♪ in white at `translate(75.48 85.01) scale(.42)`, drawn about its head:

```svg
<ellipse cx="0" cy="0" rx="9" ry="6.8" transform="rotate(-22)"/>
<rect x="5.2" y="-38" width="4.6" height="38" rx="1.6"/>
<path d="M5.2 -38 L9.8 -38 C11 -30 23 -28 21.5 -13 C21 -10 19.5 -7.5 18 -6 C19 -12 17.5 -20 9.8 -24.5 Z"/>
```

- **At 32 px and below,** the bubble is drawn bigger so it doesn't disappear: a cut radius of 21.5, a ring radius
  of 19, a 4.2-unit stroke, and the note at `translate(75 86.25) scale(.5)`.
- **The bubble sits bottom right** so it stays clear of the red notification badge, which macOS puts top right.

**FR-R342-2 — Two Icon Composer files and their exports.**
- `ravilo-films.icon` is R341's icon, and the installed one.
- `ravilo-music.icon` has four layers: background (navy), jellyfish (with the cut), ring, and note.
- From each file, export one PNG for each of the **six styles**, at the sizes the Dock and ⌘-Tab use:
  - **Default**
  - **Dark** (tile `#05070E`, colours kept)
  - **Clear light** and **Clear dark** (glass, with every mark in one white tone)
  - **Tinted light** and **Tinted dark** (every mark in the system tint)
- The app ships these exports. The design canvas shows each style, but only approximately: Icon Composer's own render
  is the reference.

**FR-R342-3 — The running icon follows the mode and the style.** The Swift library sets the Dock image
(`NSApp.applicationIconImage`) to the export matching the current mode and style. It updates:
- at launch, before the window shows
- on every switch between films and music (R337), at the same moment, with no animation
- whenever the appearance changes (R338's KVO on `effectiveAppearance`)

⌘-Tab follows on its own, because it shows the running app's image.

**FR-R342-4 — The mode is remembered on this Mac.**
- **Where it's stored:** the last mode is a local preference. It isn't synced to the profile, so each device opens in
  the mode it was left in.
- **Launch:** Ravilo opens straight into the stored mode. Films must not flash up first.
- **The fallback:** if music mode isn't available at launch, Ravilo opens in films mode, stores films, and the icon
  follows. That covers three cases: there is no music library, the viewer has no music access, or no one is signed
  in.
- **One viewer per computer** (R337 Q12), so the stored mode belongs to this Mac.

**FR-R342-5 — The closed Dock icon shows the stored mode.**
- **How:** a Dock tile plug-in (an `NSDockTilePlugIn` in `Contents/PlugIns/`) reads the stored mode and the current
  style, and draws the matching export. It does this even while Ravilo isn't running, including after a restart
  before Ravilo has run.
- **Signing:** the plug-in is signed and notarised with the app (R331).

**FR-R342-6 — Finder, Launchpad and Spotlight follow the mode only if it's safe (nice to have).**
- **The only way:** Ravilo rewrites its own icon file (`NSWorkspace.setIcon(_:forFile:)` on the app bundle) at every
  mode change and after every update.
- **Do it only if** it keeps the code signature valid and doesn't break updates or Gatekeeper.
- **If not,** these places keep the films icon. Nothing else depends on this.

## Non-goals

- Linux (GNOME and KDE), Windows, the phone, the TVs and the web app: their icons stay one icon.
- A separate audiobooks icon.
- Any animation of the swap.
- Changes to the films icon: that is R341.

## Acceptance

1. Switch to music: the Dock and ⌘-Tab icons gain the bubble at that moment. Switch back: it's gone. Neither switch
   depends on whether anything is playing.
2. Quit in music mode: the Dock keeps the music icon. Reopen: Ravilo opens in music mode, with no films screen first.
3. Restart the Mac before opening Ravilo: the Dock shows the music icon.
4. Remove the viewer's music access, then open Ravilo: it opens in films mode and the icon is the films icon.
5. Cycle through Default, Dark, Clear and Tinted: both icons look like the rest of the Dock in each style. At 32 px the
   bubble is still visible.
6. The app still passes Gatekeeper after an update, whether or not FR-R342-6 was built.

## Open questions (for the dev review)

The code review comes later. These are left for it to answer, most of them on a real Mac.

1. **Can an app tell which icon style the user picked** (macOS 26's Clear or Tinted)? If not, FR-R342-3 follows light
   and dark only, and Clear and Tinted show the Default or Dark export. That needs a real Mac.
2. **Can the Dock tile plug-in go inside the Compose Desktop / jpackage bundle,** and be signed and notarised by R331's
   pipeline?
3. **Is FR-R342-6 safe** on a signed, notarised app in `/Applications`? If it isn't, it is dropped.
4. **Launch before the server answers:** FR-R342-4's fallback needs to know whether music is available. Is that known from the local cache at launch, before the server answers? If not, does Ravilo open in the stored mode and step back to films when the server says no, so the icon flips once? Or does it wait? The lean is to open in the stored mode and step back only on a definite *no*. Being offline is not a no.
5. **The export sizes:** which pixel sizes does the Dock actually ask for (Retina at the largest Dock magnification, and ⌘-Tab)? Ship only those, from FR-R342-2's files.
6. **Where the stored mode lives:** the app and the Dock tile plug-in both need it. Is that a shared `UserDefaults` suite, or does the plug-in read the app's own domain? And is the plug-in told when the mode changes while Ravilo is closed, which can't happen?
7. **Turning the icon off:** should there be a setting? The owner hasn't asked for one, and the lean is no.

## Dev review (2026-10-01, against `main` `44e26871`)

Read against `ravilo-desktop` (the window, the Swift library in `native/`, the packaging in `build.gradle.kts`),
`ravilo-ui` (commonMain's mode state, desktopMain's stores and appearance), `.github/workflows/deploy-macos.yml` (R331),
the shipped `ravilo.icns`, and `design/ravilo/Desktop - Music App Icon.html`. Apple's own documents say little about
Dock tile plug-ins and nothing about macOS 26's icon styles, so items 6, 8, 9 and 11 also lean on what other shipping
apps found (Ghostty's plug-in on macOS 26, a 2026 crash report about another vendor's plug-in, a write-up of the Dock's
plug-in host); each says so. Nothing here was run on a Mac. The running half (FR-R342-3) is straightforward. The
remembered mode (FR-R342-4) is mostly shipped already, apart from one existing bug (item 2). The closed-Dock half
(FR-R342-5) has a cost the spec does not know about: on macOS 26 a Dock tile plug-in stops the Dock from styling the
icon at all (item 9). FR-R342-6 is not safe and is dropped (item 11). Items 6 and 9 need the owner, with leans.

1. **The drawing in FR-R342-1 matches the canvas, number for number.** `app()` in the canvas (lines 116–131) draws
   the jellyfish at `at(1.18, 50, 50, 50, 59.5)` = `translate(-9 -20.21) scale(1.18)` (R341's Mac transform), masks a
   circle of radius 17 (21.5 at ≤ 32 px) at (78, 78), draws the ring at radius 15 (19), stroke 2.6 (4.2), and the
   note at `at(.42, 78, 78.5, 6, -15.5)` = `translate(75.48 85.01) scale(.42)` (small: `translate(75 86.25)
   scale(.5)`). Two details the prose leaves out, both in the canvas: in the Clear and Tinted styles the ring has no
   fill (only the stroke), and in Clear · light the marks carry a small drop shadow. The exports (item 7) follow the
   canvas there.

2. **Bug in shipped code: an unreachable server counts as "no music", so a music-mode viewer who opens Ravilo
   offline lands in films.** `RaviloApp.kt:944–947` asks `getAudiobooks()` and `getMusicHome()` and turns any
   exception into `false` (`getOrDefault(false)`). Then `inMusic` (`:949`) goes false and `:952` resets the stack to
   Home. R321's FR-R321-2 asks for that only when the viewer *lost the grant*. It hits the phone too. This also
   answers **open question 4**. There is no local cache of "music is available": the answer comes from the server
   every launch. The fix has three outcomes:
   - **A definite no:** the server answered, `/tv/music/home` has no rows (`MusicTvService.kt:144` answers an empty
     `MusicHome` to a viewer with no music), and audiobooks are absent (`TvApiClient.kt:392–398`: a 404 is `null`).
     Fall back to films, and **write `video` to the store** (item 3).
   - **Unknown:** the request threw (offline, timeout, 5xx). Keep `musicAvailable = null`, which already means
     "assume yes" (`:949`). Stay in the stored mode, and the music pages show their own unreachable states.
   - **Yes:** as today.

   So the lean in open question 4 is what the code does once this is fixed. Ravilo opens in the stored mode at once
   (`:582–583` builds the first stack from `ListeningMode.read()`, so films never flash first) and steps back once,
   only on a definite no. The icon flips at most once.

3. **FR-R342-4 is already built, except for "stores films".** The mode is `ListeningMode` (`MusicDeviceStore.kt:15–21`).
   On the desktop it is the key `mode` in `ravilo_music.json` (`MusicDeviceStoreDesktop.kt:7`, a `PrefsFile`), in
   `~/Library/Application Support/Ravilo/` (`DesktopPaths.kt:18`). It is per device and never synced, and it is
   written on every switch (`RaviloApp.kt:1071`, `:2068`). Sign-out writes `video` (`MusicDeviceStore.kt:42–47`,
   called from `SettingsScreen.kt:180/198`), which covers "no one is signed in". What is missing is a write on the
   definite no from item 2: `:952` resets the stack today but leaves `music` stored, so the closed icon (item 8)
   would show ♪ for a viewer who can't use it.

4. **The running icon follows `inMusic`, not `musicMode`, through one new seam.** `musicMode` (`:934`) is the stored
   wish. `inMusic` (`:949`) is what the screen shows. They differ only while the server says no, and the icon must
   match the screen. Add `expect fun reportListeningMode(music: Boolean)` in `ui/seams`. It does nothing on Android,
   web and Tizen; on the desktop it calls a new `DesktopDock` object, which acts on the Mac only. Call it from
   `LaunchedEffect(inMusic)` in `RaviloApp`. Audiobooks need nothing: they are music mode (Q3).

5. **Yes, a JVM app can set its running Dock icon. Do it in the Swift library, not through `java.awt.Taskbar`.**
   - **Both work.** AWT's `Taskbar.setIconImage` ends in `NSApp.applicationIconImage`. But the Swift library can load
     the PNGs straight from disk and build one `NSImage` with two representations (item 7). It is also where items 6
     and 8 live.
   - **The call:** add `ravilo_dock_icon(path: char*)` in a new `native/Dock.swift`. It sets the image on the main
     queue, as `Window.swift:73` does, and `nil` puts the bundle's icon back. Add it without an ABI bump, the way
     `MacNative.kt:60` added R338's call.
   - **Not `Window(icon = …)`.** That is the AWT window's icon (`Main.kt:127`), and the Dock ignores it on a Mac.
     Leave it as it is.
   - **When:** first from `Main.kt`'s opening `LaunchedEffect` (`:113–120`, beside `MacAppHooks.install`), because
     AWT is running and `NSApp` exists by then. Then on every change of `inMusic` and of the style (item 6). On quit
     there is nothing to undo: the image dies with the process.
   - **What can't be avoided:** from the click until the JVM is up (about a second or two), the Dock shows the
     bundle's own icon, which is films. FR-R342-3's "before the window shows" is met. "Never films first" in the
     Dock can only come from the plug-in, and only if the Dock keeps the plug-in's tile while the app launches. That
     has to be checked on the Mac.
   - **⌘-Tab follows on its own**, as the spec says.

6. **Open question 1: there is no public way to read the icon style. There are two undocumented global defaults —
   needs the owner (lean: read them).** macOS 26 writes `AppleIconAppearanceTheme`, with these values:
   - `RegularDark` and `RegularAutomatic`;
   - `ClearLight`, `ClearDark` and `ClearAutomatic`;
   - `TintedLight`, `TintedDark` and `TintedAutomatic`.

   When the key is absent, the style is Default. A second key, `AppleIconAppearanceTintColor`, holds the tint: a named
   colour (*Graphite* is one). How a custom colour is stored is unknown; read it on the Mac with
   `defaults read -g | grep -i Icon`. Both keys are readable from the Swift library through `UserDefaults.standard`,
   the same way `ravilo_appearance_dark` reads `AppleInterfaceStyle` (`RaviloMac.swift:76–79`).
   - **The rules:**
     - An `…Automatic` value resolves through the system's light or dark mode.
     - An unknown value, or no key at all, gives Default, or Dark when the system is dark.
     - On macOS 14 and 15 (`minimumSystemVersion = "14.0"`, `build.gradle.kts:99`) icons have no styles, so it is
       always Default.
   - **The trigger is not R338's KVO.** Change FR-R342-3's third bullet. R338 does not watch `effectiveAppearance`:
     the app is pinned to DarkAqua (`Main.kt:74`), so `DesktopAppearance` polls `AppleInterfaceStyle` once a second
     (`DesktopAppearance.kt:18–35`). Read the icon theme in the same tick and follow that one flow.
   - **The cost:** the keys are undocumented and may change in a later macOS. If they do, the icon falls back to
     Default or Dark and nothing breaks. Q6 can't be met any other way.
   - **What a runtime image can't do.** The Dock draws `applicationIconImage` exactly as given on macOS 26: no glass
     edge, no squircle mask, no re-styling. So:
     - **Tinted can't be a fixed PNG.** The tint is the viewer's own colour. Export the marks and the tile as
       one-colour masks, and paint them with the tint at run time. Tinted · light paints the tile in the tint with
       white marks; Tinted · dark paints `#0C1322` with marks in the tint, as `app()` does.
     - **Clear is an approximation.** It is a translucent tile with no live blur of the desktop behind it.
     - **Every export must carry macOS's icon-grid margin itself.** The canvas's tile fills its 100-unit box. A
       picture drawn edge to edge sits bigger than its neighbours in the Dock (R341's open question 2).

7. **Open question 5 and FR-R342-2: what ships, and where it comes from.**
   - **What the bundle has today.** Only `ravilo.icns` (`build.gradle.kts:101`). It holds ten PNG entries
     (ic07–ic14, icp4, icp5; 16 to 1024 px), with no `.icon`, no `Assets.car` and no `CFBundleIconName`. So on macOS
     26 the installed icon is styled by the system's fallback for old icons, not from layers.
   - **Making `ravilo-films.icon` the installed icon is R341's work**, and R342 builds on it:
     - Xcode 26's `actool` compiles the `.icon` into `Assets.car`.
     - Compose's DSL can't put files into `Contents/Resources`, so copy `Assets.car` into the `.app` after
       `createDistributable` and before CI signs it.
     - Add `CFBundleIconName` through `extraKeysRawXml` (`build.gradle.kts:103–110`).
     - Keep the `.icns` for macOS 14 and 15.
     - Check which Xcode the `macos-15` runner has.
   - **The exports are made by hand on the MacBook and committed.** Icon Composer (or the `ictool` inside it) renders
     each style from the `.icon`; CI never runs Icon Composer. They go in `ravilo-desktop/icons/dock/`, one copy,
     packaged into the plug-in's `Resources` (item 10). The app reads them from there too, and does nothing when they
     are absent (a `gradle run`).
   - **Sizes.** The Dock tile is at most 128 pt (size plus magnification), which is 256 px on Retina; ⌘-Tab is
     smaller. Ship 512 px for headroom, plus the ≤ 32 px drawing as a second representation of the same `NSImage`. The
     Dock is handed one picture by the running app, so whether it ever uses the small one is a check on the Mac. If it
     doesn't, nothing breaks.
   - **Per icon:** Default, Dark, Clear · light and Clear · dark as whole pictures, plus the tile and marks masks for
     Tinted (item 6). That is about ten files, under 1 MB in all.

8. **Open question 6: the plug-in reads the app's own defaults domain, mirrored from the JSON store. It can't be an
   App Group.**
   - **Where the plug-in runs.** Not in Ravilo, and not in the Dock itself, but in Apple's XPC service
     `com.apple.dock.external.extra.<arch>`. Reports differ on whether that service is sandboxed, so don't count on
     it reading `~/Library/Application Support/Ravilo/ravilo_music.json`.
   - **The proven path is Ghostty's shipping plug-in.** The app writes its own defaults domain and posts a distributed
     notification. The plug-in reads `UserDefaults(suiteName: "<the app's bundle id>")` and listens for that
     notification.
   - **Why not a shared App Group suite:** a group container needs a Team ID, and our self-signed certificate has
     none (the same reason the README moved tokens out of the Keychain).
   - **So, on the desktop:** `MusicDeviceStore.put` mirrors the key `mode` into `UserDefaults.standard` as `DockMode`,
     through the Swift library. The JVM runs inside `Ravilo.app`, so that is the domain `dev.jellystructure.ravilo`
     (`build.gradle.kts:97`). It then posts `dev.jellystructure.ravilo.dock` on `DistributedNotificationCenter`. The
     JSON file stays the source of truth. The mirror holds the stored mode (item 3), not `inMusic`.
   - **The second half of the question.** The mode can't change while Ravilo is closed. But the Dock calls
     `setDockTile:` only once, at login or when the tile is added to the Dock, not at each quit. So the plug-in must
     listen for the app's notification while Ravilo runs. It must also follow style changes on its own:
     `AppleInterfaceThemeChangedNotification`, plus whatever the icon-style switch posts (find out on the Mac). No
     timers: the plug-in lives for as long as Ravilo's tile is in the Dock.

9. **The plug-in takes the styling away from the Dock — needs the owner, after a spike on the Mac.** Ghostty found
   this on macOS 26. Once `Info.plist` declares `NSDockTilePlugIn`, the Dock routes the tile through the plug-in
   (`dock-extra`), and the tile **stops following Icon & widget style, even for the installed `.icon`**, whether the
   app is running or not. A style change shows only after a relaunch. So:
   - **With the plug-in**, Ravilo owns its Dock tile at all times. The films icon must also be drawn per style by us
     (items 6–7), and Clear is approximated.
   - **Without it**, the closed Dock shows the installed films icon in the system's real styles, always films.

   Three more limits:
   - The Dock loads a plug-in only at login or when the tile is added. After the first update that brings it, nothing
     happens until the next log in.
   - The closed icon exists only while Ravilo is kept in the Dock.
   - A downloaded (quarantined) app's plug-in may wait for the Open Anyway approval, or for a restart.

   **The owner's choice:**
   - **(a)** Build the plug-in. Q4 is met in full, Q6 is met with our own pictures read from undocumented keys.
   - **(b)** Drop it. The closed Dock shows the films icon, styled by macOS. Ravilo still opens in the stored mode,
     and the running icon still follows it.

   **Lean:** a spike first, on the MacBook, by the owner or with the owner's leave (not done here). It is a minimal
   plug-in signed with the Ravilo certificate. Check that it loads, that it draws all six styles, that switching the
   style updates it, and that it survives replacing the app with a new build. Take (a) if all four hold, (b)
   otherwise.

10. **Open question 2: yes, it fits in the bundle and in R331's pipeline, signed but never notarised.**
    - **Correct FR-R342-5's "signed and notarised (R331)".** R331 has no notarisation and never will (owner,
      2026-09-29); the bundle is signed with the one self-signed certificate (`deploy-macos.yml:6–12`).
    - **The signature is enough.** The Dock's plug-in service has library validation turned off, so it loads code
      signed by any identity. Since Catalina it refuses an unsigned plug-in, which ours isn't.
    - **What to build:**
      - **The plug-in is built from Swift on a Mac, like the library.** A new `Exec` task, `buildDockPlugin`,
        beside `buildMacNative` (`build.gradle.kts:55–70`), with the same `onlyIf { isMacHost }`. It compiles a
        loadable bundle with `@objc(RaviloDockTilePlugIn)` as its class. Every app's plug-in shares one process, and
        `NSPrincipalClass` is how the Dock tells them apart, so the Objective-C name must be unique and not Swift's
        mangled one.
      - **The bundle.** Its own `Info.plist`: `CFBundlePackageType` `BNDL`, `CFBundleIdentifier`
        `dev.jellystructure.ravilo.dock`, and `NSPrincipalClass`. Item 7's exports go in its `Resources`.
      - **Into `PlugIns`.** Compose's DSL has no way to fill `Contents/PlugIns` (`appResourcesRootDir`, `:92`, lands
        in `Contents/app/resources`). So a task that runs after `createDistributable` copies the plug-in to
        `Ravilo.app/Contents/PlugIns/RaviloDock.docktileplugin`.
      - **The Info.plist key.** `NSDockTilePlugIn` = `RaviloDock.docktileplugin` goes into `extraKeysRawXml` only
        when `isMacHost`, so a bundle never names a plug-in it doesn't have.
    - **CI** (`deploy-macos.yml`):
      - Fail if the plug-in is missing, as `:176–177` does for the library.
      - Sign it explicitly before the app. The `find` at `:189` matches only `.dylib`, `.jnilib` and `.so`, and the
        plug-in's executable has no extension. `--deep` at `:195` would reach `PlugIns`, but the file already signs
        inside out (the runtime, `:192–194`).
      - Verify it with `codesign --verify --strict`.
      - `--self-test` names it (`SelfTest.kt:20–25`).
    - **Keep the plug-in tiny.** No network and no work beyond reading two defaults and drawing one picture: a
      crashing plug-in crash-loops the Dock's helper (seen with another vendor's plug-in in 2026). If it is ever
      removed, remove the key and the bundle in the same release.

11. **Open question 3: FR-R342-6 is not safe. Dropped; Finder, Launchpad and Spotlight keep the films icon.**
    - **What `NSWorkspace.setIcon(_:forFile:)` does to an app bundle.** It writes an `Icon\r` file into the bundle's
      root and sets a Finder-info attribute on it. `codesign` rejects both ("resource fork, Finder information, or
      similar detritus not allowed"), so `codesign --verify --strict` fails on the installed app.
    - **It works today, but only by luck.** macOS doesn't re-check an approved app's seal at every launch. Later
      checks are a risk to the Gatekeeper approval, to the Local Network grant (`build.gradle.kts:105–108`) and to
      acceptance 6.
    - **The App Management prompt.** Writing into an app bundle can raise it, and a self-signed app has no Team ID to
      be exempt by.
    - **Every update undoes it.** Dragging a new `Ravilo.app` out of the `.dmg` brings the films icon back, and
      Finder and Spotlight cache icons anyway.

    By the spec's own rule, it is not built. Nothing else depended on it.

12. **Open question 7: no setting.** Agreed with the lean; nothing is built for it.

13. **Acceptance, as the code can meet it.**
    - **1, 4 and 5 stand.** In 5, "Clear" means the approximation from item 6.
    - **2 and 3 hold only with the plug-in** (item 9), and only while Ravilo is kept in the Dock. 3 also needs one
      log out and back in after the first release that carries the plug-in.
    - **6 needs rewording.** The app is not notarised, so "passes Gatekeeper" means: `codesign --verify --deep
      --strict` passes, and the update opens without a new *Open Anyway*.
    - **Add one:** in music mode, open Ravilo with the server unreachable. It stays in music mode, and the icon keeps
      ♪ (item 2).

14. **Wire and versions: none.** This phase is client-only plus packaging: no route, no DTO, no new string. An old
    server is handled by item 2's 404 rule. Build order, each step shippable:
    - **(a)** Item 2's fix and the store half of item 3. The phone gains from it too.
    - **(b)** The running swap in Default and Dark (items 4–5). It needs R341's Mac drawing for the films picture, so
      R341 comes first.
    - **(c)** Reading the style and the Tinted and Clear pictures (items 6–7).
    - **(d)** The plug-in spike, then the owner's pick (item 9), then items 8 and 10.
