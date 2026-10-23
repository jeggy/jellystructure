/* promo-shim.js — 2026-10-23 (DEB-2193).

   The round's surface builders are reused VERBATIM, so the promotion is rendered from the same one
   data file the owner's pictures came from and a difference in a picture is a difference in the CSS
   rather than in a second hand-written mockup. What the round drew with an inline style or a mockup
   class, the promoted stylesheet gives a class name — a production page cannot carry
   `style="color:var(--warn)"` and still be re-tinted for the light theme. This file makes exactly
   those substitutions, in the DOM, after the frames are built.

   Every line below is therefore also the engineer's markup list: left is what the round emits, right
   is what design/app/processing.css expects. Nothing here changes a single pixel of layout — it
   moves a declaration from an attribute into a rule. */

function promote(root) {
  const q = (sel) => [...root.querySelectorAll(sel)];
  const styleIs = (el, needle) => (el.getAttribute('style') || '').includes(needle);

  /* 1. The scope class. `.pfx` goes on each host; the Scan dialog's host is the SCRIM itself,
        because a dialog renders at body level and is nobody's descendant. `.open` is what reveals
        it, the same way wf.css reveals a .menu. */
  q('.pf-scrim').forEach((el) => el.classList.add('pfx', 'open'));

  /* 2. Refresh ▾ rides the shipped menu: wf.css's .menu-wrap / .menu own the open-and-close, and
        .menu-item owns the row. The round had its own .mi / .s, which no other menu in the admin
        uses. */
  q('.pf-menu').forEach((el) => {
    el.classList.add('menu');
    const wrap = el.closest('.menu-wrap');
    if (wrap) wrap.classList.add('open');
    q_in(el, '.mi').forEach((mi) => {
      mi.classList.remove('mi');
      mi.classList.add('menu-item');
      q_in(mi, '.s').forEach((s) => { s.classList.remove('s'); s.classList.add('mi-sub'); });
    });
  });

  /* 3. The accented notes and cards. --warn / --info / --hi as a border, by class. */
  q('.pf-note').forEach((el) => {
    if (styleIs(el, 'var(--warn)')) el.classList.add('warn');
    else if (styleIs(el, 'var(--line-2)')) el.classList.add('flat');
    else return;
    stripBorderLeft(el);
  });
  q('.card').forEach((el) => {
    if (styleIs(el, 'solid var(--warn)')) el.classList.add('pf-warn');
    else if (styleIs(el, 'solid var(--hi)')) el.classList.add('pf-hi');
    else return;
    stripBorderLeft(el);
  });

  /* 4. Two unstyled wrappers the round drew with an inline border: the Queued work list, and one
        feature's own checks indented under it. */
  q('[style]').forEach((el) => {
    if (styleIs(el, 'border-top:1px solid var(--line);margin-top:10px')) {
      el.classList.add('pf-queued');
      el.removeAttribute('style');
    }
    if (styleIs(el, 'border-left:2px solid var(--line)')) {
      el.classList.add('chk-sub');
      el.removeAttribute('style');
    }
  });

  /* 5. The toasts: the left rail is a variant and the glyph is `.ic`, so both are re-tinted on the
        light theme instead of being an inline --ok / --info / --warn on white. */
  q('.pf-toast').forEach((el) => {
    if (styleIs(el, 'var(--info)')) el.classList.add('info');
    else if (styleIs(el, 'var(--warn)')) el.classList.add('warn');
    el.removeAttribute('style');
    const glyph = el.firstElementChild;
    if (glyph && glyph.tagName === 'SPAN') { glyph.className = 'ic'; glyph.removeAttribute('style'); }
  });

  /* 6. A part that reports a failure inside the Scan dialog's right pane. */
  q('.tiny').forEach((el) => {
    if (styleIs(el, 'color:var(--warn)')) {
      el.classList.add('pf-line-warn');
      el.setAttribute('style', (el.getAttribute('style') || '').replace('color:var(--warn);', ''));
    }
  });
}

function q_in(el, sel) { return [...el.querySelectorAll(sel)]; }

function stripBorderLeft(el) {
  const kept = (el.getAttribute('style') || '')
    .split(';')
    .filter((d) => d.trim() && !d.includes('border-left'))
    .join(';');
  if (kept) el.setAttribute('style', kept); else el.removeAttribute('style');
}
