# Report — round ai-suggestions-tab

No phase number. Where one would go, it is left blank: `phase-NNN`.

## What I drew

Suggestions as the third tab of the AI page (Support · Fetchers · Suggestions), key set and Seerr connected, admin only, at 1280 and 390. Two named directions for where the page's own header controls go. The recommended one (A) is carried through every state. The alternatives for decisions 2 and 3 are drawn beside their decision rows.

**Recommendation: A.** The shared header stays the same on every tab. Suggestions' title-row facts move word for word into a toolbar row that belongs to the tab panel. B makes the header re-render whenever the tab changes, which contradicts the shipped contract ("Only the tab panel toggles in the DOM").

### Direction headings, character for character

- `A · Toolbar under the tabs — the tab owns its controls`
- `B · Up into the shared header — the header changes with the tab`

### What moving costs the page

- its title (the page heading becomes `AI`; `Suggestions` survives only as the tab label)
- its sidebar entry between Library and Activity
- landing directly on it (the AI page opens on its first present tab unless the link says `?tab=suggestions`)
- its address (`suggestions.html` becomes the no-key page only)

None of its content is lost.

### Decided — owner, 2026-10-16

All recommendations taken, except question 9 (groups), which the owner decided differently:

1. Header controls: a toolbar under the tabs (A).
2. Dismissed: inside the tab, replacing the list, with Close; `?tab=suggestions&view=dismissed` via `replaceState`.
3. Sidebar AI entry: unchanged, `AI 2` — the open-ticket count only.
4. Fetchers is always a tab while a key is set, so Suggestions is never the only tab.
5. The AI page opens on its first present tab (Support); Suggestions only via `?tab=suggestions`.
6. Links to `suggestions.html` (the Dashboard card's included) land on `ai.html?tab=suggestions` while a key is set. Built into the mockup: `app-shell.js` rewrites every such link, and `suggestions.html` itself forwards there while a key is set.
7. Lost connection: the page-level strip above the tabs; Download, No thanks and Rebuild wait.
8. The narrow stacked Dismissed table is scoped to the tab; `suggestions.html` is left as it ships.
9. **Groups are AI-named, and each group is shown once.** I chose **Show {n} more under each group** over no Show more at all: it keeps the first paint at the 20 sized to the household and puts each weaker match under its own group. The list-foot *Show 30 more*, and the second pass of the same groups it opened, are removed. {n} is the server's count of that group's weaker matches (9 · 6 · 5 · 3 · 3 · 4).

The admin ships in English only, so no Danish or Faroese UI text is drawn.

## States

**Drawn**, each at desktop 1280 and at narrow 390:

- 1 · All three tabs present, Suggestions selected (dark and light)
- 2 · Suggestions is the only tab — drawn as **Fetchers · Suggestions**. I agree with the lean: Fetchers needs only a key and still holds its New auto-fetcher form, so with a key Suggestions can never be the only tab.
- 3 · Seerr unreachable
- 4 · The Dismissed view
- 5 · Empty
- 6 · The narrow breakpoint (the 390 frame of every state)
- Show more under each group (new, owner 2026-10-16)
- 7 · The longest string — English only; the admin ships in English, so no Danish or Faroese is drawn
- loading
- offline — drawn separately from 3. A lost WebSocket is the page's state, not the tab's; it disables Download, No thanks and Rebuild now.
- error — this is state 3.

**Not applicable:** 10-foot focus. There is no TV surface in this round.

**Skipped:**
- the no-key case (it ships today and does not change)
- the no-Seerr case (nothing to draw)
- the Fetchers tab's own empty panel (unchanged by this round)
- the Download sheet and the No-thanks picker (unchanged, and they behave the same inside the tab)

## Did any Suggestions string or control change?

**One string and three behaviours changed:**

1. **Dismissed.** Inside the tab, Dismissed replaces the list instead of appearing below it (decision 2 lean). Its URL is `?tab=suggestions&view=dismissed`, written with `replaceState`, instead of `#dismissed`. Close links to `?tab=suggestions` instead of `#`. `suggestions.html` keeps `#dismissed` exactly as it ships.
3. **Show more (owner, 2026-10-16).** The list-foot `Show 30 more` and its line (`weaker matches from the same build — the 20 above are the ones sized to the household`) are removed, along with the second pass of the same groups that opened beneath it. Each group now ends with `Show {n} more` · `weaker matches from the same build`, which opens that group's weaker matches in place. This is in `suggestions.js`, so it applies on `suggestions.html` (no key) too — one Suggestions list, one rule.
4. **AI-named groups** are the default in every drawing. `suggestions.js` already defaulted to them; the canvas now does too.
2. **Header facts.** The header row's facts (Seerr connected · Built · shown · Dismissed · Rebuild now) now sit in the tab's toolbar `.sg-tb`, in the same order. The `Suggestions` h1 is gone; the word survives as the tab label.

## New UI strings (en / da / fo)

### New (English only — the admin ships in English)

- `ai_conn_lost` — Connection to this server lost · reconnecting. What you see is what it last sent. Nothing is sent until it's back — Download, No thanks and Rebuild wait.

- `sg_group_more` — Show {n} more · weaker matches from the same build (replaces `Show 30 more` · `weaker matches from the same build — the 20 above are the ones sized to the household`)

Every other string drawn already exists.

### Translation only — not part of the round, not on the canvas

- `request_logged` (a viewer-facing support-thread line, not admin UI)
  - en: Thanks — this is logged as request #{n}. Adding it needs a decision from the people who run this server, so it won't happen on its own.
  - da: Tak — det er registreret som ønske #{n}. At tilføje det kræver en beslutning fra dem, der driver denne server, så det sker ikke af sig selv.
  - fo: Takk — hetta er skrásett sum ynski #{n}. At leggja tað afturat krevur eina avgerð frá teimum, ið reka hendan ambætaran, so tað hendir ikki av sær sjálvum.

`{n}` is present once, unchanged, in both translations, directly after `#`.

## Files

This report itself (`app/ai-suggestions-tab-report.md`) is not listed: its own size can't be stated inside it.

### Added — text

| path | bytes | lines | first line | last line |
|---|---|---|---|---|
| `app/AI Suggestions Tab - Directions.html` | 24585 | 175 | `<!doctype html>` | `</html>` |
| `app/ai-suggestions-directions.css` | 16207 | 182 | `/* ai-suggestions-tab round — the one sibling stylesheet for "AI Suggestions Tab - Directions.html" and its 1:1 shot page.` | `.shotpage .slot.show{display:block}` |
| `app/ai-suggestions-directions.js` | 15425 | 149 | `/* ai-suggestions-tab round — renders every admin frame on the directions canvas and on the 1:1 shot page.` | `})();` |
| `app/ai-suggestions-tab-shots.html` | 2295 | 35 | `<!doctype html>` | `</html>` |

How the doc and shot page load their assets:
- The directions doc loads one sibling stylesheet, `ai-suggestions-directions.css`.
- Its fonts come from `app/fonts/*.woff2`.
- Its data comes from the existing `app/suggestions-data.js`, unchanged.
- There is no CDN and no link out.
- `ai-suggestions-tab-shots.html` renders each frame at 1:1 for the PNG export.

### Added — PNGs

All 28 are in `screenshots/ai-suggestions-tab/`, at 1 device pixel per CSS pixel, 8-bit RGB. All are under 192 KiB; the largest is 174892 bytes.

| file | bytes | size |
|---|---|---|
| ai-suggestions-tab-header-desktop-all-tabs.png | 169009 | 1280×800 |
| ai-suggestions-tab-header-narrow-all-tabs.png | 78724 | 390×844 |
| ai-suggestions-tab-toolbar-desktop-all-tabs-light.png | 174892 | 1280×800 |
| ai-suggestions-tab-toolbar-desktop-all-tabs.png | 168436 | 1280×800 |
| ai-suggestions-tab-toolbar-desktop-dismissed-drawer.png | 167046 | 1280×800 |
| ai-suggestions-tab-toolbar-desktop-dismissed.png | 98157 | 1280×800 |
| ai-suggestions-tab-toolbar-desktop-empty.png | 92621 | 1280×800 |
| ai-suggestions-tab-toolbar-desktop-loading.png | 48871 | 1280×800 |
| ai-suggestions-tab-toolbar-desktop-longest.png | 48918 | 1280×420 |
| ai-suggestions-tab-toolbar-desktop-offline.png | 173022 | 1280×800 |
| ai-suggestions-tab-toolbar-desktop-only-tab.png | 167666 | 1280×800 |
| ai-suggestions-tab-toolbar-desktop-seerr-unreachable.png | 163689 | 1280×800 |
| ai-suggestions-tab-toolbar-desktop-show-more.png | 78146 | 1280×420 |
| ai-suggestions-tab-toolbar-desktop-sidebar-two-counts.png | 21712 | 248×600 |
| ai-suggestions-tab-toolbar-desktop-sidebar.png | 21252 | 248×600 |
| ai-suggestions-tab-toolbar-desktop-tab-two-counts.png | 50182 | 1280×420 |
| ai-suggestions-tab-toolbar-narrow-all-tabs-light.png | 82492 | 390×844 |
| ai-suggestions-tab-toolbar-narrow-all-tabs.png | 80850 | 390×844 |
| ai-suggestions-tab-toolbar-narrow-dismissed-drawer.png | 49830 | 390×844 |
| ai-suggestions-tab-toolbar-narrow-dismissed.png | 69443 | 390×844 |
| ai-suggestions-tab-toolbar-narrow-empty.png | 63351 | 390×844 |
| ai-suggestions-tab-toolbar-narrow-loading.png | 22085 | 390×844 |
| ai-suggestions-tab-toolbar-narrow-longest.png | 38166 | 390×640 |
| ai-suggestions-tab-toolbar-narrow-offline.png | 82175 | 390×844 |
| ai-suggestions-tab-toolbar-narrow-only-tab.png | 80061 | 390×844 |
| ai-suggestions-tab-toolbar-narrow-seerr-unreachable.png | 76521 | 390×844 |
| ai-suggestions-tab-toolbar-narrow-show-more.png | 54809 | 390×640 |
| ai-suggestions-tab-toolbar-narrow-sidebar.png | 40236 | 390×844 |

### Existing files edited in place

| path | bytes | lines | first line | last line | what changed |
|---|---|---|---|---|---|
| `app/ai.html` | 14977 | 158 | `<!DOCTYPE html>` | `</html>` | links `suggestions.css`; third tab `Suggestions`; new `#ai-suggestions` panel (`.sg-tb` toolbar + the Suggestions content ids); loads `suggestions-data.js` + `suggestions.js`; the page-level lost-connection strip above the tabs, with a Connected / Connection lost preview toggle in the Suggestions fence |
| `app/app-shell.js` | 21194 | 300 | `/* Injects the persistent left sidebar, mobile drawer and the ambient scan dock into every app page.` | `})();` | the Suggestions nav entry gains `hideWithAiKey` — absent from the sidebar and the command palette while a key is set; every `suggestions.html` link is sent to `ai.html?tab=suggestions` while a key is set |
| `app/support.js` | 14191 | 128 | `/* Jellystructure — Support (support-lane). Renders into #ai-support (AI page tab: list → detail with the forward` | `})();` | tab switcher handles three tabs, sets `main[data-tab]`, writes `?tab=` with `replaceState`, opens on the first present tab |
| `app/support.css` | 9884 | 97 | `/* Jellystructure — Support (support-lane, decided 2026-10-14): a tab on the AI page, the open-ticket count on the AI` | `.tk-lg i { font-style:normal; font-family:'JetBrains Mono',monospace; font-size:.76rem; color:var(--ink-soft); text-align:right; }` | two show/hide rules for the third panel |
| `app/suggestions.css` | 11331 | 109 | `/* Phase 274 — the Suggestions page (sg-*). Every selector starts with .sg-, so it ships next to wf.css untouched.` | `.sg-dlb.as-sheet .sg-grab{display:block}` | `.sg-tb` toolbar; the in-tab Dismissed view; the narrow stacked Dismissed table — all scoped to `[data-sg-host="tab"]` |
| `app/suggestions.js` | 28872 | 305 | `/* Suggestions page (design brief 2026-09-27). Renders SUGG from suggestions-data.js; the server builds the` | `})();` | tab-host mode for Dismissed (`replaceState`, `?view=dismissed`, Close); guards the absent `#sg-qs` record panel; Show more moved under each group (owner, 2026-10-16) — this last change applies on `suggestions.html` too |
| `app/suggestions.html` | 3429 | 51 | `<!DOCTYPE html>` | `</html>` | forwards to `ai.html?tab=suggestions` (keeping `#dismissed` as `&view=dismissed`) while a key is set — the page now exists only for the no-key case |

No file was created called `STATUS.md` or `CLAUDE.md`, and nothing was written under `specs/` or `scripts/`.
