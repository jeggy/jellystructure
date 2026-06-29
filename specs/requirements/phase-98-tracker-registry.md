# Phase 98 — Tracker registry: name a tracker, map its announce-host mirrors (FR-XS2)

> **Status: Planned** (design done, not built). Extends **[Phase 97](phase-97-seeding-cross-seed-surface.md)**
> (seeding surface) and lives on the **Metadata** page next to Studios / Networks / Genres / Tags
> (Phase 82). It is the lookup table the seeding surface resolves torrents against.

## Problem
A torrent announces to exactly **one** tracker, but a tracker frequently publishes **several announce
URLs** — mirrors / load-balanced hosts — and qBittorrent does **not** list them in a stable order. The
same release can therefore show any of:
```
https://t.polarswarm.org/announce/0021b118ead08ed41ba7ef0066619d3e
https://t.nordicswarm.org/announce/0021b118ead08ed41ba7ef0066619d3e
https://t.nordicbytes.org/announce/0021b118ead08ed41ba7ef0066619d3e
```
…all of which are **the same tracker**. Without a mapping, the Phase 97 seeding view would treat each
host as a different tracker, miscount "how many trackers", and split one tracker's torrents across
several rows. There's no place to say "these hosts are all *NordicHD*".

## Goal
A **Trackers** registry on the Metadata page where an operator defines each tracker once — a friendly
**name**, a **private/public** type, and the set of announce **hosts** that belong to it. The seeding
surface (Phase 97) resolves every torrent's announce URLs to a single named tracker by host, regardless
of which mirror (or order) qBittorrent reports. Hosts seen in torrents but not yet mapped are surfaced
as **unmapped**, one click from being named.

## Core rule (operator's framing)
- **A torrent belongs to one tracker.** If it carries multiple announce URLs, they are all the *same*
  tracker — just different URLs. Resolution therefore needs only to match **one** of a torrent's
  announce hosts to a tracker; all the rest are mirrors of it.
- **Match on host only.** The passkey segment in the announce path is per-user and is **ignored** for
  matching (and never displayed/stored as identifying).

## Requirements

### A. Trackers tab (Metadata page — `metadata.html`, `data-tab="trackers"`)
1. A new **Trackers** tab after Tags, with an intro explaining the mirror problem and that the seeding
   view resolves against this table.
2. **Tracker cards** (`meta-grid`), each showing:
   - **Name** + **private/public** badge, and an **Edit** affordance.
   - **Announce hosts** list — one row per mirror host (mono), each removable (✕), plus an
     **add-mirror-host** input. **No host is “primary”** — they are simply the set of URLs the tracker
     exposes; resolution matches on any of them.
   - **Usage**: a link — `N torrents · M items seeded →` — to the Library filtered to that tracker
     (`library.html?tracker=<name>`, requirement D).
3. **"＋ New tracker" modal**: name, private/public segmented control, and a **multi-line announce-hosts**
   field (one host per line; all mirrors of the tracker). A note states match is host-only, passkey
   ignored.

### B. Unmapped announce hosts
1. Below the cards, an **"Unmapped announce hosts"** list: every announce host observed in a torrent
   that matches no tracker, with how many torrents / items reference it.
2. Each row offers **Assign to → {existing tracker}** (adds the host as a mirror of that tracker) or
   **＋ Name as new tracker** (opens the New-tracker modal pre-filled with the host).

### C. Resolution contract (consumed by Phase 97)
1. `resolveTracker(announceUrls)` → `{ name, private, matchedHost, mirrorCount, unmapped }`:
   - take each URL's **hostname**; return the first tracker whose host set contains any of them.
   - `mirrorCount` = number of announce URLs on the torrent (for the "+N mirrors, same tracker" note).
   - no match ⇒ `unmapped: true`, `name` = the bare host (so the UI can still show *something* and link
     to "name this tracker").
2. The Phase 97 seeding surface uses this for: the **tracker column / badge** (resolved name +
   private/public), the **"trackers" count** in the summary + pill (distinct resolved names), the
   per-torrent **Announce** row (`via <host> (+N mirrors, same tracker)`), and the **unmapped** state
   (amber `unmapped` badge + `name this tracker →` link to `metadata.html#tab=trackers`).

### D. Library tracker filter (NOT a workbench facet)
1. The **Library** page (`library.html`) gains a **“seeded on” tracker filter** — a dedicated library
   axis alongside the existing quick chips (All / Needs attention / Missing artwork), **separate from**
   the shared workbench builder. The Phase 30/32 workbench facets stay **Studio · Network · Genre ·
   Tag** only; tracker is intentionally *not* added there (seeding membership is qBittorrent state, not
   a library taxonomy, and isn't reusable as a Ravilo row/channel facet).
2. The control offers **off**, **any tracker** (seeded anywhere), or a specific named tracker. When
   active it ANDs with whatever else is set (quick chip / search / workbench conditions).
3. **Deep-link**: `library.html?tracker=<name>` opens the page with the filter pre-applied — this is
   what the Metadata usage link (A2) targets.
4. Membership comes from the same qBittorrent→registry resolution (C): a title is “seeded on T” if any
   torrent whose `covers` includes the title (or any of its episodes) resolves to tracker T.

### E. Config / persistence
A new optional top-level section, following the `[qbittorrent]` / `[radarr]` opt-in pattern; absent ⇒
no named trackers (everything resolves as unmapped, which is non-fatal and purely cosmetic):
```toml
[[trackers]]
name = "NordicHD"
private = true
hosts = ["t.nordicswarm.org", "t.polarswarm.org", "t.nordicbytes.org"]
```
`GET/PUT /api/config` round-trips `[[trackers]]`. No secrets here (hosts only); nothing masked.

## Invariants
- **No primary host** — a tracker owns an unordered set of mirror hosts; live data has no canonical
  “main” URL and qBittorrent doesn't order them stably.
- **Host-only matching** — passkey path is ignored, never used as an identifier.
- **One tracker per torrent** — resolution short-circuits on the first matching host; remaining hosts
  are recorded as that tracker's mirrors, never as separate trackers.
- **Unmapped is non-fatal** — an unknown host degrades to a cosmetic "unmapped" label; it never blocks
  seeding reads or edits, and never invents a tracker.
- **Read-only against qBittorrent** — the registry is operator-authored metadata; defining/editing a
  tracker never touches torrents or qBittorrent state (scope fence, Phase 54).
- **One taxonomy** — trackers are a first-class metadata facet alongside Studio/Network/Genre/Tag, not a
  parallel store; the seeding view is the only consumer for now.

## Out of scope
- Auto-learning/merging mirrors from observed swarms (operator defines them explicitly here).
- Per-tracker rules (ratio targets, seed-time minimums, auto-actions) — a possible later phase.
- Adding tracker as a **shared workbench facet** (Studio/Network/Genre/Tag) or a Ravilo row/channel
  filter — the Library “seeded on” filter (D) is deliberately a standalone library axis only.

## Design reference
- `design/app/metadata.html` — the **Trackers** tab: NordicHD (3 mirror hosts, add-host), FilmBytes
  (single host), OpenTrackers (public, 2 hosts), the unmapped-hosts list, the New-tracker modal, and the
  `N torrents · M items seeded →` link into the Library.
- `design/app/library.html` — the **“seeded on”** tracker filter (off / any / named), deep-linkable via
  `?tracker=`, ANDing with the quick chips / search / workbench.
- `design/app/seeding.js` — `resolveTracker()` + `TRACKERS` table; torrents carry `announce[]` URLs
  (deliberately in different mirror orders) and resolve to one named tracker; one torrent
  (`t.newswarm.io`) is intentionally unmapped to show that state on the seeding card.
