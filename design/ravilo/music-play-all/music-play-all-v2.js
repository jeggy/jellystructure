/* music-play-all v2 — one recommendation, nothing left to pick. The quiet pair (dir Q): 28 px pills in the sort pill's style, on the count line.
   Frames from music-play-all-kit.js (window.MPK); each carries data-png. MPA.solo(id) / MPA.unsolo() for the PNG export. */
(function () {
  const K = window.MPK, C = document.getElementById('cv');
  const X0 = 80, GAP = 50, WIDTH = 2650;
  let Y = 80;
  function put(x, y, html) { const t = document.createElement('div'); t.innerHTML = html.trim(); const el = t.firstElementChild; el.style.left = x + 'px'; el.style.top = y + 'px'; C.appendChild(el); return el; }
  function bottom() { let b = 0; [...C.children].forEach(e => { b = Math.max(b, e.offsetTop + e.offsetHeight); }); return b; }
  function sec(n, title) { put(X0, Y, `<div class="rule" style="width:${WIDTH}px"></div>`); Y += 44; put(X0, Y, `<div class="sech"><div class="n">${n}</div><h2>${title}</h2></div>`); Y = bottom() + 34; }
  function row(items, start) {
    let x = start || X0; const y = Y;
    items.forEach(it => {
      put(x, y, `<div class="cap" style="width:${it.w}px">${it.cap}</div>`);
      const f = put(x, y + 30, it.html);
      if (it.note) put(x, y + 30 + f.offsetHeight + 14, `<div class="note" style="width:${it.w}px">${it.note}</div>`);
      x += it.w + GAP;
    });
    Y = bottom() + 84;
  }
  const P = (id, cap, o, note) => ({ cap, note, w: o.land ? 780 : o.cmpw ? 400 : 360, html: K.phone(Object.assign({ id: 'music-play-all-' + id, dir: 'Q' }, o)) });
  const D = (id, cap, o, note) => ({ cap, note, w: 1120, html: K.desk(Object.assign({ id: 'music-play-all-' + id, dir: 'Q' }, o)) });
  const lbl = (x, y, w, html) => put(x, y, `<div class="lbl" style="width:${w}px">${html}</div>`);
  const st = (n, t) => { lbl(X0, Y, 2400, `<span class="tag new">State ${n}</span><h3>${t}</h3>`); Y = bottom() + 22; };

  /* ===== 0 · title + the one ask ===== */
  lbl(X0, Y, 1500, `<span class="tag new">Round · music-play-all · v2 · 2026-10-17 · no phase number</span><h2>Play all &amp; Shuffle all — v2, the quiet pair</h2><small>v1 offered three directions and five open decisions. The owner said that was too much to decide, and that the buttons were too big. v2 is <b>one answer with no open decisions</b>: two small pills, <b>▶ Play all · ⤮ Shuffle all</b>, drawn in the sort pill’s own style at <b>28 px</b>, sitting on the line that already carries the count. Every state the brief names is carried through below. The stand-ins are the brief’s, unchanged.</small>`);
  Y = bottom() + 40;
  put(X0, Y, `<div class="panel" style="width:1100px"><h2>The only question</h2><p style="font-size:17px;margin:0 0 12px"><b>Is the quiet pair right?</b> If yes, everything below is the spec. If not, say what’s off and v3 changes only that.</p>
<ul><li><b>Size:</b> 28 px tall, 12.5 px Sora 600, 13 px glyphs. Both pills are quiet: no gradient, the sort pill’s fill and border. The tap area stays 44 px.</li>
<li><b>Place:</b> right-aligned on the count line (<i>30 albums</i> ··· <i>▶ Play all · ⤮ Shuffle all</i>). Where a page has no count line, the pills sit alone in that same place: at the top of Listen, under the genre heading, under the search field.</li>
<li><b>Behaviour:</b> it scrolls with the list. It is the same in portrait, landscape and the compact desktop. On the wide desktop the same pills sit right of the page title.</li>
<li><b>Everything else v1 asked you</b> has a default now. Section 3 lists what was dropped, one line each.</li></ul></div>`);
  put(X0 + 1170, Y, `<div class="panel" style="width:1170px"><h2>Fixed, and found while drawing</h2><ul>
<li><b>Fixed, not reopened:</b> the five surfaces; nothing on Playlists, Audiobooks, Playing, Queue or Profile. Album, artist and playlist pages keep their own Play / Shuffle. The pair replaces the queue, a search plays its whole expanded result once, and play follows the sort order with a cap at 1 000. Counts come only from the server. The bottom bar and mini player don’t move.</li>
<li><b>Sibling:</b> the pills are the <b>sort pill’s</b> style, not the album page’s big pair. That is an existing style, not a third one, and the album page keeps its loud <i>Play</i>. Flagged in case “sibling” was meant strictly.</li>
<li>The desktop album page puts its buttons under the title; desktop Artists, Albums and Songs have no sort pill; desktop search groups are built Artists · Songs · Albums (drawn here in the requirement’s order); the desktop has no <i>Playing from</i> line.</li>
<li>Listen also has <i>Continue listening</i> (not in the brief’s row list, so not drawn). If it’s there, the pair still sits at the top.</li>
<li>At 360 dp the top row already clips cast in Faroese today. This round doesn’t touch the top row.</li>
<li>Faroese: the settled toast says <i>sangum</i>, while shipped strings say <i>løg</i>. <i>Search "{q}"</i> has no da/fo in the brief.</li></ul></div>`);
  Y = bottom() + 90;

  /* ===== 1 · the answer, on the case that decides it ===== */
  sec('1 · The answer — on the crowded case (360 dp, mini player docked)', 'Today · v1 · v2 side by side');
  const cmp = [
    { cap: 'Today · nothing added', w: 360, html: K.phone({ id: 'music-play-all-today-phone-crowded', dir: 'none', page: 'albums', playing: 1 }) },
    { cap: 'v1 · the big pair (dropped)', w: 360, html: K.phone({ id: 'music-play-all-v1-phone-crowded', dir: 'A', page: 'albums', playing: 1 }) },
    P('quiet-phone-crowded', 'v2 · the quiet pair', { page: 'albums', playing: 1 }),
    P('quiet-phone-crowded-listen', 'v2 · Listen', { page: 'listen', playing: 1 }),
    P('quiet-phone-crowded-search', 'v2 · search · harbour', { page: 'search', playing: 1 }),
  ];
  row(cmp);
  const mrow = put(X0, Y - 60, `<div class="note" style="width:1200px" id="mpa-measure"></div>`);
  Y = bottom() + 70;

  /* ===== 2 · every state ===== */
  sec('2 · Every state', 'The quiet pair, carried through');
  st('1', 'Each surface with the pair in place · phone 360');
  row([P('quiet-phone-listen', 'Listen', { page: 'listen' }), P('quiet-phone-artists', 'Browse ▸ Artists', { page: 'artists' }), P('quiet-phone-albums', 'Browse ▸ Albums', { page: 'albums' }), P('quiet-phone-songs', 'Browse ▸ Songs', { page: 'songs' }),
    P('quiet-phone-genre', 'a genre · Indie Folk', { page: 'genre' }, 'Albums filtered, <i>‹ Genres</i>, the heading. The pills sit under the heading that names what they play.'), P('quiet-phone-search', 'a search · harbour', { page: 'search' }, 'Once, above the three groups.')]);
  st('1 · 9', 'Each surface · desktop wide (pills right of the title)');
  row([D('quiet-desktop-wide-listen', 'Listen', { page: 'listen' }), D('quiet-desktop-wide-artists', 'Artists', { page: 'artists' })]);
  row([D('quiet-desktop-wide-albums', 'Albums', { page: 'albums', playing: 1 }), D('quiet-desktop-wide-songs', 'Songs', { page: 'songs' })]);
  row([D('quiet-desktop-wide-genre', 'Indie Folk', { page: 'genre' }), D('quiet-desktop-wide-search', 'harbour', { page: 'search' }, 'On search the field is the head, so the pills sit to its right.')]);
  st('9', 'Each surface · desktop compact (under 600 dp, the phone layout)');
  row(['listen', 'artists', 'albums', 'songs', 'genre', 'search'].map(p => P('quiet-desktop-compact-' + p, 'compact · ' + p, { page: p, cmpw: 1 })));
  st('2 · 3 · 4 · error · casting', 'Loading, empty, one song, a failed request, casting');
  row([P('quiet-phone-loading', 'loading · Songs', { page: 'songs', loading: 1 }, 'The pills are there; the count is not yet.'),
    P('quiet-phone-empty-search', 'empty search · harbor', { page: 'search-empty' }, '<b>No pills.</b>'),
    P('quiet-phone-empty-genre', 'a genre with no songs', { page: 'genre-empty' }, '<b>No pills</b>, not greyed.'),
    P('quiet-phone-one-song', 'one song · kvøldsól', { page: 'search-one' }, '<b>Both stay.</b> The pair never depends on a count.'),
    P('quiet-phone-error', 'error / offline', { page: 'albums', toast: 'error' }, 'A toast; the pills don’t change. Offline is the same.'),
    P('quiet-phone-casting', 'casting · Stue', { page: 'albums', playing: 1, cast: 1 }, 'No visual difference.')]);
  row([D('quiet-desktop-wide-loading', 'desktop · loading', { page: 'albums', loading: 1 }), D('quiet-desktop-wide-capped', 'desktop · capped toast', { page: 'songs', big: 1, playing: 1, toast: 'capped' })]);
  st('5', 'Capped toast · Playing 1 000 of 3 214 songs');
  row([P('quiet-phone-capped-en', 'en', { page: 'songs', big: 1, playing: 1, toast: 'capped' }), P('quiet-phone-capped-da', 'da', { page: 'songs', big: 1, playing: 1, toast: 'capped', lang: 'da' }), P('quiet-phone-capped-fo', 'fo', { page: 'songs', big: 1, playing: 1, toast: 'capped', lang: 'fo' })]);
  st('6', 'Playing from · Your library · Indie Folk · Search "harbour"');
  row([P('quiet-phone-from-library', 'Your library', { page: 'np', npFrom: 'lib' }), P('quiet-phone-from-genre', 'Indie Folk', { page: 'np', npFrom: 'genre' }), P('quiet-phone-from-search', 'Search "harbour"', { page: 'np', npFrom: 'search', npSong: { t: 'Two Harbours', a: 'Harbour Lights', al: 'Salt on the Window' } }),
    P('quiet-phone-from-library-da', 'Dit bibliotek', { page: 'np', npFrom: 'lib', lang: 'da' }), P('quiet-phone-from-library-fo', 'Títt savn', { page: 'np', npFrom: 'lib', lang: 'fo' })]);
  st('7 · 8', 'Scrolled while playing, and landscape');
  row([P('quiet-phone-crowded-scrolled', '360 · playing · scrolled', { page: 'albums', playing: 1, scroll: 300 }, 'Scrolled, the page reads exactly as today.'),
    P('quiet-phone-land-crowded', 'landscape · Albums · playing', { page: 'albums', playing: 1, land: 1 }), P('quiet-phone-land-listen', 'landscape · Listen', { page: 'listen', playing: 1, land: 1 })]);
  st('longest string', 'Danish and Faroese at 360');
  row([P('quiet-phone-longest-da', 'da · Albums', { page: 'albums', lang: 'da', playing: 1 }), P('quiet-phone-longest-fo', 'fo · Albums', { page: 'albums', lang: 'fo', playing: 1 }),
    P('quiet-phone-longest-da-genre', 'da · Indie Folk', { page: 'genre', lang: 'da' }), P('quiet-phone-longest-fo-search', 'fo · harbour', { page: 'search', lang: 'fo' })]);
  st('10', 'Desktop keyboard focus · Tab reaches both, Enter starts');
  row([D('quiet-desktop-wide-focus-play', 'focus on Play all', { page: 'albums', focus: 'play', tabs: 1 }, 'Tab order: sidebar → <b>Play all</b> ① → <b>Shuffle all</b> ② → grid. The ring is 2 px <code>--ring</code> with a 2 px <code>--bg</code> gap.'),
    D('quiet-desktop-wide-focus-shuffle', 'focus on Shuffle all · Noir', { page: 'albums', focus: 'shuf', skin: 'noir', tabs: 1 })]);
  st('11', 'All three skins');
  row([P('quiet-phone-skin-aurora', 'Aurora', { page: 'albums', playing: 1 }), P('quiet-phone-skin-midnight', 'Midnight', { page: 'albums', playing: 1, skin: 'midnight' }), P('quiet-phone-skin-noir', 'Noir', { page: 'albums', playing: 1, skin: 'noir' }, 'No gradient to drop: the pills are ink-on-fill in every skin.')]);
  row([D('quiet-desktop-wide-skin-midnight', 'desktop · Midnight', { page: 'albums', playing: 1, skin: 'midnight' }), D('quiet-desktop-wide-skin-noir', 'desktop · Noir', { page: 'albums', playing: 1, skin: 'noir' })]);
  lbl(X0, Y, 1400, `<span class="tag no">10-foot focus</span><h3>Not applicable</h3><small>No TV surface in this round.</small>`);
  Y = bottom() + 90;

  /* ===== 3 · dropped ===== */
  sec('3 · Dropped, so there is nothing to pick', 'Every v1 option, closed with a default');
  put(X0, Y, `<div class="panel" style="width:1500px"><table class="tbl"><tr><th style="width:380px">v1 option</th><th>v2</th></tr>
<tr><td>A · a 38 px pair, Play all on the gradient</td><td><b>Replaced</b> by the quiet pair. The owner found it too big.</td></tr>
<tr><td>B · the pair pinned under the search field</td><td><b>Dropped.</b> It costs 114 px on every page, all the time.</td></tr>
<tr><td>C · one ⤮ in the top row, Play all in its menu</td><td><b>Dropped.</b> It hides Play all and overflows the top row at 360 dp.</td></tr>
<tr><td>Listen · the pair lower down</td><td><b>Dropped.</b> It reads as part of Recently played. The pair goes at the top.</td></tr>
<tr><td>Desktop · the pair under the title</td><td><b>Dropped.</b> It adds a row. Right of the title is free space.</td></tr>
<tr><td>Landscape · the pair on the search field’s row</td><td><b>Dropped.</b> The count line works in landscape too, so there is one rule everywhere.</td></tr>
<tr><td>Error toast wording</td><td>Reuses the shipped <i>Couldn’t play this</i>. No new string.</td></tr></table></div>`);
  Y = bottom() + 90;

  /* ===== 4 · strings ===== */
  sec('4 · Strings', 'What the drawing shows that is new');
  put(X0, Y, `<div class="panel" style="width:1500px"><table class="tbl"><tr><th>String</th><th>en</th><th>da</th><th>fo</th><th>Status</th></tr>
<tr><td><b>Play all</b></td><td>Play all</td><td>Afspil alle</td><td>Spæl øll</td><td>already ships as <code>music.play_all</code></td></tr>
<tr><td><b>Shuffle all</b></td><td>Shuffle all</td><td>Bland alle</td><td>Blanda øll</td><td>settled</td></tr>
<tr><td><b>capped toast</b></td><td>Playing {shown} of {total} songs</td><td>Afspiller {shown} af {total} sange</td><td>Spælir {shown} av {total} sangum</td><td>settled</td></tr>
<tr><td><b>Playing from · value</b></td><td>Your library</td><td>Dit bibliotek</td><td>Títt savn</td><td>settled</td></tr>
<tr><td><b>Playing from · value</b></td><td>Search "{q}"</td><td>—</td><td>—</td><td>English only in the brief</td></tr></table>
<p style="margin-top:14px">Also shown, all existing and unchanged: chips, row headings, the sort pill, counts, <i>Nothing matches “{q}”</i>, and <i>Couldn’t play this</i>.</p></div>`);
  Y = bottom() + 120;

  document.body.style.width = (X0 * 2 + WIDTH) + 'px';
  document.body.style.height = Y + 'px';

  // measured, not typed: px of list visible between the top of the first grid row and the mini player
  (function measure() {
    const m = id => { const f = C.querySelector(`[data-png="music-play-all-${id}"]`); if (!f) return '?'; const s = f.getBoundingClientRect().height / f.offsetHeight; const g = f.querySelector('.mpa-g2'), mi = f.querySelector('.mpa-mini'); return Math.round((mi.getBoundingClientRect().top - g.getBoundingClientRect().top) / s); };
    const t = m('today-phone-crowded'), a = m('v1-phone-crowded'), q = m('quiet-phone-crowded');
    mrow.innerHTML = `<b>Measured on this canvas:</b> the list visible above the mini player is ${t} px today, ${a} px with v1’s big pair (−${t - a}), and <b>${q} px with the quiet pair (−${t - q})</b>. Once the list is scrolled, all three are the same.`;
  })();

  const keep = { w: document.body.style.width, h: document.body.style.height };
  window.MPA = {
    ids: () => [...C.querySelectorAll('[data-png]')].map(e => e.dataset.png),
    solo(id) {
      document.body.classList.add('solo');
      [...C.children].forEach(e => { e.style.display = e.dataset.png === id ? '' : 'none'; });
      const f = C.querySelector(`[data-png="${id}"]`); f.dataset.l = f.style.left; f.dataset.t = f.style.top; f.style.left = '0px'; f.style.top = '0px';
      document.body.style.width = f.offsetWidth + 'px'; document.body.style.height = f.offsetHeight + 'px'; window.scrollTo(0, 0);
      return [f.offsetWidth, f.offsetHeight];
    },
    unsolo() {
      document.body.classList.remove('solo');
      [...C.children].forEach(e => { e.style.display = ''; if (e.dataset.l != null) { e.style.left = e.dataset.l; e.style.top = e.dataset.t; delete e.dataset.l; delete e.dataset.t; } });
      document.body.style.width = keep.w; document.body.style.height = keep.h;
    },
  };
})();
