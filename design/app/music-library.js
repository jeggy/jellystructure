/* Library → Music (admin brief §A). A fourth kind beside Movies · TV · Music videos, with its own three
   views (Albums · Artists · Songs), music facets in the workbench's idiom, a selection with bulk actions,
   and the five states. Renders into #mu-lib; library.html shows it when the Music kind is picked. */
(function () {
  const M = window.MUSIC, root = document.getElementById('mu-lib');
  if (!M || !root) return;
  const esc = s => (s == null ? '' : String(s)).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const LOCK = '<svg viewBox="0 0 11 12"><rect x="1" y="5" width="9" height="6.2" rx="1.6" fill="currentColor"/><path d="M3.1 5V3.5a2.4 2.4 0 0 1 4.8 0V5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>';
  const LYR = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 6h12M4 11h9M4 16h6"/><circle cx="17.5" cy="16.5" r="2.5"/><path d="M20 16.5V8l-2 1"/></svg>';
  let view = 'artists', state = 'partly', q = '', sel = new Set(), facets = {}, openF = null;
  const V = !!window.Versions, vf = { inc: new Set(), exc: new Set() };   // song versions (brief 2026-10-01): include / exclude
  let byArtist = null;
  try { const u = new URLSearchParams(location.search); if (u.get('mview')) view = u.get('mview'); if (u.get('mstate')) state = u.get('mstate');
    if (u.get('vi')) u.get('vi').split(',').forEach(k => vf.inc.add(k)); if (u.get('vx')) u.get('vx').split(',').forEach(k => vf.exc.add(k));
    if (u.get('artist')) byArtist = u.get('artist'); if (u.get('lyr')) facets.lyrics = new Set([u.get('lyr')]); } catch (e) {}

  // the page's truth depends on the preview state: "everything unmatched" is the first real state of this household's library
  const matchOf = a => state === 'unmatched' ? 'unmatched' : a.match;
  const coverOf = a => state === 'unmatched' ? a.id === 'salt-on-the-window' : a.cover;
  const artImg = r => state === 'unmatched' ? (r.id === 'harbour-lights' || r.id === 'marta-vang') : r.image;
  const artMatch = r => state === 'unmatched' ? 'unmatched' : r.match;
  const recOf = t => state === 'unmatched' ? 'none' : t.rec;
  const lyrOf = t => state === 'unmatched' ? null : t.lyrics;
  const matchLabel = { matched: 'matched', unmatched: 'unmatched', locked: 'locked', needs: 'needs you' };

  const FACETS = [
    { k: 'match', l: 'Match', vals: () => ['matched', 'unmatched', 'locked', 'needs'].map(v => [v, matchLabel[v]]) },
    { k: 'cover', l: 'Cover', vals: () => [['has', 'has a cover'], ['missing', 'missing']] },
    { k: 'artimg', l: 'Artist image', vals: () => [['has', 'has a picture'], ['missing', 'missing']] },
    { k: 'format', l: 'Format', vals: () => [['MP3', 'MP3'], ['WMA', 'WMA', 'plays on a phone only by re-encoding'], ['FLAC', 'FLAC'], ['AAC', 'AAC'], ['other', 'other']] },
    { k: 'genre', l: 'Genre', vals: () => M.genres().map(g => [g.name, g.name]) },
    { k: 'decade', l: 'Decade', vals: () => [['1990', '1990s'], ['2000', '2000s'], ['2010', '2010s']] },
    { k: 'type', l: 'Album type', vals: () => [['album', 'album'], ['single', 'single / EP'], ['compilation', 'compilation'], ['live', 'live'], ['soundtrack', 'soundtrack']] },
    { k: 'lyrics', l: 'Lyrics', vals: () => [['has', 'has lyrics'], ['missing', 'missing']] },
    { k: 'lib', l: 'Library', vals: () => [['Musik', 'Musik']] },
  ];
  const on = (k, v) => facets[k] && facets[k].has(v);
  const any = k => facets[k] && facets[k].size;
  function albumPass(a, skip) {
    if (skip !== 'match' && any('match') && !on('match', matchOf(a))) return false;
    if (skip !== 'cover' && any('cover') && !on('cover', coverOf(a) ? 'has' : 'missing')) return false;
    if (skip !== 'genre' && any('genre') && !a.genres.some(g => on('genre', g.name))) return false;
    if (skip !== 'decade' && any('decade') && !on('decade', String(Math.floor(a.year / 10) * 10))) return false;
    if (skip !== 'type' && any('type') && !on('type', a.type === 'ep' ? 'single' : a.type)) return false;
    const ts = M.tracksOf(a.id);
    if (skip !== 'format' && any('format') && !ts.some(t => on('format', t.codec))) return false;
    if (skip !== 'lyrics' && any('lyrics') && !ts.some(t => on('lyrics', lyrOf(t) ? 'has' : 'missing'))) return false;
    if (skip !== 'artimg' && any('artimg') && !on('artimg', artImg(M.artist(a.artistId)) ? 'has' : 'missing')) return false;
    return true;
  }
  function trackPass(t, skip) {
    // album-level facets decide through the album; format and lyrics are checked on the track itself
    const a = M.album(t.albumId);
    const keep = facets; facets = Object.assign({}, keep); delete facets.format; delete facets.lyrics;
    const ok = albumPass(a, skip); facets = keep;
    if (!ok) return false;
    if (skip !== 'format' && any('format') && !on('format', t.codec)) return false;
    if (skip !== 'lyrics' && any('lyrics') && !on('lyrics', lyrOf(t) ? 'has' : 'missing')) return false;
    if (V && skip !== 'version' && !Versions.pass(t, vf)) return false;
    if (byArtist && t.artistIds.indexOf(byArtist) < 0 && t.feat.indexOf(byArtist) < 0) return false;
    return true;
  }
  function artistPass(r, skip) {
    if (skip !== 'artimg' && any('artimg') && !on('artimg', artImg(r) ? 'has' : 'missing')) return false;
    const keep = facets; facets = Object.assign({}, keep); delete facets.artimg;
    const mine = M.albums.filter(a => a.artistId === r.id || M.tracksOf(a.id).some(t => t.artistIds.indexOf(r.id) >= 0 || t.feat.indexOf(r.id) >= 0));
    let ok;
    if (facets.match && facets.match.size) { const m = facets.match; delete facets.match; ok = m.has(artMatch(r)) && (mine.length === 0 || mine.some(a => albumPass(a, skip))); facets.match = m; }
    else ok = mine.some(a => albumPass(a, skip));
    facets = keep; return ok;
  }
  const qHit = s => !q || String(s).toLowerCase().indexOf(q.toLowerCase()) >= 0;
  const albumsShown = () => M.albums.filter(a => albumPass(a) && (qHit(a.title) || qHit(M.artist(a.artistId).name)));
  const artistsShown = () => M.artists.filter(r => artistPass(r) && qHit(r.name));
  const songsShown = () => M.tracks.filter(t => trackPass(t) && (qHit(t.title) || qHit(M.artistNames(t.artistIds)) || qHit(M.album(t.albumId).title)));
  function countFor(k, v) {
    const keep = facets; facets = Object.assign({}, keep); facets[k] = new Set([v]);
    let n;
    if (view === 'artists') n = M.artists.filter(r => artistPass(r)).length;
    else if (view === 'songs') n = M.tracks.filter(t => trackPass(t)).length;
    else n = M.albums.filter(a => albumPass(a)).length;
    facets = keep; return n;
  }

  function chip(m) {
    if (m === 'unmatched') return '<span class="mu-mchip w">unmatched</span>';
    if (m === 'needs') return '<span class="mu-mchip w">needs you</span>';
    if (m === 'locked') return '<span class="mu-mchip" title="Locked · won’t be re-matched">' + LOCK + '</span>';
    return '';
  }
  function albumCell(a) {
    const has = coverOf(a), ts = M.tracksOf(a.id);
    return '<a class="mu-cell' + (sel.has(a.id) ? ' on' : '') + '" href="album.html?a=' + a.id + '" data-id="' + a.id + '">'
      + '<span class="mu-sel" data-sel="' + a.id + '">✓</span>'
      + '<div class="mu-cov" style="' + (has ? M.coverStyle(a) : M.wordmarkStyle(a.title)) + '">' + (has ? '' : '<span class="mu-wm">' + esc(a.title) + '</span><i class="mu-nocov" title="No cover on disk — a task on this side"></i>') + chip(matchOf(a)) + '</div>'
      + '<div class="ttl">' + esc(a.title) + '</div><div class="sub">' + esc(M.artist(a.artistId).name) + '</div>'
      + '<div class="yr">' + a.year + ' · ' + (ts.length === 1 ? '1 song' : ts.length + ' songs') + '</div></a>';
  }
  function artistCell(r) {
    const has = artImg(r), nAl = M.albumsOf(r.id).length, nS = M.tracksBy(r.id).length;
    return '<a class="mu-cell" href="artist.html?ar=' + r.id + '" data-id="' + r.id + '">'
      + '<div class="mu-circ" style="' + (has ? M.artistStyle(r) : M.wordmarkStyle(r.name)) + '">' + (has ? '' : esc(M.initials(r.name)) + '<i class="mu-nocov" title="No picture"></i>') + chip(artMatch(r)) + '</div>'
      + '<div class="ttl">' + esc(r.name) + '</div><div class="yr">' + (nAl ? nAl + (nAl === 1 ? ' album' : ' albums') + ' · ' : 'credited · ') + nS + (nS === 1 ? ' song' : ' songs') + '</div></a>';
  }
  function fmtCell(t) {
    const w = M.reencodes(t);
    return '<span class="mu-fmt' + (w ? ' w' : '') + '">' + t.codec + ' · ' + t.kbps + (w ? '<span class="re">re-encodes on a phone</span>' : '') + '</span>';
  }
  function lyrCell(t) { const l = lyrOf(t); return l ? '<span class="mu-ly" title="' + (l === 'synced' ? 'Synced lyrics' : 'Plain lyrics') + '">' + LYR + (l === 'synced' ? 'synced' : 'plain') + '</span>' : '<span class="mu-ly no">—</span>'; }
  function recCell(t) {
    const r = recOf(t);
    return r === 'ok' ? '<span class="mu-mt ok">✓</span>' : r === 'other' ? '<span class="mu-mt w">different release</span>' : '<span class="mu-mt w">unmatched</span>';
  }
  function songsTable(list) {
    return '<div class="mu-scroll"><table class="mu-tbl' + (V && list.some(t => Versions.sel.has(t.id)) ? ' vr-selecting' : '') + '"><thead><tr><th>#</th><th>Title</th><th>Artist</th><th>Album</th><th>Length</th><th>Format</th><th>Lyrics</th><th>Match</th></tr></thead><tbody>'
      + list.map(t => { const a = M.album(t.albumId); return '<tr class="' + (V && Versions.sel.has(t.id) ? 'vr-on' : '') + '"><td class="n">' + (V ? Versions.selCell(t) : t.n) + '</td><td><a href="album.html?a=' + a.id + '">' + esc(t.title) + '</a>' + (V ? Versions.badges(t) : '') + (t.feat.length ? ' <span class="dim tiny">feat. ' + esc(M.artistNames(t.feat)) + '</span>' : '') + (V ? Versions.detail(t) : '') + '</td>'
        + '<td class="dim">' + t.artistIds.map(id => '<a class="dim" href="artist.html?ar=' + id + '">' + esc(M.artist(id).name) + '</a>').join(' & ') + '</td><td class="dim"><a class="dim" href="album.html?a=' + a.id + '">' + esc(a.title) + '</a></td>'
        + '<td class="num">' + M.fmtLen(t.len) + '</td><td>' + fmtCell(t) + '</td><td>' + lyrCell(t) + '</td><td>' + recCell(t) + '</td></tr>'; }).join('')
      + '</tbody></table></div>';
  }

  function statusLine() {
    if (state === 'empty' || state === 'unscanned') return '';
    const n = M.albums.length, m = M.albums.filter(a => ['matched', 'locked'].indexOf(matchOf(a)) >= 0).length, need = n - m;
    const nc = M.albums.filter(a => !coverOf(a)).length;
    return '<span class="mu-status"><b>' + n + '</b> albums · <b>' + m + '</b> matched' + (need ? ' · <b class="w">' + need + '</b> <span class="w">need you</span>' : '') + ' · ' + M.tracks.length + ' songs · ' + M.artists.length + ' artists'
      + (nc ? ' · <span class="w">' + nc + ' without a cover</span>' : '') + '</span>'
      + (need ? '<span class="btn sm primary" data-act="matchall">Match now</span>' : '');
  }
  function facetBar() {
    return '<div class="mu-facets"><span class="muted tiny">filter:</span>' + (V && view === 'songs' ? Versions.facetHTML(vf, openF === 'version', k => M.tracks.filter(t => trackPass(t, 'version') && (k === 'none' ? !Versions.list(t).length : Versions.list(t).indexOf(k) >= 0)).length) : '') + FACETS.map(f => {
      const n = facets[f.k] ? facets[f.k].size : 0;
      return '<span class="mu-fc' + (openF === f.k ? ' open' : '') + '"><span class="mu-fbtn' + (n ? ' on' : '') + '" data-fopen="' + f.k + '">' + f.l + (n ? ' <span class="c">' + n + '</span>' : '') + ' ▾</span>'
        + '<div class="mu-fpop">' + f.vals().map(v => { const c = countFor(f.k, v[0]);
          return '<div class="mu-fv' + (on(f.k, v[0]) ? ' on' : '') + (c ? '' : ' zero') + '" data-fk="' + f.k + '" data-fv="' + esc(v[0]) + '"><span class="bx">' + (on(f.k, v[0]) ? '✓' : '') + '</span><span>' + esc(v[1]) + (v[2] ? '<span class="nt">' + v[2] + '</span>' : '') + '</span><span class="ct">' + c + '</span></div>'; }).join('')
        + '</div></span>';
    }).join('') + '<span class="spacer" style="flex:1"></span><span class="select" style="width:auto;min-width:150px;"><span>sort: ' + (view === 'songs' ? 'album, position' : view === 'artists' ? 'name' : 'recently added') + '</span></span></div>';
  }
  function activeBar(n) {
    const act = FACETS.filter(f => any(f.k));
    const vOn = V && view === 'songs' && (vf.inc.size || vf.exc.size || byArtist);
    const words = vOn ? '<span class="vr-words">' + Versions.words(vf, byArtist && M.artist(byArtist).name) + '<span class="rm" data-vfclr title="Clear">✕</span></span>' + (act.length ? ' <span class="tiny muted">and</span> ' : '') : '';
    if (!act.length && !vOn) return '';
    return words + act.map(f => '<span class="fxchip">' + f.l + ' is ' + [...facets[f.k]].map(v => (f.vals().find(x => x[0] === v) || [v, v])[1]).join(' or ') + ' <span class="rm" data-frm="' + f.k + '">✕</span></span>').join(' <span class="tiny muted">and</span> ')
      + '<span class="livecount" style="margin-left:6px;"><span class="n">' + n + '</span><span class="tiny muted">' + (view === 'songs' ? 'songs' : view) + ' match</span></span><span class="tiny" style="margin-left:6px;cursor:pointer;color:var(--ink-soft);" data-fclear>clear all</span>';
  }
  function fence() {
    const S = [['partly', 'Partly matched'], ['unmatched', 'Everything unmatched'], ['unscanned', 'Mapped, not yet scanned'], ['empty', 'Empty library']];
    return '<div class="mu-fence"><span class="fl">Preview · mockup only</span><span class="seg" id="mu-state">' + S.map(s => '<span data-mstate="' + s[0] + '" class="' + (state === s[0] ? 'on' : '') + '">' + s[1] + '</span>').join('') + '</span>'
      + '<span class="tiny muted">“Everything unmatched” is this household’s first real state. A search with no results: type in the search box.</span></div>';
  }
  function body() {
    if (state === 'empty') return '<div class="mu-empty"><h3>Nothing filed as music yet</h3><p>The <b>Musik</b> library is mapped but Jellyfin has no albums in it. Put folders under <span class="mono">/media/music</span> as <span class="mono">Artist/Album/track</span> and scan.</p><a class="btn sm" href="settings.html?tab=libraries#mu-libcard">Library card ›</a></div>';
    if (state === 'unscanned') return '<div class="mu-empty"><h3>Mapped — not scanned yet</h3><p><span class="mono">/media/music → /mnt/media/jellyfin/music</span>. The first scan reads what Jellyfin has filed (<b>60 tracks · 30 albums · 23 artists</b>), then matches each album against MusicBrainz at one request a second.</p><span class="btn sm primary" data-act="scan">Scan now</span> <a class="btn sm ghost" href="settings.html?tab=libraries#mu-libcard">Library card ›</a></div>';
    let list, html;
    if (view === 'artists') { list = artistsShown(); html = '<div class="mu-agrid">' + list.map(artistCell).join('') + '</div>'; }
    else if (view === 'songs') { list = songsShown(); html = songsTable(list); }
    else { list = albumsShown(); html = '<div class="mu-grid' + (sel.size ? ' selecting' : '') + '">' + list.map(albumCell).join('') + '</div>'; }
    if (!list.length) return (q ? '<div class="muted" style="padding:40px 4px;">Nothing in the music library matches “' + esc(q) + '”.</div>' : '<div class="muted" style="padding:40px 4px;">No ' + view + ' match these filters.</div>');
    return html;
  }
  function selBar() {
    return '<div class="mu-selbar' + (sel.size && view === 'albums' ? ' on' : '') + '"><b>' + sel.size + '</b> selected'
      + ['match:Match now', 'covers:Fetch covers', 'nfo:Write NFOs', 'tags:Write tags…', 'lock:Lock match', 'clear:Clear match'].map(x => { const [k, l] = x.split(':'); return '<span class="btn sm' + (k === 'match' ? ' primary' : k === 'clear' ? ' ghost' : '') + '" data-bulk="' + k + '">' + l + '</span>'; }).join('')
      + '<span class="spacer" style="flex:1"></span><span class="tiny" style="cursor:pointer;color:var(--ink-soft);" data-bulk="all">select all shown</span><span class="tiny" style="cursor:pointer;color:var(--ink-soft);" data-bulk="none">✕ clear</span></div>'
      + (tagAsk && sel.size && view === 'albums' ? tagConfirm() : '');
  }
  // prospective 284 §7.4 — the skip reasons as counts before confirming. Seeding is 0 today (§1: no music is in qBittorrent).
  let tagAsk = false;
  function tagConfirm() {
    const T = [...sel].flatMap(id => M.tracksOf(id)), un = T.filter(t => { const a = M.album(t.albumId); return a.match !== 'matched' && a.match !== 'locked'; }).length;
    const w = T.filter(t => M.reencodes(t)).length, write = T.length - un, seed = 0;
    const off = localStorage.getItem('js-mu-tagwrite') === '0';
    return '<div class="mu-bulkc"><b>Write tags to ' + write + ' song' + (write === 1 ? '' : 's') + '?</b><span class="muted">' + T.length + ' selected · ' + un + ' unmatched (skipped — nothing to write yet) · ' + seed + ' seeding · ' + w + ' WMA (tagged; Jellyfin won’t read their ids)</span><span class="spacer" style="flex:1"></span>'
      + (off ? '<span class="tiny" style="color:var(--warn)">Tag writing is off in Settings → Music providers</span>' : '') + '<span class="btn sm ghost" data-bulk="tagno">Cancel</span><span class="btn sm primary" data-bulk="taggo"' + (off || !write ? ' aria-disabled="true" style="opacity:.45"' : '') + '>Write ' + write + '</span></div>';
  }
  function render() {
    const n = view === 'artists' ? artistsShown().length : view === 'songs' ? songsShown().length : albumsShown().length;
    const live = state !== 'empty' && state !== 'unscanned';
    root.innerHTML = '<div class="mu-top"><span class="seg" id="mu-view">' + ['artists:Artists', 'albums:Albums', 'songs:Songs'].map(x => { const [k, l] = x.split(':'); return '<span data-mview="' + k + '" class="' + (view === k ? 'on' : '') + '">' + l + '</span>'; }).join('') + '</span>' + statusLine() + '</div>'
      + '<p class="page-sub" style="margin-top:0">Albums, artists and songs from Jellyfin’s <b>Musik</b> library, matched against <b>MusicBrainz</b> — what this page maintains is written into the files’ own tags first (Picard’s vocabulary) and <span class="mono">album.nfo</span> / <span class="mono">artist.nfo</span> second — the file is the record. Not the <b>Music videos</b> library: those are films and stay under their own kind.</p>'
      + fence() + (live ? facetBar() + '<div class="mu-active">' + activeBar(n) + '</div>' + selBar() + (V && view === 'songs' ? Versions.selBar(songsShown().map(t => t.id)) : '') : '') + body() + (V && view === 'songs' && live ? Versions.qHTML() : '');
    if (window.MusicQ && !document.getElementById('mu-qs-lib')) { const d = document.createElement('div'); d.id = 'mu-qs-lib'; root.after(d); MusicQ.mount(d, 'library'); }
    const qp = document.getElementById('mu-qs-lib'); if (qp) qp.hidden = root.hidden;
  }
  function toast(m) { (window.__toast || (x => alert(x)))(m); }

  root.addEventListener('click', e => {
    const s = e.target.closest('[data-sel]');
    if (s) { e.preventDefault(); e.stopPropagation(); const id = s.dataset.sel; sel.has(id) ? sel.delete(id) : sel.add(id); render(); return; }
    const cell = e.target.closest('.mu-grid.selecting .mu-cell');
    if (cell && !e.metaKey) { e.preventDefault(); const id = cell.dataset.id; sel.has(id) ? sel.delete(id) : sel.add(id); render(); return; }
    const v = e.target.closest('[data-mview]'); if (v) { view = v.dataset.mview; sel.clear(); openF = null; render(); return; }
    const ms = e.target.closest('[data-mstate]'); if (ms) { state = ms.dataset.mstate; sel.clear(); facets = {}; render(); return; }
    const fo = e.target.closest('[data-fopen]'); if (fo) { openF = openF === fo.dataset.fopen ? null : fo.dataset.fopen; render(); return; }
    const fv = e.target.closest('[data-fk]'); if (fv) { const k = fv.dataset.fk, val = fv.dataset.fv; facets[k] = facets[k] || new Set(); facets[k].has(val) ? facets[k].delete(val) : facets[k].add(val); if (!facets[k].size) delete facets[k]; render(); return; }
    const fr = e.target.closest('[data-frm]'); if (fr) { delete facets[fr.dataset.frm]; render(); return; }
    const vi = e.target.closest('[data-vfi],[data-vfx]'); if (vi) { Versions.toggleF(vf, vi.dataset.vfi || vi.dataset.vfx, vi.dataset.vfi ? 'i' : 'x'); render(); return; }
    if (e.target.closest('[data-vfclr]')) { vf.inc.clear(); vf.exc.clear(); byArtist = null; render(); return; }
    if (e.target.closest('[data-fclear]')) { facets = {}; vf.inc.clear(); vf.exc.clear(); byArtist = null; render(); return; }
    const b = e.target.closest('[data-bulk]');
    if (b) {
      const k = b.dataset.bulk, n = sel.size;
      if (k === 'all') { albumsShown().forEach(a => sel.add(a.id)); render(); return; }
      if (k === 'none') { sel.clear(); tagAsk = false; render(); return; }
      if (k === 'tags') { tagAsk = true; render(); return; }
      if (k === 'tagno') { tagAsk = false; render(); return; }
      if (k === 'taggo') { if (b.getAttribute('aria-disabled')) return; tagAsk = false; toast('write_tags queued · ' + n + ' album' + (n > 1 ? 's' : '') + ' · Jobs & workers'); sel.clear(); render(); return; }
      const msg = { match: 'Matching ' + n + ' album' + (n > 1 ? 's' : '') + ' against MusicBrainz · one request a second — about ' + Math.max(2, n * 4) + ' s', covers: 'Fetching covers for ' + n + ' from the Cover Art Archive', nfo: 'Writing album.nfo for ' + n + ' · then Jellyfin re-reads them', lock: n + ' match' + (n > 1 ? 'es' : '') + ' locked · won’t be re-matched', clear: n + ' match' + (n > 1 ? 'es' : '') + ' cleared · the fields it filled stay until the next run' }[k];
      toast(msg); sel.clear(); render(); return;
    }
    const a = e.target.closest('[data-act]');
    if (a && a.dataset.act === 'matchall') {
      if (state === 'unmatched') { toast('Matching 30 albums · MusicBrainz allows one request a second — about 2 minutes'); a.textContent = 'Matching… 0 of 30'; let i = 0; const t = setInterval(() => { i += 3; a.textContent = 'Matching… ' + Math.min(30, i) + ' of 30'; if (i >= 30) { clearInterval(t); state = 'partly'; render(); toast('27 matched · 3 need you · 26 covers fetched'); } }, 220); }
      else toast('Running the ladder on the 3 albums that need you — 2 still need a pick, open them from the dock');
      return;
    }
    if (a && a.dataset.act === 'scan') { toast('Scanning Musik · 60 tracks'); setTimeout(() => { state = 'unmatched'; render(); }, 700); }
  });
  document.addEventListener('click', e => { if (openF && !e.target.closest('.mu-fc')) { openF = null; if (!root.hidden) render(); } });
  if (V) Versions.on(() => { if (!root.hidden) render(); });

  window.MusicLib = {
    show(on) { root.hidden = !on; const qp = document.getElementById('mu-qs-lib'); if (qp) qp.hidden = !on; if (on) render(); },
    search(s) { q = s || ''; if (!root.hidden) render(); },
  };
})();
