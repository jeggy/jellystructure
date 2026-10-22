/* music-play-all — the canvas: what is fixed → directions → comparison → every state (A + D1) → decided rows → strings.
   Frames come from music-play-all-kit.js (window.MPK). Each frame carries data-png = its PNG's file name (without .png).
   MPA.solo(id) shows one frame alone at 0,0 for the PNG export; MPA.unsolo() puts the canvas back. */
(function () {
  const K = window.MPK, C = document.getElementById('cv');
  const X0 = 80, GAP = 50, WIDTH = 3560;
  let Y = 80;
  function put(x, y, html) { const t = document.createElement('div'); t.innerHTML = html.trim(); const el = t.firstElementChild; el.style.left = x + 'px'; el.style.top = y + 'px'; C.appendChild(el); return el; }
  function bottom() { let b = 0; [...C.children].forEach(e => { b = Math.max(b, e.offsetTop + e.offsetHeight); }); return b; }
  function sec(n, title) { put(X0, Y, `<div class="rule" style="width:${WIDTH}px"></div>`); Y += 44; put(X0, Y, `<div class="sech"><div class="n">${n}</div><h2>${title}</h2></div>`); Y = bottom() + 34; }
  // one row of frames; start = x of the first frame (a label may sit left of it)
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
  const P = (id, cap, o, note) => ({ id, cap, note, w: o.land ? 780 : o.cmpw ? 400 : 360, html: K.phone(Object.assign({ id: 'music-play-all-' + id }, o)) });
  const D = (id, cap, o, note) => ({ id, cap, note, w: 1120, html: K.desk(Object.assign({ id: 'music-play-all-' + id }, o)) });
  const lbl = (x, y, w, html) => put(x, y, `<div class="lbl" style="width:${w}px">${html}</div>`);

  /* ================= 0 · title, what is fixed, found while drawing ================= */
  lbl(X0, Y, 1500, `<span class="tag new">Round · music-play-all · 2026-10-16 · no phase number (left blank on purpose)</span><h2>Play all &amp; Shuffle all on the music list pages</h2><small>The owner asked for both at once: <b>“a ‘play all’ and ‘shuffle all’ always available”</b> on Listen, a search, a genre and the browse lists — and <b>“I don’t want these two new buttons to clutter everything.”</b> This canvas places the pair on the five surfaces that get it (phone at <b>360 dp</b>, desktop wide and compact), in three directions, compares them on the crowded case, then carries the recommended one — <b>A</b> on the phone and compact desktop, <b>D1</b> on the wide desktop — through every state the brief names. Nothing else on these pages moves. Stand-ins are the brief’s: <b>Harbour Lights</b>, <b>Kite Weather</b> (2006), <b>Signal Found 20th Anniversary</b>, <b>Indie Folk</b>, the query <b>harbour</b>, a library of <b>385 songs</b>, and the capped case of <b>3 214</b>.</small>`);
  Y = bottom() + 40;
  put(X0, Y, `<div class="panel" style="width:1180px"><h2>What is fixed (not reopened here)</h2><ul>
<li><b>Five surfaces get the pair</b>: Listen (Recently added · Recently played · Artists · a row per genre — no Now-playing card) · Browse ▸ Artists / Albums / Songs with the sort pill · a genre (the Albums chip filtered, <i>‹ Genres</i>, the genre as heading) · a search (three groups) · the desktop, where a sidebar replaces the chip strip.</li>
<li><b>Nothing</b> on the Playlists chip, Audiobooks, Playing, Queue or Profile. The album, artist and playlist pages keep their own Play / Shuffle, including <i>Play album ▾</i>.</li>
<li>The bottom bar is on every music page; the mini player docks on it. <b>Neither moves.</b></li>
<li>The pair <b>replaces the queue</b>. A search plays its whole expanded result once (no pair per group). Play all follows the list on screen in the sort pill’s order. More than <b>1 000</b> songs are capped, and a toast says so.</li>
<li>Every count is a number the server sent; nothing is totalled on the device. Noir uses ink where the others use the gradient. Ravilo reads and reports playback only. No string names the media server.</li></ul>
<div class="good" style="margin-bottom:0">The pair needs <b>no count to draw</b> — its labels are <i>Play all</i> and <i>Shuffle all</i>, so it is on screen before the selection is resolved (state 2), and the only number it ever shows is the server’s, in the capped toast.</div></div>`);
  put(X0 + 1310, Y, `<div class="panel" style="width:1240px"><h2>Found while drawing — information, not edits</h2><p>None of these was changed. Each is how the mockup is built today, or a gap in the brief.</p><ul>
<li><b>The desktop album page’s buttons sit under the title and meta, on the left</b>, not to the right of the title (<code>desktop/ravilo-desktop.js</code> → <code>.alh .btns</code>). Decision 3’s lean is still drawn (D1), and so is the album page’s real geometry (D2).</li>
<li><b>The desktop has no sort pill</b> on Artists / Albums / Songs. The desktop frames are drawn without one, and none is added.</li>
<li><b>Desktop search groups are built Artists · Songs · Albums</b>; the requirement says Songs · Albums · Artists. Drawn in the requirement’s order.</li>
<li><b>The desktop Playing page has no <i>Playing from</i> line.</b> State 6 is drawn on the phone only.</li>
<li><b>Listen also carries <i>Continue listening</i></b> (a book in progress, R323) on phone and desktop. The brief’s row list leaves it out, so it is not drawn. If it is there, the pair sits above it, at the top of the page.</li>
<li><b>The genre view is built under the Genres chip</b> in the phone mockup (no sort pill). The requirement says the Albums chip filtered, so it is drawn that way: Albums lit and the sort pill showing.</li>
<li><b>At 360 dp the top row is already over-full today</b> (brand · sort pill · cast, inside 18 px margins, so it ends at 342 px). Measured on this canvas, the row ends at <b>351 px</b> in English (cast runs 9 px into the margin), exactly <b>342 px</b> in Danish, and <b>367 px</b> in Faroese, where cast is clipped by 7 px at the screen edge. This is before this round adds anything. Direction C adds a 38 px button to that row (397 / 413 px).</li>
<li><b>Faroese:</b> the settled capped toast says <i>sangum</i>, while the shipped set says <i>løg</i> for songs (<code>music.songs_n</code> = <i>{n} løg</i>). Reported only, and the toast is drawn as settled.</li>
<li><b><i>Search "{q}"</i> has no Danish or Faroese</b> in the brief, so it is drawn in English only.</li>
<li><i>Profile</i> (bottom bar) and <i>Clear</i> (search field) are hard-coded English in the phone markup, so they stay English in the da/fo frames.</li></ul></div>`);
  Y = bottom() + 90;

  /* ================= 1 · the directions ================= */
  sec('1 · Directions — the pair on a phone, compared on the crowded case (360 dp, mini player docked)', 'Three ways to place the pair');
  lbl(X0, Y, 400, `<span class="tag no">Reference · today</span><h3>Today, with nothing added</h3><small>Browse ▸ Albums at 360 dp, as built: the top row (brand · sort pill · cast), the search field, the chip strip, the count, the grid. The mini player is docked on the bottom bar. <b>524 px</b> lies between the top row and the mini player, and 387 px of it is list.</small>`);
  row([P('today-phone-albums', 'T1 · Albums · today', { dir: 'none', page: 'albums' }), P('today-phone-crowded', 'T2 · Albums · today · playing', { dir: 'none', page: 'albums', playing: 1 })], X0 + 450);

  lbl(X0, Y, 400, `<span class="tag pick">Direction A · recommended</span><h3>Direction A · The pair heads the list</h3><small>A compact pair, <b>▶ Play all · ⤮ Shuffle all</b>, sits directly above the first row of what it plays and <b>scrolls away with the list</b>: under the chip strip on the browse chips, under the heading on a genre, under the field on a search, and at the top of Listen. The buttons are the album page’s <code>.mu-dacts</code> pair (gradient + ghost, the same glyphs and weight) made <b>38 px tall</b>, which is the sort pill’s height, so they sit in the content rather than shouting from it.<br><br><b>Landscape:</b> the pair moves up onto the search field’s row (A5), because the field row has room there and the list under it has none.</small><span class="cost"><b>Cost:</b> 52 px at the top of each list, and none once the list is scrolled. While scrolled the pair is off screen until you scroll back to the top. The owner’s sentence lists <i>places</i> (“a search … the dashboard … a genre page”), and A is in every one of them.</span>`);
  row([P('a-phone-albums', 'A1 · Albums · at rest', { dir: 'A', page: 'albums' }), P('a-phone-crowded', 'A2 · crowded · 360 · playing', { dir: 'A', page: 'albums', playing: 1 }, '335 px of list shows above the mini player, against 387 px today.'),
    P('a-phone-crowded-scrolled', 'A3 · crowded · scrolled', { dir: 'A', page: 'albums', playing: 1, scroll: 300 }, 'Scrolled, the pair has gone with the list, so the page reads exactly as today.'), P('a-phone-listen', 'A4 · Listen', { dir: 'A', page: 'listen', playing: 1 })], X0 + 450);
  row([P('a-phone-land-crowded', 'A5 · landscape · crowded · the pair on the field row', { dir: 'A', page: 'albums', playing: 1, land: 1 }, 'The field and the pair share one row, so landscape spends no extra height on the pair.'), P('a-phone-land-crowded-scrolled', 'A6 · landscape · scrolled', { dir: 'A', page: 'albums', playing: 1, land: 1, scroll: 112 })], X0 + 450);

  lbl(X0, Y, 400, `<span class="tag">Direction B</span><h3>Direction B · The pair stays pinned</h3><small>The same pair sits under the search field, and <b>the field and the pair pin together</b> under the top row, so they never scroll away. On Listen, which has no field, the pair pins under the top row by itself. The chip strip then scrolls as usual.</small><span class="cost"><b>Cost:</b> 114 px pinned on every browse page, <b>including while the viewer is reading the list and not using either</b>, which is the clutter the owner named. Scrolled and crowded at 360, 410 px of list is left. In landscape only 74 px is left (B4). And the field, which scrolls away today, would stop doing so.</span>`);
  row([P('b-phone-albums', 'B1 · Albums · at rest', { dir: 'B', page: 'albums' }), P('b-phone-crowded-scrolled', 'B2 · crowded · scrolled', { dir: 'B', page: 'albums', playing: 1, scroll: 300 }, 'The field and the pair stay on screen while the list scrolls under them.'),
    P('b-phone-listen', 'B3 · Listen · scrolled', { dir: 'B', page: 'listen', playing: 1, scroll: 260 }), P('b-phone-land-crowded-scrolled', 'B4 · landscape · scrolled', { dir: 'B', page: 'albums', playing: 1, land: 1, scroll: 50 }, 'Pinned at 62 px, 74 px of list is left between the pair and the mini player.')], X0 + 450);

  lbl(X0, Y, 400, `<span class="tag">Direction C</span><h3>Direction C · One shuffle button in the top row</h3><small>A single <b>⤮</b> button sits to the right of the sort pill in the top row, and the top row is sticky, so it is <b>always on screen and costs no height</b>. Pressing and holding it, or opening its menu, offers <i>Shuffle all</i> and <i>Play all</i> (C3). On Listen and on a search there is no sort pill, so the ⤮ sits there alone.</small><span class="cost"><b>Cost:</b> <i>Play all</i> is hidden behind a long press. The button is icon-only, so it is not a sibling of the album page’s labelled pair. The top-row slot has a rule today, <i>filled only with the page’s own filter</i> (FR-R267-5c), and C breaks it. And at 360 dp the row overflows: the row runs 37 px past the screen edge in English, and 53 px past it in Faroese (C4), so cast is pushed out of the frame.</span>`);
  row([P('c-phone-albums', 'C1 · Albums · at rest', { dir: 'C', page: 'albums' }), P('c-phone-crowded-scrolled', 'C2 · crowded · scrolled', { dir: 'C', page: 'albums', playing: 1, scroll: 300 }),
    P('c-phone-menu', 'C3 · long-press ⤮ · the menu', { dir: 'C', page: 'albums', menu: 1 }), P('c-phone-longest-fo', 'C4 · Faroese · 360 · the top row', { dir: 'C', page: 'albums', lang: 'fo' }, 'Brand, <i>Nýliga lagt afturat ▾</i>, ⤮ and cast need 395 px and the row has 324. Today’s row already clips cast here, and C pushes it out entirely.')], X0 + 450);

  lbl(X0, Y, 400, `<span class="tag pick">Decision 2 · lean kept</span><h3>Decision 2 · Where the pair sits on Listen</h3><small><b>At the top, above <i>Recently added</i></b> (D2·a), which is the lean, and the recommendation.<br><br>The alternative, drawn in D2·b, puts it lower, between <i>Recently played</i> and <i>Artists</i>. There it competes with <i>Recently played</i>’s <i>See all</i> and its five song rows, which already play the Recently played context, so a pair right under them reads as “play these five”. It is also a screen and a half down, so it is not “always there”.</small>`);
  row([P('a-phone-listen-top', 'D2·a · Listen · top (recommended)', { dir: 'A', page: 'listen' }), P('a-phone-listen-lower', 'D2·b · Listen · lower (not recommended)', { dir: 'A', page: 'listen', listenAlt: 1, scroll: 330 }, 'Scrolled to where the pair sits. It looks like part of Recently played.')], X0 + 450);

  lbl(X0, Y, 400, `<span class="tag pick">Decision 3 · lean kept</span><h3>Decision 3 · Where the pair sits on the desktop</h3><small><b>D1, to the right of the title in the page head</b>, as the lean says. The buttons are the album page’s own <code>.bt.pri</code> / <code>.bt</code> pair at 38 px, with nothing new. The grid does not move down, and the empty right half of the head row is where the eye goes for page actions.<br><br><b>D2</b> puts them under the title and count, which is where the album page actually puts its buttons (see “Found while drawing”). It is just as much a sibling, but it costs 56 px on every list. The sidebar does not change the answer, because it replaces only the chip strip and the head row is untouched.</small>`);
  row([D('d1-desktop-wide-albums', 'D3·a · D1 · right of the title (recommended)', { dir: 'D1', page: 'albums', playing: 1 }), D('d2-desktop-wide-albums', 'D3·b · D2 · under the title', { dir: 'D2', page: 'albums', playing: 1 })], X0 + 450);

  /* ================= 2 · comparison ================= */
  sec('2 · How they compare', 'A, B and C on the case that decides it');
  put(X0, Y, `<div class="panel" style="width:2000px"><table class="tbl"><tr><th style="width:430px">Question</th><th>A · heads the list</th><th>B · pinned</th><th>C · one ⤮ in the top row</th></tr>
<tr><td><b>On all five surfaces</b></td><td class="y">yes</td><td class="y">yes</td><td class="m">yes, but alone in the slot on Listen and on a search</td></tr>
<tr><td><b>On screen while the list is scrolled</b></td><td class="n">no, it is at the top of the list</td><td class="y">yes</td><td class="y">yes (the top row is sticky)</td></tr>
<tr><td><b>Height it takes, phone portrait</b></td><td>52 px, scrolls away</td><td class="n">114 px, never scrolls (field + pair)</td><td class="y">0</td></tr>
<tr><td><b>Crowded · 360 · px of list visible above the mini player</b> (today: 387 at rest, 524 scrolled)</td><td>335 at rest · <b>524</b> scrolled</td><td class="n">335 at rest · 410 scrolled</td><td class="y">387 at rest · 524 scrolled</td></tr>
<tr><td><b>Landscape · crowded · px of list visible</b> (136 px between the top row and the mini player; today: 0 at rest, because the field and the chips fill it)</td><td>0 at rest, as today · <b>136</b> scrolled (the pair rides on the field row)</td><td class="n">0 at rest · 74 scrolled</td><td>0 at rest · 136 scrolled</td></tr>
<tr><td><b>Play all is one tap</b></td><td class="y">yes</td><td class="y">yes</td><td class="n">no, it needs a long press or the menu</td></tr>
<tr><td><b>Sibling of the album page’s Play / Shuffle</b></td><td class="y">yes: the same fills, glyphs and weight, at 38 px</td><td class="y">yes, the same as A</td><td class="n">no, a bare icon is a third style</td></tr>
<tr><td><b>Danish and Faroese at 360</b></td><td class="y">fits: en 228 px, <i>Afspil alle · Bland alle</i> 247 px, <i>Spæl øll · Blanda øll</i> 236 px, of 324</td><td class="y">fits, as A</td><td class="n">the top row overflows and cast is clipped (C4)</td></tr>
<tr><td><b>Keeps today’s rules</b></td><td class="y">yes</td><td class="m">the field stops scrolling away</td><td class="n">the slot stops being “the page’s own filter only”</td></tr>
<tr><td><b>Against our precedent</b> (a series’ Shuffle became a <i>small pill past a threshold</i>, not a primary button)</td><td>small and in the content, as the precedent was, but with no threshold, because this pair has to be <i>always</i> there</td><td>always there, but too loud for the precedent</td><td>the most like the precedent in size, but it hides Play all</td></tr></table>
<div class="good" style="margin-bottom:0"><b>Recommendation: A</b>, with the landscape rule (the pair on the field row), and <b>D1</b> on the wide desktop. In the crowded case A costs nothing once the list is scrolled, and it keeps both verbs one labelled tap away at the top of every list. B pays for “never scrolls away” with 114 px on every page. C is the cheapest in height, but it hides one of the two verbs and breaks the top row at 360 dp.</div></div>`);
  Y = bottom() + 90;

  /* ================= 3 · every state, recommended direction ================= */
  sec('3 · Every state — Direction A on the phone and compact desktop, D1 on the wide desktop', 'The recommended direction, carried through');
  const st = (n, t) => { lbl(X0, Y, 3400, `<span class="tag new">State ${n}</span><h3>${t}</h3>`); Y = bottom() + 22; };
  st('1', 'Each surface with the pair in place · phone 360');
  row([P('a-phone-listen-s1', 'S1·1 · Listen', { dir: 'A', page: 'listen' }), P('a-phone-artists', 'S1·2 · Browse ▸ Artists', { dir: 'A', page: 'artists' }), P('a-phone-albums-s1', 'S1·3 · Browse ▸ Albums', { dir: 'A', page: 'albums' }), P('a-phone-songs', 'S1·4 · Browse ▸ Songs', { dir: 'A', page: 'songs' }),
    P('a-phone-genre', 'S1·5 · a genre · Indie Folk', { dir: 'A', page: 'genre' }, 'The Albums chip, filtered. The pair sits under the heading that names what it plays.'), P('a-phone-search', 'S1·6 · a search · harbour', { dir: 'A', page: 'search' }, 'Once, above the three groups. It plays every song, album and artist the search returned, expanded.')]);
  st('1 · 9', 'Each surface · desktop wide (D1)');
  row([D('d1-desktop-wide-listen', 'S1·7 · Listen', { page: 'listen' }), D('d1-desktop-wide-artists', 'S1·8 · Artists', { page: 'artists' }), D('d1-desktop-wide-albums-s1', 'S1·9 · Albums', { page: 'albums' })]);
  row([D('d1-desktop-wide-songs', 'S1·10 · Songs', { page: 'songs', playing: 1 }), D('d1-desktop-wide-genre', 'S1·11 · Indie Folk', { page: 'genre' }), D('d1-desktop-wide-search', 'S1·12 · harbour', { page: 'search' }, 'On the search page the field is the head, so the pair sits to its right.')]);
  st('9', 'Each surface · desktop compact (under 600 — the phone layout, so Direction A)');
  row(['listen', 'artists', 'albums', 'songs', 'genre', 'search'].map((p, i) => P('a-desktop-compact-' + p, 'S9·' + (i + 1) + ' · compact · ' + p, { dir: 'A', page: p, cmpw: 1 })));
  st('2 · 3 · 4 · error · casting', 'Loading, an empty selection, one song, a failed request, casting');
  row([P('a-phone-loading', 'S2 · loading · Songs', { dir: 'A', page: 'songs', loading: 1 }, 'The pair is already there and the count is not. A tap now starts as soon as the server resolves the selection.'),
    P('a-phone-empty-search', 'S3·a · empty search · harbor', { dir: 'A', page: 'search-empty' }, '<b>No pair.</b> The search’s own <i>Nothing matches</i> line is all there is.'),
    P('a-phone-empty-genre', 'S3·b · a genre with no songs', { dir: 'A', page: 'genre-empty' }, '<b>No pair</b>, and no greyed one. (The stand-in name is reused. There is no empty genre in the corpus.)'),
    P('a-phone-one-song', 'S4 · one song · kvøldsól', { dir: 'A', page: 'search-one' }, '<b>Both stay.</b> Shuffle of one plays that song, and the pair keeps its shape whatever the count, so nothing on the device has to count.'),
    P('a-phone-error', 'Error · the request fails', { dir: 'A', page: 'albums' , toast: 'error' }, 'A toast, and the buttons do not change. <b>Offline is drawn the same:</b> on this surface a lost connection and a failed request both mean “nothing started”.'),
    P('a-phone-casting', 'S12 · casting · Stue', { dir: 'A', page: 'albums', playing: 1, cast: 1 }, '<b>No visual difference.</b> The pair starts playback on Stue, and the mini player already says where.')]);
  D2s();
  function D2s() { row([D('d1-desktop-wide-loading', 'S2 · desktop · loading', { page: 'albums', loading: 1 }), D('d1-desktop-wide-capped', 'S5 · desktop · capped toast', { page: 'songs', big: 1, playing: 1, toast: 'capped' })]); }
  st('5', 'The capped toast · Playing 1 000 of 3 214 songs, in three languages');
  row([P('a-phone-capped-en', 'S5·en', { dir: 'A', page: 'songs', big: 1, playing: 1, toast: 'capped' }), P('a-phone-capped-da', 'S5·da', { dir: 'A', page: 'songs', big: 1, playing: 1, toast: 'capped', lang: 'da' }), P('a-phone-capped-fo', 'S5·fo', { dir: 'A', page: 'songs', big: 1, playing: 1, toast: 'capped', lang: 'fo' }, 'The toast sits above the mini player, which appears the moment playback starts. The thousands are grouped with a no-break space.')]);
  st('6', 'The Playing from line · Your library · Indie Folk · Search "harbour"');
  row([P('a-phone-from-library', 'S6·1 · Your library', { dir: 'A', page: 'np', npFrom: 'lib' }), P('a-phone-from-genre', 'S6·2 · Indie Folk', { dir: 'A', page: 'np', npFrom: 'genre' }), P('a-phone-from-search', 'S6·3 · Search "harbour"', { dir: 'A', page: 'np', npFrom: 'search', npSong: { t: 'Two Harbours', a: 'Harbour Lights', al: 'Salt on the Window' } }),
    P('a-phone-from-library-da', 'S6·1 · da', { dir: 'A', page: 'np', npFrom: 'lib', lang: 'da' }), P('a-phone-from-library-fo', 'S6·1 · fo', { dir: 'A', page: 'np', npFrom: 'lib', lang: 'fo' }, 'Only the value is new. The carrier, <i>Spælir úr {x}</i>, ships already. <i>Search "{q}"</i> has no da/fo in the brief, so it is not drawn in them.')]);
  st('7 · 8', 'The crowded case at 360, and landscape');
  row([P('a-phone-crowded-s7', 'S7·1 · 360 · playing · at rest', { dir: 'A', page: 'albums', playing: 1 }), P('a-phone-crowded-search', 'S7·2 · 360 · playing · search', { dir: 'A', page: 'search', playing: 1 }), P('a-phone-crowded-listen', 'S7·3 · 360 · playing · Listen', { dir: 'A', page: 'listen', playing: 1 }),
    P('a-phone-land-listen', 'S8 · landscape · Listen', { dir: 'A', page: 'listen', playing: 1, land: 1 }), P('a-phone-land-search', 'S8 · landscape · search', { dir: 'A', page: 'search', playing: 1, land: 1 })]);
  st('longest string', 'Danish and Faroese at 360, with the sort pill in its row');
  row([P('a-phone-longest-da', 'da · Albums · playing', { dir: 'A', page: 'albums', lang: 'da', playing: 1 }), P('a-phone-longest-fo', 'fo · Albums · playing', { dir: 'A', page: 'albums', lang: 'fo', playing: 1 }, 'The pair fits: 247 px (da) and 236 px (fo) of 324. The top row’s overflow in Faroese is today’s, and was not touched (see “Found while drawing”).'),
    P('a-phone-longest-da-genre', 'da · Indie Folk', { dir: 'A', page: 'genre', lang: 'da' }), P('a-phone-longest-fo-search', 'fo · harbour', { dir: 'A', page: 'search', lang: 'fo' })]);
  st('10', 'Desktop keyboard focus · Tab reaches both, Enter starts');
  row([D('d1-desktop-wide-focus-play', 'S10·1 · focus on Play all', { page: 'albums', focus: 'play', tabs: 1 }, 'Tab order: the sidebar, then <b>Play all</b> ①, then <b>Shuffle all</b> ②, then the grid. Enter or Space starts. The ring is the skin’s <code>--ring</code>, 2 px, offset by a 2 px <code>--bg</code> gap.'),
    D('d1-desktop-wide-focus-shuffle', 'S10·2 · focus on Shuffle all · Noir', { page: 'albums', focus: 'shuf', skin: 'noir', tabs: 1 })]);
  st('11', 'All three skins');
  row([P('a-phone-skin-aurora', 'S11 · Aurora', { dir: 'A', page: 'albums', playing: 1 }), P('a-phone-skin-midnight', 'S11 · Midnight', { dir: 'A', page: 'albums', playing: 1, skin: 'midnight' }), P('a-phone-skin-noir', 'S11 · Noir', { dir: 'A', page: 'albums', playing: 1, skin: 'noir' }, 'Noir: <i>Play all</i> is ink on black, never a gradient, the same swap the album page’s <i>Play</i> makes.')]);
  row([D('d1-desktop-wide-skin-midnight', 'S11 · desktop · Midnight', { page: 'albums', playing: 1, skin: 'midnight' }), D('d1-desktop-wide-skin-noir', 'S11 · desktop · Noir', { page: 'albums', playing: 1, skin: 'noir' })]);
  lbl(X0, Y, 1400, `<span class="tag no">10-foot focus</span><h3>Not applicable</h3><small>There is no TV surface in this round. The TV has no music browsing yet, so no 10-foot frame is drawn.</small>`);
  Y = bottom() + 90;

  /* ================= 4 · decided rows ================= */
  sec('4 · Decided rows — proposed, for the owner to pick', 'Leans and alternatives');
  put(X0, Y, `<div class="panel" style="width:1500px"><div class="opt">
<div><h5>Q1 · Shape and place (phone)</h5><p>A: the pair heads each list and scrolls with it. In landscape it rides on the search field’s row. B: the field and the pair pin together. C: one ⤮ in the top row, with Play all in its long-press menu.</p><div class="ch"><span class="lean">A (recommended)</span><span>B</span><span>C</span></div></div>
<div><h5>Q2 · On Listen</h5><p>At the top, above Recently added, or lower, between Recently played and Artists.</p><div class="ch"><span class="lean">top (lean, recommended)</span><span>lower</span></div></div>
<div><h5>Q3 · On the desktop</h5><p>D1: to the right of the title in the page head. D2: under the title and count, where the album page puts its own buttons.</p><div class="ch"><span class="lean">D1 (lean, recommended)</span><span>D2</span></div></div>
<div><h5>Q4 · Play all’s fill on the phone</h5><p>Drawn as the album page’s pair (Play all on the gradient, Shuffle all ghost). The quieter option is both ghost, which costs the sibling reading.</p><div class="ch"><span class="lean">gradient + ghost (drawn)</span><span>both ghost</span></div></div>
<div><h5>Q5 · The error toast</h5><p>Drawn with the shipped <i>Couldn’t play this</i> (<code>music.fail_t</code>), so there is no new string. A specific line would be a sixth string, and that is not proposed.</p><div class="ch"><span class="lean">reuse music.fail_t (drawn)</span><span>a new line</span></div></div>
</div></div>`);
  put(X0 + 1630, Y, `<div class="panel" style="width:1100px"><h2>States the drawing settles</h2><ul>
<li><b>One song:</b> both buttons stay. The pair’s shape never depends on a count.</li>
<li><b>Empty:</b> the pair is absent and the page’s own empty line stays.</li>
<li><b>Loading:</b> the pair is drawn before the count arrives, and the count line holds a placeholder.</li>
<li><b>Error and offline:</b> one toast, and the buttons do not change.</li>
<li><b>Casting:</b> no visual difference.</li>
<li><b>Capped:</b> the toast sits above the mini player, with thousands grouped by a no-break space.</li></ul>
<h4>The one new token</h4><p>The phone pair is 38 px tall (the sort pill’s existing height) with a 12 px radius (the album pair’s 14 px scaled to 38 px). Everything else is reused: the fills, the border, weight 600, the glyphs, Noir’s ink-for-gradient swap, and on the desktop the <code>.bt</code> pair unchanged.</p></div>`);
  Y = bottom() + 90;

  /* ================= 5 · strings ================= */
  sec('5 · New strings', 'What the drawing shows that is new');
  put(X0, Y, `<div class="panel" style="width:1700px"><table class="tbl"><tr><th>String</th><th>en</th><th>da</th><th>fo</th><th>Status</th></tr>
<tr><td><b>Play all</b></td><td>Play all</td><td>Afspil alle</td><td>Spæl øll</td><td>already ships as <code>music.play_all</code> (the artist page) — reused</td></tr>
<tr><td><b>Shuffle all</b></td><td>Shuffle all</td><td>Bland alle</td><td>Blanda øll</td><td>settled, new</td></tr>
<tr><td><b>capped toast</b></td><td>Playing {shown} of {total} songs</td><td>Afspiller {shown} af {total} sange</td><td>Spælir {shown} av {total} sangum</td><td>settled, new</td></tr>
<tr><td><b>Playing from · value</b></td><td>Your library</td><td>Dit bibliotek</td><td>Títt savn</td><td>settled, new value for <code>music.from</code></td></tr>
<tr><td><b>Playing from · value</b></td><td>Search "{q}"</td><td>—</td><td>—</td><td>settled in English; <b>no da / fo in the brief</b></td></tr></table>
<p style="margin-top:14px">The drawing also shows <b>existing</b> strings, unchanged: the chips, row headings, sort pill, counts, <i>Nothing matches “{q}”</i> (the empty search), <i>{n} albums</i> (the empty genre) and <i>Couldn’t play this</i> (the error toast, see Q5). A genre’s <i>Playing from</i> value is the genre’s own name.</p></div>`);
  Y = bottom() + 120;

  document.body.style.width = (X0 * 2 + WIDTH) + 'px';
  document.body.style.height = Y + 'px';

  /* ---- PNG export helpers ---- */
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
