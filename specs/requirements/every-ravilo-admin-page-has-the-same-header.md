# Every page in the admin's Ravilo section has the same header

Task: DEB-2806

> Owner, 2026-10-29 (DEB-2806), with a screenshot of *Ravilo · Layout* beside one of *Live TV*: *"Not all the
> Ravilo pages in jellystructure are using the same heading pattern. Let's make sure they use the same pattern and
> that it's the one with the ravilo logo on it etc"*

## Scope

Admin frontend only (`src/wasmJsMain/kotlin/dev/jellystructure/ui/`). No backend, no wire, no Ravilo client.

The admin sidebar's **Ravilo** group has five entries (phase 148, FR-RV-NAV1 A.1). They render three different
headers today:

| Sidebar entry | Route | Header today |
|---|---|---|
| Layout | `/ravilo?tab=layout` | jellyfish mark · **Ravilo · Layout** · `app config` badge · sticky bar with a bottom rule |
| Requests | `/ravilo?tab=requests` | the same, **Ravilo · Requests** |
| Preferences | `/ravilo?tab=preferences` | the same, **Ravilo · Preferences** |
| Live TV | `/livetv` | **Live TV**, plain: no mark, no badge, not sticky |
| Users & devices | `/ravilo-users` | **Users & devices**, plain, with a `Ravilo · from Jellyfin` badge, not sticky |

The editor's loading and error states also show a fourth header, a plain **Ravilo TV**. Phase 148 retired that
name. Live TV's loading state is plain **Live TV**.

The pattern the owner picked is the editor's, already drawn in `design/app/ravilo-config.html` and shipped. This
spec applies it to the other two pages and to the loading and error states, so **there is no design round**. The
`design/app/livetv.html` and `design/app/ravilo-users.html` mockups still draw the old headers. Where they and this
file disagree, this file wins. Refreshing them is Rannvá's, and nothing here waits on it.

Phase 148 B.2's titles (*Ravilo · Layout / Requests / Preferences*) are kept as they are and extended to the other
two pages. Phase 148 is not amended: it does not specify how Live TV's or Users & devices' header looks.

## Requirements

**FR-1 — One header for all five pages.** All five routes in the table render their pagebar through **one shared
function**, never through hand-written markup in each page. In order, the bar holds:
1. the Ravilo jellyfish mark;
2. the title, `Ravilo · <page>` (FR-2);
3. one source badge (FR-3);
4. a spacer, then that page's own controls (FR-4).

The mark and the title sit inside the `<h1>`, as on Layout today
(`display:flex;align-items:center;gap:.4em`).

**FR-2 — Titles.** The five titles are **Ravilo · Layout**, **Ravilo · Requests**, **Ravilo · Preferences**,
**Ravilo · Live TV** and **Ravilo · Users & devices**. After `Ravilo · `, each title is the page's sidebar label,
word for word.

**FR-3 — The source badge.** One `badge info` follows the title. It says one of two things:
- **`app config`** on a page that edits what Ravilo shows: the three editor tabs, and Live TV (which channels
  appear, their number, logo and order).
- **`from Jellyfin`** on a page that mirrors Jellyfin read-only: Users & devices. Its old badge said
  `Ravilo · from Jellyfin`, and the title now carries the word Ravilo.

Live TV keeps its own status badge (`● connected via Jellyfin` / `● not reachable`) among its controls. That badge
reports a state; the source badge says what kind of page this is.

**FR-4 — The bar's look, and each page's own controls.** Every bar is sticky the way Layout's is today:
`position:sticky;top:0;z-index:50;background:var(--bg);border-bottom:1px solid var(--line);margin-bottom:18px`.
Each page passes its controls into the shared function. They keep their markup, their element ids and their
behaviour:
- **editor:** the *Users & devices* link, `#rav-synced`, `#rav-save`;
- **Live TV:** the status badge, `#ltv-refresh`, the *Live TV in Ravilo* chip with `#ltv-master-toggle`;
- **Users & devices:** `#users-summary`, `#users-version-chip`, `#users-refresh-btn`.

On a narrow screen the bar wraps as `.pagebar` already does. The title is never truncated.

**FR-5 — Loading and error look like the loaded page.** While a page loads, and when it fails to load, its bar
shows the same mark, title and badge as the loaded page. The page's controls may be missing in those two states.
This replaces the editor's **Ravilo TV** loading and error shells and Live TV's plain loading shell. The header no
longer changes when the data arrives.

**FR-6 — The mark is drawn once.** The jellyfish SVG moves out of `RaviloConfig.kt`'s private `RAVILO_MARK` and
into one `internal` definition beside `brandMarkSvg` in `BrandMark.kt`. Its drawing and its inline size and glow do
not change. Only one Ravilo bar is in the DOM at a time, so its gradient id `ravJelly` can stay fixed.

## Non-goals

- No change under `design/`: no new CSS class, no `wf.css` edit. The mark keeps its inline style, as it has today.
- The sidebar, the browser tab title, and the Settings tabs that mention Ravilo stay as they are.
- What each page does below its bar stays as it is.

## Acceptance

Open each of the five Ravilo sidebar entries in the admin, at desktop width and at 390 px. Each bar shows the mark,
`Ravilo · <sidebar label>` and one source badge, and stays pinned when the page scrolls. Reload each one: the
loading state shows the same title, never **Ravilo TV**. Every control in the bar still works: Save, Refresh, the
Live TV toggle, the *Users & devices* link.
