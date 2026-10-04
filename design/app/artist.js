/* artist.html — the Artist page (admin brief §C). ?ar=<artist id>. */
(function () {
  const M = window.MUSIC, $ = id => document.getElementById(id);
  const esc = s => (s == null ? '' : String(s)).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const LOCK = '<svg viewBox="0 0 11 12"><rect x="1" y="5" width="9" height="6.2" rx="1.6" fill="currentColor"/><path d="M3.1 5V3.5a2.4 2.4 0 0 1 4.8 0V5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>';
  const P = new URLSearchParams(location.search);
  const R = M.artist(P.get('ar')) || M.artist('harbour-lights');
  const Q = k => (window.MusicQ ? MusicQ.get(k) : null);
  let tab = P.get('tab') || 'overview', match = R.match, locked = false, hasImg = R.image, bio = R.bio, editing = false;
  const credited = M.albums.filter(a => a.artistId !== R.id && M.tracksOf(a.id).some(t => t.artistIds.indexOf(R.id) >= 0 || t.feat.indexOf(R.id) >= 0));
  const own = M.albumsOf(R.id);
  document.title = 'Jellystructure — ' + R.name;
  function toast(m) { const t = document.createElement('div'); t.className = 'toast'; t.textContent = m; document.body.appendChild(t); setTimeout(() => t.remove(), 2200); }
  const isM = () => match === 'matched';

  function fence() {
    const S = [['harbour-lights', 'Matched · bio · videos'], ['marta-vang', 'Person · bio'], ['kvold', 'No picture · no bio'], ['skerry', 'No picture'], ['disa', 'Unmatched · credited only']];
    $('ar-fence').innerHTML = '<div class="mu-fence"><span class="fl">Preview · mockup only</span><span class="seg">' + S.map(s => '<a href="artist.html?ar=' + s[0] + '" class="' + (R.id === s[0] ? 'on' : '') + '" style="color:inherit;text-decoration:none;">' + s[1] + '</a>').join('') + '</span></div>';
  }
  function bar() {
    const links = isM() ? [['MusicBrainz', 'musicbrainz.org/artist/' + R.mbid.slice(0, 8) + '…']].concat(R.bio ? [['Wikipedia', 'en.wikipedia.org'], ['Wikidata', 'wikidata.org']] : []).concat(R.id === 'harbour-lights' ? [['Official site', 'harbourlights.no'], ['Discogs', 'discogs.com']] : []) : [];
    $('ar-bar').innerHTML = '<h1>' + esc(R.name) + '</h1><span class="spacer"></span>'
      + '<span class="menu-wrap"><span class="btn sm ghost menu-btn">External links <span class="caret">▾</span></span><div class="menu"><a class="menu-item" href="#"><span class="mi-ic">↗</span><span>Open in Jellyfin<span class="mi-sub">Artist in the Jellyfin web UI</span></span></a>' + links.map(l => '<a class="menu-item" href="#"><span class="mi-ic">↗</span><span>' + l[0] + '<span class="mi-sub">' + l[1] + '</span></span></a>').join('') + '</div></span>'
      + '<span class="menu-wrap"><span class="btn sm ghost menu-btn">⋯</span><div class="menu">' + (isM() ? '<div class="menu-item" data-a="lock"><span class="mi-ic">🔒</span><span>' + (locked ? 'Unlock match' : 'Lock match') + '</span></div><div class="menu-item" data-a="clear"><span class="mi-ic">✕</span><span>Clear match</span></div>' : '') + '<div class="menu-item" data-a="find"><span class="mi-ic">⌕</span><span>' + (isM() ? 'Change match…' : 'Find match…') + '<span class="mi-sub">Search MusicBrainz artists</span></span></div></div></span>'
      + (window.FilesTab ? FilesTab.saveMenu({ kind: 'music', nfo: 'artist.nfo', where: 'artist', n: M.tracksBy(R.id).length, differ: M.tracksBy(R.id).length }) : '');
  }
  function head() {
    const facts = [R.type, R.country, R.span].filter(Boolean).join(' · ');
    const chip = isM() ? '<span class="mu-match' + (locked ? ' lk' : '') + '"><span class="l1">' + (locked ? LOCK + ' Locked' : '<span class="dot"></span>MusicBrainz · artist') + ' <a class="mono tiny" href="#" style="font-weight:500">' + R.mbid.slice(0, 8) + ' ▸</a></span></span>'
      : '<span class="mu-match w"><span class="l1"><span class="dot"></span>No MusicBrainz match</span><span class="l2">Credited on one compilation track only — nothing to match against yet</span></span><span class="btn primary" data-a="find">Find match…</span>';
    $('ar-head').innerHTML = '<div class="mu-pb"><div class="mu-circ" style="width:160px;' + (hasImg ? M.artistStyle(R) : M.wordmarkStyle(R.name)) + '">' + (hasImg ? '' : esc(M.initials(R.name)) + '<i class="mu-nocov"></i>') + '</div><div>'
      + '<div class="mu-mono tiny muted">' + esc(R.sort) + '</div>'
      + '<div class="mu-pbm">' + (facts ? '<span>' + esc(facts) + '</span>' : '') + (R.disamb ? '<span class="sep">·</span><span class="muted">' + esc(R.disamb) + '</span>' : '') + '</div>'
      + '<div class="mu-pbm"><span>' + own.length + (own.length === 1 ? ' album' : ' albums') + '</span><span class="sep">·</span><span>' + M.tracksBy(R.id).length + ' songs in the library</span>' + (credited.length ? '<span class="sep">·</span><span>credited on ' + credited.map(a => '<a href="album.html?a=' + a.id + '">' + esc(a.title) + '</a>').join(', ') + '</span>' : '') + '</div>'
      + (window.Versions ? Versions.artistLine(R.id) : '')
      + '<div class="mu-acts">' + chip + '</div>'
      + (!hasImg ? '<div class="tiny" style="margin-top:10px;color:var(--warn)">No artist picture — <a href="#" data-tabgo="artwork">choose one in Artwork</a>. MusicBrainz has none; they come from fanart.tv or Wikimedia Commons.</div>' : '')
      + '</div></div>';
    $('ar-crumb').innerHTML = '<a href="library.html?kind=music">Music</a> / <a href="library.html?kind=music&mview=artists">Artists</a> / ' + esc(R.name);
  }
  const GROUPS = [['Albums', a => a.type === 'album' || a.type === 'soundtrack'], ['Singles & EPs', a => (a.type === 'single' || a.type === 'ep') && !(window.Editions && Editions.home(a.id))], ['Compilations', a => a.type === 'compilation'], ['Live', a => a.type === 'live']];
  function cell(a) {
    return '<a class="mu-cell" href="album.html?a=' + a.id + '"><div class="mu-cov" style="' + (a.cover ? M.coverStyle(a) : M.wordmarkStyle(a.title)) + '">' + (a.cover ? '' : '<span class="mu-wm">' + esc(a.title) + '</span><i class="mu-nocov"></i>') + (a.match === 'needs' || a.match === 'unmatched' ? '<span class="mu-mchip w">' + (a.match === 'needs' ? 'needs you' : 'unmatched') + '</span>' : a.match === 'locked' ? '<span class="mu-mchip">' + LOCK + '</span>' : '') + '</div><div class="ttl">' + esc(a.title) + '</div><div class="yr">' + a.year + ' · ' + ((window.Editions && Editions.cellCount(a)) || a.trackIds.length + (a.trackIds.length === 1 ? ' song' : ' songs')) + '</div></a>';
  }
  function ghost(d) { return '<div class="mu-cell mu-ghost" title="On MusicBrainz, not in the library"><div class="mu-cov"><span class="mu-wm">' + esc(d[0]) + '</span></div><div class="ttl">' + esc(d[0]) + '</div><div class="yr">' + d[1] + ' · not in library</div></div>'; }
  function overview() {
    const disco = Q('disco') === 'greyed' && isM() ? (M.DISCO[R.id] || []) : [];
    // music editions (owner 2026-10-04, Q1): a single that found its album lives under it, not in Singles & EPs
    const under = window.Editions ? Editions.underOf(R.id) : [];
    const byHome = {}; under.forEach(s => { const h = Editions.home(s.id); (byHome[h] = byHome[h] || []).push(s); });
    const underNote = under.length ? '<div class="tiny muted" style="margin-top:10px">' + under.length + (under.length === 1 ? ' single lives' : ' singles live') + ' under ' + (Object.keys(byHome).length === 1 ? 'its album' : 'their albums') + ' — ' + Object.keys(byHome).map(h => '<a href="album.html?a=' + h + '">' + esc(M.album(h).title) + '</a>’s ' + byHome[h].length).join(' · ') + ' in Singles &amp; B-sides. Move one back from its album page.</div>' : '';
    const albums = GROUPS.map(g => { const own2 = own.filter(g[1]), gh = disco.filter(d => g[1]({ type: d[2] })), sg = g[0] === 'Singles & EPs'; if (!own2.length && !gh.length && !(sg && under.length)) return '';
      return '<div class="mu-sec">' + g[0] + ' <span class="tiny muted" style="text-transform:none;letter-spacing:0;font-weight:500">' + own2.length + (gh.length ? ' · ' + gh.length + ' more on MusicBrainz' : '') + '</span></div>' + (own2.length || gh.length ? '<div class="mu-grid">' + own2.map(cell).join('') + gh.map(ghost).join('') + '</div>' : '') + (sg ? underNote : ''); }).join('');
    const bioH = editing ? '<textarea id="ar-bio">' + esc(bio) + '</textarea><div class="row" style="gap:8px;margin-top:8px;"><span class="btn sm primary" data-a="biosave">Save → artist.nfo</span><span class="btn sm ghost" data-a="biocancel">Cancel</span></div>'
      : bio ? '<div class="mu-bio">' + esc(bio) + '</div><div class="tiny muted" style="margin-top:6px;">Source: ' + (R.bioSrc || 'written here') + ' · via MusicBrainz’s URL relationships · <a href="#" data-a="bioedit">Edit</a></div>'
      : '<div class="tiny muted">No biography found — MusicBrainz links this artist to no Wikipedia or Wikidata page. <a href="#" data-a="bioedit">Write one</a>; it is saved into <span class="mono">artist.nfo</span> as you save.</div>';
    const vids = M.VIDEOS[R.id] || [];
    return '<div class="mu-sec">Biography</div>' + bioH
      + (albums || '<div class="mu-sec">Albums</div><div class="tiny muted">No albums of their own in the library — credited on ' + credited.map(a => esc(a.title)).join(', ') + '.</div>')
      + (disco.length ? '<div class="tiny muted" style="margin-top:10px">Greyed: MusicBrainz knows them, the library doesn’t have them. Nothing here requests anything.</div>' : '')
      + (vids.length ? '<div class="mu-sec">Videos <span class="tiny muted" style="text-transform:none;letter-spacing:0;font-weight:500">from the Music videos library · matched by the filename’s artist · edited on their own pages</span></div><div class="mu-vgrid">' + vids.map(v => '<a class="mu-vt" href="media.html"><div class="im" style="' + M.coverStyle({ id: v.title, hue: (R.hue + 90) % 360 }) + '">' + esc(v.title) + '<span class="d">' + v.len + '</span></div><div class="cap">' + v.kind + ' · ' + v.year + '</div></a>').join('') + '</div>' : '');
  }
  function artwork() {
    const img = s => '<div class="im" style="' + s + '"></div>';
    const use = hasImg ? '<div class="mu-aw use"><div class="im" style="' + M.artistStyle(R) + ';border-radius:50%"></div><div class="cap"><b>folder.jpg</b><span class="d">thumb · 1:1 · 1000 px</span></div></div><div class="mu-aw use"><div class="im wide" style="' + M.coverStyle({ id: R.id + 'bg', hue: R.hue }) + '"></div><div class="cap"><b>backdrop.jpg</b><span class="d">16:9 · 1920 px</span></div></div>'
      : '<div class="mu-aw"><div class="im" style="display:flex;align-items:center;justify-content:center"><span class="tiny" style="color:var(--warn);font-weight:600">no folder.jpg</span></div><div class="cap"><b>thumb</b><span class="d">missing — a task</span></div></div>';
    const fan = hasImg || R.id === 'skerry' ? '<div class="mu-art">' + [0, 1].map(i => '<div class="mu-aw"><div class="im" style="' + M.artistStyle({ hue: (R.hue + i * 30) % 360 }) + '"></div><div class="cap"><b>artistthumb</b><span class="d">fanart.tv · 1000 px</span><span class="btn sm ghost" style="margin-left:auto" data-a="useimg">Use</span></div></div>').join('')
      + '<div class="mu-aw"><div class="im wide" style="' + M.coverStyle({ id: R.id + 'fb', hue: (R.hue + 20) % 360 }) + '"></div><div class="cap"><b>artistbackground</b><span class="d">1920 × 1080</span><span class="btn sm ghost" style="margin-left:auto" data-a="useimg">Use</span></div></div>'
      + '<div class="mu-aw"><div class="im logo">' + esc(R.name) + '</div><div class="cap"><b>hdmusiclogo</b><span class="d">transparent · light ink</span><span class="btn sm ghost" style="margin-left:auto" data-a="useimg">Use</span></div></div></div>'
      : '<div class="tiny muted">fanart.tv has nothing for this artist.</div>';
    const commons = R.id === 'harbour-lights' || R.id === 'kvold' ? '<div class="mu-art"><div class="mu-aw"><div class="im" style="' + M.artistStyle({ hue: (R.hue + 140) % 360 }) + '"></div><div class="cap"><b>' + (R.id === 'kvold' ? 'Kvøld at G! 2008.jpg' : 'Harbour Lights 2011.jpg') + '</b><span class="btn sm ghost" style="margin-left:auto" data-a="useimg">Use</span></div><div class="tiny muted" style="margin-top:4px;line-height:1.45">CC BY-SA 4.0 · photographer credited in artist.nfo — the licence line travels with the file</div></div></div>' : '<div class="tiny muted">No image on Wikimedia Commons for this artist.</div>';
    return '<div class="mu-sec">Currently in use</div><div class="mu-art">' + use + '</div><div class="mu-sec">Candidates · fanart.tv</div>' + fan + '<div class="mu-sec">Candidates · Wikimedia Commons</div>' + commons;
  }
  function genres() {
    const m = {}; own.forEach(a => a.genres.forEach(g => { m[g.name] = (m[g.name] || 0) + g.votes; }));
    const list = Object.entries(m).sort((a, b) => b[1] - a[1]);
    return '<div class="mu-sec">MusicBrainz genres · votes on the artist</div>' + (list.length ? '<div class="mu-gchips">' + list.map((g, i) => '<span class="mu-gc' + (i < 3 && g[1] >= 3 ? ' on' : '') + '"><span class="bx">' + (i < 3 && g[1] >= 3 ? '✓' : '') + '</span>' + esc(g[0]) + '<span class="v">' + g[1] + '</span></span>').join('') + '</div>' : '<div class="tiny muted">None yet.</div>');
  }
  function nfo() {
    const L = ['<?xml version="1.0" encoding="utf-8" standalone="yes"?>', '<artist>', '  <name>' + esc(R.name) + '</name>', '  <sortname>' + esc(R.sort) + '</sortname>'];
    if (isM()) L.push('  <musicbrainzartistid>' + R.mbid + '</musicbrainzartistid>', '  <type>' + R.type + '</type>', R.span ? '  <formed>' + esc(R.span) + '</formed>' : '', R.disamb ? '  <disambiguation>' + esc(R.disamb) + '</disambiguation>' : '');
    if (bio) L.push('  <biography>' + esc(bio.slice(0, 90)) + '…</biography>');
    own.forEach(a => L.push('  <album>', '    <title>' + esc(a.title) + '</title>', '    <year>' + a.year + '</year>', '  </album>'));
    L.push('</artist>');
    return '<div class="row center" style="margin-bottom:10px;gap:8px"><span class="tiny muted mono">/mnt/media/jellyfin/music/' + esc(R.name) + '/artist.nfo</span></div><div class="mu-nfo">' + L.filter(Boolean).join('\n') + '</div>';
  }
  function hist() {
    const H = [['2026-09-23 21:40', 'Scanned from Jellyfin'], isM() ? ['2026-09-27 03:02', 'match_musicbrainz · matched through ' + (own[0] ? own[0].title : 'a release') + '’s album artist'] : ['2026-09-27 03:02', 'match_musicbrainz · credited only, not matched'], hasImg ? ['2026-09-27 03:03', 'fetch_music_artwork · thumb + background from fanart.tv'] : ['2026-09-27 03:03', 'fetch_music_artwork · no image on fanart.tv or Commons — flagged']];
    return '<div class="mu-hist">' + H.reverse().map(h => '<div class="mu-hi"><span class="ts">' + h[0] + '</span><span>' + esc(h[1]) + '</span></div>').join('') + '</div>';
  }
  function panel() {
    document.querySelectorAll('#tabbar [data-tab]').forEach(t => t.classList.toggle('on', t.dataset.tab === tab));
    $('ar-panel').innerHTML = tab === 'artwork' ? artwork() : tab === 'genres' ? genres() : tab === 'nfo' ? nfo() : tab === 'history' ? hist() : overview();
  }
  function repaint() { bar(); head(); panel(); }
  if (window.Editions) Editions.on(panel);
  document.addEventListener('click', e => {
    const mw = e.target.closest('.menu-wrap .menu-btn, .split .menu-btn');
    document.querySelectorAll('.menu-wrap.open, .split.open').forEach(o => { if (!mw || o !== mw.parentNode) o.classList.remove('open'); });
    if (mw) { mw.parentNode.classList.toggle('open'); return; }
    const t = e.target.closest('#tabbar [data-tab]'); if (t) { tab = t.dataset.tab; panel(); return; }
    const tg = e.target.closest('[data-tabgo]'); if (tg) { e.preventDefault(); tab = tg.dataset.tabgo; panel(); return; }
    const a = e.target.closest('[data-a]'); if (!a) return; e.preventDefault();
    const k = a.dataset.a;
    if (k === 'bioedit') { editing = true; panel(); }
    else if (k === 'biocancel') { editing = false; panel(); }
    else if (k === 'biosave') { bio = $('ar-bio').value.trim(); editing = false; panel(); toast('artist.nfo written · Jellyfin re-reading'); }
    else if (k === 'useimg') { hasImg = true; repaint(); toast('folder.jpg written'); }
    else if (k === 'lock') { locked = !locked; repaint(); }
    else if (k === 'clear') { match = 'unmatched'; repaint(); toast('Match cleared'); }
    else if (k === 'find') toast('Artist search opens the same Find match… panel as an album, over MusicBrainz artists');
    else if (k === 'save') toast('artist.nfo written');
    else if (k === 'savesync') toast(FilesTab.writeOn('music') ? 'artist.nfo + tags in ' + M.tracksBy(R.id).length + ' files written · Jellyfin re-reading ↻' : 'artist.nfo written · Jellyfin re-reading ↻');
    else if (k === 'savefiles') toast(e.target.closest('[data-off]') ? 'Tag writing is off in Settings → Music providers' : 'Artist name, sort name and MusicBrainz artist id written into ' + M.tracksBy(R.id).length + ' files');
    else if (k === 'sync') toast('Sync requested ↻');
  });
  if (window.MusicQ) { MusicQ.mount($('ar-qs'), 'artist'); MusicQ.on(repaint); }
  if (window.Versions) Versions.on(repaint);
  fence(); repaint();
})();
