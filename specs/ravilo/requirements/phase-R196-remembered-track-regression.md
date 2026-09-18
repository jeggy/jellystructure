# Phase R196 — Ravilo: remembered audio/subtitle choice stopped applying after R195 (bug fix, FR-RV-TRK2)

> Reported 2026-08-14: *"Ever since we updated the designs for subtitles picker in ravilo, everything we
> did in regards of remembering subtitles for next episodes or when continuing watching a series I
> started and stopped without finishing [stopped working]. Let's fix this again, so it starts
> remembering just like before."*

**Status:** Planned — dev-authored, root-caused by code reading, not yet built.

## 1. Scope of the regression

R181 (FR-RV-TRK1) shipped layered track resolution: **per-series remembered choice → learned global
choice → source default → first track / Off**. R195 rewrote the picker into two levels and extended
`RememberedChoice` with `audioVariant`/`subtitleVariant` signatures so the exact same-language
*version* survives to the next file.

The reported symptom is that the remembered **subtitle** choice no longer applies. Reading the code,
the defect is not subtitle-specific and not next-episode-specific: **the entire remembered tier —
audio and subtitles, per-series and global — has been dead since R195**, on the first episode as well
as on auto-advance. That matches both halves of the report ("for next episodes **or when continuing
watching a series I started and stopped**").

Persistence itself is fine: `persistChoice` still writes on every pick, and both platform stores
(`PlaybackPrefsStoreAndroid`, `PlaybackPrefsStoreWasm`) round-trip all five fields correctly. The
values are in storage. They are simply never matched on read.

## 2. Root cause — a stale composition snapshot captured by the poll loop

`resolveTrackSelection()` is called from the player's 500 ms poll loop
(`PlayerScreen.kt:756-759`), inside `LaunchedEffect(Unit) { while (true) { … } }` (`PlayerScreen.kt:721`).
`LaunchedEffect` with a constant key never restarts and never re-captures its block, so the lambda —
and every plain local `val` it closes over — is the one built during the **first** composition of
`PlayerScreen`.

Before R195, `resolveTrackSelection()`'s remembered tiers read the raw track lists directly:

```kotlin
// pre-R195
choice?.audioLanguage?.let { lang -> audioTracks.firstOrNull { it.language.equals(lang, true) }?.index }
```

`audioTracks` / `subtitleTracks` are `var … by remember { mutableStateOf(...) }` (`PlayerScreen.kt:249-250`)
— snapshot state, so a read from inside the long-lived effect always sees the current value, which the
same loop assigned two lines earlier. That worked.

R195 changed both tiers to resolve through the new grouping instead:

```kotlin
// post-R195 (PlayerScreen.kt:526, :546)
val group = audioGroups.firstOrNull { it.language.equals(lang, ignoreCase = true) } ?: return null
val group = subGroups.firstOrNull  { it.language.equals(lang, ignoreCase = true) } ?: return null
```

and `audioGroups` / `subGroups` are **plain vals** produced by `remember(...)`
(`PlayerScreen.kt:361`, `:368`) — not snapshot state. The effect's closure therefore holds the values
computed during the **first** composition, when `audioTracks` and `subtitleTracks` are still their
initial `emptyList()`:

- `subGroups` is permanently `emptyList()` → `tierSub` always returns `null`.
- `audioGroups` is permanently the one placeholder group `buildLanguageGroups` synthesises from
  `audioTracks.ifEmpty { listOf(PlayerAudioTrack(0, "Default", null)) }`, whose `language` is `null`
  → `tierAudio`'s `equals(lang, ignoreCase = true)` on a null receiver is always false → always `null`.

Both remembered tiers therefore fall straight through to `audioTracks.firstOrNull { it.isDefault }`
and `subtitleTracks.firstOrNull { it.isDefault } ?: firstOrNull { it.forced } ?: -1` — which read live
state and so *appear* to work, which is exactly why this looks like "it just picks the default now"
rather than a crash. `resolvedForItemId` is then set, so the resolve never runs again for that item.

The rest of the picker is unaffected because it is rendered from composition, where `subGroups` is
always current — hence "picking works, remembering doesn't".

**This exact hazard is already documented in this file.** Lines 213-222 wrap `itemId`,
`nextEpisodeId`, `seriesId`, `originalLanguage`, `segments` and the skip/autoplay modes in
`rememberUpdatedState` precisely so the poll loop sees live values, with a comment recording a prior
bug of the same shape ("auto play next never working again"). R195's new derived values were not given
the same treatment.

## 3. Functional requirements

### FR-RV-TRK2-1 — `resolveTrackSelection()` must resolve against live track data

Everything `resolveTrackSelection()` reads must be current at call time. Either:

**(a)** derive the groups inside the function from the live state (`buildLanguageGroups` over the
current `audioTracks` / `subtitleTracks + encodeSubTracks`), or

**(b)** wrap the derived values in `rememberUpdatedState` and read through those, matching the
established pattern at `PlayerScreen.kt:213-222`.

**(b) is recommended** — it is the pattern the file already uses, keeps `remember`'s memoisation for
the composition path, and makes the intent obvious to the next reader. It must cover every derived
value the function transitively depends on: `audioGroups`, `subGroups`, `subVersionOptions` and
`encodeSubTracks` (`PlayerScreen.kt:334`, itself `remember(sessionState)`).

Whichever is chosen, add a comment at the `remember` sites saying these are read from the poll loop,
so a future derived value doesn't reintroduce this.

### FR-RV-TRK2-2 — Restore the full layered resolution, verified end to end

After the fix, all four tiers must be observably live again, for **both** axes:

1. per-series `RememberedChoice` (exact variant signature first, then the language's first native
   version),
2. learned global `RememberedChoice`,
3. the source's own default track,
4. first track / forced / Off.

Including the R195 additions that have never actually run in production: the
`audioVariant`/`subtitleVariant` signature match, and its documented fall-back to a language-only
match when the next file doesn't carry that exact variant.

### FR-RV-TRK2-3 — `subtitlesOff` must survive as a first-class state

`tierSub` returns `-1` for a remembered "off" before it looks at any group, so it is not affected by
the same-language grouping — but it *is* affected by the same stale closure via `seriesChoice` /
`globalChoice`? (No: those are read fresh from `PlaybackPrefsStore` inside the function.) Confirm by
test rather than by reading: turning subtitles off on episode 1 must leave them off on episode 2 and
on a later resume.

### FR-RV-TRK2-4 — A regression guard

Add a test that fails against the current code. The resolution tiers are currently expressed as local
functions inside a composable, which is not directly testable; extract the pure part —
"given (remembered choice, audio groups, sub groups, track lists) → (audioIndex, subIndex)" — into a
plain top-level function in `commonMain` and unit-test it, including the case that broke:
**empty groups plus non-empty track lists** must not silently resolve to the source default.

This extraction is the point of the requirement. Without it there is no way to prove this stays fixed.

## 4. Non-goals

- Any change to what is persisted, or to the storage format on either platform. Storage is correct.
- Any change to the picker's visual design or to `buildLanguageGroups`' grouping/clustering rules.
- The PGS/`encode` scope note stands: a remembered language that exists in this file only as a
  burn-in track still falls through rather than triggering an autoplay transcode.
- Server-side sync of playback preferences. These stay client-local per R181.

## 5. Notes for the implementer

- `PlaybackPrefsStoreWasm`'s hand-rolled parser splits the series map on `,` and `:`, and `seriesKey`
  (a media-id slug) is interpolated unescaped. Not the cause of this bug and not in scope, but worth a
  glance while in the file — a slug containing either character would corrupt the whole map.
- `persistChoice`'s null-language case: a `PickerLanguage` with `language == null` (the untagged-track
  cluster R195's `groupDisplayName()` handles for display) calls
  `persistChoice(newSubtitleLanguage = null, …)`, which the read-modify-write then treats as "no
  change" and carries the *previous* language forward, while still storing the new variant signature.
  That mismatch is latent today and will start mattering once the remembered tier works again.
  Decide explicitly: either don't persist a null-language pick, or persist it as a real "untagged"
  sentinel.

## 6. Verification

- Unit test per FR-RV-TRK2-4.
- On a Pixel 9 Pro (the standing default verification device): pick a non-default subtitle language on
  episode 1 of a series, let it auto-advance — episode 2 must open with the same language. Back out
  entirely, re-enter from Continue Watching — same language again.
- Same for audio, and for a same-language *variant* (pick SDH where the language has both SDH and
  plain) — the next episode must come up SDH, not the first same-language track.
- Turn subtitles off, advance, confirm still off.
- Report any unexpected D-pad/focus behaviour noticed during the pass, per standing convention.
