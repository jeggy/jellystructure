/* Ravilo phone — music mode (design brief 2026-09-27): the switch, the bar, and the pages.
   The player, mini bar, sheets and the review panel are in ravilo-music-player.js. Data: ../app/music-data.js
   (the same stand-in library the admin shows). Host hooks: window.RaviloHost (Ravilo Mobile.html). */
(function () {
  const M = window.MUSIC, H = window.RaviloHost;
  if (!M || !H) return;
  const esc = H.esc, t = (k, v) => (window.t ? window.t(k, v) : k), $ = id => document.getElementById(id);
  const QKEY = 'ravilo-music-q', MKEY = 'ravilo-mode';
  const QDEF = { j1: 'card', j2: 'cnp', j3: 'draw', j4: 'cover', j5: 'swipe', j6: 'on', j7: 'two', s1: 'yes', s3: 'yes', s4: 'thirty', s5: 'settings' };
  let q = {}; try { q = JSON.parse(localStorage.getItem(QKEY) || '{}'); } catch (e) {}
  q = Object.assign({}, QDEF, q);
  // owner, 2026-09-27: Library and Search merged, Now playing in the middle — the new default bar
  // owner, 2026-09-27 (second note): (c) Listen · Browse · Search · Queue, with Now playing in the middle
  // owner, 2026-09-27 (third note): Browse and Search merged ⇒ Listen · Browse · Playing · Queue, Playing centred with Profile
  if (!q.v4) { q.j2 = 'cnp'; q.v2 = 1; q.v3 = 1; q.v4 = 1; try { localStorage.setItem(QKEY, JSON.stringify(q)); } catch (e) {} }
  const U = new URLSearchParams(location.search);
  const MS = window.RaviloMusicState = {
    mode: (localStorage.getItem(MKEY) === 'music') ? 'music' : 'video', granted: U.get('music') !== 'none',
    size: U.get('mlib') || 'household', q, libChip: 'artists', libSort: 0, det: null, detStack: [], srch: '', srchAll: null,
    favs: new Set(['salt-on-the-window-1']), playlists: [], evenVol: true, genre: null,
  };
  if (U.get('mode') === 'music') MS.mode = 'music';
  if (U.get('mode') === 'video') MS.mode = 'video';
  const setQ = (k, v) => { q[k] = v; try { localStorage.setItem(QKEY, JSON.stringify(q)); } catch (e) {} };

  /* ---- glyphs ---- */
  const G = {
    home: '<svg viewBox="0 0 24 24"><path d="M4 11l8-7 8 7"/><path d="M6 10v9h12v-9"/></svg>',
    library: '<svg viewBox="0 0 24 24"><rect x="3" y="8" width="12" height="12" rx="2"/><path d="M7 5h10a2 2 0 0 1 2 2v10"/><path d="M11 2.5h8.5a2 2 0 0 1 2 2V13"/></svg>',
    search: '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20.5 20.5l-4.2-4.2"/></svg>',
    playlists: '<svg viewBox="0 0 24 24"><path d="M4 6h11M4 11h11M4 16h6"/><circle cx="16.5" cy="17.5" r="2.5"/><path d="M19 17.5V9l2-1"/></svg>',
    albums: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="2.6"/></svg>',
    artists: '<svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="4"/><path d="M4.5 20a7.5 7.5 0 0 1 15 0"/></svg>',
    listen: '<svg viewBox="0 0 24 24"><circle cx="7.5" cy="17.5" r="2.8"/><path d="M10.3 17.5V5.5l9.2-2v12"/><circle cx="16.7" cy="15.5" r="2.8"/></svg>',
    browse: '<svg viewBox="0 0 24 24"><rect x="4" y="4" width="7" height="7" rx="1.5"/><rect x="13" y="4" width="7" height="7" rx="1.5"/><rect x="4" y="13" width="7" height="7" rx="1.5"/><rect x="13" y="13" width="7" height="7" rx="1.5"/></svg>',
    queue: '<svg viewBox="0 0 24 24"><path d="M4 6h12M4 11h12M4 16h7"/><path d="M16 14l5 3.2-5 3.2z"/></svg>',
    nowp: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M8.5 15.5v-3M11 15.5V9M13.5 15.5v-5M16 15.5v-2"/></svg>',
    books: '<svg viewBox="0 0 24 24"><path d="M4 5.5A1.5 1.5 0 0 1 5.5 4H11v15H5.5A1.5 1.5 0 0 0 4 20.5z"/><path d="M11 4h5.5A1.5 1.5 0 0 1 18 5.5V12"/><path d="M14 16.5a3.5 3.5 0 0 1 7 0v2"/><rect x="13.3" y="17.2" width="2" height="3.3" rx=".8"/><rect x="19.7" y="17.2" width="2" height="3.3" rx=".8"/></svg>',
    more: '<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.9"/><circle cx="12" cy="12" r="1.9"/><circle cx="19" cy="12" r="1.9"/></svg>',
    lyr: '<svg viewBox="0 0 24 24"><path d="M4 6h12M4 11h9M4 16h6"/><circle cx="17.5" cy="16.5" r="2.5"/><path d="M20 16.5V8l-2 1"/></svg>',
    play: '<svg viewBox="0 0 24 24"><path d="M8 5l11 7-11 7z"/></svg>',
    shuffle: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7h3.5c5 0 6 10 11 10H21"/><path d="M3 17h3.5c2 0 3.2-1.6 4.2-3.5M14 8.5c1-1 2-1.5 3.5-1.5H21"/><path d="M18.5 4.5 21 7l-2.5 2.5M18.5 14.5 21 17l-2.5 2.5"/></svg>',
    chev: '<svg class="chev" viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"/></svg>',
    note: '<svg class="ic" viewBox="0 0 24 24"><circle cx="8" cy="17" r="3"/><path d="M11 17V5l9-2v12"/><circle cx="17" cy="15" r="3"/></svg>',
    film: '<svg class="ic" viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="M10 9.5l5 2.5-5 2.5z"/></svg>',
  };
  MS.G = G;
  // part 2: the lean (a) puts Audiobooks fourth and Playlists becomes a Library chip; (a′) is part 1's original
  const BARS = { a: [['m-home', 'mnav.home', 'home'], ['m-library', 'mnav.library', 'library'], ['m-search', 'mnav.search', 'search'], ['m-books', 'mnav.audiobooks', 'books']],
    ap: [['m-home', 'mnav.home', 'home'], ['m-library', 'mnav.library', 'library'], ['m-search', 'mnav.search', 'search'], ['m-playlists', 'mnav.playlists', 'playlists']],
    np: [['m-home', 'mnav.home', 'home'], ['m-library', 'mnav.library', 'library'], ['m-now', 'mnav.now', 'nowp'], ['m-books', 'mnav.audiobooks', 'books']],
    cnp: [['m-listen', 'mnav.listen', 'listen'], ['m-browse', 'mnav.browse', 'browse'], ['m-now', 'mnav.now_short', 'nowp'], ['m-queue', 'mnav.queue', 'queue']],
    cnp6: [['m-listen', 'mnav.listen', 'listen'], ['m-browse', 'mnav.browse', 'browse'], ['m-now', 'mnav.now_short', 'nowp'], ['m-search', 'mnav.search', 'search'], ['m-queue', 'mnav.queue', 'queue']],
    b: [['m-home', 'mnav.home', 'home'], ['m-albums', 'mnav.albums', 'albums'], ['m-artists', 'mnav.artists', 'artists'], ['m-search', 'mnav.search', 'search']],
    c: [['m-listen', 'mnav.listen', 'listen'], ['m-browse', 'mnav.browse', 'browse'], ['m-search', 'mnav.search', 'search'], ['m-queue', 'mnav.queue', 'queue']] };
  const bar = () => BARS[q.j2] || BARS.a;

  /* ---- data views (the viewer side: no cover ⇒ the title as a wordmark, never a badge) ---- */
  const artistOf = a => M.artist(a.artistId);
  const cover = (a, cls) => '<div class="mu-cv' + (cls ? ' ' + cls : '') + '" style="' + (a.cover && !MS.nocoverAll ? M.coverStyle(a) : M.wordmarkStyle(a.title)) + '">' + (a.cover && !MS.nocoverAll ? '' : '<span class="wm">' + esc(a.title) + '</span>') + '</div>';
  const artImg = r => r.image ? M.artistStyle(r) : M.wordmarkStyle(r.name);
  const songsN = n => n === 1 ? t('music.songs_one') : t('music.songs_n', { n });
  const albumsN = n => n === 1 ? t('music.albums_one') : t('music.albums_n', { n });
  const empty = () => MS.size === 'empty';
  const RECENT = ['salt-on-the-window', 'glass-birds', 'signal-lost', 'brim', 'stormur', 'undertow', 'kite-weather', 'north-atlantic-songbook', 'last-stop-everybody', 'low-tide-radio'];
  const recentAlbums = () => RECENT.map(M.album);
  const recentPlayed = () => M.tracks.filter(x => x.last != null).sort((a, b) => a.last - b.last).slice(0, 5);
  const albumArtists = () => M.artists.filter(r => M.albumsOf(r.id).length).sort((a, b) => (b.image - a.image) || M.albumsOf(b.id).length - M.albumsOf(a.id).length);
  MS.util = { cover, artImg, songsN, albumsN, artistOf, recentPlayed };

  function acard(a) { return '<div class="mu-acard" data-mu="album" data-id="' + a.id + '">' + cover(a) + '<div class="t">' + esc(a.title) + '</div><div class="s">' + esc(artistOf(a).name) + '</div></div>'; }
  function arc(r, sub) { return '<div class="mu-arc" data-mu="artist" data-id="' + r.id + '"><div class="c" style="' + artImg(r) + '">' + (r.image ? '' : esc(M.initials(r.name))) + '</div><div class="t">' + esc(r.name) + '</div>' + (sub ? '<div class="s">' + sub + '</div>' : '') + '</div>'; }
  function songRow(x, ctx, i) {
    const a = M.album(x.albumId), cur = MS.P && MS.P.cur() === x.id;
    return '<div class="mu-song' + (cur ? ' on' : '') + '" data-mu="song" data-id="' + x.id + '" data-ctx="' + ctx + '" data-i="' + i + '">' + cover(a, 'sm')
      + '<div class="b"><div class="t">' + esc(x.title) + '</div><div class="s">' + esc(M.artistNames(x.artistIds)) + ' — ' + esc(a.title) + '</div></div>'
      + '<span class="ln">' + M.fmtLen(x.len) + '</span><button class="mu-more" data-mu="menu" data-id="' + x.id + '" aria-label="More">' + G.more + '</button></div>';
  }
  MS.songRow = songRow;
  const row = (title, inner, more) => '<div class="row"><h3>' + title + (more ? '<span class="more" data-mu="' + more + '">' + t('music.see_all') + ' ›</span>' : '') + '</h3>' + inner + '</div>';
  // contexts: a list of track ids that becomes the queue when one of them is tapped
  const CTX = {};
  MS.CTX = CTX;

  /* ---- pages ---- */
  function home(listen) {
    if (empty()) return '<div class="mu-empty">' + t('mhome.empty') + '</div>';
    const rp = recentPlayed(); CTX.recent = rp.map(x => x.id);
    let h = '';
    // no Now-playing card at the top of Listen: the mini bar above the bottom bar already shows it (owner, 2026-09-27)
    void listen;
    // R323 FR-R323-2 (owner 2026-09-28: the lean) — a book in progress is one tap from Listen, not two
    if (listen && window.RaviloBooks && RaviloBooks.contRow) h += RaviloBooks.contRow();
    h += row(t('mhome.recent_albums'), '<div class="mu-track">' + recentAlbums().map(acard).join('') + '</div>');
    h += row(t('mhome.recent_played'), '<div class="mu-list">' + rp.map((x, i) => songRow(x, 'recent', i)).join('') + '</div>', 'seeall-recent');
    h += row(t('mhome.artists'), '<div class="mu-track">' + albumArtists().slice(0, 10).map(r => arc(r)).join('') + '</div>');
    if (false) { // owner 2026-09-28: no Mix row in round 1
      const mix = ['salt-on-the-window', 'signal-lost', 'brim', 'glass-birds'].map(M.album);
      CTX.mix = M.tracks.filter((x, i) => i % 3 === 0).map(x => x.id);
      h += '<div class="row"><h3>' + t('mhome.mix') + '</h3><div class="mu-mix" data-mu="mix"><div class="col">' + mix.map(a => '<i style="' + M.coverStyle(a) + '"></i>').join('') + '</div><div><b>' + t('mhome.mix') + '</b><span>Harbour Lights, Velvet Static, Aldan and more</span></div></div></div>';
    }
    M.genres().filter(g => g.albums >= 3).forEach(g => {
      const al = M.albums.filter(a => a.genres.some(x => x.name === g.name));
      h += row(esc(g.name.charAt(0).toUpperCase() + g.name.slice(1)), '<div class="mu-track">' + al.map(acard).join('') + '</div>');
    });
    if (q.j2 === 'b') h += row(t('mnav.playlists'), MS.playlists.length ? '<div>' + MS.playlists.map(plRow).join('') + '</div>' : '<div class="mu-meta">' + t('music.no_playlists') + '</div>');
    if (q.j2 === 'b' && window.RaviloBooks) h += RaviloBooks.homeRow();
    return h + '<div style="height:24px"></div>';
  }
  const SORTS = ['music.sort_added', 'music.sort_az', 'music.sort_year', 'music.sort_played'];
  function sorted(list, kind) {
    const s = MS.libSort;
    if (kind === 'artists') return s === 1 || s === 0 ? list.slice().sort((a, b) => a.sort.localeCompare(b.sort)) : list;
    if (kind === 'songs') {
      if (s === 1) return list.slice().sort((a, b) => a.title.localeCompare(b.title));
      if (s === 2) return list.slice().sort((a, b) => M.album(b.albumId).year - M.album(a.albumId).year);
      if (s === 3) return list.slice().sort((a, b) => b.plays - a.plays);
      return list;
    }
    if (s === 1) return list.slice().sort((a, b) => a.title.localeCompare(b.title));
    if (s === 2) return list.slice().sort((a, b) => b.year - a.year);
    if (s === 3) return list.slice().sort((a, b) => M.tracksOf(b.id).reduce((n, x) => n + x.plays, 0) - M.tracksOf(a.id).reduce((n, x) => n + x.plays, 0));
    return RECENT.map(M.album).concat(M.albums.filter(a => RECENT.indexOf(a.id) < 0));
  }
  function albumsGrid() { const l = sorted(M.albums, 'albums'); return '<div class="mu-meta">' + albumsN(l.length) + '</div><div class="mu-g2">' + l.map(a => '<div class="mu-acard" data-mu="album" data-id="' + a.id + '">' + cover(a) + '<div class="t">' + esc(a.title) + '</div><div class="s">' + esc(artistOf(a).name) + ' · ' + a.year + '</div></div>').join('') + '</div>'; }
  function artistsGrid() { const l = sorted(albumArtists(), 'artists'); return '<div class="mu-meta">' + l.length + ' ' + t('mlib.artists').toLowerCase() + '</div><div class="mu-g3">' + l.map(r => arc(r, albumsN(M.albumsOf(r.id).length))).join('') + '</div>'; }
  function songsList() { const l = sorted(M.tracks, 'songs'); CTX.songs = l.map(x => x.id); return '<div class="mu-meta">' + songsN(l.length) + '</div><div class="mu-list">' + l.map((x, i) => songRow(x, 'songs', i)).join('') + '</div>'; }
  function genresList() {
    if (MS.genre) { const al = M.albums.filter(a => a.genres.some(x => x.name === MS.genre));
      return '<div class="mu-back" data-mu="genre-back">‹ ' + t('mlib.genres') + '</div><div class="row" style="margin-top:0"><h3>' + esc(MS.genre) + '<span class="more" style="cursor:default">' + albumsN(al.length) + '</span></h3></div><div class="mu-g2">' + al.map(acard).join('') + '</div>'; }
    return '<div class="mu-gen">' + M.genres().map(g => '<button data-mu="genre" data-id="' + esc(g.name) + '">' + esc(g.name) + ' <span>' + g.albums + '</span></button>').join('') + '</div>';
  }
  // (a) and (c): Playlists is a Library chip; with Playlists in the bar (a′) or no Audiobooks tab (a′, c) books are a chip too
  // owner 2026-09-30: the same order as jellystructure's Library → Music — Artists · Albums · Songs, then the rest; Artists first
  const CHIPS = () => ['artists', 'albums', 'songs', 'genres'].concat(['a', 'c', 'np', 'cnp', 'cnp6'].indexOf(q.j2) >= 0 ? ['playlists'] : []).concat(['ap', 'c', 'cnp', 'cnp6'].indexOf(q.j2) >= 0 ? ['books'] : []);
  function library() {
    if (empty()) return '<div class="mu-empty">' + t('mhome.empty') + '</div>';
    const c = MS.libChip;
    const chips = '<div class="tabs discsegs">' + CHIPS().map(k => '<div class="tab' + (k === c ? ' on' : '') + '" data-mu="chip" data-id="' + k + '">' + (k === 'playlists' ? t('mnav.playlists') : k === 'books' ? t('mnav.audiobooks') : t('mlib.' + k)) + '</div>').join('') + '</div>';
    return chips + (c === 'artists' ? artistsGrid() : c === 'songs' ? songsList() : c === 'genres' ? genresList() : c === 'playlists' ? playlists(true) : c === 'books' && window.RaviloBooks ? RaviloBooks.shelf(true) : albumsGrid()) + '<div style="height:24px"></div>';
  }
  function paintSort(tab) {
    const slot = $('libSlot'); if (!slot) return;
    if (tab === 'm-books') { if (window.RaviloBooks) RaviloBooks.paintSort(slot); return; }
    if (['m-library', 'm-browse', 'm-albums', 'm-artists'].indexOf(tab) < 0 || empty() || (tab !== 'm-albums' && tab !== 'm-artists' && (MS.libChip === 'genres' || MS.libChip === 'books'))) return;
    slot.innerHTML = '<button class="mu-sortpill" data-mu="sort">' + t(SORTS[MS.libSort]) + '<svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg></button>';
  }
  function search() {
    return '<div class="srch"><div class="fld"><svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20.5 20.5l-4.2-4.2"/></svg><input id="muSrch" type="search" placeholder="' + esc(t('music.search_ph')) + '" autocomplete="off"><button class="clr" id="muSrchClr" type="button" hidden>Clear</button></div></div><div id="muSrchRes"></div><div style="height:24px"></div>';
  }
  function fillSearch() {
    const res = $('muSrchRes'); if (!res) return;
    const s = MS.srch.trim().toLowerCase();
    const body = $('muLibBody'); if (body) { body.hidden = !!s; if (!s) { res.innerHTML = ''; return; } }
    if (!s) { const rp = recentPlayed(); CTX.recent = rp.map(x => x.id);
      res.innerHTML = empty() ? '' : row(t('mhome.recent_played'), '<div class="mu-list">' + rp.map((x, i) => songRow(x, 'recent', i)).join('') + '</div>') + row(t('mhome.artists'), '<div class="mu-track">' + albumArtists().slice(0, 10).map(r => arc(r)).join('') + '</div>'); return; }
    const hit = v => String(v).toLowerCase().indexOf(s) >= 0;
    const songs = M.tracks.filter(x => hit(x.title) || hit(M.artistNames(x.artistIds))), albums = M.albums.filter(a => hit(a.title) || hit(artistOf(a).name)), artists = M.artists.filter(r => hit(r.name));
    if (!songs.length && !albums.length && !artists.length) { res.innerHTML = '<div class="srchmeta">' + esc(t('music.no_results', { q: MS.srch.trim() })) + '</div>'; return; }
    CTX.srch = songs.map(x => x.id);
    const cap = (k, l) => MS.srchAll === k ? l : l.slice(0, 3), more = (k, l) => l.length > 3 && MS.srchAll !== k ? 'srch-all-' + k : null;
    res.innerHTML = (songs.length ? row(t('mlib.songs'), '<div class="mu-list">' + cap('songs', songs).map((x, i) => songRow(x, 'srch', i)).join('') + '</div>', more('songs', songs)) : '')
      + (albums.length ? row(t('mlib.albums'), '<div class="mu-g2">' + cap('albums', albums).map(acard).join('') + '</div>', more('albums', albums)) : '')
      + (artists.length ? row(t('mlib.artists'), '<div class="mu-g3">' + cap('artists', artists).map(r => arc(r)).join('') + '</div>', more('artists', artists)) : '');
  }
  function plRow(p) { const al = p.ids.slice(0, 4).map(id => M.album(M.track(id).albumId));
    return '<button class="mu-pl" data-mu="playlist" data-id="' + p.id + '"><div class="col">' + al.map(a => '<i style="' + (a.cover ? M.coverStyle(a) : M.wordmarkStyle(a.title)) + '"></i>').join('') + '</div><div><div class="t">' + esc(p.name) + '</div><div class="s">' + songsN(p.ids.length) + '</div></div></button>'; }
  function playlists(inLib) {
    const newBtn = '<button class="mu-newpl" data-mu="newpl">＋ ' + t('music.new_playlist') + (q.j7 === 'two' ? '<span class="mu-ph2">phase 2</span>' : '') + '</button>';
    if (!MS.playlists.length) return (inLib ? '' : '<div class="row" style="margin-top:6px"><h3>' + t('mnav.playlists') + '</h3></div>') + '<div class="mu-empty" style="margin-top:14px"><b style="display:block;color:var(--ink);margin-bottom:6px">' + t('music.no_playlists') + '</b>' + t('music.no_playlists_hint') + '</div>' + newBtn;
    return (inLib ? '' : '<div class="row" style="margin-top:6px"><h3>' + t('mnav.playlists') + '<span class="more" style="cursor:default">' + MS.playlists.length + '</span></h3></div>') + MS.playlists.map(plRow).join('') + newBtn;
  }
  function render(tab) {
    const a = H.app;
    MS.lastTab = tab;
    if (tab === 'm-home' || tab === 'm-listen') a.innerHTML = home(tab === 'm-listen');
    else if (tab === 'm-library' || tab === 'm-browse') {
      // with no Search tab, Library carries the field: a query replaces the chips' content with results
      if (merged() && !empty()) { a.innerHTML = searchField() + '<div id="muLibBody">' + library() + '</div><div id="muSrchRes"></div><div style="height:24px"></div>'; wireSearch(); }
      else a.innerHTML = library();
    }
    else if (tab === 'm-now') { a.innerHTML = ''; closeAllDet(); if (MS.P) MS.P.openTab(); }
    else if (tab === 'm-albums') a.innerHTML = '<div style="height:6px"></div>' + (empty() ? '<div class="mu-empty">' + t('mhome.empty') + '</div>' : albumsGrid());
    else if (tab === 'm-artists') a.innerHTML = '<div style="height:6px"></div>' + (empty() ? '<div class="mu-empty">' + t('mhome.empty') + '</div>' : artistsGrid());
    else if (tab === 'm-search') { a.innerHTML = search(); const i = $('muSrch'), c = $('muSrchClr'); i.value = MS.srch; c.hidden = !MS.srch;
      i.addEventListener('input', () => { MS.srch = i.value; MS.srchAll = null; c.hidden = !MS.srch; fillSearch(); }); c.addEventListener('click', () => { MS.srch = ''; i.value = ''; c.hidden = true; fillSearch(); }); fillSearch(); }
    else if (tab === 'm-playlists') a.innerHTML = playlists(false);
    else if (tab === 'm-queue') a.innerHTML = MS.P ? MS.P.queuePage() : '';
    else if (tab === 'm-books') a.innerHTML = window.RaviloBooks ? RaviloBooks.shelf(false) : '';
    paintSort(tab);
    if (MS.P) MS.P.paintMini();
  }
  function rerender() { if (H.tab.indexOf('m-') === 0) render(H.tab); }

  /* ---- detail: album · artist · playlist (one title's detail — the bar is hidden, the mini bar stays) ---- */
  const det = document.createElement('div'); det.className = 'mu-det'; det.id = 'muDet'; det.innerHTML = '<div class="mu-dsc" id="muDsc"></div>';
  document.querySelector('.screen').appendChild(det);
  function albumHTML(a) {
    const ts = M.tracksOf(a.id), len = ts.reduce((s, x) => s + x.len, 0), r = artistOf(a), cur = MS.P && MS.P.cur(), pl = MS.P && MS.P.playing();
    CTX['al:' + a.id] = ts.map(x => x.id);
    const more = M.albumsOf(r.id).filter(x => x.id !== a.id);
    const tint = 'background:linear-gradient(180deg,hsl(' + a.hue + ' 42% 20%) 0,var(--bg) 560px)';
    return '<div style="' + tint + ';min-height:100%" class="mu-tint"><div class="mu-dtop"><button class="mu-dback" data-mu="back" aria-label="Back">‹</button>' + cover(a) + '</div>'
      + '<div class="mu-dbody"><div class="mu-dt">' + esc(a.title) + '</div>'
      + '<div class="mu-dby">' + (r.id === 'various' ? esc(r.name) : '<a data-mu="artist" data-id="' + r.id + '">' + esc(r.name) + '</a>') + '</div>'
      + '<div class="mu-dm"><span>' + a.year + '</span><span>·</span><span>' + songsN(ts.length) + '</span><span>·</span><span>' + M.fmtTotal(len) + '</span>' + (a.type !== 'album' ? '<span class="mu-badge">' + t('music.type.' + a.type) + '</span>' : '') + '</div>'
      + '<div class="mu-dacts"><button class="pri" data-mu="playctx" data-ctx="al:' + a.id + '">' + G.play + t('music.play') + '</button><button data-mu="shufctx" data-ctx="al:' + a.id + '">' + G.shuffle + t('music.shuffle') + '</button></div>'
      + '<div class="mu-trs">' + ts.map((x, i) => '<div class="mu-tr' + (cur === x.id ? ' on' : '') + '" data-mu="song" data-id="' + x.id + '" data-ctx="al:' + a.id + '" data-i="' + i + '"><span class="n">' + (cur === x.id ? '<span class="mu-bars' + (pl ? '' : ' paused') + '"><i></i><i></i><i></i></span>' : (i + 1)) + '</span>'
        + '<span class="t">' + esc(x.title) + (x.feat.length ? ' <small>feat. ' + esc(M.artistNames(x.feat)) + '</small>' : '') + (x.artistIds[0] !== a.artistId ? ' <small>' + esc(M.artistNames(x.artistIds)) + '</small>' : '') + '</span>'
        + '<span class="ln">' + (x.lyrics ? '<span class="ly" title="' + t('music.lyrics') + '">' + G.lyr + '</span>' : '') + M.fmtLen(x.len) + '</span><button class="mu-more" data-mu="menu" data-id="' + x.id + '">' + G.more + '</button></div>').join('') + '</div>'
      + (more.length ? '<div class="mu-sec">' + esc(t('music.more_from', { artist: r.name })) + '</div><div class="mu-track">' + more.map(acard).join('') + '</div>' : '')
      + '</div></div>';
  }
  const GROUPS = [['music.albums', a => a.type === 'album' || a.type === 'soundtrack'], ['music.singles', a => a.type === 'single' || a.type === 'ep'], ['music.compilations', a => a.type === 'compilation'], ['music.type.live', a => a.type === 'live']];
  function artistHTML(r) {
    const own = M.albumsOf(r.id), songs = M.tracksBy(r.id).slice().sort((a, b) => b.plays - a.plays), top = songs.slice(0, 5);
    CTX['ar:' + r.id] = songs.map(x => x.id);
    const facts = [r.type === 'Group' ? 'Group' : r.type === 'Person' ? '' : '', r.span].filter(Boolean).join(' · ');
    const vids = M.VIDEOS[r.id] || [];
    return '<div class="mu-ahead"><div class="bgf" style="' + (r.image ? M.coverStyle({ id: r.id + 'bg', hue: r.hue }) : M.wordmarkStyle(r.name)) + '"></div><button class="mu-dback" data-mu="back" aria-label="Back">‹</button>'
      + '<div class="img" style="' + artImg(r) + '">' + (r.image ? '' : esc(M.initials(r.name))) + '</div><div class="mu-dt">' + esc(r.name) + '</div>' + (facts ? '<div class="mu-dm">' + esc(facts) + '</div>' : '') + '</div>'
      + '<div class="mu-dbody">' + (r.bio ? '<div class="mu-bio">' + esc(r.bio) + '</div><span class="mu-biomore" data-mu="bio" data-id="' + r.id + '">' + t('music.more') + '</span>' : '')
      + '<div class="mu-dacts"><button class="pri" data-mu="playctx" data-ctx="ar:' + r.id + '">' + G.play + t('music.play_all') + '</button><button data-mu="shufctx" data-ctx="ar:' + r.id + '">' + G.shuffle + t('music.shuffle') + '</button></div>'
      + GROUPS.map(g => { const l = own.filter(g[1]); return l.length ? '<div class="mu-sec">' + t(g[0]) + '</div><div class="mu-g2" style="padding:0">' + l.map(a => '<div class="mu-acard" data-mu="album" data-id="' + a.id + '">' + cover(a) + '<div class="t">' + esc(a.title) + '</div><div class="s">' + a.year + '</div></div>').join('') + '</div>' : ''; }).join('')
      + (top.length ? '<div class="mu-sec">' + t('music.top_songs') + (songs.length > 5 ? '<span class="more" data-mu="ar-all" data-id="' + r.id + '">' + t('music.see_all') + ' ›</span>' : '') + '</div><div class="mu-list" style="margin:0 -10px">' + (MS.arAll === r.id ? songs : top).map((x, i) => songRow(x, 'ar:' + r.id, i)).join('') + '</div>' : '')
      + (vids.length ? '<div class="mu-sec">' + t('music.videos') + '<span class="mu-ph2">phase 2</span></div><div class="mu-track">' + vids.map(v => '<div class="mu-vt" data-mu="video" data-id="' + esc(v.title) + '"><div class="im" style="' + M.coverStyle({ id: v.title, hue: (r.hue + 90) % 360 }) + '">' + esc(v.title) + '<span class="d">' + v.len + '</span></div><div class="s">' + esc(v.kind) + ' · ' + v.year + '</div></div>').join('') + '</div>' : '')
      + '</div>';
  }
  function playlistHTML(p) {
    CTX['pl:' + p.id] = p.ids.slice();
    return '<div class="mu-ahead" style="padding-top:70px"><button class="mu-dback" data-mu="back">‹</button><div class="mu-dt">' + esc(p.name) + '</div><div class="mu-dm" style="justify-content:center">' + songsN(p.ids.length) + '</div></div>'
      + '<div class="mu-dbody"><div class="mu-dacts"><button class="pri" data-mu="playctx" data-ctx="pl:' + p.id + '">' + G.play + t('music.play') + '</button><button data-mu="shufctx" data-ctx="pl:' + p.id + '">' + G.shuffle + t('music.shuffle') + '</button></div><div class="mu-list" style="margin:14px -10px 0">'
      + p.ids.map((id, i) => songRow(M.track(id), 'pl:' + p.id, i)).join('') + '</div></div>';
  }
  function paintDet() {
    const d = MS.det; if (!d) return;
    const dsc = $('muDsc'); const top = dsc.scrollTop;
    dsc.innerHTML = d.kind === 'album' ? albumHTML(M.album(d.id)) : d.kind === 'artist' ? artistHTML(M.artist(d.id)) : (d.kind === 'book' || d.kind === 'bauthor') && window.RaviloBooks ? RaviloBooks.detHTML(d) : playlistHTML(MS.playlists.find(p => p.id === d.id));
    dsc.scrollTop = d.keep ? top : 0; d.keep = true;
  }
  function openDet(kind, id) { if (MS.det) MS.detStack.push(MS.det); MS.det = { kind, id }; det.classList.add('on'); paintDet(); if (MS.P) MS.P.paintMini(); }
  function closeDet() { MS.det = MS.detStack.pop() || null; if (MS.det) { MS.det.keep = false; paintDet(); } else det.classList.remove('on'); if (MS.P) MS.P.paintMini();
    if (!MS.det && H.tab === 'm-now' && MS.P) MS.P.openTab(); }
  const merged = () => !bar().some(b => b[0] === 'm-search');
  function searchField() { return '<div class="srch"><div class="fld"><svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M20.5 20.5l-4.2-4.2"/></svg><input id="muSrch" type="search" placeholder="' + esc(t('music.search_ph')) + '" autocomplete="off"><button class="clr" id="muSrchClr" type="button" hidden>Clear</button></div></div>'; }
  function wireSearch() { const i = $('muSrch'), c = $('muSrchClr'); i.value = MS.srch; c.hidden = !MS.srch;
    i.addEventListener('input', () => { MS.srch = i.value; MS.srchAll = null; c.hidden = !MS.srch; fillSearch(); });
    c.addEventListener('click', () => { MS.srch = ''; i.value = ''; c.hidden = true; fillSearch(); }); fillSearch(); }
  function closeAllDet() { MS.det = null; MS.detStack = []; det.classList.remove('on'); }

  /* ---- the bottom bar: the same bar with a different set of five ---- */
  const brow = document.querySelector('#bnav .brow'), profileBtn = brow.querySelector('[data-tab="profile"]');
  const videoBtns = [...brow.querySelectorAll('.bn')].filter(b => b !== profileBtn).map(b => b.outerHTML);
  function paintBar() {
    [...brow.querySelectorAll('.bn')].forEach(b => { if (b !== profileBtn) b.remove(); });
    const html = MS.mode === 'music' && MS.granted ? bar().map(x => '<button class="bn" data-tab="' + x[0] + '"><span class="p">' + G[x[2]] + '</span><b>' + t(x[1]) + '</b></button>').join('') : videoBtns.join('');
    profileBtn.insertAdjacentHTML('beforebegin', html);
    H.paintNav();
  }
  /* ---- chrome: ♪ beside the brand (J3), the top-row segment (J1 direction 3) ---- */
  const brand = document.querySelector('.appbar .brand'), appbar = document.querySelector('.appbar');
  // owner 2026-09-28: in music mode the bottom bar is on every page — an album, an artist, a book, Now playing
  function paintBarAlways() {
    const ph = document.querySelector('.phone'), bn = document.getElementById('bnav');
    ph.classList.toggle('mu-on', MS.mode === 'music'); // not 'mu-mode' — that is the Profile mode card's class
    if (bn) ph.style.setProperty('--bnh', bn.offsetHeight + 'px');
  }
  // a tab pressed while an album/artist/book is open goes to that tab (the detail stack closes)
  document.getElementById('bnav').addEventListener('click', e => { if (MS.mode === 'music' && e.target.closest('[data-tab]') && MS.det) { MS.detStack = []; MS.det = null; det.classList.remove('on'); } }, true);
  function paintChrome() {
    paintBarAlways();
    let n = $('muBrandNote'); if (!n) { n = document.createElement('span'); n.id = 'muBrandNote'; n.className = 'mu-brandnote'; n.textContent = '♪'; brand.appendChild(n); }
    n.style.display = MS.mode === 'music' && MS.granted && q.j3 === 'draw' ? '' : 'none';
    let s = $('muTseg');
    if (q.j1 === 'top' && MS.granted) {
      if (!s) { s = document.createElement('div'); s.id = 'muTseg'; s.className = 'mu-tseg'; appbar.insertBefore(s, $('libSlot')); }
      s.innerHTML = '<button data-mu-mode="video" class="' + (MS.mode === 'video' ? 'on' : '') + '">' + (t('mode.video') === 'Films & series' ? 'Video' : t('mode.video').split(' ')[0]) + '</button><button data-mu-mode="music" class="' + (MS.mode === 'music' ? 'on' : '') + '">' + t('mode.music') + '</button>';
    } else if (s) s.remove();
  }
  appbar.addEventListener('click', e => { const b = e.target.closest('[data-mu-mode]'); if (b) setMode(b.dataset.muMode); });
  function firstTab() { return bar()[0][0]; }
  function setMode(m, silent) {
    if (!MS.granted) m = 'video';
    MS.mode = m; try { localStorage.setItem(MKEY, m); } catch (e) {}
    closeAllDet(); paintBar(); paintChrome();
    // owner 2026-09-28: switching from Profile stays on Profile — only the bottom bar changes, so the viewer can
    // switch straight back or pick a tab themselves. (The top-bar switch, elsewhere, still lands on the mode's first tab.)
    if (!silent) {
      H.closeAcct();
      if (H.tab === 'profile') { H.render(); if (window.RaviloHost.phToast) H.phToast(t(m === 'music' ? 'mode.now_music' : 'mode.now_video')); }
      else { H.setTab(m === 'music' ? firstTab() : 'home'); H.render(); }
    }
    if (MS.P) MS.P.paintMini();
  }

  /* ---- Profile page pieces (the host calls these) ---- */
  function profileTop() {
    if (!MS.granted || q.j1 !== 'card') return '';
    return '<div class="mu-mode" role="group" aria-label="' + esc(t('mode.label')) + '"><button data-mu-set="video" class="' + (MS.mode === 'video' ? 'on' : '') + '"><b>' + t('mode.video') + '</b><span>' + t('mode.video_sub') + '</span></button>'
      + '<button data-mu-set="music" class="' + (MS.mode === 'music' ? 'on' : '') + '"><b>' + t('mode.music_books') + ' <span style="font-size:14px">♪</span></b><span>' + bar().map(x => t(x[1])).join(' · ') + '</span></button></div>';
  }
  function profileRow() {
    if (!MS.granted || q.j1 !== 'row') return '';
    const m = MS.mode === 'music';
    return '<button class="pft-row" data-mu-set="' + (m ? 'video' : 'music') + '">' + (m ? G.film : G.note) + '<span>' + t(m ? 'mode.switch_video' : 'mode.switch_music') + '</span>' + G.chev + '</button>';
  }
  // part 2 (§M5): a small Listening group — closes the host's Playback group and opens its own
  function settingsRows() {
    if (!MS.granted) return '';
    const sw = (k, l, sub, on) => '<button class="pft-row" data-mu-lsw="' + k + '"><span>' + l + (sub ? '<div class="rsub">' + sub + '</div>' : '') + '</span><span class="ph-sw' + (on ? ' on' : '') + '"></span></button>';
    const rows = (q.j6 === 'on' ? sw('evenVol', t('music.even_volume'), t('music.even_volume_sub'), MS.evenVol) : '')
      + (q.s5 !== 'none' ? sw('skipSil', t('ab.skip_silence'), t('ab.skip_silence_sub'), MS.skipSil) : '')
      + sw('sleepFade', t('ab.sleep_fade'), t('ab.sleep_fade_sub'), MS.sleepFade !== false);
    return '</div><div class="acct-sec" style="margin-top:22px">' + t('ab.listening') + '</div><div class="pft-grp">' + rows;
  }
  function _oldEven() {
    if (!MS.granted || q.j6 !== 'on') return '';
    return '<button class="pft-row" data-mu-evol="1"><span>' + t('music.even_volume') + '<div class="rsub">' + t('music.even_volume_sub') + '</div></span><span class="ph-sw' + (MS.evenVol ? ' on' : '') + '"></span></button>';
  }
  document.addEventListener('click', e => {
    const s = e.target.closest('[data-mu-set]'); if (s) { e.stopPropagation(); setMode(s.dataset.muSet); return; }
    const ev = e.target.closest('[data-mu-evol]'); if (ev) { MS.evenVol = !MS.evenVol; ev.querySelector('.ph-sw').classList.toggle('on', MS.evenVol); }
    const ls = e.target.closest('[data-mu-lsw]'); if (ls) { const k = ls.dataset.muLsw; MS[k] = k === 'sleepFade' ? MS.sleepFade === false : !MS[k]; ls.querySelector('.ph-sw').classList.toggle('on', k === 'sleepFade' ? MS.sleepFade !== false : !!MS[k]); }
  }, true);

  /* ---- page events ---- */
  function onClick(e) {
    const el = e.target.closest('[data-mu]'); if (!el) return;
    const k = el.dataset.mu, id = el.dataset.id;
    if (k === 'menu') { e.stopPropagation(); MS.P.menu(id); return; }
    if (k === 'album') openDet('album', id);
    else if (k === 'artist') openDet('artist', id);
    else if (k === 'playlist') openDet('playlist', id);
    else if (k === 'back') closeDet();
    else if (k === 'song') MS.P.playCtx(CTX[el.dataset.ctx] || [id], +el.dataset.i || 0, el.dataset.ctx);
    else if (k === 'playctx') MS.P.playCtx(CTX[el.dataset.ctx], 0, el.dataset.ctx);
    else if (k === 'shufctx') MS.P.playCtx(CTX[el.dataset.ctx], 0, el.dataset.ctx, true);
    else if (k === 'mix') MS.P.playCtx(CTX.mix, 0, 'mix', true);
    else if (k === 'chip') { MS.libChip = id; MS.genre = null; rerender(); }
    else if (k === 'genre') { MS.genre = id; rerender(); }
    else if (k === 'genre-back') { MS.genre = null; rerender(); }
    else if (k === 'sort') MS.P.sortSheet();
    else if (k === 'seeall-recent') { MS.libChip = 'songs'; MS.libSort = 3; const lt = bar().find(x => x[0] === 'm-library' || x[0] === 'm-browse'); H.setTab(lt ? lt[0] : 'm-search'); H.render(); }
    else if (k.indexOf('srch-all-') === 0) { MS.srchAll = k.slice(9); fillSearch(); }
    else if (k === 'newpl') MS.P.newPlaylist();
    else if (k === 'open-now') MS.P.openNow();
    else if (k === 'bio') { // owner 2026-09-28: expand in place, no sheet
      const bio = el.previousElementSibling; if (!bio) return;
      const open = !bio.classList.contains('open');
      bio.style.maxHeight = bio.scrollHeight + 'px';
      if (open) { bio.classList.add('open'); } else { bio.style.maxHeight = bio.scrollHeight + 'px'; requestAnimationFrame(() => { bio.classList.remove('open'); bio.style.maxHeight = ''; }); }
      el.textContent = open ? (t('music.less') !== 'music.less' ? t('music.less') : 'Less') : t('music.more');
    }
    else if (k === 'ar-all') { MS.arAll = id; paintDet(); }
    else if (k === 'video') H.openPlayer(id);
    else if (window.RaviloBooks && RaviloBooks.onClick(k, el, e)) {}
  }
  H.app.addEventListener('click', onClick);
  det.addEventListener('click', onClick);

  window.RaviloMusic = {
    MS, handles: tab => typeof tab === 'string' && tab.indexOf('m-') === 0, render, rerender, profileTop, profileRow, settingsRows, setMode, setQ, paintBar, paintChrome,
    openDet, closeDet, closeAllDet, paintDet, firstTab, bar,
    reselect(tab) { if (!this.handles(tab)) return false;
      if ((tab === 'm-library' || tab === 'm-browse') && merged()) { const i = $('muSrch'); if (i) i.focus(); return true; }
      if (tab === 'm-library' || tab === 'm-browse') { const c = CHIPS(); MS.libChip = c[(c.indexOf(MS.libChip) + 1) % c.length]; MS.genre = null; rerender(); return true; }
      if (tab === 'm-search') { const i = $('muSrch'); if (i) i.focus(); return true; }
      return false; },
    onLang() { paintBar(); paintChrome(); rerender(); if (MS.det) paintDet(); if (MS.P) MS.P.repaintAll(); },
    signOut() { setMode('video', true); if (MS.P) MS.P.stop(); },
    onVideoStart() { if (MS.P) MS.P.stop(); },
  };
})();
