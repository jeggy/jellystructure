/* Music editions & duplicates — admin (brief specs/design-brief-music-editions-and-duplicates-2026-10-03.md;
   owner 2026-10-04: D1 A · Q1 yes · Q2 yes · Q3 yes, B-sides play with "Play album + extras" · Q4 no number ·
   Q5 the better file first, wherever it is from · Q6 Bonus after the version chips, no hue · Q7 folded).
   window.Editions. Load after music-data.js and versions.js. localStorage: js-ed-off (official pick per album),
   js-ed-home (a single moved by you), js-ed-apart / js-ed-join (copies split / joined by you), js-ed-sugg (answers),
   js-ed-every (Show every copy). */
(function () {
  const M = window.MUSIC; if (!M) return;
  const esc = s => (s == null ? '' : String(s)).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const L = s => { const [m, x] = s.split(':'); return +m * 60 + +x; };
  const LS = (k, d) => { try { const v = JSON.parse(localStorage.getItem(k)); return v == null ? d : v; } catch (e) { return d; } };
  const save = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} };

  /* ---- stand-ins (the brief's; numbers are the household's) ---- */
  function album(id, title, year, type, total, codec, kbps, o) {
    o = o || {}; let al = M.byAlbum[id];
    if (!al) {
      al = { id, title, artistId: o.artist || 'harbour-lights', year, type, total, match: 'matched', cover: true, codec, kbps, khz: o.khz || 44.1, genres: [{ name: 'indie folk', votes: 6 }], mbid: M.mbid('rg' + id), relMbid: M.mbid('rel' + id),
        hue: o.hue != null ? o.hue : M.hash(title) % 360, label: 'Fjordlight Records', country: 'NO', added: '2026-09-23', gain: -9.6, order: M.albums.length, trackIds: [] };
      M.albums.push(al); M.byAlbum[id] = al;
    } else if (o.force) Object.assign(al, { codec, kbps, khz: o.khz || 44.1 });
    return al;
  }
  function track(albumId, n, title, len, x) {
    x = x || {}; const al = M.byAlbum[albumId]; if (!al) return null;
    const id = albumId + '-' + n; let t = M.byTrack[id];
    if (!t) {
      t = { id, albumId, n, artistIds: x.by || [al.artistId], feat: x.feat || [], lyrics: x.ly || null, rec: 'ok', gain: -(8.7 + (M.hash(id) % 19) / 10), plays: 0, last: null };
      M.tracks.push(t); M.byTrack[id] = t; al.trackIds.push(id); al.trackIds.sort((a, b) => M.byTrack[a].n - M.byTrack[b].n);
    }
    Object.assign(t, { title, len: L(len), codec: al.codec, kbps: al.kbps, khz: al.khz || 44.1 });
    if (x.ex) t.ex = x.ex;
    return t;
  }
  album('kite-weather', 'Kite Weather', 2006, 'album', 11, 'FLAC', '24-bit', { force: true, khz: 96 });
  [['Kite Weather', '3:36'], ['Northern Line', '3:58'], ['Fog Bank', '4:12'], ['String and Paper', '4:40'], ['Salt on the Window', '4:05'], ['Low Tide', '5:02'], ['Weathervane Hymn', '4:21'], ['Tidewater', '4:48'], ['Gull Year', '3:55'], ['The Slipway', '4:30'], ['Last Ferry Out', '5:13']]
    .forEach((x, i) => track('kite-weather', i + 1, x[0], x[1]));
  track('kite-weather', 12, 'Lantern Swing', '3:44', { ex: 'the Japanese CD, 2006' });
  album('signal-found', 'Signal Found', 2003, 'album', 14, 'MP3', 320, { force: true });
  [['Signal Found', '3:48'], ['Lighthouse Code', '4:10'], ['Ninety Miles of Static', '5:02'], ['Shortwave Hearts', '3:37'], ['The Pilot Boat', '4:21'], ['Weather Report', '3:29'], ['Morning Watch', '4:44'], ['Coastal Station', '3:58'], ['Antenna Song', '4:06'], ['Ice on the Mast', '4:52'], ['Gale Warning', '3:41'], ['Cold Front', '4:18'], ['Dead Reckoning', '5:11'], ['All Ships', '6:02']]
    .forEach((x, i) => track('signal-found', i + 1, x[0], x[1]));
  track('signal-found', 15, 'Morse Code Lullaby', '3:26', { ex: 'the Japanese CD, 2003' });
  [['Signal Found (live, 2004)', '4:02'], ['Lighthouse Code (live, 2004)', '4:31'], ['Shortwave Hearts (live, 2004)', '3:55'], ['The Pilot Boat (live, 2004)', '4:40'], ['Gale Warning (live, 2004)', '4:03'], ['All Ships (live, 2004)', '7:12'],
    ['Signal Found (demo)', '3:31'], ['Antenna Song (demo)', '3:50'], ['Cold Front (demo)', '4:02'], ['Weather Report (demo)', '3:14'], ['Four-track sketch (demo)', '2:41']]
    .forEach((x, i) => track('signal-found', 16 + i, x[0], x[1], { ex: 'Signal Found 20th Anniversary, 2023' }));
  // the five singles from Kite Weather (13 B-sides) and one from Signal Found; track 1 is the A-side
  const SG = [['kw-northern-line', 'Northern Line', 2006, [['Northern Line', '3:58'], ['Night Bus to Ålesund', '3:41'], ['Northern Line (acoustic)', '3:50']]],
    ['kw-fog-bank', 'Fog Bank', 2006, [['Fog Bank', '4:11'], ['Harbour Wall', '4:02'], ['Fog Bank (demo)', '4:31']]],
    ['kw-salt', 'Salt on the Window', 2007, [['Salt on the Window', '4:05'], ['Paper Moon Tide', '3:33'], ['Kettle Song', '2:58'], ['Salt on the Window (live in Bergen)', '4:26']]],
    ['kw-low-tide', 'Low Tide', 2007, [['Low Tide', '5:02'], ['Undertow', '4:15'], ['Low Tide (radio session)', '4:49']]],
    ['kw-tidewater', 'Tidewater', 2007, [['Tidewater', '4:48'], ['Flood Year', '3:39'], ['Tidewater (remix)', '5:40'], ['Breakwater', '4:04'], ['Kite Weather (instrumental)', '3:34', { ly: 'synced' }]]],
    ['sf-shortwave', 'Shortwave Hearts', 2004, [['Shortwave Hearts', '3:37'], ['Shortwave Hearts (Lighthouse Keepers remix) (extended)', '7:12', { feat: ['lighthouse'] }]], { codec: 'FLAC', kbps: '16-bit' }],
    ['hl-small-hours-radio', 'Small Hours Radio', 2009, [['Small Hours Radio', '3:20'], ['Dial Tone', '2:55']]]];
  SG.forEach(s => { const o = s[4] || {}; album(s[0], s[1], s[2], 'single', s[3].length, o.codec || 'MP3', o.kbps || 320); s[3].forEach((x, i) => track(s[0], i + 1, x[0], x[1], x[2])); });
  // copies on the box set and a compilation (the box set is versions.js's; made here if that file isn't loaded)
  album('tide-tables', 'Tide Tables 1999–2012', 2012, 'compilation', 48, 'MP3', 320);
  album('nordic-nights-2', 'Nordic Nights Vol. 2', 2012, 'compilation', 20, 'MP3', 256, { artist: 'various' });
  [[12, 'Northern Line', '3:58'], [13, 'Low Tide', '5:02'], [14, 'Signal Found', '3:48'], [15, 'Gull Year', '3:52'], [16, 'Antenna Song', '4:01']].forEach(x => track('tide-tables', x[0], x[1], x[2]));
  track('nordic-nights-2', 7, 'Northern Line', '3:58', { by: ['harbour-lights'] });
  const ord = id => (M.byAlbum[id] || { order: 999 }).order;
  M.tracks.sort((a, b) => ord(a.albumId) - ord(b.albumId) || a.n - b.n);

  /* ---- the official album ---- */
  const ED = {
    'kite-weather': { edition: 'Japanese edition', k: 14, m: 20, held: 'JP · 2006 · Fjordlight · Digital Media · 12 tracks · 24 bit / 96 kHz',
      P: [{ c: 'NO', d: '2006-03-06', l: 'Fjordlight', f: 'CD', n: 11, auto: true }, { c: 'JP', d: '2006-08-23', l: 'Fjordlight', f: 'CD', n: 12, held: true, name: 'the Japanese CD', adds: ['kite-weather-12'] },
        { c: 'XE', d: '2006-03-20', l: 'Fjordlight', f: 'CD', n: 11 }, { c: 'XW', d: '2006-03-06', l: 'Fjordlight', f: 'Digital Media', n: 11 }, { c: 'US', d: '2007-01-16', l: 'Grey Pier', f: 'CD', n: 11 },
        { c: 'XW', d: '2016-05-13', l: 'Fjordlight', f: '2×CD · 10th Anniversary', n: 24, name: 'the 10th Anniversary edition', more: 13 }], rest: 14 },
    'signal-found': { edition: 'Signal Found 20th Anniversary', k: 9, m: 12, held: 'XW · 2023 · Northern Wire · Digital Media · 26 tracks',
      P: [{ c: 'NO', d: '2003-02-17', l: 'Northern Wire', f: 'CD', n: 14, auto: true }, { c: 'JP', d: '2003-06-25', l: 'Northern Wire', f: 'CD', n: 15, name: 'the Japanese CD', adds: ['signal-found-15'] },
        { c: 'XW', d: '2023-02-17', l: 'Northern Wire', f: 'Digital Media · 20th Anniversary', n: 26, held: true, name: 'the 20th Anniversary edition', adds: Array.from({ length: 12 }, (_, i) => 'signal-found-' + (15 + i)) },
        { c: 'XE', d: '2003-03-03', l: 'Northern Wire', f: 'CD', n: 14 }, { c: 'US', d: '2004-04-06', l: 'Grey Pier', f: 'CD', n: 14 }], rest: 7 },
  };
  const isM = a => a && (a.match === 'matched' || a.match === 'locked');
  let OFF = LS('js-ed-off', {}), HOME = LS('js-ed-home', {}), APART = LS('js-ed-apart', {}), JOIN = LS('js-ed-join', {}), SUGG = LS('js-ed-sugg', {}), every = LS('js-ed-every', false);
  const gap = {};   // preview only: an official song the held edition lacks
  const of = id => (ED[id] && isM(M.album(id)) ? ED[id] : null);
  const pick = id => (ED[id] && OFF[id] != null ? ED[id].P[OFF[id]] : null);
  function official(id) {
    const ts = M.tracksOf(id), p = pick(id), adds = new Set(p && p.adds || []);
    const off = ts.filter(t => !t.ex || adds.has(t.id)), ex = ts.filter(t => t.ex && !adds.has(t.id));
    return { off, ex, pick: p, gap: gap[id] || null };
  }
  const isExtra = t => !!(t.ex && of(t.albumId) && official(t.albumId).ex.indexOf(t) >= 0);
  const bonus = t => (isExtra(t) ? '<span class="ed-bonus" title="An extra: on ' + esc(of(t.albumId).edition) + ', not on the official album">Bonus</span>' : '');
  const plural = (n, a, b) => n + ' ' + (n === 1 ? a : b);
  function count(id) { const o = official(id); return plural(o.off.length, 'song', 'songs') + (o.ex.length ? ' + ' + plural(o.ex.length, 'extra', 'extras') : ''); }
  const cellCount = a => (of(a.id) ? count(a.id) : null);
  function lineHTML(id) {
    const e = of(id); if (!e) return '';
    const o = official(id), p = o.pick;
    return '<div class="ed-off"><b>Official album: ' + plural(o.off.length, 'song', 'songs') + ', as on ' + (p ? esc(p.name) : e.k + ' of ' + e.m + ' releases') + '</b>'
      + (p ? '<span class="ed-how you">chosen by you</span><a href="#" data-edauto="' + id + '">Back to automatic</a>' : '') + '<a href="#" data-edchange="' + id + '">Change…</a></div>'
      + '<div class="ed-off" style="margin-top:4px">held: ' + esc(e.held) + '</div>';
  }
  const divHTML = (id, cols) => { const o = official(id); return o.ex.length ? '<tr class="ed-div"><td colspan="' + cols + '"><span class="l">Extras · ' + esc(of(id).edition) + ' · ' + o.ex.length + '</span></td></tr>' : ''; };
  const firstHTML = t => (t.ex ? '<div class="ed-first">first on ' + esc(t.ex) + '</div>' : '');

  /* ---- singles under their album ---- */
  const SINGLE = { 'kw-northern-line': ['kite-weather', 'MusicBrainz'], 'kw-fog-bank': ['kite-weather', 'MusicBrainz'], 'kw-salt': ['kite-weather', 'by title'], 'kw-low-tide': ['kite-weather', 'MusicBrainz'], 'kw-tidewater': ['kite-weather', 'via remix'], 'sf-shortwave': ['signal-found', 'MusicBrainz'] };
  const home = sid => (sid in HOME ? HOME[sid] : SINGLE[sid] ? SINGLE[sid][0] : null);
  const how = sid => (sid in HOME ? 'you' : SINGLE[sid] ? SINGLE[sid][1] : null);
  const singlesOf = id => M.albums.filter(a => (a.type === 'single' || a.type === 'ep') && home(a.id) === id).sort((a, b) => a.year - b.year || a.order - b.order);
  const bsides = s => M.tracksOf(s.id).filter(t => t.n > 1);
  const underOf = artistId => M.albums.filter(a => a.artistId === artistId && (a.type === 'single' || a.type === 'ep') && home(a.id));
  const openB = {};
  function moveMenu(s) {
    const al = M.albums.filter(a => a.artistId === s.artistId && (a.type === 'album' || a.type === 'soundtrack' || a.type === 'live'));
    return '<span class="menu-wrap"><span class="btn sm ghost menu-btn">Move to… <span class="caret">▾</span></span><div class="menu">'
      + al.map(a => '<div class="menu-item" data-edmove="' + s.id + '|' + a.id + '"><span class="mi-ic">' + (home(s.id) === a.id ? '✓' : '') + '</span><span>' + esc(a.title) + '<span class="mi-sub">' + a.year + '</span></span></div>').join('')
      + '<div class="menu-item" data-edmove="' + s.id + '|"><span class="mi-ic">' + (home(s.id) ? '' : '✓') + '</span><span>No album<span class="mi-sub">Back to the artist’s Singles &amp; EPs</span></span></div>'
      + (s.id in HOME ? '<div class="menu-item" data-edmove="' + s.id + '|auto"><span class="mi-ic">⟲</span><span>Back to automatic<span class="mi-sub">' + (SINGLE[s.id] ? esc(M.album(SINGLE[s.id][0]).title) + ' · ' + SINGLE[s.id][1] : 'No album') + '</span></span></div>' : '') + '</div></span>';
  }
  function singlesHTML(id) {
    if (!of(id)) return '';
    const S = singlesOf(id); if (!S.length) return '';
    const B = S.flatMap(s => bsides(s).map(t => [t, s])), V = window.Versions;
    return '<div class="mu-sec">Singles &amp; B-sides <span class="tiny muted" style="text-transform:none;letter-spacing:0;font-weight:500">' + plural(S.length, 'single', 'singles') + ' · ' + plural(B.length, 'B-side', 'B-sides') + '</span></div>'
      + '<div class="mu-scroll"><table class="mu-tbl"><thead><tr><th>Single</th><th>Year</th><th>B-sides here</th><th>Linked</th><th></th></tr></thead><tbody>'
      + S.map(s => { const b = bsides(s), h = how(s.id); return '<tr><td><a class="ed-sn" href="album.html?a=' + s.id + '"><span class="ed-sth" style="' + M.coverStyle(s) + '"></span>' + esc(s.title) + '</a></td><td class="num">' + s.year + '</td><td class="dim">' + b.length + (b.length ? ' · ' + b.slice(0, 2).map(t => esc(t.title)).join(', ') + (b.length > 2 ? ' …' : '') : '') + '</td><td><span class="ed-how' + (h === 'you' ? ' you' : '') + '">' + esc(h) + '</span></td><td>' + moveMenu(s) + '</td></tr>'; }).join('')
      + '</tbody></table></div>'
      + '<div class="ed-bfold"><b>' + plural(B.length, 'B-side', 'B-sides') + '</b><a href="#" data-edbs="' + id + '">' + (openB[id] ? 'Hide' : 'Show') + '</a><span class="tiny muted">· play after the extras with <i>Play album + extras</i> (owner, Q3)</span></div>'
      + (openB[id] ? '<div class="mu-scroll"><table class="mu-tbl"><tbody>' + B.map(x => '<tr><td>' + esc(x[0].title) + (V ? V.badges(x[0]) : '') + '<div class="ed-first">from ' + esc(x[1].title) + ' (single, ' + x[1].year + ')</div></td><td class="num">' + M.fmtLen(x[0].len) + '</td><td>' + fmt(x[0]) + '</td></tr>').join('') + '</tbody></table></div>' : '')
      + '<div class="tiny muted" style="margin-top:8px;line-height:1.6">The A-sides are not repeated: each is the album’s own song (one copy). <i>Linked</i> says how the single found this album — MusicBrainz’s <i>single from</i>, a remix of an album song, its A-side’s title, or you.</div>';
  }
  function singleLine(id) {
    const a = M.album(id); if (!a || (a.type !== 'single' && a.type !== 'ep') || a.artistId === 'various') return '';
    const h = home(id), w = how(id);
    return '<div class="ed-off">' + (h ? '<b>Single from <a href="album.html?a=' + h + '">' + esc(M.album(h).title) + '</a></b><span class="ed-how' + (w === 'you' ? ' you' : '') + '">' + esc(w) + '</span>' : '<b>No album</b><span class="tiny muted">on the artist’s Singles &amp; EPs</span>' + (w === 'you' ? '<span class="ed-how you">you</span>' : '')) + moveMenu(a) + '</div>';
  }

  /* ---- one copy of every song ---- */
  const GR = {
    'kw-1': [['kite-weather-1'], ['summer-hits-2004-12', 'rec']],
    'nl': [['kite-weather-2'], ['kw-northern-line-1', 'rec'], ['tide-tables-12', 'rec'], ['nordic-nights-2-7', 'audio', 'no trusted recording on the compilation']],
    'salt': [['kite-weather-5'], ['kw-salt-1', 'audio', 'its tags name “Salt on the Window (radio edit)”']],
    'lt': [['kite-weather-6'], ['kw-low-tide-1', 'rec'], ['tide-tables-13', 'rec']],
    'tw': [['kite-weather-8'], ['kw-tidewater-1', 'rec']],
    'sf-1': [['signal-found-1'], ['tide-tables-14', 'rec']],
    'sh': [['signal-found-4'], ['sf-shortwave-1', 'rec']],
  };
  const SUG = [['kite-weather-3', 'kw-fog-bank-1', 'Fog Bank', 'Fog Bank (single version)'], ['kite-weather-9', 'tide-tables-15', 'Gull Year', 'Gull Year (2012 mix)'], ['signal-found-9', 'tide-tables-16', 'Antenna Song', 'Antenna Song (Tide Tables)']];
  const WHY = { rec: 'same recording · MusicBrainz', audio: 'sounds the same', you: 'you said so' };
  const bits = t => parseInt(t.kbps, 10) || 16;
  const quality = t => (t.codec === 'FLAC' ? 2000 + bits(t) * 10 + t.khz / 10 : t.codec === 'WMA' ? t.kbps - 100 : +t.kbps || 0);
  const kind = t => { const a = M.album(t.albumId); return a.type === 'single' || a.type === 'ep' ? 2 : a.type === 'compilation' ? 3 : a.type === 'live' ? 4 : t.ex ? 1 : 0; };
  const KIND = ['album', 'album’s extra', 'single', 'compilation · box set', 'live album'];
  const better = (a, b) => quality(b.t) - quality(a.t) || kind(a.t) - kind(b.t);
  function groups() {
    const G = {};
    Object.entries(GR).forEach(([k, arr]) => { G[k] = arr.map(x => ({ t: M.track(x[0]), why: x[1] || null, note: x[2] || '' })).filter(x => x.t && !APART[x.t.id]); });
    SUG.forEach((s, i) => { if (SUGG[i] === 'yes') G['sug' + i] = [{ t: M.track(s[0]), why: null }, { t: M.track(s[1]), why: 'you' }].filter(x => x.t); });
    Object.entries(JOIN).forEach(([id, k]) => { const t = M.track(id); if (!t) return; Object.values(G).forEach(g => { const i = g.findIndex(x => x.t === t); if (i >= 0) g.splice(i, 1); }); (G[k] = G[k] || []).push({ t, why: 'you' }); });
    Object.keys(G).forEach(k => { if (G[k].length < 2) delete G[k]; else G[k].sort(better); });
    return G;
  }
  const keyOf = (G, t) => Object.keys(G).find(k => G[k].some(x => x.t === t));
  function fold(list, all) {
    const G = groups(), out = [], done = new Set(), inL = new Set(list);
    list.forEach(t => {
      const k = keyOf(G, t); if (!k) { out.push({ t }); return; }
      if (done.has(k)) return;
      const mem = G[k].filter(x => inL.has(x.t)); if (!mem.length) return;
      if (mem[0].t !== t) return;   // the group sits where its shown copy sits
      done.add(k);
      out.push({ t, n: G[k].length - 1 });
      if (all) mem.slice(1).forEach(x => out.push({ t: x.t, copy: true, why: x }));
    });
    return out;
  }
  const fmt = t => '<span class="mu-fmt">' + t.codec + ' · ' + t.kbps + (t.codec === 'FLAC' ? ' · ' + t.khz + ' kHz' : '') + '</span>';
  const alsoHTML = (t, n) => (n ? '<div><span class="ed-also" data-edcopies="' + t.id + '">also on ' + plural(n, 'release', 'releases') + '</span></div>' : '');
  const copyWhy = x => '<div class="ed-cpw">↳ ' + esc(WHY[x.why] || '') + (x.note ? ' · ' + esc(x.note) : '') + ' · <span class="ed-also" data-edcopies="' + x.t.id + '">why</span></div>';
  function switchHTML() {
    const all = M.tracks.length, shown = fold(M.tracks, false).length;
    return '<span class="ed-sw' + (every ? ' on' : '') + '" data-edevery title="Lists show every song once; the admin manages files"><i></i>Show every copy <span class="c">· ' + (every ? all + ' files' : shown + ' songs · ' + (all - shown) + ' copies folded') + '</span></span>';
  }

  /* ---- the copies panel ---- */
  let panelId = null, picking = false, pickQ = '';
  function ensure() {
    if (document.getElementById('ed-panel')) return;
    const s = document.createElement('div'); s.className = 'mu-scrim'; s.id = 'ed-scrim'; document.body.appendChild(s);
    const p = document.createElement('aside'); p.className = 'mu-panel'; p.id = 'ed-panel'; p.setAttribute('aria-label', 'Copies'); document.body.appendChild(p);
    const m = document.createElement('div'); m.className = 'mu-modal ed-modal'; m.id = 'ed-modal'; m.innerHTML = '<div class="card" id="ed-modal-card"></div>'; document.body.appendChild(m);
  }
  function copyRow(x, i, kept) {
    const a = M.album(x.t.albumId), t = x.t;
    const why = i === 0 ? (kind(t) === 0 ? 'the album’s own · the better file' : 'the better file') : WHY[x.why] || '';
    return '<div class="ed-copy"><span class="c" style="' + M.coverStyle(a) + '"></span><div><div class="t"><a href="album.html?a=' + a.id + '">' + esc(a.title) + '</a>' + (i === 0 ? '<span class="ed-kept">shown in lists</span>' : '') + '</div>'
      + '<div class="s">' + esc(KIND[kind(t)].replace(/^./, c => c.toUpperCase())) + ' · ' + a.year + ' · track ' + t.n + ' · ' + t.codec + ' ' + t.kbps + (t.codec === 'FLAC' ? ' / ' + t.khz + ' kHz' : '') + (x.note ? ' · ' + esc(x.note) : '') + '</div></div>'
      + '<div class="a"><span class="ed-why">' + esc(why) + '</span>' + (i ? '<span class="btn sm ghost" data-edapart="' + t.id + '">Not the same song</span>' : '') + '</div></div>';
  }
  function panelHTML(id) {
    const t = M.track(id), G = groups(), k = keyOf(G, t), mem = k ? G[k] : [{ t, why: null }];
    const cands = !picking ? [] : M.tracks.filter(x => x !== t && x.artistIds.indexOf(t.artistIds[0]) >= 0 && !mem.some(m => m.t === x) && (!pickQ || (x.title + ' ' + M.album(x.albumId).title).toLowerCase().indexOf(pickQ.toLowerCase()) >= 0)).slice(0, 8);
    return '<div class="mu-ph"><h3>' + esc(t.title) + '</h3><span class="tiny muted">' + esc(M.artistNames(t.artistIds)) + ' · ' + M.fmtLen(t.len) + '</span><span class="spacer"></span><span class="btn sm ghost" data-edx>✕</span></div>'
      + '<div class="mu-pbody"><div class="mu-sec" style="margin-top:0">' + (mem.length > 1 ? 'One song · ' + mem.length + ' copies' : 'One copy') + '</div>'
      + mem.map((x, i) => copyRow(x, i)).join('')
      + '<div class="tiny muted" style="margin-top:12px;line-height:1.6">The copy shown in lists is <b>the better file, wherever it is from</b> (owner, Q5); on a tie the album’s copy, then its extra, a single’s, a compilation’s or box set’s, a live album’s. Facts are shared only between copies of the <b>same MusicBrainz recording</b>: versions and lyrics never spread to a copy joined by sound or by you.</div>'
      + (picking ? '<div class="ed-pick"><input id="ed-pq" placeholder="Search this artist’s songs" value="' + esc(pickQ) + '">' + (cands.length ? cands.map(x => '<div class="r" data-edjoin="' + x.id + '">' + esc(x.title) + '<span>' + esc(M.album(x.albumId).title) + ' · ' + M.fmtLen(x.len) + '</span></div>').join('') : '<div class="tiny muted" style="padding:8px 4px">Nothing by this artist matches.</div>') + '</div>' : '')
      + '</div><div class="mu-pfoot"><span class="btn sm" data-edpick>' + (picking ? 'Cancel' : 'Same song as…') + '</span><span class="spacer" style="flex:1"></span><span class="btn sm primary" data-edx>Done</span></div>';
  }
  function paint() { if (panelId) document.getElementById('ed-panel').innerHTML = panelHTML(panelId); }
  function openCopies(id) { ensure(); panelId = id; picking = false; pickQ = ''; paint(); document.getElementById('ed-panel').classList.add('on'); document.getElementById('ed-scrim').classList.add('on'); }
  function closeCopies() { panelId = null; const p = document.getElementById('ed-panel'); if (p) { p.classList.remove('on'); document.getElementById('ed-scrim').classList.remove('on'); } }

  /* ---- modals: the pressings, and "Listen and decide" ---- */
  function modal(h) { ensure(); document.getElementById('ed-modal-card').innerHTML = h; document.getElementById('ed-modal').classList.add('on'); }
  const closeModal = () => { const m = document.getElementById('ed-modal'); if (m) m.classList.remove('on'); };
  function pressingsHTML(id) {
    const e = ED[id], a = M.album(id), cur = OFF[id];
    return '<h3>Change the official album · ' + esc(a.title) + '</h3><p>The pressings <i>Find match…</i> already lists. Automatic is the tracklist most official releases share: <b>' + e.k + ' of ' + e.m + '</b> have these ' + e.P[0].n + ' songs.</p>'
      + '<div class="mu-scroll"><table class="mu-tbl ed-ptbl"><thead><tr><th>Country</th><th>Date</th><th>Label</th><th>Format</th><th>Tracks</th><th>Against the official</th><th></th></tr></thead><tbody>'
      + e.P.map((p, i) => '<tr><td>' + p.c + '</td><td class="mu-mono" style="font-size:.74rem">' + p.d + '</td><td>' + esc(p.l) + '</td><td>' + esc(p.f) + (p.held ? ' <span class="chip" style="font-size:.62rem">in the library</span>' : '') + '</td><td class="num">' + p.n + '</td>'
        + '<td class="' + (p.n === e.P[0].n ? 'dim' : '') + '">' + (p.n === e.P[0].n ? 'the official tracklist' : '+ ' + (p.adds ? (p.adds.length === 1 ? esc(M.track(p.adds[0]).title) : p.adds.length + ' (live, demos)') : p.more + ' not in the library')) + '</td>'
        + '<td>' + (p.auto ? (cur == null ? '<span class="tiny muted">official now</span>' : '<span class="btn sm ghost" data-eduse="' + id + '|">Use automatic</span>') : p.adds ? (cur === i ? '<span class="tiny muted">official now</span>' : '<span class="btn sm ghost" data-eduse="' + id + '|' + i + '">Use as the official album</span>') : '') + '</td></tr>').join('')
      + '<tr><td colspan="7" class="dim tiny">… ' + e.rest + ' more releases</td></tr></tbody></table></div>'
      + '<p class="tiny" style="margin-top:10px">A pick reads <i>chosen by you · Back to automatic</i> and is kept across runs. Songs on the held files that the pick doesn’t have become extras; songs the pick has that the files lack become gap rows. A pressing whose extra songs aren’t in the library can’t be picked.</p>'
      + '<div class="row" style="justify-content:flex-end;gap:8px;margin-top:10px"><span class="btn ghost" data-edmx>Close</span></div>';
  }
  let pl = null;
  const left = () => SUG.map((s, i) => i).filter(i => !SUGG[i]);
  function compareHTML() {
    const L2 = left(); if (!L2.length) return '<h3>Songs that may be the same</h3><p>All three answered. <b>Yes</b> joins two copies into one song (<i>you said so</i>); <b>No</b> keeps them two and never asks again.</p><div class="row" style="justify-content:flex-end;margin-top:12px"><span class="btn primary" data-edmx>Done</span></div>';
    const i = L2[0], s = SUG[i], a = M.track(s[0]), b = M.track(s[1]);
    const side = (t, rec, k) => { const al = M.album(t.albumId); return '<div class="ed-pl' + (pl === k ? ' on' : '') + '"><div class="h"><span class="mu-play' + (pl === k ? ' on' : '') + '" data-edpl="' + k + '">' + (pl === k ? '❚❚' : '▶') + '</span><div><div class="t">' + esc(t.title) + '</div><div class="s">' + esc(al.title) + ' · ' + KIND[kind(t)] + ' · ' + M.fmtLen(t.len) + '</div></div></div><div class="ed-wave"></div><div class="s" style="margin-top:8px">recording “' + esc(rec) + '” · MusicBrainz</div></div>'; };
    return '<div class="row center" style="gap:10px"><h3 style="margin:0">These sound the same: one song?</h3><span class="spacer"></span><span class="tiny muted">' + (SUG.length - L2.length + 1) + ' of ' + SUG.length + '</span></div>'
      + '<div class="ed-cmp">' + side(a, s[2], 'a') + side(b, s[3], 'b') + '</div>'
      + '<p class="tiny" style="margin-top:10px">Both start from the same second, so you hear the same bar. MusicBrainz lists two recordings; the audio says they may be one. Nothing is joined until you say so.</p>'
      + '<div class="ed-acts"><span class="btn primary" data-edans="' + i + '|yes">Yes, one song</span><span class="btn" data-edans="' + i + '|no">No, two songs</span><span class="spacer" style="flex:1"></span><span class="btn ghost" data-edmx>Later</span></div>';
  }
  function dashSync() {
    const n = left().length;
    if (window.DASH && DASH.ROWS) { const r = DASH.ROWS.find(x => x.id === 'm-same'); if (r) { r.n = n; if (!n) DASH.ROWS.splice(DASH.ROWS.indexOf(r), 1); } }
    const b = document.querySelector('[data-act="m-same"]'), row = b && b.closest('.ov-row');
    if (row) { if (!n) row.remove(); else { const c = row.querySelector('.ov-n b'); if (c) c.textContent = n; } }
  }
  dashSync();

  /* ---- events ---- */
  const subs = [];
  function changed() { subs.forEach(f => { try { f(); } catch (e) { console.error(e); } }); paint(); }
  function toast(m) { const t = document.createElement('div'); t.className = 'toast'; t.textContent = m; document.body.appendChild(t); setTimeout(() => t.remove(), 2400); }
  window.addEventListener('click', e => { const b = e.target.closest('[data-act="m-same"]'); if (b) { e.stopPropagation(); e.preventDefault(); pl = null; modal(compareHTML()); } }, true);
  document.addEventListener('input', e => { if (e.target.id === 'ed-pq') { pickQ = e.target.value; paint(); const q = document.getElementById('ed-pq'); if (q) { q.focus(); q.setSelectionRange(q.value.length, q.value.length); } } });
  document.addEventListener('click', e => {
    const g = s => e.target.closest('[' + s + ']');
    let x;
    if ((x = g('data-edcopies'))) { e.preventDefault(); e.stopPropagation(); openCopies(x.dataset.edcopies); return; }
    if (e.target.id === 'ed-scrim' || g('data-edx')) { closeCopies(); return; }
    if (e.target.id === 'ed-modal' || g('data-edmx')) { closeModal(); return; }
    if (g('data-edpick')) { picking = !picking; pickQ = picking ? M.track(panelId).title.replace(/\s*\(.*$/, '') : ''; paint(); return; }
    if ((x = g('data-edjoin'))) { const G = groups(), t = M.track(panelId), k = keyOf(G, t) || 'you-' + t.id; if (!keyOf(G, t)) JOIN[t.id] = k; JOIN[x.dataset.edjoin] = k; delete APART[x.dataset.edjoin]; delete APART[t.id]; save('js-ed-join', JOIN); save('js-ed-apart', APART); picking = false; changed(); toast('Joined · one song in lists from now on (you said so)'); return; }
    if ((x = g('data-edapart'))) { const id = x.dataset.edapart; APART[id] = true; delete JOIN[id]; save('js-ed-apart', APART); save('js-ed-join', JOIN); changed(); toast('Kept apart · ' + M.album(M.track(id).albumId).title + '’s copy is its own song now'); return; }
    if (g('data-edevery')) { every = !every; save('js-ed-every', every); changed(); return; }
    if ((x = g('data-edchange'))) { e.preventDefault(); modal(pressingsHTML(x.dataset.edchange)); return; }
    if ((x = g('data-edauto'))) { e.preventDefault(); delete OFF[x.dataset.edauto]; save('js-ed-off', OFF); changed(); toast('Back to automatic · the tracklist most releases share'); return; }
    if ((x = g('data-eduse'))) { const [id, i] = x.dataset.eduse.split('|'); if (i === '') delete OFF[id]; else OFF[id] = +i; save('js-ed-off', OFF); closeModal(); changed(); toast(i === '' ? 'Back to automatic' : 'Official album: ' + ED[id].P[+i].name + ' · kept across runs'); return; }
    if ((x = g('data-edmove'))) { const [sid, to] = x.dataset.edmove.split('|'); if (to === 'auto') delete HOME[sid]; else HOME[sid] = to || null; save('js-ed-home', HOME); changed(); toast(to === 'auto' ? 'Back to automatic' : to ? M.album(sid).title + ' now sits under ' + M.album(to).title : M.album(sid).title + ' moved to No album · it is on the artist’s Singles & EPs again'); return; }
    if ((x = g('data-edbs'))) { e.preventDefault(); openB[x.dataset.edbs] = !openB[x.dataset.edbs]; changed(); return; }
    if ((x = g('data-edpl'))) { pl = pl === x.dataset.edpl ? null : x.dataset.edpl; modal(compareHTML()); return; }
    if ((x = g('data-edans'))) { const [i, v] = x.dataset.edans.split('|'); SUGG[i] = v; save('js-ed-sugg', SUGG); pl = null; modal(compareHTML()); dashSync(); changed(); toast(v === 'yes' ? 'One song · joined (you said so)' : 'Two songs · never asked again'); return; }
  });
  document.addEventListener('keydown', e => { if (e.key === 'Escape') { closeCopies(); closeModal(); } });

  window.Editions = {
    of, official, isExtra, bonus, count, cellCount, lineHTML, divHTML, firstHTML, singlesHTML, singleLine, underOf, home,
    fold, alsoHTML, copyWhy, switchHTML, openCopies, groups, singlesOf, bsides, kind, KIND,
    copiesOf(t) { const G = groups(), k = keyOf(G, t); return k ? G[k].map(x => x.t) : [t]; },
    get every() { return every; },
    setGap(id, n) { if (n) gap[id] = n; else delete gap[id]; changed(); },
    gapOf: id => gap[id] || null,
    on(f) { subs.push(f); },
  };
})();
