# Phase 59 — Restore pagebar menu/split-button CSS (FR-MB1)

**Status:** Planned

## Goal

The CSS rules that style the **External links**, **Re-pull**, and **Save & sync** dropdown
menus — plus the split save button — in the media/series detail pagebar were accidentally
removed from `design/app/app.css` in two "updated designs" commits (`84a8c3a`, `43ed143`).
Without them the buttons render as unstyled text on a transparent background: the text sits
there but nothing looks like a button or a dropdown.

Restore the missing CSS block to `app.css` so both the live app and the design mockup share
the same source for these styles.

## Root cause

The style rules originally lived as an **inline `<style>` block inside `design/app/media.html`**
(page-local, only served the design mockup). Commit `170590b` correctly promoted them to
`app.css` (shared, shipped to the frontend via `syncDesignAssets`). Subsequent design-sync
commits removed them again, leaving the inline copy in `media.html` untouched — so the
**design mockup still renders fine** (it gets the inline copy), but the **live app gets no CSS
at all** for these components.

## Missing CSS (add to `app.css`)

```css
/* ===== pagebar dropdown menus + split save button ===== */
.menu-wrap, .split { position: relative; display: inline-flex; }
.menu-btn { cursor: pointer; }
.caret { font-size: .7rem; opacity: .7; }
.menu {
  position: absolute; top: calc(100% + 6px); right: 0; min-width: 224px; z-index: 60;
  padding: 6px; background: var(--fill); border: 1px solid var(--line-2);
  border-radius: 12px; box-shadow: 0 18px 50px rgba(0,0,0,.45); display: none;
}
.menu-wrap.open > .menu, .split.open > .menu { display: block; }
.menu-item {
  display: flex; align-items: flex-start; gap: 9px; padding: 8px 10px;
  border-radius: 8px; font-size: .86rem; font-weight: 600; color: var(--ink);
  cursor: pointer; white-space: nowrap; text-decoration: none;
}
.menu-item:hover { background: var(--fill-3); }
.menu-item .mi-ic { width: 16px; text-align: center; opacity: .7; flex: none; }
.menu-item .mi-sub {
  display: block; font-size: .72rem; color: var(--ink-soft); font-weight: 400; margin-top: 1px;
}
/* split button: primary face + caret welded together */
.split .btn.primary:first-child { border-top-right-radius: 0; border-bottom-right-radius: 0; }
.split .split-caret {
  border-top-left-radius: 0; border-bottom-left-radius: 0;
  margin-left: 1px; padding-left: 9px; padding-right: 10px;
}
```

## Where to place it

After the `.pagebar` / `.crumb` / `.page-sub` / `.backrow` block (around line 103 in the
current file), before the dock styles. Add a section comment
`/* ===== pagebar dropdown menus + split save button ===== */`.

## Secondary cleanup — deduplicate the inline copy in `media.html`

Once `app.css` has the rules, remove (or comment out) the identical `<style>` block that is
currently inline in `design/app/media.html`. The file loads `app.css` via the shell, so the
inline copy is redundant and will cause confusion again next time someone edits one but not
the other.

## Classes used by the Kotlin code

`MediaDetail.kt` renders these components at lines ~494–516:

| Wrapper | Contains |
|---------|---------|
| `span.menu-wrap#links-menu` | `.menu-btn` trigger + `.menu` dropdown with `.menu-item` links |
| `span.menu-wrap#repull-menu` | `.menu-btn` trigger + `.menu` dropdown with `.menu-item` actions |
| `span.split#save-split` | `btn.primary` face + `btn.primary.split-caret.menu-btn` + `.menu` dropdown |

All three use `wirePagebarMenus()` (MediaDetail.kt line ~2389) which adds the `.open` toggle
on click and Esc/outside-click to dismiss — already correct; only the CSS is missing.

## Non-goals

- No Kotlin changes required; the JS wiring is correct.
- No changes to `wf.css`; these are app-shell / page-level components, not design-system tokens.
- No visual redesign of the buttons; restore to the state they were in after `170590b`.

## Files

| File | Change |
|------|--------|
| `design/app/app.css` | Add the ~16-line CSS block after the pagebar section |
| `design/app/media.html` | Remove (or `/* */` comment out) the duplicate inline `<style>` block |
