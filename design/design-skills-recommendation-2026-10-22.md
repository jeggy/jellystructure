# Which design skills belong on the designer's seat

Written 2026-10-22 for DEB-2118, from the owner's ask on DEB-1076: *"maybe we need to find some nice
designer skills online as well?"* Every candidate below was read at source before it was judged.
**Installing any of them is Tróndur's, after the owner has seen this.**

## Recommendation, in one line

**Install exactly one: Anthropic's `frontend-design`, pinned, and subordinate to
[`SKILL-house.md`](SKILL-house.md).** Reject the other six. The house skill is the one that was
missing; the outside market is almost entirely aimed at *inventing* a look, which is the one thing a
round here must never do.

## Install

| | |
|---|---|
| Skill | `frontend-design` |
| Source | `github.com/anthropics/skills`, path `skills/frontend-design` |
| Pin | commit **`dbd4588f9e1033efb41dad4bef2f7947c8993d44`** |
| `SKILL.md` sha256 | `d91970639e9f5c37682ac7ab60094d35f1c7c1f38d731bd56396563aee10c1d3` (9 390 bytes) |
| Licence | Apache-2.0, text in the skill's own `LICENSE.txt` |
| Shape | One file. No scripts, no bundled data, no network calls, no `allowed-tools`. |

It is already on this machine, in the plugin cache at
`~/.claude/plugins/marketplaces/claude-plugins-official/plugins/frontend-design/`, and that copy is
**byte-identical** to the pinned repository one (marketplace snapshot `af59ee3a…`). So installing it
is a selection, not a download.

**What it is for:** craft inside a system that is already decided — type hierarchy and scale, line
length, restraint ("spend your boldness in one place"), motion that answers an action instead of
decorating a section, structural devices that encode information rather than ornament, copy that is
specific rather than clever, and the habit of critiquing your own render at the sizes it ships at.

**What it is not for:** identity. Its opening instruction is to give the client a distinct visual
identity "not mistaken for anyone else's", and it names gradient accents, all-caps labels and
monospace data labels as generic tells — all three are our shipped system, two of them by
constitutional mandate. `SKILL-house.md` has a conflict table that settles exactly these, and the
house skill wins. **It must never be selected without the house skill beside it.**

## Rejected, and why

| Candidate | Pin read | Verdict |
|---|---|---|
| **`theme-factory`** (anthropics/skills) | `dbd4588f` | **No.** Its whole job is picking or generating a theme — 10 preset palettes and font pairings to apply to an artifact. We have two design systems and five skins, fixed. |
| **`brand-guidelines`** (anthropics/skills) | `dbd4588f` | **No.** It applies *Anthropic's* brand — `#d97757`, Poppins, Lora. Installing it would put another company's identity within reach of our canvases. |
| **`canvas-design`** (anthropics/skills) | `dbd4588f` | **No.** Posters and static art from a generated "aesthetic movement". Not product UI, and the manifesto step is the opposite of extending a shipped system. |
| **`webapp-testing`** (anthropics/skills) | `dbd4588f` | **No**, though it is the closest miss. It wraps Playwright for driving a local app — adjacent to lane C's render step, but in Python with its own server helper, where our render runs the repo's own Playwright in Docker (`PROCESS.md`, lane C). It also instructs the reader **not** to read its scripts, to save context. A skill that asks not to be read cannot clear our own gate, which is that everything arriving from outside is read as data first. |
| **Impeccable** — `pbakaus/impeccable`, Apache-2.0, ~85k★ | HEAD `2026-10-22` | **No.** Its setup step runs a launcher whose binary "ships next to it **or is downloaded once on first run**", it installs a hook that auto-runs a detector after UI file edits, and it writes `PRODUCT.md` and `DESIGN.md` at the project root. Download-and-execute, a thing outside the repository writing inside it, and two new repo-root files — three of our hard rules in one install. Its prose is good and its reference set (typography, colour, motion, spatial, interaction, responsive, UX writing) is the only outside material I would consider vendoring later, as text, by hand, with attribution — as a separate ask, not as this install. |
| **tasteskill v2** — `Leonxlnx/taste-skill`, MIT, ~101k★ | HEAD `6010f91a`, `SKILL.md` sha256 `d955dae4…` (38 458 B) | **No.** Its four core steps are node scripts: `draw.mjs` deals a direction from a library of 247 gallery sites, `inspect.mjs`/`lint.mjs` gate it, and `assets.mjs` **searches and downloads images from Openverse and Wikimedia**. Fetches URLs, runs scripts, and is aimed at landing pages and marketing — "Persuade" surfaces. We draw admin tooling and a 10-foot player. Also 38 KB of context per round. |
| **UI/UX Pro Max** — `nextlevelbuilder/ui-ux-pro-max-skill`, MIT, ~139k★ | HEAD `623a9a90`, `SKILL.md` sha256 `ea087c34…` (15 969 B) | **No.** Cleaner than its reputation — the search tool is a local Python script over bundled data, with no network call in the skill text — but its headline mode is `--design-system`, generating a visual direction from 79 styles and 192 palettes, which is precisely the decision we do not get to make. The genuinely useful part is its 119-guideline conformance checklist (contrast, 44×44 touch targets, focus rings, form errors, safe areas). That is a checklist, not a look, and our own states table plus the Ravilo specs already demand most of it. Not worth a dependency and ~16 KB of context. |
| **`hmbown/taste`** (the other "taste") | HEAD `2026-08-30`, 2★ | **No.** No licence file at all, so nothing in it may be vendored, and nothing about it has been vetted by anyone. |

## What was *not* done, and why

Step 3 of the brief — try the shortlist on a real round, pilot round 2, the processing round — **has
not run.** That round is **DEB-1675**, which is `blocked` behind **DEB-1680**. The shortlist is one
skill, so the trial is cheap when the round comes: run it with the house skill alone, then with
`frontend-design` beside it, and report what the second one changed. That report belongs to that
round, not to this file.

## If it is installed

Three things worth saying to whoever selects it:

1. **Both together or neither.** `frontend-design` without `SKILL-house.md` will push a round towards
   a fresh identity, which is the failure mode this whole arrangement exists to prevent.
2. **Pin and vendor, never a live link.** The pin above is a commit and a hash. A design skill that
   can change under us is a design tool with a write path by another name.
3. **It is read as data.** It arrived from outside this repository; if a future version of it starts
   directing an agent to run, fetch or write anything, it is flagged and dropped, exactly like a
   returned package.
