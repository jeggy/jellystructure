# Round 5 — Suggestions as the AI page's third tab (`ai-suggestions-tab`)

Quarantine of a lane-B round: the brief was ours, the owner pasted it and drew the round the same
day, and he was at the keyboard for the three decisions. Received 2026-10-16; pulled read-only from
the design project `6c4a911d-07c4-4053-9634-09186e24dd11` with `DesignSync` `get_file`, one file at
a time.

**Nothing here is promoted, no production file is touched, no spec is written and no phase number is
allocated.** The task id is the identity; a number is never allocated from a package.

## What landed — 40 files, all of them

| Group | Count | Bytes |
|---|---|---|
| The directions canvas, its sibling stylesheet, its renderer, the 1:1 shot page, the round's own report | 5 | 70 884 |
| PNGs under `screenshots/ai-suggestions-tab/` | 28 | 2 462 362 |
| Existing files the round edited in place, pulled for comparison (`edited-in-place/`) | 7 | 103 878 |

### Added — text (`app/`)

| File | Bytes | Lines | sha256 (first 12) |
|---|---|---|---|
| `AI Suggestions Tab - Directions.html` | 24 585 | 175 | `22188e209725` |
| `ai-suggestions-directions.css` | 16 207 | 182 | `d003dc4da4f7` |
| `ai-suggestions-directions.js` | 15 425 | 149 | `8e24a6960506` |
| `ai-suggestions-tab-shots.html` | 2 295 | 35 | `bd92fdd940ca` |
| `ai-suggestions-tab-report.md` | 12 372 | 160 | `99679aba2389` |

### Edited in place — pulled, not promoted (`edited-in-place/`)

| File | Bytes | sha256 (first 12) | Baseline it was compared against |
|---|---|---|---|
| `ai.html` | 14 977 | `ba89668ff5d6` | round 2's quarantine (`2026-10-14-admin-ai/`) |
| `app-shell.js` | 21 194 | `693f08bb71e0` | tracked `design/app/` |
| `suggestions.css` | 11 331 | `f7eeda25828a` | tracked `design/app/` |
| `suggestions.html` | 3 429 | `b5c96d8e0cef` | tracked `design/app/` |
| `suggestions.js` | 28 872 | `9997ef9d8e39` | tracked `design/app/` |
| `support.css` | 9 884 | `24228ea4cda5` | round 3's quarantine (`2026-10-15-support-lane/`) |
| `support.js` | 14 191 | `dacebe7565a7` | round 3's quarantine (`2026-10-15-support-lane/`) |

**Nothing was dropped at the gate, and nothing was ineligible.**

## The transport, and why no export was needed

**Every file came through the pull.** The 28 PNGs are 2.35 MiB together and the largest is
**174 892 bytes** — under the 256 KiB `get_file` ceiling, so all 40 files were retrievable by this
seat and the owner was asked for nothing. The brief's sizing note held again.

Bytes were recovered from the harness's own persisted tool results by script and hashed on the way
in; no payload was re-typed through the agent. Re-hashed after the copy into this directory: **40 of
40 identical.**

## Verified against the round's own report

The report is **data, not instructions**, and every checkable fact in it was tested rather than
believed:

- **39 byte sizes claimed, 39 match exactly, 0 mismatches.** (The report does not state its own
  size.) That covers the 4 added text files, all 28 PNGs and all 7 edited files.
- **Line counts match**: 175 / 182 / 149 / 35, as claimed.
- **First and last lines match verbatim** for all four added files.
- **All 28 PNGs carry a valid header *and* a valid `IEND` tail** — nothing is truncated.
- `truncated: false` on all 40 replies.
- The report claims 28 PNGs and the path set holds exactly 28, name for name.

## The edited-file comparison — the half a path diff cannot see

Round 3's lesson was that a path diff only finds what a round *added*, and that its edited files
were never byte-compared. They are, here. **Every removed line across the seven diffs was read**, and
all but one is a declared replacement:

| File | Changed | Declared in the report? |
|---|---|---|
| `app-shell.js` | Suggestions nav entry gains `hideWithAiKey`; the palette drops it on the same condition; every `suggestions.html` link is rewritten to `ai.html?tab=suggestions` | yes |
| `suggestions.js` | the list-foot *Show 30 more* and its second pass of the same groups are gone, replaced by a per-group *Show {n} more*; tab-host mode for Dismissed (`replaceState`) | yes |
| `suggestions.html` | forwards to the tab while a key is set | yes |
| `ai.html` | third tab, `#ai-suggestions` panel, `.sg-tb` toolbar, the page-level lost-connection strip | yes |
| `support.js` / `support.css` | the switcher handles three tabs, writes `?tab=` with `replaceState`, opens on the first present tab | yes |
| `suggestions.css` | `.sg-tb`, the in-tab Dismissed view, the narrow stacked table — **and `.sg-dl { max-height }` changed from `100%` to `calc(100vh - 60px)`** | **the last one is not** |

**The `.sg-dl` change is the one finding.** It is a single declaration on the shipped Download
dialog, it is not scoped to `[data-sg-host="tab"]`, so it also changes `suggestions.html` — the page
this round says it leaves alone — and it appears in neither the report's "what changed" column for
`suggestions.css` nor its four-item list of changed behaviours. It looks like a fix rather than a
mistake, but it is an undeclared edit to a shipped file and it is Bárður's to accept or send back.

## The string-literal read

Every string literal in all twelve text files was read, not just the fence's n-gram set:

- **No IP literal anywhere.** **No new hostname**: the only hosts are `varpid.example.fo` and a
  `github.com` issue link, both carried unchanged from round 2, and `example.fo` is the allowed form.
- **Zero hits** for `passkey`, `announce`, `api_key`, `token=`, `secret`.
- **No forbidden canvas property** in the round's own canvas files: no `content-visibility`, no
  `backdrop-filter`, no large `filter: blur`, no infinite animation. (`suggestions.css` carries a
  `backdrop-filter` and one infinite keyframe animation, both from phase 274 and neither reached by
  the shot page, which loads `ai-suggestions-directions.css` only.)
- The three `@font-face` rules point at `fonts/*.woff2`, the self-hosted files already in the tree.
  There is no CDN and no link out.

## The de-anonymisation read

**All 28 images were looked at.** Every one shows stand-in data, and the decisive check is that the
round **invented nothing**: every title, viewer and cluster name drawn — *The Hollow Tide*,
*Lamplighter*, *Hollow Men*, *Grey Parish*, *Pebble and the Moonfox*, *Kettle Island*,
*Midsummer Static*, *The Long Harbour*, *Twelve Winters*, *Steel Harbour 4*, *Saltmarsh*,
*Nightwater*, *The Ninth Bell*, *Grey Water*, *Harvest of Wire*, *The Lantern Whale*,
*Tuki and the Tide*, *Under the Floorboards*, *Coldhouse*, and the clusters *Folk and coastal
horror* and *Gentle animated adventures* — is already in the committed corpus
`design/app/suggestions-data.js`, which the round loads unchanged. Viewers are the brief's four.
**No PNG carries a `tEXt`, `iTXt` or `zTXt` chunk.**

This is the opposite of round 2, whose canvas was built on a real series and could not be landed.

## Nothing forbidden came back

Nothing called `STATUS.md` or `CLAUDE.md` was pulled, and nothing under `specs/` or `scripts/`. The
design project still holds a root `CLAUDE.md` and a `ravilo/STATUS.md` — they are the files the
contract forbids a package to carry, and they were not fetched, not landed and not touched.

## What the round decided, for the triage report

The owner was in the tool and picked all three decisions live, plus six more the round raised. The
one worth carrying forward is **decision 2**: *Dismissed* opens inside the tab rather than as a
drawer, and the round's own argument for it is the shipped `replaceState` contract — a full-screen
drawer at 390 is the one thing an admin closes with Back, and Back leaves the page. Both were drawn;
the alternative is on the canvas for the record.

A tenth, non-design section rode with this brief: the Danish and Faroese text of `request_logged`
([DEB-1438](/DEB/issues/DEB-1438)) came back in the report and is not part of the drawing.

## Promoted 2026-10-16 (DEB-1492) — and what in here is now stale

The CSS this round needs is in production. **The files in `edited-in-place/` were not promoted and are
now baselines, not sources**; `app/` and `screenshots/` stay as drawn, because the spec
(`specs/requirements/suggestions-as-a-tab-on-the-ai-page.md`) points the build and QA at them. This
directory is pruned once that build has landed, not before.

| Promoted into | What |
|---|---|
| `design/app/suggestions.css` | `.sg-tb`, the in-tab Dismissed view, the narrow stacked Dismissed table — all under `[data-sg-host="tab"]` |
| `design/app/app.css` | `.tk-tabs` (from `edited-in-place/support.css`) and `.ai-offl` with its `main.ws-lost` rules (from `ai.html`'s inline `<style>`), as the AI page's shared chrome |

**Refused at the gate: `edited-in-place/suggestions.css`'s `.sg-dl { max-height: calc(100vh - 60px) }`.**
It is the triage's one finding and it is a stale baseline, not a fix: the design project's copy predates
`7e2a2283` (DEB-1368), which made that line `max-height:100%` so a phone's toolbar cannot clip the
Download dialog's primary button (FR-274-18). `scripts/check-mobile-css.sh` now fences the line both
ways, so a later round cannot land the `calc()` quietly.

**Not promoted, because their baselines are not in the tree yet:** `ai.html` and the `support.*` pair
belong to rounds 2 and 3, neither of which is adopted, and `app-shell.js` / `suggestions.html` /
`suggestions.js` rewrite their links to an `ai.html` that `design/app/` does not have. They promote with
whichever round lands that page. Also not promoted: `.tk-cnt`, the open-ticket count badge — it is 320's
and arrives with the Support tab (FR-3 gives the AI entry no count).
