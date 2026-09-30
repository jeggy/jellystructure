# Phase R338 — Five themes, and a light one and a dark one that follow the system

> Owner, 2026-09-30: *"Let's instead of adding these as system only. Let's make them into a proper theme. And then we
> will add support for selecting two themes (inspired by JetBrains Intellij). Where the user selects which light theme
> and which dark theme to use. And then the app selects the correct theme based on the system current dark/light mode
> setting, and dynamically changes right away when the system changes."* — and: *"It should not be per device, but
> rather across devices."*

## Status

`Planned` — written 2026-09-30 (design-authored) from `design/ravilo/Desktop - D1.html` §T and the phone built in
`design/ravilo/Ravilo Mobile.html` (Settings ▸ Theme, every screen in Daylight). **Not dev-reviewed.** Number verified
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
| D6 | A device with no light/dark of its own (the TV, the receiver, the web app in a desktop browser) | **It is a dark-mode-only device** (owner, 2026-09-30): it always shows **the dark pick** (`theme_dark`), whether follow is on or off, and it never shows a light theme. So one synced setting still means the same thing everywhere |

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
`ravilo-ui` (and the web and receiver CSS) becomes `fg` at the same alpha; every scrim that fades to the page uses
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
| TV, receiver, web on a desktop browser | none — **dark-only** (D6) | — |

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
(the system already has one).

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
