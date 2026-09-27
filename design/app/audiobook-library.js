/* Library → Audiobooks (admin brief §M1). Books · Authors · Series (Series only when any book has one,
   and only if M6·3 says it is a view). The household has exactly one book — the grid is drawn with one
   cell and no apology. Renders into #ab-lib. */
(function () {
  const B = window.BOOKS, root = document.getElementById('ab-lib');
  if (!B || !root) return;
  const esc = s => (s == null ? '' : String(s)).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const M = window.MUSIC;
  let view = 'books', state = 'one', q = '', facets = {}, openF = null;
  try { const u = new URLSearchParams(location.search); if (u.get('bstate')) state = u.get('bstate'); } catch (e) {}
  const books = () => state === 'empty' ? [] : state === 'many' ? B.all : [B.household];
  const needs = b => B.gaps(b).length > 0 || !!b.twoBooks;
  const FACETS = [
    { k: 'cover', l: 'Cover', f: b => b.cover ? 'has' : 'missing', v: [['has', 'has a cover'], ['missing', 'missing']] },
    { k: 'narr', l: 'Narrator', f: b => b.narrators.length ? 'known' : 'unknown', v: [['known', 'known'], ['unknown', 'unknown']] },
    { k: 'desc', l: 'Description', f: b => b.desc ? 'has' : 'missing', v: [['has', 'has one'], ['missing', 'missing']] },
    { k: 'parts', l: 'Parts', f: b => b.parts.length > 1 ? 'multi' : 'single', v: [['single', 'single file'], ['multi', 'multi-file']] },
    { k: 'needs', l: 'Needs you', f: b => needs(b) ? 'yes' : 'no', v: [['yes', 'a gap or two books in one folder'], ['no', 'nothing to fix']] },
    { k: 'format', l: 'Format', f: b => b.format, v: [['MP3', 'MP3'], ['M4B', 'M4B'], ['other', 'other']] },
    { k: 'lang', l: 'Language', f: b => b.language, v: [['da', 'Danish'], ['en', 'English'], ['fo', 'Faroese']] },
    { k: 'lib', l: 'Library', f: () => 'Bøger', v: [['Bøger', 'Bøger']] },
  ];
  const pass = (b, skip) => FACETS.every(f => f.k === skip || !facets[f.k] || !facets[f.k].size || facets[f.k].has(f.f(b)));
  const qHit = b => !q || [b.title, B.authorNames(b.authors), b.narrators.join(' ')].join(' ').toLowerCase().indexOf(q.toLowerCase()) >= 0;
  const shown = () => books().filter(b => pass(b) && qHit(b));
  const hasSeries = () => books().some(b => b.series) && window.MusicQ && MusicQ.get('abseries') === 'view';
  function chip(b) {
    if (b.twoBooks) return '<span class="mu-mchip w">two books?</span>';
    if (B.gaps(b).length) return '<span class="mu-mchip w">part ' + B.gaps(b)[0] + ' missing</span>';
    return '';
  }
  function cell(b) {
    const fin = (B.listeners[b.id] || []).filter(l => l.pos === 'finished').length;
    return '<a class="mu-cell" href="audiobook.html?b=' + b.id + '"><div class="mu-cov" style="' + (b.cover ? B.coverStyle(b) : B.wordmarkStyle(b)) + '">' + (b.cover ? '' : '<span class="mu-wm">' + esc(b.title) + '</span><i class="mu-nocov" title="No cover on disk — a task on this side"></i>') + chip(b) + '</div>'
      + '<div class="ttl">' + esc(b.title) + '</div><div class="sub">' + esc(B.authorNames(b.authors)) + '</div>'
      + '<div class="yr">' + B.fmtH(b.len) + ' · ' + (b.parts.length === 1 ? '1 file' : b.parts.length + ' parts') + (fin ? ' · <span style="color:var(--ok)">✓ finished by ' + fin + '</span>' : '') + '</div></a>';
  }
  function authorCell(a) {
    const n = books().filter(b => b.authors.indexOf(a.id) >= 0).length;
    return '<a class="mu-cell" href="author.html?au=' + a.id + '"><div class="mu-circ" style="' + (a.image ? M.artistStyle({ hue: a.hue }) : M.wordmarkStyle(a.name)) + '">' + (a.image ? '' : esc(M.initials(a.name)) + '<i class="mu-nocov"></i>') + '</div><div class="ttl">' + esc(a.name) + '</div><div class="yr">' + (n === 1 ? '1 book' : n + ' books') + '</div></a>';
  }
  function seriesList() {
    const m = {}; books().filter(b => b.series).forEach(b => { (m[b.series.name] = m[b.series.name] || { name: b.series.name, of: b.series.of, books: [] }).books.push(b); });
    return Object.values(m).map(s => '<div class="mu-sec">' + esc(s.name) + ' <span class="tiny muted" style="text-transform:none;letter-spacing:0;font-weight:500">' + s.books.length + ' of ' + s.of + ' in the library</span></div><div class="mu-grid">' + s.books.map(b => cell(b).replace('<div class="ttl">', '<div class="ttl"><span class="muted">' + b.series.n + ' · </span>')).join('') + '</div>').join('');
  }
  function fence() {
    const S = [['one', 'One book (the household)'], ['many', 'Many'], ['empty', 'Empty library']];
    return '<div class="mu-fence"><span class="fl">Preview · mockup only</span><span class="seg">' + S.map(s => '<span data-bstate="' + s[0] + '" class="' + (state === s[0] ? 'on' : '') + '">' + s[1] + '</span>').join('') + '</span><span class="tiny muted">“Many” is invented to show the triage and the Series view; the household has one book.</span></div>';
  }
  function facetBar() {
    return '<div class="mu-facets"><span class="muted tiny">filter:</span>' + FACETS.map(f => { const n = facets[f.k] ? facets[f.k].size : 0;
      return '<span class="mu-fc' + (openF === f.k ? ' open' : '') + '"><span class="mu-fbtn' + (n ? ' on' : '') + '" data-fopen="' + f.k + '">' + f.l + (n ? ' <span class="c">' + n + '</span>' : '') + ' ▾</span><div class="mu-fpop">'
        + f.v.map(v => { const c = books().filter(b => pass(b, f.k) && f.f(b) === v[0]).length, on = facets[f.k] && facets[f.k].has(v[0]);
          return '<div class="mu-fv' + (on ? ' on' : '') + (c ? '' : ' zero') + '" data-fk="' + f.k + '" data-fv="' + v[0] + '"><span class="bx">' + (on ? '✓' : '') + '</span><span>' + v[1] + '</span><span class="ct">' + c + '</span></div>'; }).join('') + '</div></span>'; }).join('')
      + '<span class="spacer" style="flex:1"></span><span class="select" style="width:auto;min-width:150px;"><span>sort: recently added</span></span></div>';
  }
  function render() {
    if (view === 'series' && !hasSeries()) view = 'books';
    const views = [['books', 'Books'], ['authors', 'Authors']].concat(hasSeries() ? [['series', 'Series']] : []);
    const all = books(), nNeeds = all.filter(needs).length, nCov = all.filter(b => !b.cover).length;
    let body;
    if (state === 'empty') body = '<div class="mu-empty"><h3>Nothing filed as audiobooks yet</h3><p>The <b>Bøger</b> library is mapped but holds no audiobooks. One folder per book, the parts inside it in order.</p><a class="btn sm" href="settings.html?tab=libraries#ab-libcard">Library card ›</a></div>';
    else if (view === 'authors') body = '<div class="mu-agrid">' + B.authors.filter(a => all.some(b => b.authors.indexOf(a.id) >= 0) && (!q || a.name.toLowerCase().indexOf(q.toLowerCase()) >= 0)).map(authorCell).join('') + '</div>';
    else if (view === 'series') body = seriesList();
    else { const l = shown(); body = l.length ? '<div class="mu-grid">' + l.map(cell).join('') + '</div>' : '<div class="muted" style="padding:40px 4px;">' + (q ? 'No audiobook matches “' + esc(q) + '”.' : 'No books match these filters.') + '</div>'; }
    root.innerHTML = '<div class="mu-top"><span class="seg">' + views.map(v => '<span data-bview="' + v[0] + '" class="' + (view === v[0] ? 'on' : '') + '">' + v[1] + '</span>').join('') + '</span>'
      + (state !== 'empty' ? '<span class="mu-status"><b>' + all.length + '</b> ' + (all.length === 1 ? 'book' : 'books') + ' · ' + all.reduce((s, b) => s + b.parts.length, 0) + ' files · ' + B.fmtH(all.reduce((s, b) => s + b.len, 0))
        + (nNeeds ? ' · <b class="w">' + nNeeds + '</b> <span class="w">need' + (nNeeds === 1 ? 's' : '') + ' you</span>' : '') + (nCov ? ' · <span class="w">' + nCov + ' without a cover</span>' : '') + '</span>' : '') + '</div>'
      + '<p class="page-sub" style="margin-top:0">Audiobooks from Jellyfin’s <b>Bøger</b> library. Jellyfin sees one item per file, so <b>the folder is the book</b> and the parts are put in order here. No provider covers a Danish shelf — the files’ own tags come first, then what you type, then suggestions.</p>'
      + fence() + (state !== 'empty' && view === 'books' ? facetBar() : '') + body;
    if (window.MusicQ && !document.getElementById('ab-qs-lib')) { const d = document.createElement('div'); d.id = 'ab-qs-lib'; root.after(d); MusicQ.mount(d, 'library'); MusicQ.on(() => { if (!root.hidden) render(); }); }
    const qp = document.getElementById('ab-qs-lib'); if (qp) qp.hidden = root.hidden;
  }
  root.addEventListener('click', e => {
    const v = e.target.closest('[data-bview]'); if (v) { view = v.dataset.bview; render(); return; }
    const s = e.target.closest('[data-bstate]'); if (s) { state = s.dataset.bstate; facets = {}; render(); return; }
    const fo = e.target.closest('[data-fopen]'); if (fo) { openF = openF === fo.dataset.fopen ? null : fo.dataset.fopen; render(); return; }
    const fv = e.target.closest('[data-fk]'); if (fv) { const k = fv.dataset.fk; facets[k] = facets[k] || new Set(); facets[k].has(fv.dataset.fv) ? facets[k].delete(fv.dataset.fv) : facets[k].add(fv.dataset.fv); render(); }
  });
  document.addEventListener('click', e => { if (openF && !e.target.closest('#ab-lib .mu-fc')) { openF = null; if (!root.hidden) render(); } });
  window.BookLib = { show(on) { root.hidden = !on; const qp = document.getElementById('ab-qs-lib'); if (qp) qp.hidden = !on; if (on) render(); }, search(s) { q = s || ''; if (!root.hidden) render(); } };
})();
