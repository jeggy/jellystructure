/* Continue Watching · "Hide titles tagged" — the sub-row under a Continue Watching system row.
   Used by ravilo-config.html (Home content rows) and ravilo-builders.js (a collection's Custom rows).
   Each Continue Watching row owns its list: CwHideTags.mount(el, holder) reads/writes holder.hideTags.
   The list is part of the layout and is saved by the page's existing Save — no save of its own.
   Matching is the exact tag name (case-insensitive); the search below only *finds* tags.
   Stand-in tag catalogue (the real one is the server's, ~2 697 tags with title counts). */
(function () {
  const TOTAL = '2 697';
  const TAGS = { 'kids-tv': 27, 'cartoons': 14, 'kidnapping': 8, 'kids': 6, 'børnefilm': 1, 'animated-family-christmas-specials-from-the-archive': 2, 'old-kids-block': 0 };
  const L = { facet: 'Hide titles tagged', add: 'Add tag', ph: 'Search ' + TOTAL + ' tags…', top: 'Most used tags',
    res: (n, q) => n + ' tag' + (n === 1 ? '' : 's') + ' contain “' + q + '”', hint: 'Hides the exact tag only — never tags that merely contain it.',
    cnt: n => n === 1 ? '1 title' : n + ' titles', none: q => 'No tag called “' + q + '”.', none2: 'Tags come from your library — nothing is created here.', remove: t => 'Remove ' + t };
  const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  const mark = (t, q) => { const i = q ? t.indexOf(q) : -1; return i < 0 ? esc(t) : esc(t.slice(0, i)) + '<mark>' + esc(q) + '</mark>' + esc(t.slice(i + q.length)); };
  let openEl = null;

  function mount(el, holder, onChange) {
    if (!holder.hideTags) holder.hideTags = [];
    let q = '', open = false, hov = 0;
    const list = () => holder.hideTags;
    function results() {
      const s = q.trim().toLowerCase();
      return s ? Object.keys(TAGS).filter(t => t.includes(s)).sort((a, b) => (b.startsWith(s) - a.startsWith(s)) || TAGS[b] - TAGS[a])
        : Object.keys(TAGS).sort((a, b) => TAGS[b] - TAGS[a]).slice(0, 6);
    }
    function pickerHtml() {
      const s = q.trim().toLowerCase(), items = results();
      let h = '<div class="cw-pick" role="dialog"><label class="cw-ps">⌕<input class="cw-q" value="' + esc(q) + '" placeholder="' + esc(L.ph) + '" autocomplete="off"></label>';
      if (s && !items.length) h += '<div class="cw-none"><b>' + esc(L.none(q.trim())) + '</b><span>' + L.none2 + '</span></div>';
      else h += '<div class="cw-ph">' + (s ? esc(L.res(items.length, s)) : L.top) + '</div>' + items.map((t, i) => {
        const on = list().includes(t);
        return '<div class="cw-pi' + (on ? ' on' : '') + (i === hov ? ' hov' : '') + '" data-pick="' + esc(t) + '"><span class="cw-box">' + (on ? '✓' : '') + '</span><span class="nm">' + mark(t, s) + '</span><span class="n' + (TAGS[t] === 0 ? ' zero' : '') + '">' + L.cnt(TAGS[t]) + '</span></div>';
      }).join('');
      return h + '<div class="cw-pf">' + L.hint + '</div></div>';
    }
    function render(focus) {
      el.classList.add('cw-cond');
      el.innerHTML = '<span class="cw-facet">' + L.facet + '</span>' +
        list().map(t => '<span class="cw-chip" title="' + esc(t) + ' · ' + L.cnt(TAGS[t] != null ? TAGS[t] : 0) + '"><span class="nm">' + esc(t) + '</span><span class="n' + (TAGS[t] === 0 ? ' zero' : '') + '">' + (TAGS[t] != null ? TAGS[t] : 0) + '</span><button type="button" class="x" data-rm="' + esc(t) + '" aria-label="' + esc(L.remove(t)) + '">✕</button></span>').join('') +
        '<button type="button" class="cw-add' + (open ? ' open' : '') + '" aria-expanded="' + open + '">＋ ' + L.add + '</button>' + (open ? pickerHtml() : '');
      if (open && focus) { const i = el.querySelector('.cw-q'); if (i) { i.focus(); i.setSelectionRange(i.value.length, i.value.length); } }
    }
    function changed() { if (onChange) onChange(list()); document.dispatchEvent(new CustomEvent('cw-hide-tags-change', { detail: { tags: list().slice() } })); }
    function toggle(t) { const l = list(), i = l.indexOf(t); if (i < 0) l.push(t); else l.splice(i, 1); changed(); }
    function close() { if (!open) return; open = false; q = ''; hov = 0; if (openEl === close) openEl = null; render(); }
    el.addEventListener('click', e => {
      e.stopPropagation();
      const rm = e.target.closest('[data-rm]'); if (rm) { toggle(rm.dataset.rm); render(); return; }
      const pk = e.target.closest('[data-pick]'); if (pk) { toggle(pk.dataset.pick); render(true); return; }
      if (e.target.closest('.cw-add')) { if (open) close(); else { if (openEl) openEl(); open = true; openEl = close; render(true); } }
    });
    el.addEventListener('input', e => { if (e.target.classList.contains('cw-q')) { q = e.target.value; hov = 0; render(true); } });
    el.addEventListener('keydown', e => {
      if (!open) return;
      const items = results();
      if (e.key === 'Escape') { e.preventDefault(); close(); el.querySelector('.cw-add').focus(); }
      else if (e.key === 'ArrowDown') { e.preventDefault(); hov = Math.min(items.length - 1, hov + 1); render(true); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); hov = Math.max(0, hov - 1); render(true); }
      else if (e.key === 'Enter' && items[hov]) { e.preventDefault(); toggle(items[hov]); render(true); }
    });
    render();
    return { render, close };
  }
  document.addEventListener('click', () => { if (openEl) openEl(); });
  window.CwHideTags = { mount, TAGS };
})();
