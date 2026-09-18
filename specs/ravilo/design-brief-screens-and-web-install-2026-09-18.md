# Design brief — Play on a TV from the phone (any phone, any TV), and the web app that installs

**Date:** 2026-09-18 · **For:** the design project (Cosmos) that owns `design/ravilo/` and `design/app/` ·
**Status:** brief, awaiting round-1 directions on the two open questions in §E; everything else is decided
and can be built straight into the main mockups. Source: `specs/research-reports/ravilo-web-pwa-player-cast-2026-09-18.md`
(§3, §5.3, §12) and the five specs written the same day: **235** (serving), **R263** (the web app
installs), **236** (screens: the backend drives a TV for a phone), **R264** (the receiver-only TV app),
**R265** (one glyph, three tiers).

> Owner's picture, 2026-09-18: *"An iPhone installs the PWA and streams to the Samsung TV — our most
> important use case."* The TV runs a receiver-only Ravilo app (no navigation; "one more media player
> with all functionality, fully controllable remotely"); the phone lists its TVs — the ones **on this
> network** first, then a **collapsible list of all of them** — and drives the TV through the backend, so
> the phone can be closed and the picture continues. **AirPlay is kept**, one tier further down, **with a
> notice** that the phone must stay on. On the phone all of it hides behind **the same Cast glyph**, "even
> though it might be a bit misleading". And iPhones below iOS 18.2 get **one notice**, nothing else.

**⚠ Two things this brief reverses from the 2026-09-16 brief:** (1) *"Never: AirPlay — do not draw an
AirPlay affordance anywhere"* is withdrawn — draw it, as the third tier with its caveat (§B4). (2)
FR-R245-2 "the device picker is the platform's dialog" is superseded — the picker is now **Ravilo's own
sheet** (§B). The remote, the mini bar, the subtitles sheet and the receiver's ten screens from that round
are **reused as drawn**; nothing in them changes.

## 0. What exists, so nothing is drawn twice

| Have | Where | Reuse |
|---|---|---|
| Phone frames: Pixel 9 (412 × 915) and iPhone 16 (393 × 852, `?dev=ios`) | `ravilo/Ravilo Mobile.html`, `mobile/ravilo-mobile.css`, `mobile/ravilo-mobile-player.css` | Every new phone screen in **both** frames |
| The phone player, the casting remote (direction 2 · *Now playing*), the mini bar, the connecting bar, the subtitles sheet, the seven remote states | `Ravilo Mobile.html` (built 2026-09-16) | **Unchanged.** The remote now drives a Tizen TV or a Chromecast alike |
| The receiver's ten non-interactive screens (idle · loading · buffering · playing · paused · next-up · ended · busy · unreachable · failed), 1920 × 1080, three skins, three languages, STATE picker | `ravilo/Ravilo Receiver.html` | The Tizen receiver **is** this file plus §C's additions |
| The TV player chrome and the two-level audio & subtitles picker | `Ravilo TV.html` → `ravilo-player.js` / `ravilo-player.css` | The receiver's on-TV picker (§C2) reuses it verbatim |
| Settings → Connections → Chromecast card (three states) | `app/settings.html` (`cc-*`) | Untouched. §D adds *screens* to Users & devices, not here |
| Brand masters (lit-mark on `#000B25`) | `ravilo/assets/brand/*.svg` | Icons for the installed app (§A3) |
| i18n en · da · fo | `ravilo/ravilo-i18n.js` | Every new string in all three; the shipped table wins where a key exists |

Rules carried over: **absent, never greyed** (a control that cannot work is not drawn); **never name a
product, protocol, codec or status code** in viewer copy (R180 / FR-R245-16); **render-never-compute**
(the phone shows what the server said, including *which TVs are on this network* — the server decides,
§B2); 46 px targets and a 13 px type floor (R234); Noir drops accent tints (R221/R222).

---

## A. The web app that installs (R263) — phone frames, iPhone first

### A1. The unsupported-device notice (FR-R263-1)
One screen, shown *instead of the app* on an iPhone below iOS 18.2 (and on a too-old browser anywhere).
Plain page, no app chrome, no bundle behind it: the mark, one sentence, nothing to tap.
- iOS wording: *"Ravilo can't run on this device. It needs iOS 18.2 or newer."*
- Elsewhere: *"Ravilo can't run in this browser. It needs a browser from 2024 or newer."*
- Three languages. Draw in the iPhone frame, all three skins are irrelevant (it renders before any skin
  loads — use the dark ground `#0d0d1a` and the mark only).

### A2. The install card (FR-R263-8)
Where: the sign-in screen (below the form) and Settings (a row *Install Ravilo* that opens it). Only in a
browser tab, never once installed, never on the TV, never above 900 px.
- **iPhone (no prompt exists):** the share glyph, *"Add Ravilo to your Home Screen"*, two steps
  *Share → Add to Home Screen*, and one line *"You'll sign in again in the installed app."* Dismiss ×.
- **Android Chrome:** the same card with one button **Install** instead of the steps.
- States: default · dismissed (gone; the Settings row remains) · Android-without-prompt (the card is
  absent — draw nothing, note it).
- The **update toast** (FR-R263-5): *"Ravilo updated · Reload"*, bottom, above the mini bar if one is
  showing; one action. Draw once on Home.

### A3. Icons (FR-R263-4)
From the brand masters: 192 and 512 px (`any`), 512 px **maskable** (mark inside the 80 % safe zone on
the navy), 96 px **monochrome** (Android themed icon), 180 px `apple-touch-icon`. Show the maskable one
under a circle, a squircle and a rounded square so the safe zone is judged, and the monochrome one on a
Material You tint. Splash: `#0d0d1a` ground + the 512 — one frame per platform.

### A4. Standalone mode (FR-R263-6/7)
Two frames on the iPhone: Home and the player, **installed** — app bar clearing the Dynamic Island,
player controls clearing the home indicator, no Safari chrome, no *Fullscreen* button. These are the
frames R261/R244 already imply; draw them because the safe-area seam on web is new.

## B. One glyph, three tiers (R265) — the sheet

Entry: the **Cast glyph** on Home, Detail and Player, in its two forms (outline; filled + accent when
connected). Present when the user has ≥ 1 paired TV *or* AirPlay is available here; absent otherwise.
Tapping opens **Ravilo's own bottom sheet**, title *"Play on a TV"*. Both phone frames; Aurora + Noir at
minimum.

### B1. Rows
One row shape for every TV: platform mark (a small TV outline; the Cast mark only for a Chromecast
row on Android), **name**, and one line of state:
- *Ready*
- *Playing {title}* — yours; tapping opens the remote
- *Busy · {user} is watching* — not tappable, dimmed (236's 409; never hijack)
- *Offline · last seen {when}* — dimmed
Each row is a 46 px target. Long names truncate, never wrap.

### B2. Tier 1 — *On this network*
Section label + rows. **Empty ⇒ the section is absent** (no empty box, no "none found"). The server
decides membership; the phone does no scanning — so there is no spinner, no "searching…", ever.

### B3. Tier 2 — *All your TVs (n)*
A collapsible row, **closed by default**, remembering its state. Open: the same rows. On the Android
*app* frame, Chromecasts discovered by Google appear here too with the Cast mark; on iPhone/web they
never exist — draw the iPhone version without them.

### B4. Tier 3 — *AirPlay*, with the notice
Only where the browser reported AirPlay (iPhone/iPad Safari, the web build). One row *AirPlay* with a
**persistent second line**, not a tooltip: *"Your phone has to stay on and in Ravilo — the TV stops when
you close the app."* Visually one step *below* the TV rows (a hairline and a quieter ink), so it reads as
supported-with-caveats, never as the recommended route. Tapping hands over to Apple's picker (the
platform's sheet; do not redraw it). While AirPlay runs: the glyph's connected form with the TV's name,
and **the phone's own player is the remote** — no mini bar, no R245 remote. Draw the player with the glyph
connected as the AirPlay-active frame.

### B5. *Add a TV* (FR-R265-5)
Last row of the sheet. Opens a **6-character code entry** (native keyboard on phone/web), one line *"The
code is on the TV's screen"*, error *"That code didn't work"* (one string for unknown/expired/used).
Success: the sheet returns with the new TV in place, briefly highlighted.

### B6. Connecting bar copy (FR-R265-7)
*"Sending to {TV}…"* → *"Playing on {TV}"*, retiring after ~2 s — the same bar as FR-R245-3, new words
because nothing is "connecting". Reconnect on app start: **no bar at all** (either the mini bar appears
with the live position, or nothing) — draw the "nothing" case explicitly once, as the 2026-09-16 round
did for the receiver-gone outcome.

### B7. States list for the sheet
default (both tiers) · tier 1 absent · tier 2 open · busy row · offline row · AirPlay row present ·
AirPlay row absent · code entry · code error · Android app with a Chromecast row.

## C. The receiver-only TV app (R264) — 1920 × 1080, from `Ravilo Receiver.html`

The ten receiver screens stand. Add:

### C1. Idle with the pairing code (FR-R264-2)
The lit mark and the TV's name as today, plus — always — the **6-character code** in a corner with one
line *"Enter this code in Ravilo on your phone"*. Large enough to read from a sofa (≥ 72 px glyphs,
tabular figures, no ambiguous characters — 218's alphabet already excludes them). Variant: *"Can't reach
the server"* replaces the code line when the backend is unreachable (FR-R264-6). Variant: dimmed to 20 %
after 10 minutes (R264 OQ 2) — one frame.

### C2. The player on the TV, driven from either remote (FR-R264-3/4)
The receiver becomes a *whole* player when the TV remote is used: the TV chrome (transport, scrub, times)
and the **two-level audio & subtitles picker** exactly as `Ravilo TV.html` draws them, plus **Subtitle
size** S/M/L in the picker (the phone's addition, now on the TV too), **Skip Intro / Skip Credits**, the
next-up card. Draw: chrome up · picker level 1 · picker level 2 · next-up · Skip Intro. No browse, no
search, no settings, no profile — if a frame needs a nav item, it is wrong.

### C3. A TV-remote action mirrored on the phone
One paired frame: the TV remote pauses → the phone's remote shows *Paused* (art dimmed) — the "within one
push" promise of FR-R264-4, shown side by side (TV frame + phone frame).

## D. Admin — Settings → Users & devices: screens (236 FR-236-10)

In `app/settings.html`'s Users & devices tab, the device table gains **screens**: kind (*TV app* ·
*Chromecast*), platform (*Samsung TV* · *LG TV* · *Chromecast*), **paired users** (chips), *now playing*
(title · user · position), last seen, and **revoke per user** (the existing revoke, scoped to one user's
session on a shared TV). One row each for: a Tizen screen paired by two users, idle; the same playing for
one of them; a Chromecast receiver (unchanged shape). Help text under the table, one sentence, for the
"on this network" rule: *"Ravilo groups a TV with a phone when both reach the server from the same
internet address. A VPN or mobile data puts the TV under 'All your TVs' instead."* (`jfa-*`/`cc-*`
naming discipline as before — never `adv-*`.)

## E. Round-1 questions (directions wanted, owner picks)

1. **The AirPlay tier's weight.** Two directions: (a) a full row with the notice as a second line;
   (b) a footnote-sized link *"AirPlay (phone must stay on)"* under the collapsible. Owner said "shown to
   be supported but expect bad behaviour" — draw both and let the frame decide.
2. **"Busy" and "Offline" ink.** Whether a busy TV (someone else watching) shows *who* — the owner's
   per-user model says yes; draw with and without the name.

Everything else in A–D is decided; build it straight into the main mockups after the two picks, or in
parallel if the picks do not touch the frame.

## F. Strings (all × en/da/fo — drafts; the shipped table wins where a key exists)

`web.unsupported_ios` · `web.unsupported_browser` · `install.title` · `install.step_share` ·
`install.step_add` · `install.signin_again` · `install.cta` · `settings.install` · `update.toast` ·
`update.reload` · `screens.title` · `screens.nearby` · `screens.all` · `screens.ready` · `screens.busy` ·
`screens.offline` · `screens.add` · `screens.code_hint` · `screens.code_failed` · `screens.airplay` ·
`screens.airplay_notice` · `screens.sending` · `screens.playing_on` · `receiver.code_hint` ·
`receiver.no_server`.

## G. Deliverables and order

1. **Round 1 — `ravilo/Play on a TV - Directions.html`** (canvas + print copy): the sheet (§B) with its
   ten states in both phone frames, the two E-questions as marked options, the AirPlay-active player
   frame, the receiver's idle-with-code and player-on-TV frames (§C), the admin rows (§D).
2. **Build (no picks needed):** §A into `Ravilo Mobile.html` (iPhone frame first) — notice, install card,
   toast, standalone frames; icons into `assets/brand/` + a small `Ravilo - Web App Icons.html`; §C1 into
   `Ravilo Receiver.html` as new STATE entries; §D into `app/settings.html`.
3. **After the picks:** §B into `Ravilo Mobile.html` behind the Cast glyph, replacing the 2026-09-16
   "device sheet on its own layer (the platform's dialog)" frame; §C2/C3 into `Ravilo Receiver.html`.
4. Update the design project's `CLAUDE.md`: next unassigned numbers **237 / R266**; R189 is *Removed →
   R264*; AirPlay's "never" is withdrawn.

**Not in this round:** the web player's chrome rebuild (the transparent-canvas question, prospective
R266 — the AirPlay frame uses today's chrome), launching or waking a TV from the phone, LG store
screenshots, tablets, Chromecast-for-iOS (dropped).
