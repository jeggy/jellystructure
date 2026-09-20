# Phase R274 — The bottom bar sits on the phone, not on the keyboard

> Two owner reports against the shipped R267 bar, from one screenshot of Search with the keyboard up:
> the bar has **too little room under its labels**, and when the keyboard opens the bar **rides up on
> top of it** instead of the keyboard covering it. They look like two complaints and they are — but
> the second one is also what makes the first one look as bad as it does, because riding the keyboard
> is what takes the bar's own bottom inset away.

## Status

`⚠ Partial` — design-authored 2026-09-20 from an owner report on a Pixel 9 Pro (Android 17, debug
build), **built and verified on that Pixel the same day**. Not dev-reviewed.

### Build (2026-09-20)

- **FR-R274-5** — `Modifier.safeAreaPadding(includeIme, plusBottom)`, defaults `true` / `0.dp`, so
  every existing call site is unchanged in meaning. Android actual sums before it unions; the wasm
  actual takes the same signature with `includeIme` inert.
- **FR-R274-1** — `RaviloDimens.bottomNavHeight` 56 → 68 → **74 dp**, now the whole bar: the hairline
  is subtracted inside `RaviloBottomNav` rather than sitting on top of the token, and the cell pads
  10 dp above (the same constant the pill is offset by, so the two cannot drift) and **8 dp below**.
  The second step is the owner's, after seeing 68 dp on the phone: pill 32 → 36 dp, the glyphs became
  the design's stroked paths in a **28 dp** box equalised to one ink height, and the avatar takes the
  same box. The label is unchanged throughout.
- **FR-R274-2/-3** — the bar's host takes `includeIme = false`; the per-destination content takes
  `plusBottom = navBarInset` instead of a trailing `.padding(bottom = …)`.
- **FR-R274-4** — `CastMiniBar`'s own `windowInsetsPadding(WindowInsets.safeDrawing)` becomes
  `safeAreaPadding(includeIme = false)`.

### Verified on the Pixel 9 Pro, 2026-09-20 (debug)

Acceptance 1, 2, 3 and 4 pass. Search with the keyboard up: **the bar is behind the keyboard and does
not move**, and the suggestions grid runs to the keyboard's top edge with no dead band. Keyboard
dismissed: the bar returns with visible space under its labels, pill on Search. Home → Library →
Search → Discover → back: identical geometry on every page. Re-checked at 74 dp after the size
amendment, and again after the glyph's second step: bigger glyphs and avatar, the same spacing under
the labels, nothing clipped. Measured off the screenshots — the bar's top edge moved 13 px (5.8 dp at
2.25×) against the 6 dp intended, and the gesture inset beneath it is unchanged at ~23 dp. A title opened from Library and Back:
the bar returns in the same place (acceptance 5).

⚠ **FR-R274-4 is the one thing not device-verified** — no cast was running, so the mini bar was never
on screen. It is a one-line inset change of the same shape as the bar's, but it has not been seen.

## Context — what is actually happening

`ravilo-android` is `targetSdk = 36` and the Pixel 9 Pro runs SDK 37. **Android 15+ enforces
edge-to-edge for anything targeting 35 or above, and 36 removed the opt-out attribute**, so the phone
activity's own comment — *"everywhere else now just uses Android's own default window fitting"*
(`phone/MainActivity.kt:20-26`) — is stale. It does not fit the decor; the platform does not let it.
Compose's `WindowInsets` are live and real on this phone, which is exactly why R261's
`safeAreaPadding()` seam has anything to apply.

That seam unions **`systemBarsIgnoringVisibility ∪ displayCutout ∪ ime`** and is applied at three
places in `RaviloApp.kt`: the per-destination content (line 793), the bottom-nav host (1427) and the
profile menu (1475). Unioning the IME is right for content — a text field must not end up under the
keyboard — and wrong for the nav bar, which is a fixed piece of window furniture:

- **Riding the keyboard (FR-R274-2).** The bar's host pads its bottom by the IME inset, so the bar
  is lifted to sit exactly on the keyboard's top edge. A platform bottom bar does not do this; the
  keyboard is drawn over it, and the bar comes back when the keyboard goes away.
- **Losing its inset with it (FR-R274-1, half of it).** `union` takes the larger value per side, and
  the keyboard is far taller than the gesture bar — so while the keyboard is up the bar's
  navigation-bar inset is **subsumed**, not added. The bar's 11.5 sp labels end up flush against the
  keyboard, which is the tightness in the screenshot.
- **And the labels are flush anyway (FR-R274-1, the other half).** `RaviloDimens.bottomNavHeight` is
  `56.dp` while the cell it contains measures `8 (top) + 32 (pill) + 2 (spacer) + ~15 (label) = 57`.
  The label is not merely tight, it is **one dp past the bar's own height**. The mockup
  (`design/ravilo/Ravilo Mobile.html`, `.bnav`/`.bn`) is a 1 px hairline + 9 px top padding + a
  56 px item that **centres** its content — a 66 px bar with slack at both ends. The built bar took
  the top padding and dropped the bottom.

## Functional requirements

### FR-R274-1 — the bar is as tall as its own contents, with room under the label

`RaviloDimens.bottomNavHeight` becomes the **whole bar** (hairline included) and is large enough that
the label has real space beneath it, following the mockup's geometry rather than inventing one: a
1 dp hairline, 9 dp above the pill, and ~8 dp under the label.

**Amended on the device the same day, at the owner's ask:** the bar is **74 dp**, carrying a 36 dp
pill, a **26 sp** glyph and a **32 dp** avatar — the mockup was drawn in a 393 px frame and its icons
read small on the real phone. The glyph's last step (22 → 26 sp) needed no further height: its line
box is ~31 dp and the pill that holds it is 36.

**One font size is not one optical size, so the page marks stopped being text.** R267 drew them as
Unicode characters (`⌂ ▤ ⌕ ✧`) to avoid an asset pipeline. Measured on the Pixel at a shared 26 sp
the ink came out `✧` 20.0 dp, `▤` 15.6, `⌕` **14.2** — the magnifier at 71 % of the star, which is
exactly what it looked like. Per-glyph sizes could level that on *this* phone, but the ratios belong
to the system font, so any device substituting another brings the unevenness straight back.

They are now **the design's own icons**, drawn as paths: `design/ravilo/Ravilo Mobile.html`'s `.bn svg`
set, `viewBox="0 0 24 24"`, `stroke-width: 2`, round caps and joins. No file and no loader, so this is
not an asset pipeline either, and `tint` still follows the skin. It also puts **Discover** back to the
compass the design draws — `✧` had quietly become a sparkle — and Library to its book.

Each icon is then **scaled about its own centre to one ink height**, because the design's paths are
not equally tall on the grid (19.6 / 20.9 / 21.8 / 23.6 dp as drawn — ordinary optical sizing, where a
circle is drawn a little larger to *look* equal). The owner asked for equal-by-measurement, and the
four now render at **20.9 dp of ink on one centre line, to the pixel**. The stroke is deliberately
left out of that scale: scaling it too would make the compass's outline visibly thinner than the
house's, which is the unevenness being fixed. The avatar takes the same 28 dp box, so all five items
in the row share one height — the relationship the mockup already holds (`.bn svg` 23 px beside
`.avatar.sm` 24 px), stated here as one constant so they cannot drift apart again.

The pill's vertical offset and the cell's top padding remain the same value for the same reason.

The pill's vertical offset and the cell's top padding are **the same value**, because they are the
same edge: the pill is drawn in the parent `Box` and the glyph in the child cell, and the two only
line up while those two numbers agree.

### FR-R274-2 — the keyboard covers the bar; the bar does not climb the keyboard

The bottom-nav host is padded by the safe-area insets **excluding the IME**. The bar stays where it
lives — one navigation-bar inset above the window's bottom edge — and the keyboard is simply drawn
over it. Going to Search and typing must not move the bar by a pixel; dismissing the keyboard must
not move it back.

### FR-R274-3 — content clears whichever is taller, the keyboard or the bar

Content's bottom inset is **`max(ime, systemBars + barHeight)`**, not a sum of all three and not the
IME alone:

- keyboard down ⇒ `systemBars + barHeight`, which is what R267 FR-R267-12 already required (the bar
  occupies the band *above* the gesture inset, so content must clear both);
- keyboard up ⇒ the IME, because the bar is behind the keyboard and a page that also reserved 68 dp
  for it would leave a dead band above the keyboard — a new defect in place of the fixed one.

This is the one place the two rules meet, so it is expressed once, in the seam, and not re-derived at
a call site.

### FR-R274-4 — the mini bar keeps docking against the bar, keyboard or no keyboard

R267 FR-R267-8 says the cast mini bar and the nav bar move as one block. Its *height* offset is
already read from the token, and its own bottom inset comes from `CastMiniBar`'s internal
`windowInsetsPadding(WindowInsets.safeDrawing)` — so the two agree today only because both track the
IME. Once the bar stops doing that (FR-R274-2) the mini bar would be the only thing left climbing the
keyboard, floating 68 dp above the keys with nothing under it.

The mini bar takes the same rule: the seam, with the IME excluded. That also drops its last use of
plain `safeDrawing`, which tracks live bar *visibility* — the thing R261 FR-R261-5 replaced
everywhere else for producing a layout jump mid-animation.

### FR-R274-5 — one seam, one formula

`Modifier.safeAreaPadding()` gains two parameters — whether the IME participates, and a bottom
addition applied **before** the IME is unioned in — and keeps its defaults, so every existing call
site is unchanged in meaning. The three geometry rules above are then one expression in one place,
per R267 FR-R267-12's reason for existing: a phone value wrong by one bar is how a heading ends up
underneath one, and this project has now paid for it three times.

The wasm actual takes the same signature. It has no IME source of its own (the seam reads
`env(safe-area-inset-*)` through a DOM probe), so the flag is inert there and the bottom addition is
the only part with an effect — which is correct for the web app, where the bar is not drawn at all
unless the window is a handset.

## Non-goals

- The other three R267 refinements still open (the profile menu's anchoring, Search having no top
  row, FR-R267-9's scroll-to-top). This phase is the bar's own geometry.
- Any change to the TV or to the web app's wide layout: `bottomNavHeight` is read only where
  `handset && bottomItemOf(dest) != null`.
- Revisiting `phone/MainActivity.kt`'s window setup. Its comment is stale and is corrected in place,
  but the behaviour it describes is the platform's now, not the app's, and nothing should be added
  back to fight it.

## Acceptance

On a Pixel 9 (debug build), navigating **to and away from** the feature, not just at it:

1. Home → Search: the bar does not move when the field takes focus and the keyboard opens, and does
   not move when it closes. The keyboard is drawn over the bar.
2. With the keyboard up, the suggestions/results list ends at the keyboard's top edge — no dead band.
3. With the keyboard down, on all four pages, no content is clipped by the bar and the labels have
   visible space beneath them.
4. Search → Home → Library → Discover → back to Search: the pill lands on the right item each time
   and the bar's geometry is identical on every page.
5. Opening a title from Search (a pushed screen, no bar) and coming back: the bar returns in the same
   place, and the keyboard state is not carried into the detail screen.
