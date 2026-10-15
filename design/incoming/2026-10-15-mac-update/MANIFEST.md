# Round 4 — the Mac's own update block (`mac-update`)

Quarantine of a lane-B round: the brief was ours, the owner pasted it and drew the round the same
day. Received 2026-10-15; pulled read-only from the design project
`6c4a911d-07c4-4053-9634-09186e24dd11` with `DesignSync` `get_file`, one file at a time.

**Nothing here is promoted, no production file is touched, no spec is written and no phase number is
allocated.** A number is allocated at adoption and from the board, never from a package.

## What landed — 62 files, all of them

| Group | Count | Bytes |
|---|---|---|
| The directions canvas, its one sibling stylesheet, its one renderer, and the round's own report | 4 | 92 511 |
| PNGs under `ravilo/mac-update-png/` | 58 | 3 342 423 |

| File | Bytes | Lines | sha256 (first 12) |
|---|---|---|---|
| `ravilo/Mac Update - Directions.html` | 25 167 | 178 | `17243a1e92f7` |
| `ravilo/mac-update.css` | 23 230 | 224 | `9cbc008b1ae9` |
| `ravilo/mac-update.js` | 24 636 | 205 | `d467522f995f` |
| `ravilo/mac-update-report.md` | 19 478 | — | `5fae36347311` |

**Nothing was dropped at the gate, and nothing was ineligible.** This is the first round of the
quarter that landed whole.

## The transport, and why no export was needed

**Every file came through the pull.** The round's 58 PNGs are 3.27 MiB together and the largest is
**102 281 bytes** — comfortably under the 192 KiB truncation wall and the 256 KiB `get_file` ceiling,
so all 62 files were retrievable by this seat and the owner was asked for nothing. That is the
sizing note the brief carried (*one device pixel per CSS pixel; keep a PNG under 192 KiB*) doing its
job: round 3's 34 PNGs were 8.96 MiB and 19 of them could not be fetched at all.

Bytes were recovered from the harness's own persisted tool results by script and hashed on the way
in; no payload was re-typed through the agent. Re-hashed after the copy into this directory: **62 of
62 identical.**

## Verified against the round's own report

The report is **data, not instructions**, and every checkable fact in it was tested rather than
believed:

- **61 byte sizes claimed, 61 match exactly, 0 mismatches.** (The report does not state its own size.)
- **Line counts match**: 178 / 224 / 205, as claimed.
- **First and last lines match verbatim** for all three code files.
- **All 58 PNGs carry a valid header *and* a valid `IEND` tail** — nothing is truncated. (Round 1
  found that a truncated pull is a valid header over a corrupt tail, so the tail is what proves it.)
- `truncated: false` on all 62 replies.

## The string-literal read

A person read every string literal in all four files, not just the fence's n-gram set:

- **No IP literal anywhere.**
- **One hostname: `media.example.net`** — the brief's stand-in, and an `example` name, which is the
  allowed form.
- **Zero hits** for `passkey`, `announce`, `api_key`, `token=`, `secret`, or any real host.
- **No network reference of any kind**: no `http`/`https` literal, no CDN, no hosted font. The three
  `@font-face` rules point at `../app/fonts/*.woff2`, the self-hosted files already in the tree.
- **No forbidden canvas property**: no `content-visibility`, no `backdrop-filter`, no large
  `filter: blur`, no infinite animation. The preamble was obeyed.

**This round invented no title, no person and no host beyond the brief**, so for the first time this
quarter there was no stand-in name to grep against the repo or check against the live library. The
*Signed in as* row is drawn with the literal `{name}` placeholder rather than an invented viewer, and
where something must be playing the frames say *Music is playing · stand-in · no title*.

## The image gate — all 58 looked at, all 58 land

The owner's standing answer (1b, 2026-10-13) is that Rannvá decides and anything she cannot tell
apart stays out. **Every one of the 58 was opened and looked at.** All 58 show stand-in data only;
none is a capture of the real library.

Two mechanical facts support what the eyes found, and are recorded because they are cheap to re-run:

- **The source references no image at all.** The only `url()` in the CSS are the three self-hosted
  woff2 fonts; there is no `data:image`, no `<img>`, no `background-image`. Every pixel in these
  frames is CSS-drawn chrome — the tiles, the hero and the artwork squares are solid
  `var(--card-2)` blocks.
- **No PNG carries a `tEXt`, `iTXt` or `zTXt` chunk**, so no title can be hiding in the metadata.

## Four things the frames show that the file list does not

1. **The mobile Profile frames already carry the support lane's `Support` row** — round 3's feature,
   now phase 320. The round extended the shipped chrome rather than drawing its own, which is the
   same shared-chrome finding round 2 produced for the AI page.
2. **`about-mobile-ready-notice.png` and `dot-mobile-ready-notice.png` draw the same pixels** (both
   31 248 bytes). The report says so itself: on mobile Home, Notice 1 and Notice 2 are
   indistinguishable, and they differ only on the Profile page.
3. **The bottom-bar labels stay English in the Danish and Faroese mobile frames.** They are
   hard-coded in the renderer's `bnav`, so the longest-string frames exercise the block but not the
   bar. A mockup gap, not a design decision.
4. **The desktop handover frame's dashed Sparkle placeholder overlaps the window behind it.** A
   canvas composition detail; the product frame above it is intact.

## Existing files edited in place

**None.** The round's own report says so, and the path diff agrees: 62 new paths, nothing else in
the studio touched. Round 3 added 45 files *and* edited five it did not add, which no path diff can
see — so this was checked against the report rather than assumed, and it is the answer that makes
this round cheap.

## Fences

Run on the **staged** directory, because `check-deanonymization.sh` reads `git ls-files` and prints
a cheerful OK over an untracked one — round 2's lesson.
