# Phase R338 — Five themes, and a light one and a dark one that follow the system

> Owner, 2026-09-30: *"Let's instead of adding these as system only. Let's make them into a proper theme. And then we
> will add support for selecting two themes (inspired by JetBrains Intellij). Where the user selects which light theme
> and which dark theme to use. And then the app selects the correct theme based on the system current dark/light mode
> setting, and dynamically changes right away when the system changes."* — and: *"It should not be per device, but
> rather across devices."*

## Status

`Planned` — written 2026-09-30 (design-authored) from `design/ravilo/Desktop - D1.html` §T and the phone built in
`design/ravilo/Ravilo Mobile.html` (Settings ▸ Theme, every screen in Daylight). **Dev-reviewed 2026-09-30** (§Dev review; the wire shape changes: item 1; the owner took item 8's lean the same day — the receivers are out of scope). Number verified
free (after R337). **Builds on** R161/R162 (the viewer's settings, server-owned, the behaviour overlay), R234 (the TV's
Settings focus chain), R304 (the phone's Settings row), R337 (the desktop).

## Decisions

| # | Question | Decision |
|---|---|---|
| D1 | The list | **Aurora · Midnight · Noir · Graphite · Daylight** (owner) — Graphite dark, Daylight Ravilo's first light theme |
| D2 | The setting | **Follow the system** + **a light pick** + **a dark pick**; off ⇒ **one pick** of all five (IntelliJ's *Sync with OS*) |
| D3 | Where it is kept | **On the viewer, across devices** (owner) — like `ui_language`. Only the light/dark *state* is each device's |
| D4 | When it changes | **Live**, the moment the system changes; nothing restarts, nothing that plays stops |
| D5 | Defaults | new install: follow **on**, light **Daylight**, dark **Aurora**. Existing viewer: follow **off**, one pick = their current `skin` |
| D6 | A device with no light/dark of its own (the TV, the web app in a desktop browser; the receivers are out of scope — dev review 8) | **It is a dark-mode-only device** (owner, 2026-09-30): it always shows **the dark pick** (`theme_dark`), whether follow is on or off, and it never shows a light theme. So one synced setting still means the same thing everywhere |

## Requirements

**FR-R338-1 — Two new themes, as tokens.** Each theme is the same token set as today's three (`ravilo.css`,
`RaviloTheme.kt`) plus two it now needs: **`fg`** (the ink colour as a triple, for every translucent fill that was
hard-coded white) and **`bgRgb`** (the page colour as a triple, for scrims). Each theme is marked **light** or **dark**.

| Token | Graphite (dark) | Daylight (light) |
|---|---|---|
| bg · bg-2 | `#1c1c1f` · `#242428` | `#fbfbfd` · `#f1f2f6` |
| ink · soft · dim | `#f2f2f4` · `#b4b4bb` · `#85858d` | `#15171f` · `#4a4f63` · `#6d7286` |
| line · line-2 | white 8 % · 14 % | `#10121e` 9 % · 16 % |
| card · card-2 | `#2a2a2e` · `#323237` | `#f1f2f6` · `#e8eaf0` |
| accent · accent-2 | `#4f8ef0` · `#6fb0f5` | `#5b4ee0` · `#0f73c2` |
| grad | the accent, flat | `120° #6a5cf0 → #1f8fdc` |
| chip | white 10 % | `#10121e` 7 % |
| ring · glow | as accent | `#5b4ee0` · 30 % |
| fg | `255,255,255` | `16,18,30` |

Contrast is checked for text on bg, bg-2 and card at 4.5 : 1 (ink-dim included on Daylight: `#6d7286` on `#fbfbfd`
is 4.8 : 1).

**FR-R338-2 — No white is hard-coded in the shared UI.** Every translucent white fill, border and track in
`ravilo-ui` (and the web app's CSS) becomes `fg` at the same alpha; every scrim that fades to the page uses
`bgRgb`. **Three surfaces keep dark tokens in a light theme**, because they sit on artwork or video: the hero card /
hero backdrop's text block, the film player and its sheets, and the receiver. The mockups show the rule
(`Ravilo Mobile.html`: `html[data-appearance="light"] .hcard, .mp`).

**FR-R338-3 — The setting, stored on the viewer.** R162's behaviour overlay gains three viewer-writable fields beside
`skin`: **`theme_follow`** (bool), **`theme_light`**, **`theme_dark`** (theme ids); `skin` stays the one pick used when
follow is off. They are written with the viewer writer tag, resolved viewer → admin → global exactly as `skin` is, and
**pushed live** to the viewer's other signed-in devices (R33/R141's push). `allowSkinOverride` gates all four as it
gates `skin` today. A light id in `theme_dark` (or the reverse) is refused by the API, never stored.

**FR-R338-4 — Which theme a device shows.** `resolveTheme(settings, deviceAppearance?)`, in `shared`, tested:
a **dark-only device** (D6) ⇒ always `theme_dark`; otherwise follow on and the device dark ⇒ `theme_dark`, light ⇒
`theme_light`; follow off ⇒ `skin`. The device's appearance comes from:

| Device | Source | Live |
|---|---|---|
| Android phone | `Configuration.uiMode` night bits | `onConfigurationChanged` |
| the iPhone web app / a phone browser | `prefers-color-scheme` | `matchMedia(...).change` |
| macOS | `NSApp.effectiveAppearance` (R328's native library) | KVO on `effectiveAppearance` |
| Linux | the Settings portal: `org.freedesktop.appearance` `color-scheme` (1 dark, 2 light, 0 no preference ⇒ light) | `SettingChanged` |
| TV, web on a desktop browser | none — **dark-only** (D6) | — |

A change recomposes with the new colours in place: no restart, no navigation, no pause.

**FR-R338-5 — Settings ▸ Theme.**

- **Phone** (R304's Settings screen, and the desktop's compact layout): a switch row **Match the phone's light and
  dark** with the sub-line *Switches the moment the phone does*; on ⇒ two groups **When the phone is light** (Daylight)
  and **When the phone is dark** (Aurora · Midnight · Noir · Graphite), each a row of chips with a small two-tone
  swatch, the group in use marked; off ⇒ one row of all five. The foot line: *Saved to your profile: every TV, phone
  and computer you sign in on uses these.*
- **macOS** (Settings ⌘, ▸ General): a checkbox *Match System Settings ▸ Appearance*, then a **Light** row and a
  **Dark** row of swatch cards, *in use now* under the row's label; off ⇒ one row of five. The same foot line.
- **GNOME** (Preferences ▸ Appearance, an `AdwPreferencesDialog`): a switch row *Follow the system style*, then two
  combo rows **Light style** / **Dark style** (the one in use says *In use now*); off ⇒ one combo row **Theme**.
- **TV and every other dark-only device** (R234's Settings): **one row of the four dark themes**, titled *Theme*, with the
  sub-line *This TV is always dark* (`theme.dark_only`), in the explicit D-pad chain. No follow switch and no light row —
  the device has no light to follow. A pick writes **`theme_dark`**, and also `skin` when the viewer's follow is off and
  `skin` is a dark theme, so a pick on the TV is what the viewer sees on every dark screen. Graphite joins the list;
  Daylight is never offered here. The synced foot line (`theme.synced`) is shown as on the phone.
- **The admin** (`app/ravilo-config.html` ▸ Behaviour): the default-skin choice gains Graphite and Daylight, and three
  rows for the new fields' global defaults (D5), each with R162's per-field state chips.

**FR-R338-6 — Migration.** On upgrade, a viewer with an overlay `skin` gets `theme_follow = false` (so nobody's Ravilo
turns white overnight) and `theme_light = daylight`, `theme_dark = skin` when `skin` is dark; a viewer with none follows
the new global defaults (D5).

**FR-R338-7 — Strings** × en · da · fo (da/fo drafts; the shipped table wins): `theme.graphite` *Graphite* ·
`theme.daylight` *Daylight* · `theme.follow` *Match the phone's light and dark* · `theme.follow_desk` *Match the system
appearance* · `theme.follow_sub` *Switches the moment the phone does* · `theme.when_light` *When the phone is light* ·
`theme.when_dark` *When the phone is dark* · `theme.light` *Light* · `theme.dark` *Dark* · `theme.in_use` *In use now*
· `theme.synced` *Saved to your profile: every TV, phone and computer you sign in on uses these.* · `theme.dark_only`
*This TV is always dark* (da *Dette tv er altid mørkt*, fo *Hetta sjónvarpið er altíð myrkt*).

## Out of scope

An accent colour taken from the system (macOS's accent, GNOME 47's accent portal, Material You) — the themes carry
their own · a per-device override of the synced setting · more light themes (the list is built to grow) · scheduling
(the system already has one) · **the receivers** (`ravilo-cast`, `ravilo-screen`), which keep their fixed look (owner,
2026-09-30 — dev review 8).

## Acceptance

1. A new phone follows: the phone in dark shows Aurora, flipping the system to light repaints in Daylight at once,
   with a film's audio or a song still going.
2. Pick Midnight as the dark theme on the phone → the Mac (dark) and the TV change to Midnight within a push.
3. On the Mac with Auto appearance, at the switch the window repaints; Settings' *in use now* moves rows.
4. On GNOME, Quick Settings ▸ Dark Style flips the Flatpak live.
5. Follow off with Daylight as the one pick on the phone → the TV shows the viewer's dark pick (Aurora by default);
   picking Noir on the TV → the phone and the Mac show Noir whenever they are dark; the admin editor shows *Set by viewer*.
6. No white or near-white translucent fill remains on any screen in Daylight (the design's screens in
   `Ravilo Mobile.html?skin=daylight` are the reference); the hero card and the film player stay dark.

## Open questions

1. ~~Daylight on a TV~~ — **answered 2026-09-30:** a device without light/dark is dark-only and shows the dark pick (D6).
2. The TV's `ravilo.css` / `RaviloTheme.kt` tokenisation (FR-R338-2) is the largest part of the work; the design has
   done the phone's stylesheets only.

## Dev review (2026-09-30, against `main` `c4258560`)

Read against `shared`'s models, the server's `RaviloConfigService`, `ravilo-ui`'s theme and Settings, the Android
manifest, `ravilo-desktop` and the Mac's Swift library. The design holds; the way it reaches the wire does not. Twelve
items. One is for the owner (item 8, lean given).

1. **No new `Skin` values on the wire.** Every installed app back to the wire floor (v1.23) decodes
   `Skin { AURORA, MIDNIGHT, NOIR }` strictly. R318's `coerceInputValues` protects only v1.41 and later, and
   `WireCompatTest` fails on a new value in `default_skin`, `viewer_skin_override` or `BehaviourOverlay.skin`. So the
   themes travel as new **string** fields that carry theme ids (`aurora · midnight · noir · graphite · daylight`; the list
   lives in `shared` as `RaviloThemes`, each with `isLight`):
   - the one pick: `theme`;
   - follow the system: `theme_follow`;
   - the pair: `theme_light` and `theme_dark`.

   They go on `BehaviourOverlay` (with `*_writer` tags), on `ViewerSettingsRequest`, and on `RaviloConfig`, resolved
   for the viewer on every read path as `viewerSkinOverride` already is. The global defaults are `default_theme`,
   `default_theme_follow`, `default_theme_light` and `default_theme_dark`.

   `skin` and `default_skin` stay, as a **mirror for older apps** that the server writes and a new app never sends:
   - the pick itself when it is one of the three;
   - Graphite → Midnight;
   - Daylight → the mirror of the dark pick.

   A new app that meets an id it does not know uses `skin`. A new app on an old server finds no `theme_follow` and shows
   today's three-skin picker. So FR-R338-3's "`skin` stays the one pick" is `theme` here, and `skin` becomes the mirror.
2. **Inside the app, a theme id replaces `Skin`.** `Colors.kt` and `RaviloTheme.kt` are keyed on `Skin`
   (`LocalRaviloSkin`, and 13 `Skin.X` branches in 10 files, mostly Noir's "no tint" rules). `ravilo-ui` gains
   `ThemeId` with `isLight`:
   - Noir stays the only no-tint theme;
   - Graphite takes Midnight's branches;
   - Daylight takes Aurora's.

   In Compose, FR-R338-1's `fg` and `bgRgb` are two more `Color`s on `RaviloColors` (the ink as a base for alpha
   fills, and the page colour for scrims). The number triples are the CSS form only.
3. **The hard-coded white, measured (FR-R338-2).** `ravilo-ui/commonMain` has 211 `Color.White` in 28 files. The film
   player has 107 of them (`PlayerScreen.kt` 73, `PlayerHandsetChrome.kt` 34), and those stay, as the spec exempts the
   player.
   **Not exempt:** the music Playing page and the book player (`MusicPlayerScreens` 5, `BookPlayerScreens` 22,
   `MusicCommon` 13). Their ground is built from the theme (`playingGround()`, `MusicPlayerScreens.kt:204`), so in
   Daylight they would be white on white.
   The rule for the build:
   - white **over artwork or video** stays: tile captions on posters (`Tile.kt`), the hero, the players, the cast
     remote's backdrop;
   - white **over a theme surface** becomes `colors.fg`.

   A grep fence in `scripts/` (any new `Color.White` outside an allowlist fails) keeps it that way.
4. **The TV's share of the work is small (open question 2 closes).** The TV app *is* `ravilo-ui`, so its "tokenisation"
   is the same Compose theme. The TV is dark-only (D6), and Graphite is dark, so the TV needs no white removed at all;
   it only needs the fifth token set and the fourth pill. `ravilo.css` is the mockup's stylesheet, a design task, not a
   product one. The only light screens in the product are the phone's and the desktop's.
5. **Where each device reads its appearance — corrections to FR-R338-4.**
   - **Android:** use `isSystemInDarkTheme()`. Both activities already list `uiMode` in `configChanges`
     (`AndroidManifest.xml:67/80`), so a flip recomposes the screen without recreating the activity, and nothing that
     plays stops. The status-bar and navigation-bar icons follow the **resolved** theme
     (`WindowInsetsControllerCompat.isAppearanceLightStatusBars`), not the system.
   - **Web (a phone browser, the iPhone app):** `matchMedia('(prefers-color-scheme: dark)')` and its `change` event;
     `<meta name="theme-color">` follows the theme.
   - **macOS: not `NSApp.effectiveAppearance`.** `Main.kt:66` pins the app to `NSAppearanceNameDarkAqua` (R328), so
     the app's effective appearance always says dark. Read the system's own instead:
     - a new C function in the Swift library, `ravilo_appearance_dark()`, which returns whether
       `UserDefaults.standard.string(forKey: "AppleInterfaceStyle") == "Dark"`;
     - a callback on `DistributedNotificationCenter`'s `AppleInterfaceThemeChangedNotification`;
     - an ABI bump.

     Then give each window its own appearance from the **resolved** theme
     (`rootPane.putClientProperty("apple.awt.windowAppearance", "NSAppearanceNameAqua" | "NSAppearanceNameDarkAqua")`),
     so the traffic lights, menus and the About window match Daylight.
   - **Linux:** the Settings portal's `Read("org.freedesktop.appearance", "color-scheme")` and its `SettingChanged`
     signal, over the one D-Bus client (R337 dev review 12). Portals need no finish-arg. `0` (no preference) reads as
     light, as the spec says (GNOME's *Default*). With no portal at all (a bare X session), the desktop is dark.
   - **Every platform reads its appearance before the first frame.** Android has it synchronously. Linux makes one
     blocking portal read, capped at 200 ms, before `application {}`. The Mac makes one call. R212's Home snapshot also
     stores the theme ids beside `skin`, so a cold start does not flash the wrong theme (the flash R212 fixed).
6. **Dark-only (D6) is the TV layout family (R337).** `resolveTheme(settings, appearance)` lives in `shared`, with
   `appearance == null` meaning "this device has no light or dark". It is `null` exactly where R337's family is `TV`:
   the TVs, and the web app when it is not a phone. One predicate, tested.
7. **The migration: the trap is the global default (FR-R338-6 / D5).** On an existing server most viewers have no
   `skin` of their own; they follow the global default. "A viewer with none follows the new global defaults" (D5:
   follow on) would therefore turn their phones white the next time the system went light, which is the thing the
   migration exists to prevent. So:
   - **The migration writes the global `default_theme_follow = false` on a server that already has a stored global
     config.** Only a fresh install starts at `true`.
   - No per-viewer rows are written. R162's rule "equal to the global ⇒ follow the global" would clear a per-viewer
     `false` anyway.
   - `default_theme` = today's `default_skin`; `default_theme_light` = `daylight`; `default_theme_dark` = today's
     `default_skin`.

   (The CLAUDE.md note "the one pick = the first device opened after the update" is not in the spec and is not needed.)
8. **The receivers — decided (owner, 2026-09-30): out of scope.** Neither `ravilo-cast` nor `ravilo-screen` reads a skin
   today; both draw a fixed Aurora look. "The receiver shows the dark pick" (D6's list) would need the theme in the
   cast load payload and a second token set in two Kotlin/JS apps. The receivers keep their look; D6's list,
   FR-R338-4's table and §Out of scope say so.
9. **The TV's Settings.** `SettingsScreen.kt:412–459` walks `Skin.entries`. It walks the four dark `ThemeId`s instead:
   Graphite is one more pill in a chain that is already list-based. The write follows D6: `PUT /tv/settings` with
   `theme_dark`, plus `theme` when follow is off and the current pick is dark.
10. **The admin editor** (`src/wasmJsMain/…/RaviloConfig.kt:2848`, `#beh-skin`): a default-theme select over the five
    ids, and three rows with R162's state chips. The server gains `setAdminTheme*`, shaped like `setAdminSkin`,
    including its refusal to overwrite a viewer's entry.
11. **Strings.** The keys match the table's dotted naming. Two gaps:
    - In the desktop's compact layout (the phone's Settings on a computer), the phone wording is false. Use
      `theme.follow_desk` there, and `theme.light` / `theme.dark` as the group labels; all three are already in the
      spec's list.
    - `theme.dark_only` (*This TV is always dark*) also shows in the web app in a desktop browser, which is not a TV.
      Add `theme.dark_only_web`, *Always dark here*.
12. **Wire, installed versions, build order.** The new fields are additive strings and booleans. With the mirror
    (item 1), every app from v1.23 on keeps a sane skin, and `WireCompatTest` has to pass. `PUT /tv/settings` refuses a
    light id in `theme_dark` (and the reverse) with a 400 that names the field. The push already exists:
    `saveBehaviourOverlay` → `notifyConfigChanged` (`RaviloConfigService.kt:218`). Build order:
    (a) the server: fields, resolution, the mirror, the migration;
    (b) `resolveTheme` in `shared`, with tests;
    (c) Graphite and Daylight token sets and the white sweep;
    (d) the Android and web phone;
    (e) the TV's Settings;
    (f) the desktop, after R337.
