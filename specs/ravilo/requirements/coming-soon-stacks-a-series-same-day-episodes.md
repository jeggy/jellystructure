# Coming Soon stacks a series' same-day episodes into one card

Task: DEB-2078

> Owner, 2026-10-21 (DEB-2078), with a screenshot of Discover ▸ Coming Soon on the TV, one day row holding six
> near-identical cards for one series' whole-season drop: *"Let's add some type of stacking here. And yes, let's
> create some designs for it first as well."*

## Scope

Ravilo clients that draw Coming Soon: Android TV, phone, desktop and web. They all draw it from one shared
`UpcomingContent` (`ravilo-ui` commonMain). Tizen and the Chromecast receiver have no Coming Soon. The backend gets
one additive field on the wire. Nothing else changes on the server.

Designed in two rounds, both drawn in the repo by Rannvá:

- round 11, `design/incoming/2026-10-22-coming-soon-stacks/`, directions A, B and C;
- its second revision, `design/incoming/2026-10-22-coming-soon-stack-shape/`, directions D to G.

**The owner picked A, the stacked edge (2026-10-23).** On 2026-10-22 he also fixed when a stack forms, what selecting
one does, and what the date strip counts. The investigation and every decision are on DEB-2078.

**Not in scope:**

- the time zone of the *04:00* on these cards. `UpcomingItem.time` is the UTC slice of Sonarr's `airDateUtc`, and
  `todayUtc()` decides *Today*. That is a separate fix, noted in *the-upcoming-badge-names-the-day.md*.
- the series detail page;
- the poster tile's upcoming badge;
- any day-header *N releases* line. The shipped app has none, and this spec adds none.

## Today (verified against `main`, 2026-10-23)

- `GET /api/tv/upcoming` returns `UpcomingFeed(enabled, items, missing)` (`shared/.../tv/Upcoming.kt`). It is a
  **flat** list: one `UpcomingItem` per Sonarr episode or Radarr film, sorted by date (`UpcomingService.splitFeed`).
- **No field says which series an episode belongs to.** `item_id` is the series' catalogue id, and is set only when
  the series is held. The Sonarr series id survives only inside the synthetic `id` (`ep-{seriesId}-{s}-{e}`), which
  is not a contract.
- `UpcomingContent` (`UpcomingScreen.kt`) groups `items` by `date` into one `StaticContentRow` of `UpcomingCard`s
  per day. The date strip's number is `grouped[day].size`. The *Missing (N)* pill is `feed.missing.size`.
- Selecting a card (`RaviloApp.kt`, the `Dest.Discover` upcoming handler):
  - an episode of a held series opens **Series detail**;
  - a held film opens **Movie detail**;
  - anything else opens `Dest.UpcomingDetail(item.id, …)`, R167's not-held detail with its `ScheduleGrid`.

## Requirements

### FR-1 — The server names each episode's series, additively
`UpcomingItem` gets `@SerialName("series_key") val seriesKey: String? = null`.

- It is set on every **episode** item, in `items` and in `missing`.
- It is **null on films**.
- Its value is an opaque string derived from the Sonarr series id (e.g. `"s{seriesId}"`). The client compares it
  for equality and never parses it. No source name reaches the client (R160).
- It is the same for every episode of one series, whether or not the series is held.

Nothing else on the wire changes. `id`, `item_id`, `date`, `time` and `status` keep their meaning, so an installed
app that predates this ignores the field and keeps drawing one card per episode. The `UpcomingFeed` wire baseline
(`shared/src/linuxX64Test/.../wire/WireRoots.kt`) moves with it.

### FR-2 — When a stack forms
The client builds the rows from the flat feed with one pure function, roughly
`stackUpcoming(items, byDay): List<UpcomingEntry>`. An entry is either a single `UpcomingItem` or a `Stack` of two
or more.

- **Day rows:** two or more episodes with the same stack key **on the same `date`** form one stack, whatever their
  times. One episode alone stays a single card. A film never stacks.
- **The *Missing* row:** two or more `missing` episodes with the same stack key form one stack, **whatever their
  days**.
- **The stack key** is `seriesKey ?: itemId`. An episode with neither (a server that predates FR-1, showing a series
  not held) **never stacks**.
- **Order:**
  - A stack takes the position of its **first** member in the feed's own order, and the members inside it keep the
    feed's order.
  - In the Missing row the feed is newest-first, so a stack sits where its most recent episode was.
- **Filter chips:** the All / Series / Movies chips filter first and stacking runs on what is left. *Movies* shows no
  stack.

### FR-3 — The card: direction A, the stacked edge
A stack is drawn as today's `UpcomingCard`, **at today's footprint**: 220 × 124 dp art on the TV, and the existing
sizes elsewhere. The front face gives up the width the deck needs. The card does not grow.

- **The deck:** two card edges peek out to the right of the front face. Each further edge is inset more vertically
  and dimmed more. Each edge is the series' own art (poster or gradient), dimmed, with a one-pixel light top edge,
  so the deck reads as cards seen edge-on and not as a shadow. Proportions are as drawn in round 11 A
  (`coming-soon-stacks.css`, `.cs-artface` / `.cs-edge`, 430 px stage card ≈ 220 dp):
  - the face is inset about 16 dp on the right;
  - each edge shows about 8 dp;
  - the vertical insets are about 7 dp and 14 dp.
- **Top-left:** the *Episode* badge, as today, then the stack's status (FR-4).
- **Bottom-left:** the stack's **earliest** air time (the smallest non-null `time` among its members). There is no
  time on a Missing stack.
- **Bottom-right:** a count badge, ***{n} episodes*** (`up.episodes_count`).
- **Under the art:**
  - the title;
  - the episode range, formatted from data: `S02E01–E08` within one season, `S01E10–S02E02` across seasons;
  - `network · year` as today.
  - On a Missing stack, the *Was due* line names the **earliest** due date.
- **The single-episode card is unchanged.**

### FR-4 — The stack's status
Worked out from its members' `status`, with `k` = the number `AVAILABLE` and `n` = the size:

| Members | Status pill |
|---|---|
| all `AVAILABLE` | *Already available* (`up.available`), as today |
| some, not all, `AVAILABLE` | ***{k} of {n} available*** (`up.available_of`), in the warning tint |
| all `MISSING` | *Missing*, as today |
| none `AVAILABLE`, none `MISSING`/`DOWNLOADING` | no pill, as today |

If any member is `DOWNLOADING`, its percent pill (as today) is shown **beside** the stack's status pill. If several
are downloading, the first in episode order is shown. The words carry the state; colour never does it alone.

### FR-5 — One focus stop, and Back returns to it
- A stack is **one** D-pad / keyboard / pointer target.
- Its focused state is today's card focus treatment: scale, ring and glow. The ring is drawn around the **front
  face**, and the deck stays visible behind it.
- Neither focus nor the deck changes the row's height or the card's width.
- The stack's restore key is:
  - `stack:{stackKey}:{date}` in a day row;
  - `missing-stack:{stackKey}` in the Missing row.

  It is stored in `lastSelectedItemKey` on select exactly as a single card's id is, so Back from whatever the stack
  opened lands on the stack.

### FR-6 — Selecting a stack opens what each of its cards opens today (owner, 2026-10-22)
- For a **held** series, it opens **Series detail**, exactly as each member does today.
- For a series **not held**, it opens `Dest.UpcomingDetail` for the stack's **first** member. That page's schedule
  then lists **every member of the stack**, one row each: `S01E01 · {weekday, day month} · {time}`. The single-item
  rows stay above the list.

The members come from the same `UpcomingFeed` the client already holds: the entries with the same stack key on the
same date, or in `missing` with the same stack key. There is no new route, and a reload of the detail's URL finds
them the same way. A not-held episode that is not part of a stack opens as today.

### FR-7 — What the counts count (owner, 2026-10-22)
**Unchanged.**

- The date strip's number per day counts **episodes**: an 8-episode drop adds 8, and that day's row shows one card
  for it.
- The *Missing (N)* pill counts episodes too.
- Neither number is derived from the stacked rows.

### FR-8 — Strings
Two new keys in `i18n/en.json`, each with a `note`, and drafts in `da.json` and `fo.json` marked for review the way
R279's drafts are. Wording is from round 11.

| Key | en | da (draft) | fo (draft) |
|---|---|---|---|
| `up.episodes_count` | `{n} episodes` | `{n} afsnit` | `{n} partar` |
| `up.available_of` | `{k} of {n} available` | `{k} af {n} tilgængelige` | `{k} av {n} tøk` |

`n` is always 2 or more, so the plural is the only form these keys need. The episode range is built from data, not
from a string.

**For translation review, not changed here:** Danish `up.episode` is *Episode* while the new count reads *afsnit*,
so a Danish stack shows both words.

## What the code makes cheap or expensive

- **Cheap overall.** FR-1 is one field set at the one place each episode item is built in `UpcomingService`. FR-2
  is one pure function, which is the thing to test. FR-7 needs no code, because `grouped` stays per item.
- FR-3 and FR-4 are the real work: a stacked variant of `UpcomingCard`. It is one component on every surface,
  because they all use this file.
- FR-6 touches `UpcomingDetailScreen`'s schedule and needs the store to find the stack's siblings in the feed.

## Acceptance

1. **Unit, `ravilo-ui` commonTest, `stackUpcoming`:**
   - two same-key episodes on one day make one stack, and a third on another day stays single;
   - a lone episode stays single;
   - films never stack;
   - `seriesKey` null with `itemId` set stacks by `itemId`;
   - both null never stacks;
   - Missing stacks across days;
   - a stack keeps its first member's position, and members keep feed order;
   - the filter-then-stack order holds.

   Test names have no commas (they run on linuxX64 too).
2. **Unit, the stack's status (FR-4):** all available; 4 of 6; all missing; none; one downloading beside *4 of 6*;
   two downloading shows the first.
3. **Unit, the earliest time and the episode range:** within a season, across seasons, and with a null `time` among
   the members.
4. **Backend:** `series_key` is present on every episode item in `items` and `missing`, equal across one series,
   held or not, and absent on films. Every other field is unchanged. The wire baseline is updated.
5. **Device (QA, TV and phone), on a household with a same-day multi-episode drop:**
   - the row shows one stacked card with *{n} episodes*;
   - the strip still counts every episode;
   - selecting it opens what the single cards opened;
   - Back lands on the stack;
   - a mixed stack reads *k of n available*.
6. **Back-compat:** an app built before this, pointed at the new server, still draws one card per episode.
