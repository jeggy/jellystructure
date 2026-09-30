/* Ravilo desktop — shared frame builders (window.DK) for the desktop canvases; all names are fictional stand-ins (the title sweep). */
(function () {
  const P = {
    home: '<path d="M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/>',
    compass: '<circle cx="12" cy="12" r="9"/><path d="M15.5 8.5l-2 5-5 2 2-5z"/>',
    bookmark: '<path d="M6 3h12v18l-6-4-6 4z"/>',
    film: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 4v16M17 4v16M3 9h4M17 9h4M3 15h4M17 15h4"/>',
    tv: '<rect x="3" y="5" width="18" height="12" rx="2"/><path d="M8 21h8"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/>',
    note: '<path d="M9 18V5l11-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/>',
    disc: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="2.5"/>',
    person: '<circle cx="12" cy="8" r="4"/><path d="M4 21c1-4 4-6 8-6s7 2 8 6"/>',
    book: '<path d="M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2z"/><path d="M19 19v2H6"/>',
    phones: '<path d="M4 15v-3a8 8 0 0 1 16 0v3"/><rect x="3" y="14" width="4" height="6" rx="1"/><rect x="17" y="14" width="4" height="6" rx="1"/>',
    queue: '<path d="M4 6h16M4 12h16M4 18h10"/>',
    wave: '<path d="M5 10v4M9 6v12M13 9v6M17 4v16M21 10v4"/>',
    cast: '<path d="M3 17a4 4 0 0 1 4 4M3 13a8 8 0 0 1 8 8M3 9V7a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2h-6"/>',
    vol: '<path d="M4 9v6h4l5 4V5L8 9z"/><path d="M16 9a4 4 0 0 1 0 6"/>',
    lyr: '<path d="M4 5h16v11H9l-5 4z"/><path d="M8 9h8M8 12h5"/>',
    menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
    back: '<path d="M15 5l-7 7 7 7"/>', fwd: '<path d="M9 5l7 7-7 7"/>',
    x: '<path d="M6 6l12 12M18 6L6 18"/>',
    full: '<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>',
    subs: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M7 12h3M12 12h5M7 15h8"/>',
    side: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16"/>',
    up: '<path d="M7 14l5-5 5 5"/>', r10: '<path d="M4 12a8 8 0 1 0 3-6.2"/><path d="M4 4v4h4"/>', f10: '<path d="M20 12a8 8 0 1 1-3-6.2"/><path d="M20 4v4h-4"/>',
    shuffle: '<path d="M3 7h3l9 10h6M3 17h3l3-3.4M14 10l1-1h6M18 4l3 3-3 3M18 14l3 3-3 3"/>',
    play: '<path d="M8 5v14l11-7z"/>', pause: '<path d="M7 5h4v14H7zM13 5h4v14h-4z"/>',
    next: '<path d="M6 5l9 7-9 7zM16 5h2.5v14H16z"/>', prev: '<path d="M18 5l-9 7 9 7zM5.5 5H8v14H5.5z"/>',
  };
  const FILL = { play: 1, pause: 1, next: 1, prev: 1 };
  const ic = (n, c) => `<svg class="ic${FILL[n] ? ' f' : ''}${c ? ' ' + c : ''}" viewBox="0 0 24 24">${P[n]}</svg>`;
  const MARK = '<svg class="mk" viewBox="12 20 76 76"><defs><linearGradient id="rj" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#7b6ef0"/><stop offset="1" stop-color="#3fb6f5"/></linearGradient></defs><path d="M22 52 C22 24 78 24 78 52 C66 45 59 45 50 49 C41 45 34 45 22 52 Z" fill="url(#rj)"/><g stroke="url(#rj)" stroke-width="4.5" stroke-linecap="round" fill="none"><path d="M33 51 q-5 12 1 20 q5 8 0 14" opacity=".9"/><path d="M44 52 q-4 13 1 21 q4 9 0 13" opacity=".72"/><path d="M56 52 q4 13 -1 21 q-4 9 0 13" opacity=".72"/><path d="M67 51 q5 12 -1 20 q-5 8 0 14" opacity=".9"/></g></svg>';
  const G = ['#3a5f8a,#b3d4e8', '#7a3b2e,#e0a36b', '#2f4f3a,#9cc9a0', '#4b2f6b,#d49ae0', '#1f3f55,#5fb8c9', '#6b5a2a,#e8d28a', '#5a2a3a,#e07a8f', '#2a2f5a,#8a95e0', '#3a3a3a,#bdbdbd', '#1f4a45,#e0c07a', '#552a1f,#f0b27a', '#233a2a,#7ae0b0'];
  const bg = i => `background:linear-gradient(135deg,${G[i % G.length]})`;
  const A = 'assets/';

  /* ---------- data ---------- */
  const FILMS = [['The Salt Road', 1, 1], ['Northern Static', 7], ['Kelp Forest', 2], ['Harbour Nine', 4, 1], ['Ember Valley', 10], ['Quiet Coast', 5], ['The Long Ferry', 6], ['Glass Winter', 3]];
  const CONT = [['Big Buck Bunny', 'bbb-backdrop-bunny.png', 58, 'Film · 4 min left'], ['Mesterholdet', null, 30, 'S02E04 · 21 min left', 4], ['The Lamp Keepers', null, 72, 'S01E07 · 9 min left', 1], ['Low Tide', null, 12, 'Film · 1 h 18 min left', 9]];
  const ALB = [['Salt on the Window', 'Harbour Lights', 0], ['Quiet Hours', 'Marta Vang', 3], ['Low Water', 'The Ferrymen', 4], ['Signal Lamp', 'Copperline', 1], ['Lichen', 'Moss Garden', 2], ['Last Departure', 'Nightbus Orchestra', 7], ['Myrkur', 'Kvøld', 8], ['Viser fra vejen', 'Jens Holm', 5], ['Undertow', 'Sunniva & the Tides', 6], ['Northbound', 'Ola Brink', 9], ['Static Bloom', 'Velvet Static', 10], ['Skerries', 'Aldan', 11]];
  const ARTS = [['Harbour Lights', 0], ['Marta Vang', 3], ['The Ferrymen', 4], ['Copperline', 1], ['Moss Garden', 2], ['Kvøld', 8], ['Jens Holm', 5]];
  const TRK = [['Boathouse', '3:42'], ['Salt on the Window', '4:10', 'now'], ['Cannery Lights', '3:55'], ['Eleven Days', '5:02'], [null, 'Track 5 is not in the library'], ['Winter Tour', '4:27'], ['Ålesund Rain', '3:49'], ['Low Tide Hymn', '6:12']];
  const NOW = { t: 'Salt on the Window', a: 'Harbour Lights', g: 0 };

  /* ---------- chrome ---------- */
  const seg = (mode, cls) => `<div class="seg${cls ? ' ' + cls : ''}"><b class="${mode !== 'music' ? 'on' : ''}">Films &amp; series</b><b class="${mode === 'music' ? 'on' : ''}">Music</b></div>`;
  const eq = '<span class="eq"><i style="height:6px"></i><i style="height:11px"></i><i style="height:8px"></i></span>';
  const NAV = {
    films: [[null, [['home', 'Home'], ['compass', 'Discover'], ['bookmark', 'My List']]], ['Library', [['film', 'Films', '412'], ['tv', 'Series', '96']]]],
    music: [[null, [['phones', 'Listen'], ['wave', 'Playing'], ['queue', 'Queue']]], ['Library', [['person', 'Artists', '23'], ['disc', 'Albums', '30'], ['note', 'Songs', '60'], ['book', 'Audiobooks', '1']]]],
    both: [['Watch', [['home', 'Home'], ['compass', 'Discover'], ['film', 'Films'], ['tv', 'Series'], ['bookmark', 'My List']]], ['Listen', [['phones', 'Listen'], ['person', 'Artists'], ['disc', 'Albums'], ['note', 'Songs'], ['book', 'Audiobooks']]]],
  };
  function nav(set, active, playing) {
    return '<div class="nav">' + NAV[set].map(([h, items]) => (h ? `<h6>${h}</h6>` : '') + items.map(([i, l, n]) =>
      `<a class="${l === active ? 'on' : ''}">${ic(i)}<span>${l}</span>${l === 'Playing' && playing ? eq : n ? `<span class="n">${n}</span>` : ''}</a>`).join('')).join('') + '</div>';
  }
  const who = '<div class="who"><span class="av">E</span><div><div class="nm">Eyð</div><div class="sub">Settings · Sign out</div></div>' + ic('up') + '</div>';
  const search = mode => `<div class="sfield">${ic('search')}<span>${mode === 'music' ? 'Search music' : 'Search films &amp; series'}</span></div>`;

  function macSide(o) {
    return `<aside class="mside"><div class="lights"><i></i><i></i><i></i></div>${o.noSwitch ? '' : seg(o.mode)}${search(o.noSwitch ? 'both' : o.mode)}${nav(o.set || (o.mode === 'music' ? 'music' : 'films'), o.active, o.playing)}${who}</aside>`;
  }
  const vseg = mode => `<div class="seg v"><b class="${mode !== 'music' ? 'on' : ''}" title="Films &amp; series">${ic('film')}</b><b class="${mode === 'music' ? 'on' : ''}" title="Music">${ic('note')}</b></div>`;
  function railNav(set, active, playing) {
    return '<div class="nav">' + NAV[set].map(([h, items], gi) => (gi ? '<hr>' : '') + items.map(([i, l]) =>
      `<a class="${l === active ? 'on' : ''}">${ic(i)}<span>${l === 'Audiobooks' ? 'Books' : l}</span>${l === 'Playing' && playing ? '<span class="dot"></span>' : ''}</a>`).join('')).join('') + '</div>';
  }
  function macRail(o) {
    return `<aside class="mside rail"><div class="lights"><i></i><i></i><i></i></div>${vseg(o.mode)}<div class="nav"><a>${ic('search')}<span>Search</span></a></div>${railNav(o.mode === 'music' ? 'music' : 'films', o.active, o.playing)}<span class="av" style="margin:auto auto 4px">E</span></aside>`;
  }
  function gnRail(o) {
    return `<aside class="gside rail"><div class="ghb" style="justify-content:center;padding:0"><span class="gb">${ic('menu')}</span></div>${vseg(o.mode)}<div class="nav"><a>${ic('search')}<span>Search</span></a></div>${railNav(o.mode === 'music' ? 'music' : 'films', o.active, o.playing)}<span class="av" style="margin:auto auto 4px">E</span></aside>`;
  }
  function gnSide(o) {
    return `<aside class="gside"><div class="ghb">${MARK}<span class="tt">Ravilo</span><span class="sp"></span><span class="gb">${ic('menu')}</span></div>${o.noSwitch ? '' : seg(o.mode)}${search(o.noSwitch ? 'both' : o.mode)}${nav(o.set || (o.mode === 'music' ? 'music' : 'films'), o.active, o.playing)}${who}</aside>`;
  }
  const macTb = (title, over, extra) => `<div class="mtb${over ? ' over' : ''}"><span class="gl two">${ic('back')}${ic('fwd', 'dim')}</span>${title ? `<span class="tt">${title}</span>` : ''}<span class="sp"></span>${extra || ''}<span class="gl">${ic('cast')}</span></div>`;
  const gnHb = (title, backBtn, extra) => `<div class="ghb center">${backBtn ? `<span class="gb">${ic('back')}</span>` : ''}${title ? `<span class="tt">${title}</span>` : ''}<span class="sp"></span>${extra || ''}<span class="gb">${ic('cast')}</span><span class="gx">${ic('x')}</span></div>`;

  function capBar(o) {
    o = o || {};
    return `<div class="cap-bar${o.compact ? ' cmp' : ''}"><span class="pb-art" style="${bg(NOW.g)}"></span><div class="pb-t"><div class="t">${NOW.t}</div><div class="s">${NOW.a}${o.dev ? ' · Stue' : ''}</div></div><div class="pb-ctl"><span class="b">${ic('prev')}</span><span class="pp">${ic('pause')}</span><span class="b">${ic('next')}</span></div><div class="pb-seek"><span>1:44</span><span class="tr"><i></i></span><span>4:10</span></div><div class="pb-r">${o.compact ? '' : `<span class="b">${ic('lyr')}</span>`}<span class="b${o.q ? ' on' : ''}">${ic('queue')}</span><span class="b${o.dev ? ' on' : ''}">${ic('cast')}</span>${o.compact ? '' : ic('vol') + '<span class="vol"><i></i></span>'}</div></div>`;
  }
  function gBar(o) {
    o = o || {};
    if (o.mini) return `<div class="gbar mini"><span class="ln"><i></i></span><span class="pb-art" style="${bg(NOW.g)}"></span><div class="pb-t" style="flex:1;width:auto"><div class="t">${NOW.t}</div><div class="s">${NOW.a}</div></div><div class="pb-ctl"><span class="pp">${ic('pause')}</span><span class="b">${ic('next')}</span></div></div>`;
    return `<div class="gbar${o.compact ? ' cmp' : ''}"><span class="ln"><i></i></span><span class="pb-art" style="${bg(NOW.g)}"></span><div class="pb-t"><div class="t">${NOW.t}</div><div class="s">${NOW.a}</div></div><div class="pb-ctl">${o.compact ? '' : `<span class="b">${ic('shuffle')}</span>`}<span class="b">${ic('prev')}</span><span class="pp">${ic('pause')}</span><span class="b">${ic('next')}</span></div><div class="pb-seek"><span>1:44</span><span class="tr"><i></i></span><span>4:10</span></div><div class="pb-r">${o.compact ? '' : `<span class="b">${ic('lyr')}</span>`}<span class="b${o.q ? ' on' : ''}">${ic('queue')}</span><span class="b">${ic('cast')}</span>${o.compact ? '' : ic('vol') + '<span class="vol"><i></i></span>'}</div></div>`;
  }

  /* ---------- pages ---------- */
  const poster = ([t, g, k]) => `<div class="po"><div class="a" style="${bg(g)}">${k ? '<span class="q4k">4K</span>' : ''}<span style="position:relative">${t}</span></div><div class="t">${t}</div></div>`;
  const cont = ([t, img, p, s, g]) => `<div class="ls"><div class="a" style="${img ? `background-image:url(${A}${img})` : bg(g)}"><span class="pg"><i style="width:${p}%"></i></span></div><div class="t">${t}</div><div class="s">${s}</div></div>`;
  const album = ([t, a, g]) => `<div class="al"><div class="a" style="${bg(g)}"><span style="position:relative">${t}</span></div><div class="t">${t}</div><div class="s">${a}</div></div>`;
  const artist = ([t, g]) => `<div class="ar"><div class="a" style="${bg(g)}"></div><div class="t">${t}</div><div class="s">Artist</div></div>`;

  function filmsHome(bottomPad) {
    return `<div class="body"><div class="hero" style="background-image:url(${A}bbb-backdrop-landscape.png)"><div class="hb"><div class="kick">New in Films</div><img class="lg" src="${A}bbb-logo.png" alt=""><div class="meta"><span>2008</span><span>10 min</span><span class="tg">Animation</span><span class="tg">4K</span></div><p class="syn">A gentle giant rabbit takes on three bullies of the forest in a comedy with no words at all.</p><div class="btns"><span class="bt pri">${ic('play')}Play</span><span class="bt">${ic('bookmark')}My List</span></div></div></div>
      <div class="pad"><div class="rh"><h4>Continue watching</h4></div><div class="row">${CONT.map(cont).join('')}</div>
      <div class="rh"><h4>New in Films</h4><span class="more">See all</span></div><div class="row">${FILMS.map(poster).join('')}</div></div>${bottomPad ? '' : ''}</div>`;
  }
  function listen() {
    return `<div class="body"><div class="pad" style="padding-top:4px"><div class="h1">Listen</div>
      <div class="rh"><h4>Continue listening</h4></div><div class="bookc"><span class="c" style="${bg(9)}"></span><div><div class="t">The Lamplighter’s Year</div><div class="s">Ida Kross · Chapter 9 of 24 · 6 h 12 min left</div></div><span class="ring"><i>${ic('play')}</i></span></div>
      <div class="rh"><h4>Recently played</h4><span class="more">See all</span></div><div class="row">${ALB.slice(0, 7).map(album).join('')}</div>
      <div class="rh"><h4>Artists you play</h4></div><div class="row">${ARTS.map(artist).join('')}</div></div></div>`;
  }
  function albumPage(queue, overlay) {
    const tr = TRK.map(([t, d, c], i) => t ? `<div class="tr${c ? ' now' : ''}"><span class="nr">${c ? eq.replace('eq', 'eq" style="margin:0') : i + 1}</span><span>${t}</span><span class="du">${d}</span></div>` : `<div class="tr gap"><span class="nr">${i + 1}</span><span>${d}</span><span></span></div>`).join('');
    const q = queue ? `<div class="qp${overlay ? ' ov' : ''}"><h5>Now playing</h5><div class="qr now"><span class="c" style="${bg(0)}"></span><div class="b"><div class="t">Salt on the Window</div><div class="s">Harbour Lights</div></div></div><h5>Next · from Salt on the Window</h5>${TRK.slice(2).filter(x => x[0]).map(x => `<div class="qr"><span class="c" style="${bg(0)}"></span><div class="b"><div class="t">${x[0]}</div><div class="s">Harbour Lights · ${x[1]}</div></div><span class="g">⋮⋮</span></div>`).join('')}</div>` : '';
    return `<div class="body" style="${queue && !overlay ? 'padding-right:300px' : ''}"><div class="alh"><span class="cv" style="${bg(0)}"></span><div><div class="k">Album · 2004</div><div class="h1">Salt on the Window</div><div class="by">Harbour Lights</div><div class="mt">8 tracks · 34 min · Indie folk</div><div class="btns"><span class="bt pri">${ic('play')}Play</span><span class="bt">${ic('shuffle')}Shuffle</span></div></div></div><div class="trk">${tr}</div></div>${q}`;
  }
  function albumsGrid() {
    return `<div class="body"><div class="pad" style="padding-top:4px"><div class="h1">Albums</div><div class="chips"><b class="on">All</b><b>Indie folk</b><b>Ambient</b><b>Visur</b><b>Post-rock</b></div><div class="grid" style="margin-top:16px">${ALB.slice(0, 10).map(album).join('')}</div></div></div>`;
  }
  function nowPlaying() {
    return `<div class="body"><div class="np"><div class="bl" style="${bg(0)}"></div><div><span class="cv" style="display:block;${bg(0)}"></span><h2>${NOW.t}</h2><div class="by">${NOW.a} · Salt on the Window</div><span class="devc">${ic('cast')}Stue</span><div class="seek"><div class="tr"><i></i></div><div class="tm"><span>1:44</span><span>4:10</span></div></div><div class="tp"><span class="sm">${ic('shuffle')}</span>${ic('prev')}<span class="pp">${ic('pause')}</span>${ic('next')}<span class="sm">${ic('queue')}</span></div></div>
      <div class="lyr"><p>The window keeps the salt</p><p>from every winter we stayed</p><p class="now">and I write your name in it</p><p>with the back of my hand</p><p>the harbour lights go out</p><p>one boat at a time</p></div></div></div>`;
  }
  function filmPlayer(p) {
    const ctl = p === 'mac' ? `<div class="lights" style="padding:0 6px 0 4px"><i></i><i></i><i></i></div><span class="gl">${ic('back')}</span>` : `<span class="gb bg">${ic('back')}</span>`;
    const end = p === 'mac' ? '' : `<span class="gx" style="margin-right:4px">${ic('x')}</span>`;
    return `<div class="fp" style="background-image:url(${A}bbb-backdrop-rodents.png)"><div class="top">${ctl}<img src="${A}bbb-logo.png" alt="">${end}</div><div class="bot"><div class="ttl">Big Buck Bunny</div><div class="sk"><i></i><u></u></div><div class="ct"><span class="pp">${ic('pause')}</span>${ic('r10')}${ic('f10')}<span>3:06 / 9:56</span><span class="sp"></span>${ic('subs')}${ic('cast')}${ic('vol')}${ic('full')}</div></div></div>`;
  }

  /* ---------- windows ---------- */
  function mac(o) {
    const side = o.noSide ? '' : o.rail ? macRail(o) : macSide(o);
    return `<div class="win mac${o.noSide ? ' noside' : ''}${o.cls ? ' ' + o.cls : ''}"${o.skin ? ` data-skin="${o.skin}"` : ''}${o.w ? ` style="width:${o.w}px"` : ''}>${o.over || ''}${side}<div class="main">${o.tb || ''}${o.page}${o.bar ? capBar(o.bar) : ''}</div></div>`;
  }
  function gn(o) {
    const side = o.noSide ? '' : o.rail ? gnRail(o) : gnSide(o);
    return `<div class="win gn${o.noSide ? ' noside' : ''}${o.narrow ? ' narrow' : ''}${o.cls ? ' ' + o.cls : ''}"${o.skin ? ` data-skin="${o.skin}"` : ''}${o.w ? ` style="width:${o.w}px"` : ''}>${o.over || ''}${side}<div class="main">${o.hb || ''}${o.page}</div>${o.bar ? gBar(o.bar) : ''}</div>`;
  }

  window.DK = { P, ic, MARK, G, bg, A, FILMS, CONT, ALB, ARTS, TRK, NOW, seg, eq, NAV, nav, who, search, macSide, gnSide, macTb, gnHb, capBar, gBar, poster, cont, album, artist, filmsHome, listen, albumPage, albumsGrid, nowPlaying, filmPlayer, mac, gn, vseg, macRail, gnRail };
})();
