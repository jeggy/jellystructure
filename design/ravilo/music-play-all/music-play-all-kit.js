/* music-play-all — frame builders (window.MPK). Stand-ins are the brief's and the music mockup's corpus (app/music-data.js);
   no title, artist or genre is invented here. Strings: en/da/fo from ravilo/ravilo-i18n.js where the key exists; the five
   settled values for this round are marked NEW. Numbers are what the server would send — nothing is totalled here. */
(function () {
  const IC = {
    play: '<path d="M8 5l11 7-11 7z"/>',
    shuffle: '<path d="M3 7h3.5c5 0 6 10 11 10H21"/><path d="M3 17h3.5c2 0 3.2-1.6 4.2-3.5M14 8.5c1-1 2-1.5 3.5-1.5H21"/><path d="M18.5 4.5 21 7l-2.5 2.5M18.5 14.5 21 17l-2.5 2.5"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20.5 20.5l-4.2-4.2"/>',
    chev: '<path d="M6 9l6 6 6-6"/>', down: '<path d="M6 9l6 6 6-6"/>', back: '<path d="M15 5l-7 7 7 7"/>', fwd: '<path d="M9 5l7 7-7 7"/>',
    more: '<circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/>',
    cast: '<path d="M3 17a4 4 0 0 1 4 4M3 13a8 8 0 0 1 8 8M3 9V7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-6"/>',
    speaker: '<rect x="6" y="2.5" width="12" height="19" rx="3"/><circle cx="12" cy="14.5" r="3.2"/><circle cx="12" cy="7" r="1"/>',
    listen: '<circle cx="7.5" cy="17.5" r="2.8"/><path d="M10.3 17.5V5.5l9.2-2v12"/><circle cx="16.7" cy="15.5" r="2.8"/>',
    browse: '<rect x="4" y="4" width="7" height="7" rx="1.5"/><rect x="13" y="4" width="7" height="7" rx="1.5"/><rect x="4" y="13" width="7" height="7" rx="1.5"/><rect x="13" y="13" width="7" height="7" rx="1.5"/>',
    nowp: '<circle cx="12" cy="12" r="9"/><path d="M8.5 15.5v-3M11 15.5V9M13.5 15.5v-5M16 15.5v-2"/>',
    queue: '<path d="M4 6h12M4 11h12M4 16h7"/><path d="M16 14l5 3.2-5 3.2z"/>',
    pause: '<path d="M7 5h4v14H7zM13 5h4v14h-4z"/>', next: '<path d="M6 5l9 7-9 7zM16 5h2.5v14H16z"/>', prev: '<path d="M18 5l-9 7 9 7zM5.5 5H8v14H5.5z"/>',
    repeat: '<path d="M17 2l4 4-4 4"/><path d="M3 11V9a3 3 0 0 1 3-3h15"/><path d="M7 22l-4-4 4-4"/><path d="M21 13v2a3 3 0 0 1-3 3H3"/>',
    phones: '<path d="M4 15v-3a8 8 0 0 1 16 0v3"/><rect x="3" y="14" width="4" height="6" rx="1"/><rect x="17" y="14" width="4" height="6" rx="1"/>',
    wave: '<path d="M5 10v4M9 6v12M13 9v6M17 4v16M21 10v4"/>', lq: '<path d="M4 6h16M4 12h16M4 18h10"/>',
    person: '<circle cx="12" cy="8" r="4"/><path d="M4 21c1-4 4-6 8-6s7 2 8 6"/>', disc: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="2.5"/>',
    note: '<path d="M9 18V5l11-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/>', tag: '<path d="M3 12V4h8l10 10-8 8z"/><circle cx="7.5" cy="8.5" r="1.5"/>',
    plist: '<path d="M4 6h11M4 11h11M4 16h6"/><circle cx="16.5" cy="17.5" r="2.5"/><path d="M19 17.5V9l2-1"/>', book: '<path d="M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2z"/><path d="M19 19v2H6"/>',
    lyr: '<path d="M4 5h16v11H9l-5 4z"/><path d="M8 9h8M8 12h5"/>', vol: '<path d="M4 9v6h4l5 4V5L8 9z"/><path d="M16 9a4 4 0 0 1 0 6"/>',
  };
  const FILL = { play: 1, pause: 1, next: 1, prev: 1, more: 1 };
  const ic = (n, c) => `<svg class="ic${FILL[n] ? ' f' : ''}${c ? ' ' + c : ''}" viewBox="0 0 24 24">${IC[n]}</svg>`;
  let MARKN = 0;
  const SKC = { aurora: ['#7b6ef0', '#3fb6f5'], midnight: ['#19d6c6', '#2a8cf0'], noir: ['#f5b542', '#e0792f'] };
  const MARK = sk => { const [a, b] = SKC[sk] || SKC.aurora, id = 'mpaJ' + (++MARKN); return `<svg class="mark" viewBox="12 20 76 76" aria-hidden="true"><defs><linearGradient id="${id}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${a}"/><stop offset="1" stop-color="${b}"/></linearGradient></defs><path d="M22 52 C22 24 78 24 78 52 C66 45 59 45 50 49 C41 45 34 45 22 52 Z" fill="url(#${id})"/><g stroke="url(#${id})" stroke-width="4.5" stroke-linecap="round" fill="none"><path d="M33 51 q-5 12 1 20 q5 8 0 14" opacity=".9"/><path d="M44 52 q-4 13 1 21 q4 9 0 13" opacity=".72"/><path d="M56 52 q4 13 -1 21 q-4 9 0 13" opacity=".72"/><path d="M67 51 q5 12 -1 20 q-5 8 0 14" opacity=".9"/></g></svg>`; };

  /* ---- strings ---- */
  const L = {
    en: { listen: 'Listen', browse: 'Browse', playing: 'Playing', queue: 'Queue', profile: 'Profile', artists: 'Artists', albums: 'Albums', songs: 'Songs', genres: 'Genres', playlists: 'Playlists', books: 'Audiobooks',
      search_ph: 'Search music', clear: 'Clear', r_added: 'Recently added', r_played: 'Recently played', h_artists: 'Artists', see_all: 'See all', sort: 'Recently added',
      songs_n: '{n} songs', albums_n: '{n} albums', artists_n: '{n} artists', no_results: 'Nothing matches “{q}”', fail: 'Couldn’t play this', from: 'Playing from',
      play_all: 'Play all', shuffle_all: 'Shuffle all', capped: 'Playing {shown} of {total} songs', lib: 'Your library', srch: 'Search "{q}"' },
    da: { listen: 'Lyt', browse: 'Gennemse', playing: 'Afspiller', queue: 'Kø', profile: 'Profile', artists: 'Kunstnere', albums: 'Album', songs: 'Sange', genres: 'Genrer', playlists: 'Playlister', books: 'Lydbøger',
      search_ph: 'Søg i musik', clear: 'Clear', r_added: 'Senest tilføjet', r_played: 'Senest afspillet', h_artists: 'Kunstnere', see_all: 'Se alle', sort: 'Senest tilføjet',
      songs_n: '{n} sange', albums_n: '{n} album', artists_n: '{n} kunstnere', no_results: 'Intet matcher “{q}”', fail: 'Kunne ikke afspille', from: 'Afspiller fra',
      play_all: 'Afspil alle', shuffle_all: 'Bland alle', capped: 'Afspiller {shown} af {total} sange', lib: 'Dit bibliotek', srch: null },
    fo: { listen: 'Lurta', browse: 'Kaga', playing: 'Spælir', queue: 'Bíðirøð', profile: 'Profile', artists: 'Tónleikarar', albums: 'Fløgur', songs: 'Løg', genres: 'Sjangrur', playlists: 'Spælilistar', books: 'Ljóðbøkur',
      search_ph: 'Leita í tónleiki', clear: 'Clear', r_added: 'Nýliga lagt afturat', r_played: 'Nýliga spælt', h_artists: 'Tónleikarar', see_all: 'Sí øll', sort: 'Nýliga lagt afturat',
      songs_n: '{n} løg', albums_n: '{n} fløgur', artists_n: '{n} tónleikarar', no_results: 'Einki samsvarar “{q}”', fail: 'Kundi ikki spæla hetta', from: 'Spælir úr',
      play_all: 'Spæl øll', shuffle_all: 'Blanda øll', capped: 'Spælir {shown} av {total} sangum', lib: 'Títt savn', srch: null },
  };
  const NB = '\u00a0';
  const fmtN = n => String(n).replace(/\B(?=(\d{3})+(?!\d))/g, NB);
  const T = (lang, k, v) => { let s = L[lang][k]; if (s == null) s = L.en[k]; return String(s).replace(/\{(\w+)\}/g, (m, x) => v && v[x] != null ? v[x] : m); };

  /* ---- stand-in corpus (music mockup) ---- */
  const H = s => { let h = 0; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0; return h % 360; };
  const cov = t => { const h = H(t); return `background:radial-gradient(circle at 72% 28%,hsla(${(h + 50) % 360},70%,72%,.55),transparent 46%),linear-gradient(135deg,hsl(${h} 42% 24%),hsl(${(h + 34) % 360} 52% 52%))`; };
  const AL = [['Kite Weather', 'Harbour Lights', 2006], ['Signal Found 20th Anniversary', 'Harbour Lights', 2023], ['Salt on the Window', 'Harbour Lights', 2003], ['Glass Birds', 'Marta Vang', 2004],
    ['Signal Lost', 'Velvet Static', 2005], ['Brim', 'Aldan', 2007], ['Stormur', 'Bylgja', 2005], ['Undertow', 'Sunniva & the Tides', 2004], ['North Atlantic Songbook', 'The Ferrymen', 2002], ['Last Stop, Everybody', 'Nightbus Orchestra', 2000], ['Low Tide Radio', 'Harbour Lights', 2005], ['Myrkrið og ljósið', 'Kvøld', 2006]];
  const FOLK = [AL[0], AL[1], AL[2], ['Low Tide Radio', 'Harbour Lights', 2005], ['Early Recordings', 'Harbour Lights', 2008], AL[3], AL[7], ['Signal Found', 'Harbour Lights', 2003], ['Foghorn Lullabies', 'The Lighthouse Keepers', 2004]];
  const AR = [['Aldan', 1], ['Bylgja', 1], ['Copperline', 1], ['Emil Rask', 1], ['Frost & Fjord', 1], ['Hanna Dal', 1], ['Harbour Lights', 5], ['Jens Holm', 1], ['Kvøld', 2, 1], ['Marta Vang', 3], ['Moss Garden', 2], ['Nightbus Orchestra', 2], ['Ola Brink', 1], ['Skerry', 1, 1], ['Sunniva & the Tides', 1]];
  const SONGS = [['Kite Weather', 'Harbour Lights', 'Kite Weather', '3:36'], ['Northern Line', 'Harbour Lights', 'Kite Weather', '3:58'], ['String and Paper', 'Harbour Lights', 'Kite Weather', '4:40'],
    ['Signal Found', 'Harbour Lights', 'Signal Found', '3:48'], ['Lighthouse Code', 'Harbour Lights', 'Signal Found', '4:10'], ['Salt on the Window', 'Harbour Lights', 'Salt on the Window', '3:52'],
    ['Paper Lanterns', 'Harbour Lights', 'Salt on the Window', '4:10'], ['The Long Way Round', 'Harbour Lights', 'Salt on the Window', '3:31'], ['Weathervane', 'Harbour Lights', 'Salt on the Window', '4:44'], ['Glass Birds', 'Marta Vang', 'Glass Birds', '3:48']];
  const RECENT = [['Salt on the Window', 'Harbour Lights', 'Salt on the Window', '3:52'], ['Glass Birds', 'Marta Vang', 'Glass Birds', '3:48'], ['Signal Lost', 'Velvet Static', 'Signal Lost', '3:41'], ['Night Tram', 'Nightbus Orchestra', 'Last Stop, Everybody', '3:47'], ['Weathervane', 'Harbour Lights', 'Salt on the Window', '4:44']];
  const S_HARB = [['Two Harbours', 'Harbour Lights', 'Salt on the Window', '4:21'], ['Harbour Wall', 'Sunniva & the Tides', 'Undertow', '4:18'], ['Kite Weather', 'Harbour Lights', 'Kite Weather', '3:36']];
  const S_ONE = [['Kvøldsól', 'Kvøld', 'Kvøld', '3:33']];
  const ini = n => n.replace(/^The /, '').split(/\s+/).map(w => w[0]).join('').slice(0, 2).toUpperCase();
  const arStyle = (n, noimg) => noimg ? `background:linear-gradient(135deg,hsl(${H(n)} 18% 22%),hsl(${H(n)} 22% 34%))` : cov(n + ' portrait');

  /* ============ PHONE ============ */
  const pc = (t, cls) => `<div class="mpa-cv${cls ? ' ' + cls : ''}" style="${cov(t)}"></div>`;
  const acard = ([t, a, y], yr) => `<div class="mpa-ac">${pc(t)}<div class="t">${t}</div><div class="s">${a}${yr ? ' · ' + y : ''}</div></div>`;
  const arc = ([n, k, noimg], lang, sub) => `<div class="mpa-arc"><div class="c" style="${arStyle(n, noimg)}">${noimg ? ini(n) : ''}</div><div class="t">${n}</div>${sub ? `<div class="s">${T(lang, 'albums_n', { n: k }).replace(/^1 albums$/, '1 album')}</div>` : ''}</div>`;
  const song = ([t, a, al, d], on) => `<div class="mpa-song${on ? ' on' : ''}">${pc(al, 'sm')}<div class="b"><div class="t">${t}</div><div class="s">${a} — ${al}</div></div><span class="ln">${d}</span><span class="mo">${ic('more')}</span></div>`;
  const rowh = (h, more) => `<h3>${h}${more ? `<span class="more">${more} ›</span>` : ''}</h3>`;
  const sk = (w, h, m) => `<span class="mpa-skl" style="width:${w};height:${h}px;${m || ''}"></span>`;
  function pair(lang, o) {
    o = o || {};
    return `<div class="mpa-pair${o.pin ? ' pin' : ''}" data-pair="1"><span class="mpa-pb pri${o.hit === 'play' ? ' hit' : ''}">${ic('play')}${T(lang, 'play_all')}</span><span class="mpa-pb${o.hit === 'shuf' ? ' hit' : ''}">${ic('shuffle', 'sh')}${T(lang, 'shuffle_all')}</span></div>`;
  }
  // Q (v2, "quiet pair"): two 28 px pills in the sort pill's own style, right-aligned on the count line (or alone on a 28 px row where a page has no count)
  function qrow(lang, count, loading) {
    return `<div class="mpa-qrow" data-pair="1">${count != null ? `<span class="c">${loading ? sk('64px', 10) : count}</span>` : ''}<span class="mpa-qb f">${ic('play')}${T(lang, 'play_all')}</span><span class="mpa-qb">${ic('shuffle', 'sh')}${T(lang, 'shuffle_all')}</span></div>`;
  }
  // S (v3, "split"): one control — ▶ Play all | ▾ — the ▾ opens a menu with Shuffle all. Never on a row of its own.
  function split(lang, o) {
    o = o || {};
    return `<span class="mpa-sp${o.top ? ' top' : ''}${o.f ? ' f' : ''}" data-pair="1"><span class="a${o.focus === 'play' ? ' fc' : ''}">${ic('play')}${T(lang, 'play_all')}${o.tabs ? '<span class="mpa-tab">1</span>' : ''}</span><span class="b${o.menu ? ' hot' : ''}${o.focus === 'chev' ? ' fc' : ''}">${ic('chev')}${o.tabs ? '<span class="mpa-tab">2</span>' : ''}</span>${o.menu ? `<span class="mpa-spm"><span class="on${o.focus === 'menu' ? ' fc' : ''}">${ic('shuffle')}${T(lang, 'shuffle_all')}</span></span>` : ''}</span>`;
  }
  function chips(lang, on) {
    return `<div class="mpa-chips">${['artists', 'albums', 'songs', 'genres', 'playlists', 'books'].map(k => `<b class="${k === on ? 'on' : ''}">${T(lang, k)}</b>`).join('')}</div>`;
  }
  const field = (lang, q, right) => `<div class="mpa-srch${right ? ' row' : ''}"><div class="mpa-fld">${ic('search')}${q ? `<span class="q">${q}</span><span class="clr">${T(lang, 'clear')}</span>` : `<span>${T(lang, 'search_ph')}</span>`}</div>${right || ''}</div>`;

  // the page body below the app bar, for one direction. P = the pair's html if this direction puts it inline here.
  function body(o) {
    const lang = o.lang, d = o.dir, land = o.land, A = d === 'A', B = d === 'B', Q = d === 'Q', S = d === 'S', P0 = (A || B) ? pair(lang, { hit: o.hit }) : '', W = A && land && o.page !== 'listen', P = W ? '' : P0;
    const g2 = l => `<div class="mpa-g2">${l.map(x => acard(x, true)).join('')}</div>`;
    const loading = o.loading;
    const meta = s => S ? `<div class="mpa-qrow"><span class="c">${loading ? sk('64px', 10) : s}</span>${split(lang, { f: 1, menu: o.menu })}</div>` : Q ? qrow(lang, s, loading) : `<div class="mpa-meta">${loading ? sk('70px', 10, 'margin-top:2px') : s}</div>`;
    let h = '';
    if (o.page === 'listen') {
      const r1 = `<div class="mpa-row${A && !o.listenAlt || B ? ' first' : ''}">${rowh(T(lang, 'r_added'))}<div class="mpa-track">${AL.slice(0, land ? 6 : 4).map(x => acard(x)).join('')}</div></div>`;
      const r2 = `<div class="mpa-row">${rowh(T(lang, 'r_played'), T(lang, 'see_all'))}<div class="mpa-list">${RECENT.map(x => song(x)).join('')}</div></div>`;
      const r3 = `<div class="mpa-row">${rowh(T(lang, 'h_artists'))}<div class="mpa-track">${AR.slice(5, 11).map(x => arc(x, lang)).join('')}</div></div>`;
      const r4 = `<div class="mpa-row">${rowh('Indie Folk')}<div class="mpa-track">${FOLK.slice(0, 4).map(x => acard(x)).join('')}</div></div>`;
      if (S) return `<div style="height:6px"></div>${r1.replace('class="mpa-row"', 'class="mpa-row first"')}${r2}${r3}${r4}`;
      if (Q) return `<div style="height:8px"></div>${qrow(lang)}${r1.replace('class="mpa-row"', 'class="mpa-row first"')}${r2}${r3}${r4}`;
      if ((A || B) && !o.listenAlt) h = `<div style="height:6px"></div>${B ? '' : P}${r1}${r2}${r3}${r4}`;
      else if (A && o.listenAlt) h = `${r1}${r2}<div style="height:16px"></div>${P}${r3.replace('mpa-row"', 'mpa-row" style="margin-top:8px"')}${r4}`;
      else h = r1.replace('mpa-row', 'mpa-row first') + r2 + r3 + r4;
      if (!(A || B) || o.listenAlt) h = '<div style="height:6px"></div>' + h;
      return h;
    }
    const isSearch = o.page.indexOf('search') === 0;
    const q = o.page === 'search' ? 'harbour' : o.page === 'search-empty' ? 'harbor' : o.page === 'search-one' ? 'kvøldsól' : '';
    if (isSearch) {
      h += B ? '' : field(lang, q, W && o.page !== 'search-empty' ? P0 : '');
      if (o.page === 'search-empty') return h + `<div class="mpa-srchmeta">${T(lang, 'no_results', { q })}</div>`;
      h += A ? P : Q ? qrow(lang) : '';
      const sl = o.page === 'search-one' ? S_ONE : S_HARB;
      h += `<div class="mpa-row first">${rowh(T(lang, 'songs'), o.page === 'search' ? T(lang, 'see_all') : '')}<div class="mpa-list">${sl.map(x => song(x)).join('')}</div></div>`;
      if (o.page === 'search') h += `<div class="mpa-row">${rowh(T(lang, 'albums'))}${g2([AL[0], AL[1]])}</div><div class="mpa-row">${rowh(T(lang, 'artists'))}<div class="mpa-g3">${arc(AR[6], lang)}</div></div>`;
      return h;
    }
    const chip = o.page === 'genre' || o.page === 'genre-empty' ? 'albums' : o.page;
    h += B ? '' : field(lang, '', W && o.page !== 'genre-empty' ? P0 : '');
    h += chips(lang, chip);
    if (o.page === 'genre' || o.page === 'genre-empty') {
      const n = o.page === 'genre' ? 9 : 0;
      h += (S && n ? `<div class="mpa-bkrow"><div class="mpa-back">‹ ${T(lang, 'genres')}</div>${split(lang, { f: 1, menu: o.menu })}</div>` : `<div class="mpa-back">‹ ${T(lang, 'genres')}</div>`) + `<div class="mpa-row" style="margin-top:0">${rowh('Indie Folk', '').replace('</h3>', `<span class="more">${T(lang, 'albums_n', { n })}</span></h3>`)}</div>`;
      if (n) h += (A ? P : Q ? qrow(lang) : '') + g2(FOLK.slice(0, land ? 10 : 6));
      return h;
    }
    h += A ? P : '';
    if (o.page === 'albums') h += meta(o.big ? T(lang, 'albums_n', { n: 212 }) : T(lang, 'albums_n', { n: 30 })) + (loading ? `<div class="mpa-g2">${Array.from({ length: land ? 10 : 6 }, () => `<div><div class="mpa-skc"></div>${sk('80%', 10, 'margin-top:10px')}${sk('55%', 9, 'margin-top:6px')}</div>`).join('')}</div>` : g2(AL.slice(0, land ? 10 : 8)));
    if (o.page === 'artists') h += meta(T(lang, 'artists_n', { n: 20 })) + `<div class="mpa-g3">${AR.slice(0, land ? 14 : 12).map(x => arc(x, lang, true)).join('')}</div>`;
    if (o.page === 'songs') h += meta(o.big ? T(lang, 'songs_n', { n: fmtN(3214) }) : T(lang, 'songs_n', { n: 385 })) + (loading ? `<div class="mpa-list">${Array.from({ length: 8 }, () => `<div class="mpa-song"><span class="mpa-skc" style="width:48px;flex:none;border-radius:7px"></span><div class="b">${sk('62%', 11)}${sk('44%', 9, 'margin-top:8px')}</div></div>`).join('')}</div>` : `<div class="mpa-list">${SONGS.map((x, i) => song(x, o.playing && i === 0)).join('')}</div>`);
    return h;
  }
  function bar(lang, on) {
    const it = [['listen', 'listen'], ['browse', 'browse'], ['nowp', 'playing'], ['queue', 'queue']];
    return `<div class="mpa-bnv"><div class="brow">${it.map(([i, k]) => `<div class="mpa-bn${k === on ? ' on' : ''}"><span class="p">${ic(i)}</span><b>${T(lang, k)}</b></div>`).join('')}<div class="mpa-bn"><span class="p"><span class="av">ER</span></span><b>${T(lang, 'profile')}</b></div></div><div class="safe"></div></div>`;
  }
  const NOWS = { t: 'Kite Weather', a: 'Harbour Lights', al: 'Kite Weather' };
  function mini(o, bottom) {
    const s = o.npSong || NOWS;
    return `<div class="mpa-mini" style="bottom:${bottom}px"><div class="pg"><i></i></div><span class="gr"></span>${pc(s.al, 'sm')}<div class="tx"><div class="n">${s.t}</div><div class="d">${o.cast ? `<span>${s.a}</span><span>·</span><span class="on">${ic('speaker')}Stue</span>` : s.a}</div></div><span class="bt">${ic('pause')}</span><span class="bt">${ic('next')}</span></div>`;
  }
  function phone(o) {
    o = Object.assign({ lang: 'en', dir: 'A', page: 'albums', skin: 'aurora' }, o);
    const lang = o.lang, land = !!o.land, cmpw = !!o.cmpw;
    const sbH = cmpw ? 38 : land ? 24 : 34, apH = land ? 52 : 56, bnH = land ? 76 : cmpw ? 78 : 94;
    const tab = o.page === 'listen' ? 'listen' : o.page === 'np' ? 'playing' : 'browse';
    const sortOn = ['albums', 'artists', 'songs', 'genre', 'genre-empty'].indexOf(o.page) >= 0;
    const slot = (sortOn ? `<span class="mpa-sort">${T(lang, 'sort')}${ic('chev')}</span>` : '') + (o.dir === 'S' && ['listen', 'search', 'search-one'].indexOf(o.page) >= 0 ? split(lang, { top: 1, menu: o.menu }) : '') + (o.dir === 'C' && o.page !== 'np' && !/empty/.test(o.page) ? `<span class="mpa-ib${o.menu ? ' hot' : ''}" title="${T(lang, 'shuffle_all')}">${ic('shuffle')}</span>` : '');
    const top = cmpw ? `<div class="mpa-strip"><i></i><i></i><i></i><span>compact · 400 dp</span></div>` : `<div class="mpa-sb"><span>9:41</span><span class="si"><i></i><i></i><i></i></span></div>`;
    let h = top;
    if (o.page === 'np') {
      const s = o.npSong || NOWS;
      const val = o.npFrom === 'lib' ? T(lang, 'lib') : o.npFrom === 'genre' ? 'Indie Folk' : o.npFrom === 'search' ? (L[lang].srch ? T(lang, 'srch', { q: 'harbour' }) : '—') : s.al;
      h += `<div class="mpa-np" style="top:${sbH}px;bottom:${bnH}px;background:radial-gradient(120% 70% at 50% 18%,hsl(${H(s.al)} 45% 26%),var(--bg) 78%)"><div class="top"><span class="ib">${ic('down')}</span><div class="ctx hl">♪ ${T(lang, 'from')}<b>${val}</b></div><span class="ib">${ic('more')}</span></div><div class="ncov">${pc(s.al)}</div><div class="meta"><div class="t">${s.t}</div><div class="s">${s.a} — ${s.al}</div></div><div class="seek"><i></i><u></u></div><div class="tm"><span>0:48</span><span>−2:48</span></div><div class="tp"><span class="on">${ic('shuffle', 'sm')}</span>${ic('prev')}<span class="pp">${ic('pause')}</span>${ic('next')}${ic('repeat', 'sm')}</div></div>`;
      return frame(o, h + bar(lang, tab), land ? 'mpa-ph land' : cmpw ? 'mpa-ph cmpw' : 'mpa-ph');
    }
    h += `<div class="mpa-apb" style="top:${sbH}px"><div class="mpa-brand">${MARK(o.skin)}<span class="wm">Ravilo</span><span class="nt">♪</span></div><span class="sp"></span><div class="mpa-slot">${slot}</div><span class="mpa-ib">${ic('cast')}</span></div>`;
    let cTop = sbH + apH;
    // B: the pair (and the field above it, on Browse) never scroll away. At rest it is simply where it sits; scrolled, it stays.
    if (o.dir === 'B' && !/empty/.test(o.page)) {
      const isBrowse = o.page !== 'listen';
      const qq = o.page === 'search' ? 'harbour' : o.page === 'search-one' ? 'kvøldsól' : '';
      const pin = isBrowse && land ? field(lang, qq, pair(lang, { hit: o.hit })) : (isBrowse ? field(lang, qq) : '<div style="height:4px"></div>') + pair(lang, { pin: 1, hit: o.hit });
      const pinH = isBrowse && land ? 62 : (isBrowse ? 62 : 4) + 52;
      h += `<div class="mpa-pin${o.scroll ? ' edge' : ''}" style="top:${cTop}px;height:${pinH}px">${pin}</div>`;
      cTop += pinH;
    } else if (o.dir === 'B' && /empty/.test(o.page) && o.page === 'search-empty') {
      h += `<div class="mpa-pin" style="top:${cTop}px;height:62px">${field(lang, 'harbor')}</div>`; cTop += 62;
    }
    let b = body(o);
    if (o.dir === 'B' && o.page === 'search-empty') b = b.replace(/<div class="mpa-srch">[\s\S]*?<\/div><\/div>/, '');
    h += `<div class="mpa-cnt" style="top:${cTop}px"><div class="mpa-in" style="margin-top:${-(o.scroll || 0)}px">${b}</div></div>`;
    if (o.menu && o.dir === 'C') h += `<div class="mpa-menu" style="top:${sbH + 50}px"><div class="on">${ic('shuffle')}${T(lang, 'shuffle_all')}</div><div>${ic('play')}${T(lang, 'play_all')}</div></div>`;
    const mb = bnH + 8;
    if (o.playing) h += mini(o, mb);
    if (o.toast) {
      const msg = o.toast === 'capped' ? T(lang, 'capped', { shown: fmtN(1000), total: fmtN(3214) }) : T(lang, 'fail');
      h += `<div class="mpa-tst" style="bottom:${o.playing ? mb + 72 : bnH + 14}px">${msg}</div>`;
    }
    h += bar(lang, tab);
    if (o.ann) h += o.ann;
    return frame(o, h, land ? 'mpa-ph land' : cmpw ? 'mpa-ph cmpw' : 'mpa-ph');
  }
  function frame(o, inner, cls) { return `<div class="fr ${cls}" data-skin="${o.skin}" lang="${o.lang}"${o.id ? ` data-png="${o.id}"` : ''}>${inner}</div>`; }

  /* ============ DESKTOP (wide, macOS shell) ============ */
  const dcv = t => `<div class="mpa-cv" style="${cov(t)}"></div>`;
  const dal = ([t, a, y], yr) => `<div class="mpa-dal">${dcv(t)}<div class="t">${t}</div><div class="s">${a}${yr ? ' · ' + y : ''}</div></div>`;
  const dar = ([n, k, noimg], sub) => `<div class="mpa-dar"><div class="c" style="${arStyle(n, noimg)}">${noimg ? ini(n) : ''}</div><div class="t">${n}</div><div class="s">${sub ? (k === 1 ? '1 album' : k + ' albums') : 'Artist'}</div></div>`;
  const trk = (l, now) => `<div class="mpa-trk"><div class="hd"><span>#</span><span>Title</span><span>Artist</span><span>Album</span><span>Time</span></div>${l.map(([t, a, al, d], i) => `<div class="tr${now && i === 0 ? ' now' : ''}"><span class="nr">${i + 1}</span><span class="tt">${t}</span><span class="dm">${a}</span><span class="dm">${al}</span><span class="du">${d}</span></div>`).join('')}</div>`;
  function dpair(o, cls) { if (o.dir === 'S') return split('en', { focus: o.focus, tabs: o.tabs, menu: o.menu }); return `<div class="mpa-dp${cls ? ' ' + cls : ''}${o.dir === 'Q' ? ' q' : ''}" data-pair="1"><span class="mpa-bt pri${o.focus === 'play' ? ' fc' : ''}">${ic('play')}Play all${o.tabs ? '<span class="mpa-tab">1</span>' : ''}</span><span class="mpa-bt${o.focus === 'shuf' ? ' fc' : ''}">${ic('shuffle')}Shuffle all${o.tabs ? '<span class="mpa-tab">2</span>' : ''}</span></div>`; }
  function dside(o) {
    const nav = [[null, [['phones', 'Listen', 'listen'], ['wave', 'Playing'], ['lq', 'Queue']]], ['Library', [['person', 'Artists', 'artists', 20], ['disc', 'Albums', 'albums', 30], ['note', 'Songs', 'songs', 385], ['tag', 'Genres', 'genres', 18], ['plist', 'Playlists', null, 2], ['book', 'Audiobooks', null, 1]]]];
    const on = o.page === 'genre' ? 'genres' : o.page;
    return `<aside class="mpa-side"><div class="mpa-lights"><i></i><i></i><i></i></div><div class="mpa-seg"><b>Films &amp; series</b><b class="on">Music</b></div><div class="mpa-sf${o.page === 'search' ? ' on' : ''}">${ic('search')}<span>Search music</span></div><div class="mpa-nav">${nav.map(([hh, it]) => (hh ? `<h6>${hh}</h6>` : '') + it.map(([i, l, k, n]) => `<a class="${k && k === on ? 'on' : ''}">${ic(i)}<span>${l}</span>${n != null ? `<span class="n">${n}</span>` : ''}</a>`).join('')).join('')}</div><div class="mpa-who"><span class="av">E</span><div><div class="nm">Eyð</div><div class="sub">Settings · Sign out</div></div></div></aside>`;
  }
  function desk(o) {
    o = Object.assign({ dir: 'D1', page: 'albums', skin: 'aurora', lang: 'en' }, o);
    const R = o.dir === 'D1' || o.dir === 'Q' || o.dir === 'S', U = o.dir === 'D2';
    const head = (title, extra) => R ? `<div class="mpa-dh"><div class="h1">${title}</div><span class="sp"></span>${extra === false ? '' : dpair(o)}</div>` : `<div class="mpa-dh"><div class="h1">${title}</div></div>`;
    const under = U ? dpair(o, 'under') : '';
    let b = '';
    if (o.page === 'listen') b = head('Listen') + under + `<div class="mpa-rh" style="margin-top:10px"><h4>Recently added</h4><span class="more">See all</span></div><div class="mpa-drow">${AL.slice(0, 6).map(x => dal(x)).join('')}</div><div class="mpa-rh"><h4>Recently played</h4><span class="more">See all</span></div>${trk(RECENT.slice(0, 4))}`;
    if (o.page === 'albums') b = head('Albums') + `<div class="mpa-ml">${o.loading ? sk('64px', 10) : '30 albums'}</div>` + under + (o.loading ? `<div class="mpa-dgrid">${Array.from({ length: 12 }, () => `<div><div class="mpa-skc" style="border-radius:8px"></div>${sk('80%', 10, 'margin-top:10px')}</div>`).join('')}</div>` : `<div class="mpa-dgrid">${AL.map(x => dal(x, true)).join('')}</div>`);
    if (o.page === 'artists') b = head('Artists') + `<div class="mpa-ml">20 artists</div>` + under + `<div class="mpa-dgrid">${AR.slice(0, 12).map(x => dar(x, true)).join('')}</div>`;
    if (o.page === 'songs') b = head('Songs') + `<div class="mpa-ml">${o.loading ? sk('64px', 10) : o.big ? fmtN(3214) + ' songs' : '385 songs'}</div>` + under + (o.loading ? `<div class="mpa-trk"><div class="hd"><span>#</span><span>Title</span><span>Artist</span><span>Album</span><span>Time</span></div>${Array.from({ length: 10 }, () => `<div class="tr"><span></span>${sk('70%', 10)}${sk('60%', 10)}${sk('60%', 10)}<span></span></div>`).join('')}</div>` : trk(SONGS, o.playing));
    if (o.page === 'genre') b = head('Indie Folk') + `<div class="mpa-ml">9 albums</div>` + under + `<div class="mpa-dgrid">${FOLK.map(x => dal(x, true)).join('')}</div>`;
    if (o.page === 'search') b = (R ? `<div class="mpa-dh"><div class="mpa-dsrch">${ic('search')}<span class="q">harbour</span><span class="x">esc</span></div>${dpair(o)}</div>` : `<div class="mpa-dh"><div class="mpa-dsrch">${ic('search')}<span class="q">harbour</span><span class="x">esc</span></div></div><div style="height:12px"></div>${under}`)
      + `<div class="mpa-rh" style="margin-top:10px"><h4>Songs</h4><span class="more">See all</span></div>${trk(S_HARB)}<div class="mpa-rh"><h4>Albums</h4></div><div class="mpa-drow">${[AL[0], AL[1]].map(x => dal(x, true)).join('')}</div><div class="mpa-rh"><h4>Artists</h4></div><div class="mpa-drow">${dar(AR[6])}</div>`;
    const cap = o.playing ? `<div class="mpa-capbar"><span class="art" style="${cov('Kite Weather')}"></span><div class="tx"><div class="t">Kite Weather</div><div class="s">Harbour Lights</div></div><div class="ctl">${ic('prev')}<span class="pp">${ic('pause')}</span>${ic('next')}</div><div class="sk"><span>1:04</span><i></i><span>3:36</span></div><div class="r">${ic('lyr')}${ic('lq')}${ic('cast')}${ic('vol')}</div></div>` : '';
    const tst = o.toast ? `<div class="mpa-dtst">${o.toast === 'capped' ? 'Playing ' + fmtN(1000) + ' of ' + fmtN(3214) + ' songs' : 'Couldn’t play this'}</div>` : '';
    const inner = `${dside(o)}<div class="mpa-main"><div class="mpa-mtb"><span class="mpa-gl">${ic('back')}${ic('fwd', 'dim')}</span><span class="sp" style="flex:1"></span><span class="mpa-gl">${ic('cast')}</span></div><div class="mpa-body">${b}</div>${cap}${tst}${o.ann || ''}</div>`;
    return `<div class="fr mpa-dk" data-skin="${o.skin}" lang="en"${o.id ? ` data-png="${o.id}"` : ''}>${inner}</div>`;
  }
  window.MPK = { phone, desk, T, L, fmtN, ic };
})();
