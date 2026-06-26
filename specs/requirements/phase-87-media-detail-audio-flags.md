# Phase 87 — Media detail: audio-track language flags in the pagebar


> Surface the languages a title can be **heard** in at a glance: a small row of country flags in the
> media-detail pagebar, one per audio track that carries a language tag, in physical track order.

## Problem

A title's audio languages are only discoverable today by opening the **Tracks & order** tab and
reading the per-track language codes. There is no at-a-glance signal on the detail page (or anywhere
in the UI) telling the operator "this file has Faroese, Danish and English audio". The data is
already in hand — every audio `MediaStream` carries a `language` (Phase 11 language pickers, Phase 46
track-language write) — it is just never visualised.

## Goal

On the movie detail pagebar (next to the title / TMDB-match badge), show a compact **audio-language
flag strip**: one flag per audio track that has a language, in the **file's physical track order**, so
the operator instantly sees which dubs a file contains and in what priority. It must stay in sync with
manual track-language edits made on the Tracks tab.

## Behaviour

- **One flag per audio track that has a language tag**, rendered left-to-right in **physical audio
  track order** (the same order the Tracks tab and the language resolver walk — track 0 first).
- **Untagged tracks are skipped.** A commentary or any track with no language metadata contributes no
  flag (it is not "unknown-flagged" — it is simply omitted).
- **If no audio track has a language, the strip is hidden entirely** — no empty label, no placeholder.
- **At most 5 flags.** When more than 5 tagged tracks exist, show the first 5 flags followed by a
  `+N` pill where `N` = remaining tagged tracks (e.g. 9 languages → 5 flags + `+4`).
- **Duplicates are kept** — if a file genuinely has two English audio tracks, two `gb` flags show
  (order/fidelity matters more than de-duping at this surface).
- **Live update.** Editing a track's language (or reordering audio) on the Tracks tab re-renders the
  strip immediately from the same in-memory track model — no save/round-trip required.

## Language → flag mapping

Flags are **country** assets but tracks carry **language** codes, so a fixed ISO-639-1 → ISO-3166-1
map bridges them (lives with the frontend render code; mirror the keys in the mockup):

```
en→gb  fr→fr  de→de  es→es  da→dk  fo→fo  is→is  no→no  sv→se  fi→fi
nl→nl  it→it  pt→pt  pl→pl  ru→ru  ja→jp  ko→kr  zh→cn  ar→sa  hi→in
```

A language with no entry in the map is treated like an untagged track for this strip (skipped) rather
than rendering a broken flag. The map is the single source of truth; extend it as new audio languages
appear in libraries. `title` / `aria-label` on each flag carries the human language name for
accessibility and hover.

## Flag assets (new, self-hosted)

- A curated set of **99 country flags** as 4:3 SVGs ships in `design/flags/4x3/<cc>.svg`
  (source: lipis/flag-icons, MIT) plus `design/flags.css`, which defines the base `.fi` class and one
  `.fi-<cc>` background rule per flag. **No CDN** — assets are self-hosted so the frontend works
  offline and air-gapped.
- `design/flags.css` and `design/flags/` must be added to the Gradle **`syncDesignAssets`** copy set
  (the same mechanism that ships `wf.css` + `app.css` verbatim) and `flags.css` linked from
  `index.html`. The mockup CSS is the production CSS — flags are no exception.
- The pagebar strip markup is `.audio-flags` → `.af-label` ("AUDIO") + `.af-row` of `.fi.fi-<cc>`
  spans and an optional `.af-more` pill; styles live in `design/app/wf.css` (small flag size,
  hairline border, subtle shadow for legibility on the dark shell).

## Scope

- `src/wasmJsMain/kotlin/dev/jellystructure/ui/MediaDetail.kt` — render the `.audio-flags` strip in the
  pagebar from the item's ordered audio `MediaStreams`; recompute on track-language/order edits (it
  already owns the track model that drives the Tracks tab).
- `design/flags/4x3/*.svg` + `design/flags.css` — new self-hosted flag asset set (+ `syncDesignAssets`
  + `index.html` link).
- `design/app/wf.css` — `.audio-flags`, `.af-label`, `.af-row`, `.fi` sizing, `.af-more` rules
  (shipped verbatim).
- `design/app/media.html` — mockup is the visual source of truth: strip placed after `#match-badge`,
  populated from `model.audio` in physical order with the skip / max-5 / `+N` / hide-when-empty logic.

## Verification

Open **Sintel** detail: pagebar shows `🇬🇧 🇫🇷 🇫🇴 🇩🇰` (English, French, Faroese, Danish — the
commentary track, which has no language, is skipped). On the Tracks tab, change an audio track's
language → the pagebar flag updates without a save. Open a title with >5 tagged audio tracks → exactly
5 flags + a `+N` pill. Open a title whose audio is all untagged → no strip renders.

## Non-goals

- **Subtitle-language flags** — this strip is audio only. (A subtitle equivalent, if wanted, is a
  separate follow-up.)
- **Series detail aggregation** — series resolve language per-episode and already have the Phase 12
  series-language distribution card; an aggregated series flag strip is out of scope here.
- **Clicking a flag** to filter/jump — display only; library audio-language filtering already exists
  (Phase 20).
- **Changing track data** — this surface never writes; it only visualises existing `language` tags.
