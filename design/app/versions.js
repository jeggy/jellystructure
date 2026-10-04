/* Song versions — design brief 2026-10-01 (specs/design-brief-music-song-versions-2026-10-01.md), round 1.
   Q1 (the badge): owner 2026-10-01 picked A · words — in jellystructure and in Ravilo (ravilo/mobile/ravilo-versions.js).
   B and C stay drawable (chips(ks, 'b'|'c')) for the directions canvas only.
   Also: the side-panel editor (Q2), Set version… on a selection, the Version facet (include / exclude),
   the Metadata → Versions tab, the artist page's doorway, the album header summary.
   Loads after music-data.js and adds the brief's stand-ins (all fictional) to window.MUSIC.
   localStorage: js-ver-q1 (direction) · js-ver-ovr (your ticks, keyed by recording) · js-ver-col (colours, meanings). */
(function () {
  const M = window.MUSIC; if (!M) return;
  const esc = s => (s == null ? '' : String(s)).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const LS = (k, d) => { try { const v = JSON.parse(localStorage.getItem(k)); return v == null ? d : v; } catch (e) { return d; } };
  const SAVE = (k, v) => { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} };

  // the nine (§B) — fixed in round 1 (Q3); colour and meaning editable. n = household songs today; via = found from.
  const TYPES = [
    { k: 'live', l: 'Live', c: '#f0795b', code: 'L', m: 'Recorded at a concert — and every Session', via: 'MusicBrainz · the title', n: 116, pl: 'live' },
    { k: 'demo', l: 'Demo', c: '#a3aec6', code: 'D', m: 'An early, unfinished recording', via: 'MusicBrainz · the title', n: 28, pl: 'demos' },
    { k: 'remix', l: 'Remix', c: '#c67fe3', code: 'R', m: 'Someone re-made the recording', via: 'MusicBrainz · the title', n: 25, pl: 'remixes' },
    { k: 'instrumental', l: 'Instrumental', c: '#3fb6f5', code: 'I', m: 'A sung song without the voice', via: 'MusicBrainz · the title', n: 12, pl: 'instrumental' },
    { k: 'cover', l: 'Cover', c: '#2dd49a', code: 'C', m: 'One artist playing another artist’s song', via: 'MusicBrainz', n: 20, pl: 'covers' },
    { k: 'acoustic', l: 'Acoustic', c: '#d8ad62', code: 'Ac', m: 'Played unplugged', via: 'the title', n: 10, pl: 'acoustic' },
    { k: 'edit', l: 'Edit', c: '#9d95f7', code: 'E', m: 'The same recording made shorter or longer', via: 'the title', n: 8, pl: 'edits' },
    { k: 'alternate', l: 'Alternate version', s: 'Alternate', c: '#e9709f', code: 'Alt', m: 'A different take or arrangement of the same song', via: 'the title', n: 7, pl: 'alternate versions' },
    { k: 'session', l: 'Session', c: '#f2a65a', code: 'S', m: 'Recorded live in a studio for radio, TV or a website — counts as Live', via: 'the title', n: 4, pl: 'sessions' },
  ];
  const T = {}; TYPES.forEach(t => T[t.k] = t);
  const COL = LS('js-ver-col', {});
  TYPES.forEach(t => { if (COL[t.k]) { if (COL[t.k].c) t.c = COL[t.k].c; if (COL[t.k].m) t.m = COL[t.k].m; } });
  const PALETTE = ['#f0795b', '#f2a65a', '#d8ad62', '#2dd49a', '#3fb6f5', '#9d95f7', '#c67fe3', '#e9709f', '#a3aec6'];

  /* ---- stand-ins (§B): added to the shared music data on the admin pages that load this file ---- */
  const L = s => { const [m, x] = s.split(':'); return +m * 60 + +x; };
  function addAlbum(id, title, artistId, year, type, total, codec, kbps, genres) {
    if (M.byAlbum[id]) return;
    const al = { id, title, artistId, year, type, total, match: 'matched', cover: true, codec, kbps, genres: genres.map(g => ({ name: g[0], votes: g[1] })), mbid: M.mbid('rg' + id), relMbid: M.mbid('rel' + id), hue: M.hash(title) % 360,
      label: 'Fjordlight Records', country: (M.byArtist[artistId] || {}).country || 'XE', added: '2026-09-23', gain: -9.4, order: M.albums.length, trackIds: [] };
    M.albums.push(al); M.byAlbum[id] = al;
  }
  function addTrack(albumId, n, title, len, x) {
    x = x || {}; const al = M.byAlbum[albumId], id = albumId + '-' + n; if (!al || M.byTrack[id]) return;
    const t = { id, albumId, n, title, len: L(len), artistIds: x.by || [al.artistId], feat: x.feat || [], codec: al.codec, kbps: al.kbps, khz: 44.1, lyrics: x.ly || null,
      rec: al.match === 'matched' || al.match === 'locked' ? 'ok' : 'none', gain: -(8.7 + (M.hash(id) % 19) / 10), plays: 0, last: null };
    M.tracks.push(t); M.byTrack[id] = t; al.trackIds.push(id); al.trackIds.sort((a, b) => M.byTrack[a].n - M.byTrack[b].n);
  }
  addAlbum('live-at-the-harbour', 'Live at the Harbour', 'harbour-lights', 2011, 'live', 14, 'MP3', 320, [['indie folk', 12], ['folk rock', 4]]);
  addTrack('live-at-the-harbour', 1, 'Salt on the Window (live at the harbour, 2011)', '4:31', { ly: 'synced' });
  addTrack('live-at-the-harbour', 6, 'Low Tide (acoustic, radio session)', '3:48');
  addTrack('live-at-the-harbour', 9, 'Weathervane (live at the harbour, 2011)', '5:02');
  addAlbum('tide-tables', 'Tide Tables 1999–2012', 'harbour-lights', 2012, 'compilation', 48, 'MP3', 320, [['indie folk', 7]]);
  addTrack('tide-tables', 1, 'Prelude', '1:42');
  addTrack('tide-tables', 7, 'Salt on the Window (live at the harbour, 2011)', '4:31', { ly: 'synced' });
  addTrack('tide-tables', 31, 'Fog Bank', '3:56', { ly: 'plain' });
  addTrack('tide-tables', 32, 'Paper Lanterns (instrumental demo)', '4:02', { ly: 'synced' });
  addTrack('tide-tables', 33, 'Two Harbours (instrumental demo)', '4:16', { ly: 'plain' });
  addTrack('tide-tables', 34, 'Ferry Lights (demo)', '3:09');
  addTrack('tide-tables', 35, 'Northbound (acoustic, alternate take, radio session)', '4:05');
  addAlbum('nordic-nights-2', 'Nordic Nights Vol. 2', 'various', 2012, 'compilation', 20, 'MP3', 256, [['indie folk', 3]]);
  addTrack('nordic-nights-2', 14, 'Salt on the Window (live at the harbour, 2011)', '4:31', { by: ['harbour-lights'] });
  // the remix and the instrumental now live on singles (sf-shortwave-2, kw-tidewater-5), made by editions.js (2026-10-04)
  addTrack('kvold', 5, 'Kvøldsól (live)', '4:02');

  // what each finder says. rec = the MusicBrainz recording (one answer for every copy, §C); of = the sung song.
  const VS = {
    'live-at-the-harbour-1': { rec: 'salt-live', mb: ['live'], ti: ['live'] },
    'tide-tables-7': { rec: 'salt-live', mb: ['live'], ti: ['live'] },
    'nordic-nights-2-14': { rec: 'salt-live', mb: ['live'], ti: ['live'] },
    'live-at-the-harbour-6': { ti: ['acoustic', 'session'] },
    'live-at-the-harbour-9': { mb: ['live'], ti: ['live'] },
    'tide-tables-1': { nowords: true },
    'tide-tables-31': { mb: ['cover', 'demo', 'instrumental'], ofName: 'Fog Bank', ofBy: 'The Ferrymen' },
    'tide-tables-32': { mb: ['demo', 'instrumental'], ti: ['demo', 'instrumental'], of: 'salt-on-the-window-2' },
    'tide-tables-33': { mb: ['demo', 'instrumental'], ti: ['demo', 'instrumental'], of: 'salt-on-the-window-6' },
    'tide-tables-34': { mb: ['demo'], ti: ['demo'] },
    'tide-tables-35': { mb: ['live'], ti: ['acoustic', 'alternate', 'session'] },
    'sf-shortwave-2': { mb: ['remix'], ti: ['remix', 'edit'] },
    'kw-tidewater-5': { mb: ['instrumental'], ti: ['instrumental'], of: 'kite-weather-1' },
    // editions.js's extras and B-sides (brief 2026-10-03)
    'signal-found-16': { mb: ['live'], ti: ['live'] }, 'signal-found-17': { mb: ['live'], ti: ['live'] }, 'signal-found-18': { mb: ['live'], ti: ['live'] }, 'signal-found-19': { mb: ['live'], ti: ['live'] }, 'signal-found-20': { mb: ['live'], ti: ['live'] }, 'signal-found-21': { mb: ['live'], ti: ['live'] },
    'signal-found-22': { mb: ['demo'], ti: ['demo'] }, 'signal-found-23': { mb: ['demo'], ti: ['demo'] }, 'signal-found-24': { mb: ['demo'], ti: ['demo'] }, 'signal-found-25': { mb: ['demo'], ti: ['demo'] }, 'signal-found-26': { mb: ['demo'], ti: ['demo'] },
    'kw-northern-line-3': { mb: ['acoustic'], ti: ['acoustic'] }, 'kw-fog-bank-3': { mb: ['demo'], ti: ['demo'] }, 'kw-salt-4': { mb: ['live'], ti: ['live'] }, 'kw-low-tide-3': { ti: ['session'] }, 'kw-tidewater-3': { mb: ['remix'], ti: ['remix'] },
    'kvold-5': { ti: ['live'] },
    'early-recordings-7': { mb: ['demo'], ti: ['demo'] },
    'live-at-torshavn-4': { mb: ['live'], ti: ['live'] },
    'live-at-torshavn-9': { mb: ['live'], ti: ['live'] },
    'north-atlantic-songbook-1': { mb: ['live'] }, 'north-atlantic-songbook-2': { mb: ['live'] }, 'north-atlantic-songbook-3': { mb: ['live'] },
    'summer-hits-2004-5': { mb: ['edit'], ti: ['edit'] },
    'last-stop-everybody-5': { mb: ['live'] },
  };
  // your choices, keyed by recording (a song without a match is keyed by itself). Two seeded: one set, one removed.
  const OVR = LS('js-ver-ovr', { 'trk:live-at-torshavn-4': { acoustic: true }, 'trk:last-stop-everybody-5': { live: false } });

  const matched = t => { const a = M.album(t.albumId); return (a.match === 'matched' || a.match === 'locked') && t.rec !== 'none'; };
  const key = t => { const v = VS[t.id]; return v && v.rec && matched(t) ? 'rec:' + v.rec : 'trk:' + t.id; };
  const user = t => OVR[key(t)] || {};
  function auto(t) {
    const v = VS[t.id] || {}, out = {}, mb = matched(t) ? (v.mb || []) : [];
    mb.forEach(k => out[k] = 'mb'); (v.ti || []).forEach(k => out[k] = out[k] ? 'both' : 'title');
    if (out.session && !out.live) out.live = 'session';
    return out;
  }
  function list(t) {
    const a = auto(t), u = user(t), s = new Set(Object.keys(a));
    Object.keys(u).forEach(k => u[k] ? s.add(k) : s.delete(k));
    if (s.has('session')) s.add('live');
    return TYPES.map(x => x.k).filter(k => s.has(k));
  }
  function srcOf(t, k) {
    const a = auto(t), u = user(t);
    if (u[k] === false) return a[k] ? 'removed' : null;
    if (u[k] === true && !a[k]) return 'you';
    if (k === 'live' && list(t).indexOf('session') >= 0 && !a.live && u.live == null) return 'session';
    return a[k] || (u[k] ? 'you' : null);
  }
  const SRC = { mb: 'from MusicBrainz', title: 'from the title', both: 'MusicBrainz · the title', you: 'set by you', removed: 'removed by you', session: 'with Session' };
  const noWords = t => !!(VS[t.id] && VS[t.id].nowords && matched(t));
  const lyricsIssue = t => !!t.lyrics && (noWords(t) || list(t).indexOf('instrumental') >= 0);
  function copies(t) { const v = VS[t.id]; if (!v || !v.rec || !matched(t)) return []; return M.tracks.filter(x => x.id !== t.id && VS[x.id] && VS[x.id].rec === v.rec && matched(x)); }
  function setType(t, k, on) {
    const ky = key(t), a = auto(t), u = Object.assign({}, OVR[ky] || {});
    const put = (kk, v) => { if (!!a[kk] === v && !(kk === 'live' && a.live === 'session')) delete u[kk]; else u[kk] = v; };
    put(k, on);
    if (k === 'session' && on) { if (!a.live) u.live = true; }
    if (k === 'live' && !on && list(t).indexOf('session') >= 0) put('session', false);
    if (Object.keys(u).length) OVR[ky] = u; else delete OVR[ky];
    SAVE('js-ver-ovr', OVR);
  }
  function backToAuto(t) { delete OVR[key(t)]; SAVE('js-ver-ovr', OVR); }

  /* ---- Q1: the badge ---- */
  let dir = 'a'; // owner 2026-10-01: A · Words (decided — the switch is gone)
  const DIRS = [
    ['a', 'A · Words', 'One chip per type, in its colour, with its name — reads without a legend; the widest.', true],
    ['b', 'B · Marks', 'A coloured letter per type (L · D · R · I · C …) — four fit where one word does; the name on hover and in Metadata.'],
    ['c', 'C · One phrase', 'One quiet chip that reads “Live · Session”, a dot of colour per word — one target; colour is secondary.'],
  ];
  const name = (k, short) => short && T[k].s ? T[k].s : T[k].l;
  function chips(ks, d) {
    d = d || dir; const shown = ks.slice(0, 3), more = ks.length - shown.length;
    const plus = more ? '<span class="vr-more" title="' + esc(ks.slice(3).map(k => T[k].l).join(', ')) + '">+' + more + '</span>' : '';
    if (d === 'b') return shown.map(k => '<span class="vr-m" style="--c:' + T[k].c + '" title="' + esc(T[k].l) + '">' + T[k].code + '</span>').join('') + plus;
    if (d === 'c') return '<span class="vr-p">' + shown.map((k, i) => (i ? '<span class="s">·</span>' : '') + '<i style="--c:' + T[k].c + '"></i>' + esc(name(k, true))).join('') + (more ? '<span class="s">+' + more + '</span>' : '') + '</span>';
    return shown.map(k => '<span class="vr-b" style="--c:' + T[k].c + '">' + esc(name(k, true)) + '</span>').join('') + plus;
  }
  function badges(t, d) {
    const ks = list(t);
    if (!ks.length) return '<span class="vr-add" data-ver="' + t.id + '" title="Version — none yet">＋ Version</span>';
    return '<span class="vr-bs" data-ver="' + t.id + '" title="Version — click to change">' + chips(ks, d) + '</span>';
  }
  function detail(t) {
    const v = VS[t.id] || {};
    if (list(t).indexOf('instrumental') >= 0) {
      if (v.of && M.track(v.of)) { const s = M.track(v.of); return '<div class="vr-of">Instrumental version of <a href="album.html?a=' + s.albumId + '">' + esc(s.title) + '</a></div>'; }
      if (v.ofName) return '<div class="vr-of">Instrumental version of <i>' + esc(v.ofName) + '</i> · ' + esc(v.ofBy) + ' — not in the library</div>';
    }
    return '';
  }
  function albumSummary(ts, d) {
    if (ts.length < 2) return '';
    const c = {}; ts.forEach(t => list(t).forEach(k => c[k] = (c[k] || 0) + 1));
    const top = Object.keys(c).filter(k => k !== 'session').sort((a, b) => c[b] - c[a])[0];
    if (!top || c[top] < Math.ceil(ts.length * 0.6)) return '';
    return '<span class="sep">·</span><span class="vr-sum">' + chips([top], d) + '<span>' + (c[top] === ts.length ? 'all ' + ts.length + ' songs' : c[top] + ' of ' + ts.length + ' songs') + '</span></span>';
  }

  /* ---- selection + Set version… ---- */
  const sel = new Set();
  const selCell = t => '<span class="vr-num">' + t.n + '</span><span class="vr-sel' + (sel.has(t.id) ? ' on' : '') + '" data-vsel="' + t.id + '">' + (sel.has(t.id) ? '✓' : '') + '</span>';
  function selBar(ids) {
    const n = ids ? ids.filter(id => sel.has(id)).length : sel.size;
    return '<div class="mu-selbar' + (n ? ' on' : '') + '"><b>' + n + '</b> song' + (n === 1 ? '' : 's') + ' selected<span class="btn sm primary" data-vbulk>Set version…</span><span class="spacer" style="flex:1"></span>'
      + (ids ? '<span class="tiny" style="cursor:pointer;color:var(--ink-soft)" data-vall="' + ids.join(',') + '">select all shown</span>' : '') + '<span class="tiny" style="cursor:pointer;color:var(--ink-soft)" data-vnone>✕ clear</span></div>';
  }
  let bulkPick = {};
  function bulkHTML() {
    const ts = [...sel].map(M.track).filter(Boolean), cnt = k => ts.filter(t => list(t).indexOf(k) >= 0).length;
    const recs = new Set(ts.map(key)), others = ts.reduce((s, t) => s + copies(t).filter(c => !sel.has(c.id)).length, 0);
    return '<h3>Set version on ' + ts.length + ' song' + (ts.length === 1 ? '' : 's') + '</h3><p>Add or remove a type on all of them. What you leave alone stays as it is on each song.</p>'
      + '<div class="vr-bk">' + TYPES.map(x => { const c = cnt(x.k), p = bulkPick[x.k] || 'leave';
        return '<div class="vr-bkr"><span class="vr-dot" style="--c:' + x.c + '"></span><span class="nm">' + esc(x.l) + '</span><span class="tiny muted">' + (c ? c + ' of ' + ts.length + ' have it' : 'none have it') + '</span><span class="seg">'
          + ['leave:Leave', 'add:Add', 'remove:Remove'].map(o => { const [v, l] = o.split(':'); return '<span data-vbp="' + x.k + ':' + v + '" class="' + (p === v ? 'on' : '') + '">' + l + '</span>'; }).join('') + '</span></div>'; }).join('') + '</div>'
      + (bulkPick.session === 'add' ? '<div class="tiny muted" style="margin-top:8px">Adding Session adds Live with it.</div>' : '')
      + (others ? '<div class="mu-note" style="margin:12px 0 0"><div class="t">' + others + ' other cop' + (others === 1 ? 'y' : 'ies') + ' of these recordings, on other albums, change with them — the same MusicBrainz recording carries one answer.</div></div>' : '')
      + '<div class="row" style="justify-content:flex-end;gap:8px;margin-top:14px"><span class="btn ghost" data-vbx>Cancel</span><span class="btn primary" data-vbgo>Apply to ' + ts.length + '</span></div>';
  }

  /* ---- the side panel (Q2) ---- */
  let open = null;
  function panelHTML(t, d) {
    const a = M.album(t.albumId), ks = list(t), cp = copies(t), u = user(t), v = VS[t.id] || {};
    const rows = TYPES.map(x => { const on = ks.indexOf(x.k) >= 0, s = srcOf(t, x.k);
      return '<div class="vr-row' + (on ? ' on' : '') + (s === 'removed' ? ' rm' : '') + '" data-vt="' + x.k + '" style="--c:' + x.c + '"><span class="bx">' + (on ? '✓' : '') + '</span><span class="nm">' + esc(x.l) + '</span>'
        + '<span class="src' + (s === 'you' ? ' you' : s === 'removed' ? ' rmv' : '') + '">' + (s ? SRC[s] : '') + '</span><span class="mn">' + esc(x.m) + '</span></div>'; }).join('');
    const notes = [];
    if (!matched(t)) notes.push('<div class="mu-note"><div class="t"><b>Not matched yet.</b> Only the title can say anything, so every type here reads <i>from the title</i>. A match adds MusicBrainz’s answer.</div></div>');
    if (noWords(t)) notes.push('<div class="mu-note"><div class="t"><b>No words — MusicBrainz.</b> A piece that was never sung has no version — it is not Instrumental — and it is never given lyrics.</div></div>');
    if (cp.length) notes.push('<div class="mu-note"><div class="t"><b>The same recording is also on ' + cp.length + ' other album' + (cp.length === 1 ? '' : 's') + ' — the change applies there too:</b> ' + cp.map(c => '<a href="album.html?a=' + c.albumId + '">' + esc(M.album(c.albumId).title) + '</a>').join(' · ') + '</div></div>');
    if (lyricsIssue(t)) notes.push('<div class="mu-note w"><div class="t"><b>Lyrics beside a song with no singing.</b> They are listed on <a href="index.html">the Dashboard</a>; only you remove them there.</div></div>');
    const ofLine = ks.indexOf('instrumental') >= 0 ? '<div class="mu-sec">Instrumental version of</div>' + (v.of && M.track(v.of) ? '<div class="vr-ofp"><a href="album.html?a=' + M.track(v.of).albumId + '">' + esc(M.track(v.of).title) + '</a><span class="tiny muted">on ' + esc(M.album(M.track(v.of).albumId).title) + ' · from MusicBrainz’s link</span></div>' : v.ofName ? '<div class="vr-ofp"><i>' + esc(v.ofName) + '</i><span class="tiny muted">' + esc(v.ofBy) + ' · not in the library</span></div>' : '<div class="tiny muted">MusicBrainz names no sung song for this one.</div>') : '';
    return '<div class="mu-ph"><h3>Version</h3><span class="spacer"></span><span class="btn sm ghost" data-vpx>✕</span></div>'
      + '<div class="mu-pbody"><div class="vr-ph"><div class="ttl">' + esc(t.title) + '</div><div class="tiny muted">' + esc(M.artistNames(t.artistIds)) + ' · ' + esc(a.title) + ' · track ' + t.n + '</div><div class="vr-pv">' + (ks.length ? chips(ks, d) : '<span class="tiny muted">No version — an ordinary recording shows nothing.</span>') + '</div></div>'
      + notes.join('') + '<div class="vr-rows">' + rows + '</div>' + ofLine + '</div>'
      + '<div class="mu-pfoot"><span class="tiny muted">Saved as you tick · kept across runs</span><span class="spacer" style="flex:1"></span>' + (Object.keys(u).length ? '<span class="btn sm ghost" data-vauto>Back to automatic</span>' : '') + '<span class="btn sm primary" data-vpx>Done</span></div>';
  }
  function ensure() {
    if (document.getElementById('vr-panel')) return;
    const s = document.createElement('div'); s.className = 'mu-scrim'; s.id = 'vr-scrim'; document.body.appendChild(s);
    const p = document.createElement('aside'); p.className = 'mu-panel vr-panel'; p.id = 'vr-panel'; p.setAttribute('aria-label', 'Version'); document.body.appendChild(p);
    const m = document.createElement('div'); m.className = 'mu-modal'; m.id = 'vr-modal'; m.innerHTML = '<div class="card" id="vr-modal-card" style="max-width:600px"></div>'; document.body.appendChild(m);
  }
  function paintPanel() { if (open) document.getElementById('vr-panel').innerHTML = panelHTML(M.track(open)); }
  function openPanel(id) { ensure(); open = id; paintPanel(); document.getElementById('vr-panel').classList.add('on'); document.getElementById('vr-scrim').classList.add('on'); }
  function closePanel() { open = null; const p = document.getElementById('vr-panel'); if (p) { p.classList.remove('on'); document.getElementById('vr-scrim').classList.remove('on'); } }

  /* ---- facet (D2) ---- */
  const has = (t, k) => k === 'none' ? !list(t).length : list(t).indexOf(k) >= 0;
  function pass(t, vf) {
    if (!vf) return true;
    if (vf.inc.size && ![...vf.inc].some(k => has(t, k))) return false;
    if ([...vf.exc].some(k => has(t, k))) return false;
    return true;
  }
  const fname = k => k === 'none' ? 'No version' : T[k].l;
  function facetHTML(vf, isOpen, count) {
    const n = vf.inc.size + vf.exc.size;
    return '<span class="mu-fc' + (isOpen ? ' open' : '') + '"><span class="mu-fbtn' + (n ? ' on' : '') + '" data-fopen="version">Version' + (n ? ' <span class="c">' + n + '</span>' : '') + ' ▾</span>'
      + '<div class="mu-fpop vr-fpop"><div class="tiny muted" style="padding:4px 8px 8px">A version belongs to a song. <b>Only</b> keeps the songs with it; <b>Hide</b> takes them out.</div>'
      + TYPES.map(x => x.k).concat(['none']).map(k => { const c = count(k), i = vf.inc.has(k), x = vf.exc.has(k);
        return '<div class="vr-fv' + (c ? '' : ' zero') + '"><span class="nm">' + (k === 'none' ? '<span class="tiny" style="font-weight:600">No version</span><span class="tiny muted">the originals</span>' : chips([k]) + (dir === 'b' ? '<span class="tiny">' + esc(T[k].l) + '</span>' : '')) + '</span><span class="ct">' + c + '</span>'
          + '<span class="vr-ie"><span class="i' + (i ? ' on' : '') + '" data-vfi="' + k + '">Only</span><span class="x' + (x ? ' on' : '') + '" data-vfx="' + k + '">Hide</span></span></div>'; }).join('')
      + '</div></span>';
  }
  function words(vf, by) {
    const p = [];
    if (vf.inc.size) p.push('only ' + [...vf.inc].map(k => k === 'none' ? 'the originals' : T[k].l).join(' or '));
    if (vf.exc.size) p.push('without ' + [...vf.exc].map(k => k === 'none' ? 'the originals' : T[k].l).join(', '));
    return 'Songs' + (by ? ' by ' + esc(by) : '') + (p.length ? ' · ' + p.join(' · ') : '');
  }
  function toggleF(vf, k, which) {
    const a = which === 'i' ? vf.inc : vf.exc, b = which === 'i' ? vf.exc : vf.inc;
    if (a.has(k)) a.delete(k); else { a.add(k); b.delete(k); }
  }

  /* ---- the artist page's doorway (D4) ---- */
  function artistLine(arId) {
    const ts = M.tracksBy(arId); if (!ts.length) return '';
    const c = {}; ts.forEach(t => list(t).forEach(k => c[k] = (c[k] || 0) + 1));
    const ks = TYPES.map(x => x.k).filter(k => c[k]); if (!ks.length) return '';
    const base = 'library.html?kind=music&mview=songs&artist=' + arId;
    return '<div class="mu-pbm vr-al"><a href="' + base + '">' + ts.length + ' songs</a>' + ks.map(k => '<span class="sep">·</span><a href="' + base + '&vi=' + k + '">' + c[k] + ' ' + (c[k] === 1 ? T[k].l.toLowerCase() : T[k].pl) + '</a>').join('')
      + (c.live || c.remix ? '<span class="sep">·</span><a class="vr-door" href="' + base + '&vx=live,remix">songs without Live and Remix →</a>' : '') + '</div>';
  }

  /* ---- Metadata → Versions (D3) ---- */
  function metaHTML() {
    const byYou = k => Object.values(OVR).reduce((s, u) => s + (u[k] === true ? 1 : 0), 0), rmYou = k => Object.values(OVR).reduce((s, u) => s + (u[k] === false ? 1 : 0), 0);
    return '<div class="note blue" style="margin-bottom:16px;">A <b>version</b> belongs to a song — one set of types per MusicBrainz recording, so the same recording on an album, a best-of and a box set carries one answer. Found automatically (MusicBrainz first, then the title) and yours to change on any song; what you change is kept across runs. The nine are fixed for now — the colour and the meaning are yours.</div>'
      + '<div class="row center" style="margin-bottom:10px;gap:10px;flex-wrap:wrap"><span class="muted tiny">9 types · <b style="color:var(--ink)">193</b> of 487 songs have at least one · <b style="color:var(--ink)">294</b> have no version</span></div>'
      + '<div class="mu-scroll"><table class="mu-tbl vr-mt"><thead><tr><th>Colour</th><th>Type</th><th>Means</th><th>Found from</th><th>Songs</th><th>By you</th></tr></thead><tbody>'
      + TYPES.map(x => '<tr><td><span class="vr-sw" style="--c:' + x.c + '" data-vsw="' + x.k + '" title="Change the colour"></span></td><td>' + chips([x.k]) + (dir === 'b' ? ' <span class="tiny">' + esc(x.l) + '</span>' : '') + '</td>'
        + '<td><input class="vr-mi" data-vmi="' + x.k + '" value="' + esc(x.m) + '"></td><td class="tiny muted">' + x.via + '</td>'
        + '<td class="num"><a href="library.html?kind=music&mview=songs&vi=' + x.k + '">' + x.n + ' songs →</a></td><td class="tiny muted">' + ([byYou(x.k) ? byYou(x.k) + ' set' : '', rmYou(x.k) ? rmYou(x.k) + ' removed' : ''].filter(Boolean).join(' · ') || '—') + '</td></tr>').join('')
      + '<tr><td></td><td><span class="tiny" style="font-weight:600">No version</span></td><td class="tiny muted">An ordinary recording — and a piece that was never sung</td><td></td><td class="num"><a href="library.html?kind=music&mview=songs&vi=none">294 songs →</a></td><td></td></tr>'
      + '</tbody></table></div>'
      + '<div class="tiny muted" style="margin-top:12px;line-height:1.6;max-width:760px">Songs counts are the household’s (2026-10-01); <i>By you</i> counts this mockup’s edits. A Session is also Live — ticking Session ticks Live, so <i>without Live</i> hides sessions too.</div>';
  }

  /* ---- the Q1 panel ---- */
  function qHTML() {
    return ''; // Q1 decided (owner 2026-10-01: A)
    return '<div class="mu-qs vr-qs"><div class="row center" style="gap:10px;flex-wrap:wrap"><h3>Round 1 · the badge (Q1)</h3><span class="tiny muted">the brief leaves it to design · switches every page live</span><span class="spacer"></span><a class="tiny" href="Song%20Versions%20-%20Directions.html">side by side ›</a></div>'
      + DIRS.map(x => '<div class="mu-q' + (dir === x[0] ? ' here' : '') + '" data-vq="' + x[0] + '" style="cursor:pointer"><span class="qn">' + x[0].toUpperCase() + '</span><span class="qt">' + esc(x[1].slice(4)) + (x[3] ? '<span class="mu-lean">lean</span>' : '') + (dir === x[0] ? '<span class="mu-here">showing</span>' : '') + '<span class="vr-qx">' + '<span>' + chips(['live', 'session'], x[0]) + '</span><span>' + chips(['demo', 'instrumental', 'cover'], x[0]) + '</span><span>' + chips(['live', 'acoustic', 'alternate', 'session'], x[0]) + '</span>' + '</span></span><span class="qd">' + esc(x[2]) + '</span></div>').join('') + '</div>';
  }
  const subs = [];
  function changed() { subs.forEach(f => { try { f(); } catch (e) { console.error(e); } }); document.querySelectorAll('[data-vqmount]').forEach(el => el.innerHTML = qHTML()); if (open) paintPanel(); }
  function toast(m) { const t = document.createElement('div'); t.className = 'toast'; t.textContent = m; document.body.appendChild(t); setTimeout(() => t.remove(), 2400); }

  document.addEventListener('click', e => {
    const q = e.target.closest('[data-vq]'); if (q) { dir = q.dataset.vq; SAVE('js-ver-q1', dir); changed(); return; }
    const v = e.target.closest('[data-ver]'); if (v) { e.preventDefault(); e.stopPropagation(); openPanel(v.dataset.ver); return; }
    const s = e.target.closest('[data-vsel]'); if (s) { e.preventDefault(); e.stopPropagation(); const id = s.dataset.vsel; sel.has(id) ? sel.delete(id) : sel.add(id); changed(); return; }
    const all = e.target.closest('[data-vall]'); if (all) { all.dataset.vall.split(',').forEach(id => sel.add(id)); changed(); return; }
    if (e.target.closest('[data-vnone]')) { sel.clear(); changed(); return; }
    if (e.target.closest('[data-vbulk]')) { ensure(); bulkPick = {}; document.getElementById('vr-modal-card').innerHTML = bulkHTML(); document.getElementById('vr-modal').classList.add('on'); return; }
    const bp = e.target.closest('[data-vbp]'); if (bp) { const [k, val] = bp.dataset.vbp.split(':'); bulkPick[k] = val; if (k === 'session' && val === 'add' && (bulkPick.live || 'leave') === 'leave') bulkPick.live = 'add'; if (k === 'live' && val === 'remove') bulkPick.session = 'remove'; document.getElementById('vr-modal-card').innerHTML = bulkHTML(); return; }
    if (e.target.closest('[data-vbx]') || e.target.id === 'vr-modal') { document.getElementById('vr-modal').classList.remove('on'); return; }
    if (e.target.closest('[data-vbgo]')) {
      const ts = [...sel].map(M.track).filter(Boolean), ch = Object.entries(bulkPick).filter(x => x[1] !== 'leave');
      ts.forEach(t => ch.forEach(([k, val]) => setType(t, k, val === 'add')));
      document.getElementById('vr-modal').classList.remove('on');
      toast(ch.length ? ch.map(([k, val]) => (val === 'add' ? '+ ' : '− ') + T[k].l).join(' · ') + ' on ' + ts.length + ' song' + (ts.length === 1 ? '' : 's') : 'Nothing to change');
      sel.clear(); changed(); return;
    }
    if (e.target.closest('#vr-scrim') || e.target.closest('[data-vpx]')) { closePanel(); return; }
    if (e.target.closest('[data-vauto]')) { backToAuto(M.track(open)); changed(); toast('Back to automatic · MusicBrainz and the title decide again'); return; }
    const r = e.target.closest('#vr-panel [data-vt]'); if (r && open) { const t = M.track(open), k = r.dataset.vt, on = list(t).indexOf(k) < 0; setType(t, k, on); changed(); return; }
    const sw = e.target.closest('[data-vsw]'); if (sw) { const x = T[sw.dataset.vsw], i = (PALETTE.indexOf(x.c) + 1) % PALETTE.length; x.c = PALETTE[i]; COL[x.k] = Object.assign(COL[x.k] || {}, { c: x.c }); SAVE('js-ver-col', COL); changed(); return; }
  }, true);
  document.addEventListener('change', e => { const mi = e.target.closest('[data-vmi]'); if (!mi) return; const x = T[mi.dataset.vmi]; x.m = mi.value.trim() || x.m; COL[x.k] = Object.assign(COL[x.k] || {}, { m: x.m }); SAVE('js-ver-col', COL); toast('Meaning saved · ' + x.l); });
  document.addEventListener('keydown', e => { if (e.key === 'Escape') { closePanel(); const m = document.getElementById('vr-modal'); if (m) m.classList.remove('on'); } });

  window.Versions = {
    TYPES, T, list, srcOf, badges, chips, detail, albumSummary, noWords, lyricsIssue, copies, matched,
    sel, selCell, selBar, pass, facetHTML, words, toggleF, artistLine, metaHTML, qHTML, panelHTML, openPanel,
    get dir() { return dir; },
    on(f) { subs.push(f); },
    mountQ(el) { el.setAttribute('data-vqmount', ''); el.innerHTML = qHTML(); },
    mountMeta(el) { const p = () => { el.innerHTML = metaHTML(); }; subs.push(p); p(); },
  };
})();
