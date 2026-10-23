# The Mac's Dock menu plays, pauses and skips

Task: DEB-2249

> Owner, 2026-10-23 (DEB-2249), with two screenshots of the Dock's right-click menu: Ravilo's, which offers only
> *Options*, *Show All Windows*, *Hide* and *Quit*, and Spotify's, which starts with *Play*, *Next* and *Previous*:
> *"Let's add pause/play and next/previous buttons to these menu items in macosx like how spotify has it"*

## What the Mac does today

Right-clicking Ravilo's Dock icon shows only the items macOS adds to every app. Ravilo adds nothing.

The playback controls this menu needs already exist one level down. R329's FR-R329-10 gave the Mac a Now Playing card
and the media keys. `MacNowPlaying` (`ravilo-ui/src/desktopMain/.../desktop/MacNowPlaying.kt`) gives that card to one
owner at a time:

- a film (`RaviloPlayerDesktop.kt`), as `Mode.FILM`;
- the music engine's song (`Mode.MUSIC`) or book (`Mode.BOOK`) (`MusicEngineDesktop.kt`);
- a speaker or TV the Mac is casting to (`CastSenderDesktop.kt`, R330 FR-R330-7).

Each owner answers the same `Command`s: play, pause, toggle, next, previous, skip ±30 s. Each owner also reports
whether it is playing through `MacNowPlaying.update(…, playing, …)`. `NowPlaying.swift` turns on next/previous for
music only and ±30 s for a book only.

So the Dock menu needs no new playback logic. It is a third way to send the same commands, next to Control Center and
the keyboard's media keys.

**Mechanism.** AWT's `java.awt.Taskbar.setMenu(PopupMenu)` is supported on macOS (`Taskbar.Feature.MENU`). The JDK's
own application delegate returns that menu from `applicationDockMenu:`. macOS then draws our items above its own,
which is how Spotify's menu is built. No Swift export is needed and `ravilo_abi` does not change. Ravilo must **not**
replace `NSApp.delegate` from Swift for this. AWT owns that delegate, and the About/Settings/Quit hooks in
`MacAppHooks.kt` depend on it.

## Requirements

**FR-1 — The Dock menu carries playback controls while Ravilo holds the Now Playing card.** While
`MacNowPlaying` has an owner, the Dock icon's right-click menu starts with Ravilo's playback items, followed by a
separator. macOS's own items (*Options*, *Show All Windows*, *Hide*, *Quit*) stay below, unchanged. When there is no
owner (nothing playing or paused in Ravilo), the menu is exactly today's: no playback items, no separator.

**FR-2 — Which items, per kind.** The items follow the media keys' mode, so the Dock never offers what the keys
refuse:

| Owner's mode | Items, top to bottom |
|---|---|
| Music (a song, local or cast) | *Play* or *Pause* · *Next* · *Previous* |
| Book | *Play* or *Pause* · *Forward 30 s* · *Back 30 s* |
| Film (local or cast) | *Play* or *Pause* |

The order follows Spotify's: the play/pause item first, then *Next*, then *Previous*.

**FR-3 — One play/pause item that names what it will do.** While the owner is playing, the first item reads *Pause*;
while it is paused, it reads *Play*. This follows the `playing` flag the owner last passed to `MacNowPlaying.update`.
A click sends `Command.TOGGLE`. The label is correct each time the menu opens. It is never one state behind after a
key press, a click in the window, or a remote pausing a cast.

**FR-4 — A click does exactly what the media key does.** *Next* sends `Command.NEXT`, *Previous* sends
`Command.PREVIOUS`, *Forward 30 s* sends `Command.SKIP_FORWARD` with 30 and *Back 30 s* sends `Command.SKIP_BACK`
with 30. Each goes to the current owner through the same path as the media-key callback (posted to the Swing/Compose
thread). The owner's existing handlers decide what happens. For example, *Previous* early in a song restarts it or
steps back by the music engine's existing rule. The Dock adds no rule of its own. If the owner has changed since the
menu was opened, the click goes to the new owner, or does nothing when there is none.

**FR-5 — The window stays where it is.** Clicking a playback item does not bring Ravilo forward, show a hidden
window or change the screen, just as Spotify's menu does not. (Clicking the Dock icon itself still reopens the window,
as `MacAppHooks`'s reopen handler does today.)

**FR-6 — The labels are in the string table, in every language.** The labels are new keys in `i18n/*.json` (R279)
under `mac.dock.`: `play`, `pause`, `next`, `previous`, `forward_30`, `back_30`. They are written in English, Danish
and Faroese, and follow the interface language the viewer has picked. When the language changes, the menu changes
the next time it opens.

**FR-7 — Off the Mac, and without support, nothing changes.** On Linux, `Taskbar.Feature.MENU` is unsupported, and
GNOME's dock has no app menu to add to. Nothing is installed there, and nothing else changes. On a Mac where
`Taskbar.isSupported(MENU)` is false, the same applies, and the app logs one line saying so.

**FR-8 — The test driver can read the menu.** `TestDriver.kt` gains a `dockmenu` command. It prints the playback
items currently installed, with their labels and enabled state (or `none`), and `dockmenu <n>` clicks item *n*. This
lets QA and the self-test check FR-1–FR-4 without a mouse in the Dock, the same way the `dock <png>` command serves
R342.

## Out of scope

- Spotify's *Shuffle*, *Repeat* and *Recently Played* items. The owner asked for play/pause and next/previous. Any of
  those would be a new ask on its own task.
- The track's title as a header in the menu (Apple Music shows one; Spotify does not).
- The Linux tray or MPRIS. That is a separate surface, and nothing in it changes here.
- Any wire or backend change. There is none.

## Verification

On a Mac, in a build with the Swift library:

1. With nothing played since launch, right-click the Dock icon. The menu is today's, with no playback items (FR-1).
2. Play an album. The menu shows *Pause · Next · Previous* above a separator (FR-2). *Next* moves to the next song and
   the window does not come forward (FR-4, FR-5). Pause with the space bar, then reopen the menu: the first item now
   reads *Play* (FR-3).
3. Play a book. The menu shows *Play/Pause · Forward 30 s · Back 30 s*, and each skips by 30 s (FR-2, FR-4).
4. Play a film. The menu shows only *Pause* (FR-2).
5. Cast a song to a speaker. The menu drives the speaker (FR-2, FR-4). Pause it from another device, then reopen the
   menu: it reads *Play* (FR-3).
6. Stop all playback until the Now Playing card clears. The playback items are gone (FR-1).
7. Switch the interface to Faroese. The labels follow (FR-6).
8. On Linux the app starts and plays as before (FR-7).
