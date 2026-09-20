# Phase 251 — Jellyfin 12 stopped sending the lock fields, and nothing noticed

## Status

`✓ Built` 2026-09-20 — **found by phase 240's model guard on its first real run** against a seeded
12.1 container, which is exactly the failure class FR-240-4 was written to catch. Spec written first,
then built the same day. Not dev-reviewed.

### Build (2026-09-20)

- **FR-251-1** — `JellyfinClient.getItemLocks` reads `GET /Items/{id}?userId=…`, the shape that
  carries the fields, and `/api/media/{id}/jellyfin-locks` uses it. The route takes the user id from
  the caller's own session (that route 400s without a user context — phase 207's finding); a lock is
  a property of the item, so which user asks does not change the answer.
- **FR-251-2** — `JellyfinItem.lockData`/`lockedFields` became **nullable**, so "absent" and "nothing
  is locked" stop being the same value, and `preserveJellyfinLockState` carries the stored state
  forward at both `MediaStore` write choke points. The Scanner stops writing the fields at all — it
  reads the list shape, which cannot carry them, so anything it wrote would be a guess, and the guess
  it made was "nothing is locked". Same reasoning phase 153 applied to `nfoWrittenAt`/`nfoHash`/
  `jfSyncedAt` one field over. `updateOne` gains `respectJellyfinLocks = true`; exactly one caller
  passes `false`, and it is the one that has just read the detail shape.
  The locks route also stops persisting a negative answer when the read fails: **unknown is not
  false**, and inventing one from the error path would be the same bug wearing a different hat.
- **FR-251-3** — `DateLastSaved` is sent on **no** shape on 12.1, with or without an explicit
  `Fields=DateLastSaved`. Open question 1's honest branch taken: nothing guesses a replacement, and
  the admin's *Jellyfin → Updated* row gains a help tip saying the blank means **not reported**, not
  *never updated*. The field stays declared so a future server's value flows through unchanged.
- **FR-251-4** — the model guard probes the **detail** shape for the lock fields (a `Model@variant`
  row), so `LockData`/`LockedFields` are checked for **presence**, not exempted. If a future Jellyfin
  drops them there too, CI says so.
- **FR-251-5** — no migration: FR-251-1 and FR-251-2 converge on their own, since a real value is now
  read and written and a wrong one is no longer written over it.
- **Tests:** `JellyfinLockStateTest` (5, green) pins the guard **and** the nullability that makes it
  possible — including that `JellyfinItem()` with nothing set leaves all three fields null.

Acceptance 1–3 need a real 12.x server with a lock set and are verified on deploy; 4 passes against
the seeded container; 5 holds.

## What is wrong

Three fields this product reads from Jellyfin are **no longer sent on the endpoint shape it uses**,
and because all three have Kotlin defaults, nothing failed, nothing logged, and the values silently
became `false`, `emptyList()` and `null` everywhere.

Measured against a fresh Jellyfin **12.1.0** (2026-09-20, `scripts/jellyfin-test-server.sh`), with
the lock deliberately **set** on the item first (`LockData: true`, `LockedFields: ["Name"]`, accepted
with 204):

| field | `GET /Items/{id}?userId=…` | `GET /Items?Ids=…&Fields=…` (what the product uses) |
|---|---|---|
| `LockData` | **present** (`true`) | **absent** |
| `LockedFields` | **present** (`["Name"]`) | **absent** |
| `DateLastSaved` | absent | absent |

The list form is asked for them explicitly — `Fields=Path,ProviderIds,ProductionYear,LockData,LockedFields,Tags,DateCreated,DateLastSaved,SortName,SeriesId`
— and answers without them anyway. The detail form returns the two lock fields **regardless of
`Fields`**, so this is a change in which endpoint carries them, not a change in whether Jellyfin
knows them.

### What that breaks, concretely

**1. The Jellyfin field-lock banner is always empty, and it overwrites the truth on its way past.**
`GET /api/media/{id}/jellyfin-locks` (`MediaRoutes.kt:1554`) calls `jellyfinClient.getItem`, which is
the **list** shape (`JellyfinClient.kt:437`). So `lockData` reads `false` and `lockedFields` reads
`[]` for every title, always — and the route then does
`store.updateOne(item.copy(jellyfinLockData = false, jellyfinLockedFields = emptyList()))`, writing
that wrong answer into jellystructure's own database over whatever was correctly recorded before.
Opening a media detail page is enough to do it.

The banner exists to tell an operator *"Jellyfin has locked these fields, so your edit will not
stick"*. It now says nothing, on a server where locks genuinely exist.

**2. Every scan writes the same wrong values.** `Scanner.kt` sets `jellyfinLockData`,
`jellyfinLockedFields` and `jellyfinUpdatedAt` from the list payload at four sites (`:422`, `:508`,
`:803`, `:862`). Since the 12.1 upgrade every scanned item has had all three cleared.

**3. `jellyfinUpdatedAt` is now always null.** It comes from `DateLastSaved`, which 12.1 does not
send on **either** shape. Anything that compares jellystructure's own timestamps against Jellyfin's
last-save is comparing against nothing.

### Why it was invisible

`JellyfinItem` declares all three with defaults:

```kotlin
@SerialName("LockData") val lockData: Boolean = false,
@SerialName("LockedFields") val lockedFields: List<String> = emptyList(),
@SerialName("DateLastSaved") val dateLastSaved: String? = null,
```

A field with a default that stops arriving is indistinguishable from a field that arrived with its
default value. There is no error, no warning and no log line — the product simply believes nothing is
locked. This is the "SILENT" bucket phase 240's FR-240-4 insisted be reported as loudly as the
throwing one, and it is the same shape as phase 246's `TranscodingTempPath` move, which printing the
unset value as "(default)" concealed.

## Requirements

**FR-251-1 — Read the lock fields from the shape that carries them.** `getItem` fetches the two lock
fields from `GET /Items/{id}?userId=…`, which returns them unconditionally on 12.1. The user context
is required by that route (phase 207's finding), so the call needs a user id; the server's own admin
user is the correct one, since a lock is a property of the item and not of a viewer.

**FR-251-2 — A scan must not clear a lock it cannot see.** Where the value is **unknown** rather than
**known-false**, the existing stored value is kept. `false`/`emptyList()` may only be written when
Jellyfin actually said so. `JellyfinItem`'s three fields therefore need to distinguish "absent" from
"false" — `lockData: Boolean?` and `lockedFields: List<String>?`, null meaning *not carried by this
payload*, with every write site keeping the prior value on null.

This is the requirement that matters most. Without it, correcting FR-251-1 still leaves four scanner
sites quietly zeroing the field on every pass.

**FR-251-3 — `jellyfinUpdatedAt` stops claiming to be Jellyfin's last-save time.** 12.1 sends
`DateLastSaved` nowhere. Either a replacement field is found on a payload the product already
fetches, or the column stops being written and whatever reads it is changed to say it is unknown. A
timestamp that is silently always null is worse than an absent one, because code still compares it.

**FR-251-4 — The model guard covers this permanently.** `scripts/check-jellyfin-models.sh` keeps
these three fields in its checked set with no exemption, so if a future Jellyfin drops them from the
detail shape too, CI says so. The `CONDITIONAL_FIELDS` allow-list may only carry fields that are
absent **because there is genuinely nothing to report**, each with its reason.

**FR-251-5 — Repair what was overwritten.** Items whose stored `jellyfinLockData` /
`jellyfinLockedFields` were zeroed since the upgrade are re-read from the correct shape on the next
scan. No separate migration: FR-251-1 plus FR-251-2 converge on their own, since a real value will
now be read and written.

## Non-goals

- Changing what a lock *means* to jellystructure. Phase 151's guarantee is unchanged; this phase is
  about being able to see the lock at all.
- Migrating other call sites off the list shape. `getItems`, `getItemsByIds` and the scanner's own
  sweeps are correct for their purpose — they want many items cheaply, and only the lock fields are
  affected.
- Asking Jellyfin to put the fields back.

## Acceptance

1. With a lock set on an item in a 12.x server, `GET /api/media/{id}/jellyfin-locks` reports it.
2. Opening a media detail page for an item with no lock does not clear a lock recorded for a
   different item, and opening one for a locked item does not clear its lock.
3. A scan against a server that does not carry the fields leaves previously-recorded lock state
   alone rather than zeroing it.
4. `scripts/check-jellyfin-models.sh` passes against a seeded 12.x container with no exemption added
   for `LockData`, `LockedFields` or `DateLastSaved`.
5. Nothing reads `jellyfinUpdatedAt` as a real timestamp while it cannot be populated.

## Open questions

1. Is there a 12.x field that carries what `DateLastSaved` used to? `DateLastMediaAdded` and
   `DateModified` exist on some item types but mean different things, and guessing here is how a
   comparison starts lying again. Until one is confirmed, FR-251-3's second branch (stop writing it)
   is the honest answer.
2. Did 12.0 behave this way too, or only 12.1? It does not change what gets built — the product
   targets 12.0+ and carries no version branches (243 FR-243-4) — but it would date the regression,
   which matters for judging how much stored lock state was overwritten.
3. Should the detail-shape read be batched for the scanner? Today the scanner never needs it
   per-item, so no; if a future phase wants lock state during a full sweep, one request per item is
   the wrong shape and a different endpoint should be found first.
