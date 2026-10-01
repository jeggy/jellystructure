/* Ravilo Desktop — the maintained desktop mockup (R337 layout · R338 themes · R328/R333 shells).
   One live window: macOS (glass sidebar, traffic lights, capsule bar, the menu bar) or Linux (libadwaita: header bars,
   ☰ primary menu, a docked bar). The fence above the window (platform · size class · theme) is DESIGN ONLY — the app
   reads the platform, the window width and the viewer's theme itself. Compact (< 600) embeds the phone, unchanged.
   Builders + icons: ../desktop-kit.js (window.DK). Data: ../app/music-data.js, ../app/audiobook-data.js, versions.
   localStorage: ravilo-desk (fence + last page per mode). URL: ?os=mac|linux &size=large|expanded|medium|compact &skin= &mode=films|music */
(function () {
  const DK = window.DK, M = window.MUSIC, B = window.BOOKS, V = window.RaviloVersions;
  const { ic, bg, MARK, A } = DK;
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const $ = id => document.getElementById(id);
  const KEY = 'ravilo-desk';
  let saved = {}; try { saved = JSON.parse(localStorage.getItem(KEY) || '{}'); } catch (e) {}
  const S = Object.assign({ os: 'mac', size: 'large', skin: 'aurora', mode: 'films', fpage: 'home', mpage: 'listen', side: true }, saved);
  const U = new URLSearchParams(location.search);
  if (U.get('os')) S.os = /linux|gnome|flatpak/.test(U.get('os')) ? 'linux' : 'mac';
  ['size', 'skin', 'mode'].forEach(k => { if (U.get(k)) S[k] = U.get(k); });
  const save = () => { try { localStorage.setItem(KEY, JSON.stringify(S)); } catch (e) {} };

  const SIZES = { large: [1280, 800, 'Large', '≥ 1200'], expanded: [1000, 720, 'Expanded', '840–1199'], medium: [720, 720, 'Medium', '600–839'], compact: [400, 760, 'Compact', '< 600'] };
  const SKINS = [['aurora', 'Aurora', '#0a0c13', '#7b6ef0'], ['midnight', 'Midnight', '#04101a', '#19d6c6'], ['noir', 'Noir', '#080807', '#f5b542'], ['graphite', 'Graphite', '#1c1c1f', '#4f8ef0'], ['daylight', 'Daylight', '#fbfbfd', '#5b4ee0']];
  if (!SIZES[S.size]) S.size = 'large';
  if (!SKINS.some(x => x[0] === S.skin)) S.skin = 'aurora';

  /* ---- films stand-ins (fictional; the title sweep) ---- */
  const GEN = ['Drama', 'Thriller', 'Documentary', 'Crime', 'Adventure', 'Drama', 'Mystery', 'Romance'];
  const FL = [{ t: 'Big Buck Bunny', img: 'bbb-backdrop-landscape.png', play: 'bbb-backdrop-rodents.png', logo: 'bbb-logo.png', year: 2008, len: '10 min', g: 5, k: 1, genre: 'Animation', kind: 'film', syn: 'A gentle giant rabbit takes on three bullies of the forest in a comedy with no words at all.' }]
    .concat(DK.FILMS.map(([t, g, k], i) => ({ t, g, k, year: 2018 + i % 7, len: (86 + (i * 13) % 52) + ' min', genre: GEN[i], kind: 'film' })));
  const SER = [['Mesterholdet', 4, 2, 10], ['The Lamp Keepers', 1, 1, 8], ['Lundin og vinir', 6, 3, 13], ['Kelp Station', 9, 1, 6], ['North Ferry', 2, 2, 8], ['Tide Clock', 7, 1, 10]]
    .map(([t, g, s, e], i) => ({ t, g, seasons: s, eps: s * e, year: 2016 + i, genre: ['Reality', 'Drama', 'Kids', 'Documentary', 'Crime', 'Mystery'][i], kind: 'series' }));
  const DISC = [['Fog Line', 3], ['The Quiet Year', 8], ['Ninefold', 2], ['Marrow Bay', 5], ['Last Lantern', 10], ['Iron Shore', 1]];
  const item = key => { const [k, i] = key.split(':'); return (k === 's' ? SER : FL)[+i]; };
  const CONT = [['f:0', 58, 'Film · 4 min left'], ['s:0', 30, 'S02E04 · 21 min left'], ['s:1', 72, 'S01E07 · 9 min left']];
  const myList = new Set(['f:0', 's:2', 'f:4']);

  /* ---- runtime ---- */
  const R = { st: { films: [], music: [] }, playing: false, cur: 'salt-on-the-window-1', ctx: 'al:salt-on-the-window', queue: [], qi: 0, pos: 62,
    qOpen: false, film: null, fpPaused: false, fpHide: false, menu: null, dlg: null, srch: '', keepBar: false, requested: new Set(), toast: null };
  R.queue = M.tracksOf('salt-on-the-window').map(t => t.id); R.qi = Math.max(0, R.queue.indexOf(R.cur));
  const stack = () => R.st[S.mode];
  const top = () => stack()[stack().length - 1];
  const page = () => S.mode === 'music' ? S.mpage : S.fpage;

  /* ---- small builders ---- */
  const isMac = () => S.os === 'mac';
  const cs = a => a.cover ? M.coverStyle(a) : M.wordmarkStyle(a.title);
  const arName = a => (M.artist(a.artistId) || {}).name || 'Various artists';
  const chips = (x, n) => V ? V.chips(x, n || 3) : '';
  const EQ = '<span class="eq"><i style="height:6px"></i><i style="height:11px"></i><i style="height:8px"></i></span>';
  const poster = (key) => { const it = item(key); return `<div class="po" data-film="${key}"><div class="a" style="${bg(it.g)}">${it.k ? '<span class="q4k">4K</span>' : ''}<span style="position:relative">${esc(it.t)}</span></div><div class="t">${esc(it.t)}</div><div class="s">${it.kind === 'series' ? it.eps + ' episodes' : it.year + ' · ' + it.len}</div></div>`; };
  const contCard = ([key, p, s]) => { const it = item(key); return `<div class="ls" data-film="${key}"><div class="a" style="${it.img ? `background-image:url(${A}${it.img})` : bg(it.g)}"><span class="pg"><i style="width:${p}%"></i></span></div><div class="t">${esc(it.t)}</div><div class="s">${s}</div></div>`; };
  const albumCard = a => `<div class="al" data-al="${a.id}"><div class="a" style="${cs(a)}">${a.cover ? '' : `<span style="position:relative">${esc(a.title)}</span>`}</div><div class="t">${esc(a.title)}</div><div class="s">${esc(arName(a))}</div></div>`;
  const artistCard = r => `<div class="ar" data-ar="${r.id}"><div class="a" style="${r.image ? M.artistStyle(r) : M.wordmarkStyle(r.name)}">${r.image ? '' : esc(M.initials(r.name))}</div><div class="t">${esc(r.name)}</div><div class="s">${M.albumsOf(r.id).length} album${M.albumsOf(r.id).length === 1 ? '' : 's'}</div></div>`;
  const rh = (h, more) => `<div class="rh"><h4>${h}</h4>${more ? `<span class="more" data-nav="${more}" style="cursor:pointer">See all</span>` : ''}</div>`;
  const albumArtists = () => M.artists.filter(r => M.albumsOf(r.id).length).sort((a, b) => (b.image - a.image) || M.albumsOf(b.id).length - M.albumsOf(a.id).length);
  const RECENT = ['live-at-the-harbour', 'salt-on-the-window', 'glass-birds', 'signal-lost', 'brim', 'stormur', 'undertow', 'kite-weather', 'north-atlantic-songbook', 'last-stop-everybody'].map(M.album).filter(Boolean);
  const recentSongs = () => M.tracks.filter(x => x.last != null).sort((a, b) => a.last - b.last).slice(0, 5);

  /* ---- play contexts ---- */
  function ctxIds(ctx) {
    const [k, id] = ctx.split(':');
    if (k === 'al') return M.tracksOf(id).map(t => t.id);
    if (k === 'ar') return M.tracksBy(id).slice().sort((a, b) => b.plays - a.plays).map(t => t.id);
    if (k === 'songs') return M.tracks.map(t => t.id);
    if (k === 'recent') return recentSongs().map(t => t.id);
    if (k === 'srch') return srchSongs().map(t => t.id);
    return [];
  }
  function ctxName(ctx) {
    const [k, id] = ctx.split(':');
    return k === 'al' ? (M.album(id) || {}).title : k === 'ar' ? (M.artist(id) || {}).name : k === 'songs' ? 'Songs' : k === 'recent' ? 'Recently played' : 'Search';
  }
  function playCtx(ctx, id, shuffle) {
    let ids = ctxIds(ctx); if (!ids.length) return;
    if (shuffle) { ids = ids.slice(); for (let i = ids.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [ids[i], ids[j]] = [ids[j], ids[i]]; } }
    R.queue = ids; R.qi = id ? Math.max(0, ids.indexOf(id)) : 0; R.cur = ids[R.qi]; R.pos = 0; R.playing = true; R.ctx = ctx;
  }
  function next(auto) {
    if (R.qi < R.queue.length - 1) { R.qi++; R.cur = R.queue[R.qi]; R.pos = 0; }
    else { R.pos = 0; if (auto) R.playing = false; }
  }
  function prev() { if (R.pos > 3 || R.qi === 0) R.pos = 0; else { R.qi--; R.cur = R.queue[R.qi]; R.pos = 0; } }

  /* ---- song rows ---- */
  function songRows(list, ctx, wide) {
    return `<div class="trk${wide ? ' songs' : ''}">${wide ? '<div class="hd"><span>#</span><span>Title</span><span>Artist</span><span>Album</span><span>Length</span></div>' : ''}` + list.map((x, i) => {
      const a = M.album(x.albumId), now = x.id === R.cur;
      const other = !wide && a && x.artistIds[0] !== a.artistId ? M.artistNames(x.artistIds) : (x.feat.length ? 'feat. ' + M.artistNames(x.feat) : '');
      return `<div class="tr${now ? ' now' : ''}" data-song="${x.id}" data-ctx="${ctx}"><span class="nr">${now && R.playing ? EQ : wide ? i + 1 : (ctx.startsWith('al:') ? x.n : i + 1)}</span><span class="tt"><span>${esc(x.title)}</span>${chips(x)}</span>`
        + (wide ? `<span class="dim">${esc(M.artistNames(x.artistIds))}</span><span class="dim">${esc(a ? a.title : '')}</span>` : `<span class="ar2">${esc(other)}</span>`)
        + `<span class="du">${M.fmtLen(x.len)}</span></div>`;
    }).join('') + '</div>';
  }

  /* ---- pages: each returns { t: title, over: header over a hero, h: body html } ---- */
  const P = {};
  P.home = () => ({ t: '', over: true, h: `<div class="hero" style="background-image:url(${A}bbb-backdrop-landscape.png)"><div class="hb"><div class="kick">New in Films</div><img class="lg" src="${A}bbb-logo.png" alt="Big Buck Bunny"><div class="meta"><span>2008</span><span>10 min</span><span class="tg">Animation</span><span class="tg">4K</span></div><p class="syn">${esc(FL[0].syn)}</p><div class="btns"><button class="bt pri" data-act="playfilm" data-k="f:0">${ic('play')}Play</button><button class="bt" data-act="mylist" data-k="f:0">${ic('bookmark')}${myList.has('f:0') ? 'On My List' : 'My List'}</button></div></div></div>`
    + `<div class="pad">${rh('Continue watching')}<div class="row">${CONT.map(contCard).join('')}</div>${rh('New in Films', 'films')}<div class="row">${FL.slice(1).map((x, i) => poster('f:' + (i + 1))).join('')}</div>${rh('Series', 'series')}<div class="row">${SER.map((x, i) => poster('s:' + i)).join('')}</div></div>` });
  P.discover = () => ({ t: 'Discover', h: `<div class="pad"><div class="h1">Discover</div><div class="meta-l">Suggested for you from Seerr — not in the library yet</div><div class="grid">${DISC.map(([t, g], i) => `<div class="po" data-act="request" data-i="${i}"><div class="a" style="${bg(g)}"><span style="position:relative">${esc(t)}</span></div><div class="t">${esc(t)}</div><div class="s">${R.requested.has(i) ? '✓ Requested' : 'Request'}</div></div>`).join('')}</div></div>` });
  P.mylist = () => ({ t: 'My List', h: `<div class="pad"><div class="h1">My List</div><div class="meta-l">${myList.size} title${myList.size === 1 ? '' : 's'}</div>${myList.size ? `<div class="grid">${[...myList].map(poster).join('')}</div>` : '<div class="dx-empty"><b>Nothing on My List</b>Add a film or a series from its page.</div>'}</div>` });
  P.films = () => ({ t: 'Films', h: `<div class="pad"><div class="h1">Films</div><div class="meta-l">${FL.length} films</div><div class="grid">${FL.map((x, i) => poster('f:' + i)).join('')}</div></div>` });
  P.series = () => ({ t: 'Series', h: `<div class="pad"><div class="h1">Series</div><div class="meta-l">${SER.length} series</div><div class="grid">${SER.map((x, i) => poster('s:' + i)).join('')}</div></div>` });
  P.film = key => {
    const it = item(key), ser = it.kind === 'series', on = myList.has(key);
    const eps = ser ? `<div class="pad">${rh('Season 1')}<div class="row dx-eps">${Array.from({ length: Math.min(8, it.eps / it.seasons) }, (_, i) => `<div class="ls" data-act="playfilm" data-k="${key}"><div class="a" style="${bg(it.g + i)}">${i < 3 ? '<span class="pg"><i style="width:100%"></i></span>' : ''}</div><div class="t">${i + 1}. Episode ${i + 1}</div><div class="s">S01E${String(i + 1).padStart(2, '0')}${i < 3 ? ' · <b>✓</b>' : ''}</div></div>`).join('')}</div></div>` : '';
    return { t: it.t, over: true, h: `<div class="dx-fd"><div class="hero" style="${it.img ? `background-image:url(${A}${it.img})` : bg(it.g)}"><div class="hb"><div class="kick">${ser ? 'Series' : 'Film'}</div>${it.logo ? `<img class="lg" src="${A}${it.logo}" alt="${esc(it.t)}">` : `<div class="ttl">${esc(it.t)}</div>`}<div class="meta"><span>${it.year}</span><span>${ser ? it.seasons + ' season' + (it.seasons > 1 ? 's' : '') : it.len}</span><span class="tg">${it.genre}</span>${it.k ? '<span class="tg">4K</span>' : ''}</div>${it.syn ? `<p class="syn">${esc(it.syn)}</p>` : ''}<div class="btns"><button class="bt pri" data-act="playfilm" data-k="${key}">${ic('play')}${ser ? 'Play · S01E04' : 'Play'}</button><button class="bt" data-act="mylist" data-k="${key}">${ic('bookmark')}${on ? 'On My List' : 'My List'}</button></div></div></div>${eps}</div>` };
  };
  P.listen = () => {
    const b = B && B.household, bl = b ? b.parts.reduce((s, p) => s + p.len, 0) : 0, bp = 7920;
    const rs = recentSongs();
    return { t: 'Listen', h: `<div class="pad" style="padding-top:4px"><div class="h1">Listen</div>`
      + (b ? `${rh('Continue listening')}<div class="bookc" data-book="${b.id}" style="cursor:pointer"><span class="c" style="${M.wordmarkStyle(b.title)}"></span><div><div class="t">${esc(b.title)}</div><div class="s">${esc(B.authorNames(b.authors))} · ${M.fmtTotal(bl - bp)} left</div></div><span class="ring" style="background:conic-gradient(var(--accent-2) 0 ${Math.round(bp / bl * 100)}%,rgba(var(--fg),.12) ${Math.round(bp / bl * 100)}% 100%)"><i>${ic('play')}</i></span></div>` : '')
      + `${rh('Recently added', 'albums')}<div class="row">${RECENT.slice(0, 8).map(albumCard).join('')}</div>`
      + (rs.length ? `${rh('Recently played', 'songs')}${songRows(rs, 'recent')}` : '')
      + `${rh('Artists you play', 'artists')}<div class="row">${albumArtists().slice(0, 9).map(artistCard).join('')}</div></div>` };
  };
  P.artists = () => ({ t: 'Artists', h: `<div class="pad"><div class="h1">Artists</div><div class="meta-l">${albumArtists().length} artists</div><div class="grid" style="grid-template-columns:repeat(auto-fill,minmax(130px,1fr))">${albumArtists().map(artistCard).join('')}</div></div>` });
  P.albums = () => ({ t: 'Albums', h: `<div class="pad"><div class="h1">Albums</div><div class="meta-l">${M.albums.length} albums</div><div class="grid">${M.albums.map(albumCard).join('')}</div></div>` });
  P.songs = () => ({ t: 'Songs', h: `<div class="pad"><div class="h1">Songs</div><div class="meta-l">${M.tracks.length} songs</div></div>${songRows(M.tracks, 'songs', true)}` });
  P.genres = () => ({ t: 'Genres', h: `<div class="pad"><div class="h1">Genres</div><div class="meta-l">${M.genres().length} genres</div><div class="dx-gl">${M.genres().map(g => `<div data-genre="${esc(g.name)}"><b>${esc(g.name)}</b><span>${g.albums} album${g.albums === 1 ? '' : 's'} · ${g.songs} songs</span></div>`).join('')}</div></div>` });
  P.genre = name => ({ t: name.charAt(0).toUpperCase() + name.slice(1), h: `<div class="pad"><div class="h1" style="text-transform:capitalize">${esc(name)}</div><div class="grid">${M.albums.filter(a => a.genres.some(g => g.name === name)).map(albumCard).join('')}</div></div>` });
  P.playlists = () => ({ t: 'Playlists', h: '<div class="dx-empty"><b>No playlists yet</b>Making playlists comes in a later phase. Play an album, an artist or a song meanwhile.</div>' });
  P.audiobooks = () => ({ t: 'Audiobooks', h: `<div class="pad"><div class="h1">Audiobooks</div><div class="meta-l">1 book</div><div class="grid">${B ? `<div class="al" data-book="${B.household.id}"><div class="a" style="${M.wordmarkStyle(B.household.title)}"><span style="position:relative">${esc(B.household.title)}</span></div><div class="t">${esc(B.household.title)}</div><div class="s">${esc(B.authorNames(B.household.authors))}</div></div>` : ''}</div></div>` });
  P.book = id => {
    const b = B.book(id), len = b.parts.reduce((s, p) => s + p.len, 0), pos = 7920;
    return { t: b.title, h: `<div class="alh"><span class="cv" style="${M.wordmarkStyle(b.title)}"></span><div><div class="k">Audiobook · ${b.year}</div><div class="h1">${esc(b.title)}</div><div class="by">${esc(B.authorNames(b.authors))}</div><div class="mt">${b.parts.length} parts · ${M.fmtTotal(len)} · ${M.fmtTotal(len - pos)} left</div><div class="btns"><button class="bt pri" data-act="book">${ic('play')}Resume</button></div></div></div>`
      + `<div class="trk">${b.chapters.map((c, i) => `<div class="tr${i === 2 ? ' now' : ''}" data-act="book"><span class="nr">${i + 1}</span><span class="tt"><span>${esc(c.title)}</span></span><span class="ar2"></span><span class="du">${M.fmtLen(c.len)}</span></div>`).join('')}</div>` };
  };
  P.album = id => {
    const a = M.album(id), ts = M.tracksOf(id), len = ts.reduce((s, t) => s + t.len, 0), more = M.albumsOf(a.artistId).filter(x => x.id !== id);
    return { t: a.title, h: `<div class="alh"><span class="cv" style="${cs(a)}"></span><div><div class="k">${M.TYPE_LABEL[a.type] || 'Album'} · ${a.year}</div><div class="h1">${esc(a.title)}</div><div class="by"${a.artistId !== 'various' ? ` data-ar="${a.artistId}"` : ''}>${esc(arName(a))}</div><div class="mt">${ts.length} of ${a.total || ts.length} songs · ${M.fmtTotal(len)}${a.genres[0] ? ' · <span style="text-transform:capitalize">' + esc(a.genres[0].name) + '</span>' : ''}</div><div class="btns"><button class="bt pri" data-act="playctx" data-ctx="al:${id}">${ic('play')}Play</button><button class="bt" data-act="shufctx" data-ctx="al:${id}">${ic('shuffle')}Shuffle</button></div></div></div>`
      + songRows(ts, 'al:' + id) + (more.length ? `<div class="pad">${rh('More by ' + esc(arName(a)))}<div class="row">${more.map(albumCard).join('')}</div></div>` : '') };
  };
  P.artist = id => {
    const r = M.artist(id), own = M.albumsOf(id), songs = M.tracksBy(id).slice().sort((a, b) => b.plays - a.plays);
    return { t: r.name, h: `<div class="dx-arh"><span class="img" style="${r.image ? M.artistStyle(r) : M.wordmarkStyle(r.name)}">${r.image ? '' : esc(M.initials(r.name))}</span><div><div class="k">Artist${r.country ? ' · ' + esc(r.country) : ''}</div><div class="h1">${esc(r.name)}</div><div class="mt" style="font-size:12.5px;color:var(--ink-dim);margin:0 0 16px">${own.length} album${own.length === 1 ? '' : 's'} · ${songs.length} songs</div><div class="btns"><button class="bt pri" data-act="playctx" data-ctx="ar:${id}">${ic('play')}Play all</button><button class="bt" data-act="shufctx" data-ctx="ar:${id}">${ic('shuffle')}Shuffle</button></div></div></div>`
      + (r.bio ? `<p class="dx-bio">${esc(r.bio)}</p>` : '') + (songs.length ? `<div class="pad">${rh('Top songs')}</div>${songRows(songs.slice(0, 5), 'ar:' + id)}` : '')
      + `<div class="pad">${rh('Albums')}<div class="grid">${own.map(albumCard).join('')}</div></div>` };
  };
  P.playing = () => {
    const x = M.track(R.cur), a = M.album(x.albumId), ly = M.LYRICS[x.id];
    return { t: 'Playing', h: `<div class="np"><div class="bl" style="${cs(a)}"></div><div><span class="cv" style="display:block;${cs(a)}"></span><h2>${esc(x.title)}</h2><div class="by"><span data-ar="${x.artistIds[0]}">${esc(M.artistNames(x.artistIds))}</span> · <span data-al="${a.id}">${esc(a.title)}</span></div>${chips(x, 9)}`
      + `<div class="seek"><div class="tr"><i id="dxB2" style="width:${R.pos / x.len * 100}%"></i></div><div class="tm"><span id="dxT2">${M.fmtLen(R.pos)}</span><span>${M.fmtLen(x.len)}</span></div></div><div class="tp"><span class="sm" data-act="shufnow">${ic('shuffle')}</span><span data-act="prev" style="cursor:pointer">${ic('prev')}</span><span class="pp" data-act="pp" style="cursor:pointer">${ic(R.playing ? 'pause' : 'play')}</span><span data-act="next" style="cursor:pointer">${ic('next')}</span><span class="sm" data-act="queue" style="cursor:pointer">${ic('queue')}</span></div></div>`
      + (ly ? `<div class="lyr" id="dxLyr">${ly.map(([s, l]) => `<p data-s="${s}">${esc(l)}</p>`).join('')}<p style="height:240px"></p></div>` : '<div class="lyr none">No lyrics for this song.</div>') + '</div>' };
  };
  P.queue = P.playing;
  function srchSongs() { const s = R.srch.trim().toLowerCase(); if (!s) return []; return M.tracks.filter(x => (x.title + ' ' + M.artistNames(x.artistIds)).toLowerCase().indexOf(s) >= 0).slice(0, 8); }
  function srchResults() {
    const s = R.srch.trim().toLowerCase(); if (!s) return `<div class="meta-l">${S.mode === 'music' ? 'Songs, albums and artists' : 'Films and series'}</div>`;
    const hit = v => String(v).toLowerCase().indexOf(s) >= 0;
    if (S.mode !== 'music') { const keys = FL.map((x, i) => ['f:' + i, x]).concat(SER.map((x, i) => ['s:' + i, x])).filter(([, x]) => hit(x.t) || hit(x.genre)).map(k => k[0]);
      return keys.length ? `<div class="grid">${keys.map(poster).join('')}</div>` : `<div class="meta-l">Nothing found for “${esc(R.srch.trim())}”</div>`; }
    const so = srchSongs(), al = M.albums.filter(a => hit(a.title) || hit(arName(a))).slice(0, 8), ar = M.artists.filter(r => hit(r.name) && M.albumsOf(r.id).length).slice(0, 8);
    if (!so.length && !al.length && !ar.length) return `<div class="meta-l">Nothing found for “${esc(R.srch.trim())}”</div>`;
    return (ar.length ? `${rh('Artists')}<div class="row">${ar.map(artistCard).join('')}</div>` : '') + (so.length ? `${rh('Songs')}</div>${songRows(so, 'srch')}<div class="pad">` : '') + (al.length ? `${rh('Albums')}<div class="row">${al.map(albumCard).join('')}</div>` : '');
  }
  P.search = () => ({ t: 'Search', h: `<div class="pad"><div class="dx-srch">${ic('search')}<input id="dxQ" type="search" placeholder="${S.mode === 'music' ? 'Search music' : 'Search films &amp; series'}" value="${esc(R.srch)}" autocomplete="off"></div><div id="dxRes">${srchResults()}</div></div>` });
  P.profile = () => ({ t: 'Profile', h: `<div class="dx-prof"><div class="hd"><span class="av">E</span><b>Eyð</b><span>Admin</span></div>`
    + `<div class="dx-grp"><div data-nav="mylist" data-mode-to="films">${ic('bookmark')}My List<span class="v">${myList.size}</span></div></div>`
    + `<h6>Settings</h6><div class="dx-grp"><div data-act="theme">Theme<span class="v">${SKINS.find(x => x[0] === S.skin)[1]}</span></div><div data-act="noop">App language<span class="v">English</span></div></div>`
    + `<h6>Account</h6><div class="dx-grp"><div data-act="noop">Change password</div><div data-act="signout"><span class="red">Sign out</span></div></div>`
    + `<div class="foot">Ravilo 1.38 · ${isMac() ? 'Mac' : 'Linux'} · signed in to ravilo.home</div></div>` });

  function pageNow() {
    const d = top();
    if (d) return d.t === 'album' ? P.album(d.id) : d.t === 'artist' ? P.artist(d.id) : d.t === 'film' ? P.film(d.id) : d.t === 'genre' ? P.genre(d.id) : P.book(d.id);
    return (P[page()] || P.home)();
  }

  /* ---- chrome ---- */
  const NAV = {
    films: [[null, [['home', 'Home', 'home'], ['compass', 'Discover', 'discover'], ['bookmark', 'My List', 'mylist']]], ['Library', [['film', 'Films', 'films', FL.length], ['tv', 'Series', 'series', SER.length]]]],
    music: [[null, [['phones', 'Listen', 'listen'], ['wave', 'Playing', 'playing'], ['queue', 'Queue', 'q']]], ['Library', [['person', 'Artists', 'artists', albumArtists().length], ['disc', 'Albums', 'albums', M.albums.length], ['note', 'Songs', 'songs', M.tracks.length], ['tag', 'Genres', 'genres', M.genres().length], ['plist', 'Playlists', 'playlists'], ['book', 'Audiobooks', 'audiobooks', B ? 1 : 0]]]],
  };
  const navOn = k => k === 'q' ? R.qOpen : page() === k;
  const navA = ([i, l, k, n], rail) => `<a class="${navOn(k) ? 'on' : ''}" ${k === 'q' ? 'data-act="queue"' : `data-nav="${k}"`} title="${l}">${ic(i)}<span>${rail && l === 'Audiobooks' ? 'Books' : l}</span>${rail ? (k === 'playing' && R.playing ? '<span class="dot"></span>' : '') : k === 'playing' && R.playing ? EQ : n ? `<span class="n">${n}</span>` : ''}</a>`;
  const seg = () => `<div class="seg"><b data-mode="films" class="${S.mode !== 'music' ? 'on' : ''}">Films &amp; series</b><b data-mode="music" class="${S.mode === 'music' ? 'on' : ''}">Music</b></div>`;
  const vseg = () => `<div class="seg v"><b data-mode="films" class="${S.mode !== 'music' ? 'on' : ''}" title="Films &amp; series">${ic('film')}</b><b data-mode="music" class="${S.mode === 'music' ? 'on' : ''}" title="Music">${ic('note')}</b></div>`;
  const LIGHTS = '<div class="lights"><i data-act="closewin" title="Close"></i><i></i><i></i></div>';
  function sideHTML() {
    const nav = '<div class="nav">' + NAV[S.mode].map(([h, items]) => (h ? `<h6>${h}</h6>` : '') + items.map(x => navA(x)).join('')).join('') + '</div>';
    const who = `<div class="who${page() === 'profile' && !top() ? ' on' : ''}" data-nav="profile"><span class="av">E</span><div><div class="nm">Eyð</div><div class="sub">Profile</div></div></div>`;
    const sf = `<div class="sfield" data-nav="search">${ic('search')}<span>${S.mode === 'music' ? 'Search music' : 'Search films &amp; series'}</span></div>`;
    return isMac() ? `<aside class="mside">${LIGHTS}${seg()}${sf}${nav}${who}</aside>`
      : `<aside class="gside"><div class="ghb">${MARK}<span class="tt">Ravilo</span><span class="sp"></span><span class="gb" data-act="menu" title="Main menu">${ic('menu')}</span></div>${seg()}${sf}${nav}${who}</aside>`;
  }
  function railHTML() {
    const nav = '<div class="nav">' + NAV[S.mode].map(([, items], gi) => (gi ? '<hr>' : '') + items.map(x => navA(x, true)).join('')).join('') + '</div>';
    const head = isMac() ? LIGHTS : `<div class="ghb" style="justify-content:center;padding:0"><span class="gb" data-act="menu">${ic('menu')}</span></div>`;
    return `<aside class="${isMac() ? 'mside' : 'gside'} rail">${head}${vseg()}<div class="nav"><a data-nav="search" class="${page() === 'search' ? 'on' : ''}">${ic('search')}<span>Search</span></a></div>${nav}<span class="av" data-nav="profile" style="margin:auto auto 4px;cursor:pointer">E</span></aside>`;
  }
  function header(pg, hasSide) {
    const back = stack().length > 0;
    if (isMac()) return `<div class="mtb${pg.over ? ' over' : ''}">${hasSide ? '' : LIGHTS.replace('class="lights"', 'class="lights" style="padding:0 6px 0 2px"') + `<span class="gl" data-act="side" title="Show sidebar">${ic('side')}</span>`}<span class="gl two"><span data-act="back" class="${back ? '' : 'off'}" style="display:flex">${ic('back')}</span>${ic('fwd', 'dim')}</span>${pg.t && !pg.over ? `<span class="tt">${esc(pg.t)}</span>` : ''}<span class="sp"></span><span class="gl" data-act="cast" title="Play on…">${ic('cast')}</span></div>`;
    return `<div class="ghb center">${hasSide ? '' : `<span class="gb" data-act="side" title="Show sidebar">${ic('side')}</span>`}${back ? `<span class="gb" data-act="back" title="Back">${ic('back')}</span>` : ''}${pg.t ? `<span class="tt">${esc(pg.t)}</span>` : ''}<span class="sp"></span><span class="gb" data-act="cast" title="Play on…">${ic('cast')}</span><span class="gx" data-act="closewin" title="Close">${ic('x')}</span></div>`;
  }
  function barHTML() {
    const x = M.track(R.cur), a = M.album(x.albumId), cmp = S.size === 'medium' || (S.size === 'expanded' && R.qOpen), pct = R.pos / x.len * 100;
    const seek = `<div class="pb-seek"><span id="dxT1">${M.fmtLen(R.pos)}</span><span class="tr"><i id="dxB1" style="width:${pct}%"></i></span><span>${M.fmtLen(x.len)}</span></div>`;
    const right = `<div class="pb-r">${cmp ? '' : `<span class="b" data-act="toplaying" title="Show lyrics">${ic('lyr')}</span>`}<span class="b${R.qOpen ? ' on' : ''}" data-act="queue" title="${R.qOpen ? 'Close queue' : 'Show queue'}">${ic('queue')}</span><span class="b" data-act="cast" title="Play on…">${ic('cast')}</span>${cmp ? '' : ic('vol') + '<span class="vol"><i></i></span>'}</div>`;
    const ctl = `<div class="pb-ctl">${!isMac() && !cmp ? `<span class="b" data-act="shufnow">${ic('shuffle')}</span>` : ''}<span class="b" data-act="prev">${ic('prev')}</span><span class="pp" data-act="pp">${ic(R.playing ? 'pause' : 'play')}</span><span class="b" data-act="next">${ic('next')}</span></div>`;
    const art = `<span class="pb-art" style="${cs(a)}" data-act="toplaying"></span><div class="pb-t" data-act="toplaying"><div class="t">${esc(x.title)}</div><div class="s">${esc(M.artistNames(x.artistIds))}</div></div>`;
    return isMac() ? `<div class="cap-bar${cmp ? ' cmp' : ''}">${art}${ctl}${seek}${right}</div>`
      : `<div class="gbar${cmp ? ' cmp' : ''}"><span class="ln"><i id="dxB0" style="width:${pct}%"></i></span>${art}${ctl}${seek}${right}</div>`;
  }
  function queueHTML(over) {
    const nx = R.queue.slice(R.qi + 1), x = M.track(R.cur), a = M.album(x.albumId);
    return `<div class="qp${over ? ' ov' : ''}"><h5>Now playing</h5><div class="qr now" data-act="toplaying"><span class="c" style="${cs(a)}"></span><div class="b"><div class="t">${esc(x.title)}</div><div class="s">${esc(M.artistNames(x.artistIds))}</div></div></div>`
      + `<h5>Next · from ${esc(ctxName(R.ctx))}</h5>` + (nx.length ? nx.map((id, i) => { const t = M.track(id), al = M.album(t.albumId); return `<div class="qr" data-q="${R.qi + 1 + i}"><span class="c" style="${cs(al)}"></span><div class="b"><div class="t">${esc(t.title)}</div><div class="s">${esc(M.artistNames(t.artistIds))} · ${M.fmtLen(t.len)}</div></div><span class="x" data-qrm="${R.qi + 1 + i}" title="Remove">${ic('x')}</span></div>`; }).join('') : '<div class="emp">Nothing after this song.</div>') + '</div>';
  }
  function filmHTML() {
    const it = item(R.film), ser = it.kind === 'series';
    const ctl = isMac() ? `${LIGHTS.replace('class="lights"', 'class="lights" style="padding:0 6px 0 4px"')}<span class="gl" data-act="closefilm" title="Back">${ic('back')}</span>` : `<span class="gb bg" data-act="closefilm" title="Back">${ic('back')}</span>`;
    return `<div class="fp${R.fpHide ? ' hide' : ''}" data-act="fpchrome" style="${it.play ? `background-image:url(${A}${it.play})` : bg(it.g)}"><div class="top">${ctl}${it.logo ? `<img src="${A}${it.logo}" alt="">` : `<span style="margin-left:auto;margin-right:10px;font-family:'Space Grotesk',sans-serif;font-weight:700;font-size:18px">${esc(it.t)}</span>`}${isMac() ? '' : `<span class="gx" data-act="closefilm" title="Close">${ic('x')}</span>`}</div>`
      + `<div class="bot"><div class="ttl">${ser ? 'S01E04 · Episode 4' : esc(it.t)}</div><div class="sk"><i></i><u></u></div><div class="ct"><span class="pp" data-act="fpp">${ic(R.fpPaused ? 'play' : 'pause')}</span>${ic('r10')}${ic('f10')}<span>${it.logo ? '3:06 / 9:56' : '0:12 / ' + (ser ? '42:00' : it.len.replace(' min', ':00'))}</span><span class="sp"></span>${ic('subs')}<span data-act="cast" style="display:flex">${ic('cast')}</span>${ic('vol')}<span data-act="full" style="display:flex" title="Full screen">${ic('full')}</span></div></div></div>`;
  }
  function menuHTML() {
    if (R.menu !== 'gn') return '';
    const k = S.size === 'medium' ? 'left:10px' : 'left:150px';
    return `<div class="gpop" style="${k};top:44px"><div class="mi hd">Eyð</div><div class="mi" data-mi="films">Films &amp; series<span class="k">Ctrl+1</span></div><div class="mi" data-mi="music">Music<span class="k">Ctrl+2</span></div><hr><div class="mi" data-mi="settings">Settings<span class="k">Ctrl+,</span></div><div class="mi" data-mi="keys">Keyboard Shortcuts<span class="k">Ctrl+?</span></div><div class="mi" data-mi="about">About Ravilo</div></div>`;
  }
  function dlgHTML() {
    if (R.dlg !== 'signout') return '';
    return `<div class="dx-dlg" data-act="dlgx"><div><h4>Sign out of Ravilo?</h4><p>Sign in again with your name and password.</p><div class="bs"><span data-act="dlgx">Cancel</span><span class="red" data-act="dlgok">Sign out</span></div></div></div>`;
  }
  const showBar = () => !R.film && page() !== 'playing' && !(top() && 0) && (S.mode === 'music' || R.playing || R.keepBar);

  function winHTML() {
    const [w, h] = SIZES[S.size], cls = isMac() ? 'mac' : 'gn', lt = S.skin === 'daylight' ? ' lt' : '';
    if (S.size === 'compact') return `<div class="win cwin ${cls} dx-win${lt}" data-skin="${S.skin}" style="height:${h}px"><div class="cstrip${isMac() ? '' : ' gnh'}">${isMac() ? '<div class="lights"><i></i><i></i><i></i></div>' : ''}<span class="tag">COMPACT · THE PHONE, UNCHANGED</span>${isMac() ? '' : `<span class="gx">${ic('x')}</span>`}</div><iframe title="Ravilo — compact window" src="Ravilo%20Mobile.html?frame=window&amp;skin=${S.skin}&amp;mode=${S.mode === 'music' ? 'music' : 'video'}"></iframe></div>`;
    const toast = R.toast ? `<div class="dx-toast">${esc(R.toast)}</div>` : '';
    if (R.film) return `<div class="win ${cls} noside dx-win${lt}" data-skin="${S.skin}" style="width:${w}px;height:${h}px"><div class="main">${filmHTML()}</div>${toast}</div>`;
    const rail = S.size === 'medium', side = rail ? railHTML() : S.side ? sideHTML() : '';
    const pg = pageNow(), bar = showBar() ? barHTML() : '';
    const qpush = R.qOpen && S.size === 'large', qover = R.qOpen && !qpush;
    const sz = S.size === 'medium' ? ' med' : S.size === 'expanded' ? ' exp' : '';
    return `<div class="win ${cls} dx-win${lt}${sz}${qpush ? ' qpush' : ''}${qover ? ' qover' : ''}${side ? '' : ' noside'}" data-skin="${S.skin}" style="width:${w}px;height:${h}px">${side}`
      + `<div class="main">${header(pg, !!side)}<div class="body" id="dxBody">${pg.h}</div>${isMac() ? bar : ''}${R.qOpen ? queueHTML(qover) : ''}</div>${isMac() ? '' : bar}${menuHTML()}${dlgHTML()}${toast}</div>`;
  }
  function menubarHTML() {
    if (!isMac()) return '';
    const [w] = SIZES[S.size];
    const items = { view: [['films', 'Films & Series', '⌘1', S.mode === 'films'], ['music', 'Music', '⌘2', S.mode === 'music'], 0, ['side', S.side ? 'Hide Sidebar' : 'Show Sidebar', '⌃⌘S'], ['queue', R.qOpen ? 'Hide Queue' : 'Show Queue', '⌥⌘U'], ['toplaying', 'Show Lyrics', '⌥⌘L'], 0, ['full', 'Enter Full Screen', '⌃⌘F']],
      playback: [['pp', R.playing ? 'Pause' : 'Play', 'Space'], ['next', 'Next', '⌘→'], ['prev', 'Previous', '⌘←']] };
    const m = R.menu && items[R.menu] ? `<div class="pmenu" style="left:${R.menu === 'view' ? 150 : 196}px">${items[R.menu].map(x => x ? `<div class="mi" data-mi="${x[0]}"><span class="ck">${x[3] ? '✓' : ''}</span>${esc(x[1])}<span class="k">${x[2]}</span></div>` : '<hr>').join('')}</div>` : '';
    return `<div class="dx-menubar" style="width:${w}px"><span>&#63743;</span><span class="b">Ravilo</span><span>File</span><span>Edit</span><span data-mb="view" class="${R.menu === 'view' ? 'on' : ''}">View</span><span data-mb="playback" class="${R.menu === 'playback' ? 'on' : ''}">Playback</span><span>Window</span><span>Help</span>${m}</div>`;
  }
  function fenceHTML() {
    const b = (k, v, l, sub) => `<button data-fs="${k}:${v}" class="${S[k] === v ? 'on' : ''}">${l}${sub ? `<small>${sub}</small>` : ''}</button>`;
    return `<span class="tag">Design only · not in the app</span>`
      + `<div class="grp"><span>Platform</span><div class="dx-seg">${b('os', 'mac', 'macOS')}${b('os', 'linux', 'Linux')}</div></div>`
      + `<div class="grp"><span>Window</span><div class="dx-seg">${Object.keys(SIZES).map(k => b('size', k, SIZES[k][2], SIZES[k][3])).join('')}</div></div>`
      + `<div class="grp"><span>Theme</span><div class="dx-sw">${SKINS.map(([k, l, c1, c2]) => `<button data-fs="skin:${k}" class="${S.skin === k ? 'on' : ''}" title="${l}" style="background:linear-gradient(135deg,${c1} 50%,${c2} 50%)"></button>`).join('')}</div></div>`
      + `<span class="note2">${isMac() ? 'macOS 26 · ⌘1 / ⌘2 switch' : 'GNOME (libadwaita) · Ctrl+1 / Ctrl+2 switch'} · Space plays</span>`;
  }

  /* ---- render ---- */
  let lastKey = '', toastT = null;
  function render() {
    const body = $('dxBody'), key = S.mode + '|' + page() + '|' + JSON.stringify(top() || null);
    const st = body && key === lastKey ? body.scrollTop : 0;
    $('dxFence').innerHTML = fenceHTML();
    $('dxDesk').innerHTML = menubarHTML() + winHTML();
    const nb = $('dxBody'); if (nb && st) nb.scrollTop = st;
    lastKey = key; fit(); paintLyrics(true);
  }
  function fit() {
    const f = $('dxFence'), st = $('dxStage'), d = $('dxDesk'); if (!f || !d) return;
    st.style.top = f.offsetHeight + 'px';
    d.style.transform = 'none';
    const sc = Math.min(1, (st.clientWidth - 40) / d.offsetWidth, (st.clientHeight - 40) / d.offsetHeight);
    d.style.transform = `scale(${sc})`;
  }
  function paintTime() {
    const x = M.track(R.cur), p = R.pos / x.len * 100;
    ['dxB0', 'dxB1', 'dxB2'].forEach(id => { const e = $(id); if (e) e.style.width = p + '%'; });
    ['dxT1', 'dxT2'].forEach(id => { const e = $(id); if (e) e.textContent = M.fmtLen(R.pos); });
    paintLyrics();
  }
  function paintLyrics(jump) {
    const l = $('dxLyr'); if (!l) return;
    const ps = [...l.querySelectorAll('p[data-s]')]; let now = -1;
    ps.forEach((p, i) => { if (+p.dataset.s <= R.pos) now = i; });
    ps.forEach((p, i) => p.classList.toggle('now', i === now));
    if (now >= 0) { const tgt = Math.max(0, ps[now].offsetTop - 140); if (jump) l.scrollTop = tgt; else l.scrollTo({ top: tgt, behavior: 'smooth' }); }
  }
  function toast(m) { R.toast = m; clearTimeout(toastT); toastT = setTimeout(() => { R.toast = null; const t = document.querySelector('.dx-toast'); if (t) t.remove(); }, 2600); render(); }
  setInterval(() => {
    if (!R.playing) return;
    R.pos++;
    if (R.pos >= M.track(R.cur).len) { next(true); render(); return; }
    paintTime();
  }, 1000);

  /* ---- actions ---- */
  function setMode(m) { if (m !== 'films' && m !== 'music') return; S.mode = m; R.keepBar = false; R.menu = null; if (S.size !== 'large') R.qOpen = false; save(); render(); }
  function go(k) { if (S.mode === 'music') S.mpage = k; else S.fpage = k; stack().length = 0; R.keepBar = false; R.menu = null; save(); render(); if (k === 'search') { const q = $('dxQ'); if (q) { q.focus(); q.setSelectionRange(q.value.length, q.value.length); } } }
  function push(t, id) { stack().push({ t, id }); R.keepBar = false; render(); const b = $('dxBody'); if (b) b.scrollTop = 0; }
  function act(a, el) {
    switch (a) {
      case 'back': stack().pop(); R.keepBar = false; render(); break;
      case 'side': S.side = !S.side; save(); render(); break;
      case 'menu': R.menu = R.menu === 'gn' ? null : 'gn'; render(); break;
      case 'queue': if (S.size === 'compact') break; R.qOpen = !R.qOpen; render(); break;
      case 'pp': R.playing = !R.playing; if (!R.playing && S.mode === 'films') R.keepBar = true; render(); break;
      case 'next': next(); R.playing = true; render(); break;
      case 'prev': prev(); render(); break;
      case 'shufnow': playCtx(R.ctx, null, true); toast('Shuffled · ' + ctxName(R.ctx)); break;
      case 'toplaying': S.mode = 'music'; S.mpage = 'playing'; stack().length = 0; save(); render(); break;
      case 'playctx': playCtx(el.dataset.ctx); render(); break;
      case 'shufctx': playCtx(el.dataset.ctx, null, true); render(); break;
      case 'playfilm': R.film = el.dataset.k; R.fpPaused = false; R.fpHide = false; if (R.playing) { R.playing = false; R.toast = 'Music paused'; clearTimeout(toastT); toastT = setTimeout(() => { R.toast = null; render(); }, 2200); } render(); break;
      case 'closefilm': R.film = null; render(); break;
      case 'fpp': R.fpPaused = !R.fpPaused; render(); break;
      case 'fpchrome': R.fpHide = !R.fpHide; render(); break;
      case 'full': toast(isMac() ? 'Full screen — the Mac’s own (⌃⌘F) · Esc leaves' : 'Full screen — F11 · Esc leaves'); break;
      case 'mylist': { const k = el.dataset.k; myList.has(k) ? myList.delete(k) : myList.add(k); toast(myList.has(k) ? 'Added to My List' : 'Removed from My List'); break; }
      case 'request': { const i = +el.dataset.i; if (!R.requested.has(i)) { R.requested.add(i); toast('Requested · ' + DISC[i][0]); } break; }
      case 'cast': toast('Play on… — the TVs and speakers sheet (R330)'); break;
      case 'closewin': toast(R.playing ? (isMac() ? 'The window closes · music keeps playing (Dock)' : 'The window closes · music keeps playing (Background Apps)') : 'The window closes'); break;
      case 'theme': { const i = SKINS.findIndex(x => x[0] === S.skin); S.skin = SKINS[(i + 1) % SKINS.length][0]; save(); render(); break; }
      case 'signout': R.dlg = 'signout'; render(); break;
      case 'dlgx': if (el.classList.contains('dx-dlg') || el.tagName === 'SPAN') { R.dlg = null; render(); } break;
      case 'dlgok': R.dlg = null; toast('Signed out — back to the sign-in screen'); break;
      case 'book': toast('The book plays in the book player — R323, as on the phone'); break;
      case 'noop': break;
    }
  }
  function menuItem(k) {
    R.menu = null;
    if (k === 'films' || k === 'music') return setMode(k);
    if (k === 'settings') return toast(isMac() ? 'Settings (⌘,) — drawn in Desktop - D1 · T·e' : 'Preferences — drawn in Desktop - D1 · T·h');
    if (k === 'keys') return toast('Keyboard Shortcuts — the R337 table');
    if (k === 'about') return toast('Ravilo 1.38 · net.jebster.Ravilo');
    act(k, document.body);
  }

  document.addEventListener('click', e => {
    const fs = e.target.closest('[data-fs]'); if (fs) { const [k, v] = fs.dataset.fs.split(':'); S[k] = v; R.menu = null; save(); render(); return; }
    const mb = e.target.closest('[data-mb]'); if (mb) { R.menu = R.menu === mb.dataset.mb ? null : mb.dataset.mb; render(); return; }
    const mi = e.target.closest('[data-mi]'); if (mi) { menuItem(mi.dataset.mi); return; }
    if (!e.target.closest('#dxDesk')) { if (R.menu) { R.menu = null; render(); } return; }
    if (R.menu && !e.target.closest('.gpop,.pmenu,[data-act="menu"]')) { R.menu = null; render(); return; }
    // FR-R337-8: the overlay queue (< 1200) closes on a click outside it
    if (R.qOpen && S.size !== 'large' && !e.target.closest('.qp,[data-act="queue"]')) { R.qOpen = false; render(); return; }
    const md = e.target.closest('[data-mode]'); if (md) return setMode(md.dataset.mode);
    const rm = e.target.closest('[data-qrm]'); if (rm) { e.stopPropagation(); R.queue.splice(+rm.dataset.qrm, 1); render(); return; }
    const ac = e.target.closest('[data-act]'); if (ac) return act(ac.dataset.act, ac);
    const nv = e.target.closest('[data-nav]'); if (nv) { if (nv.dataset.modeTo) S.mode = nv.dataset.modeTo; return go(nv.dataset.nav); }
    const fl = e.target.closest('[data-film]'); if (fl) return push('film', fl.dataset.film);
    const al = e.target.closest('[data-al]'); if (al) return push('album', al.dataset.al);
    const ar = e.target.closest('[data-ar]'); if (ar) return push('artist', ar.dataset.ar);
    const gr = e.target.closest('[data-genre]'); if (gr) return push('genre', gr.dataset.genre);
    const bk = e.target.closest('[data-book]'); if (bk) return push('book', bk.dataset.book);
    const sg = e.target.closest('[data-song]'); if (sg) { playCtx(sg.dataset.ctx, sg.dataset.song); render(); return; }
    const q = e.target.closest('[data-q]'); if (q) { R.qi = +q.dataset.q; R.cur = R.queue[R.qi]; R.pos = 0; R.playing = true; render(); return; }
  });
  document.addEventListener('input', e => { if (e.target.id !== 'dxQ') return; R.srch = e.target.value; const r = $('dxRes'); if (r) r.innerHTML = srchResults(); });
  document.addEventListener('keydown', e => {
    const typing = e.target.tagName === 'INPUT';
    const mod = e.metaKey || e.ctrlKey;
    if (mod && (e.key === '1' || e.key === '2')) { e.preventDefault(); return setMode(e.key === '1' ? 'films' : 'music'); }
    if (e.key === 'Escape') { if (R.menu || R.dlg) { R.menu = null; R.dlg = null; return render(); } if (R.film) { R.film = null; return render(); } if (R.qOpen) { R.qOpen = false; return render(); } return; }
    if (typing) return;
    if (e.key === ' ' && !R.film && S.size !== 'compact') { e.preventDefault(); act('pp'); return; }
    if (e.key === ' ' && R.film) { e.preventDefault(); act('fpp'); return; }
    if ((e.ctrlKey && e.metaKey && e.key.toLowerCase() === 's') || e.key === 'F9') { e.preventDefault(); act('side'); return; }
    if (e.altKey && e.metaKey && e.code === 'KeyU') { e.preventDefault(); act('queue'); }
  });
  window.addEventListener('resize', fit);

  document.body.insertAdjacentHTML('afterbegin', '<div class="dx-fence" id="dxFence"></div><div class="dx-stage" id="dxStage"><div class="dx-desk" id="dxDesk"></div></div>');
  render();
  if (document.fonts) document.fonts.ready.then(fit);
})();
