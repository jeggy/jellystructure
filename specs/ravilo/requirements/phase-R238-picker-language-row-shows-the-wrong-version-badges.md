# Phase R238 — The picker's language row describes the wrong subtitle version, and it describes the one the viewer was complaining about

## Status
`✓ Built` 2026-09-06 — found on the stue TV while verifying **R235** against its own reported file. Not
dev-reviewed.

## What was seen

Verifying R235 on `Helt.Sort.S07E03` (the exact file from that phase's live report), the picker's
**level 1** row read:

> 🇩🇰 **Dansk**  `Default`  `Signs only`   — 2 versions ›   ✓

Descending into **level 2** showed the truth:

| version | badges | selected |
|---|---|---|
| Dansk | `Default` · `Signs only` — *"Only the on-screen text and foreign lines."* | ○ |
| Dansk | `Sound described` — *"Adds speaker names and sound-effect notes."* | **✓** |

R235 had picked correctly — full Danish dialogue was rendering on screen throughout. But the collapsed
row was captioning that selection with the badges of **the track R235 exists to avoid**.

## Why this matters more than a cosmetic slip

R235's live report was: *"there is no subtitles, but the picker says danish is selected; I have to go
in and pick the other danish one."* The viewer's evidence for "the wrong one is selected" is exactly
this row.

So after R235, a viewer whose subtitles are now correct still sees **`Signs only`** attributed to
their selection at the top level. The one surface they would check to confirm the fix worked reports
that it didn't. It invites re-reporting a bug that is fixed, and it invites "fixing" it by hand into
the genuinely wrong track.

## Root cause

`PlayerScreen.kt:2503` (introduced by R195, `c640dec8`):

```kotlin
val single = group.versions.size <= 1
val activeVersion = group.versions.firstOrNull { it.flatIndex >= 0 && (single || selected) }
```

`single` and `selected` are **loop-invariant** — neither depends on `it`. The predicate therefore
reduces to `firstOrNull { it.flatIndex >= 0 }` whenever `single || selected` holds, and to `null`
otherwise. It selects the **first version in stream order**, which for this file is the forced
`Dansk` track.

The intent was already written down, three lines above it:

```
// §A — badges belong to the single version, or (with several) to whichever one is CURRENTLY
// playing; a language with several versions that ISN'T the active one shows no badges at all
```

*"whichever one is currently playing"* is never expressed in the code. Nothing in the predicate
compares a version against the selected track, even though the call site one screen up already
computes exactly that:

```kotlin
val active = g.versions.any { it.flatIndex == selectedFlat }   // :2335
```

`selectedFlat` was available and simply not passed down. This is the same shape as R202 and R231: a
comment stating the correct invariant next to code that does not implement it.

## Goal

The collapsed language row describes the version that is actually playing, or says nothing.

## Requirements

### FR-R238-1 — Badges come from the selected version

`PickerLanguageRow` takes the selected flat index. Badge resolution becomes:

- the language contains the selected track ⇒ badges of the version whose `flatIndex == selectedFlat`;
- otherwise, exactly one version ⇒ that version's badges (unchanged, and the common case);
- otherwise ⇒ no badges, exactly as the existing comment already specifies.

### FR-R238-2 — No badges rather than wrong badges

Where the selected version cannot be identified, the row shows none. A missing badge costs the viewer
nothing; a wrong one actively misinforms them about a choice they are trying to verify.

### FR-R238-3 — The audio tab gets the same fix

The row is shared. The identical failure exists for a language with several audio versions (e.g.
commentary alongside the main mix): the row would caption the selection with the first track's badges.
No separate code path, and no reason to fix only the tab this was noticed on.

## Non-goals

- No change to R235's resolution logic — it was correct, and this phase is only about how its result is
  described.
- No change to level 2, which was right throughout and is what made the diagnosis possible.
- No new badge vocabulary, no copy changes.
- Nothing about `groupDisplayName()`, which picks the row's *name* and is a separate concern.

## Acceptance

1. On `Helt.Sort.S07E03`, level 1's Danish row shows `Sound described` — not `Default` / `Signs only` —
   and matches what level 2 marks selected.
2. Explicitly switching to the `Signs only` version updates the collapsed row to `Signs only`.
3. A single-version language is unchanged.
4. A multi-version language that is *not* selected still shows no badges.

## Source references

- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt:2499-2504` —
  the loop-invariant predicate and the comment stating the correct rule.
- `…/PlayerScreen.kt:2335` — `selectedFlat`, already computed at the call site.
- `…/PlayerScreen.kt:2487-2489` — R195 §A's doc comment, which is the specification this violates.
- `specs/ravilo/requirements/phase-R235-never-auto-select-a-signs-only-subtitle.md` — the phase whose
  verification surfaced this, and whose reported symptom this row reproduces.
- Introduced by `c640dec8` (R195, two-level picker).

## Open questions

1. Should the collapsed row show the badges at all once a language has several versions, given the
   count + `›` already signals "there is more inside"? Showing the active version's badges is the
   documented intent and the smaller change, but "no badges until you descend" is defensible and
   removes a whole class of mismatch. Kept as-is per the existing comment.
