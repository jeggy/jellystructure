# Phase R342 — The Mac app icon shows music mode

> Owner, 2026-10-01: *"On desktop, I would like to be able to change the app icon dynamically … when on mobile and in
> the Music mode, there is an additional music tone icon next to the Ravilo logo … I would like to get this music icon
> as well to be part of the Ravilo App Icon and then we will dynamically on macos … have it switch to this when in
> music mode."* Picked: **M7b**. Q2–Q7 below.

## Status

`Planned`. Written 2026-10-01 (design-authored) from `design/ravilo/Desktop - Music App Icon.html`. Not dev-reviewed.
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
