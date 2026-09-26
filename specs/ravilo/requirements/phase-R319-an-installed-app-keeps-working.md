# Phase R319 — An installed app keeps working: every server change is checked against the apps still in use

> Owner, 2026-09-26: *"now that we have multiple devices we need to be backwards compatible for a while
> with our apis etc"*, and after v1.41 broke Home on every installed app: *"Lets fix it. We need to be
> backwards compatible."*

## Status

`✓ Built` 2026-09-26 (see *Build notes* at the end). Written 2026-09-26. `shared`'s tests, a recording script, CI (it runs in the existing
`linuxX64Test`). No change to what any app or the server does. **Numbering:** verified against `STATUS.md`
the same day — Ravilo taken through **R318**.

## What happened

v1.41 (Phase R318) gave twelve enum fields the server sends a default, so that a *new* app reading an
unknown value falls back instead of failing. The server's `Json` leaves out any field that equals its
default. So from 22:57 to 23:18 on 2026-09-26, production sent 10 of 13 Home rows and 202 of 354 cards
without `kind`, and a channel without `style`. Every app installed before v1.41 has those fields as
required, so **Home failed to load on every TV and phone**. It was fixed by writing them always
(`@EncodeDefault(ALWAYS)`, v1.42).

Every test passed, because every test checked the *new* app against the *new* server. Nothing checked the
case that matters when a household runs several app versions at once: **an old app against the new server**.
The installs seen in the last 30 days (production, 2026-09-26) run 1.23, 1.35, 1.36, 1.39 and 1.42, plus
three devices too old to report a version.

## Requirements

**FR-R319-1 — A recorded wire contract per release.** For every response the apps decode and every request
they send (the types `TvApiClient` decodes and encodes, and the Home snapshot), a contract is recorded from
the serial descriptors:
- every field, with whether it is required and whether it may be `null`;
- its kind (string, integer, fraction, boolean, enum, list, map, object, polymorphic);
- for an enum, its values;
- for a polymorphic type, its subtypes.

Nested types are recorded inside their parents, so a renamed or moved class does not matter: the wire does
not carry class names.

**FR-R319-2 — One baseline for every release still in use.** The contracts of every release from a floor
(`WIRE_FLOOR`, today **v1.23**, the oldest release a device reported in the last 30 days) up to the
current release are merged into the strictest set of rules they imply:
- **Responses (server → app).** A field is required if any release required it. It is non-null if any
  release had it non-null. An enum value may be sent only if every release that has the field knows it.
  The kinds must match every release.
- **Requests (app → server).** A field is present only if every release always sends it. A value may
  arrive if any release sends it.

The merged baseline is committed. A script (`scripts/record-wire-baseline.sh`) regenerates it, by
recording each tag in a temporary worktree.

**FR-R319-3 — The check, in CI.** A test in `shared` walks the current models against the baseline and
fails on:
1. a field an old app requires that the current server may leave out. This is checked by **encoding**, not
   by reading annotations: a minimal payload is built from the current descriptor, read back leniently,
   and re-encoded exactly as the server encodes (`Json { ignoreUnknownKeys = true }`, no
   `encodeDefaults`). Every field an old app requires must come out. This is the check that would have
   caught v1.41;
2. a field an old app requires non-null that may now be `null`;
3. a changed kind (a string that became a number, a list that became an object);
4. an enum value an old app does not know, unless it is listed as **never sent**, with the reason (today:
   `RowKind.RECOMMENDED`, which `forClients()` maps to `CUSTOM`, Phase 269);
5. a new subtype of a polymorphic type an old app decodes;
6. for a request: a field the server now requires that an old app may not send, a field that must no
   longer be `null` where an old app sent `null`, or a value an old app sends that the server no longer
   knows.

Each failure names the field's path, the rule, and the releases it would break.

**FR-R319-4 — Moving the floor is deliberate.** Dropping support for old apps means editing `WIRE_FLOOR`
and regenerating the baseline, in a commit that says which versions stop being supported. Nothing moves it
by itself.

**FR-R319-5 — Tests of the check itself.** The checker is tested on invented types:
- a required field given a default and not always written → caught;
- the same field with `@EncodeDefault(ALWAYS)` → passes;
- a new enum value → caught unless listed as never sent;
- `String` → `Int` → caught;
- a new required request field → caught;
- a new optional field → passes.

## Non-goals

- Running old app builds against the server (the contract check is the fast, always-on version of that).
- The server's WebSocket event strings that are built by hand, not through `shared`'s types.
- The admin frontend: it ships with the server, so it is never older than the server.

## Acceptance

1. With the fix reverted (the twelve `@EncodeDefault` removed), the check fails and names `Row.kind`,
   `MediaCard.kind` and `Channel.style`.
2. With v1.42's models, the check passes.
3. The committed baseline covers v1.23–v1.42, and the script reproduces it.

## Build notes (2026-09-26)

Built the same evening, on top of v1.42's fix.

1. **Where it lives:** `shared/src/linuxX64Test/kotlin/dev/jellystructure/shared/wire/`, so it runs in CI's
   existing `linuxX64Test` (the `unit` job) with no workflow change.
   - `WireContract.kt`: recording and merging.
   - `WireCheck.kt`: the rules.
   - `WireRoots.kt`: the 57 payloads (37 responses, REST and WebSocket; 20 requests), generated by
     `scripts/wire_roots.py` from what `TvApiClient` decodes and encodes.
   - `WireBaseline.kt`: the merged baseline, generated.
   - `WireCompatTest`: the check, and its self-tests.
   - `WireContractRecordTest` / `WireBaselineMergeTest`: no-ops unless the script sets their environment.
2. **Why the dynamic half exists:** `@EncodeDefault` is not visible in a serial descriptor (probed). A
   static look therefore cannot tell a silenced default from a written one. The check builds a minimal
   payload per root and leaves optional scalars and enums out, so they take their defaults. Optional
   containers are filled, so nested types are reached; a list of a sealed type gets one element per subtype,
   under the type's own class discriminator (`QueryNode` uses `kind`, read from `@JsonClassDiscriminator`).
   The payload is read back with `RaviloWireJson` and re-encoded with the server's `Json`. A required
   container given a default cannot be seen that way, so the static half reports it.
3. **Baseline:** v1.23–v1.42, 20 releases, each recorded in a temporary worktree (about 30 s each), merged
   to 69 KB.
4. **Acceptance:**
   1. With the twelve `@EncodeDefault(ALWAYS)` removed, the check fails with 42 violations, among them
      `HomeFeed.rows[].kind`, `HomeFeed.rows[].items[].kind`, `HomeFeed.channels[].style` and
      `RaviloConfig.rows[].kind`, which are the v1.41 breakage;
   2. with them, it passes;
   3. the script reproduced the baseline from the tags.
5. **Self-tests** (7 in total with the real check): a silent default at the top level and inside a list; a
   written one passes; a new enum value; a changed kind; a new `null`; a new optional field passes; a
   request the server requires more of; a request that loses its `null`; the merge keeping the strictest
   rule.
6. **After each release,** rerun `scripts/record-wire-baseline.sh` and commit `WireBaseline.kt`, so the
   newest apps' requirements join the baseline.
