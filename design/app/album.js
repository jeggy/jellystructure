/* album.html — the Album page (admin brief §B): pagebar, Find match…, five tabs, the states.
   ?a=<album id> picks the album; the fence links one album per state. */
(function () {
  const M = window.MUSIC, $ = id => document.getElementById(id);
  const esc = s => (s == null ? '' : String(s)).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const LOCK = '<svg viewBox="0 0 11 12"><rect x="1" y="5" width="9" height="6.2" rx="1.6" fill="currentColor"/><path d="M3.1 5V3.5a2.4 2.4 0 0 1 4.8 0V5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>';
  const LYR = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 6h12M4 11h9M4 16h6"/><circle cx="17.5" cy="16.5" r="2.5"/><path d="M20 16.5V8l-2 1"/></svg>';
  const P = new URLSearchParams(location.search);
  const A = M.album(P.get('a')) || M.album('glass-birds');
  const artist = M.artist(A.artistId);
  const ts = M.tracksOf(A.id);
  const Q = k => (window.MusicQ ? MusicQ.get(k) : null);
  let match = A.match, locked = A.match === 'locked', hasCover = A.cover, tab = P.get('tab') || 'tracks', playing = null, acoust = true;
  let chosen = null, chosenRel = 0, searching = false, recOpen = null, driftOn = A.id === 'salt-on-the-window';
  const lyr = {}; ts.forEach(t => lyr[t.id] = t.lyrics);
  const genresOn = new Set(A.genres.filter((g, i) => g.votes >= 3 && g.votes >= A.genres[0].votes * 0.1 && i < 4).map(g => g.name));
  const isMatched = () => match === 'matched' || match === 'locked';
  const wma = ts.filter(M.reencodes);
  // prospective 284 — the Files tab's view of this album (stand-in values; ids shortened for the grid)
  const tagSpec = () => ({ kind: 'album', title: A.title, artist: artist.name, total: A.total || ts.length, rel: isMatched() ? A.relMbid.slice(0, 8) + '…' : '', cover: hasCover,
    tracks: ts.map(t => ({ id: t.id, n: t.n, title: t.title, artist: M.artistNames(t.artistIds), codec: t.codec, rec: isMatched() ? M.mbid('rec' + t.id).slice(0, 8) + '…' : '', gain: t.gain.toFixed(2) + ' dB', lyrics: lyr[t.id] })) });
  const TQ = () => !!window.FilesTab;
  const V = !!window.Versions;   // the song-versions brief (2026-10-01), round 1
  document.title = 'Jellystructure — ' + A.title;

  function toast(m) { const t = document.createElement('div'); t.className = 'toast'; t.textContent = m; document.body.appendChild(t); setTimeout(() => t.remove(), 2200); }
  const artistLinks = ids => ids.map(id => { const r = M.artist(id); return r.id === 'various' ? esc(r.name) : '<a href="artist.html?ar=' + r.id + '">' + esc(r.name) + '</a>'; }).join(' &amp; ');
  const coverHTML = (cls, st) => '<div class="' + cls + '" style="' + (hasCover ? M.coverStyle(A) : M.wordmarkStyle(A.title)) + (st || '') + '">' + (hasCover ? '' : '<span class="mu-wm">' + esc(A.title) + '</span><i class="mu-nocov"></i>') + '</div>';

  function fence() {
    const S = [['glass-birds', 'Matched · partial · WMA'], ['salt-on-the-window', 'Full album · MP3 · drift'], ['low-tide-radio', 'Needs you'], ['summer-hits-2004', 'Compilation needs you'], ['kvold', 'Unmatched'], ['myrkrid-og-ljosid', 'Locked'], ['foghorn-lullabies', 'No cover after a match'], ['live-at-the-harbour', 'Versions · live album'], ['tide-tables', 'Versions · box set']];
    $('al-fence').innerHTML = '<div class="mu-fence"><span class="fl">Preview · mockup only</span><span class="seg">' + S.map(s => '<a href="album.html?a=' + s[0] + '" class="' + (A.id === s[0] ? 'on' : '') + '" style="color:inherit;text-decoration:none;">' + s[1] + '</a>').join('') + '</span>'
      + '<span class="tiny muted">AcoustID key</span><span class="seg" id="al-ak"><span data-ak="1" class="' + (acoust ? 'on' : '') + '">set</span><span data-ak="0" class="' + (acoust ? '' : 'on') + '">not set</span></span></div>';
  }
  function bar() {
    const mbLink = isMatched() ? '<a class="menu-item" href="#" target="_blank" rel="noopener"><span class="mi-ic">↗</span><span>MusicBrainz · release-group<span class="mi-sub mono">musicbrainz.org/release-group/' + A.mbid.slice(0, 8) + '…</span></span></a>'
      + '<a class="menu-item" href="#" target="_blank" rel="noopener"><span class="mi-ic">↗</span><span>Cover Art Archive<span class="mi-sub">coverartarchive.org/release-group/' + A.mbid.slice(0, 8) + '…</span></span></a>'
      + (A.id === 'salt-on-the-window' || A.id === 'glass-birds' ? '<a class="menu-item" href="#"><span class="mi-ic">↗</span><span>Discogs<span class="mi-sub">from MusicBrainz’s URL relationships</span></span></a><a class="menu-item" href="#"><span class="mi-ic">↗</span><span>Wikipedia<span class="mi-sub">en.wikipedia.org</span></span></a>' : '') : '';
    $('al-bar').innerHTML = '<h1>' + esc(A.title) + ' <span class="muted">(' + A.year + ')</span></h1>'
      + (A.type !== 'album' ? '<span class="mu-type">' + M.TYPE_LABEL[A.type] + '</span>' : '')
      + '<span class="spacer"></span>'
      + '<span class="menu-wrap"><span class="btn sm ghost menu-btn">External links <span class="caret">▾</span></span><div class="menu"><a class="menu-item" href="#"><span class="mi-ic">↗</span><span>Open in Jellyfin<span class="mi-sub">Album in the Jellyfin web UI</span></span></a>' + mbLink + '</div></span>'
      + '<span class="menu-wrap"><span class="btn sm ghost menu-btn">Re-pull <span class="caret">▾</span></span><div class="menu"><div class="menu-item" data-a="repull-jf"><span class="mi-ic">⟲</span><span>From Jellyfin…<span class="mi-sub">Re-read tracks, tags and paths</span></span></div>'
        + (locked ? '<div class="menu-item mu-tip" style="opacity:.45;cursor:not-allowed"><span class="mi-ic">⟲</span><span>From MusicBrainz<span class="mi-sub">Locked — unlock to re-pull</span></span><span class="tp">This match is locked, so a re-pull would change nothing it filled. Unlock it first if MusicBrainz has something newer you want.</span></div>'
          : isMatched() ? '<div class="menu-item" data-a="repull-mb"><span class="mi-ic">⟲</span><span>From MusicBrainz<span class="mi-sub">Re-fetch genres, credits and the cover</span></span></div>' : '')
      + '</div></span>'
      + '<span class="menu-wrap"><span class="btn sm ghost menu-btn">⋯</span><div class="menu">'
        + (isMatched() ? '<div class="menu-item" data-a="lock"><span class="mi-ic">' + (locked ? '🔓' : '🔒') + '</span><span>' + (locked ? 'Unlock match' : 'Lock match') + '<span class="mi-sub">' + (locked ? 'Let the next run re-match it' : 'The next run leaves this match alone') + '</span></span></div><div class="menu-item" data-a="clear"><span class="mi-ic">✕</span><span>Clear match<span class="mi-sub">Forget the MusicBrainz ids; fields stay until the next run</span></span></div>' : '')
        + '<div class="menu-item" data-a="find"><span class="mi-ic">⌕</span><span>' + (isMatched() ? 'Change match…' : 'Find match…') + '<span class="mi-sub">Search MusicBrainz for this album</span></span></div>'
      + '</div></span>'
      + FilesTab.saveMenu({ kind: 'music', nfo: 'album.nfo', where: 'album', n: ts.length, differ: FilesTab.summary(tagSpec()).differ });
  }
  function matchChip() {
    if (match === 'unmatched') return '<span class="mu-match w"><span class="l1"><span class="dot"></span>No MusicBrainz match</span><span class="l2">The ladder ran on ' + A.year + '-era tags and found nothing above the bar</span></span>';
    if (match === 'needs') return '<span class="mu-match w"><span class="l1"><span class="dot"></span>' + (M.CANDS[A.id] || []).length + ' candidates · needs you</span><span class="l2">No clear winner — two fit the tracks equally well</span></span>';
    const r = (M.CANDS[A.id] && chosen != null) ? M.CANDS[A.id][chosen].releases[chosenRel] : { country: A.country, date: String(A.year), label: A.label };
    return '<span class="mu-match' + (locked ? ' lk' : '') + '"><span class="l1">' + (locked ? LOCK + ' Locked · won’t be re-matched' : '<span class="dot"></span>MusicBrainz · release-group') + ' <a class="mono tiny" href="#" style="font-weight:500">' + A.mbid.slice(0, 8) + ' ▸</a></span><span class="l2">release: ' + esc(r.country) + ' ' + esc(String(r.date).slice(0, 4)) + ' · ' + esc(r.label) + '</span></span>';
  }
  function head() {
    const have = ts.length, len = ts.reduce((s, t) => s + t.len, 0);
    const codecs = [...new Set(ts.map(t => t.codec + ' ' + t.kbps))];
    const codecHTML = wma.length
      ? '<span class="mu-fmt w">' + codecs.join(' + ') + '</span><span class="tiny" style="color:var(--warn)">' + (wma.length === have ? 're-encodes on a phone' : wma.length + ' of ' + have + ' re-encode on a phone') + '</span>' + (Q('wma') === 'convert' ? '<span class="btn sm" data-a="convert">Convert…</span>' : '')
      : '<span class="mu-fmt">' + codecs.join(' + ') + '</span>';
    const primary = match === 'unmatched' ? '<span class="btn primary" data-a="find">Find match…</span>' : match === 'needs' ? '<span class="btn primary" data-a="find">Choose…</span>' : '<span class="btn sm ghost" data-a="find">Change match…</span>';
    $('al-head').innerHTML = '<div class="mu-pb">' + coverHTML('mu-cov', '') + '<div>'
      + '<div class="mu-by">by ' + artistLinks([A.artistId]) + '</div>'
      + '<div class="mu-pbm"><span>' + A.year + '</span><span class="sep">·</span><span>' + (A.total && A.total > have ? have + ' of ' + A.total + ' songs' : have + (have === 1 ? ' song' : ' songs')) + '</span><span class="sep">·</span><span>' + M.fmtTotal(len) + '</span><span class="mu-type">' + M.TYPE_LABEL[A.type] + '</span><span class="sep">·</span>' + codecHTML + (V ? Versions.albumSummary(ts) : '') + '</div>'
      + '<div class="mu-acts">' + matchChip() + primary + (isMatched() ? '<span class="chip" style="cursor:pointer" data-a="lock">' + (locked ? LOCK.replace('<svg', '<svg width="10" height="11"') + ' Locked' : 'Lock') + '</span>' : '') + '</div>'
      + (A.total && A.total > have ? '<div class="tiny muted" style="margin-top:10px;">Partial album — the release has ' + A.total + ' tracks, the library holds ' + have + '. Tracks shows the gaps.</div>' : '')
      + '</div></div>';
    $('al-crumb').innerHTML = '<a href="library.html?kind=music">Music</a> / <a href="library.html?kind=music&mview=albums">Albums</a> / ' + esc(A.title);
  }
  function banners() {
    let h = '';
    if (match === 'unmatched') h += '<div class="mu-note w"><span class="badge warn">Unmatched</span><div class="t"><b>No MusicBrainz match after the ladder ran.</b> Folder <span class="mono">' + esc(artist.name) + '/' + esc(A.title) + '</span>, one track, tags from 2004. Nothing below fills in until this is matched: the cover, genres, the release’s track count and every recording id.</div><span class="btn sm primary" data-a="find">Find match…</span></div>';
    if (match === 'needs') h += '<div class="mu-note w"><span class="badge warn">Needs you</span><div class="t"><b>' + (M.CANDS[A.id] || []).length + ' candidates, no clear winner.</b> ' + (A.id === 'summer-hits-2004' ? 'Three compilations share this name, the year and the label type — only the track list tells them apart.' : 'The album and its deluxe edition both hold these two tracks at these positions.') + '</div><span class="btn sm primary" data-a="find">Choose…</span></div>';
    if (driftOn) h += '<div class="note" style="margin-bottom:14px;background:var(--warn-soft);border-color:rgba(245,181,66,.4);display:flex;gap:13px;align-items:flex-start;"><span class="badge warn" style="flex:none;margin-top:1px;">⇄ Drift detected</span><div style="flex:1;min-width:0;"><b>Jellyfin rewrote album.nfo at 03:12 — its own NFO saver is on for the Musik library.</b><div class="tiny" style="margin-top:5px;line-height:1.6;"><b>2 fields</b> differ from our last write (genres, the release id). This will keep happening until <a href="index.html">the Dashboard’s Jellyfin finding</a> (the NFO saver on Musik) is fixed.</div><div class="pill-row" style="margin-top:10px;"><span class="btn sm" data-a="reassert">Re-assert NFO → Jellyfin</span></div></div><span class="x" data-a="nodrift" style="cursor:pointer;color:var(--ink-soft);">✕</span></div>';
    if (A.id === 'early-recordings') h += '<div class="note red" style="margin-bottom:14px;display:flex;gap:13px;align-items:flex-start;"><span class="badge bad" style="flex:none;margin-top:1px;">⚠ Locked in Jellyfin</span><div style="flex:1;min-width:0;"><b>This album has locked metadata fields in Jellyfin, so your changes here may be ignored.</b><div class="tiny" style="margin-top:5px;line-height:1.6;">Locked fields: <span class="chip" style="font-size:.66rem;">Genres</span>. To fix it: open the album in Jellyfin → <b>Edit metadata</b> → uncheck all locks → save.</div></div></div>';
    $('al-banners').innerHTML = h;
  }

  /* ---- tabs ---- */
  function lyrCell(t) {
    const l = lyr[t.id];
    if (V && Versions.noWords(t) && !l) return '<span class="mu-ly no" title="Never sung — never given lyrics">No words — MusicBrainz</span>';
    if (l && V && Versions.lyricsIssue(Object.assign({}, t, { lyrics: l }))) return '<span class="mu-ly" style="color:var(--warn)" title="Lyrics beside a song with no singing — listed on the Dashboard">' + LYR + (l === 'synced' ? 'synced' : 'plain') + ' · no singing</span>';
    if (l) return '<span class="mu-ly">' + LYR + (l === 'synced' ? 'synced ✓' : 'plain ✓') + '</span>';
    if (!isMatched()) return '<span class="mu-ly no">—</span>';
    return Q('lyrics') === 'on' ? '<span class="mu-ly no">none · <a href="#" data-fetch="' + t.id + '">Fetch</a></span>' : '<span class="mu-ly no" title="Lyrics fetching is off in Settings">none</span>';
  }
  function recCell(t) {
    if (!isMatched()) return '<span class="mu-mt d">— after a match</span>';
    if (t.rec === 'other') return '<span class="mu-mt w">from a different release</span> <span class="btn sm ghost" data-rec="' + t.id + '" style="margin-left:4px;">Match this track…</span>';
    return '<span class="mu-mt ok">✓ recording</span>';
  }
  function trackRow(t) {
    const canPlay = !M.reencodes(t);
    return '<tr class="' + (t.rec === 'other' && isMatched() ? 'off' : '') + (V && Versions.sel.has(t.id) ? ' vr-on' : '') + '"><td class="n">' + (V ? Versions.selCell(t) : t.n) + '</td><td>' + esc(t.title) + (V ? Versions.badges(t) : '') + (t.feat.length ? ' <span class="dim tiny">feat. ' + artistLinks(t.feat) + '</span>' : '') + (t.artistIds[0] !== A.artistId ? '<div class="tiny dim">' + artistLinks(t.artistIds) + '</div>' : '') + (V ? Versions.detail(t) : '') + '</td>'
      + '<td class="num">' + M.fmtLen(t.len) + '</td><td><span class="mu-fmt' + (canPlay ? '' : ' w') + '">' + t.codec + ' · ' + t.kbps + ' · ' + t.khz + ' kHz</span></td><td>' + recCell(t) + '</td><td>' + lyrCell(t) + '</td><td class="num mu-mono" style="font-size:.74rem">' + t.gain.toFixed(1) + ' dB</td>'
      + '<td>' + FilesTab.glyph(tagSpec(), t, ts.indexOf(t)) + '</td><td>' + (canPlay ? '<span class="mu-play' + (playing === t.id ? ' on' : '') + '" data-play="' + t.id + '" title="Play in this browser — your own Jellyfin session, direct play">' + (playing === t.id ? '❚❚' : '▶') + '</span>' : '<span class="tiny muted mu-tip" style="cursor:help">no direct play<span class="tp">A browser can’t direct-play WMA, and this page never asks Jellyfin to convert — direct play or nothing, the segment editor’s rule.</span></span>') + '</td></tr>'
      + (recOpen === t.id ? '<tr><td></td><td colspan="8"><div class="mu-cand on" style="cursor:default;margin:2px 0 6px;"><b class="tiny">Recordings of “' + esc(t.title) + '” by ' + esc(artist.name) + '</b>'
        + '<table class="mu-mini"><tr><td>Small Hours</td><td class="mono">3:59</td><td>on <i>Glass Birds</i> (2004) · position 7</td><td class="ok">length agrees</td><td><span class="btn sm primary" data-recuse="' + t.id + '">Use</span></td></tr><tr><td>Small Hours (single version)</td><td class="mono">3:47</td><td>on <i>Small Hours</i> (single, 2004)</td><td class="w">12 s shorter</td><td><span class="btn sm ghost" data-recuse="' + t.id + '">Use</span></td></tr></table>'
        + '<div class="tiny muted" style="margin-top:6px;">Today the file is matched to the single’s recording, which is why the row is marked. The album recording agrees on position and length.</div></div></td></tr>' : '');
  }
  function tracksTab() {
    const byN = {}; ts.forEach(t => byN[t.n] = t);
    const max = A.total || Math.max(...ts.map(t => t.n));
    const rows = []; let gapFrom = null;
    const flush = to => { if (gapFrom == null) return; rows.push('<tr class="gap"><td class="n">' + (gapFrom === to ? gapFrom : gapFrom + '–' + to) + '</td><td colspan="8">not in library' + (to > gapFrom ? ' · ' + (to - gapFrom + 1) + ' tracks' : '') + '</td></tr>'); gapFrom = null; };
    if (A.total) { for (let n = 1; n <= max; n++) { if (byN[n]) { flush(n - 1); rows.push(trackRow(byN[n])); } else if (gapFrom == null) gapFrom = n; } flush(max); }
    else ts.forEach(t => rows.push(trackRow(t)));
    const missingLy = ts.filter(t => !lyr[t.id]).length;
    return '<div class="row center" style="gap:10px;flex-wrap:wrap;margin-bottom:10px;"><span class="tiny muted">Album gain <b class="mono" style="color:var(--ink)">' + A.gain.toFixed(1) + ' dB</b> · from Jellyfin’s loudness scan · the phone applies album gain on an album, track gain on a mix</span><span class="spacer"></span>'
      + (isMatched() && missingLy && Q('lyrics') === 'on' ? '<span class="btn sm ghost" data-a="lyrics">Fetch missing lyrics (' + missingLy + ')</span>' : '') + '</div>'
      + (!A.total ? '<div class="note blue" style="margin-bottom:12px;">Positions come from the files’ tags. A match tells us how many tracks the release has — then the gaps show here.</div>' : '')
      + (V ? Versions.selBar(ts.map(t => t.id)) : '')
      + '<div class="mu-scroll"><table class="mu-tbl' + (V && ts.some(t => Versions.sel.has(t.id)) ? ' vr-selecting' : '') + '"><thead><tr><th>#</th><th>Title</th><th>Length</th><th>Format</th><th>Recording</th><th>Lyrics</th><th>Gain</th><th title="What the file says, against this page — opens the Files tab">File</th><th></th></tr></thead><tbody>' + rows.join('') + '</tbody></table></div>'
      + '<div class="tiny muted" style="margin-top:10px;">No disc numbers in these files — every track is disc 1. Positions are the files’ own.</div>';
  }
  function artworkTab() {
    const inUse = hasCover ? '<div class="mu-aw use"><div class="im chk"><div style="position:absolute;inset:0;' + M.coverStyle(A) + '"></div></div><div class="cap"><b>cover.jpg</b><span class="d">1000 × 1000 · on disk</span><span class="spacer" style="flex:1"></span><span class="chip" style="font-size:.66rem;cursor:pointer" data-a="coverlock">🔒 lock</span><span class="chip" style="font-size:.66rem;cursor:pointer" data-a="coverclear">Clear</span></div></div>'
      : '<div class="mu-aw"><div class="im chk" style="display:flex;align-items:center;justify-content:center;"><span class="tiny" style="color:var(--warn);font-weight:600">no cover.jpg on disk</span></div><div class="cap"><b>cover.jpg</b><span class="d">missing — a task</span></div></div>';
    let caa;
    if (!isMatched()) caa = '<div class="tiny muted">A match fills this from the Cover Art Archive — front, back and booklet, as the release’s own scans.</div>';
    else if (!A.cover) caa = '<div class="mu-note w"><div class="t"><b>No cover on the Cover Art Archive for this release-group.</b> Nobody has uploaded one yet. Upload one here — it is written as <span class="mono">cover.jpg</span> in the album folder and Jellyfin reads it on the next sync.</div><span class="btn sm primary" data-a="upload">Upload…</span></div>';
    else caa = '<div class="mu-art">' + [['front', 'Front', true], ['back', 'Back', false], ['booklet', 'Booklet p.1', false]].map((x, i) => '<div class="mu-aw' + (hasCover && i === 0 ? ' use' : '') + '"><div class="im" style="' + (i === 0 ? M.coverStyle(A) : M.coverStyle({ id: A.id + x[0], hue: (A.hue + 40 * i) % 360 })) + (i ? ';filter:saturate(.6) brightness(.8)' : '') + '"></div>' + (x[2] ? '<span class="mu-appr">approved</span>' : '') + '<div class="cap"><b>' + x[1] + '</b><span class="d">500 px thumb · 1400 px</span>' + (hasCover && i === 0 ? '<span class="badge ok" style="font-size:.6rem">in use</span>' : '<span class="btn sm ghost" style="margin-left:auto" data-a="usecover">Use</span>') + '</div></div>').join('') + '</div>';
    const fan = !isMatched() ? '' : A.id === 'salt-on-the-window' || A.id === 'glass-birds'
      ? '<div class="mu-art"><div class="mu-aw"><div class="im" style="' + M.coverStyle({ id: A.id + 'fa', hue: (A.hue + 12) % 360 }) + '"></div><div class="cap"><b>albumcover</b><span class="d">1000 px</span><span class="btn sm ghost" style="margin-left:auto" data-a="usecover">Use</span></div></div><div class="mu-aw"><div class="im chk"><div class="mu-cd"></div></div><div class="cap"><b>cdart</b><span class="d">transparent · 1000 px</span><span class="btn sm ghost" style="margin-left:auto" data-a="usecd">Use</span></div></div></div>'
      : '<div class="tiny muted">fanart.tv has nothing for this album.</div>';
    return '<div class="mu-sec">Currently in use</div><div class="mu-art">' + inUse + '<label class="mu-aw" style="cursor:pointer;border-style:dashed;"><div class="im" style="display:flex;align-items:center;justify-content:center;flex-direction:column;gap:6px;color:var(--ink-soft);font-size:.8rem;">⬆<span>Upload a cover</span><span class="tiny muted">JPG or PNG, square</span></div></label></div>'
      + '<div class="mu-sec">Candidates · Cover Art Archive</div>' + caa
      + (isMatched() ? '<div class="mu-sec">Candidates · fanart.tv</div>' + fan : '');
  }
  function genresTab() {
    if (!isMatched()) return '<div class="mu-sec">Genres</div>' + (A.genres.length ? '<div class="mu-gchips">' + A.genres.map(g => '<span class="mu-gc on"><span class="bx">✓</span>' + esc(g.name) + '<span class="v">from the file’s tag</span></span>').join('') + '</div>' : '<div class="tiny muted">None in the files. A match brings MusicBrainz’s genre votes.</div>')
      + '<div class="tiny muted" style="margin-top:10px;">After a match, MusicBrainz’s community votes replace the file’s tags as the source.</div>';
    const real = A.genres.filter(g => g.votes > 0);
    return '<div class="mu-sec">MusicBrainz genres · votes</div><div class="mu-gchips">' + real.map(g => '<span class="mu-gc' + (genresOn.has(g.name) ? ' on' : '') + '" data-g="' + esc(g.name) + '"><span class="bx">' + (genresOn.has(g.name) ? '✓' : '') + '</span>' + esc(g.name) + '<span class="v">' + g.votes + '</span></span>').join('') + '</div>'
      + '<div class="tiny muted" style="margin-top:10px;line-height:1.6;">Ticked genres are written to <span class="mono">album.nfo</span>. The rule: up to four, each with at least 3 votes and at least a tenth of the top genre’s votes. Tick or untick to override — the override is kept across runs.</div>'
      + '<div class="mu-sec">Jellystructure tags</div><div class="mu-gchips"><span class="chip"><span class="tag-dot" style="background:#7b6ef0"></span> Kitchen radio</span><span class="chip" style="border-style:dashed;cursor:pointer">＋ Add tag</span></div>';
  }
  function nfoTab() {
    const lines = ['<?xml version="1.0" encoding="utf-8" standalone="yes"?>', '<album>', '  <title>' + esc(A.title) + '</title>', '  <artist>' + esc(artist.name) + '</artist>', '  <albumartist>' + esc(artist.name) + '</albumartist>', '  <year>' + A.year + '</year>', '  <type>' + M.TYPE_LABEL[A.type] + '</type>'];
    if (isMatched()) { lines.push('  <musicbrainzreleasegroupid>' + A.mbid + '</musicbrainzreleasegroupid>', '  <musicbrainzalbumid>' + A.relMbid + '</musicbrainzalbumid>', '  <musicbrainzalbumartistid>' + artist.mbid + '</musicbrainzalbumartistid>', '  <label>' + esc(A.label) + '</label>'); [...genresOn].forEach(g => lines.push('  <genre>' + esc(g) + '</genre>')); }
    ts.forEach(t => { lines.push('  <track>', '    <position>' + t.n + '</position>', '    <title>' + esc(t.title) + '</title>', '    <duration>' + M.fmtLen(t.len) + '</duration>'); if (isMatched()) lines.push('    <musicBrainzTrackID>' + M.mbid('tr' + t.id) + '</musicBrainzTrackID>'); lines.push('  </track>'); });
    lines.push('  <lockdata>false</lockdata>', '</album>');
    return '<div class="row center" style="margin-bottom:10px;gap:8px"><span class="tiny muted mono">/mnt/media/jellyfin/music/' + esc(artist.name) + '/' + esc(A.title) + '/album.nfo</span><span class="spacer"></span>' + (isMatched() ? '<span class="badge ok">written 2026-09-27 03:04</span>' : '<span class="badge">not written yet</span>') + '</div><div class="mu-nfo">' + lines.join('\n') + '</div>';
  }
  function historyTab() {
    const H = [['2026-09-23 21:40', 'Scanned from Jellyfin · ' + ts.length + ' track' + (ts.length > 1 ? 's' : '') + ', tags only, no ids']];
    if (match === 'unmatched') H.push(['2026-09-27 03:02', 'match_musicbrainz · no candidate above the bar (best 61) — flagged for triage']);
    if (match === 'needs') H.push(['2026-09-27 03:02', 'match_musicbrainz · ' + M.CANDS[A.id].length + ' candidates, top two within 5 points — needs you']);
    if (isMatched()) H.push(['2026-09-27 03:02', 'match_musicbrainz · matched by text search · ' + ts.length + ' of ' + ts.length + ' tracks agree'], ['2026-09-27 03:03', hasCover ? 'fetch_music_artwork · cover.jpg from the Cover Art Archive' : 'fetch_music_artwork · the Cover Art Archive has no cover — flagged'], ['2026-09-27 03:04', 'write_music_nfo · album.nfo written']);
    if (A.match === 'locked') H.push(['2026-09-27 09:15', 'Eyð locked the match (chosen by hand from 2 candidates)']);
    if (driftOn) H.push(['2026-09-27 03:12', 'detect_drift · Jellyfin rewrote album.nfo (its NFO saver is on)']);
    return '<div class="mu-hist">' + H.reverse().map(h => '<div class="mu-hi"><span class="ts">' + h[0] + '</span><span>' + esc(h[1]) + '</span></div>').join('') + '</div>';
  }
  function panel() {
    document.querySelectorAll('#tabbar [data-tab]').forEach(t => t.classList.toggle('on', t.dataset.tab === tab));
    if (tab === 'files') { FilesTab.mount($('al-panel'), tagSpec(), bar); return; }
    $('al-panel').innerHTML = tab === 'artwork' ? artworkTab() : tab === 'genres' ? genresTab() : tab === 'nfo' ? nfoTab() : tab === 'history' ? historyTab() : tracksTab();
  }

  /* ---- Find match… ---- */
  function agreeHTML(c) {
    const g = c.agree, cls = g.hit === g.of && !g.off ? 'ok' : g.hit === 0 ? 'bad' : 'w';
    if (Q('arith') === 'table') {
      const cand = ts.map((t, i) => { const hit = i < g.hit; return '<tr><td class="mono">' + t.n + '</td><td>' + esc(t.title) + '</td><td class="mono">' + M.fmtLen(t.len) + '</td><td class="' + (hit ? (g.off ? 'w' : 'ok') : 'bad') + '">' + (hit ? (g.off && i === 0 ? 'position ✓ · length +' + g.off + ' s' : 'position ✓ · length ✓') : 'not on this release') + '</td></tr>'; }).join('');
      return '<table class="mu-mini">' + cand + '</table>';
    }
    let s = '<b>' + g.hit + ' of ' + g.of + '</b> track' + (g.of > 1 ? 's' : '') + ' agree on position and length' + (g.partial ? ' (partial album)' : '');
    if (g.off && g.hit) s = '<b>titles agree</b>, lengths off by up to <b>' + g.off + ' s</b>' + (g.partial ? ' (partial album)' : '');
    if (g.note) s += ' — ' + esc(g.note);
    return '<div class="mu-agree ' + cls + '">' + s + '</div>';
  }
  function fmHTML() {
    const C = M.CANDS[A.id] || [{ title: A.title, artist: artist.name, type: M.TYPE_LABEL[A.type], year: A.year, score: 100, agree: { hit: ts.length, of: ts.length, partial: A.total > ts.length, off: 0 }, releases: [{ country: A.country, date: A.year + '-04-12', label: A.label, format: 'CD', tracks: A.total || ts.length, agree: ts.length }, { country: 'XW', date: '2014-01-20', label: A.label, format: 'Digital Media', tracks: (A.total || ts.length) + 2, agree: ts.length }] }];
    const best = c => c.releases.reduce((b, r, i) => r.agree > c.releases[b].agree ? i : b, 0);
    const list = searching ? '<div class="mu-busy"><span class="sp"></span>Searching MusicBrainz… it answers one request a second, so a search plus each candidate’s releases takes a few seconds.</div>'
      : C.map((c, i) => '<div class="mu-cand' + (chosen === i ? ' on' : '') + '" data-cand="' + i + '"><div class="mu-ch"><div class="th" style="' + (c.score > 70 ? M.coverStyle({ id: A.id + i, hue: (A.hue + i * 60) % 360 }) : M.wordmarkStyle(c.title)) + '"></div><div><div class="nm">' + esc(c.title) + '</div><div class="sb">' + esc(c.artist) + ' <span class="mu-type">' + esc(c.type) + '</span> first released ' + c.year + '</div></div><div class="mu-score">score<b>' + c.score + '</b></div></div>'
        + agreeHTML(c)
        + (chosen === i ? '<div class="mu-rels"><div class="tiny muted" style="margin-bottom:4px;">Releases · the best-agreeing one is preselected</div>' + c.releases.map((r, j) => '<div class="mu-rel' + (chosenRel === j ? ' on' : '') + '" data-rel="' + j + '"><span class="rd"></span><span class="mono">' + r.country + '</span><span class="mono">' + r.date + '</span><span class="lb">' + esc(r.label) + '</span><span class="fm">' + r.format + ' · ' + r.tracks + ' tr</span><span>' + (j === best(c) ? '<span class="best">best · ' + r.agree + '/' + ts.length + '</span>' : '<span class="tiny muted">' + r.agree + '/' + ts.length + '</span>') + '</span></div>').join('') + '</div>' : '')
        + '</div>').join('');
    const fp = acoust ? '<div class="mu-fp"><div class="h">Identify by sound · AcoustID<span class="spacer" style="flex:1"></span><span class="btn sm ghost" data-a="fp">Run fpcalc on ' + ts.length + ' track' + (ts.length > 1 ? 's' : '') + '</span></div><div class="tiny muted" id="fp-out" style="margin-top:6px;">Fingerprints each file and asks AcoustID which recordings they are — the tie-breaker when the tags are not enough.</div></div>'
      : '<div class="mu-fp"><div class="h">Identify by sound</div><div class="tiny muted" style="margin-top:6px;"><a href="settings.html?tab=connections#mu-prov">Add an AcoustID key in Settings</a> to identify by sound.</div></div>';
    return '<div class="mu-ph"><h3>' + (match === 'needs' ? 'Choose a match' : 'Find a match') + ' · ' + esc(A.title) + '</h3><span class="spacer"></span><span class="btn sm ghost" data-a="fmclose">✕</span></div>'
      + '<div class="mu-pbody"><div class="mu-q3"><div class="field"><label>Artist</label><input id="fm-ar" value="' + esc(artist.name === 'Various Artists' ? '' : artist.name) + '"></div><div class="field"><label>Album</label><input id="fm-al" value="' + esc(A.title) + '"></div><div class="field"><label>Year</label><input id="fm-yr" value="' + A.year + '"></div><span class="btn" data-a="search">Search</span></div>'
      + '<div class="tiny muted" style="margin-top:6px;">Pre-filled from the folder <span class="mono">' + esc(artist.name) + '/' + esc(A.title) + '</span>. Or paste a MusicBrainz release-group URL.</div>'
      + list + fp + '</div>'
      + '<div class="mu-pfoot"><span class="btn primary' + (chosen == null ? ' is-off' : '') + '" data-a="use">Use this match</span><span class="btn' + (chosen == null ? ' is-off' : '') + '" data-a="uselock">Use and lock</span><span class="spacer" style="flex:1"></span><span class="btn ghost" data-a="fmclose">Cancel</span></div>';
  }
  function openFM() {
    const C = M.CANDS[A.id];
    chosen = match === 'needs' || match === 'unmatched' ? null : 0; chosenRel = 0;
    if (chosen == null && C && match === 'needs') { chosen = null; }
    $('fm-panel').innerHTML = fmHTML(); $('fm-panel').classList.add('on'); $('fm-scrim').classList.add('on');
  }
  function closeFM() { $('fm-panel').classList.remove('on'); $('fm-scrim').classList.remove('on'); }
  function paintFM() { $('fm-panel').innerHTML = fmHTML(); }
  $('fm-scrim').onclick = closeFM;
  $('fm-panel').addEventListener('click', e => {
    const c = e.target.closest('[data-cand]');
    const r = e.target.closest('[data-rel]');
    if (r && c) { chosenRel = +r.dataset.rel; paintFM(); return; }
    if (c && !e.target.closest('[data-a]')) { const i = +c.dataset.cand; if (chosen !== i) { chosen = i; const cc = (M.CANDS[A.id] || [])[i]; chosenRel = cc ? cc.releases.reduce((b, x, j) => x.agree > cc.releases[b].agree ? j : b, 0) : 0; } paintFM(); return; }
    const a = e.target.closest('[data-a]'); if (!a) return;
    const k = a.dataset.a;
    if (k === 'fmclose') closeFM();
    else if (k === 'search') { searching = true; paintFM(); setTimeout(() => { searching = false; paintFM(); }, 1600); }
    else if (k === 'fp') { const o = $('fp-out'); o.innerHTML = '<span class="mu-busy" style="padding:0"><span class="sp"></span>fpcalc · ' + ts.length + ' file' + (ts.length > 1 ? 's' : '') + '…</span>'; setTimeout(() => { o.innerHTML = '<b style="color:var(--ink)">' + esc(M.FP[A.id] || ts.length + ' of ' + ts.length + ' tracks identified → 1 release-group (' + A.title + ')') + '</b>'; }, 1300); }
    else if ((k === 'use' || k === 'uselock') && chosen != null) {
      const cand = (M.CANDS[A.id] || [])[chosen];
      if (cand && cand.agree.hit === 0) { toast('No track agrees with that candidate — pick one whose tracks line up, or keep it unmatched'); return; }
      match = k === 'uselock' ? 'locked' : 'matched'; locked = k === 'uselock';
      closeFM(); repaint();
      toast('Matched · cover, genres and album.nfo will be fetched on the next run — or Run now');
    }
  });

  /* ---- modals ---- */
  function modal(html) { $('al-modal-card').innerHTML = html; $('al-modal').classList.add('on'); }
  $('al-modal').addEventListener('click', e => { if (e.target === $('al-modal') || e.target.closest('[data-m="no"]')) $('al-modal').classList.remove('on'); if (e.target.closest('[data-m="go"]')) { $('al-modal').classList.remove('on'); toast(e.target.closest('[data-m="go"]').dataset.msg); } });
  function convertModal() {
    modal('<h3>Convert ' + wma.length + ' song' + (wma.length > 1 ? 's' : '') + ' so a phone can play them directly?</h3><p>These are <b>WMA 128 kbps</b> — a phone can only play them by asking Jellyfin to re-encode on every play, which is never gapless. A one-time repair job converts them to <b>AAC 192 kbps</b>.</p><p><b>They are already lossy, so a little more is lost.</b> You are unlikely to hear it, and it cannot be undone from the new files. The WMA originals are moved to a holding folder, not deleted, until you empty it.</p><p class="tiny">Files seeding in qBittorrent are skipped (cross-seed safety).</p><div class="row" style="justify-content:flex-end;gap:8px;margin-top:12px;"><span class="btn ghost" data-m="no">Keep as they are</span><span class="btn primary" data-m="go" data-msg="Convert job queued · ' + wma.length + ' files · Jobs &amp; workers">Convert ' + wma.length + '</span></div>');
  }

  /* ---- events ---- */
  document.addEventListener('click', e => {
    const mw = e.target.closest('.menu-wrap .menu-btn, .split .menu-btn');
    document.querySelectorAll('.menu-wrap.open, .split.open').forEach(o => { if (!mw || o !== mw.parentNode) o.classList.remove('open'); });
    if (mw) { mw.parentNode.classList.toggle('open'); return; }
    if (e.target.closest('.menu-item')) document.querySelectorAll('.menu-wrap.open, .split.open').forEach(o => o.classList.remove('open'));
    if (e.target.closest('#fm-panel')) return;
    const t = e.target.closest('#tabbar [data-tab]'); if (t) { tab = t.dataset.tab; const u = new URL(location.href); u.searchParams.set('tab', tab); history.replaceState(null, '', u); panel(); return; }
    const ak = e.target.closest('[data-ak]'); if (ak) { acoust = ak.dataset.ak === '1'; fence(); return; }
    const cov = e.target.closest('.mu-pb .mu-cov'); if (cov) { $('al-lb-im').setAttribute('style', hasCover ? M.coverStyle(A) : M.wordmarkStyle(A.title)); $('al-lb-cap').textContent = hasCover ? 'cover.jpg · 1000 × 1000' : 'no cover.jpg on disk'; $('al-lb').classList.add('on'); return; }
    if (e.target.closest('#al-lb')) { $('al-lb').classList.remove('on'); return; }
    const fr = e.target.closest('[data-ftrow]'); if (fr) { e.preventDefault(); tab = 'files'; panel(); FilesTab.highlight(fr.dataset.ftrow); return; }
    if (e.target.closest('[data-off]')) { toast('Turn on Write tags into music files in Settings → Music providers'); return; }
    const pl = e.target.closest('[data-play]'); if (pl) { playing = playing === pl.dataset.play ? null : pl.dataset.play; panel(); return; }
    const fe = e.target.closest('[data-fetch]'); if (fe) { e.preventDefault(); lyr[fe.dataset.fetch] = 'synced'; panel(); toast('LRCLIB · synced lyrics found'); return; }
    const rc = e.target.closest('[data-rec]'); if (rc) { recOpen = recOpen === rc.dataset.rec ? null : rc.dataset.rec; panel(); return; }
    const ru = e.target.closest('[data-recuse]'); if (ru) { M.track(ru.dataset.recuse).rec = 'ok'; recOpen = null; panel(); toast('Recording matched · written with the next album.nfo'); return; }
    const g = e.target.closest('[data-g]'); if (g) { genresOn.has(g.dataset.g) ? genresOn.delete(g.dataset.g) : genresOn.add(g.dataset.g); panel(); return; }
    const a = e.target.closest('[data-a]'); if (!a) return;
    const k = a.dataset.a;
    if (k === 'find') openFM();
    else if (k === 'lock') { locked = !locked; match = locked ? 'locked' : 'matched'; repaint(); toast(locked ? 'Locked · the next run leaves this match alone' : 'Unlocked · the next run may re-match it'); }
    else if (k === 'clear') { match = 'unmatched'; locked = false; repaint(); toast('Match cleared · the fields it filled stay until the next run'); }
    else if (k === 'convert') convertModal();
    else if (k === 'nodrift' || k === 'reassert') { driftOn = false; banners(); if (k === 'reassert') toast('Re-asserted · album.nfo rewritten, Jellyfin re-reading'); }
    else if (k === 'lyrics') { ts.forEach(t => { if (!lyr[t.id]) lyr[t.id] = t.len % 3 ? 'synced' : null; }); panel(); toast('LRCLIB · lyrics found for some; the rest stay empty'); }
    else if (k === 'upload') toast('Pick an image — it is written as cover.jpg');
    else if (k === 'usecover') { hasCover = true; repaint(); toast('cover.jpg written · Jellyfin re-reads on the next sync'); }
    else if (k === 'coverclear') { hasCover = false; repaint(); toast('cover.jpg removed'); }
    else if (k === 'coverlock') toast('Cover locked · a re-pull won’t replace it');
    else if (k === 'savesync') toast(FilesTab.writeOn('music') ? 'album.nfo + tags in ' + ts.length + ' files written · Jellyfin re-reading ↻' : 'album.nfo written · Jellyfin re-reading ↻');
    else if (k === 'savefiles') { tab = 'files'; panel(); }
    else if (k === 'save') toast('album.nfo written');
    else if (k === 'sync') toast('Sync requested ↻');
    else if (k === 'repull-mb') toast('Re-pulling from MusicBrainz · one request a second');
    else if (k === 'repull-jf') toast('Re-reading from Jellyfin');
    else if (k === 'usecd') toast('cdart.png written');
  });
  function repaint() { bar(); head(); banners(); panel(); }
  TagsQ.mount($('al-tq')); TagsQ.on(() => { bar(); if (tab === 'tracks') panel(); });
  if (window.MusicQ) { MusicQ.mount($('al-qs'), 'album'); MusicQ.on(() => { repaint(); if ($('fm-panel').classList.contains('on')) paintFM(); }); }
  if (V) { Versions.mountQ($('al-vq')); Versions.on(() => { head(); if (tab === 'tracks') panel(); }); }
  fence(); repaint();
  if (P.get('find') === '1') openFM();
  if (V && P.get('ver')) Versions.openPanel(P.get('ver'));
})();
