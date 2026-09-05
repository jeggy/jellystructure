# R235 — never auto-select a signs-only subtitle track when a real one exists

> Live report (stue TV): *"Very often (example in 'Helt Sort' S07E03) there is no subtitles, but when
> opening the subtitles picker I can see danish is selected and then I just have to go in and select
> danish and pick the other danish subtitles to be able to see any subtitles at all."*

## Status
Planned (spec'd 2026-09-06). Not dev-reviewed. **Root cause confirmed against the exact reported
episode** in the production database, and quantified library-wide.

## Evidence

`helt-sort-2019`, S07E03 (*Hygge*),
`Helt.Sort.S07E03.DANiSH.1080p.WEB.h264-STROMPEBUKSER.mkv` — its tracks as scanned:

| kind | lang | codec | title | default | forced |
|---|---|---|---|---|---|
| AUDIO | dan | aac | `Dansk` | **true** | false |
| SUBTITLE | dan | subrip | `Dansk` | **true** | **true** |
| SUBTITLE | dan | subrip | `Dansk (CC)` | false | false |

The file's *first* Danish subtitle track is **forced** — signs-only — and the file also marks it
**default**. The second Danish track is the full one.

Danish audio plus a Danish signs-only track means, in practice, an empty screen: forced subtitles exist
to translate the handful of on-screen signs and foreign lines an otherwise-understood soundtrack leaves
out. A viewer who wanted subtitles gets, correctly, almost nothing — and the picker truthfully reports
"Danish", because Danish *is* what is selected.

Both of `resolveTrackChoice`'s relevant branches land on that track
(`PlayerScreen.kt:3462-3477`):

```kotlin
fun tierSub(choice: RememberedChoice?): Int? = when {
    …
    else -> {
        val lang = choice.subtitleLanguage ?: return null
        val group = subGroups.firstOrNull { it.language.equals(lang, ignoreCase = true) } ?: return null
        val native = group.versions.filter { it.flatIndex < subtitleTracks.size }
        val bySignature = choice.subtitleVariant?.let { sig -> native.firstOrNull { it.signature() == sig } }
        (bySignature ?: native.firstOrNull())?.flatIndex        // ← falls back to the FORCED track
    }
}
val subIdx = tierSub(seriesChoice) ?: tierSub(globalChoice)
    ?: subtitleTracks.firstOrNull { it.isDefault }?.index       // ← the FORCED track (file marks it default)
    ?: subtitleTracks.firstOrNull { it.forced }?.index          // ← deliberately picks a forced track
    ?: -1
```

- **Remembered "Danish", variant not matched in this file** → `native.firstOrNull()` → the forced one.
  R195 added the variant signature precisely so "always SDH" keeps meaning SDH, but its *fallback* is
  stream order, and stream order here puts the forced track first.
- **No memory at all** → `firstOrNull { it.isDefault }` → the forced one, because the file says so.
- The third fallback, `firstOrNull { it.forced }`, is an explicit preference for a forced track. That
  is the right last resort for a viewer who has never expressed a preference and is watching foreign-
  language audio; it is the wrong answer whenever a full track in the same language is sitting beside
  it.

Nothing anywhere in the ladder knows that a forced track is not a substitute for a full one.

### How common

Census over the production database, 6 721 files carrying subtitle tracks:

```
files where a language's FIRST subtitle track is forced while a
non-forced track exists in that same language, OR the file's default
subtitle track is forced while a non-forced same-language track exists:

    247 of 3 476 units with subtitles   (7.1 %)
```

Spread across Danish TV (*Helt sort*, *Forbrydelsen*), Marvel releases, Netflix rips and more — this is
a release-packaging convention, not one bad file. Roughly one title in fourteen picks the wrong Danish.

## Problem

R181 defined the resolution ladder in terms of **language**. R195 refined it to **language + variant**,
but only for a remembered choice that matches exactly. Neither layer has any concept of a track being
*insufficient on its own* — which is exactly what `forced` means.

The result is a silent failure. Nothing is broken, nothing errors, the picker's own state display is
accurate, and the viewer has no way to tell that the app chose a deliberately-near-empty track for
them. Every affected viewer has to learn the workaround the reporter learned.

## Goal

When Ravilo picks a subtitle track without being told exactly which one, it never picks a signs-only
track over a full one in the same language. Choosing a forced track explicitly still works and is still
remembered.

## Requirements

### FR-R235-1 — Forced is a last resort within a language, never a default

Inside a language group, automatic selection prefers, in order:

1. the exact remembered variant signature (unchanged — an explicit past choice always wins, **including
   a remembered forced track**),
2. the first **non-forced** version in the group,
3. the first version of any kind (i.e. the group is forced-only, so forced is genuinely all there is).

Concretely, `tierSub`'s `bySignature ?: native.firstOrNull()` becomes
`bySignature ?: native.firstOrNull { !it.forced } ?: native.firstOrNull()`.

`PickerVersion` already carries `forced` (`PlayerScreen.kt:3397`), so no new data is needed.

### FR-R235-2 — The source's own "default" flag does not override this

`subtitleTracks.firstOrNull { it.isDefault }` must not select a forced track when a non-forced track
exists in the same language. The file that started this report marks its forced Danish track *both*
default *and* forced; that combination is a packaging habit, not an instruction, and jellystructure's
own scan data proves it happens 247 times in this library.

Order becomes: first non-forced default → first non-forced track in the audio-matched / first language
→ existing forced fallback → off.

### FR-R235-3 — An explicit pick of a forced track is honoured and remembered forever

Nothing about this phase makes a forced track unreachable or unstickable. If a viewer opens the picker,
enters Danish and picks the **Signs only** version, that choice is stored with its variant signature
(`forced||0`) and FR-R235-1's step 1 replays it on the next episode. The badge already exists
(`player.badge_signs_only`, R180) and stays.

### FR-R235-4 — Same rule for audio

`tierAudio` has the same `bySignature ?: group.versions.firstOrNull()` shape and the same
`audioTracks.firstOrNull { it.isDefault }` fallback. Audio has no `forced` concept, but it has
`VariantKind.COMMENTARY` and `VariantKind.DESCRIBE` — a director's commentary or an audio-description
track is likewise never the right *automatic* pick over a plain track in the same language. Apply the
identical "prefer PLAIN within the group" rule, with an explicit past choice still winning.

This is the same bug one tab over and should not wait for its own report.

### FR-R235-5 — "CC" is recognised as a subtitle variant

`SDH_RE` (`PlayerScreen.kt:3231`) matches `sdh`, `hi`, `hard of hearing`, `hearing impaired`,
`hearing` — but not `cc` / `closed caption`. The reported file's full track is literally titled
`Dansk (CC)`, so today it groups as `PLAIN` and shows no descriptive badge at all. Add `\bcc\b` and
`closed caption` to the pattern.

This changes the variant signature of any track so titled (`plain|…` → `sdh|…`), which means a
previously remembered choice for such a track falls back to FR-R235-1's step 2 once. That is
acceptable — step 2 now lands on a full track — and must be stated rather than discovered.

### FR-R235-6 — A test that fails today

`PlayerScreenTrackResolutionTest` gains the reported file's exact track list: two Danish subtitle
tracks, the first `forced = true, isDefault = true` titled `Dansk`, the second plain titled
`Dansk (CC)`. Assert that with no remembered choice, and with a remembered `subtitleLanguage = "da"`
whose variant signature matches neither, resolution lands on the **second** track. Both assertions must
fail against `main`.

## Non-goals

- No backend change. Track metadata is already correct — jellystructure scanned the `forced` flag
  faithfully and Jellyfin/ExoPlayer report it faithfully. This is entirely a client selection-order
  bug.
- No change to R195's two-level picker structure, grouping, clustering, region synonyms or copy.
- No change to `RememberedChoice`'s stored shape or to `PlaybackPrefsStore`. Existing memories stay
  valid.
- No PGS/encode-subtitle behaviour change — those stay a manual pick (R181's scope note).
- No auto-enabling of subtitles for a viewer who has them off.

## Acceptance

1. Stue TV, *Helt sort* S07E03, with no prior Danish memory for the series: subtitles appear on screen
   the moment playback starts, and the picker shows the `Dansk (CC)` version selected.
2. Same episode with a stale global memory of `subtitleLanguage = "da"` and an unmatched variant: same
   result.
3. Explicitly pick the **Signs only** Danish version, then play S07E04: signs-only is still selected.
4. A file whose only Danish subtitle track is forced still selects it (nothing is made unreachable).
5. A file with a commentary audio track and a plain one, no memory: the plain one plays.
6. `Dansk (CC)` shows the sound-described badge.
7. `linuxX64Test` / `commonTest` green including FR-R235-6's new cases.

## Source references

- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreen.kt`
  — `resolveTrackChoice` `:3443-3479` (the ladder), `tierAudio` `:3451-3459`, `tierSub` `:3462-3477`,
  `PickerVersion` `:3390-3402`, `signature()` `:3407`, `variantKind` `:3330-3336`, `SDH_RE` `:3231`,
  `subtitleBadges` `:3305-3311`, `buildLanguageGroups` `:3486-`.
- `ravilo-ui/src/commonMain/kotlin/dev/jellystructure/ravilo/ui/screens/PlaybackPrefsStore.kt`
  — `RememberedChoice`, incl. R195's `subtitleVariant` doc.
- `ravilo-ui/src/commonTest/kotlin/dev/jellystructure/ravilo/ui/screens/PlayerScreenTrackResolutionTest.kt`
- `specs/ravilo/requirements/phase-R195-same-language-subtitle-picker.md` — §5.4, the variant memory
  this extends.
- R180/R181 — the flag-forward picker and the original remembered-language ladder.

## Open questions

1. Should a **forced** track be auto-selected when the chosen *audio* language differs from the
   viewer's UI language — i.e. the case forced subtitles were actually invented for (English audio, a
   Danish viewer, a Danish forced track)? Today's final fallback does this by accident. FR-R235-1/2
   keep it as a last resort, which preserves the behaviour, but if it should become a *deliberate* rule
   it needs its own requirement and its own reasoning about what "the viewer's language" means on a
   shared TV.
2. R195 deferred Bazarr `hi`-flag plumbing. FR-R235-5's `cc` addition is title-text sniffing of the
   same kind and has the same limits — a track with no title at all still can't be classified. Out of
   scope here, noted so it isn't mistaken for solved.
