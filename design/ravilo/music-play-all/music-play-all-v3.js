/* music-play-all v3 — the split: one control, ▶ Play all | ▾ (▾ opens Shuffle all), never on a row of its own.
   Frames from music-play-all-kit.js (window.MPK), dir 'S'. Each carries data-png. MPA.solo(id) / MPA.unsolo() for the PNG export. */
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
  const P = (id, cap, o, note) => ({ cap, note, w: o.land ? 780 : o.cmpw ? 400 : 360, html: K.phone(Object.assign({ id: 'music-play-all-' + id, dir: 'S' }, o)) });
  const D = (id, cap, o, note) => ({ cap, note, w: 1120, html: K.desk(Object.assign({ id: 'music-play-all-' + id, dir: 'S' }, o)) });
  const lbl = (x, y, w, html) => put(x, y, `<div class="lbl" style="width:${w}px">${html}</div>`);
  const st = (n, t) => { lbl(X0, Y, 2400, `<span class="tag new">State ${n}</span><h3>${t}</h3>`); Y = bottom() + 22; };

  /* ===== 0 · title + the rule ===== */
  lbl(X0, Y, 1500, `<span class="tag new">Round · music-play-all · v3 · 2026-10-17 · no phase number</span><h2>Play all &amp; Shuffle all — v3, one split button</h2><small>v2’s two pills are merged into <b>one control: ▶ Play all | ▾</b>. Tapping <i>Play all</i> plays; the ▾ opens a one-line menu with <i>⤮ Shuffle all</i>. This is the same shape as the album page’s <i>Play album ▾</i>, in the sort pill’s quiet fill. The rule: <b>it never gets a row of its own.</b> It always shares a row that is already there.</small>`);
  Y = bottom() + 40;
  put(X0, Y, `<div class="panel" style="width:1100px"><h2>Where it sits — one rule, per surface</h2><table class="tbl"><tr><th style="width:300px">Surface</th><th>Row it shares</th></tr>
<tr><td><b>Listen</b></td><td>The <b>top bar</b>, between Ravilo and cast. Listen has no sort pill, so the slot is free.</td></tr>
<tr><td><b>A search</b> (harbour)</td><td>The <b>top bar</b>, the same as Listen. A search has no sort pill and no count line.</td></tr>
<tr><td><b>Artists · Albums · Songs</b></td><td>The <b>count line</b>, right-aligned: <i>30 albums ··· ▶ Play all ▾</i>. The top bar already holds the sort pill, and the two don’t fit together at 360 dp.</td></tr>
<tr><td><b>A genre</b> (Indie Folk)</td><td>The <b>‹ Genres line</b>, right-aligned.</td></tr>
<tr><td><b>Desktop wide</b></td><td>The <b>page head</b>, right of the title, as in v2.</td></tr>
<tr><td><b>Compact desktop · landscape</b></td><td>The same as the phone.</td></tr></table>
<p style="margin-top:12px">It is 38 px in the top bar, matching the sort pill and cast, and 28 px on a text line. The tap area is 44 px either way.</p></div>`);
  put(X0 + 1170, Y, `<div class="panel" style="width:1170px"><h2>Fixed, and found while drawing</h2><ul>
<li><b>Fixed, not reopened:</b> the five surfaces; nothing on Playlists, Audiobooks, Playing, Queue or Profile. Album, artist and playlist pages keep their own buttons. The pair replaces the queue, a search plays its whole expanded result once, and play follows the sort order with a cap at 1 000. Counts come only from the server.</li>
<li><b>A rule this bends:</b> the top bar’s page slot is <i>“filled only with the page’s own filter”</i> (FR-R267-5c). On Listen and a search, v3 puts <i>Play all ▾</i> there. That is a change to an existing rule, so it is flagged rather than done quietly.</li>
<li><b>Sibling: resolved.</b> v2 flagged that its pills looked like the sort pill, not the album page’s pair. The split is the album page’s own <i>Play album ▾</i> shape, so it now reads as a sibling.</li>
<li><b>The ▾ needs an accessible name.</b> It should reuse whatever the album page’s ▾ ships with. If that has none, it is a new string. None is drafted here.</li>
<li><b>Shuffle is now two taps</b> (▾, then <i>Shuffle all</i>). This is the cost of merging the two buttons.</li>
<li>Carried from v1/v2: the desktop album page puts its buttons under the title; the desktop has no sort pill and no <i>Playing from</i> line; desktop search groups are built in another order; <i>Continue listening</i> is not drawn; the top row at 360 already clips cast in Faroese on Browse; Faroese <i>sangum</i> vs <i>løg</i>; <i>Search "{q}"</i> has no da/fo.</li></ul></div>`);
  Y = bottom() + 90;

  /* ===== 1 · the answer ===== */
  sec('1 · The answer — the crowded case (360 dp, mini player docked)', 'Today · v2 · v3, and the menu');
  row([
    { cap: 'Today · nothing added', w: 360, html: K.phone({ id: 'music-play-all-today-phone-crowded', dir: 'none', page: 'albums', playing: 1 }) },
    { cap: 'v2 · two pills on the count line', w: 360, html: K.phone({ id: 'music-play-all-v2-phone-crowded', dir: 'Q', page: 'albums', playing: 1 }) },
    P('split-phone-crowded', 'v3 · Albums · the count line', { page: 'albums', playing: 1 }),
    P('split-phone-crowded-listen', 'v3 · Listen · the top bar', { page: 'listen', playing: 1 }),
    P('split-phone-crowded-search', 'v3 · harbour · the top bar', { page: 'search', playing: 1 }),
    P('split-phone-crowded-genre', 'v3 · Indie Folk · the ‹ Genres line', { page: 'genre', playing: 1 }),
  ]);
  const mnote = put(X0, Y - 60, `<div class="note" style="width:1500px" id="mpa-measure"></div>`);
  Y = bottom() + 60;
  row([P('split-phone-menu-albums', '▾ open · on the count line', { page: 'albums', menu: 1 }, 'One item. Tap it and the whole list plays shuffled. Tap outside and the menu closes.'),
    P('split-phone-menu-listen', '▾ open · in the top bar', { page: 'listen', menu: 1 }),
    P('split-phone-menu-genre', '▾ open · on the ‹ Genres line', { page: 'genre', menu: 1 })]);

  /* ===== 2 · every state ===== */
  sec('2 · Every state', 'The split, carried through');
  st('1', 'Each surface · phone 360');
  row([P('split-phone-listen', 'Listen', { page: 'listen' }), P('split-phone-artists', 'Browse ▸ Artists', { page: 'artists' }), P('split-phone-albums', 'Browse ▸ Albums', { page: 'albums' }), P('split-phone-songs', 'Browse ▸ Songs', { page: 'songs' }),
    P('split-phone-genre', 'a genre · Indie Folk', { page: 'genre' }), P('split-phone-search', 'a search · harbour', { page: 'search' }, 'Once, in the top bar. It plays the whole expanded result.')]);
  st('1 · 9', 'Each surface · desktop wide (page head, right of the title)');
  row([D('split-desktop-wide-listen', 'Listen', { page: 'listen' }), D('split-desktop-wide-artists', 'Artists', { page: 'artists' })]);
  row([D('split-desktop-wide-albums', 'Albums', { page: 'albums', playing: 1 }), D('split-desktop-wide-songs', 'Songs', { page: 'songs' })]);
  row([D('split-desktop-wide-genre', 'Indie Folk', { page: 'genre' }), D('split-desktop-wide-search', 'harbour', { page: 'search' }, 'On search the field is the head, so the split sits to its right.')]);
  st('9', 'Each surface · desktop compact (under 600 dp, the phone layout)');
  row(['listen', 'artists', 'albums', 'songs', 'genre', 'search'].map(p => P('split-desktop-compact-' + p, 'compact · ' + p, { page: p, cmpw: 1 })));
  st('2 · 3 · 4 · error · casting', 'Loading, empty, one song, a failed request, casting');
  row([P('split-phone-loading', 'loading · Songs', { page: 'songs', loading: 1 }, 'The split is there; the count is not yet.'),
    P('split-phone-empty-search', 'empty search · harbor', { page: 'search-empty' }, '<b>Absent</b>: the top bar slot is empty.'),
    P('split-phone-empty-genre', 'a genre with no songs', { page: 'genre-empty' }, '<b>Absent</b>: the ‹ Genres line is as it is today.'),
    P('split-phone-one-song', 'one song · kvøldsól', { page: 'search-one', menu: 1 }, '<b>It stays, with its menu.</b> Shuffle of one plays that one song. The control never depends on a count.'),
    P('split-phone-error', 'error / offline', { page: 'albums', toast: 'error' }, 'A toast; the split doesn’t change. Offline is the same.'),
    P('split-phone-casting', 'casting · Stue', { page: 'albums', playing: 1, cast: 1 }, 'No visual difference.')]);
  row([D('split-desktop-wide-loading', 'desktop · loading', { page: 'albums', loading: 1 }), D('split-desktop-wide-capped', 'desktop · capped toast', { page: 'songs', big: 1, playing: 1, toast: 'capped' })]);
  st('5', 'Capped toast · Playing 1 000 of 3 214 songs');
  row([P('split-phone-capped-en', 'en', { page: 'songs', big: 1, playing: 1, toast: 'capped' }), P('split-phone-capped-da', 'da', { page: 'songs', big: 1, playing: 1, toast: 'capped', lang: 'da' }), P('split-phone-capped-fo', 'fo', { page: 'songs', big: 1, playing: 1, toast: 'capped', lang: 'fo' })]);
  st('6', 'Playing from · Your library · Indie Folk · Search "harbour"');
  row([P('split-phone-from-library', 'Your library', { page: 'np', npFrom: 'lib' }), P('split-phone-from-genre', 'Indie Folk', { page: 'np', npFrom: 'genre' }), P('split-phone-from-search', 'Search "harbour"', { page: 'np', npFrom: 'search', npSong: { t: 'Two Harbours', a: 'Harbour Lights', al: 'Salt on the Window' } }),
    P('split-phone-from-library-da', 'Dit bibliotek', { page: 'np', npFrom: 'lib', lang: 'da' }), P('split-phone-from-library-fo', 'Títt savn', { page: 'np', npFrom: 'lib', lang: 'fo' })]);
  st('7 · 8', 'Scrolled while playing, and landscape');
  row([P('split-phone-crowded-scrolled', '360 · playing · scrolled', { page: 'albums', playing: 1, scroll: 300 }, 'Scrolled, the Browse page reads exactly as today. On Listen and a search the split stays in the top bar.'),
    P('split-phone-crowded-listen-scrolled', '360 · Listen · scrolled', { page: 'listen', playing: 1, scroll: 300 }),
    P('split-phone-land-crowded', 'landscape · Albums · playing', { page: 'albums', playing: 1, land: 1 }), P('split-phone-land-listen', 'landscape · Listen', { page: 'listen', playing: 1, land: 1 })]);
  st('longest string', 'Danish and Faroese at 360 — the top bar is the tight place');
  row([P('split-phone-longest-da-listen', 'da · Listen · top bar', { page: 'listen', lang: 'da' }), P('split-phone-longest-fo-listen', 'fo · Listen · top bar', { page: 'listen', lang: 'fo' }),
    P('split-phone-longest-da-search', 'da · harbour · top bar', { page: 'search', lang: 'da' }), P('split-phone-longest-fo-search', 'fo · harbour · top bar', { page: 'search', lang: 'fo' })]);
  const tnote = put(X0, Y - 60, `<div class="note" style="width:1500px" id="mpa-measure-top"></div>`);
  Y = bottom() + 60;
  row([P('split-phone-longest-da', 'da · Albums', { page: 'albums', lang: 'da', playing: 1 }), P('split-phone-longest-fo', 'fo · Albums', { page: 'albums', lang: 'fo', playing: 1 }),
    P('split-phone-longest-da-genre', 'da · Indie Folk', { page: 'genre', lang: 'da' }), P('split-phone-longest-fo-genre', 'fo · Indie Folk · ▾ open', { page: 'genre', lang: 'fo', menu: 1 })]);
  st('10', 'Desktop keyboard focus · Tab reaches both halves, Enter starts or opens');
  row([D('split-desktop-wide-focus-play', 'focus on Play all', { page: 'albums', focus: 'play', tabs: 1 }, 'Tab order: sidebar → <b>Play all</b> ① → <b>▾</b> ② → grid. Enter or Space on ① plays. On ② it opens the menu, and ↓ / Enter picks <i>Shuffle all</i>. Esc closes it. The ring is 2 px <code>--ring</code> with a 2 px <code>--bg</code> gap.'),
    D('split-desktop-wide-focus-menu', '▾ opened from the keyboard · Noir', { page: 'albums', focus: 'menu', menu: 1, skin: 'noir', tabs: 1 })]);
  st('11', 'All three skins');
  row([P('split-phone-skin-aurora', 'Aurora', { page: 'albums', playing: 1 }), P('split-phone-skin-midnight', 'Midnight', { page: 'albums', playing: 1, skin: 'midnight' }), P('split-phone-skin-noir', 'Noir', { page: 'albums', playing: 1, skin: 'noir' }, 'No gradient anywhere, so Noir needs no swap.'),
    P('split-phone-skin-noir-listen', 'Noir · Listen · top bar', { page: 'listen', playing: 1, skin: 'noir' })]);
  row([D('split-desktop-wide-skin-midnight', 'desktop · Midnight', { page: 'albums', playing: 1, skin: 'midnight' }), D('split-desktop-wide-skin-noir', 'desktop · Noir', { page: 'albums', playing: 1, skin: 'noir' })]);
  lbl(X0, Y, 1400, `<span class="tag no">10-foot focus</span><h3>Not applicable</h3><small>No TV surface in this round.</small>`);
  Y = bottom() + 90;

  /* ===== 3 · dropped ===== */
  sec('3 · Dropped', 'What v3 replaces');
  put(X0, Y, `<div class="panel" style="width:1500px"><table class="tbl"><tr><th style="width:420px">Earlier option</th><th>v3</th></tr>
<tr><td>v2 · two pills, Play all and Shuffle all</td><td><b>Merged</b> into one split. Shuffle moves behind ▾.</td></tr>
<tr><td>v2 · a 28 px row of its own on Listen, a genre and a search</td><td><b>Gone.</b> Listen and a search use the top bar; a genre uses the ‹ Genres line.</td></tr>
<tr><td>The top bar on Artists, Albums and Songs too</td><td><b>Not done.</b> The sort pill already fills that slot, and the two together overflow 360 dp. The count line is free.</td></tr>
<tr><td>v1 · A, B, C, and the Listen and desktop alternatives</td><td>Closed in v2; still closed.</td></tr></table></div>`);
  Y = bottom() + 90;

  /* ===== 4 · strings ===== */
  sec('4 · Strings', 'What the drawing shows that is new');
  put(X0, Y, `<div class="panel" style="width:1500px"><table class="tbl"><tr><th>String</th><th>en</th><th>da</th><th>fo</th><th>Status</th></tr>
<tr><td><b>Play all</b> (the split)</td><td>Play all</td><td>Afspil alle</td><td>Spæl øll</td><td>already ships as <code>music.play_all</code></td></tr>
<tr><td><b>Shuffle all</b> (the ▾ menu)</td><td>Shuffle all</td><td>Bland alle</td><td>Blanda øll</td><td>settled</td></tr>
<tr><td><b>capped toast</b></td><td>Playing {shown} of {total} songs</td><td>Afspiller {shown} af {total} sange</td><td>Spælir {shown} av {total} sangum</td><td>settled</td></tr>
<tr><td><b>Playing from · value</b></td><td>Your library</td><td>Dit bibliotek</td><td>Títt savn</td><td>settled</td></tr>
<tr><td><b>Playing from · value</b></td><td>Search "{q}"</td><td>—</td><td>—</td><td>English only in the brief</td></tr></table>
<p style="margin-top:14px">Also shown, all existing and unchanged: chips, row headings, the sort pill, counts, <i>‹ Genres</i>, <i>Nothing matches “{q}”</i>, and <i>Couldn’t play this</i>. The ▾’s accessible name is not shown; see the finding above.</p></div>`);
  Y = bottom() + 120;

  document.body.style.width = (X0 * 2 + WIDTH) + 'px';
  document.body.style.height = Y + 'px';

  // measured, not typed
  (function measure() {
    const fr = id => C.querySelector(`[data-png="music-play-all-${id}"]`);
    const sc = f => f.getBoundingClientRect().height / f.offsetHeight;
    const list = id => { const f = fr(id); const g = f.querySelector('.mpa-g2'), mi = f.querySelector('.mpa-mini'); return Math.round((mi.getBoundingClientRect().top - g.getBoundingClientRect().top) / sc(f)); };
    const t = list('today-phone-crowded'), v2 = list('v2-phone-crowded'), v3 = list('split-phone-crowded');
    mnote.innerHTML = `<b>Measured on this canvas:</b> on Albums the list visible above the mini player is ${t} px today, ${v2} px with v2 (−${t - v2}), and <b>${v3} px with v3 (−${t - v3})</b>. On Listen and a search v3 costs 0 px, because it rides in the top bar.`;
    const edge = id => { const f = fr(id); const ib = [...f.querySelectorAll('.mpa-apb > .mpa-ib')].pop(); return Math.round((ib.getBoundingClientRect().right - f.getBoundingClientRect().left) / sc(f)); };
    const ids = ['split-phone-listen', 'split-phone-longest-da-listen', 'split-phone-longest-fo-listen', 'split-phone-longest-da-search', 'split-phone-longest-fo-search'];
    const e = ids.map(edge);
    tnote.innerHTML = `<b>Measured on this canvas, top bar at 360 dp</b> (its content must end by 342 px): cast ends at ${e[0]} px in English, ${e[1]} (da) and ${e[2]} (fo) on Listen, and ${e[3]} (da) and ${e[4]} (fo) on a search. ${e.every(v => v <= 342) ? '<b>All fit.</b>' : '<b>Over 342 means cast runs into the margin, a finding.</b>'}`;
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
