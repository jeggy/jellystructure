/* audiobook.html — the Book page (admin brief §M2). The folder is the book; the Details tab is an editor
   first; providers are suggestions. ?b=<book id>. The tag-writing switch lives in Settings (localStorage
   'js-ab-tagwrite', off) and changes what Save writes. */
(function () {
  const B = window.BOOKS, M = window.MUSIC, $ = id => document.getElementById(id);
  const esc = s => (s == null ? '' : String(s)).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const LOCK = '<svg viewBox="0 0 11 12" width="10" height="11"><rect x="1" y="5" width="9" height="6.2" rx="1.6" fill="currentColor"/><path d="M3.1 5V3.5a2.4 2.4 0 0 1 4.8 0V5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>';
  const P = new URLSearchParams(location.search);
  const b = B.book(P.get('b')) || B.household;
  const Q = k => (window.MusicQ ? MusicQ.get(k) : null);
  const tagWrite = () => Q('abtags') === 'draw' && localStorage.getItem('js-ab-tagwrite') === '1';
  let tab = P.get('tab') || 'details', locked = false, source = b.source, hasCover = b.cover, order = b.parts.slice();
  const f = { title: b.title, subtitle: b.subtitle, authors: b.authors.map(a => B.author(a).name), narrators: b.narrators.slice(), series: b.series ? b.series.name : '', seriesN: b.series ? b.series.n : '', year: b.year, publisher: b.publisher, language: b.language, genres: b.genres.slice(), desc: b.desc };
  const fromFiles = { title: 1, authors: 1, year: 1, genres: 1 };
  const edited = new Set(b.source === 'edited' ? ['narrators', 'desc'] : []);
  const applied = {};
  const chapters = b.chapters.map(c => Object.assign({}, c));
  let chSrc = b.chaptersFrom;
  document.title = 'Jellystructure — ' + b.title;
  function toast(m) { const t = document.createElement('div'); t.className = 'toast'; t.textContent = m; document.body.appendChild(t); setTimeout(() => t.remove(), 2200); }
  const SRC = { files: 'From the files', edited: 'Edited here', itunes: 'iTunes', google: 'Google Books', openlibrary: 'Open Library', audnexus: 'Audnexus' };

  function fence() {
    const S = [['vinterfaergen', 'The household’s book'], ['the-salt-road', 'Single M4B · a suggestion taken'], ['nordlys', 'Gap · edited here'], ['samlede', 'Folder holds two books']];
    $('ab-fence').innerHTML = '<div class="mu-fence"><span class="fl">Preview · mockup only</span><span class="seg">' + S.map(s => '<a href="audiobook.html?b=' + s[0] + '" class="' + (b.id === s[0] ? 'on' : '') + '">' + s[1] + '</a>').join('') + '</span><span class="tiny muted">only the first is in the library — the rest are invented to show states</span></div>';
  }
  function bar() {
    const tw = tagWrite();
    $('ab-bar').innerHTML = '<h1>' + esc(f.title) + ' <span class="muted">(' + esc(String(f.year)) + ')</span></h1><span class="spacer"></span>'
      + '<span class="menu-wrap"><span class="btn sm ghost menu-btn">External links <span class="caret">▾</span></span><div class="menu"><a class="menu-item" href="#"><span class="mi-ic">↗</span><span>Open in Jellyfin<span class="mi-sub">The book’s folder — Jellyfin lists ' + b.parts.length + ' separate item' + (b.parts.length > 1 ? 's' : '') + '</span></span></a>' + (source === 'itunes' ? '<a class="menu-item" href="#"><span class="mi-ic">↗</span><span>iTunes<span class="mi-sub">the accepted suggestion</span></span></a>' : '') + '</div></span>'
      + '<span class="menu-wrap"><span class="btn sm ghost menu-btn">⋯</span><div class="menu"><div class="menu-item" data-a="repull"><span class="mi-ic">⟲</span><span>Re-read the files<span class="mi-sub">Tags, part order and lengths from Jellyfin</span></span></div><div class="menu-item" data-a="ask"><span class="mi-ic">⌕</span><span>Ask the providers again<span class="mi-sub">iTunes · Google Books · Open Library · Audnexus</span></span></div></div></span>'
      + '<span class="split"><span class="btn primary" data-a="savesync">Save &amp; Sync ↻</span><span class="btn primary split-caret menu-btn"><span class="caret">▾</span></span><div class="menu">'
        + '<div class="menu-item" data-a="savesync"><span class="mi-ic">↻</span><span>Save &amp; Sync<span class="mi-sub">' + (tw ? 'cover.jpg + tags in ' + b.parts.length + ' files' : 'cover.jpg') + ', then ask Jellyfin to re-read</span></span></div>'
        + '<div class="menu-item" data-a="save"><span class="mi-ic">↓</span><span>' + (tw ? 'Save → cover + tags' : 'Save → cover.jpg') + '<span class="mi-sub">' + (tw ? 'Writes title, author, narrator, description into every part' : 'Tag writing is off in Settings — everything else stays here') + '</span></span></div>'
        + '<div class="menu-item" data-a="sync"><span class="mi-ic">↻</span><span>Sync Jellyfin<span class="mi-sub">Ask Jellyfin to re-read (no rewrite)</span></span></div></div></span>';
  }
  function head() {
    const ser = f.series ? '<span class="chip" style="font-size:.74rem">' + esc(f.series) + ' · Book ' + esc(String(f.seriesN)) + (b.series ? ' of ' + b.series.of : '') + '</span>' : '';
    $('ab-head').innerHTML = '<div class="mu-pb"><div class="mu-cov" style="' + (hasCover ? B.coverStyle(b) : B.wordmarkStyle(b)) + '">' + (hasCover ? '' : '<span class="mu-wm">' + esc(f.title) + '</span><i class="mu-nocov"></i>') + '</div><div>'
      + (f.subtitle ? '<div class="muted" style="font-size:1rem;margin-bottom:4px">' + esc(f.subtitle) + '</div>' : '')
      + '<div class="mu-by">by ' + b.authors.map(a => '<a href="author.html?au=' + a + '">' + esc(B.author(a).name) + '</a>').join(' &amp; ') + '</div>'
      + '<div class="mu-pbm">' + (f.narrators.length ? '<span>read by ' + esc(f.narrators.join(', ')) + '</span><span class="sep">·</span>' : '<span style="color:var(--ink-dim)">no narrator</span><span class="sep">·</span>') + '<span>' + esc(String(f.year)) + '</span><span class="sep">·</span><span>' + B.fmtH(b.len) + ' · ' + (b.parts.length === 1 ? '1 file' : b.parts.length + ' parts') + '</span><span class="sep">·</span><span class="mu-fmt">' + b.format + ' ' + b.kbps + '</span>' + ser + '</div>'
      + '<div class="mu-acts"><span class="ab-srcchip">' + (locked ? LOCK + ' ' : '') + esc(SRC[source]) + '</span><span class="chip" style="cursor:pointer" data-a="lock">' + (locked ? 'Locked · a re-read won’t change what you typed' : 'Lock') + '</span></div>'
      + '<div class="tiny muted" style="margin-top:10px;">Jellyfin reads no metadata file for audiobooks — ' + (tagWrite() ? 'so what you save here is written into the files’ own tags.' : 'what you type here reaches Ravilo, and Jellyfin’s own apps keep showing the files’ tags.') + '</div>'
      + '</div></div>';
    $('ab-crumb').innerHTML = '<a href="library.html?kind=books">Audiobooks</a> / ' + esc(f.title);
  }
  function banners() {
    const g = B.gaps(b), short = b.parts.filter(p => p.len < 300).length;
    let h = '';
    if (b.twoBooks) h += '<div class="mu-note w"><span class="badge warn">Needs you</span><div class="t"><b>This folder looks like two books.</b> The parts carry two different book titles — <i>' + esc(b.twoBooks[0]) + '</i> (parts 1–3) and <i>' + esc(b.twoBooks[1]) + '</i> (parts 4–6).</div><span class="btn sm primary" data-a="split">Split into two books…</span><span class="btn sm ghost" data-a="keep1">It’s one book</span></div>';
    if (g.length) h += '<div class="mu-note w"><span class="badge warn">A part is missing</span><div class="t"><b>Part ' + g.join(', ') + ' is not in the folder.</b> The numbering goes ' + (g[0] - 1) + ' → ' + (g[0] + 1) + '. A listener will hear the book jump; Parts shows the gap.</div><span class="btn sm ghost" data-a="gapok">Numbering is just wrong</span></div>';
    if (!f.narrators.length) h += '<div class="tiny" style="color:var(--ink-dim);margin:-4px 0 12px;">No narrator in the tags — type one in Details if you know it.</div>';
    $('ab-banners').innerHTML = h;
  }
  function tabs() {
    const T = [['details', 'Details'], ['parts', 'Parts'], ['chapters', 'Chapters'], ['artwork', 'Artwork']].concat(Q('ablisten') === 'show' ? [['listeners', 'Listeners']] : []).concat([['history', 'History']]);
    if (!T.some(t => t[0] === tab)) tab = 'details';
    $('tabbar').innerHTML = T.map(t => '<span data-tab="' + t[0] + '" class="' + (tab === t[0] ? 'on' : '') + '">' + t[1] + '</span>').join('');
  }

  /* ---- Details: the editor, with the Suggestions rail ---- */
  const srcTag = k => applied[k] ? '<span class="src">from ' + applied[k] + '</span>' : edited.has(k) ? '<span class="src">typed here</span>' : fromFiles[k] ? '<span class="src">from the files</span>' : '';
  const inp = (k, l, ph, cls) => '<div class="ab-f' + (cls ? ' ' + cls : '') + '"><label>' + l + srcTag(k) + '</label><input data-f="' + k + '" value="' + esc(f[k]) + '" placeholder="' + esc(ph || '') + '" class="' + (f[k] ? '' : 'empty') + '"></div>';
  const chips = (k, l, ph) => '<div class="ab-f"><label>' + l + srcTag(k) + '</label><div class="ab-chips' + (f[k].length ? '' : ' empty') + '">' + f[k].map((v, i) => '<span class="chip">' + esc(v) + ' <span style="cursor:pointer;opacity:.6" data-rmchip="' + k + ':' + i + '">✕</span></span>').join('') + '<input data-chipin="' + k + '" placeholder="' + esc(ph) + '"></div></div>';
  function suggestions() {
    if (b.id === 'the-salt-road') return [
      { p: 'itunes', name: 'iTunes', where: 'DK store', hit: { title: 'The Salt Road', by: 'Anna Berg', year: 2019 }, fill: { desc: b.desc, cover: '600 px', year: '2019' } },
      { p: 'audnexus', name: 'Audnexus', where: 'uk', hit: { title: 'The Salt Road: Northern Roads, Book 2', by: 'Anna Berg', year: 2019 }, fill: { narrators: 'Tom Hale', series: 'Northern Roads · Book 2', publisher: 'Northlight Audio' } },
      { p: 'google', name: 'Google Books', where: 'da', none: 'Found the print edition only — nothing an audiobook needs.' },
      { p: 'openlibrary', name: 'Open Library', none: 'Nothing found for this title on Open Library.' }];
    const au = B.authorNames(b.authors);
    return [
      { p: 'itunes', name: 'iTunes', where: 'DK store', none: 'Nothing found for this title on iTunes. It knows ' + au + ' — 5 other audiobooks, none of them this one.' },
      { p: 'google', name: 'Google Books', where: 'da', none: 'Nothing found for this title on Google Books.' },
      { p: 'openlibrary', name: 'Open Library', none: 'Nothing found for this title on Open Library. It knows the author (17 works), not this book.' },
      { p: 'audnexus', name: 'Audnexus', where: 'uk · de', none: 'Nothing found for this title on Audnexus — it covers what Audible sells, and Audible has no Danish store.' }];
  }
  const FL = { desc: 'Description', cover: 'Cover', year: 'Year', narrators: 'Narrator', series: 'Series', publisher: 'Publisher' };
  function sugCard(s) {
    const head = '<div class="h">' + s.name + (s.where ? '<span class="p">' + s.where + '</span>' : '') + '</div>';
    if (s.none) return '<div class="ab-sug none">' + head + '<div class="nt">' + esc(s.none) + '</div></div>';
    const perField = Q('abapply') === 'field';
    return '<div class="ab-sug">' + head + '<div class="hit"><div class="th" style="' + B.coverStyle({ id: b.id + s.p, hue: (b.hue + 30) % 360 }) + '"></div><div><b style="font-size:.84rem">' + esc(s.hit.title) + '</b><div class="tiny muted">' + esc(s.hit.by) + ' · ' + s.hit.year + '</div></div></div>'
      + '<div class="tiny muted" style="margin:8px 0 2px">What it would fill</div>'
      + Object.keys(s.fill).map(k => { const done = applied[k] === s.name; return '<div class="fl' + (done ? ' done' : '') + '"><span class="k">' + FL[k] + '</span><span class="v">' + (done ? '✓ ' : '') + esc(s.fill[k]) + '</span>' + (perField && !done ? '<span class="btn sm ghost" data-apply="' + s.p + ':' + k + '">Apply</span>' : '') + '</div>'; }).join('')
      + (!perField || Object.keys(s.fill).some(k => applied[k] !== s.name) ? '<div style="margin-top:8px"><span class="btn sm" data-apply="' + s.p + ':*">Apply all</span></div>' : '') + '</div>';
  }
  function details() {
    return '<div class="ab-grid"><div><div class="ab-fields">' + inp('title', 'Title', '', 'wide') + inp('subtitle', 'Subtitle', 'none', 'wide')
      + chips('authors', 'Authors', 'add an author…') + chips('narrators', 'Narrators', 'add a narrator…')
      + inp('series', 'Series', 'none') + inp('seriesN', 'Position in series', '—') + inp('year', 'Year') + inp('publisher', 'Publisher', 'unknown')
      + '<div class="ab-f"><label>Language</label><select data-f="language">' + [['da', 'Danish'], ['en', 'English'], ['fo', 'Faroese'], ['no', 'Norwegian'], ['sv', 'Swedish']].map(l => '<option value="' + l[0] + '"' + (f.language === l[0] ? ' selected' : '') + '>' + l[1] + '</option>').join('') + '</select></div>'
      + chips('genres', 'Genres', 'add a genre…')
      + '<div class="ab-f wide"><label>Description' + srcTag('desc') + '</label><textarea data-f="desc" placeholder="None in the files and none from a provider. Type one if you have it." class="' + (f.desc ? '' : 'empty') + '">' + esc(f.desc) + '</textarea></div></div>'
      + '<div class="tiny muted" style="margin-top:10px">Saved as you type — the same write-through as everywhere else. ' + (tagWrite() ? 'Save writes it into the files.' : 'Ravilo shows it; Jellyfin’s own apps don’t, because tag writing is off.') + '</div></div>'
      + '<div><div class="mu-sec" style="margin-top:0">Suggestions <span class="tiny muted" style="text-transform:none;letter-spacing:0;font-weight:500">asked ' + (b.household ? 'at 03:05' : 'Tuesday') + '</span><span class="spacer" style="flex:1"></span><span class="btn sm ghost" data-a="ask">Ask again</span></div><div class="ab-rail">' + suggestions().map(sugCard).join('') + '</div></div></div>';
  }
  /* ---- Parts ---- */
  function parts() {
    const short = order.filter(p => p.len < 300).length;
    let rows = '', prev = null;
    order.forEach((p, i) => {
      if (prev && p.n > prev.n + 1) for (let n = prev.n + 1; n < p.n; n++) rows += '<tr class="gap"><td></td><td class="n">' + n + '</td><td colspan="5">part ' + n + ' · not in the folder</td></tr>';
      const jp = p.len < 300 ? '<span class="tiny muted">— under 5 minutes, Jellyfin never saves a position here</span>' : p.jfPos ? '<span class="mono tiny">saved at ' + M.fmtLen(p.jfPos) + '</span>' : '<span class="tiny muted">—</span>';
      rows += '<tr class="ab-tr" draggable="true" data-i="' + i + '"><td class="ab-grab" title="Drag to reorder">⋮⋮</td><td class="n">' + p.n + '</td><td>' + esc(p.title) + '</td><td class="num">' + M.fmtLen(p.len) + '</td><td><span class="mu-fmt">' + b.format + ' · ' + b.kbps + '</span></td><td>' + jp + '</td><td><span class="mu-play" data-play="' + i + '">▶</span></td></tr>';
      prev = p;
    });
    return (short ? '<div class="note blue" style="margin-bottom:12px">Jellyfin only saves a position after 5 minutes of a file, so it never remembers one in the ' + short + ' part' + (short > 1 ? 's' : '') + ' shorter than that. Ravilo keeps the book’s position itself.</div>' : '')
      + '<div class="mu-scroll"><table class="mu-tbl"><thead><tr><th></th><th>#</th><th>Title tag</th><th>Length</th><th>Format</th><th>Jellyfin position</th><th></th></tr></thead><tbody>' + rows + '</tbody></table></div>'
      + '<div class="tiny muted" style="margin-top:10px">Drag a row when the numbering is wrong — the order is kept here, the files are not renamed. Jellyfin’s position is Eyð’s, the only listener it has seen.</div>';
  }
  function chaptersTab() {
    const both = b.chaptersFrom === 'embedded';
    const src = chSrc === 'embedded' ? 'Embedded in the file — ' + chapters.length + ' chapters' : 'From the file boundaries — ' + (both ? 'one file, so one chapter' : 'the files carry no chapters of their own, so each part is one');
    const list = chSrc === 'embedded' || !both ? chapters : [{ n: 1, title: f.title, start: 0, len: b.len }];
    return '<div class="row center" style="gap:10px;margin-bottom:10px;flex-wrap:wrap"><span class="tiny muted">' + src + '</span><span class="spacer"></span>' + (both ? '<span class="seg"><span data-chsrc="embedded" class="' + (chSrc === 'embedded' ? 'on' : '') + '">Use embedded</span><span data-chsrc="files" class="' + (chSrc === 'files' ? 'on' : '') + '">Use file boundaries</span></span>' : '') + '</div>'
      + '<div class="mu-scroll"><table class="mu-tbl ab-ch"><thead><tr><th>#</th><th>Title · rename inline</th><th>Starts</th><th>Length</th></tr></thead><tbody>'
      + list.map((c, i) => '<tr><td class="n">' + c.n + '</td><td><input value="' + esc(c.title) + '" data-ch="' + i + '"></td><td class="num mono" style="font-size:.76rem">' + M.fmtLen(c.start) + '</td><td class="num">' + M.fmtLen(c.len) + '</td></tr>').join('') + '</tbody></table></div>';
  }
  function artwork() {
    const use = hasCover ? '<div class="mu-aw use"><div class="im" style="' + B.coverStyle(b) + '"></div><div class="cap"><b>cover.jpg</b><span class="d">600 × 600 · on disk</span><span class="chip" style="font-size:.66rem;margin-left:auto">🔒 lock</span></div></div>'
      : '<div class="mu-aw"><div class="im chk" style="display:flex;align-items:center;justify-content:center"><span class="tiny" style="color:var(--warn);font-weight:600">no cover.jpg · no embedded art</span></div><div class="cap"><b>cover</b><span class="d">missing — a task</span></div></div>';
    const cands = b.id === 'the-salt-road' ? '<div class="mu-art">' + [['iTunes', '600 px'], ['Google Books', 'zoom 3 · 512 px'], ['Embedded', 'in the M4B · 500 px']].map((c, i) => '<div class="mu-aw"><div class="im" style="' + B.coverStyle({ id: b.id + c[0], hue: (b.hue + i * 25) % 360 }) + '"></div><div class="cap"><b>' + c[0] + '</b><span class="d">' + c[1] + '</span><span class="btn sm ghost" style="margin-left:auto" data-a="usecover">Use</span></div></div>').join('') + '</div>'
      : '<div class="tiny muted">No candidates — no provider found this title, and the files carry no embedded art. Upload a photo of the cover, or a scan.</div>';
    return '<div class="mu-sec">Currently in use</div><div class="mu-art">' + use + '<label class="mu-aw" style="cursor:pointer;border-style:dashed"><div class="im" style="display:flex;align-items:center;justify-content:center;flex-direction:column;gap:6px;color:var(--ink-soft);font-size:.8rem" data-a="usecover">⬆<span>Upload a cover</span><span class="tiny muted">written as cover.jpg in the book folder</span></div></label></div>'
      + '<div class="mu-sec">Candidates · iTunes · Google Books · Open Library · embedded</div>' + cands;
  }
  function listeners() {
    const L = B.listeners[b.id] || [];
    return '<div class="tiny muted" style="margin-bottom:10px">Read-only — how far each person has got, from Ravilo. The admin never moves a listener’s position.</div><table class="mu-tbl"><tbody>'
      + L.map(l => { const pct = typeof l.pos === 'number' ? Math.round(l.pos / b.len * 100) : l.pos === 'finished' ? 100 : 0;
        return '<tr><td style="width:44px"><span class="ab-ring" style="--p:' + pct + '"></span></td><td><b>' + esc(l.who) + '</b></td><td>' + (typeof l.pos === 'number' ? B.fmtH(b.len - l.pos) + ' left · part ' + b.parts[B.partAt(b, l.pos)].n + ' · ' + esc(l.last) : l.pos === 'finished' ? 'finished ' + esc(l.last) : '<span class="muted">not started</span>') + '</td></tr>'; }).join('') + '</tbody></table>';
  }
  function history() {
    const H = [['2026-09-23 21:40', 'Scanned from Jellyfin · ' + b.parts.length + ' files grouped into one book by folder'], ['2026-09-27 03:05', 'Providers asked · ' + (b.id === 'the-salt-road' ? '2 suggestions' : 'nothing found on any of the four')]];
    if (b.id === 'the-salt-road') H.push(['2026-09-27 09:40', 'Eyð accepted iTunes’ description and cover, Audnexus’ narrator and series']);
    if (edited.size) H.push(['2026-09-26 20:12', 'Edited here · ' + [...edited].map(k => FL[k] || k).join(', ')]);
    return '<div class="mu-hist">' + H.reverse().map(h => '<div class="mu-hi"><span class="ts">' + h[0] + '</span><span>' + esc(h[1]) + '</span></div>').join('') + '</div>';
  }
  function panel() { tabs(); $('ab-panel').innerHTML = tab === 'parts' ? parts() : tab === 'chapters' ? chaptersTab() : tab === 'artwork' ? artwork() : tab === 'listeners' ? listeners() : tab === 'history' ? history() : details(); }
  function repaint() { bar(); head(); banners(); panel(); }

  /* ---- events ---- */
  document.addEventListener('click', e => {
    const mw = e.target.closest('.menu-wrap .menu-btn, .split .menu-btn');
    document.querySelectorAll('.menu-wrap.open, .split.open').forEach(o => { if (!mw || o !== mw.parentNode) o.classList.remove('open'); });
    if (mw) { mw.parentNode.classList.toggle('open'); return; }
    const t = e.target.closest('#tabbar [data-tab]'); if (t) { tab = t.dataset.tab; panel(); return; }
    const cs = e.target.closest('[data-chsrc]'); if (cs) { chSrc = cs.dataset.chsrc; panel(); return; }
    const rc = e.target.closest('[data-rmchip]'); if (rc) { const [k, i] = rc.dataset.rmchip.split(':'); f[k].splice(+i, 1); edited.add(k); repaint(); return; }
    const ap = e.target.closest('[data-apply]'); if (ap) { const [p, k] = ap.dataset.apply.split(':'); const s = suggestions().find(x => x.p === p);
      (k === '*' ? Object.keys(s.fill) : [k]).forEach(key => { applied[key] = s.name;
        if (key === 'desc') f.desc = s.fill.desc; else if (key === 'narrators') f.narrators = [s.fill.narrators]; else if (key === 'series') { f.series = 'Northern Roads'; f.seriesN = 2; } else if (key === 'cover') hasCover = true; else if (key === 'publisher') f.publisher = s.fill.publisher; else if (key === 'year') f.year = 2019; });
      source = p; repaint(); toast('Applied from ' + s.name); return; }
    const pl = e.target.closest('[data-play]'); if (pl) { pl.classList.toggle('on'); pl.textContent = pl.classList.contains('on') ? '❚❚' : '▶'; return; }
    const cov = e.target.closest('.mu-pb .mu-cov'); if (cov) { $('ab-lb-im').setAttribute('style', hasCover ? B.coverStyle(b) : B.wordmarkStyle(b)); $('ab-lb-cap').textContent = hasCover ? 'cover.jpg' : 'no cover on disk'; $('ab-lb').classList.add('on'); return; }
    if (e.target.closest('#ab-lb')) { $('ab-lb').classList.remove('on'); return; }
    const a = e.target.closest('[data-a]'); if (!a) return;
    const k = a.dataset.a;
    if (k === 'lock') { locked = !locked; head(); }
    else if (k === 'ask') toast('Asking iTunes · Google Books · Open Library · Audnexus…');
    else if (k === 'repull') toast('Re-reading ' + b.parts.length + ' files from Jellyfin');
    else if (k === 'usecover') { hasCover = true; repaint(); toast('cover.jpg written'); }
    else if (k === 'savesync' || k === 'save') toast(tagWrite() ? 'cover.jpg + tags written into ' + b.parts.length + ' files · Jellyfin re-reading' : 'cover.jpg written · Jellyfin re-reading ↻');
    else if (k === 'sync') toast('Sync requested ↻');
    else if (k === 'split') toast('Split into two books · parts 1–3 and 4–6 — each gets its own page');
    else if (k === 'keep1' || k === 'gapok') { a.closest('.mu-note').remove(); toast('Noted · this flag won’t come back for this folder'); }
  });
  document.addEventListener('change', e => {
    const x = e.target.closest('[data-f]'); if (x) { f[x.dataset.f] = x.value; edited.add(x.dataset.f); delete applied[x.dataset.f]; if (source === 'files') source = 'edited'; bar(); head(); toast('Saved'); }
    const c = e.target.closest('[data-ch]'); if (c) { chapters[+c.dataset.ch].title = c.value; toast('Chapter renamed'); }
  });
  document.addEventListener('keydown', e => {
    const ci = e.target.closest('[data-chipin]'); if (ci && e.key === 'Enter' && ci.value.trim()) { f[ci.dataset.chipin].push(ci.value.trim()); edited.add(ci.dataset.chipin); if (source === 'files') source = 'edited'; repaint(); }
  });
  let dragI = null;
  document.addEventListener('dragstart', e => { const r = e.target.closest('.ab-tr'); if (r) { dragI = +r.dataset.i; r.classList.add('drag'); } });
  document.addEventListener('dragover', e => { const r = e.target.closest('.ab-tr'); if (r && dragI != null) { e.preventDefault(); document.querySelectorAll('.ab-tr.over').forEach(x => x.classList.remove('over')); r.classList.add('over'); } });
  document.addEventListener('drop', e => { const r = e.target.closest('.ab-tr'); if (!r || dragI == null) return; e.preventDefault(); const to = +r.dataset.i; const [m] = order.splice(dragI, 1); order.splice(to, 0, m); dragI = null; panel(); toast('Order kept here · the files are not renamed'); });
  document.addEventListener('dragend', () => { dragI = null; document.querySelectorAll('.ab-tr.drag,.ab-tr.over').forEach(x => x.classList.remove('drag', 'over')); });
  if (window.MusicQ) { MusicQ.mount($('ab-qs'), 'audiobook'); MusicQ.on(repaint); }
  window.addEventListener('storage', repaint);
  fence(); repaint();
})();
