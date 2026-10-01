# Phase R342 — The Mac app icon shows music mode

> Owner, 2026-10-01: *"On desktop, I would like to be able to change the app icon dynamically … when on mobile and in
> the Music mode, there is an additional music tone icon next to the Ravilo logo … I would like to get this music icon
> as well to be part of the Ravilo App Icon and then we will dynamically on macos … have it switch to this when in
> music mode."* Picked: **M7b**. Q2–Q7 below.

## Status

`✓ Built` 2026-10-01, not deployed (see *Build notes*). Written 2026-10-01 (design-authored) from `design/ravilo/Desktop - Music App Icon.html`. **Dev-reviewed
2026-10-01** (§Dev review). **The owner decided the review's open items the same day** (§Owner decisions, at the end
of the review):
- no Dock tile plug-in, so the closed Dock always shows the films icon;
- the undocumented icon-style keys are read;
- no setting;
- FR-R342-6 is dropped.

**Numbering:** checked against `main` (tree `f88c706e`) the same day, after R341.

**Builds on:**
- R328: the Mac app and its Swift library
- R337: the desktop's films ⇄ music switch
- R338: the system's light and dark, polled through the Swift library (`DesktopAppearance`)
- R341: the 66 % Mac size (FR-R341-1) and the installed films icon
- R345: an unreachable server is not "no music" (FR-R342-4's fallback depends on it)

**macOS only** (Q5).

## Decisions

| # | Question | Decision |
|---|---|---|
| Q1 | The drawing | **M7b**: the films icon unchanged, plus M7's bubble (a thin gradient ring with a white ♪) in the bottom-right corner. The jellyfish stays centred at full size. M3 and M4 were rejected outright. |
| Q2 | What it follows | **The mode only**, whether or not anything is playing. Music mode shows ♪ even when nothing plays. Films mode shows the films icon even while music plays on in the bar. |
| Q3 | Audiobooks | **The same ♪.** There is no audiobooks mode: books are part of music mode. |
| Q4 | Closed and reopened | **The mode is remembered, and the closed Dock shows the films icon.** Ravilo opens in the mode it was closed in. Once it has quit, the Dock shows the installed films icon, styled by macOS. There is no Dock tile plug-in (owner, 2026-10-01): on macOS 26 a plug-in stops the Dock styling the icon at all (§Dev review item 9). |
| Q5 | Linux / Windows | **Mac only for now.** GNOME can't change a running app's icon. KDE and Windows can come later as their own phases. |
| Q6 | macOS 26 icon styles | **Every style.** The films icon is the installed one, and macOS styles it. The running music icon is Ravilo's own picture for the style the viewer picked. It is read from macOS's undocumented style keys, and falls back to Default or Dark (FR-R342-7; owner, 2026-10-01). |
| Q7 | Finder, Launchpad, Spotlight | **Dropped** (owner, 2026-10-01). They keep the films icon: rewriting the app's own icon is not safe for its signature (§Dev review item 11). |
| Q8 | A setting to turn it off | **None** (owner, 2026-10-01). |

## Requirements

**FR-R342-1 — The music icon (M7b).** It is drawn on R341's Mac placement, on a 100-unit tile:

- **The jellyfish:** R341 FR-R341-1's Mac transform, centred and unchanged, with a **circle of radius 17 at (78, 78)
  cut out of it**. That is a real cut, not a navy disc painted on top, so it holds in Clear and Tinted (FR-R342-2).
- **The ring:** a circle at (78, 78) with radius 15, `fill` white at 8 %, and a 2.6-unit stroke in the brand gradient
  `#AA5CC3 → #00A4DC`. In Clear and Tinted the ring has no fill, only the stroke.
- **The note:** ♪ in white at `translate(75.48 85.01) scale(.42)`, drawn about its head:

```svg
<ellipse cx="0" cy="0" rx="9" ry="6.8" transform="rotate(-22)"/>
<rect x="5.2" y="-38" width="4.6" height="38" rx="1.6"/>
<path d="M5.2 -38 L9.8 -38 C11 -30 23 -28 21.5 -13 C21 -10 19.5 -7.5 18 -6 C19 -12 17.5 -20 9.8 -24.5 Z"/>
```

- **At 32 px and below,** the bubble is drawn bigger so it doesn't disappear: a cut radius of 21.5, a ring radius
  of 19, a 4.2-unit stroke, and the note at `translate(75 86.25) scale(.5)`.
- **The bubble sits bottom right** so it stays clear of the red notification badge, which macOS puts top right.

**FR-R342-2 — The music icon's Icon Composer file and its exports.**
- **The source.** `ravilo-music.icon` has four layers: background (navy), jellyfish (with the cut), ring, and note.
  The films icon is R341's installed icon. It needs no exports, because films mode shows the installed icon itself
  (FR-R342-3).
- **Made by hand.** The exports are rendered on the Mac from the `.icon` with Icon Composer (or its `ictool`) and
  committed to `ravilo-desktop/icons/dock/`. CI never runs Icon Composer.
- **What is exported:**
  - **Default**, **Dark** (tile `#05070E`, colours kept), **Clear light** and **Clear dark** (every mark in one white
    tone; Clear light's marks keep the canvas's small shadow): one whole picture each.
  - **Tinted light** and **Tinted dark:** two one-colour masks, the tile and the marks. The tint is the viewer's own
    colour, so the Swift library paints the masks at run time: Tinted light fills the tile with the tint and draws
    white marks; Tinted dark fills the tile with `#0C1322` and draws the marks in the tint.
- **The margin.** Each picture carries macOS's icon-grid margin itself. The Dock draws a running app's picture exactly
  as given, so a tile drawn edge to edge would sit bigger than its neighbours (R341 open question 2).
- **Sizes.** Each picture is 512 px, plus FR-R342-1's ≤ 32 px drawing as a second representation of the same image.
  The Dock tile is at most 128 pt, which is 256 px on Retina, and ⌘-Tab is smaller.
- **Where they go.** They are packaged with the Swift library in the app's resources (`appResourcesRootDir`,
  `macos-arm64/`). When they are absent (a `gradle run`), the app sets no icon and nothing else changes.
- **What is approximate.** Clear is an approximation: a picture has no live blur of the desktop behind it. Icon
  Composer's own render is the reference for the rest.

**FR-R342-3 — The running icon follows the mode and the style.** A new Swift call, `ravilo_dock_icon`, sets
`NSApp.applicationIconImage` on the main queue:
- **In music mode:** the music picture for the current style (FR-R342-7).
- **In films mode:** `nil`. That puts back the installed films icon, which macOS styles itself.

It follows `inMusic`, what the screen shows, through one seam (`reportListeningMode`). It updates:
- at launch, from `Main.kt`'s first `LaunchedEffect`, as soon as AWT is up. Until then (about a second or two after the
  click) the Dock shows the installed films icon, which can't be avoided;
- on every switch between films and music (R337), at the same moment, with no animation;
- whenever the system's light/dark or the icon style changes. Both are read in R338's one-second tick
  (`DesktopAppearance`).

⌘-Tab follows on its own, because it shows the running app's image. On quit there is nothing to undo.

**FR-R342-4 — The mode is remembered on this Mac.** This is already built: `ListeningMode` (R321) lives in
`ravilo_music.json`.
- **Where it's stored:** a local preference, not synced to the profile, so each device opens in the mode it was left
  in.
- **Launch:** Ravilo opens straight into the stored mode. Films must not flash up first.
- **The fallback:** if the server definitely says music isn't available, Ravilo goes to films mode, **stores films**,
  and the icon follows. That covers no music library and no music access. Signing out already stores films. An
  unreachable server is not a no: Ravilo stays in the stored mode (R345).
- **One viewer per computer** (R337 Q12), so the stored mode belongs to this Mac.

**FR-R342-5 — Withdrawn (owner, 2026-10-01).** It asked for the closed Dock to show the stored mode through a Dock tile
plug-in. A plug-in stops macOS 26 styling the icon, so the closed Dock shows the installed films icon instead (Q4).

**FR-R342-6 — Withdrawn (owner, 2026-10-01).** It asked for Finder, Launchpad and Spotlight to follow the mode.
`NSWorkspace.setIcon(_:forFile:)` on the app bundle fails `codesign --verify --strict`, and every update undoes it
(§Dev review item 11).

**FR-R342-7 — Which style the music icon uses.** The Swift library reads two undocumented global defaults:
- **`AppleIconAppearanceTheme`:** `RegularDark` and `RegularAutomatic`; `ClearLight`, `ClearDark` and
  `ClearAutomatic`; `TintedLight`, `TintedDark` and `TintedAutomatic`. When the key is absent, the style is Default.
- **`AppleIconAppearanceTintColor`:** the tint, for the Tinted styles.

The rules:
- An `…Automatic` value resolves through the system's light or dark mode.
- A missing key, an unknown value or an unreadable tint falls back to Default, or to Dark when the system is dark.
  Nothing else breaks if Apple changes the keys.
- On macOS 14 and 15 icons have no styles, so it is always Default.

## Non-goals

- Linux (GNOME and KDE), Windows, the phone, the TVs and the web app: their icons stay one icon.
- A separate audiobooks icon.
- Any animation of the swap.
- Changes to the films icon: that is R341.
- The closed Dock showing the stored mode, and any Dock tile plug-in (FR-R342-5).
- Finder, Launchpad and Spotlight following the mode (FR-R342-6).
- A setting to turn the feature off (Q8).

## Acceptance

1. Switch to music: the Dock and ⌘-Tab icons gain the bubble at that moment. Switch back: it's gone. Neither switch
   depends on whether anything is playing.
2. Quit in music mode: the Dock shows the films icon, styled by macOS. Reopen: Ravilo opens in music mode, with no
   films screen first, and the Dock icon gains ♪ as soon as the app is up.
3. In music mode, open Ravilo with the server unreachable: it stays in music mode and the icon keeps ♪ (R345).
4. Remove the viewer's music access, then open Ravilo: it opens in films mode, stores films, and the icon is the films
   icon.
5. Cycle through Default, Dark, Clear and Tinted (with several tints) while in music mode: the music icon follows each
   one within about a second, with Clear as an approximation. The films icon is macOS's own in each style. On macOS
   14 and 15 the music icon is always Default.
6. With the style keys removed, or set to a value Ravilo doesn't know, the music icon shows Default, or Dark in dark
   mode.
7. After an update, `codesign --verify --deep --strict` passes on the installed app, and it opens without a new *Open
   Anyway*.

## Open questions

Answered by the dev review and the owner (2026-10-01):
1. **Can an app tell the icon style?** Not through a public API. Ravilo reads the undocumented keys (FR-R342-7).
2. **The Dock tile plug-in in the bundle:** withdrawn, there is no plug-in (FR-R342-5).
3. **FR-R342-6:** not safe, so it is dropped.
4. **Launch before the server answers:** Ravilo opens in the stored mode and steps back only on a definite no. The
   offline case is a bug of its own, R345.
5. **Export sizes:** 512 px plus the ≤ 32 px drawing (FR-R342-2).
6. **Where the stored mode lives:** `ravilo_music.json`, unchanged. With no plug-in, nothing else reads it.
7. **A setting to turn it off:** none.

Still to see on the Mac, while building:
- How a custom tint colour is stored. If it can't be read, the Tinted styles use the system's accent colour.
- Whether the Dock ever uses the ≤ 32 px representation of a running app's picture. If it doesn't, nothing breaks.

## Dev review (2026-10-01, against `main` `44e26871`)

Read against `ravilo-desktop` (the window, the Swift library in `native/`, the packaging in `build.gradle.kts`),
`ravilo-ui` (commonMain's mode state, desktopMain's stores and appearance), `.github/workflows/deploy-macos.yml` (R331),
the shipped `ravilo.icns`, and `design/ravilo/Desktop - Music App Icon.html`. Apple's own documents say little about
Dock tile plug-ins and nothing about macOS 26's icon styles, so items 6, 8, 9 and 11 also lean on what other shipping
apps found (Ghostty's plug-in on macOS 26, a 2026 crash report about another vendor's plug-in, a write-up of the Dock's
plug-in host); each says so. Nothing here was run on a Mac. The running half (FR-R342-3) is straightforward. The
remembered mode (FR-R342-4) is mostly shipped already, apart from one existing bug (item 2). The closed-Dock half
(FR-R342-5) has a cost the spec does not know about: on macOS 26 a Dock tile plug-in stops the Dock from styling the
icon at all (item 9). FR-R342-6 is not safe and is dropped (item 11). Items 6 and 9 needed the owner. They were
decided the same day (§Owner decisions, below), and the spec above was brought in line.

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

   *Spec'd on its own as **R345** (owner, 2026-10-01: fix it later).*

3. **FR-R342-4 is already built, except for "stores films".** The mode is `ListeningMode` (`MusicDeviceStore.kt:15–21`).
   On the desktop it is the key `mode` in `ravilo_music.json` (`MusicDeviceStoreDesktop.kt:7`, a `PrefsFile`), in
   `~/Library/Application Support/Ravilo/` (`DesktopPaths.kt:18`). It is per device and never synced, and it is
   written on every switch (`RaviloApp.kt:1071`, `:2068`). Sign-out writes `video` (`MusicDeviceStore.kt:42–47`,
   called from `SettingsScreen.kt:180/198`), which covers "no one is signed in". What is missing is a write on the
   definite no from item 2: `:952` resets the stack today but leaves `music` stored, so the next launch opens in
   music again and steps back again. That write is part of R345.

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
     has to be checked on the Mac. *With no plug-in (owner decision 1), the Dock shows films until the app is up;
     FR-R342-3 now says so.*
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
   - **Making `ravilo-films.icon` the installed icon is R341's work**, and R342 builds on it. *After owner decision 1,
     films mode sets the image to `nil`, so the installed icon shows with macOS's own styling, and only the music icon
     needs exports (FR-R342-2, FR-R342-3).* What R341 needs:
     - Xcode 26's `actool` compiles the `.icon` into `Assets.car`.
     - Compose's DSL can't put files into `Contents/Resources`, so copy `Assets.car` into the `.app` after
       `createDistributable` and before CI signs it.
     - Add `CFBundleIconName` through `extraKeysRawXml` (`build.gradle.kts:103–110`).
     - Keep the `.icns` for macOS 14 and 15.
     - Check which Xcode the `macos-15` runner has.
   - **The exports are made by hand on the MacBook and committed.** Icon Composer (or the `ictool` inside it) renders
     each style from the `.icon`; CI never runs Icon Composer. They go in `ravilo-desktop/icons/dock/`, one copy.
     *After owner decision 1 they are packaged with the Swift library in the app's resources (`appResourcesRootDir`,
     `macos-arm64/`), not in a plug-in.* The app does nothing when they are absent (a `gradle run`).
   - **Sizes.** The Dock tile is at most 128 pt (size plus magnification), which is 256 px on Retina; ⌘-Tab is
     smaller. Ship 512 px for headroom, plus the ≤ 32 px drawing as a second representation of the same `NSImage`. The
     Dock is handed one picture by the running app, so whether it ever uses the small one is a check on the Mac. If it
     doesn't, nothing breaks.
   - **Per icon:** Default, Dark, Clear · light and Clear · dark as whole pictures, plus the tile and marks masks for
     Tinted (item 6). That is about ten files, under 1 MB in all.

8. **Open question 6 (where the plug-in reads the stored mode): withdrawn by owner decision 1.** There is no
   plug-in, so only the app reads the stored mode, from `ravilo_music.json` as today. The mirror into the app's
   defaults domain that a plug-in would have needed is not built.

9. *Decided by owner decision 1: (b), with no spike. Kept as the record.* **The plug-in takes the styling away from the Dock — needs the owner, after a spike on the Mac.** Ghostty found
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

10. **Open question 2 (the plug-in in the bundle and in R331's pipeline): withdrawn by owner decision 1.** No
    plug-in, no Gradle task for it, no `Contents/PlugIns` copy, no `NSDockTilePlugIn` key and no CI signing step.
    `build.gradle.kts` and `deploy-macos.yml` are unchanged by this phase, apart from packaging the music exports with
    the Swift library (FR-R342-2). One correction carries over: R331 signs with the one self-signed certificate and
    never notarises (`deploy-macos.yml:6–12`). The spec no longer says otherwise.

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

13. **Acceptance, as the code can meet it.** *Superseded by the spec's Acceptance, rewritten after the owner's
    decisions.* What carried over: 6 is reworded (the app is not notarised, so it means `codesign --verify --deep
    --strict` passes and the update opens without a new *Open Anyway*), and the offline case was added, now R345's.

14. **Wire and versions: none.** This phase is client-only plus packaging: no route, no DTO, no new string. An old
    server is handled by R345's 404 rule. Build order, after the owner's decisions, each step shippable:
    - **(a)** R345, its own phase. The phone gains from it too.
    - **(b)** R341's installed films icon.
    - **(c)** The running swap: the seam, `ravilo_dock_icon`, and music in Default and Dark (items 4–5,
      FR-R342-3).
    - **(d)** Reading the style (FR-R342-7), and the Clear and Tinted pictures (items 6–7, FR-R342-2).

### Owner decisions (2026-10-01)

1. **No Dock tile plug-in, and no spike.** On macOS 26 a plug-in stops the Dock styling the icon, the installed films
   icon included (item 9). So the closed Dock always shows the installed films icon, styled by macOS. Ravilo still
   opens in the remembered mode, and the running icon still follows the mode. FR-R342-5 is withdrawn. Items 8 and 10
   are withdrawn: no plug-in store, Gradle task, `PlugIns` copy, `Info.plist` key or CI signing step.
2. **Read the undocumented icon-style keys** (`AppleIconAppearanceTheme`, `AppleIconAppearanceTintColor`). Fall back
   safely to Default, or Dark, when they are missing or change. The running music icon then matches Dark, Tinted and
   Clear. This is the review's lean (item 6), now FR-R342-7.
3. **No setting to turn it off** (open question 7, Q8). It is the review's lean and conflicts with nothing.
4. **FR-R342-6 (Finder, Launchpad, Spotlight) is dropped**, as item 11 found.

## Build notes (2026-10-01)

**Built 2026-10-01, not deployed, not released. Run on the owner's Mac (macOS 27.0.1) as a signed test build; the Dock
itself was not seen.** As the owner decided: no Dock tile plug-in, no Finder/Launchpad change, no setting.

1. **The pictures (FR-R342-1, FR-R342-2).** `scripts/render-brand-icons.sh dock` writes 14 PNGs into
   `ravilo-desktop/icons/dock/`, 336 KB in all. It draws the canvas's `app()` number for number (dev review 1), on
   R341's Mac template: Apple's margin and shadow, the jellyfish at 1.18 with the circle cut out of it, the ring and
   the ♪.
   - Whole pictures: `music-default`, `music-dark`, `music-clear-light` and `music-clear-dark`.
   - Tinted's three one-colour layers: `music-tinted-shadow`, `music-tinted-tile` and `music-tinted-marks`.
   - Each one at 512 px, plus `-32` with the bigger bubble.
   - **Deviation:** they come from the script, not from Icon Composer. No `ravilo-music.icon` exists, and nothing ran
     on Xcode. The canvas is the reference the spec names, and the script renders it exactly. If Icon Composer's look
     is wanted later, export it over these files with the same names.
2. **Packaging.** `copyDockPictures` (in `ravilo-desktop/build.gradle.kts`) copies them to
   `native-resources/macos-arm64/dock/`, beside `libravilo-mac.dylib`. So only the Mac's package carries them, and
   they end up in `Contents/app/resources/dock/` (checked: 14 files). `--self-test` now says how many Dock pictures it
   found. Missing pictures are not a failure: the Dock keeps the films icon.
3. **The Swift side, `native/Dock.swift`.** Three new exports, added without an ABI bump, like R338's call:
   - **`ravilo_dock_icon(dir)`** sets `NSApp.applicationIconImage` on the main queue. With no folder (films mode) it
     sets `nil`, which puts the installed icon back. In music mode it builds one `NSImage` with the 512 px picture and
     the 32 px drawing as two representations of the same size. Tinted is painted at run time: each layer is filled
     with its colour, then the layers are stacked. Tinted light is the tint on the tile with white marks; Tinted dark
     is `#0C1322` with the marks in the tint.
   - **`ravilo_dock_style()`** returns the resolved style as one line (for example
     `tinted-dark #30D158 (TintedDark)`). Kotlin polls it in `DesktopAppearance`'s one-second tick, beside
     `AppleInterfaceStyle`.
   - **`ravilo_dock_snapshot(path)`** is for the test driver's new `dock <png>` command. It returns what was set last,
     and writes the image the app hands the Dock.
4. **Kotlin.**
   - **The seam** `reportListeningMode(music)` in `ui/seams` does nothing on Android and the web. On the desktop it
     calls `DesktopDock.report`. `RaviloApp` calls it from `LaunchedEffect(inMusic)`: it follows `inMusic`, not
     `musicMode` (dev review 4).
   - **`DesktopDock.start()`** runs from `Main.kt`'s first `LaunchedEffect`, beside `MacAppHooks.install`. It does
     nothing off the Mac or without the library. It combines the mode with the style and re-sets the icon only when
     that pair changes. Every change is logged as `Dock icon: …` in `ravilo.log`.
5. **FR-R342-7, with one reading.** The spec says both "when the key is absent, the style is Default" and (rules and
   acceptance 6) "a missing key … falls back to Default, or to Dark when the system is dark". The build follows
   acceptance 6: no key gives Dark on a dark system. Ravilo's Default and Dark differ only in the tile (`#000B25` and
   `#05070E`), so this is nearly invisible. `RegularLight` is also read as Default, in case it appears.
   - **The tint:** a named colour (Blue, Purple, Graphite and the rest), components as text or numbers, or an archived
     `NSColor`. **With no tint key at all, the tint is the system accent colour**, as the spec's *Still to see*
     suggests. A tint key that can't be read falls back to Default or Dark (FR-R342-7).
   - **macOS 14 and 15** always get Default (`#available(macOS 26, *)`). Not tested: the Mac runs 27.
   - **Still unknown:** how macOS really stores a custom tint. The owner's Mac has no icon-style keys set. Run
     `defaults read -g | grep -i Icon` after picking a tint to see it.
6. **Verified on the Mac**, signed as "Ravilo" (designated requirement `certificate root = H"5b3eac32…"`, and
   `codesign --verify --deep --strict` passes after running). It ran on its own data folder, and the owner's
   installed Ravilo was quit first and reopened afterwards: one Ravilo at a time.
   - **The mode:** opened in the stored music mode, the music icon was set about 3 s after launch. `cmd MODE_VIDEO`
     handed back the installed icon (`films`), and `cmd MODE_MUSIC` set the music icon again. The window shots
     matched each mode.
   - **Every style**, launched once per style with the keys in the app's argument domain only (never the owner's
     settings):
     - TintedDark plus Green → `tinted-dark #30D158`;
     - TintedLight with no tint → the accent colour, `#007AFF`;
     - ClearLight → `clear-light`; ClearAutomatic on a dark system → `clear-dark`;
     - RegularAutomatic → `dark`;
     - an unknown value → `dark`;
     - an unreadable tint → `dark`;
     - a tint as `0.9 0.3 0.1` → `#E54C19`.

     The dumped Dock images look right in each style.
   - **Live:** writing the style to Ravilo's own defaults domain while it ran (TintedAutomatic plus Purple, then
     ClearLight, then removed) changed the icon within the 3 s I waited each time. The keys were deleted afterwards.
7. **Not verifiable over SSH, so still owed on the Mac:**
   - **The Dock and ⌘-Tab themselves** (acceptance 1 and 5). Screenshots over SSH don't capture the Dock. In
     particular: does the music picture sit at the same size as the films icon? macOS 27 draws the installed `.icns`
     in its own squircle (R341 build note 5). How the Dock draws a running app's image is unseen.
   - A real style change through System Settings.
   - Acceptance 3 and 4 (an unreachable server; music access removed) depend on R345, which is not built.

