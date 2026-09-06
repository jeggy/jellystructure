# Phase 199 — the tag guard is keyed off the wrong predecessor, so a slug rename drops every JS tag

> Audit, 2026-09-06, prompted by the question *"can you verify this really works in all different
> cases? we have multiple ways of running scanning etc"* — asked about constitution invariant #6.
> The answer is *almost*: every scan-shaped path is safe, one is not, and the guard has no test.

## Status
`Planned`, 2026-09-06. Design/audit-authored, not dev-reviewed. Nothing built. Backend-only — no
mockup change, no Ravilo counterpart.

## The invariant under audit

`specs/constitution.md:231` — **Key Invariant #6, tag lifecycle**:

> **JS tags always survive** — non-JS tags are sourced externally; tags defined in `js_tags`
> (`JsTagStore.nameSet()`) are always preserved on every path.

The plain reading: nothing automatic ever removes a jellystructure-defined tag from an item. Only an
explicit operator action does.

## What the audit found

`MediaStore` has **three** write choke points, and the preserve-guards cover different subsets of them:

| | `update()` `:307` | `addOrUpdate()` `:587` | `updateOne()` `:654` |
|---|---|---|---|
| `preserveJsTags` | ✓ `:321` | ✓ `:613` | **✗ absent** |
| `preserveLockedArtwork` | ✗ | ✓ `:616` | ✓ `:674` |
| `preserveTmdbMatchLock` | ✗ | ✓ | ✓ |
| `preserveMetadataLanguage` | ✗ | ✓ | ✓ |

`MediaStore.kt:155-159` states the artwork lock is applied *"at BOTH write choke points below,
mirroring `preserveJsTags`/`mergeUserGenres`"*. The two guards in fact cover **disjoint pairs**. The
comment has been wrong since Phase 151 and is the reason nobody looked again.

### Paths that hold (each verified individually)

- Full scan / scheduled pipeline → `PipelineStepOps.kt:39` → `addOrUpdate` ✓
- Realtime + webhook ingest → `RealtimeIngestService.kt:210` additive union, **then** `addOrUpdate` ✓✓
- Phase 181 library sweep → `MediaRoutes.kt:2421/2436` → `addOrUpdate` ✓
- Re-pull from Jellyfin → `Scanner.kt:320` additive union ✓
- Re-pull from TMDB (`rescanMetadata`) → `mergeRepullTags` `Scanner.kt:1272,1343` ✓
- `POST /{id}/sync` movie → `syncMovie` `:931` ✓ · series → `syncSeriesEpisodes` `:1134` ✓
- `PATCH /media/{id}/metadata` — the *only* route that can remove a tag — reaches `updateOne`, which
  has no guard, so a manual removal genuinely sticks ✓

That last row is the uncomfortable part: **the removal half of the invariant works by accident**,
because the guard is missing from exactly the path that needs it missing. Nothing states that
dependency, so any future defence-in-depth commit that adds `preserveJsTags` to `updateOne` — the
obvious fix on reading the table above — silently makes manual tag removal impossible.

### The live bug

`MediaStore.kt:609-618`, inside `addOrUpdate`:

```kotlin
// Phase 108: the item's real predecessor is `stale` across an id-rename (existing is null in
// that case, keyed under the old id) …
val old = stale ?: existing
var merged = preserveJsTags(item, existing)      // ← existing
merged = preserveLockedArtwork(merged, old)      // ← old   (Phase 151)
merged = preserveTmdbMatchLock(merged, old)      // ← old   (Phase 174)
merged = preserveMetadataLanguage(merged, old)   // ← old   (Phase 184)
```

Four guards, one line apart, three of them keyed on the predecessor and one on the wrong thing.

On a slug rename — the case this same function documents at `:589-594`, `girl-taken` →
`girl-taken-2026` once TMDB supplies the year, and equally any title correction — `get(item.id)`
returns `null` because the row lives under the *old* id. The old row is located by `jellyfinId`, and
`deleteById`'d **in this same call** at `:605`. So `preserveJsTags(item, null)` returns at
`:150` (`?: return fresh`) having preserved nothing, the fresh Jellyfin/TMDB tag set is written, and
the only row that held the operator's tags is gone.

**Silent, and unrecoverable.** No History entry records it (the tags were never "edited"), the
`removed stale duplicate` line at `:606` reports a *duplicate cleanup*, and the next scan has nothing
left to restore from.

Phases 151, 174 and 184 each hit this exact hazard for their own field, each fixed it by switching to
`old`, and each cited `preserveJsTags` in its comment as the pattern being mirrored. The pattern they
were mirroring was itself broken.

### Exposure

Production, 2026-09-06 — **355 of 516 items carry at least one JS tag**:

| tag | items |
|---|---|
| `damkjær-streaming` | 311 |
| `horror-streaming` | 26 |
| `børne-tv` | 16 |
| `føroyskt` | 14 |
| `non-kids` | 2 |

`børne-tv` and `non-kids` feed `passesTagPolicy` / `visibleTo` (`VisibleToTest.kt:74-104`), so losing
one is not a cosmetic metadata loss — it is a **visibility change on a kids device**, in either
direction, with no notification.

Whether it has already fired is unknown: `grep "removed stale duplicate"` over the production
container logs returns 0, but that log window only reaches back to the 2026-09-06 restart. Absence of
evidence, not evidence of absence.

### No test, anywhere

Nothing under `src/linuxX64Test/` references `preserveJsTags`, `mergeRepullTags` or `JsTagStore`.
`ArtworkLockTest`, `TmdbMatchLockTest` and `MetadataLanguageLockTest` all exist — those three guards
were **extracted out of `MediaStore` into pure functions specifically so they could be unit-tested**
(`ArtworkLock.kt:26`: *"extracted from `MediaStore` so it can be applied at both store write choke
points and unit-tested directly"*). `preserveJsTags` stayed `private` and untested, which is precisely
why a one-word divergence survived three phases of work in the adjacent lines.

## Functional requirements

- **FR-199-1 — key the tag guard off the predecessor.** `addOrUpdate` passes `old`, not `existing`, to
  the JS-tag guard, matching the three guards beside it. A slug rename keeps the operator's tags.

- **FR-199-2 — extract it so it can be tested.** Move the JS-tag guard (and `Scanner.mergeRepullTags`)
  out into their own file beside `ArtworkLock.kt`, as pure `internal` functions taking the JS-tag name
  set as an explicit parameter rather than reaching for `jsTagStore`. This is the shape Phase 151 chose
  for exactly this reason; the two tag rules are the last ones still inlined and unreachable from a test.

- **FR-199-3 — make the removal path explicit rather than accidental.** Apply the guard at
  `updateOne` too, behind a `respectJsTags: Boolean = true` parameter, with `false` passed on exactly
  the routes that deliberately *change* an item's JS tags — `PATCH /api/media/{id}/metadata`
  (`MediaRoutes.kt:1468`) and its History revert (`:479`). Same contract, same wording and same
  defaults as `respectTmdbMatchLock` (Phase 174) and `respectMetadataLanguageLock` (Phase 184), so the
  three read alike. **The point is not extra protection — today's `updateOne` callers are all safe.
  The point is that "manual removal works" stops being a property of an omission and becomes a
  property somebody wrote down**, defended by FR-199-4's test.

- **FR-199-4 — tests, one per path the audit walked.** A new test file covering: a slug rename keeps
  JS tags (the FR-199-1 regression); a manual removal through the metadata route sticks (the FR-199-3
  regression, and the one that would break if FR-199-3 were done naively); a TMDB re-pull keeps JS tags
  and drops stale Jellyfin-only ones; a Jellyfin re-pull is additive; and a tag not in `nameSet()` is
  not preserved. These are the five behaviours constitution #6 actually promises.

- **FR-199-5 — correct the comment that hid this.** `MediaStore.kt:155-159` misdescribes which guards
  run where. Replace it with the real coverage table, and state the predecessor rule once, plainly:
  *across a slug rename the predecessor is not the row under the item's own id.* That sentence is the
  whole bug, and it is currently written down only in `:609-611`, one guard too late.

- **FR-199-6 — amend the constitution to say which predecessor.** Invariant #6 says "on every path"
  without defining what the tags are preserved *from*. Add the predecessor rule to it. A guard keyed on
  the wrong row satisfies the invariant as currently written.

- **FR-199-7 — `DELETE /tags/{name}` has a half-life; decide, don't leave it.** `plan.md:301` promises
  the delete *"does not strip it from items"*, and `MetadataRoutes.kt:360` honestly only removes the
  definition. But `nameSet()` no longer contains the name, so the **next scan or TMDB re-pull silently
  drops the string from every item that had it** — the tag looks retained until something rescans it
  away, and the timing depends on the freshness tier. Not a violation of invariant #6 as written; it is
  the invariant working as specified on a name that is no longer a JS tag. Either strip on delete
  (honest and immediate), or keep the string and say so in the delete confirmation, or amend
  `plan.md:301` to describe the real behaviour. Recorded as an explicit decision rather than a silent
  omission — the FR-197-4 posture.

## Non-goals

- **`update()`'s missing artwork/TMDB/language guards.** Real, and deliberately not fixed here: its
  only caller is `MediaRoutes.kt:359`, `store.update(emptyList())` — a library wipe, where there is no
  predecessor to preserve from. Noted so the next reader of the coverage table doesn't re-derive it.
- **A repair pass for tags already lost.** Nothing records what was lost, so there is nothing to
  restore from. Phase 188 is the standing reminder that a well-meant repair rescan is its own incident.
- **Genre provenance** (`mergeUserGenres`, Phase 94) — same file, same shape, different rule, and it
  reads `prior` correctly. Out of scope beyond the FR-199-2 extraction if it falls out naturally.
- **Any Ravilo-side work.** Tags reach Ravilo only through `visibleTo`/`ConditionEvaluator`, both of
  which read whatever the store holds.

## Verification

`compileKotlinLinuxX64` clean; `linuxX64Test` green including the new cases. No migration (no schema
change). Not live-verifiable without a slug rename occurring, so the rename case is proven by test,
not by observation — which is the argument for FR-199-2 in the first place.

## Related

- **Constitution invariant #6** — the rule under audit; amended by FR-199-6.
- **Phase 19 §15 / Phase 51** — where the tag lifecycle rule came from.
- **Phase 122** — the duplicate-slug cleanup that introduced the `stale`/`existing` split.
- **Phase 108** — the first guard to key off `old` (createdAt), and the comment at `:609-611`.
- **Phases 151 / 174 / 184** — the three guards that each fixed this hazard for their own field and
  left the tag guard behind. FR-199-3 copies their parameter contract verbatim.
- **Phase 196** — same file, same shape of finding: a field whose stored meaning had quietly drifted
  from what its consumer assumed.
- **R202 / R231** — the house case studies. R202 was a comment that lied; R231 was a spec that was
  right and an implementation that did half of it. This one is a third variant: **a comment that
  claimed a guard mirrored another guard, and three later phases that trusted the claim instead of the
  four lines below it.**
