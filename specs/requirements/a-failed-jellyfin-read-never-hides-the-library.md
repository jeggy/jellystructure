# A failed Jellyfin read never hides the library, and an empty library is noticed

Task: DEB-5709

> Owner, 2026-11-27 (DEB-5709 card): chose all three recommendations — a failed read flags nothing and one
> scan never flags more than half the library, a real mass removal being cleared by hand; a visible-titles
> count on `/api/health` with an Uptime Kuma check on demo and prod that pushes to his phone at 0; and a daily
> Find on the demo.

## Scope

Backend, one line and one button on the admin's Library page, a counts-only addition to `/api/health`, and
two estate steps (Uptime Kuma, the demo's config) that are not Kotlin. No Ravilo client change and no change to
anything a client reads: `liveItems()` keeps its meaning, and the new fields are additive.

It narrows Phase 95's *no longer in Jellyfin* rule (carried forward in Phase 175 §5): a title is still kept and
flagged when Jellyfin stops listing it, but only when the scan could actually read Jellyfin and the answer is
plausible.

## Why

From 2026-11-12 to 2026-11-25 the demo showed every account an empty film and series library, and nothing
noticed. The finding on DEB-5709:

- `JellyfinClient.getItems` turns any failed read (a 503, a timeout, a refused connection) into an **empty
  list**. `runScan` guards against a `null` read, which never comes, so a full scan calls
  `flagMissingFromSource` with nothing present and flags every title. `liveItems()` then hides them all from
  Ravilo. Music was spared: its read is all-or-nothing and returns `null` on failure (FR-275-2).
- Since v1.67 (DEB-4387) a Find that missed a fire runs at boot. The demo's compose file recreates the backend
  and its Jellyfin together, and that Jellyfin answers 503 for its first second or so. The run then recorded
  itself as Find's last run, so with a quarterly schedule the next fire was in January.
- Prod runs the same code hourly at :30. One failed or empty `/Items` answer there hides every household film
  and series for about an hour.

The same class was fixed for Jellyfin's users in `a-device-whose-jellyfin-user-is-gone-is-signed-out-and-removed.md`,
and `getItemIdsByTag` (DEB-2518 FR-7) already says it: `null` is a failed read, never "none".

## Requirements

### FR-1 — A failed read is not an empty library

`JellyfinClient.getItems` returns `null`, not an empty list, when the answer is not 2xx, the request throws
(timeout, refused connection, TLS failure), or the body does not parse. A `CancellationException` still
propagates. It keeps its one warning line. A successful read that lists nothing is still an empty list:
FR-3 handles that case.

### FR-2 — A full scan whose read failed changes no flag and says so

When the full scan's read is `null`:

- no title is flagged or unflagged, and no title is written;
- Activity gets one **error** line in category `scan`: *"Scan stopped: Jellyfin's library could not be read,
  so nothing was marked missing. The next scan tries again."*;
- the run ends as failed (its `Finished` event counts one failure), not as a clean run of zero items;
- Find is **not** recorded as having run (`ProcessingRuns.record(FIND)` is skipped), so the admin's
  *last ran* keeps its previous value and a boot catch-up is still due on the next start.

The steps after `scan_files` in the same pipeline run as today on the titles already held.

### FR-3 — One scan never flags more than half the library

On a full scan whose read succeeded, let **held** be the titles with a Jellyfin id that are not already flagged,
and **gone** the ones among them whose id the read did not list. If `gone > held / 2`, or the read listed no
items while `held > 0`, the scan **flags none of them** and:

- still unflags any flagged title the read did list, so a title that returns always comes back;
- writes one **error** line to Activity in category `scan`: *"Scan: Jellyfin no longer lists {gone} of {held}
  titles. More than half at once looks like a failed read, so none were marked missing. If they really were
  removed, use Flag them missing on the Library page."*;
- sets the hold (FR-4).

Otherwise the scan flags exactly as today and clears any hold. The rule is the same for a scan started by hand,
on a schedule or at boot. Per-library scans keep skipping the flag step altogether, as today.

Worked cases: with 23 held, a read of 0 or 11 is held back (23 and 12 gone); a read of 12 flags the 11 gone;
with 1 held, its disappearance is held back and is flagged by hand.

### FR-4 — The hold is visible and is released by hand

- The server keeps the last hold in memory: `{ at, gone, held }` (epoch seconds and two counts). It needs no
  table: after a restart, the next full scan sets it again if it still applies.
- `GET /api/triage/count` gains an optional `heldBack` object with those three fields. It is `null` or absent
  when no hold stands. This is an additive admin field.
- While `heldBack` is set, the admin's **Library page** shows one line at the top, whatever filter is chosen. It
  uses the Live TV lineup banner's markup and classes (`.note` with the warn tint, `.badge warn`, `.tiny`,
  `.btn sm`), so it needs no new CSS and no design round:
  - badge *Held back*;
  - text *"Jellyfin stopped listing {gone} of {held} titles {ago}. Viewers still see them. If they really were
    removed, flag them missing."*;
  - button **Flag them missing**.
- The button calls `POST /api/triage/held-back/flag` (admin session). The server reads Jellyfin again right
  away.
  - If that read fails, it answers 503 with the FR-2 sentence, flags nothing, and the note shows that sentence
    under its text.
  - If the read succeeds, the server flags every held title the read does not list, without the floor, clears
    the hold, and writes an Activity info line: *"Flagged {n} titles missing by hand."*
  - The page then re-reads `/api/triage/count`; it shows no state of its own.

### FR-5 — `/api/health` carries the catalog's counts

`GET /api/health` (unauthenticated, as today) gains
`"catalog": {"titles": N, "missing_from_source": M}`:

- `N` is the count of `liveItems()`: what Ravilo shows.
- `M` is the count of flagged titles.

These are counts only, like the `music` and `audiobooks` blocks beside them. Both come from the store's
in-memory list; the endpoint still makes no outbound call.

### FR-6 — Uptime Kuma pushes when a viewer would see an empty library (estate)

Add two `json-query` monitors to `~/uptime-kuma/monitors/`, in the shape of `disk-*.toml`. Each uses `url` =
that host's `/api/health`, `json_path = '$number(catalog.titles)'`, `json_path_operator = '>'` and
`expected_value = '0'`:

| File | Host | Group | Alert |
|---|---|---|---|
| `product-jellystructure-catalog.toml` | `https://jelly.jebster.net` | `group-product` | the group's |
| `dash-demo-jellystructure-catalog.toml` | `https://demo.jellystructure.jebster.net` | `group-dashboard` | `notification_name_list = ['phone']`, because that group never alerts |

Every 300 s, retry 60 s, 2 retries, so a scan in progress or a restart does not page. Add each monitor only once
its host's `/api/health` carries `catalog`. Then `./sync.sh`.

### FR-7 — The demo looks for new and changed titles daily (estate)

The demo's Find schedule becomes daily at 04:00 in its zone (`0 4 * * *`). Set it in both `scan_schedule` and
`[processing.find] schedule` of `~/jellystructure/demo/config/config.toml`. Edit with the backend stopped, as
the demo's `AGENTS.md` says, because the running process writes its old value back. This needs no build. With
FR-1–FR-3 in place, it only makes the demo pick up a real change within a day.

## Proof

- **FR-1:** `linuxX64Test`, a `JellyfinClient` against a mock engine. A 503, a thrown timeout and an
  unparseable body each give `null`; a 200 with no items gives an empty list.
- **FR-2:** a scan given a `null` read leaves 23 stored titles unflagged, writes the error line, ends with one
  failure, and does not record Find.
- **FR-3:** the worked cases above, plus a flagged title that the held-back read lists comes back unflagged.
- **FR-4:**
  - The count route carries `heldBack` after a held-back scan and drops it after a normal one.
  - The flag route flags and clears on a good read, and answers 503 and flags nothing on a failed one.
  - The Library line is checked in a Playwright admin spec against a backend fixture with a hold set.
- **FR-5:** `/api/health` carries `catalog` with the right counts for a store holding visible and flagged
  titles.
- **FR-6, FR-7:** estate. A `curl` of each host's `/api/health` shows `catalog.titles > 0`. Kuma shows both
  monitors up after `./sync.sh`. The demo's Settings page shows Find as daily.

## Out of scope

- A recommendations build over an empty catalog counts as "never built", so it re-ran every minute while the
  demo was empty. That stops by itself once the library is visible, and FR-3 keeps the library visible.
- A retry sooner than the next fire after FR-2. The library stays as it was, so nothing is lost by waiting.
