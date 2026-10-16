# Suggestions becomes a tab on the admin's AI page while an AI key is set

Task: DEB-1253

> Owner, 2026-10-15 (DEB-1253): *"As we have been designing and planning a new "AI" sidetab on the jellystructure
> admin site. We should move our "Suggestions" into this, so that would become a tab inside "AI" instead of it's own
> full page."* Asked where Suggestions goes when there is no AI key: *"Let's have it as a if thing. So if AI key is
> there, then it's under the AI page, otherwise it stays as is."* Tab order: *"Support / Fetchers / Suggestions"*.

## Scope

Admin only. Ravilo and the `/api/tv/**` wire are not touched: R320's *Suggested for you* row reads the API, not the
admin page. The Suggestions page itself is phase 274; this spec moves it and changes one rule of its list, and 274
carries the matching dated amendment (2026-10-16).

The design is round 5 of the design studio, drawn by the owner on 2026-10-16 with every decision picked live,
quarantined at `design/incoming/2026-10-16-ai-suggestions-tab/` (`7bb848ae`). Its `MANIFEST.md` is the triage; its
`app/ai-suggestions-tab-report.md` lists the decisions; the 28 PNGs under `screenshots/ai-suggestions-tab/` are what
the build is compared against. Direction **A** is the one built.

**This work builds the AI page itself.** No adopted spec built it before: round 2 (Fetchers / Auto-fetch) and round 3
(320's admin Support tab) drew it, and neither is built. The page is built here with Suggestions as its only tab, so
that Support and Fetchers each add a tab to it later rather than draw a second page. Whichever of those lands next
extends FR-2's tab list and nothing else.

Settings → AI (270) stays in Settings. 274's no-key page stays exactly as it ships, apart from FR-6 below.

## Current behaviour

- Suggestions is its own page, `#/suggestions`, with a sidebar entry between *Library* and *Activity*
  (FR-274-8a), shown while Seerr is connected (`SuggestionsApi.available()`, read once when the shell mounts,
  `Shell.kt`). Its header row carries *Suggestions* · *Seerr connected* · *Built {when}* · *{n} shown of 50* ·
  *Dismissed ({n})* · *Rebuild now* (FR-274-8). *Dismissed* is `#/suggestions?view=dismissed` and replaces the list.
- The list shows 20; one *Show {n} more* at the foot opens the 30 weaker matches as a **second pass of the same
  groups** below a fold (FR-274-9, Q3).
- The Dashboard's *Suggested films waiting* row links `/suggestions` (`DashboardRoutes.kt`).
- There is no `#/ai` route and no AI sidebar entry.
- Prod has Seerr and **no AI key**, so under this spec prod looks exactly as it does today.

## Requirements

### Which home is live

**FR-1 — Two homes, one at a time, decided by the server.** Suggestions' gate is unchanged (FR-274-1: no Seerr ⇒
nowhere). With Seerr connected:

| `[ai] api_key` | Suggestions lives | Sidebar |
|---|---|---|
| non-blank | the AI page, tab *Suggestions* | **AI**; no *Suggestions* entry |
| blank or absent | `#/suggestions`, as it ships | *Suggestions*, between *Library* and *Activity*; no **AI** entry |

The key test is the one 320 FR-320-1 names for the AI page: the key is non-blank. It is **not** `ai.enabled`, the
key's validity or the monthly limit — switching AI off, a revoked key or a spent limit leave Suggestions on the AI
page. The admin never works the home out for itself (`ConfigApi`'s `ai.api_key` is not read for this): it asks the
server, FR-2.

**FR-2 — The server names the AI page's tabs.** New admin route `GET /api/ai/page` → `{"tabs": ["suggestions"]}`,
the tabs present, in page order. The order is fixed: **Support · Fetchers · Suggestions** (owner, Q2); a tab is in the
list only when its own gate holds — for Suggestions, Seerr connected (FR-274-1). The route answers **404** when the key
is blank or when no tab is present, and a 404 means *no AI page*: no sidebar entry, no route. Admin-only, additive,
behind the admin's existing auth; it is not on the Ravilo wire.

Support and Fetchers are not built, so today the list is `["suggestions"]` or nothing. The page draws its tab strip
even with one tab, so it keeps its shape when the next tab lands. (Round 5 decided that once Fetchers is built it is
present whenever a key is set, so Suggestions will not be the only tab for long.)

*Amended 2026-10-16 (DEB-1499), for the build after this one:* the Support tab
([`support-is-a-tab-on-the-ai-page.md`](support-is-a-tab-on-the-ai-page.md), DEB-1500) is present whenever a key is set.
Once that is built, a key with no Seerr answers `{"tabs":["support"]}` instead of 404. This work's acceptance 1 is
unchanged, because it tests the build before Support exists.

**FR-3 — The sidebar follows the server, without a reload.** The shell reads `GET /api/ai/page` beside the existing
Seerr probe when it mounts: a 200 shows **AI** (below *Activity*, above the *Setup* group, icon `ai` from round 5's
`app-shell.js`), and a 200 whose `tabs` holds `suggestions` hides the *Suggestions* entry. The command palette follows
the sidebar: it offers *AI* or *Suggestions*, never both. After Settings → AI saves a key or clears it, the shell
reads the route again and updates the sidebar in place. **No count** on the AI entry: the open-ticket count drawn in
rounds 3 and 5 is 320's, and arrives with the Support tab.

**FR-4 — Old addresses land on the live home.** On every navigation to either address, the admin asks FR-2 and
redirects with `replaceState`, so Back never bounces:

- `#/suggestions` (and `?view=dismissed`) while the AI page holds Suggestions ⇒ `#/ai?tab=suggestions`
  (`&view=dismissed`). That is what the Dashboard row's `/suggestions`, bookmarks and old links reach; the Dashboard
  route is not changed.
- `#/ai?tab=suggestions` while it does not ⇒ `#/suggestions`.
- `#/ai` with no AI page ⇒ the Dashboard (the router's existing fallback).

### The AI page

**FR-5 — The page's chrome is the page's, and only the panel changes with the tab** (round 5 decision 1, direction A).

1. **Header:** `AI` as the page title and *Key & model → Settings* (`#/settings?tab=ai`). It is identical on every
   tab and does not re-render on a tab switch. The *API key valid · model reachable* badge round 2 drew is **not**
   built here: it needs a key check this work has no reason to spend, and it belongs to whichever spec adopts round
   2's header.
2. **Tab strip** (`.tk-tabs`) under the header, in FR-2's order. A tab switch is `Router.updateQuery(tab, replace =
   true)`: no `hashchange`, no history entry, only the panel toggles. This is the constitution's tab-navigation rule —
   *"Back/Forward navigate between pages, not between tabs. Deep-linking and refresh still reconstruct the correct tab
   from the URL."*
3. **Landing:** `#/ai` with no `tab`, or a `tab` that is not present, opens the **first present tab** and corrects the
   URL with `replaceState` (decision 5). Suggestions is reached directly only by `?tab=suggestions`.

**FR-6 — The Suggestions tab is the Suggestions page, hosted** (`renderSuggestions` into the panel, unchanged).

1. **The header facts become the tab's toolbar** `.sg-tb`, first in the panel, word for word and in the same order:
   *Seerr connected* · *Built {when}* · *{n} shown of 50* · *Dismissed ({n})* · *Rebuild now*. There is no
   *Suggestions* `h1` in the tab — the word is the tab's label. Everything under the header (the *Built from* line, the
   grouped-by line, the list, the empty states, the banner, the Download dialog) is FR-274's, unchanged.
2. **Dismissed opens inside the tab, replacing the list** (decision 2): the *Dismissed ({n})* chip writes
   `?tab=suggestions&view=dismissed` with `replaceState` and shows `.on`; the view's *Close* removes `view` with
   `replaceState`. Back from the Dismissed view leaves the AI page, by FR-5.2 — that is why it closes with *Close* and
   not with Back. A refresh on `…&view=dismissed` reopens it. At ≤ 560 px the Dismissed table stacks (round 5's
   narrow table, scoped to the tab; the no-key page keeps its table as it ships).
3. **Everything that checks "am I still on the Suggestions page" accepts both homes** — today the poll guard
   `Router.currentPath().startsWith("/suggestions")` in `Suggestions.kt`. A rebuild's poll that outlives a switch to
   another tab stops the way it stops when the page is left.
4. **Seerr unreachable** is FR-274-1's banner, inside the tab (state 3).

**FR-7 — Losing the server is the page's state, not the tab's** (decision 7). When a request from the AI page to
jellystructure fails with no HTTP response, a strip shows **above the tab strip**, `.ai-offl`:

> **Connection to this server lost · reconnecting.** What you see is what it last sent. Nothing is sent until it's
> back — Download, No thanks and Rebuild wait.

While it shows, *Download*, *No thanks* and *Rebuild now* are disabled (the strip is their reason — 274's own
exception for "the list is still true"), and the page asks `GET /api/ai/page` every 5 s. The first answer removes the
strip, re-enables them and reloads the open tab. A Seerr failure is not this: Seerr unreachable is a 200 from
jellystructure and stays FR-6.4.

### The list, in both homes

**FR-8 — Each group is shown once; its weaker matches open under it** (owner, 2026-10-16, round 5 question 9). This
replaces FR-274-9's foot-of-list *Show {n} more* and the second pass of the same groups it opened. It is the one list
rule, so it applies **on the no-key page too**.

- The 20 shown are grouped as today. A group that has weaker matches ends with *Show {n} more* · *weaker matches from
  the same build*, where `{n}` is the count of that group's items with `shown = false` in the server's answer.
- Pressing it adds that group's weaker matches to the **same group's grid**, after its shown tiles, and the control
  goes. Other groups are unaffected. Nothing re-orders and nothing appears twice.
- No fold line, no list-foot control. A group with no weaker matches has no control.
- Which groups are open is the page's own view state, like the dialog's; it resets when the page or tab is rendered
  again.

No wire change: `GET /api/suggestions` already returns all 50 with `cluster` and `shown`.

### Styling

**FR-9 — The CSS comes from round 5, promoted by Rannvá before the build** (`design/` is hers), less one line. The
build adds no styling of its own beyond what is promoted:

- `.sg-tb`, the in-tab Dismissed view and the narrow stacked table, scoped to `[data-sg-host="tab"]`, into
  `design/app/suggestions.css`;
- `.tk-tabs` and `.ai-offl`, wherever the promotion gate puts shared chrome (`app.css`, or a scoped file with its
  `check-css-scoping.sh` row and a `syncDesignAssets` include).

**Not promoted: round 5's `.sg-dl { max-height: calc(100vh - 60px) }`.** It is not a fix. The round's copy of
`suggestions.css` predates `7e2a2283` (DEB-1368, 2026-10-15), which changed that line to `max-height: 100%` so a phone's
toolbar cannot clip the Download dialog's primary (FR-274-18). Taking it would revert that fix on both homes.
`design/app/suggestions.css` keeps `max-height: 100%`.

## Strings

Admin copy is English (FR-274 *Strings*). New: the FR-7 strip text, and *Show {n} more* · *weaker matches from the same
build* (FR-8), which replaces *weaker matches from the same build — the {n} above are the ones sized to the household*.

## Acceptance

1. **Backend:** `GET /api/ai/page` across four configs — no `[ai]` (404); a blank key, `"  "` (404); a key and no
   Seerr (404, no tab present); a key and Seerr (`{"tabs":["suggestions"]}`). `ai.enabled = false` with a key still answers 200.
2. **No key, Seerr connected** (prod's config): the sidebar, `#/suggestions`, its header, Dismissed and the Download
   dialog are exactly as before; `#/ai` and `#/ai?tab=suggestions` land on the Dashboard and on `#/suggestions`. The
   existing `tests/e2e/suggestions-download-dialog.spec.ts` passes unchanged.
3. **Key set, Seerr connected:** the sidebar has **AI** and no *Suggestions*; the palette likewise. `#/ai` opens on
   Suggestions with the toolbar under the tab strip, word for word. `#/suggestions` and `#/suggestions?view=dismissed`
   land on the tab (and its Dismissed view) and Back from there does not return to `#/suggestions`. A refresh keeps the
   tab and the view.
4. **Dismissed in the tab:** the chip opens it in place with `view=dismissed` and adds no history entry; *Close*
   returns to the list; Back from it leaves the AI page.
5. **Key saved / cleared** in Settings → AI: the sidebar swaps between *Suggestions* and **AI** without a reload.
6. **Show more**, on both homes: a group with weaker matches shows *Show {n} more* with its own count; pressing it puts
   exactly those tiles at the end of that group, and no group appears twice on the page.
7. **Lost server:** with the backend stopped, a press anywhere on the tab shows the strip above the tab strip and
   disables Download, No thanks and Rebuild now; with it back, the strip goes on its own within ~5 s.
8. The FR-274-18 Download-dialog checks (acceptance 1–6 of that amendment) hold inside the tab, and `.sg-dl` still
   reads `max-height: 100%`.

**Tests.** 1 is a backend route test. 2–7 are a Playwright spec, `tests/e2e/ai-page-suggestions-tab.spec.ts`, that stubs
`GET /api/ai/page` and the `/api/suggestions*` routes with `page.route` at a phone and a desktop viewport (the e2e stack
has no AI key and no Seerr). Stand-in titles and viewers only — `design/app/suggestions-data.js`'s.

## Non-goals

- The Support and Fetchers tabs, the AI entry's count, the key-status badge (320's admin half and round 2's spec).
- Any change to the no-key page other than FR-8.
- Any change on the Ravilo wire or on `/api/suggestions`.
