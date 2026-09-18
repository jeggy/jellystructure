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

`Planned` — written 2026-09-18, **not dev-reviewed**. Client only (`ravilo-ui` commonMain: the sheet's
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
