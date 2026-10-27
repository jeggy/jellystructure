# Round `radio` — report

**Revision 1 · 2026-10-27 · Rannvá.** Brief from Bárður on the round's task. The source is the adopted
spec `specs/ravilo/requirements/radio-plays-beside-the-live-channels-and-on-music-home.md`: radio
stations join the one live lineup with a mark, tuning one opens an audio player instead of a video
one, and Music Home gains a Radio row.

The brief stood alone — the question as a choice, the surfaces, the stand-in data named exactly, the
states, three leans stated as leans, and no phase number and no task id in anything the round had to
draw. Nothing went back.

## What came home

| | |
|---|---|
| `frames.html` | the frame registry; one document, one knob (`?set=`), every frame built from the data file |
| `rd-data.js` | **one array per fact.** Every number, time, name, percentage and count in every picture comes from here |
| `rd-kit.js` | the builders |
| `radio.css` | the canvas, the new `rd-*` components, and the admin table's page-local rules reproduced |
| `Radio - Directions.html` | what is fixed, the named directions, how they compare, the recommended direction's states, decided rows, the string table, the open questions |
| `png/` | **53 PNGs** |

### The three TV channels are not retyped

`rd-data.js` reads the shipped live-TV mock (`design/ravilo/ravilo-livetv-data.js`) at load and takes
Kringvarp 1, Kringvarp 2 and DR1 — names, logos, gradients, categories and generated programmes — out
of it. The brief said "from that mock, with its programmes", so nothing about those three is written
in this round. The only thing the round writes is the **renumbering** the brief asks for: the mock
numbers that lineup 101, 102, 104 and the brief's lineup is 1…6, and that conversion is one line.

Music Home's album, artist and genre rows come the same way, out of `design/app/music-data.js`. So
the Radio row is compared against the real neighbours it will have, not against a sketch of them.

### What each set links

The set decides which shipped stylesheet loads, because the TV, the phone, the desktop and the admin
are four different token files that name their tokens the same way. `ravilo-music.css` re-skins the
noir and midnight tokens, so the music sets are their own and the TV sets never load it.

Nothing is copied. The only CSS this round reproduces rather than links is the `.lineup` /
`.filterrow` block, which `design/app/livetv.html` keeps in a page-local `<style>` — there is nothing
to link, and it is marked in `radio.css` as a reproduction and **not** a proposal.

---

## The decisions, as drawn

### 1 · The radio mark — recommend **A, the corner glyph**

**A gives** a shape in the one corner a channel tile leaves empty, so its cost does not grow with the
language, and one drawing that carries to six surfaces at six sizes. **It costs** being learnt once —
mitigated by the word *Radio* appearing on the guide line, the player, the Radio row and the admin
beside it.

**B gives** a word read before it is learnt, with nothing drawn over the art. **It costs** width from
the station's name, and that cost grows: in Faroese the chip is *ÚTVARP* and *5 ÚTVARP Harbour
Radio 2* only just fits 340 px. B also has nowhere to go in the guide's channel cell, which has no
number column — so B needs A's glyph there anyway.

**C gives** one overlay chip instead of two, and a visibly wider badge is itself a difference read
before the words are. **It costs** making a liveness chip carry a kind, and in Faroese *BEINLEIÐIS ·
ÚTVARP* becomes a red slab over half the tile's top edge and the loudest thing on the row. Like B, it
does not carry to the guide.

### 2 · The TV radio player — recommend **A, the Now-playing layout**

**A gives** a screen built for having no picture: one centred block, nothing pinned to an edge, so the
burn-in step moves *everything on screen* — which is the only version of that step that protects the
bright text. **It costs** a second player layout to build and keep in step with R177's.

**B gives** nothing new to build — R177's bar, OSD, Now/Next and zap banner in the places a viewer
knows. **It costs** three things the pictures show: the shipped field behind the picture is
`opacity: .85` and with no picture turns the whole screen into one saturated wash (it had to be dimmed
to .22 before the two could be compared at all); the screen says the station's name twice and *radio*
four times; and **the bottom bar cannot take part in the burn-in step**, so the brightest, most
text-dense thing on screen is the one thing that never moves.

**Burn-in, concretely:** four positions, one step every **7 minutes**, a 28-minute loop, **56 × 36 px**
of travel (2.9 % of the width, 3.3 % of the height), a 600 ms glide, and a **cut** under reduced
motion. `radio-playerA-burnin-path.png` is the diagram; `radio-playerA-burnin.png` is the product
frame after one step.

### 3 · The Radio row — recommend **A, second, square cards**

The brief's option C held two questions at once — the position *and* the tile — so they are drawn
apart: A, B and D move the row with the same square tile, C changes the tile and keeps A's position.

**A** lines the row up with the album row above it tile for tile, so radio reads as part of the
library; it costs a 142 px card for a two-line label, and *Beinleiðis útvarp* is at the edge of what
fits. **B** is impossible to miss and costs making radio the first thing Music Home is about. **C**
halves the vertical cost and gives a long programme title a line to itself, and costs two tiles per
screen instead of three plus a card shape that exists nowhere else in music mode. **D** pushes nothing
down and costs putting radio at the end of a long scroll — on the phone, which is the only place a
phone can reach radio at all.

### 4 and 5 — one answer each

A station in Music mode and the remote's radio shape are drawn as single answers; the spec leaves no
second good one. Both are in the directions doc with their states.

---

## Findings — things the pictures showed, and the markup did not

**1 · The first mark glyph read as Wi-Fi.** An upward fan of arcs over a dot is the signal-strength
mark, and a live tile — next to a stream that really can lose signal — is the last place to borrow it.
Redrawn as `((•))`, a dot with an arc pair either side, which is the broadcast mark, is symmetrical,
and at three metres is a silhouette rather than a word. Nothing in the markup said which one it was.

**2 · FR-6 read literally draws the station's name twice.** *"A station with no guide shows its name
and Live radio in place of a programme"* — but the name is already on the tile, in the channel line.
The first render said *Harbour Radio 2*, then *Harbour Radio 2* again at the programme's weight, then
*Live radio*. Drawn as: the name stays where a channel's name is, and *Live radio* alone takes the
programme's place. **Worth a sentence in the spec**, because the literal reading is the one a builder
would implement.

**3 · The shipped guide's time header is one channel column out of step with its own cells.**
`.lt-epg-timebar` in `ravilo-livetv.css` carries **both** `padding-left: var(--lt-chcol)` **and** a
`.corner` of that same width inside it, so every slot label sits 320 px to the right of the time it
marks. In the first render of the guide, *Evening News · 20:00–20:30* sat under the **19:30** label
and the now-line fell on 19:30 instead of 20:14. Corrected inside this round's frames by one scoped
rule (`.rd-tv .lt-epg-timebar { padding-left: 0 }`) and **raised here as a defect against
`ravilo-livetv.css`** rather than silently carried. It is pre-existing and nothing to do with radio.

**4 · The mark after the name truncated exactly the longest station names.** The guide's channel
column is 320 px and about 128 of them are the name. The glyph after the name clipped *Harbour
Radio 2* and *Assembly Radio* to *Harbour Ra…* and *Assembly R…* while leaving the shorter *Harbour
Radio* intact — three station rows, two names damaged, to carry a mark the line under them had room
for. Moved to the category line, which costs no name at all.

**5 · Four frames claimed LIVE over a state that was not live.** The TV's *stopped* frame in both
directions, the phone's *stopped*, and the phone's *couldn't play* all carried a red LIVE mark — and
the failed one also carried a pause glyph, meaning playing. Three claims about one fact, two of them
false. Everywhere now: no LIVE when stopped or failed, the transport shows ▶, and the word says what
the next press does. On direction B the LIVE flag's **slot** is kept and filled with the programme's
end time, because dropping it outright let the flex track grow and the same 46.7 % fill drew a
visibly longer bar than the playing frame.

**6 · ↑ Now/Next over an audio layout needs a floor the shipped panel does not have.** R177's panel
fades to transparent at 80 % of its height because a moving picture is behind it. Over the audio
layout the logo plate showed through the bottom half as a grey block behind the last three rows,
which at 1× looks like a rendering fault. Opaque for the rows, fading only below them.

**7 · The row's scroll was applied to the wrong box.** A `transform` on `.track` moves the track's own
box, and `.track` is `overflow-x: auto` — so the frame that focuses the fifth tile clipped the row at
1 672 px instead of 1 920 and lost a third of the very tile it was focusing. The scroll is a negative
margin on the content now; the content moves and the box does not.

**8 · `.lt-player.on { display: block }` is two classes.** A one-class `.rd-pA { display: flex }` loses
to it however late the round's stylesheet loads, so the first render put the logo plate half off the
top of the frame with 400 px of nothing under the hint line. This is the class-collision cost of
linking production CSS instead of copying it — and it is still the right trade.

**9 · The burn-in diagram at a quarter scale stacked four digits into one blob.** At ¼ the four
positions are 7 px and 4.5 px apart. The orbit is drawn at 1:1 over a ¼-scale screen box, the picture
says so, and the four labels sit at four *different* corners of their own outline.

**10 · A flex column shrank the tail spacer and drew option D worse than option D is.** The Radio row
ended up under the bottom bar in a frame meant to show it. `flex: none` on every row; D is now drawn
at its best, which is the only fair way to be rejected.

**11 · Two glyphs were in the wrong hand.** A `🔊` emoji in the Mac's capsule, which Chromium draws in
its own monochrome face beside three glyphs in ours; and a cast-to-a-screen rectangle beside *Playing
on Downstairs* — radio never goes to a TV (FR-11's last clause), so every place in this round is a
speaker. Both redrawn. The cast glyph was also unsized, defaulting to a 56 px box that pushed
*Playing on Downstairs* onto two lines.

**12 · The remote offered *Up next*.** FR-11 enumerates the radio shape — "⏯, and R371's volume block
with rooms and *Add a speaker…*" — and a queue is not in it; FR-10 says the queue is gone while a
station plays. A button that opens an empty screen is not a kindness. Dropped. The **phone's** own
bottom row keeps its queue button deliberately, because FR-10 says the queue *screens* show nothing
for a station, so that button has to stay reachable. The two surfaces differ because the spec makes
them differ.

**13 · The remote said *Playing on Downstairs* twice**, nine lines apart — the place pill at the top
*is* the shipped device chip's sentence (`cast.playing_on`). The state line went.

**14 · A corpus collision the mark happens to answer.** The shipped music corpus holds an album called
**Low Tide Radio** by *Harbour Lights*, and the brief's station is **Harbour Radio**. In
`radio-rowD-phone.png` they are two rows apart on one screen. Nothing was contrived to produce that —
both are the stand-in data as named. It is a small argument that FR-9's *"with the radio mark"* on a
phone tile is load-bearing rather than decoration.

---

## Things reported rather than fixed

- **`design/ravilo/ravilo-i18n.js` disagrees with the shipped `i18n/*.json`** on at least *On Now* (the
  mock has Danish *Sendes nu*, the shipped file has *Nu*), *Recently added* (*Nyligt tilføjet* vs
  *Senest tilføjet*), *Playing on…* (*Afspiller på* vs *Spiller på*) and the Faroese *Live TV*
  (*Beinleiðis sjónvarp* vs *Beinleiðis*). The house rule is that the shipped file wins, so this round
  took every existing string from `i18n/*.json` verbatim and names the key beside it in `rd-data.js`.
  A disagreement is information, not a defect; it is not fixed in the drawing.
- **New Faroese words are not in `i18n/lexicon/fo.txt`** — `útvarp` and its forms. The spelling guard
  will want them when these strings land. The designer does not write `scripts/`.
- **`.lineup tr.off` fades a hidden row to 50 %**, which makes the three *new* stations — the rows the
  operator opened the page to find — the faintest on it, in both themes. Right for a channel somebody
  deliberately hid, awkward for an arrival. Raised as Q2.
- **The red LIVE mark does not follow the skin.** `--lt-live` is at `:root` in `ravilo-livetv.css`, so
  it is the same red in noir, whose whole character is amber. Pre-existing in R177, unchanged here.
- **The shipped `.lt-zap` carries `backdrop-filter`**, which the canvas rules forbid because it has
  broken the screenshot tool before. It did not break this render; it is linked, not authored here,
  and it is named so nobody has to rediscover it.
- **Scaffolding, named:** the phone's bottom bar icons are grey squares, and the Mac's *Recently
  played* is drawn as cards where the phone's is a list. Neither is a proposal.

## Nothing landed that should not have

No `STATUS.md`, nothing under `specs/`, `scripts/`, `.github/`, no `*.gradle.kts`, no `i18n/*.json`.
Nothing of that shape turned up in the round's inputs. No network reference in anything committed —
the document declares a CSP permitting `file:` and `data:` only, and the three faces come from the
repo's own woff2. Every string literal was read: hosts are none, no IP literals, no `passkey`,
`announce`, `api_key` or `token=`. No capture of the real app on the real library, in any file name.

### The `@import` problem is the lane's, not this round's

`design/ravilo/ravilo.css` — the TV token file PROCESS.md step 3 says to **link** — opens with a
Google Fonts `@import`, and `render-design.sh` fails the run on any off-host request. This round
carries the same four lines the TV sign-in round introduced, for the same reason, and **does not
re-raise it**: it is that round's question Q5, still open. Either `render-design.sh` grows a flag for
a shipped mockup's own `@import`, or `ravilo.css` loses the line the way the served admin CSS already
has. Until then every round that links `ravilo.css` needs those four lines.

## Coverage, stated rather than implied

| What the house rule asks for | This round |
|---|---|
| Loading, and the slow case | **Skipped.** The spec's own loading answer is DEB-2627's 15 s relay fallback, which is a server behaviour with no screen of its own; the tune's spinner is R177's and unchanged. |
| Empty | **Skipped — it cannot occur.** FR-9: the Radio row exists only when the viewer has a station. |
| Error | Drawn: `radio-np-phone-failed` (the engine's couldn't-play, with the station's name). |
| The longest string | Drawn: the Faroese and Danish runs on the row, where *Beinleiðis útvarp* and *ÚTVARP* are the question, and the Faroese Now playing. |
| Danish and Faroese | Drawn: 7 frames. |
| 10-foot focus | Drawn: the focused tile in every mark frame, the focused guide cell, the focused Now/Next row. |
| Offline / lost connection | **Partly.** The failed-to-start state is drawn; a station that drops mid-stream is DEB-2627 FR-4's fallback, which re-tunes without a screen. |
| The round's own states | All of the brief's, plus five the spec implies and the brief did not name: ↑ Now/Next with the mark, number entry, stopped (TV and phone), playing on a speaker, and a station on one speaker as well as a group. |

Skins: aurora throughout, with noir on the TV player and on two phone frames. The TV radio player is
almost skin-independent by construction (see the directions doc); the phone's noir frame shows the
amber accent arriving as it should.

## How it was rendered

`scripts/render-design.sh` (DEB-2125), `--all .rd-frame`, one call per set:

```
tv-mark · tv-mark-da · tv-mark-fo      --viewport 1980x900
tv-compare                             --viewport 1600x900
tv-guide · tv-player · tv-player-noir  --viewport 1980x1180
admin · admin-light                    --viewport 1500x1200
music · music-fo · music-noir · remote --viewport 520x1000
mac                                    --viewport 1500x1000
```

**A trap, for the next round that parameterises one document.** The Danish and Faroese runs share a
`family` with the English one. The first render tested `O.family === 'tv-mark'` rather than
`O.set === 'tv-mark'`, so the `-da` and `-fo` runs re-rendered the **English-named** frames with
Danish and Faroese in them — a defect no fence can see and the file name actively hides. Test the
set, not the family, wherever a variant exists.

## Open questions for Bárður

- **Q1** — should the audio layout *dim* after a while with no key press? FR-8 says the screen stays
  on; it does not say at what brightness, and a 28-minute orbit protects the edges but not the centre
  of a 312 px plate. Beyond the brief, so asked rather than drawn.
- **Q2** — the admin's 50 % dim on a hidden row makes the three new stations the faintest thing on the
  page the operator opened to find them.
- **Q3** — the guide's time-header offset in `ravilo-livetv.css` (finding 3). A defect to route.
- **Q4** — `ravilo-i18n.js` versus the shipped `i18n/*.json` (above).
- **Q5** — FR-6's sentence draws the station's name twice when read literally (finding 2). Worth a
  clause in the spec before the first client slice.
