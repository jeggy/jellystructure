# Design brief — Playback sessions: one session, any screen, any speaker, any controller

**Date:** 2026-10-02 · **For:** the design project (Cosmos) that owns `design/ravilo/` and `design/app/` ·
**Status:** brief, round 1 wanted — no spec yet; specs follow the picks (next free numbers at time of writing:
**303 / R356**, check `main` before numbering) · **Source:**
`specs/research-reports/ravilo-playback-sessions-and-casting-2026-10-02.md` (read §1 for what we learned on devices,
§2 for how it works today, §4 for the model).

> Owner, 2026-10-02: *"Be able to do the same, but from the app instead, making it possible to do it from desktop as
> well. And we would also like to be able to start a session from the mobile to a casting session and then see
> directly on the macbook client as well. And then on top of this, we would like to be able to have multiple of these
> sessions. So basically, every session is controlled in the cloud (jellystructure) and each session can be on a
> device or in a google cast group etc. And multiple Ravilo clients can control a single session, but also have
> support to create a new session, so having multiple sessions."*

**The idea in one paragraph.** Today "what is playing" lives inside whichever app started it; a cast is mirrored only
to the phone that cast it. From now on every playback is a **session** that lives on the server. A session plays on
one **target** — this phone, the Mac, the TV app, a Chromecast, a speaker, or a group of speakers — and any Ravilo app
of the same person can **see** it, **join** it as a remote, **move** it to another target, or start a **new** session
beside it. Several sessions can run at once (a film on the TV, music in the kitchen, an audiobook on the phone).

## 0. What exists, so nothing is drawn twice

| Have | Where | Reuse |
|---|---|---|
| Phone: player, cast remote (*Now playing*), mini bar, connecting bar, subtitles sheet, *Play on…* sheet (three tiers), speakers sheet, music Playing/Queue, version chips | `ravilo/Ravilo Mobile.html`, `mobile/*` | The remote and the mini bar become **session** views (§B2, §B5) |
| Desktop: sidebar, toolbar, music capsule (mac) / docked bar (GNOME), Playing page, queue panel, cast remote | `ravilo/Ravilo Desktop.html`, `desktop/ravilo-desktop.js/.css`, `desktop-kit.js` | Sessions enter the sidebar and the capsule (§B8) |
| TV: home, player, picker | `ravilo/Ravilo TV.html` | The TV as a target and a viewer of sessions (§B9) |
| Round-1 canvases: casting, play on a TV, speakers, mobile player | `Casting - Directions.html`, `Play on a TV - Directions.html`, `Speakers - Directions.html`, `Mobile Player - Directions.html` | Decided parts stand; this brief extends them |
| Receiver screens (idle, loading, playing, next-up, ended, busy, failed) | `ravilo/Ravilo Receiver.html` | Unchanged; add "controlled by" only if §C decides |
| Admin: Users & devices, Activity | `app/ravilo-users.html`, `app/activity.html` | Sessions view for the admin (§B10) |

Rules carried over: **absent, never greyed**; **never name a product, protocol, codec or status code** in viewer copy
(R180 / FR-R245-16) — *Google Cast*, *Chromecast*, *Jellyfin* never appear to a viewer; **render-never-compute** (the
app shows what the server says); 46 px touch targets and a 13 px type floor (R234); D-pad users are first-class on
the TV (R350); Noir drops accent tints; S01E05 everywhere; say *audiobooks*, never *books*; strings in en · da · fo.

## A. The vocabulary (please settle in round 1)

| Concept | Working name | Viewer sees (lean) |
|---|---|---|
| A playback living on the server | session | **"Playing"** / *"Now playing on Stue"* — the word *session* never shown (lean) |
| Where it plays | target | the device or room name: *This phone*, *MacBook*, *Stue TV*, *Køkken hub*, *Stue + Gæsteværelse* |
| An app attached as a remote | controller | *"Also on your MacBook"* only where it helps (lean: not shown at all) |
| Moving it | move | **"Move to…"** (lean) vs "Play on…" reused |
| Several speakers playing together | group | *"Stue + Gæsteværelse"*; a saved Google Home group by its own name |

## B. Surfaces to design (round 1: directions; mark leans)

### B1. Where all sessions are listed — "Playing everywhere"

The one new screen. Every session of this person (and, per §C Q1, the household's), each with: artwork, title,
target (with an icon for phone / computer / TV / display / speaker / group), state (playing / paused / buffering /
failed), a position line, and *Join* (control it here) / *Move to…* / *Stop*.
- **Phone:** where does it live — a row on Listen and Home, a sheet from the mini bar, the cast glyph's sheet, or
  Profile? (Lean: the cast glyph opens one sheet: *Playing* sessions on top, *Play on…* targets below.)
- **Desktop:** sidebar entry *Playing* with a count, and/or the capsule's session switcher (§B8).
- **TV:** a row on Home (*Playing in other rooms*) — TV users can join a session as a remote? (Lean: show, not
  control, in round 1.)
- States: none (absent), one, many (5+), another person's session (§C Q1), a session whose target went offline, a
  failed session, a session just ended (fades).

### B2. "Playing elsewhere" — the bar when this app is a remote

When the user's session plays on another target, the mini bar/capsule shows it as a **remote**: artwork, title,
*on Stue*, play/pause, next; tapping opens the remote (§B5). Today's cast mini bar is the starting point.
- Several sessions at once: does the bar stack, switch, or show the most recent with a *2 more* chip?
  (Lean: most recent active + *+N* chip opening §B1.)
- The bar for a session started **elsewhere** (the phone started it; the Mac shows it) — the same bar, with a quiet
  *Started on Pixel 9* line? (Lean: no origin shown; the target is what matters.)

### B3. *Play on…* — one sheet for every target

Extends R265's three tiers and R324's speakers into one list:
1. **This device** (*This phone* / *This Mac*).
2. **Your other Ravilo apps** that can play (*MacBook*, *Stue TV* when its app is on screen, *Ravilo web on Chrome*).
3. **Speakers and displays on this network** (Cast devices), with *Busy · {Person} is listening* when another person's
   session holds it (R270's rule), *Playing* when it is part of this person's session.
4. **Groups**: saved Google Home groups, and *Make a group…* (§B4).
5. The R265 *All your TVs* collapsible tier stays for remote screens.

Behaviour to draw: choosing a target while something plays **here** = move this session there (lean) — or ask
*Move it there / Play it there too / Start fresh*? (§C Q2). Choosing a target that already has *this person's* other
session = join it? Desktop draws the same sheet as a popover from the toolbar's cast button.

### B4. Groups from Ravilo — pick several speakers

*Make a group…* or a multi-select mode in §B3: tick speakers, see each with its own volume, *Play on 2 speakers*.
- Adding/removing a speaker while playing (what Android's panel does today) from inside Ravilo, on phone and desktop.
- Per-speaker volume plus a master volume (lean: master on the bar, per-speaker in the remote's ⋯).
- Name of a dynamic group: *Stue + Gæsteværelse*; 3+: *Stue + 2*.
- A saved Google Home group listed as one target with a group icon.
- Desktop note: whether the Mac can build a group is a research question (report §8 Q5); draw it as if it can,
  and mark the frames that depend on it.

### B5. The remote for a session (phone and desktop)

Reuse R245's *Now playing* remote and the desktop Playing page; add:
- the target line (*Playing on Stue + Gæsteværelse*) and *Move to…*;
- queue (music) and episodes (series) editable from any controller;
- volume for the target (and per speaker in a group);
- *Play here* (move to this device) and *Stop*;
- a quiet *Also controlled from MacBook* only if §C Q4 says so;
- what happens when two controllers act at once (one wins; the other updates — no error) — state only, no dialog.

### B6. Starting playback while sessions exist

The decision point the owner raised: *join* vs *new*. Draw the moments:
- Press Play on a film on the phone while music plays on the kitchen speaker → new session here (lean), the kitchen
  keeps playing, the bar shows 2 sessions.
- Press Play on a song while the same person's music session plays on Stue → does the phone take it over, queue it on
  Stue, or start a second music session here? (Lean: a sheet *Play on Stue / Play here*, remembering the last choice per
  device.)
- From a series page while an episode of it plays on the TV → *Continue on Stue TV* / *Play here*.

### B7. Moving a session

*Move to…* from §B1, §B5 and the bar: pick a target; the session continues there at its position; the old target
stops. States: moving (one line, no spinner wall), moved, failed (*couldn't move — still playing on Stue*). Include
*Play here* as the most common move.

### B8. Desktop specifics

- Sidebar: *Playing* (count) → §B1 as a page.
- Capsule (mac) / docked bar (GNOME): session switcher, target chip, *Play on…* popover (§B3), group volume.
- A session started on the phone appears on the Mac within a second — the capsule lights up as a remote.
- Keyboard: media keys and Space control the session shown in the capsule.

### B9. TV

- The TV app as a **target**: when a phone or the Mac moves a film to it, the TV shows the player — what does the
  first frame say (*From Pixel 9* toast? nothing?) (lean: nothing beyond R303's top-right identity).
- The TV as a **viewer**: a Home row *Playing in other rooms* (lean: round 2).
- D-pad first-class: anything focusable must have a visible focus state (R350).

### B10. Admin (jellystructure web)

- Users & devices → a *Sessions* panel: every session, person, target, item, state, controllers; *End session*.
- Activity: session events (started, moved, ended) in the timeline.

### B11. States to draw for every surface

none · one · several · loading · target offline · target busy with another person · failed to start · failed while
playing · reconnecting (controller lost the server) · ended · moved · a speaker leaving a group · the server
restarting (sessions come back).

## C. Round-1 questions (owner picks; leans given)

1. **Household visibility.** Can people see each other's sessions? Control them? (Lean: see with the person's name;
   control only your own unless a household setting allows it.)
2. **Play while a session runs elsewhere:** move it / start new / ask? (Lean: on this device → replace its own;
   elsewhere → new, *take over* one tap away; music on the same speaker → ask once, remember per device.)
3. **Where §B1 lives on the phone** (cast glyph sheet / mini bar sheet / its own tab / Profile). (Lean: cast glyph.)
4. **Show controllers?** (*Also controlled from MacBook*.) (Lean: no.)
5. **The word for moving**: *Move to…* / *Play on…* reused / *Send to…*. (Lean: *Move to…*.)
6. **Group naming and saving:** keep dynamic groups unnamed (*Stue + 1*) or let people save named groups in Ravilo?
   (Lean: unnamed; saved groups come from Google Home.)
7. **Ended sessions:** fade after a minute, or keep a *Recently played here* list? (Lean: fade.)
8. **Audiobooks as sessions:** same model (lean yes), with the speed/sleep timer belonging to the session.

## D. Strings (en · da · fo drafts; the shipped `i18n/*.json` wins where a key exists)

*Playing everywhere* · *Playing on {target}* · *Move to…* · *Play here* · *Stop* · *Join* · *{Person} is listening* ·
*Make a group…* · *Play on {n} speakers* · *{first} + {n}* · *Couldn't move — still playing on {target}* ·
*{target} is offline* · *Playing in other rooms* · *Also controlled from {device}* (if Q4) · *Start fresh*.

## E. Deliverables and order

1. A round-1 canvas `ravilo/Playback Sessions - Directions.html`: vocabulary (§A), §B1 on phone + desktop in 2–3
   directions, §B2/§B3 in the chosen direction, §C questions with leans.
2. After the picks: build into `Ravilo Mobile.html` (iPhone + Pixel frames), `Ravilo Desktop.html` (mac + GNOME,
   Large/Expanded/Medium/Compact), `Ravilo TV.html` (B9), `app/ravilo-users.html` (B10).
3. States table (§B11) as a strip per surface.
4. Strings (§D) into `ravilo-i18n.js`.

The specs follow the report's phasing (§5): read-only sessions first, then control, then start-anywhere, then groups,
then multiple sessions and moving, then admin.
