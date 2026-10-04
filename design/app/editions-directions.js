/* Music editions & duplicates — round 1 canvas (brief specs/design-brief-music-editions-and-duplicates-2026-10-03.md).
   Stand-ins are the brief's (Harbour Lights · Kite Weather 2006 · Signal Found 2003), drawn here only; numbers are the brief's. */
(function () {
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const hash = s => { let h = 0; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0; return h; };
  const cov = (seed, hue) => { const h = hue == null ? hash(seed) % 360 : hue, k = hash(seed), x1 = 20 + k % 50, y1 = 20 + (k >> 3) % 50, x2 = 40 + (k >> 5) % 50, y2 = 45 + (k >> 7) % 45;
    return 'background:radial-gradient(circle at ' + x1 + '% ' + y1 + '%,hsl(' + h + ' 70% 62% / .95),transparent 42%),radial-gradient(circle at ' + x2 + '% ' + y2 + '%,hsl(' + ((h + 55) % 360) + ' 64% 52% / .9),transparent 46%),linear-gradient(150deg,hsl(' + h + ' 40% 26%),hsl(' + ((h + 30) % 360) + ' 46% 11%))'; };
  const VC = { live: ['#f0795b', 'Live'], demo: ['#a3aec6', 'Demo'], remix: ['#c67fe3', 'Remix'], acoustic: ['#d8ad62', 'Acoustic'], alternate: ['#e9709f', 'Alternate'], session: ['#f2a65a', 'Session'], instrumental: ['#3fb6f5', 'Instrumental'] };
  const PLAY = '<svg viewBox="0 0 24 24"><path d="M7 5l12 7-12 7z"/></svg>', SHUF = '<svg viewBox="0 0 24 24" style="fill:none;stroke:currentColor;stroke-width:2.2;stroke-linecap:round"><path d="M4 7h3c4 0 6 10 10 10h3M4 17h3c1.6 0 2.8-1.6 3.9-3.6M17 7h3M14 8.6C15 7.6 16 7 17 7"/></svg>', DOWN = '▾';

  /* ---- stand-ins (the brief's) ---- */
  const KW = { id: 'kw', title: 'Kite Weather', year: 2006, hue: 198, fmt: 'FLAC · 24 bit · 96 kHz',
    off: [['Kite Weather', '3:36'], ['Northern Line', '3:58'], ['Fog Bank', '4:12'], ['String and Paper', '4:40'], ['Salt on the Window', '4:05'], ['Low Tide', '5:02'], ['Weathervane Hymn', '4:21'], ['Tidewater', '4:48'], ['Gull Year', '3:55'], ['The Slipway', '4:30'], ['Last Ferry Out', '5:13']],
    ex: [['Lantern Swing', '3:44', [], 'the Japanese CD, 2006']], edition: 'Japanese edition', mins: 48 };
  const KWS = [['Northern Line', 2006, 210, [['Night Bus to Ålesund', '3:41'], ['Northern Line (acoustic)', '3:50', ['acoustic']]]],
    ['Fog Bank', 2006, 168, [['Harbour Wall', '4:02'], ['Fog Bank (demo)', '4:31', ['demo']]]],
    ['Salt on the Window', 2007, 22, [['Paper Moon Tide', '3:33'], ['Kettle Song', '2:58'], ['Salt on the Window (live in Bergen)', '4:26', ['live']]]],
    ['Low Tide', 2007, 262, [['Undertow', '4:15'], ['Low Tide (radio session)', '4:49', ['session', 'live']]]],
    ['Tidewater', 2007, 322, [['Flood Year', '3:39'], ['Tidewater (remix)', '5:40', ['remix']], ['Breakwater', '4:04'], ['Small Craft Warning', '3:12']]]];
  const SF = { id: 'sf', title: 'Signal Found', year: 2003, hue: 282, fmt: 'MP3 · 320',
    off: [['Signal Found', '3:48'], ['Lighthouse Code', '4:10'], ['Ninety Miles of Static', '5:02'], ['Shortwave Hearts', '3:37'], ['The Pilot Boat', '4:21'], ['Weather Report', '3:29'], ['Morning Watch', '4:44'], ['Coastal Station', '3:58'], ['Antenna Song', '4:06'], ['Ice on the Mast', '4:52'], ['Gale Warning', '3:41'], ['Cold Front', '4:18'], ['Dead Reckoning', '5:11'], ['All Ships', '6:02']],
    ex: [['Morse Code Lullaby', '3:26', [], 'the Japanese CD, 2003'], ['Signal Found (live, 2004)', '4:02', ['live']], ['Lighthouse Code (live, 2004)', '4:31', ['live']], ['Shortwave Hearts (live, 2004)', '3:55', ['live']], ['The Pilot Boat (live, 2004)', '4:40', ['live']], ['Gale Warning (live, 2004)', '4:03', ['live']], ['All Ships (live, 2004)', '7:12', ['live']],
      ['Signal Found (demo)', '3:31', ['demo']], ['Antenna Song (demo)', '3:50', ['demo']], ['Cold Front (demo)', '4:02', ['demo']], ['Weather Report (demo)', '3:14', ['demo']], ['Four-track sketch (demo)', '2:41', ['demo']]],
    edition: 'Signal Found 20th Anniversary', mins: 61 };

  /* ---- shared bits ---- */
  const rchips = (vs, bonus, fold) => { vs = vs || []; fold = fold || 2; const sh = vs.slice(0, fold), more = vs.length - sh.length;
    if (!sh.length && !bonus) return ''; return '<span class="rv-vb">' + sh.map(k => '<span class="rv-v" style="--c:' + VC[k][0] + '">' + VC[k][1] + '</span>').join('') + (more ? '<span class="rv-vm">+' + more + '</span>' : '') + (bonus ? '<span class="rv-bonus">Bonus</span>' : '') + '</span>'; };
  const achips = (vs, bonus) => ((vs || []).length ? '<span class="vr-bs">' + vs.map(k => '<span class="vr-b" style="--c:' + VC[k][0] + '">' + VC[k][1] + '</span>').join('') + '</span>' : '') + (bonus ? '<span class="ed-bonus">Bonus</span>' : '');
  const tr = (n, t, len, vs, sub, bonus) => '<div class="mu-tr"><span class="n">' + n + '</span><span class="t"><span class="tl"><span class="rv-tt">' + esc(t) + '</span>' + rchips(vs, bonus) + '</span>' + (sub ? '<small>' + sub + '</small>' : '') + '</span><span class="ln">' + len + '</span><button class="mu-more">⋯</button></div>';
  const ph = inner => '<div class="ph rvf"><div class="sb"><span>12:30</span><span>▲ ● ▮</span></div>' + inner + '</div>';
  const split = (pri, label, icon) => '<div class="ed-split' + (pri ? ' pri' : '') + '"><span>' + icon + label + '</span><span>' + DOWN + '</span></div>';
  const lbl = (tag, h, body, w, cls) => '<div class="lbl" style="width:' + (w || 412) + 'px">' + (tag ? '<span class="tag ' + (cls || '') + '">' + tag + '</span>' : '') + (h ? '<h3>' + h + '</h3>' : '') + (body || '') + '</div>';
  const cap = (t, w) => '<div class="cap" style="width:' + (w || 412) + 'px">' + t + '</div>';
  const col = (...xs) => '<div class="ed-col">' + xs.join('') + '</div>';
  const P = s => '<p>' + s + '</p>';
  const bsides = KWS.reduce((a, s) => a.concat(s[3].map(b => [b[0], b[1], b[2], s[0]])), []);

  /* ---- the phone album page ---- */
  function phAlbum(al, o) {
    o = o || {};
    const n = al.off.length, num = (i) => o.plus ? '+' + (i + 1) : '';
    let h = '<div class="tint" style="background:linear-gradient(180deg,hsl(' + al.hue + ' 42% 20%) 0,var(--bg) 560px)"><div class="ed-top"><span class="bk">‹</span><div class="ed-cv" style="' + cov(al.id, al.hue) + '"></div></div><div class="mu-dbody">'
      + '<div class="mu-dt">' + esc(al.title) + '</div><div class="mu-dby">Harbour Lights</div>'
      + '<div class="mu-dm"><span>' + al.year + '</span><span>·</span><span>' + n + ' songs</span><span>·</span><span>' + al.mins + ' min</span>' + (o.noEx ? '' : '<span class="q">+ ' + al.ex.length + ' extra' + (al.ex.length > 1 ? 's' : '') + '</span>') + '</div>'
      + '<div class="ed-acts">' + split(true, 'Play album', PLAY) + split(false, 'Shuffle', SHUF) + '</div>'
      + (o.menu ? '<div class="ed-menu"><div class="on"><span>Play album<small>' + n + ' songs · the official order</small></span><span class="ck">✓</span></div><div><span>Play album + extras<small>' + (n + al.ex.length + (o.nB || bsides.length)) + ' songs · extras, then the B-sides</small></span></div></div>' : '')
      + '<div class="mu-trs">' + al.off.slice(0, o.cut || n).map((x, i) => tr(i + 1, x[0], x[1], x[2])).join('') + (o.cut ? '<div class="ed-fold" style="color:var(--ink-dim);font-weight:400">… ' + (n - o.cut) + ' more of the album</div>' : '') + '</div>';
    if (!o.noEx) h += '<div class="ed-div">Extras · ' + esc(al.edition) + (al.ex.length > 1 ? '<span class="n">' + al.ex.length + '</span>' : '') + '</div><div class="mu-trs" style="margin-top:0">' + al.ex.slice(0, o.exCut || 99).map((x, i) => tr(num(i), x[0], x[1], x[2])).join('') + (o.exCut && al.ex.length > o.exCut ? '<div class="ed-fold" style="color:var(--ink-dim);font-weight:400">… ' + (al.ex.length - o.exCut) + ' more extras</div>' : '') + '</div>';
    if (o.dir !== 'none') h += singles(o.dir || 'A', o);
    return ph(h + '</div></div>');
  }
  function singles(d, o) {
    const nB = o.nB || bsides.length, cards = o.cards || KWS;
    if (d === 'A') {
      let h = '<div class="mu-sec">Singles &amp; B-sides</div><div class="ed-rail">' + cards.map(s => '<div class="ed-sc"><div class="c" style="' + cov(s[0], s[2]) + '"></div><div class="t">' + esc(s[0]) + '</div><div class="y">' + s[1] + ' · Single</div></div>').join('') + '</div>';
      if (o.openB) h += '<div class="ed-fold" style="margin-top:14px">' + nB + ' B-sides<span class="x">Hide</span></div><div class="mu-trs" style="margin-top:0">' + bsides.map(b => tr('', b[0], b[1], b[2], 'from ' + esc(b[3]) + ' (single)')).join('') + '</div>';
      else h += '<div class="ed-fold" style="margin-top:14px">' + nB + ' B-sides<span class="x">Show ›</span></div>';
      return h;
    }
    if (d === 'B') return '<div class="mu-sec">Singles &amp; B-sides</div>' + KWS.map((s, i) => '<div class="ed-sg"><div class="h"><div class="c" style="' + cov(s[0], s[2]) + '"></div><div><div class="t">' + esc(s[0]) + '</div><div class="s">' + s[1] + ' · Single · ' + s[3].length + ' B-side' + (s[3].length > 1 ? 's' : '') + '</div></div><span class="x">' + (i === 0 ? '⌃' : '⌄') + '</span></div>'
      + (i === 0 ? '<div class="mu-trs" style="margin-top:0">' + s[3].map(b => tr('', b[0], b[1], b[2])).join('') + '</div>' : '') + '</div>').join('');
    return '<div class="mu-sec">B-sides<span style="margin-left:8px;color:var(--ink-dim);font-weight:500">' + nB + '</span></div><div class="mu-trs" style="margin-top:0">' + bsides.slice(0, 4).map(b => tr('', b[0], b[1], b[2], 'from ' + esc(b[3]))).join('') + '<div class="ed-fold"><span style="color:var(--accent-2)">Show all ' + nB + '</span></div></div>'
      + '<div class="mu-sec" style="font-size:15px;margin-top:18px">From ' + KWS.length + ' singles</div><div class="ed-from">' + KWS.map(s => '<span><i style="' + cov(s[0], s[2]) + '"></i>' + esc(s[0]) + '</span>').join('') + '</div>';
  }

  /* ---- the admin Tracks tab ---- */
  function adTracks(al, o) {
    o = o || {}; const n = al.off.length;
    const fmt = '<span class="mu-fmt">' + (al.id === 'kw' ? 'FLAC · 24 · 96 kHz' : 'MP3 · 320 · 44.1 kHz') + '</span>', ok = '<span class="mu-mt ok">✓ recording</span>', ly = '<span class="mu-ly">synced ✓</span>';
    const row = (i, x, ex) => '<tr><td class="n">' + (ex ? '' : i + 1) + '</td><td>' + esc(x[0]) + achips(x[2]) + (ex && x[3] ? '<div class="ed-first">first on ' + esc(x[3]) + '</div>' : ex ? '<div class="ed-first">on ' + esc(al.edition) + ' (2023)</div>' : '') + '</td><td class="num">' + x[1] + '</td><td>' + fmt + '</td><td>' + ok + '</td><td>' + ly + '</td></tr>';
    const line = o.chosen ? '<b>Official album: 12 songs, as on the Japanese CD</b><span class="ed-how">chosen by you</span><a href="#">Back to automatic</a>'
      : '<b>Official album: ' + n + ' songs, as on ' + (al.id === 'kw' ? '14 of 20' : '9 of 12') + ' releases</b><a href="#">Change…</a>';
    let rows = al.off.map((x, i) => (o.gap === i ? '<tr class="gap"><td class="n">' + (i + 1) + '</td><td colspan="5">not in library · ' + esc(x[0]) + ' ' + x[1] + '</td></tr>' : row(i, x))).join('');
    rows += '<tr class="ed-adiv"><td colspan="6"><span class="l">Extras · ' + esc(al.edition) + ' · ' + al.ex.length + '</span></td></tr>' + al.ex.slice(0, o.exCut || 99).map(x => row(0, x, true)).join('')
      + (o.exCut && al.ex.length > o.exCut ? '<tr><td></td><td colspan="5" class="dim tiny">… ' + (al.ex.length - o.exCut) + ' more extras</td></tr>' : '');
    return '<div class="fr" style="width:' + (o.w || 1040) + 'px"><div class="mu-pb"><div class="ed-acv" style="' + cov(al.id, al.hue) + '"></div><div><div class="mu-by">by <a href="#">Harbour Lights</a></div><h4 style="font-size:1.55rem;margin:2px 0 0">' + esc(al.title) + '</h4>'
      + '<div class="mu-pbm"><span>' + al.year + '</span><span class="sep">·</span><span>' + n + ' songs + ' + al.ex.length + ' extra' + (al.ex.length > 1 ? 's' : '') + '</span><span class="sep">·</span>' + fmt + '</div>'
      + '<div class="ed-off">release: ' + (al.id === 'kw' ? 'JP · 2006 · Fjordlight · Digital Media · 12 tracks' : 'XW · 2023 · Northern Wire · Digital Media · 26 tracks') + '</div><div class="ed-off">' + line + '</div></div></div>'
      + '<div class="mu-scroll"><table class="mu-tbl"><thead><tr><th>#</th><th>Title</th><th>Length</th><th>Format</th><th>Recording</th><th>Lyrics</th></tr></thead><tbody>' + rows + '</tbody></table></div>'
      + (o.singles ? adSingles() : '') + '</div>';
  }
  function adSingles(moved) {
    const how = ['MusicBrainz', 'MusicBrainz', 'by title', 'via remix', 'you'];
    return '<div class="k">Singles &amp; B-sides · 5 singles · 13 B-sides</div><table class="mu-tbl"><thead><tr><th>Single</th><th>Year</th><th>B-sides here</th><th>Linked</th><th></th></tr></thead><tbody>'
      + KWS.map((s, i) => '<tr><td><span style="display:inline-flex;align-items:center;gap:10px"><span style="width:30px;height:30px;border-radius:5px;display:inline-block;' + cov(s[0], s[2]) + '"></span>' + esc(s[0]) + '</span></td><td class="num">' + s[1] + '</td><td>' + s[3].length + ' · ' + s[3].map(b => esc(b[0])).slice(0, 2).join(', ') + (s[3].length > 2 ? ' …' : '') + '</td><td><span class="ed-how">' + how[i] + '</span></td><td><span class="btn sm ghost">Move to…</span></td></tr>').join('')
      + '</tbody></table><div class="tiny muted" style="margin-top:8px">The A-sides are not repeated: each is the album’s own song (one copy, §A6). <i>Linked</i> says how the single found this album: MusicBrainz’s <i>single from</i> · through a remix of an album song · its A-side’s title · picked by you.</div>';
  }
  function adPressings() {
    const R = [['JP', '2006-08-23', 'Fjordlight', 'CD', 12, '+ Lantern Swing', 'held'], ['NO', '2006-03-06', 'Fjordlight', 'CD', 11, 'the official tracklist'], ['XE', '2006-03-20', 'Fjordlight', 'CD', 11, 'the official tracklist'], ['XW', '2006-03-06', 'Fjordlight', 'Digital Media', 11, 'the official tracklist'], ['US', '2007-01-16', 'Grey Pier', 'CD', 11, 'the official tracklist'], ['XW', '2016-05-13', 'Fjordlight', '2×CD · 10th Anniversary', 24, '+ 13 (demos, live)']];
    return '<div class="fr" style="width:780px"><h4>Change the official album · Kite Weather</h4><div class="tiny muted">The pressings Find match… already lists. Automatic = the tracklist most official releases share: <b>14 of 20</b> have these 11 songs.</div>'
      + '<table class="mu-tbl" style="margin-top:12px"><thead><tr><th>Country</th><th>Date</th><th>Label</th><th>Format</th><th>Tracks</th><th>Against the official</th><th></th></tr></thead><tbody>'
      + R.map(r => '<tr><td>' + r[0] + '</td><td class="mu-mono" style="font-size:.76rem">' + r[1] + '</td><td>' + r[2] + '</td><td>' + r[3] + (r[6] ? ' <span class="chip" style="font-size:.64rem">in the library</span>' : '') + '</td><td class="num">' + r[4] + '</td><td class="' + (r[5][0] === '+' ? '' : 'dim') + '">' + r[5] + '</td><td>' + (r[5][0] === '+' ? '<span class="btn sm ghost">Use as the official album</span>' : r[1] === '2006-03-06' && r[0] === 'NO' ? '<span class="tiny muted">official now</span>' : '') + '</td></tr>').join('')
      + '<tr><td colspan="7" class="dim tiny">… 14 more releases</td></tr></tbody></table><div class="tiny muted" style="margin-top:8px">A pick reads <i>chosen by you · Back to automatic</i> on the album and is kept across runs. Songs on the held files that the pick doesn’t have become extras; songs the pick has and the files don’t become gap rows.</div></div>';
  }

  /* ---- the desktop album page ---- */
  function dkAlbum() {
    const r = (n, t, l, vs, s) => '<div class="r"><span class="n">' + n + '</span><span class="x"><span class="rv-tt">' + esc(t) + '</span>' + rchips(vs, false, 3) + (s ? '<small>' + s + '</small>' : '') + '</span><span class="l">' + l + '</span><span class="n">⋯</span></div>';
    return '<div class="dk rvf"><div class="sd"><div class="lights"><i></i><i></i><i></i></div><div class="sw"><span>Films</span><span class="on">Music</span></div>' + ['Listen', 'Playing', 'Artists', 'Albums', 'Songs', 'Genres', 'Playlists', 'Audiobooks'].map(x => '<div class="i' + (x === 'Albums' ? ' on' : '') + '">' + x + '</div>').join('') + '</div>'
      + '<div class="mn" style="background:linear-gradient(180deg,hsl(198 40% 18%) 0,var(--bg) 380px)"><div class="hd"><div class="c" style="' + cov('kw', 198) + '"></div><div><div class="ty">Album</div><div class="t">Kite Weather</div><div class="m">Harbour Lights · 2006 · 11 songs · 48 min <span style="color:var(--ink-dim)">+ 1 extra</span></div><div class="ed-acts">' + split(true, 'Play album', PLAY) + split(false, 'Shuffle', SHUF) + '</div></div></div>'
      + '<div class="tl">' + KW.off.map((x, i) => r(i + 1, x[0], x[1])).join('') + '<div class="ed-div">Extras · Japanese edition</div>' + r('', 'Lantern Swing', '3:44') + '</div>'
      + '<div class="mu-sec">Singles &amp; B-sides</div><div class="ed-rail" style="margin:0;padding:0">' + KWS.map(s => '<div class="ed-sc"><div class="c" style="' + cov(s[0], s[2]) + '"></div><div class="t">' + esc(s[0]) + '</div><div class="y">' + s[1] + ' · Single</div></div>').join('') + '</div>'
      + '<div class="ed-fold" style="margin-top:14px">13 B-sides<span class="x">Show ›</span></div></div></div>';
  }

  /* ---- songs, one copy ---- */
  const mSong = (t, s, vs, bonus, seed, hue) => '<div class="mu-song"><span style="width:46px;height:46px;border-radius:7px;flex:none;' + cov(seed, hue) + '"></span><div class="b" style="flex:1;min-width:0"><div class="t" style="display:flex;align-items:center;font-size:14.5px;font-weight:600"><span class="rv-tt">' + esc(t) + '</span>' + rchips(vs, bonus) + '</div><div class="s" style="font-size:12.5px;color:var(--ink-dim);margin-top:3px">' + s + '</div></div><span class="ln" style="font-size:13px;color:var(--ink-dim)">⋯</span></div>';
  function phSongs() {
    return ph('<div class="mu-dbody" style="padding-top:6px"><div class="mu-dt" style="font-size:22px">Songs</div><div class="mu-dm">385 songs</div><div style="margin:10px -8px 0">'
      + mSong('Lantern Swing', 'Harbour Lights — Kite Weather', [], true, 'kw', 198)
      + mSong('Low Tide', 'Harbour Lights — Kite Weather', [], false, 'kw', 198)
      + mSong('Low Tide (radio session)', 'Harbour Lights — Low Tide (single)', ['session', 'live'], false, 'Low Tide', 262)
      + mSong('Morse Code Lullaby', 'Harbour Lights — Signal Found', [], true, 'sf', 282)
      + mSong('Northern Line', 'Harbour Lights — Kite Weather', [], false, 'kw', 198)
      + mSong('Northern Line (acoustic)', 'Harbour Lights — Northern Line (single)', ['acoustic'], false, 'Northern Line', 210)
      + mSong('Signal Found (live, 2004)', 'Harbour Lights — Signal Found', ['live'], true, 'sf', 282)
      + mSong('Untitled Four (demo)', 'Harbour Lights — Tide Tables 1999–2012', ['demo', 'alternate', 'acoustic'], true, 'tt', 40)
      + '</div></div>');
  }
  function phSheet() {
    return ph('<div style="height:330px;background:linear-gradient(180deg,#141826,#0a0c13);opacity:.5"></div><div class="ed-sheet"><div class="g"></div><div style="display:flex;gap:12px;align-items:center"><span style="width:52px;height:52px;border-radius:8px;' + cov('kw', 198) + '"></span><div><div class="h">Northern Line</div><div style="font-size:13px;color:var(--ink-dim)">Harbour Lights — Kite Weather</div></div></div>'
      + ['Play next', 'Add to queue', 'Go to album', 'Go to artist'].map(x => '<div class="r">' + x + '</div>').join('')
      + '<div class="mu-sec" style="font-size:14px;margin:18px 0 6px;color:var(--ink-soft)">Also on 2 releases</div>'
      + '<div class="r"><span class="c" style="' + cov('Northern Line', 210) + '"></span><span>Northern Line<small>Single · 2006</small></span></div>'
      + '<div class="r"><span class="c" style="' + cov('tt', 40) + '"></span><span>Tide Tables 1999–2012<small>Box set · 2012</small></span></div></div>');
  }
  function phPlaying() {
    return '<div class="ed-strip rvf" style="width:412px;box-sizing:border-box"><div class="cap" style="color:var(--ink-soft);margin:4px 0 6px">Now playing · the line under the title</div><div class="ed-np" style="padding:6px 0 12px"><div class="t">Signal Found (live, 2004)</div><div class="s">' + rchips(['live'], true, 9).replace('class="rv-vb"', 'class="rv-vb" style="margin:0"') + '<span>Harbour Lights · Signal Found</span></div></div>'
      + '<div class="cap" style="color:var(--ink-soft);margin:6px 0 2px">Queue row</div>' + '<div style="margin:0 -8px">' + mSong('Morse Code Lullaby', 'Harbour Lights', [], true, 'sf', 282) + '</div>'
      + '<div class="cap" style="color:var(--ink-soft);margin:10px 0 0">Mini bar · no chips (R344’s rule)</div><div class="ed-mini"><span class="c" style="' + cov('sf', 282) + '"></span><div><div class="t">Signal Found (live, 2004)</div><div class="s">Harbour Lights</div></div><span style="font-size:20px">❚❚</span></div></div>';
  }
  function adSongs(every) {
    const g = (t, al, kind, vs, bonus, copies, dim, ind) => '<tr' + (dim ? ' style="opacity:.62"' : '') + '><td>' + (ind ? '<span style="display:inline-block;width:18px;color:var(--ink-dim)">↳</span>' : '') + esc(t) + achips(vs, bonus) + '</td><td class="dim">Harbour Lights</td><td class="dim">' + esc(al) + (kind ? ' <span class="dim tiny">· ' + kind + '</span>' : '') + '</td><td class="dim tiny">' + (copies || '') + '</td></tr>';
    let rows = '';
    if (!every) rows = g('Lantern Swing', 'Kite Weather', '', [], true) + g('Low Tide', 'Kite Weather', '', [], false, 'also on 2 releases') + g('Low Tide (radio session)', 'Low Tide', 'single', ['session', 'live']) + g('Morse Code Lullaby', 'Signal Found', '', [], true, 'also on 1 release') + g('Northern Line', 'Kite Weather', '', [], false, 'also on 2 releases') + g('Northern Line (acoustic)', 'Northern Line', 'single', ['acoustic']) + g('Salt on the Window', 'Kite Weather', '', [], false, 'also on 9 releases');
    else rows = g('Lantern Swing', 'Kite Weather', '', [], true) + g('Low Tide', 'Kite Weather', '', [], false, 'shown in lists') + g('Low Tide', 'Low Tide', 'single', [], false, 'same recording · MusicBrainz', true, true) + g('Low Tide', 'Tide Tables 1999–2012', 'box set', [], false, 'same recording · MusicBrainz', true, true) + g('Low Tide (radio session)', 'Low Tide', 'single', ['session', 'live']) + g('Northern Line', 'Kite Weather', '', [], false, 'shown in lists') + g('Northern Line', 'Northern Line', 'single', [], false, 'same recording · MusicBrainz', true, true) + g('Northern Line', 'Nordic Nights Vol. 2', 'compilation', [], false, 'sounds the same', true, true);
    return '<div class="fr" style="width:780px"><div style="display:flex;align-items:center;gap:12px;flex-wrap:wrap"><h4 style="margin:0">Library → Music → Songs</h4><span class="tiny muted">' + (every ? '487 files · 385 songs' : '385 songs · 102 copies folded away') + '</span><span style="flex:1"></span><span class="ed-sw' + (every ? ' on' : '') + '"><i></i>Show every copy</span></div>'
      + '<table class="mu-tbl" style="margin-top:12px"><thead><tr><th>Title</th><th>Artist</th><th>Album</th><th>' + (every ? 'Why it is the same song' : 'Copies') + '</th></tr></thead><tbody>' + rows + '</tbody></table></div>';
  }
  function adPanel() {
    const c = (seed, hue, t, s, why, act) => '<div class="ed-copy"><span class="c" style="' + cov(seed, hue) + '"></span><div><div class="t">' + t + '</div><div class="s">' + s + '</div></div><div style="display:flex;gap:6px;align-items:center">' + why + (act || '') + '</div></div>';
    return '<div class="fr" style="width:560px"><div style="display:flex;align-items:center;gap:10px"><h4 style="margin:0">Northern Line</h4><span class="tiny muted">Harbour Lights · 3:58</span><span style="flex:1"></span><span class="tiny muted">✕</span></div>'
      + '<div class="k">One song · 4 copies</div>'
      + c('kw', 198, 'Kite Weather <span class="chip" style="font-size:.62rem">shown in lists</span>', 'Album · 2006 · track 2 · FLAC 24/96', '<span class="ed-why">the album’s own</span>')
      + c('Northern Line', 210, 'Northern Line', 'Single · 2006 · track 1 · MP3 320', '<span class="ed-why">same recording · MusicBrainz</span>', '<span class="btn sm ghost">Not the same song</span>')
      + c('tt', 40, 'Tide Tables 1999–2012', 'Box set · 2012 · disc 2, track 4 · FLAC 16/44', '<span class="ed-why">same recording · MusicBrainz</span>', '<span class="btn sm ghost">Not the same song</span>')
      + c('nn', 330, 'Nordic Nights Vol. 2', 'Compilation · 2009 · track 7 · no trusted recording', '<span class="ed-why">sounds the same</span>', '<span class="btn sm ghost">Not the same song</span>')
      + '<div class="tiny muted" style="margin-top:12px;line-height:1.6">Kept copy: the better file (owner, Q5) — here the album’s FLAC 24/96; on a tie the album’s copy, then extra, single, compilation / box set, live album. Facts are shared only between the <b>same MusicBrainz recording</b> (292 Q7): versions and lyrics never spread to the compilation’s copy.</div>'
      + '<div style="margin-top:12px;display:flex;gap:8px"><span class="btn sm">Same song as…</span></div></div>';
  }
  function adDash() {
    return '<div class="fr" style="width:780px"><div class="ov-row sev-info"><span class="ov-sev"></span><div class="ov-main"><div class="ov-l"><a href="#">Songs that may be the same</a></div><div class="ov-s">Two different MusicBrainz recordings that sound near-identical — Fog Bank on the album and on its single, and two more. Never joined until you say so.</div></div><span class="ov-n"><b>3</b> pairs</span><div class="ov-act"><span class="ov-fix k-here">One click here</span><span class="btn sm">Listen and decide</span></div></div></div>';
  }
  function adCompare(no) {
    const pl = (t, s, s2) => '<div class="ed-pl"><div style="display:flex;align-items:center;gap:10px"><span class="mu-play">▶</span><div><div class="t">' + t + '</div><div class="s">' + s + '</div></div></div><div class="ed-wave"></div><div class="s" style="margin-top:8px">' + s2 + '</div></div>';
    return '<div class="fr" style="width:620px"><div style="display:flex;align-items:center"><h4 style="margin:0">These sound the same: one song?</h4><span style="flex:1"></span><span class="tiny muted">1 of 3</span></div>'
      + '<div class="ed-cmp">' + pl('Fog Bank', 'Kite Weather · album · 4:12', 'recording “Fog Bank” · MusicBrainz') + pl('Fog Bank', 'Fog Bank · single · 4:11', 'recording “Fog Bank (single version)” · MusicBrainz') + '</div>'
      + '<div class="tiny muted" style="margin-top:10px">Both play from the same second, so you hear the same bar. MusicBrainz lists these as two recordings; the audio says they may be one.</div>'
      + (no ? '<div class="note" style="margin-top:12px"><b>Kept apart.</b> You said these are two songs — they stay two rows and this pair is never asked about again.</div>' : '<div style="display:flex;gap:8px;margin-top:14px"><span class="btn sm primary">Yes, one song</span><span class="btn sm">No, two songs</span><span style="flex:1"></span><span class="btn sm ghost">Later</span></div>') + '</div>';
  }
  function phArtist(after) {
    const list = after ? [['Weathervane', 2003, 'Single'], ['Small Hours Radio', 2009, 'Single'], ['The Long Winter: Thaw', 2004, 'Single · soundtrack'], ['Harbour Lights', 1999, 'EP'], ['Boathouse Tapes', 2001, 'EP'], ['Lanterns', 2013, 'EP']]
      : KWS.map(s => [s[0], s[1], 'Single']).concat([['Signal Found', 2003, 'Single'], ['Ninety Miles', 2003, 'Single'], ['Weathervane', 2003, 'Single']]);
    return '<div class="ed-strip rvf" style="width:412px;box-sizing:border-box"><div class="mu-sec" style="margin-top:6px">Singles &amp; EPs<span style="margin-left:8px;color:var(--ink-dim);font-weight:500">' + (after ? 6 : 49) + '</span></div><div class="ed-rail" style="margin:0 -16px;padding:0 16px 6px">'
      + list.map(s => '<div class="ed-sc"><div class="c" style="' + cov(s[0], hash(s[0]) % 360) + '"></div><div class="t">' + esc(s[0]) + '</div><div class="y">' + s[1] + ' · ' + s[2] + '</div></div>').join('') + '</div>'
      + (after ? '<div style="font-size:13px;color:var(--ink-soft);margin:8px 0 4px;line-height:1.55">The other 43 live under their album — <i>Kite Weather</i>’s five are in its Singles &amp; B-sides.</div>' : '') + '</div>';
  }

  /* ---- states ---- */
  const st = (c, body, w) => col(cap(c, w || 412), body);
  const strip = (inner, w) => '<div class="ed-strip rvf" style="width:' + (w || 412) + 'px;box-sizing:border-box">' + inner + '</div>';
  const STATES = [
    st('1 · Held as the standard edition · no divider', strip('<div class="mu-dm" style="margin:2px 0 8px">2003 · 9 songs · 41 min</div><div class="mu-trs" style="margin:0">' + [['Paper Lanterns', '4:10'], ['The Long Way Round', '3:31'], ['Harbour Song', '3:58']].map((x, i) => tr(i + 7, x[0], x[1])).join('') + '</div><div class="mu-sec">Singles &amp; B-sides</div>')),
    st('2 · Super deluxe · 11 + 10 extras, most with versions', strip('<div class="ed-div" style="margin-top:4px">Extras · super deluxe<span class="n">10</span></div><div class="mu-trs" style="margin:0">' + [['Flood Year (alternate take)', '4:44', ['alternate']], ['Undertow (acoustic)', '3:58', ['acoustic']], ['Gull Year (Pier remix)', '6:10', ['remix']], ['Tidewater (alternate mix)', '4:51', ['alternate']]].map(x => tr('', x[0], x[1], x[2])).join('') + '<div class="ed-fold" style="color:var(--ink-dim);font-weight:400">… 6 more extras</div></div>')),
    st('3 · The held edition lacks an official song (admin)', '<div class="fr" style="width:520px"><table class="mu-tbl"><tbody><tr><td class="n">8</td><td>Coastal Station</td><td class="num">3:58</td></tr><tr class="gap"><td class="n">9</td><td colspan="2">not in library · Antenna Song 4:06</td></tr><tr><td class="n">10</td><td>Ice on the Mast</td><td class="num">4:52</td></tr><tr class="ed-adiv"><td colspan="3"><span class="l">Extras · Signal Found 20th Anniversary · 12</span></td></tr></tbody></table><div class="tiny muted" style="margin-top:6px">The gap sits above the divider, in its official place. Ravilo draws no gap: the phone lists the songs it can play.</div></div>', 520),
    st('4 · The owner picked another release (admin)', '<div class="fr" style="width:520px"><div class="ed-off" style="margin:0"><b>Official album: 12 songs, as on the Japanese CD</b><span class="ed-how">chosen by you</span><a href="#">Back to automatic</a></div><div class="tiny muted" style="margin-top:8px">Lantern Swing is now song 12: no extras, no divider, here and in Ravilo.</div></div>', 520),
    st('5 · Unmatched · a plain list, no divider, no singles', strip('<div class="mu-dm" style="margin:2px 0 8px">2009 · 9 songs · 37 min</div><div class="mu-trs" style="margin:0">' + [['Kvøld', '4:01'], ['Ljós', '3:12'], ['Nátt', '5:20']].map((x, i) => tr(i + 1, x[0], x[1])).join('') + '</div><div style="font-size:13px;color:var(--ink-dim);margin-top:10px">Positions from the files’ tags, as today.</div>')),
    st('6 · An album with no singles · no section', strip('<div class="mu-trs" style="margin:0">' + tr(10, 'The Slipway', '4:30') + tr(11, 'Last Ferry Out', '5:13') + '</div><div class="mu-sec">More from Harbour Lights</div>')),
    st('7 · A single moved to No album (admin)', '<div class="fr" style="width:520px"><table class="mu-tbl"><tbody><tr><td>Weathervane</td><td class="num">2003</td><td><span class="ed-how">No album · you</span></td><td><span class="btn sm ghost">Move to…</span></td></tr></tbody></table><div class="tiny muted" style="margin-top:6px">It leaves Salt on the Window’s section and returns to the artist’s Singles &amp; EPs row.</div></div>', 520),
    st('8 · A song with 10 copies · one row (admin)', '<div class="fr" style="width:520px"><table class="mu-tbl"><tbody><tr><td>Salt on the Window</td><td class="dim">Salt on the Window</td><td class="dim tiny">also on 9 releases</td></tr></tbody></table><div class="tiny muted" style="margin-top:6px">The side panel lists the nine, each opening its album.</div></div>', 520),
    st('9 · Tags name another recording, the audio is the album’s', '<div class="fr" style="width:520px"><div class="ed-copy" style="border-top:0"><span class="c" style="' + cov('Fog Bank', 168) + '"></span><div><div class="t">Fog Bank</div><div class="s">Single · 2006 · its tags name “Fog Bank (radio edit)”</div></div><span class="ed-why">sounds the same</span></div><div class="tiny muted" style="margin-top:4px">One row in lists; this line only in the admin.</div></div>', 520),
    st('10 · A suggestion answered No (admin)', adCompare(true), 620)];

  /* ---- panels ---- */
  const Q = [['Q1', 'Singles that belong to an album leave the artist’s Singles &amp; EPs row?', '<b>Yes</b> — drawn: 49 → 6 on the artist page (§D3).'],
    ['Q2', 'Shuffle follows the album / album + extras choice?', '<b>Yes</b>, the same ▾ menu on both buttons. The menu remembers per album, not per app.'],
    ['Q3', 'B-sides with <i>Play album + extras</i>?', '<b>Yes</b> (owner, against the lean). <i>Play album + extras</i> plays the album, its extras, then the B-sides in single order; no separate <i>Play B-sides</i> (my reading — one way to play them).'],
    ['Q4', 'Numbers on extra rows?', '<b>None.</b> The <i>+1, +2</i> variant (A·b) is declined.'],
    ['Q5', 'Which copy stands for a song in lists?', '<b>The better file first</b>, wherever it is from (owner, against the lean); on a tie the album’s copy, then extra, single, compilation / box set, live album.'],
    ['Q6', 'Bonus beside version chips?', '<b>After them</b>, same size, no colour. On the phone the version chips fold at two (+N) but <b>Bonus never folds into +N</b> — it is a different axis.'],
    ['Q7', 'Admin Songs folded by default?', '<b>Yes</b>, with <i>Show every copy</i>: copies indent under their song and say why.'],
    ['D1', 'Singles &amp; B-sides on the album page', '<b>A</b>: single cards, then one fold row <i>13 B-sides · Show</i>. B groups B-sides under each single. C lists B-sides first.']];
  const qPanel = '<div class="wpanel" style="width:1180px"><h3>Decided by the owner · 2026-10-04</h3><table><thead><tr><th>#</th><th>Question</th><th>Decision</th></tr></thead><tbody>' + Q.map(q => '<tr><td><b>' + q[0] + '</b></td><td>' + q[1] + '</td><td>' + q[2] + '</td></tr>').join('') + '</tbody></table></div>';
  const found = '<div class="wpanel" style="width:640px"><h3>Also found</h3><ul>'
    + '<li><b>The brief’s numbers are taken.</b> It names admin 303 and Ravilo R361. <i>main</i> took 303 (a title’s Checks card), R360 (no cast icon) and R361–R367 (the TV D-pad sweep), and our playback-sessions specs now hold R368–R372 + 304. <b>Next free: 305 / R373.</b></li>'
    + '<li><b>Stand-ins reconciled (owner).</b> <i>music-data.js</i> now files <i>Kite Weather</i> (2006, 11) and <i>Signal Found</i> (2003, 14) under Harbour Lights, as the brief does; Petra Lind keeps a compilation track.</li>'
    + '<li><b>The Bonus chip has no hue</b> so it never reads as a tenth version type. Demo’s slate (#a3aec6) is the nearest colour; Bonus is ink on a fill, not a tint.</li>'
    + '<li><b>The header count stays the album’s</b> (<i>11 songs · 48 min</i>) with <i>+ 1 extra</i> after it in the quieter ink.</li>'
    + '<li><b>Ravilo has no gap rows</b> today. A missing official song is simply not listed on the phone and desktop; the admin draws the gap.</li></ul></div>';
  const strings = '<div class="wpanel" style="width:640px"><h3>Strings · English (§E)</h3><p style="font-size:.84rem;line-height:1.7;color:#2b2f3b;margin:0">' + ['Play album', 'Play album + extras', 'Extras', 'Extras · {edition}', '+ {n} extra(s)', 'Bonus', 'Japanese edition', 'first on {release}, {year}', 'Singles &amp; B-sides', '{n} B-sides', 'Official album: {n} songs, as on {k} of {m} releases', 'Use as the official album', 'chosen by you', 'Back to automatic', 'also on {n} releases', 'Show every copy', 'No album', 'same recording · MusicBrainz', 'sounds the same', 'you said so', 'Same song as…', 'Not the same song', 'These sound the same: one song?', 'Songs that may be the same'].map(s => '<i>' + s + '</i>').join(' · ') + '</p><p style="font-size:.8rem;color:#4a4f5e;margin:10px 0 0">No new strings beside the brief’s. Danish and Faroese go in the spec as drafts.</p></div>';

  /* ---- the canvas ---- */
  const S = [];
  S.push('<div class="ed-row">' + lbl('jellystructure · Ravilo · music editions &amp; duplicates · round 1 · 2026-10-04', '', '<h2 style="margin:0">The official album, its extras, its singles — and one copy of every song</h2>'
    + P('From <b>specs/design-brief-music-editions-and-duplicates-2026-10-03.md</b>. The owner settled the shape on 10-03 (§A): the official album first, <b>a thin divider</b>, the extras, then <b>Singles &amp; B-sides</b>; <i>Play album</i> plays the official album with <i>Play album + extras</i> beside it; a <b>Bonus</b> chip on an extra outside its own album; every song shown once (a song is its MusicBrainz recording, then its audio, then your say). What is left for round 1 is how the singles part reads, and the seven §G questions — drawn at their leans, with the variants beside them.')
    + P('<b>Read:</b> §D1 three directions for the phone album page (A is the lean, everything below is drawn in A) · the same page with weight and the menu open, and on the Mac · §D2 the admin Tracks tab · §D3 the artist page · §D4–D5 one copy everywhere and the Bonus chip · §D6 the states · the calls.'), 1500) + '</div>');
  S.push('<div class="rule" style="width:1500px"></div>' + lbl('§D1 · the album page on the phone', 'Singles &amp; B-sides — three directions', P('Kite Weather, held as the Japanese edition (24/96): 11 songs + 1 extra, five singles with 13 B-sides between them. The top half is the owner’s and is the same in all three.'), 1300));
  S.push('<div class="ed-row">'
    + col(cap('D1·A · cards, then one fold row <span class="lean">PICKED</span>'), phAlbum(KW, { dir: 'A' }), lbl('', 'A · Cards, then a fold', P('The five singles as cards (cover, title, year), then one row: <b>13 B-sides · Show</b>. Opened, each B-side says which single it is from.') + '<span class="cost"><b>Cost:</b> the cards and the B-sides are two separate things to read; the link between a B-side and its single is a second line, not a place.</span>'))
    + col(cap('D1·B · B-sides under their single'), phAlbum(KW, { dir: 'B' }), lbl('', 'B · Grouped by single', P('One row per single (cover, year, <i>2 B-sides</i>); tapping one opens its B-sides beneath it. Where a song came from is where it sits.') + '<span class="cost"><b>Cost:</b> five taps to see all 13; the single itself is no longer a card you open, and there is no single place to play every B-side.</span>'))
    + col(cap('D1·C · B-sides first'), phAlbum(KW, { dir: 'C' }), lbl('', 'C · The songs first, the singles as chips', P('The B-sides are rows straight away (four, then <i>Show all 13</i>), and the singles shrink to chips that open them.') + '<span class="cost"><b>Cost:</b> 25 B-sides on the oldest album still need the fold; the singles’ covers — the household’s way to recognise them — become thumbnails.</span>'))
    + '</div>');
  S.push('<div class="rule" style="width:1500px"></div>' + lbl('§D1 · drawn in A', 'The same page with weight, the Play menu, and on the Mac', P('Signal Found: 14 songs + 12 extras from <i>Signal Found 20th Anniversary</i>, most with version chips. In its own extras section an extra carries <b>no Bonus chip</b> — the divider already says it (§D5).'), 1300));
  S.push('<div class="ed-row">'
    + col(cap('A·a · Signal Found · 12 extras · Play ▾ open'), phAlbum(SF, { dir: 'A', menu: true, cut: 4, nB: 25, cards: [['Signal Found', 2003, 282], ['Lighthouse Code', 2003, 300], ['Ninety Miles', 2003, 250], ['Shortwave Hearts', 2004, 15], ['Gale Warning', 2004, 60]] }), lbl('', '', P('The ▾ menu: <b>Play album</b> (ticked) · <b>Play album + extras</b>, the extras after the album in their divider’s order (Q2: Shuffle’s ▾ offers the same). The header still counts the album: <i>14 songs · 61 min + 12 extras</i>.')))
    + col(cap('A·b · Q4 variant · quiet +1 · B-sides opened'), phAlbum(KW, { dir: 'A', plus: true, openB: true, cut: 3 }), lbl('', '', P('Q4’s other answer: <b>+1</b> in the number column, in the dim ink. Opened, the 13 B-sides list with their single as a second line. The A-sides never repeat (they are the album’s own songs).')))
    + col(cap('A·c · Ravilo Desktop · Expanded 1000', 1000), dkAlbum(), lbl('', '', P('R337’s album page: the same order and the same split buttons, a three-chip fold on the wider rows. The Medium rail and Compact (= the phone) need nothing new.'), 1000))
    + '</div>');
  S.push('<div class="rule" style="width:2500px"></div>' + lbl('§D2 · admin · album → Tracks', 'The official tracklist, extras with their first release, singles with how they were linked', P('One line under the release line names the official album and how it was found; <b>Change…</b> opens the pressing list with <b>Use as the official album</b>. Extras sit under the divider with where they first appeared.'), 1300));
  S.push('<div class="ed-row">' + col(cap('D2·a · Kite Weather · Tracks', 1040), adTracks(KW, { singles: true })) + col(cap('D2·b · Change… · the pressings', 780), adPressings(), cap('D2·c · Signal Found · a gap above the divider', 780), adTracks(SF, { gap: 8, exCut: 4, w: 780 })) + '</div>');
  S.push('<div class="rule" style="width:2500px"></div>' + lbl('§D3 · the artist page · Q1', 'Singles that found their album leave the row', P('Harbour Lights: 43 of 49 singles and EPs find their album. The row keeps six: 2 singles on no album, 1 single from another artist’s soundtrack, 3 EPs. The admin’s <i>Singles &amp; EPs</i> section follows the same rule.'), 1300));
  S.push('<div class="ed-row">' + col(cap('D3·a · today · 49'), phArtist(false)) + col(cap('D3·b · after · 6 <span class="lean">LEAN</span>'), phArtist(true)) + '</div>');
  S.push('<div class="rule" style="width:2500px"></div>' + lbl('§D4 – D5 · one copy everywhere, and the Bonus chip', 'Lists show a song once; the admin can see every copy and why', P('487 songs → <b>385</b> shown, 102 copies folded away. <b>Bonus</b> sits after the version chips in song rows, Now playing’s line and the queue — never on the mini bar, the lock screen or the TV.'), 1300));
  S.push('<div class="ed-row">' + col(cap('D4·a · Ravilo · Songs'), phSongs()) + col(cap('D4·b · a song’s ⋯ · also on'), phSheet()) + col(cap('D5 · Now playing · queue · mini bar'), phPlaying())
    + col(cap('D4·c · admin Songs · folded', 780), adSongs(false), cap('D4·d · Show every copy', 780), adSongs(true)) + col(cap('D4·e · side panel · the copies and why', 560), adPanel()) + col(cap('D4·f · Dashboard · 285’s grammar', 780), adDash(), cap('D4·g · Listen and decide', 620), adCompare(false)) + '</div>');
  S.push('<div class="rule" style="width:2500px"></div>' + lbl('§D6 · states', 'Ten states from the brief', '', 900));
  S.push('<div class="ed-row" style="flex-wrap:wrap;width:2600px;row-gap:36px">' + STATES.join('') + '</div>');
  S.push('<div class="rule" style="width:2500px"></div><div class="ed-row">' + qPanel + found + strings + '</div>');

  const root = document.getElementById('root');
  root.innerHTML = S.map(s => '<div class="sec">' + s + '</div>').join('');
  function layout() { let y = 0; [...root.children].forEach(el => { el.style.top = y + 'px'; y += el.offsetHeight + 40; }); }
  layout(); if (document.fonts) document.fonts.ready.then(layout);
  window.addEventListener('click', e => { if (e.target.closest('.fr,.ph,.dk,.ed-strip')) { e.preventDefault(); e.stopPropagation(); } }, true);
})();
