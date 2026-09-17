# Phase 184 — Choose the TMDB metadata language for a single title

> Reported live: **열배** (`c045ed9c87a18fbb1580cfed748acb86`) is a Korean film with a Korean first
> audio track, so the resolver fetches its TMDB metadata in Korean and the library shows a Korean title
> and a Korean overview. That is exactly what the resolver was designed to do, and it is the wrong
> answer for this house: the audio should stay Korean, the *reading* should be English. There is no way
> to say so. This phase adds one manual escape hatch — **choose a different winner for this one item** —
> and changes nothing else.

**Status:** ✓ Built — see `STATUS.md`, which is authoritative. (Header as originally written: Planned) (design-authored 2026-09-01, not yet dev-reviewed)

Design: `design/app/Metadata Language Override - Directions.html` (Direction A chosen; B and C recorded
as rejected). Implemented in the mockup at `design/app/media.html` + `design/app/detail.css`.

## Current state

`specs/constitution.md`: *"The metadata language is driven by the actual audio tracks present in each
file — not a global setting."* The resolver walks a file's audio streams in physical order, asks TMDB in
the first tagged language it finds, and the first language that returns a result becomes the **winner**
for every TMDB-derived field on that item. For 열배:

```
0:a:0 kor  ✓ TMDB result → winner
0:a:1 ??   untagged → skipped
0:a:2 kor  → duplicate, already resolved
0:a:3 kor  → duplicate, already resolved
0:a:4 kor  → duplicate, already resolved
0:a:5 eng  → skipped (winner already found)
0:a:6 kor  → duplicate, already resolved
0:a:7 kor  → duplicate, already resolved
0:a:8 eng  → skipped (winner already found)
```

The file genuinely has English audio at `0:a:5`, but the cascade never reaches it, and it should not
have to — a file whose English track sat first would resolve to English by accident, which is no better.
The resolver is not wrong; it is answering a different question than the one the operator has.

The admin's right-rail card already renders this trace read-only, so the *diagnosis* is solved and only
the *remedy* is missing.

## Goal

An operator opens a title, sees why its metadata is Korean, and picks a different language from the ones
TMDB actually holds for that title. Everything downstream then behaves exactly as it would for a title
whose winner resolved to that language automatically.

**English is the example, not the destination.** Any language TMDB holds for the title is selectable,
including going the other way (an English-audio film read in Danish).

## The three languages this phase must keep separate

| | What it is | Editable |
|---|---|---|
| **Original language** | TMDB's `original_language` — `ko`. A fact about the film. | Never |
| **Resolved language** | The winner of the audio cascade — `kor`, from `0:a:0`. Describes the *file*. | Never (it's derived) |
| **Metadata language** | What we ask TMDB for. Today it silently equals the resolved language. | **This phase** |

Conflating the second and third is the whole bug. The fix is to give the third its own storage and let it
default to the second.

## Requirements

### FR-184-1 — `metadataLanguage` on the item, defaulting to null

A nullable `metadataLanguage: String?` (ISO-639-1) on `MediaItem`. Null means *"resolve automatically"*
and is the default for every existing and future item — this phase adds no behaviour to the 99% of the
library that never gets touched. Non-null means an operator chose it, and it is the language every TMDB
request for this item is made in.

The resolver is **not modified**. It keeps running, keeps producing its trace, and its result keeps
being displayed; `metadataLanguage` is consulted at the point the fetch language is decided, above the
resolver, not inside it. The constitution's rule stays literally true — language resolution is still
per-file and first-TMDB-hit-wins; this is a layer over it, not an edit to it.

### FR-184-2 — Choosing a language re-pulls the item, through the existing path

Setting `metadataLanguage` triggers the **ordinary** `rescanMetadata` / `pull_tmdb` path with the chosen
language, and writes its result the way that path always writes it.

Explicitly: **no new field-filling logic, no new fallback, no new empty-field handling.** If TMDB has no
overview in the chosen language, whatever happens today for an automatically-resolved language happens
here too. This was a deliberate design decision (owner, 2026-09-01) after a first draft that flagged
missing fields — the flagging invented a state the rest of the page doesn't have, for a case the
existing code already handles.

### FR-184-3 — The choice is a lock

`pull_tmdb`, a scheduled or manual re-scan, a Jellyfin re-pull and a sync all preserve `metadataLanguage`.
Same shape and the same reason as Phase 151's `preserveLockedArtwork` and Phase 174's
`preserveTmdbMatchLock`: a fresh `MediaItem` built by a scan carries the field at its `null` default, so
the store guard must carry the stored value forward on `addOrUpdate`/`updateOne` or the next scan
silently undoes the operator's work.

Only two things clear it: the operator choosing **Back to automatic**, or a Phase 174 `Clear TMDB match`
(which by its own definition removes every TMDB-derived decision about the item — `metadataLanguage`
joins the FR-174-1 field set).

### FR-184-4 — The picker offers only what TMDB holds, with coverage

`GET /api/media/{id}/tmdb-languages` returns the languages TMDB currently has for the item, each with
enough to judge it: whether a **title** and an **overview** exist, and how many **posters** are
available. Backed by `GET /movie|tv/{id}/translations` plus the image language counts already fetched
for the Artwork tab.

A language TMDB doesn't hold is not offered. The coverage read-out is there so the operator can see
that Danish is title-only *before* choosing it — it is information, not a warning, and choosing a
sparse language is allowed and produces no special state.

Empty/error response ⇒ the picker says so and the button stays inert; it must not fall back to a list of
all ISO languages, which would offer choices that cannot work.

### FR-184-5 — Artwork follows the same choice

Poster and backdrop candidate fetching uses the chosen language
(`include_image_language=<chosen>,null`), so the item's artwork matches its text. The `null` half is
deliberate and is the reason artwork can't simply be a language row in the picker: **textless artwork
has no language**, and for a foreign-language film it is frequently the best available poster. It stays
reachable as its own group on the Artwork tab, exactly as it is today.

### FR-184-6 — A series is chosen as a whole

`metadataLanguage` on a series applies to the series and **every episode under it**. Per-episode
resolution keeps running (episodes have their own audio tracks and their own traces), but an episode
whose own audio resolves elsewhere does not escape the series' choice. There is no per-episode override.

### FR-184-7 — Admin UI

The existing right-rail **Metadata language** card on `media.html` / `series.html` grows the control
(design: Direction A):

- **Automatic (default).** Unchanged from today: the trace, and `Fetching metadata in <lang>`. Below it,
  a full-width **Choose another language…** button.
- **Picker.** A searchable menu anchored to the card. Two groups — *Resolved automatically* (the current
  winner) and *Also available for this title* — each row showing the language code, English name, native
  name, and three coverage pips (`Aa` title · `¶` overview · `▣` poster count; hollow = TMDB doesn't hold
  it). Picking the resolved language is the same action as Back to automatic.
- **Chosen.** The card takes an accent border and a `chosen` badge; a band names the language and when it
  was set; the **trace stays visible underneath, dimmed**, under the heading *Resolver would pick*. The
  operator must always be able to see what the automation wanted — a choice that hides its own cause is
  a mystery six months later. Then **Change language…** and **Back to automatic (`<resolved>`)**, and a
  note that the choice is locked against `pull_tmdb` and re-scans.

Nothing else on the page changes. The Metadata tab cannot tell a chosen winner from a resolved one.

### FR-184-8 — Visible in the library, and in History

- **Library list:** a small language chip on rows where `metadataLanguage != null`. The automatic
  majority stays unmarked.
- **Workbench:** a **Metadata language** facet (Metadata group) with `is any of` / `is none of` plus a
  `chosen` / `automatic` distinction, so the handful of hand-set titles can be found again.
- **History:** one operator action, three recorded writes — the language change (with the previous
  value), the NFO re-write, and the artwork re-pick. Reverting the language row restores all three, the
  same contract as Phase 174's revert.

## Explicitly out of scope

- **Rules and defaults.** No "anything Korean → English" rule, no per-library default, no global
  preference. This is a per-item escape hatch for rare edge cases; automatic resolution stays the
  default and stays untouched. (Owner decision — a rule engine is a much larger phase and there is no
  evidence yet that the cases cluster.)
- **Separate text and artwork languages.** One choice covers both.
- **Per-episode overrides.**
- **Any Ravilo work.** Ravilo receives whatever the admin stored. No client changes, no new strings.
- **Editing the resolver.** The cascade is not reordered, re-weighted or made configurable.

## Open questions for dev review

1. **Is `/translations` cheap enough to call when the picker opens**, or does the coverage read-out need
   caching on the item? A series needs the same call per season for episode-level coverage — if that is
   too expensive, series coverage may have to be series-level only.
2. **Does the NFO record the chosen language?** A field would let Jellyfin and a future re-import agree
   with us; leaving it out keeps the NFO a pure content file and the choice a jellystructure-side fact.
   Phase 163's Jellyfin experience argues for not assuming Jellyfin will honour anything we invent.
3. **`titlesByLang` interaction.** The store already union-merges TMDB translations with manual
   `metadata_edit` titles (see Phase 174's note on why it is left alone). Does choosing a language change
   which entry is *primary*, or does it only change what gets fetched? The former is probably what an
   operator expects and the latter is what falls out of the code — worth deciding deliberately.
4. **Music videos** never touch TMDB (Phase 168), so the card should presumably not render for
   `MediaKind.MUSIC_VIDEO` at all. Confirm.
