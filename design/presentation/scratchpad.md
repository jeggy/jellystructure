# Deck outline — Jellystructure & Ravilo (dev audience, ~12 min)

Audience: developers at work. Technical register. The method is the payload; the product is the evidence.
Title style: short topic noun-phrases (textbook style), consistent throughout. No punchline titles.

| # | Title | Form | Assets |
|---|---|---|---|
| 1 | Jellystructure & Ravilo | cover | — |
| 2 | Two halves, one repository | two-column cards | — |
| 3 | The household it runs in | big-number grid | — |
| 4 | The stack | table + mono constraint note | — |
| 5 | Part 1 · The product | section header | — |
| 6 | The admin surface | screenshot + caption rail | admin/01-dashboard |
| 7 | Pipeline observability | screenshot + caption rail | admin/04-activity |
| 8 | Curated channels | full-bleed screenshot + caption card | 38-channel-rail |
| 9 | The audio & subtitle picker | two screenshots + stats | 26, 29 |
| 10 | Intro & credits editing | screenshot + caption rail | admin/03-segments |
| 11 | Part 2 · The method | section header | — |
| 12 | Spec before code | mono file tree + counts | — |
| 13 | Four rules | 2×2 grid | — |
| 14 | Case study · the report | quote + code comment | — |
| 15 | Case study · the code history | quote + provenance chain | — |
| 16 | What the method costs | three honest items | — |
| 17 | Where the agent fits | three items | — |
| 18 | Guard rails | two script cards | — |
| 19 | Close | quote slide | — |

Read the titles alone: what it is → who runs it → what it's built with → the product evidence →
the method → the case study that justifies the method → costs → the agent → the guard rails → close.

Visual system: the product's own Aurora dark palette (`app/wf.css` tokens — #16181f floor,
#7b6ef0 accent, #b15cd0 → #00a4dc gradient), Space Grotesk display / Sora UI / JetBrains Mono
for code, IDs and phase numbers. Two backgrounds only: the dark floor and the slightly-lifted
panel for section headers.

Facts to keep straight (all from presentation-context.md / the repo):
- 463 media items, 170 series; en/da/fo; 5 channels; Android TV ×2, phone, web, Tizen
- ~79,000 lines Kotlin, 1,130 commits since 2026-06-11
- 41 admin spec files, 48 Ravilo spec files, 15 research reports; next numbers 169 / R208
- Same-language subtitle collisions: 46.5% of movies, 47.5% of series; worst case 32 unnamed tracks
- R202 provenance: R05 (c6233e93, 2026-06-19) → carried by R143 (a2de8eb8) → fixed as R202
- check-mobile-css.sh fences 18 CSS rules a design re-sync silently reverted 8 times
