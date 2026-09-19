# Phase R270 — AirPlay is a footnote, and a busy TV names its viewer

> Owner picks, 2026-09-18, made against the round-1 frames in
> `design/ravilo/Play on a TV - Directions.html`. Two decisions about the *"Play on a TV"* sheet
> (R265), both now built into `design/ravilo/Ravilo Mobile.html`:
> **1.** AirPlay is **not** a full row with the notice on a second line. It is a **footnote-weight
> single line** under the collapsible — *"AirPlay (phone must stay on)"* — so the caveat rides inside
> the label and the tier cannot be mistaken for the recommended route. **2.** A screen someone else is
> using **names them**: *"Busy · {user} is watching"*, and *"Offline · last seen {when}"* keeps its
> weekday, because both are the same class of disclosure and a household of four sharing three TVs is
> better served by an answer than by a second tap.

## Status

`Planned` — written 2026-09-18, **dev-reviewed 2026-09-19 against `main` `dcb97f2c`**. ⚠ **FR-R270-1 is
already built (R265) and FR-R270-3 is built wrong — it names the wrong household member on `main`
today; and naming the viewer needs a route + DTO change, so the "client only" claim below does not
hold.** See §Dev review at the foot. Client only (`ravilo-ui` commonMain: the sheet's
tier-3 row and its row-state strings; wasmJs: nothing new — the AirPlay seam of R265 FR-R265-4 is
unchanged). No backend, route, DTO or config change. **Supersedes R265 FR-R265-4's row shape and
FR-R265-9's `screens.airplay_notice`**; confirms FR-R265-2's busy/offline copy as shipped wording.
Sibling of **R269** (the receiver's server), from the same round.

**Numbering:** verified against `main` and `STATUS.md` on 2026-09-18 — Ravilo taken through **R265**,
with **R266 · R267 · R268 · R269** ours from the same day; admin through **236**, with **237** ours.
Ravilo-only, no admin pair. Next free: **238 / R271**.

Design: built into `design/ravilo/Ravilo Mobile.html` (the `sc-*` layer in
`design/ravilo/mobile/ravilo-mobile-player.css`); both alternatives for each decision are drawn side by
side in `design/ravilo/Play on a TV - Directions.html` §Q1/§Q2, with the rejected one and its reason
kept on the canvas.

## Requirements

**FR-R270-1 · The AirPlay tier is one footnote line.** Where the browser reported AirPlay availability
(R265 FR-R265-4's probe, unchanged), the sheet's last tier is a **single row of footnote weight**: the
AirPlay glyph at `ink-dim`, the label *"AirPlay (phone must stay on)"* at 13.5 sp, and above it a
`line-2` hairline — one visual step below the TV rows. The label's ink is **`ink-soft`, not `ink-dim`**:
it is an interactive label at 13.5 sp and has to clear 4.5:1 on the sheet's ground (R234's floor), so
the row is made quieter by size, weight, the missing chevron and the hairline rather than by ink alone.
It is still a **46 px target** (R234's floor is not waived for a quiet row) and still **absent, never
greyed**, where AirPlay was not reported. No second line, no tooltip, no info glyph.

**FR-R270-2 · The full notice is shown once, at the moment it becomes true.** Because the footnote
compresses the caveat, the sentence *"Your phone has to stay on and in Ravilo — the TV stops when you
close the app."* is not dropped: it appears as the **connecting bar** the instant an AirPlay session
starts (*"Playing on {TV} · keep Ravilo open"*, retiring after ~2.5 s), and in full on the Settings →
Connections help text for the household's admin. Everything else about an AirPlay session is R265
FR-R265-4 as written: Apple's picker, no mini bar, no remote, **the phone's own player is the remote**,
and the glyph in its connected form naming the target.

**FR-R270-3 · A busy screen names the person.** `GET /api/remote/devices` already carries the user for a
screen in use (236's 409 names them; the list must name them the same way). The row reads *"Busy ·
{user} is watching"* — display name as the household knows it, never a username or an id — dimmed and
not tappable. A screen playing something **this** profile started reads *"Playing {title}"* and **is**
tappable, opening the remote.

**FR-R270-4 · Offline keeps its weekday.** *"Offline · last seen {when}"*, where `{when}` is a weekday
inside the last seven days (*Tuesday*), a date beyond that, and *"just now"* is never shown — a screen
seen seconds ago is not offline. Never a timestamp, never a duration.

**FR-R270-5 · One decision, two rows.** Busy and offline are one disclosure choice, not two: if a
future guest-mode or lodger scenario ever needs anonymity (R265's open question territory), **both**
rows lose their detail together — *"In use"* and *"Offline"* — and the change is one flag, not two
strings edited apart. Record it that way so the pair cannot drift.

**FR-R270-6 · Strings.** `screens.airplay_footnote` *AirPlay (phone must stay on)* replaces
`screens.airplay` + `screens.airplay_notice` in the sheet; `screens.airplay_notice` is **kept** for
FR-R270-2's bar and the admin help text. `screens.busy` *Busy · {user} is watching* and
`screens.offline` *Offline · last seen {when}* stand as R265 wrote them. All × en/da/fo; drafts live in
`design/ravilo/ravilo-i18n.js`, the shipped table wins where a key exists.

## Acceptance

- iPhone, Safari, one paired Tizen screen and an AirPlay-2 TV in the room: the sheet shows the TV row,
  the collapsible, then the AirPlay footnote — measurably 46 px tall, `ink-dim`, below a hairline.
- Tapping it opens Apple's picker; on selection the bar says *Playing on {TV} · keep Ravilo open* and
  retires; the player is the only control surface; no mini bar appears; the glyph is connected.
- Pixel 9 (app or PWA): no AirPlay row and no hairline where one would be.
- Two users, one TV: B's sheet shows *Busy · Eydun is watching*, dimmed, and a tap does nothing; B's
  play attempt (if made another way) is refused with the same name — the list and the 409 agree.
- A screen last seen six days ago reads a weekday; eight days ago reads a date.
- `scripts/check-phases.sh`, `check-mobile-css.sh` green.

## Non-goals

- No change to the AirPlay transport, the seam, HLS subtitle delivery, or anything else in R265
  FR-R265-4/-8. This phase is the row's weight and the rows' words.
- No AirPlay on Android, no AirPlay mini bar or remote (R265 non-goals stand).
- No per-user privacy setting in this phase (FR-R270-5 only records how it would be done).

## Open questions

1. **Does the footnote survive a household with many TVs?** With tier 2 collapsed it is always visible;
   expanded past ~6 screens it can fall below the fold. Lean: pin the footnote and *Add a TV* to the
   sheet's bottom edge rather than letting them scroll with the list.
2. **Does the admin help text need the notice verbatim**, or a shorter version? Lean: verbatim — it is
   the one place with room for it.

## Dev notes

- The design canvas keeps the rejected full-row drawing on purpose: if the first real AirPlay session in
  the house ends badly enough that the owner wants the caveat louder, the alternative is drawn and this
  FR is one swap, not a new round.
- FR-R270-3's name comes from the same field 236's 409 uses; if that field is ever absent for privacy
  reasons, the row must fall back to *"In use"* rather than rendering an empty *"Busy · is watching"*.

## Dev review (2026-09-19, against `main` `dcb97f2c`)

Traced against `ScreensSheet.kt`, `RemoteRoutes.kt`, `shared/…/tv/Models.kt` and `i18n/Strings.kt`.
**Two things have changed since this was written: FR-R270-1 is already built, and FR-R270-3 is built
wrong** — it currently names the wrong household member on `main`. The Status line's "no backend,
route, DTO or config change" does not hold.

1. **FR-R270-1 already shipped with R265.** `ScreensSheet.kt:53` documents tier 3 as "R270's
   footnote-link AirPlay row", with `airplayAvailable` plumbed through (`:63`, `:89`, `:100`, `:122`).
   The phase is therefore **partly built while marked `Planned`** — worth saying in the Status, because
   what is left is FR-R270-2 through -6, not the row.
2. **FR-R270-3's premise is false, and this is the real work in the phase.** It states that
   `GET /api/remote/devices` "already carries the user for a screen in use". It does not.
   `remoteDeviceOf` (`RemoteRoutes.kt:64-80`) returns `pairedUsers` — **every** user paired to that
   screen, as `jellyfinUsername` — plus `nowPlayingTitle` and `nowPlaying: ScreenStatus?`. The only
   identity of the actual viewer anywhere is `ScreenStatus.sessionUserId` (`Models.kt:1262`), which is a
   Jellyfin **user id**, not a name. FR-R270-3 forbids both forms it can currently reach: "display name
   as the household knows it, **never a username or an id**". So this needs a resolved display name on
   `RemoteDevice`, or a server-side resolution of `sessionUserId` — **a DTO change and a route change**,
   which is precisely what the Status line says the phase does not have. Correct the Status and size the
   phase accordingly.
3. **And the shipped client already renders the wrong person.** `ScreensSheet.kt:213` fills
   `screens.busy` from `device.pairedUsers.firstOrNull()` — the *first paired* user, not the one
   watching. On a TV two people have paired with, that names the wrong household member roughly half the
   time, and it is a `jellyfinUsername` either way. This is a live, user-visible defect on `main` today,
   introduced by R265's build reaching ahead of this spec. It is also why item 2 matters more than it
   reads: the row is not missing, it is **confidently wrong**, which is worse. Note too that
   `pairedUsers` is only populated for `kind == "screen" || "cast"` (`RemoteRoutes.kt:65`), so an Android
   TV (`kind = "tv"`) renders *"Busy · is watching"* with an empty name — the exact failure this spec's
   own Dev notes already anticipated.
4. **The 409 cannot name anyone either, so acceptance's "the list and the 409 agree" is satisfied
   vacuously.** The conflict responds with the raw `ScreenStatus` (`RemoteRoutes.kt:116-117`), whose only
   identity field is the same `sessionUserId`. Both surfaces need the one resolution from item 2 — which
   is the right outcome, because it is one fix, but the acceptance criterion should say *"both name the
   same person by display name"* rather than "agree".
5. **FR-R270-6's string plan disagrees with the shipped table.** It says `screens.airplay_footnote`
   replaces `screens.airplay_notice` in the sheet, and `screens.airplay_notice` is kept for FR-R270-2's
   bar and the admin help text. What shipped is the opposite: **`screens.airplay_notice` *is* the
   footnote** — *"AirPlay (phone must stay on)"*, with da/fo drafts (`Strings.kt:351`, `:714`, `:1074`) —
   and there is **no `screens.airplay_footnote` at all**. Worse, the full sentence FR-R270-2 needs
   ("Your phone has to stay on and in Ravilo — the TV stops when you close the app.") and the bar's
   *"Playing on {TV} · keep Ravilo open"* **do not exist in the table in any language**. Cheapest correct
   path: rename the shipped key to `screens.airplay_footnote` (the three translations move with it) and
   add two genuinely new keys for the sentence and the bar. Either way, FR-R270-6 as written would leave
   the notice key holding the footnote's words, which is how a bar ends up displaying a parenthetical.
6. **FR-R270-4 needs its helper checked against its own three rules.** `lastSeenLabel(device.lastSeen)`
   already exists and feeds `screens.offline` (`ScreensSheet.kt:212`), so the requirement is about
   whether that helper actually does weekday-within-seven-days, date beyond, and **never "just now"**.
   Verify it rather than assume it — "just now" for a device the list is simultaneously calling offline
   is the kind of contradiction this spec exists to prevent.
7. **FR-R270-5 is the best thing in the spec and costs nothing to honour now.** Making busy and offline
   one flag rather than two strings is exactly right, and item 2's resolution is where it should be
   implemented — one server-side decision about whether to emit a name, not two client branches that can
   drift. Build it that way the first time.
