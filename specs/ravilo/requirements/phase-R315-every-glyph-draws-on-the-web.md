# Phase R315 — Every glyph draws on the web

> Owner, 2026-09-26: *"The glyph icons in Ravilo web are not working and are just shown with some weird and
> super ugly icons, often with question marks in them."*

## Status

`Planned` — written 2026-09-26, **dev-reviewed 2026-09-26** against `main` `0e5e434f` (see *Dev review*
at the end). Client (`ravilo-ui`, every platform for the drawn icons; the web for the fallback font)
plus one check script. **Numbering:** verified against `STATUS.md` the same day — Ravilo taken through
**R314**.

## Why it happens, measured

Ravilo draws its UI glyphs as **text characters**: `✓` for watched, `✕` to close, `★` on the IMDb chip,
`︿`/`﹀` on dropdowns, `▲`/`▼` for sort direction, `⚙`, `✎`, `✉`, `🌐`, `⛶`, `◂ ▸`, and so on. On Android
that works because a character missing from the app's font is drawn from the phone's own system fonts.
**Compose on the web has no system fonts.** Skia draws text from the fonts the app bundles and nothing
else, and a character none of them contains becomes the replacement box: the "question marks" the owner
sees.

The app bundles two fonts, `sora.ttf` and `space_grotesk.ttf` (`composeResources/font/`), and registers
no fallback on the web (no `preload` of any font in `ravilo-ui/src/wasmJsMain` or `ravilo-web`). Their
coverage, read from their `cmap` tables on 2026-09-26, against every non-Latin character in
`ravilo-ui/src/commonMain` string literals and in `i18n/*.json`:

| | Characters the app draws | Sora has | Space Grotesk has | Neither has |
|---|---|---|---|---|
| UI glyphs in Kotlin literals | 25 | `› ‹ − ≤` | those + `→ ↔ ↓` | **18**, e.g. `✓ ✕ ★ ▲ ▼ ▷ ︿ ﹀ ⚙ ✎ ✉ 🌐 ⇧ ⛶ ◂ ▸ ▦ ○` |
| symbols inside translated strings | 12 | `•` | `→ ← ↑ •` | **8**: `▾ ▴ ＋ ◂ ▸ ↵ ✓ ⛶` |
| native language names (`LanguageIdentity.kt`) | 248 names | — | — | **35 names**: `Русский 日本語 한국어 中文 العربية हिन्दी Ελληνικά עברית ไทย` and 26 more |

A character one family has does not help text set in the other: Compose falls back only to registered
fallback families, so an arrow in Sora-set text is a box even though Space Grotesk has one.

So on the web the watched tick, every close button, the IMDb star, the dropdown chevrons, the sort arrows,
the settings gear, the *See all* arrow's neighbours, *＋ My List*, *▾ More*, the fullscreen button's
label, and the audio & subtitles picker's name for any Russian, Japanese, Korean, Chinese, Arabic, Hindi,
Greek or Hebrew track are all drawn as boxes. Library titles are not affected today: none of the 550
production titles contains a character Sora lacks.

## Requirements

**FR-R315-1 — A UI icon is drawn, not typed.** Every glyph used *as an icon* (on its own, not inside a
sentence) becomes a drawn glyph: a small `@Composable` in one shared file (for example
`components/Glyphs.kt`), drawn on a `Canvas` with strokes and paths. That is how `PlayPauseGlyph`,
`SkipGlyph`, `CastMarkGlyph` and the bottom bar's glyphs are already drawn. The set follows the table
above:

- check
- close
- star
- chevron up / down / left / right, which replaces `︿ ﹀ ◂ ▸ ‹ ›` used as icons
- sort up / down
- play outline
- gear
- pencil
- envelope
- globe
- share (iOS)
- fullscreen
- grid
- ring

Each glyph takes a tint and a size and sits on the text baseline where it stood before. This changes
Android too, on purpose: the same icon on every platform, instead of whatever each phone maker's
fallback font draws.

**FR-R315-2 — Text keeps its symbols, and the web can draw them.** Symbols that live inside translated
sentences stay text, because translators write them (`browse.see_all` *"See all →"*,
`player.rail_hint` *"← ↑ ↵"*, `detail.synopsis_more` *"▾"*, `profile.mylist_empty` *"＋"*,
`browse.maturity.hint` *"◂ ▸"*, `acq.in_library` *"✓"*, `web.fullscreen` *"⛶"*). For them, and for
FR-R315-3, the web registers **one bundled fallback font** as a fallback family through Compose's font
resolver (`LocalFontFamilyResolver.current.preload(...)`, the mechanism Compose documents for emoji on the
web) before the first frame, so a character Sora and Space Grotesk lack resolves from it. The fallback is
a **subset** of Noto (Noto Sans Symbols 2 and Noto Sans Math for the symbols). It contains exactly the
characters the app's strings use, so it costs kilobytes, not the megabytes of a full Noto family.
Android keeps its system fallback and does not load this font.

**FR-R315-3 — The language picker's native names draw on the web.** The same fallback font carries the
glyphs for all 248 names in `LanguageIdentity.kt`: subsets of the matching Noto families (Cyrillic,
Greek, Arabic, Hebrew, Devanagari and the other Indic scripts, Thai, Khmer, Myanmar, Tibetan, Georgian,
Armenian, Syriac, and CJK for the three CJK names). The subset keeps each script's shaping tables
(`GSUB`/`GPOS`) for these names, so Arabic joins and Devanagari conjuncts form correctly: Skia shapes
with HarfBuzz, but only from what the font carries.

**FR-R315-4 — The subset is generated, and so is its guard.** A committed script (for example
`scripts/build-web-fallback-font.py`) builds the fallback from the Noto sources and the character set it
scans for, and writes one file under `composeResources/font/`. Noto's OFL licence goes beside it, as
`ravilo-web`'s `vendor/NOTICE.md` does for the web app's other vendored files. A second check,
`scripts/check-web-glyphs.sh`, runs where the other string checks run. It scans every Kotlin string
literal in `ravilo-ui/src/commonMain`, every `i18n/*.json` value and `LanguageIdentity.kt`, and
**fails** on any character that none of the bundled fonts (Sora, Space Grotesk, the fallback) contains.
A new `✓` in a string, a new language name or a translator's arrow then fails the build with the
character named, instead of reaching the web as a box. The check reads the fonts' `cmap` tables itself,
so the build gains no dependency.

**FR-R315-5 — Nothing about a glyph's meaning changes.** Every drawn glyph sits where its character
stood, with the same size and colour, and keeps a content description where it is the only content of a
control (close, settings, language).

## Non-goals

- Emoji in general, and arbitrary text in scripts the app never uses. No production title needs it
  today, and the guard makes the app's own strings safe. A title in a new script would be a later,
  measured decision.
- Subtitles on the web: JASSUB has its own fallback font (`vendor/default.woff2`).
- The Tizen receiver and the Chromecast receiver, which are HTML pages drawing with the browser's own
  fonts.

## Acceptance

1. The web app in a desktop browser, with the cache cleared: a watched card's tick, every close button,
   the IMDb star, dropdown chevrons, sort arrows, the settings gear, *＋ My List*, *See all →* and the
   synopsis *▾ More* all draw as their glyphs. No replacement box anywhere on Home, a detail page,
   browse, Discover, the player or Settings.
2. The audio & subtitles picker on a title with Russian, Japanese, Korean and Arabic tracks shows
   *Русский*, *日本語*, *한국어* and a correctly joined *العربية*.
3. The Pixel 9 and the stue TV: every icon looks as it did, now drawn rather than typed.
4. `check-web-glyphs.sh` passes on the result, and fails when a `⚑` is added to a string.
5. The fallback font file is under 300 KB, and the web app's first load grows by no more than that.

## Dev review (2026-09-26, against `main` `0e5e434f`)

The cause is confirmed by the framework's own documentation, and the fix is the documented one. Six
items.

1. **Why the boxes: Compose Multiplatform 1.9.3.** JetBrains' web-resources documentation: *automatic*
   font fallback on web (Noto subsets downloaded on demand) starts with **Compose Multiplatform 1.12.0**.
   Before that, a character missing from the loaded fonts is drawn as tofu. This project is on
   `compose-multiplatform = "1.9.3"` (`gradle/libs.versions.toml:14`). The same page gives the control
   this spec uses: *"specify it manually with the `FontFamily.Resolver.preload()` method"*
   (the preload API arrived in 1.8.0). So FR-R315-2 is the supported route on 1.9.3, and it stays right
   after an upgrade: a preloaded font wins over the automatic download, which would otherwise fetch fonts
   from a third party at runtime (the admin self-hosts its fonts for the same reason, FR-167-3).
2. **Where to preload.** In the web entry (`ravilo-ui/src/wasmJsMain/.../RaviloRootActuals.kt`), before
   the first composition that draws text: `preloadFont(Res.font.fallback)` (Compose resources) and
   `LocalFontFamilyResolver.current.preload(FontFamily(fallback))` inside the root. Android does not call
   it.
3. **The glyph set (FR-R315-1)** follows the existing drawn glyphs' shape. `PlayPauseGlyph`
   (`components/Cast.kt:433`) and `SkipGlyph` (`PlayerHandsetChrome.kt:306`) are `Canvas`-based
   composables taking a tint and a size. Put the new set in one file (`components/Glyphs.kt`). The call
   sites are the literals the spec's table counts: `EpisodeCard.kt` and `MultiEpisodeCard.kt` (✓),
   `InstallCard.kt`, `ScreensSheet.kt`, `SeededBrowseScreen.kt` and `TrailerOverlay.kt` (✕, ▲, ▼, ◂, ▸),
   `ImdbChip.kt`, `PlayerScreen.kt` and `RequestScreen.kt` (★), `LibraryTypePill.kt` (︿ ﹀),
   `ProfilePickerScreen.kt` (⚙), `ProfileScreen.kt` (✎), `ServerMessageHost.kt` (✉) and
   `RequestLanguagePicker.kt` (🌐).
4. **The subset.** `pyftsubset` (fontTools), with `--layout-features='*'` so Arabic and Indic shaping
   survives, over Noto Sans Symbols 2, Noto Sans Math and one Noto family per script in
   `LanguageIdentity.kt`, merged into one file with `fontTools.merge`. CJK needs only the six ideographs
   and three Hangul syllables in the three names, so the CJK part is a few kilobytes. The build script is
   committed; fontTools is needed only to regenerate, never to build.
5. **The guard reads `cmap` itself.** `scripts/check-web-glyphs.sh` (a Python helper, like
   `check_i18n_spelling.py`) parses the `cmap` tables of the three `.ttf`s without fontTools: format 4
   and 12 subtables are a short reader. It runs beside `check-ravilo-strings.sh`.
6. **The size budget (acceptance 5) is realistic.** Symbols plus about 25 scripts' worth of 248 short
   names come to tens of kilobytes. 300 KB is a ceiling, not a target.

**Net effect.** One glyph file and about fifteen call-site swaps, one generated font plus its build script
and licence, one preload in the web entry, one check script. No change on Android beyond the drawn icons.
