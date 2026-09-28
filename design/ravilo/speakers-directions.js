/* Speakers — round-1 directions (research-reports/music-cast-to-speakers-2026-09-28 §7/§8; prospective 286 · R324).
   Builds every frame of the canvas from the shared music stand-in data (../app/music-data.js). Static frames only:
   nothing here is wired into Ravilo Mobile.html until the §8 picks come back. Every name is a household stand-in. */
(function () {
  const M = window.MUSIC, root = document.getElementById('sp-root');
  const A = M.album('salt-on-the-window'), TS = M.tracksOf(A.id), ART = M.artist(A.artistId).name;
  const B2 = M.album('glass-birds');
  const cov = (al, extra) => '<div class="' + (extra || '') + '" style="' + M.coverStyle(al) + '"></div>';
  const G = {
    speaker: '<svg viewBox="0 0 24 24"><rect x="6" y="2.5" width="12" height="19" rx="3"></rect><circle cx="12" cy="14.5" r="3.2"></circle><circle cx="12" cy="7" r="1"></circle></svg>',
    group: '<svg viewBox="0 0 24 24"><rect x="2.5" y="5" width="8.5" height="15" rx="2.4"></rect><rect x="13" y="5" width="8.5" height="15" rx="2.4"></rect><circle cx="6.75" cy="14" r="2.1"></circle><circle cx="17.25" cy="14" r="2.1"></circle></svg>',
    tv: '<svg viewBox="0 0 24 24"><rect x="2" y="4" width="20" height="13" rx="2"></rect><path d="M9 21h6"></path></svg>',
    hub: '<svg viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="11" rx="2"></rect><path d="M7 15l-2 5h14l-2-5"></path></svg>',
    go: '<svg viewBox="0 0 24 24"><path d="M9 6l6 6-6 6"></path></svg>',
    down: '<svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"></path></svg>',
    plus: '<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"></path></svg>',
    info: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"></circle><path d="M12 11v5M12 8h.01"></path></svg>',
    play: '<svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"></path></svg>',
    pause: '<svg viewBox="0 0 24 24"><path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z"></path></svg>',
    prev: '<svg viewBox="0 0 24 24"><path d="M6 5h2v14H6zM20 5v14L9 12z"></path></svg>',
    next: '<svg viewBox="0 0 24 24"><path d="M16 5h2v14h-2zM4 5v14l11-7z"></path></svg>',
    shuffle: '<svg viewBox="0 0 24 24"><path d="M16 3h5v5M4 20L21 3M21 16v5h-5M15 15l6 6M4 4l5 5"></path></svg>',
    repeat: '<svg viewBox="0 0 24 24"><path d="M17 1l4 4-4 4"></path><path d="M3 11V9a4 4 0 0 1 4-4h14M7 23l-4-4 4-4"></path><path d="M21 13v2a4 4 0 0 1-4 4H3"></path></svg>',
    lyr: '<svg viewBox="0 0 24 24"><path d="M4 6h12M4 11h9M4 16h6"></path><circle cx="17.5" cy="16.5" r="2.5"></circle><path d="M20 16.5V8l-2 1"></path></svg>',
    queue: '<svg viewBox="0 0 24 24"><path d="M4 6h16M4 12h10M4 18h10M18 14v6l4-3z"></path></svg>',
    more: '<svg viewBox="0 0 24 24"><circle cx="5" cy="12" r="1.3"></circle><circle cx="12" cy="12" r="1.3"></circle><circle cx="19" cy="12" r="1.3"></circle></svg>',
    vol: '<svg viewBox="0 0 24 24"><path d="M11 5L6 9H2v6h4l5 4z"></path><path d="M15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14"></path></svg>',
    phone: '<svg viewBox="0 0 24 24"><rect x="6" y="2" width="12" height="20" rx="2.5"></rect><path d="M11 18h2"></path></svg>',
    stop: '<svg viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="2"></rect></svg>',
    album: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"></circle><circle cx="12" cy="12" r="2.5"></circle></svg>',
    artist: '<svg viewBox="0 0 24 24"><circle cx="12" cy="8" r="4"></circle><path d="M4 21a8 8 0 0 1 16 0"></path></svg>',
    search: '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"></circle><path d="M20 20l-4-4"></path></svg>',
  };
  const castic = conn => '<span class="castic' + (conn ? ' conn' : '') + '"></span>';
  const NAV = [['Listen', '<svg viewBox="0 0 24 24"><path d="M4 15v-3a8 8 0 0 1 16 0v3"></path><rect x="3" y="14" width="4" height="6" rx="1.5"></rect><rect x="17" y="14" width="4" height="6" rx="1.5"></rect></svg>'],
    ['Browse', '<svg viewBox="0 0 24 24"><rect x="4" y="4" width="7" height="7" rx="1.5"></rect><rect x="13" y="4" width="7" height="7" rx="1.5"></rect><rect x="4" y="13" width="7" height="7" rx="1.5"></rect><rect x="13" y="13" width="7" height="7" rx="1.5"></rect></svg>'],
    ['Playing', '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"></circle><path d="M10 8.5v7l5.5-3.5z"></path></svg>'],
    ['Queue', G.queue.replace('<svg viewBox="0 0 24 24">', '<svg viewBox="0 0 24 24">')],
    ['Profile', '<svg viewBox="0 0 24 24"><circle cx="12" cy="9" r="4"></circle><path d="M5 20a7 7 0 0 1 14 0"></path></svg>']];
  const nav = on => '<nav class="sp-bn">' + NAV.map((n, i) => '<div class="it' + (i === on ? ' on' : '') + '"><span class="p">' + n[1] + '</span><b>' + n[0] + '</b></div>').join('') + '</nav>';
  const sbar = t => '<div class="sp-sbar"><span>' + (t || '18.04') + '</span><span>▾ ▾ ▮</span></div>';

  let html = '';
  const put = s => { html += s; };
  const cap = (x, y, t, w) => put('<div class="cap" style="left:' + x + 'px;top:' + y + 'px' + (w ? ';width:' + w + 'px' : '') + '">' + t + '</div>');
  const note = (x, y, t, w) => put('<div class="sp-note" style="left:' + x + 'px;top:' + y + 'px' + (w ? ';width:' + w + 'px' : '') + '">' + t + '</div>');
  function phone(x, y, o, inner, label) {
    const ios = o.ios, skin = o.skin || 'aurora';
    put('<div class="dv ' + (ios ? 'ios' : 'and') + (skin === 'noir' ? ' noir' : '') + '" data-skin="' + skin + '" style="left:' + x + 'px;top:' + y + 'px" data-screen-label="' + label + '">'
      + (ios ? '<div class="isl"></div>' : '<div class="pnch"></div>') + '<div class="scn">' + inner + '</div>' + (ios ? '<div class="hind"></div>' : '') + '</div>');
  }

  /* ---------- page pieces ---------- */
  const tr = i => TS[i] || TS[0];
  function browsePage(o) {
    const albs = M.albums.filter(a => a.cover).slice(0, 6);
    return sbar() + '<div class="sp-top"><h1>Browse</h1><span class="sp"></span><span class="sp-ib' + (o.cast ? ' lit' : '') + '">' + castic(o.cast) + '</span></div>'
      + '<div class="sp-body"><div class="sp-field">' + G.search.replace('<svg', '<svg style="width:18px;height:18px;fill:none;stroke:currentColor;stroke-width:2.2"') + 'Songs, albums, artists</div>'
      + '<div class="sp-chips"><b class="on">Albums</b><b>Artists</b><b>Songs</b><b>Audiobooks</b></div>'
      + '<div class="sp-grid">' + albs.map(a => '<div class="sp-alb">' + cov(a, 'c') + '<div class="t">' + a.title + '</div><div class="a">' + M.artist(a.artistId).name + '</div></div>').join('') + '</div></div>';
  }
  function listenPage(o) {
    const albs = M.albums.filter(a => a.cover).slice(3, 7);
    return sbar() + '<div class="sp-top"><h1>Listen</h1><span class="sp"></span><span class="sp-ib' + (o.cast ? ' lit' : '') + '">' + castic(o.cast) + '</span></div>'
      + '<div class="sp-body"><div class="sp-h3">Recently played</div><div class="sp-grid">' + albs.map(a => '<div class="sp-alb">' + cov(a, 'c') + '<div class="t">' + a.title + '</div><div class="a">' + M.artist(a.artistId).name + '</div></div>').join('') + '</div></div>';
  }
  function mini(o) {
    const t = o.track || tr(0);
    return '<div class="sp-mini' + (o.gone ? ' gone' : '') + '">' + cov(A, 'c') + '<div class="tx"><div class="n">' + t.title + '</div><div class="d">' + ART
      + (o.dev ? ' <span class="on">· ' + G.speaker + o.dev + '</span>' : '') + '</div></div><span class="b">' + (o.paused ? G.play : G.pause) + '</span><span class="pg"><i style="width:' + (o.pct || 34) + '%"></i></span></div>';
  }
  function vidBar() { return '<div class="sp-mini vid"><div class="c"></div><div class="tx"><div class="n">Sommeren ’92</div><div class="d"><span class="on">' + castic(true) + 'Playing on Stue TV</span></div></div><span class="b">' + G.pause + '</span><span class="pg"><i style="width:58%"></i></span></div>'; }
  function nowPlaying(o) {
    const t = o.track || tr(0), pos = o.pos || 74, pct = Math.round(pos / t.len * 100);
    const devChip = o.dev ? '<div class="sp-dev">' + (o.devGlyph || G.speaker) + o.dev + '<svg class="cv" viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"></path></svg></div>' : '';
    let body;
    if (o.lyrics) {
      const L = M.LYRICS[t.id] || [];
      const i = Math.max(0, L.findIndex(l => l[0] > pos) - 1);
      body = '<div class="sp-lhead">' + cov(A, 'c') + '<div><div class="t">' + t.title + '</div><div class="s">' + ART + '</div>' + devChip + '</div></div>'
        + '<div class="sp-lyr">' + L.slice(Math.max(0, i - 2), i + 6).map((l, j) => '<p' + (L.indexOf(l) === i ? ' class="now"' : '') + '>' + l[1] + '</p>').join('') + '</div>';
    } else {
      body = '<div class="sp-kick">Playing from album<b>' + A.title + '</b></div>' + cov(A, 'sp-cov')
        + '<div class="sp-meta"><div class="b"><h3>' + t.title + '</h3><div class="ar">' + ART + '</div>' + devChip + '</div></div>';
    }
    return '<div class="sp-np"><div class="bgt" style="' + M.coverStyle(A) + '"></div>' + body
      + '<div class="sp-seek"><div class="tr"><i style="width:' + pct + '%"></i><u style="left:' + pct + '%"></u></div><div class="tm"><span>' + M.fmtLen(pos) + '</span><span>−' + M.fmtLen(t.len - pos) + '</span></div></div>'
      + '<div class="sp-tp"><span class="b sm">' + G.shuffle + '</span><span class="b">' + G.prev + '</span><span class="pp">' + (o.paused ? G.play : G.pause) + '</span><span class="b">' + G.next + '</span><span class="b sm">' + G.repeat + '</span></div>'
      + '<div class="sp-nbot"><span class="b' + (o.lyrics ? ' on' : '') + '">' + G.lyr + '</span><span class="b">' + G.queue + '</span><span class="b' + (o.dev ? ' on' : '') + '">' + castic(!!o.dev) + '</span><span class="b">' + G.more + '</span></div></div>';
  }
  const row = (ic, nm, st, cls, stc) => '<div class="sp-row ' + (cls || '') + '"><span class="ic">' + ic + '</span><span class="tx"><span class="nm">' + nm + '</span><span class="st ' + (stc || '') + '">' + st + '</span></span><span class="go">' + G.go + '</span></div>';
  function sheet(title, rows, extra) { return '<div class="scrim"></div><div class="sheet"><div class="grab"></div><div class="sh"><h4>' + title + '</h4></div>' + rows + (extra || '') + '</div>'; }
  const tail = '<div class="sp-col"><span>All your TVs</span><span style="color:var(--ink-dim)">(2)</span><span class="ct">' + G.down + '</span></div><div class="sp-add">' + G.plus + '<span>Add a TV</span></div>';

  /* ================= 0 · intro ================= */
  put('<div class="lbl w3" style="left:0;top:0"><span class="tag new">Round 1 · directions · 2026-09-28 · prospective 286 · R324</span><h2>Music on the household’s speakers</h2>'
    + '<small>The research report of 2026-09-28 (<b>music-cast-to-speakers</b>) measured the house: two Nest Wifi points that are Cast speakers — <b>Stue</b> (idle) and <b>Gæsteværelse</b> (playing Spotify) — a <b>Køkken hub</b> with a screen, and two TVs. The speakers are hidden today for one reason only: the Cast application is not registered for audio-only devices. <b>That is a checkbox on the existing application</b> — no new app, no new receiver URL. The receiver then runs headless on a speaker and <b>owns the queue</b>, so the phone can be closed and the music plays on; reopening it rebuilds the Playing and Queue tabs from the receiver (R245 FR-R245-5, unchanged).<br><br>'
    + 'This canvas draws §7: R265’s sheet with speaker rows · Now playing as the remote in three skins · the mini bar with <i>· Stue</i> · reconnect’s two outcomes · the Now playing screen for a display receiver (TV and hub) · four strings × three languages. The admin pieces (the Chromecast card’s step 5a and its registered-state line, a speaker row in Users &amp; devices, the Dashboard Services line) are drawn in the admin pages themselves. §8’s nine questions were <b>answered by the owner on 2026-09-28</b> — the frames now draw the picks (the table at the foot). <b>Nothing here is built into Ravilo Mobile.html yet.</b></small></div>');
  put('<div class="sp-panel" style="left:1500px;top:0;width:900px"><h2>Decided in the report — the same in every frame</h2><p class="lead">Not options: these are what the feature is, so the questions at the foot are the only things to judge.</p><table class="sp-tbl">'
    + '<tr><th style="width:190px">Rule</th><th>What the frames show</th></tr>'
    + '<tr><td><b>One sheet</b></td><td>R265’s sheet, unchanged in shape. A speaker is one more row in <i>On this network</i> with a speaker glyph; a group has a group glyph. No second picker, no speaker tab.</td></tr>'
    + '<tr><td><b>Video never goes to a speaker</b></td><td>In video mode speakers are <b>absent</b> from the sheet and the sheet says nothing about it (Google’s rule, and R265’s absent-not-empty).</td></tr>'
    + '<tr><td><b>A busy speaker names its app</b></td><td><i>Busy · Spotify</i> — the Cast status carries the running app’s display name, the way a TV names its viewer (R270). Dimmed, not tappable.</td></tr>'
    + '<tr><td><b>The receiver owns the queue</b></td><td>Transport, position and queue on the phone are the receiver’s. The phone can be closed; nothing on screen claims otherwise.</td></tr>'
    + '<tr><td><b>Lyrics stay on the phone</b></td><td>The speaker can’t show them; the position comes from the receiver, so they still scroll.</td></tr>'
    + '<tr><td><b>The TV’s remote works too</b></td><td>On a TV the receiver answers the D-pad (owner, Q8): OK pauses, ◀ ▶ skip, hold to seek, ▼ lyrics. The phone mirrors it within one report.</td></tr>'
    + '<tr><td><b>The iPhone has no speakers</b></td><td>No browser has a Cast sender (R265’s reason). The rows are absent; one footnote says so (Q7).</td></tr>'
    + '</table><div class="sp-warn"><b>Numbering, verified on main 2026-09-28:</b> admin tops at 283, Ravilo at R323 — <b>286</b> and <b>R324</b> are free (284 goes to the tags work, 285 to the Dashboard). <br><b>Two things I found while drawing:</b> (1) <span class="mono">cast.play_on_phone</span> already exists — R299 shipped <i>Play on this phone</i> as <span class="mono">cast_play_here</span> — so it is reused, and the fourth new string is Q7’s iPhone footnote. (2) The sheet’s title is <i>Play on a TV</i> (R265). With speakers in it that is wrong. Lean: <i>Play on…</i> in music mode — a fifth string the report did not count (drawn in A1).</div></div>');

  /* ================= A · the sheet ================= */
  let Y = 760;
  put('<div class="lbl w3" style="left:0;top:' + Y + 'px"><span class="tag">§7 · the sheet with speakers</span><h3>Five states · Pixel 9 and iPhone 16</h3><small>Rows as measured on the household LAN: Stue (idle, 25 %), Gæsteværelse (playing Spotify), the group <i>Hele huset</i> made in the Home app (Q5 — drawn, lean round 1), Køkken hub, Stue TV. Sheet opened from Browse in music mode.</small></div>');
  Y += 140;
  const rowsIdle = row(G.speaker, 'Stue', 'Speaker · Ready') + row(G.speaker, 'Gæsteværelse', 'Busy · Spotify') + row(G.group, 'Hele huset', 'Speaker group · Ready') + row(G.hub, 'Køkken hub', 'Ready') + row(G.tv, 'Stue TV', 'Ready');
  cap(0, Y, 'A1 · Idle speakers · a group · a busy speaker · music mode');
  phone(0, Y + 40, {}, browsePage({}) + nav(1) + sheet('Play on…', '<div class="sp-sec">On this network</div>' + rowsIdle, tail), 'Sheet · speakers idle');
  note(0, Y + 980, '<b>Gæsteværelse</b> is playing Spotify. The row is tappable (owner 2026-09-28): it asks <i>Stop Spotify and play here?</i> first (A2b). <b>Hele huset</b> is one route; the receiver runs on the group leader. The title reads <i>Play on…</i> because a speaker is not a TV.');
  cap(520, Y, 'A2 · A speaker already playing Ravilo · tap to join');
  const rowsJoin = row(G.speaker, 'Stue', 'Playing · ' + tr(4).title, '', 'play') + row(G.speaker, 'Gæsteværelse', 'Busy · Spotify', 'off') + row(G.group, 'Hele huset', 'Speaker group · Ready') + row(G.hub, 'Køkken hub', 'Ready') + row(G.tv, 'Stue TV', 'Ready');
  phone(520, Y + 40, {}, listenPage({}) + nav(0) + sheet('Play on…', '<div class="sp-sec">On this network</div>' + rowsJoin, tail), 'Sheet · join a speaker');
  note(520, Y + 980, 'Someone else in the house started the album on Stue. Tapping the row <b>joins</b>: this phone becomes a second remote for the same queue — nothing is replaced, nothing restarts.');
  cap(520, Y + 1080, 'A2b · Tap a busy speaker · it asks first (owner)');
  phone(520, Y + 1120, {}, listenPage({}) + nav(0) + '<div class="scrim"></div><div class="sheet"><div class="grab"></div><div class="sh"><h4>Stop Spotify and play here?</h4></div><div class="sp-foot" style="padding-top:0;font-size:14px;color:var(--ink-soft)"><span>Gæsteværelse is playing Spotify. Playing here stops it for whoever started it.</span></div><div class="sp-mrow" style="margin-top:12px">' + G.speaker + 'Play on Gæsteværelse</div><div class="sp-mrow mute">Cancel</div></div>', 'Sheet · busy speaker confirm');
  cap(1040, Y, 'A3 · Video mode · the same moment · speakers gone');
  const rowsVid = row(G.hub, 'Køkken hub', 'Ready') + row(G.tv, 'Stue TV', 'Ready');
  phone(1040, Y + 40, {}, sbar() + '<div class="sp-top"><h1>Home</h1><span class="sp"></span><span class="sp-ib">' + castic() + '</span></div><div class="sp-body"><div style="height:200px;border-radius:16px;background:linear-gradient(150deg,#2b3350,#161b2c 60%,#101420)"></div></div>'
    + sheet('Play on a TV', '<div class="sp-sec">On this network</div>' + rowsVid, tail), 'Sheet · video mode');
  note(1040, Y + 980, 'Video mode keeps R265’s title and rows. <b>No line explains the missing speakers</b> — nothing is greyed, nothing is promised. The hub stays: it has a screen.');
  cap(1560, Y, 'A4 · iPhone 16 · music mode · no Cast sender in a browser');
  phone(1560, Y + 40, { ios: true }, listenPage({}) + nav(0) + sheet('Play on…', '<div class="sp-sec">On this network</div>' + row(G.tv, 'Stue TV', 'Ready'),
    '<div class="sp-add">' + G.plus + '<span>Add a TV</span></div><div class="sp-foot">' + G.info + '<span>Speakers need the Android app for now</span></div><div class="sp-foot" style="padding-top:6px">' + G.info.replace('<circle cx="12" cy="12" r="9"></circle><path d="M12 11v5M12 8h.01"></path>', '<path d="M5 17a9 9 0 0 1 14 0"></path><path d="M12 13l4 7H8z"></path>') + '<span>AirPlay (phone must stay on)</span></div>'), 'Sheet · iPhone');
  note(1560, Y + 925, '<b>Q7 lean — a footnote once</b>, drawn the way R270 draws AirPlay’s. The alternative is to say nothing: the TVs are there, the speakers just aren’t. The footnote is the one new string that is not a row label.');
  cap(2080, Y, 'A5 · After the tap · Noir · the bar says it, then retires');
  phone(2080, Y + 40, { skin: 'noir' }, browsePage({ cast: true }) + '<div class="sp-cbar"><span class="sp2"></span>Sending to Stue…</div>' + mini({ dev: 'Stue', pct: 12 }) + nav(1), 'Sheet · sending to Stue');
  note(2080, Y + 980, 'R270’s two moments: <i>Sending to Stue…</i> → <i>Playing on Stue</i> for ~2 s, then only the mini bar’s <i>· Stue</i> is left. The song keeps its position — R245 FR-R245-4’s hand-off, for music (§6.9).');

  /* ================= B · Now playing as the remote ================= */
  Y = 2240;
  put('<div class="lbl w3" style="left:0;top:' + Y + 'px"><span class="tag">§7 · Now playing, casting</span><h3>The Playing tab becomes the remote · three skins · then its three extras</h3><small>The device chip sits under the artist and opens the sheet. Transport acts on the receiver; the position is the receiver’s report. The cast glyph in the bottom row is lit. Nothing else changes — the same screen R322 drew.</small></div>');
  Y += 140;
  const np = o => sbar() + nowPlaying(Object.assign({ dev: 'Stue' }, o)) + nav(2);
  cap(0, Y, 'B1 · Aurora · Playing on Stue');
  phone(0, Y + 40, {}, np({}), 'Now playing · Stue · Aurora');
  cap(520, Y, 'B2 · Midnight');
  phone(520, Y + 40, { skin: 'midnight' }, np({ pos: 118 }), 'Now playing · Stue · Midnight');
  cap(1040, Y, 'B3 · Noir · on the group');
  phone(1040, Y + 40, { skin: 'noir' }, np({ dev: 'Hele huset', devGlyph: G.group, pos: 160 }), 'Now playing · group · Noir');
  cap(1560, Y, 'B4 · ⋯ · volume, back to the phone, Stop casting (Q3 lean)');
  phone(1560, Y + 40, {}, np({}) + sheet('Stue', '<div class="sp-vol">' + G.vol + '<div class="tr"><i style="width:25%"></i><u style="left:25%"></u></div><span class="v">25 %</span></div>'
    + '<div class="sp-mrow">' + G.phone + 'Play on this phone</div><div class="sp-mrow stop">' + G.stop + 'Stop casting</div>'
    + '<div class="sp-mrow mute">' + G.plus + 'Add to playlist…</div><div class="sp-mrow mute">' + G.album + 'Go to album</div><div class="sp-mrow mute">' + G.artist + 'Go to artist</div>'), 'Now playing · menu');
  note(1560, Y + 980, 'The menu is titled with the device. The slider moves in <b>small steps</b> (audio devices expose the device volume, and Google requires small steps). <i>Play on this phone</i> pulls position and queue back and stops the speaker (§6.9). The song rows stay, dimmed here only to keep the eye on the new three.');
  cap(2080, Y, 'B5 · The phone’s own volume keys drive Stue');
  phone(2080, Y + 40, {}, np({}) + '<div class="sp-vhud"><small>Stue</small><div class="tr"><i style="height:30%"></i></div>' + G.vol + '</div>', 'Now playing · volume keys');
  note(2080, Y + 980, 'Android’s own panel, named <b>Stue</b> — the cast session hands the keys to the speaker. Nothing is drawn by Ravilo here; the frame exists so the step size (5 %) is agreed.');
  cap(2600, Y, 'B6 · Lyrics scrolling from the receiver’s position');
  phone(2600, Y + 40, { skin: 'midnight' }, sbar() + nowPlaying({ dev: 'Stue', lyrics: true, pos: 31 }) + nav(2), 'Now playing · lyrics · Stue');
  note(2600, Y + 980, 'The speaker can’t show lyrics; the phone does, and scrolls them by the position Stue reports. Close the phone and reopen it: the lyrics pick up at the line the room is hearing.');
  cap(3120, Y, 'B7 · The Queue tab mirrors the receiver’s queue');
  const qr = (t, now) => '<div class="sp-qrow' + (now ? ' now' : '') + '">' + cov(A, 'c') + '<div class="b"><div class="t">' + t.title + '</div><div class="s">' + ART + ' · ' + M.fmtLen(t.len) + '</div></div><span class="h">' + (now ? '' : '⋮⋮') + '</span></div>';
  phone(3120, Y + 40, {}, sbar() + '<div class="sp-top"><h1>Queue</h1><span class="sp"></span><span class="sp-ib lit">' + castic(true) + '</span></div><div class="sp-body">'
    + '<div class="sp-qlab">Now playing <span class="on">' + G.speaker + 'on Stue</span></div>' + qr(tr(0), true)
    + '<div class="sp-qlab">Up next · ' + (TS.length - 1) + ' songs · ' + M.fmtTotal(TS.slice(1).reduce((s, t) => s + t.len, 0)) + '</div>' + TS.slice(1, 8).map(t => qr(t)).join('') + '</div>' + mini({ dev: 'Stue' }) + nav(3), 'Queue · on Stue');
  note(3120, Y + 980, 'Drag and swipe-to-remove work as in R322, and each change is sent to the receiver, which owns the list. The only new words are <i>on Stue</i> by the label.');

  /* ================= C · mini bar · D · reconnect ================= */
  Y = 3720;
  put('<div class="lbl w3" style="left:0;top:' + Y + 'px"><span class="tag">§7 · the mini bar · reconnect</span><h3>“· Stue” on the bar, stacked under a film · then what the phone shows when it comes back</h3><small>R322’s mini bar with the device after the artist. Swipe-down <b>only dismisses the bar</b> — the room keeps playing (Q2, owner). Reconnect is R245 FR-R245-5 as written: either the bar with the live song, or nothing at all — no <i>Reconnecting…</i> anywhere.</small></div>');
  Y += 140;
  cap(0, Y, 'C1 · The mini bar · · Stue');
  phone(0, Y + 40, {}, browsePage({ cast: true }) + mini({ dev: 'Stue' }) + nav(1), 'Mini bar · Stue');
  cap(520, Y, 'C2 · Stacked under a film casting to Stue TV');
  phone(520, Y + 40, {}, listenPage({ cast: true }) + vidBar() + mini({ dev: 'Gæsteværelse', track: tr(2) }) + nav(0), 'Mini bars stacked');
  note(520, Y + 980, 'A film on <b>Stue TV</b> and an album on <b>Gæsteværelse</b> at once: two sessions, two bars, the film’s on top (R322’s stacked frame, now with a speaker’s name). Each bar opens its own remote.');
  cap(1040, Y, 'C3 · Swipe down · the bar goes, Stue plays on (Q2, owner)');
  phone(1040, Y + 40, {}, browsePage({ cast: true }) + '<div class="sp-gest" style="bottom:130px"></div>' + mini({ dev: 'Stue', gone: true }) + '<div class="sp-toast">Still playing on Stue<span class="u">Stop</span></div>' + nav(1), 'Mini bar · swipe dismisses');
  note(1040, Y + 980, 'The bar leaves; the room does not go quiet. The toast says so for 5 s with <b>Stop</b> for the listener who meant it. The cast glyph stays lit and the <b>Playing</b> tab is still the remote — the way back. The bar returns on the next open (D1) or the next song started from this phone.');
  cap(1560, Y, 'D1 · Reopened · Stue still playing · the bar is simply there');
  phone(1560, Y + 40, { skin: 'midnight' }, listenPage({ cast: true }) + mini({ dev: 'Stue', track: tr(6), pct: 61 }) + nav(0), 'Reconnect · live');
  note(1560, Y + 980, 'The phone was locked for 40 minutes; the receiver played on through the queue. On open, the bar shows <b>the song the room is on now</b>, at its position — Playing and Queue are rebuilt from the receiver. No connecting bar: R245 FR-R245-5.');
  cap(2080, Y, 'D2 · Reopened · Stue finished the album · nothing at all');
  phone(2080, Y + 40, { skin: 'midnight' }, sbar() + nowPlaying({ track: tr(TS.length - 1), pos: 0, paused: true }) + nav(2), 'Reconnect · finished');
  note(2080, Y + 980, 'The queue ended while the phone was away; Stue is silent. The Playing tab shows the last-played song <b>paused</b> (R322 FR-R322-3) — the same picture as never having cast. No device chip, the glyph unlit, no “the speaker finished” line.');

  /* ================= E · display receivers ================= */
  Y = 5200;
  put('<div class="lbl w3" style="left:0;top:' + Y + 'px"><span class="tag">§7 · the receiver on a display</span><h3>Now playing on a TV (1920 × 1080) and on the hub (1024 × 600) · three skins · ended = idle</h3><small>The receiver’s eleventh screen: cover, title, artist, album, a hairline progress, and the next song in one quiet line. <b>The TV’s own remote works too</b> (Q8, owner): a D-pad press raises the transport, like the video receiver’s chrome. <b>Lyrics on the screen are in round 1</b> (Q9, owner). On a speaker the receiver builds no screen at all (§6.2): no DOM, no gradients.</small></div>');
  Y += 150;
  function rx(x, y, w, h, scale, skin, kind, state) {
    const t = tr(0), pct = 38, s = kind === 'tv' ? 1 : 1024 / 1920;
    const P = kind === 'tv' ? { mk: [96, 72, 30, 34], c: 560, gap: 90, k: 24, h: 76, ar: 34, al: 26, bar: 6, tm: 22, nx: 24, pad: 200 } : { mk: [48, 38, 18, 20], c: 330, gap: 52, k: 15, h: 44, ar: 21, al: 16, bar: 4, tm: 14, nx: 15, pad: 90 };
    let inner;
    if (state === 'idle') inner = '<div class="idle"><i style="width:' + (kind === 'tv' ? 140 : 84) + 'px;height:' + (kind === 'tv' ? 140 : 84) + 'px"></i><b style="font-size:' + (kind === 'tv' ? 64 : 38) + 'px;margin-top:' + (kind === 'tv' ? 30 : 18) + 'px">Ravilo</b><span style="font-size:' + (kind === 'tv' ? 30 : 18) + 'px;margin-top:10px">Pick something on your phone</span></div>';
    else inner = '<div class="bgt" style="' + M.coverStyle(A) + '"></div>'
      + '<div class="mk" style="left:' + P.mk[0] + 'px;top:' + P.mk[1] + 'px;font-size:' + P.mk[3] + 'px"><i style="width:' + P.mk[2] + 'px;height:' + P.mk[2] + 'px"></i>Ravilo</div>'
      + '<div class="np" style="left:' + P.pad + 'px;right:' + P.pad + 'px;top:0;bottom:0;gap:' + P.gap + 'px">' + '<div class="c" style="width:' + P.c + 'px;height:' + P.c + 'px;' + M.coverStyle(A) + '"></div>'
      + '<div style="flex:1;min-width:0"><div class="k" style="font-size:' + P.k + 'px">Now playing</div><h3 style="font-size:' + P.h + 'px;margin-top:' + (P.k * .6) + 'px">' + t.title + '</h3><div class="ar" style="font-size:' + P.ar + 'px;margin-top:' + (P.ar * .4) + 'px">' + ART + '</div><div class="al" style="font-size:' + P.al + 'px;margin-top:' + (P.al * .3) + 'px">' + A.title + ' · ' + A.year + '</div>'
      + '<div class="bar" style="height:' + P.bar + 'px;margin-top:' + (P.h * .7) + 'px"><i style="width:' + pct + '%"></i></div><div class="tm" style="font-size:' + P.tm + 'px;margin-top:' + (P.tm * .6) + 'px"><span>' + M.fmtLen(74) + '</span><span>' + M.fmtLen(t.len) + '</span></div>'
      + '<div class="nx" style="font-size:' + P.nx + 'px;margin-top:' + (P.nx * 1.4) + 'px">Next · ' + tr(1).title + '</div></div></div>';
    if (state === 'lyrics') {
      const L = M.LYRICS[t.id] || [], li = 3, c2 = Math.round(P.c * .62), fs = kind === 'tv' ? 54 : 30;
      inner = '<div class="bgt" style="' + M.coverStyle(A) + '"></div><div class="mk" style="left:' + P.mk[0] + 'px;top:' + P.mk[1] + 'px;font-size:' + P.mk[3] + 'px"><i style="width:' + P.mk[2] + 'px;height:' + P.mk[2] + 'px"></i>Ravilo</div>'
        + '<div class="np" style="left:' + P.pad + 'px;right:' + (P.pad * .6) + 'px;top:0;bottom:0;gap:' + P.gap + 'px"><div style="flex:none;width:' + c2 + 'px"><div class="c" style="width:' + c2 + 'px;height:' + c2 + 'px;' + M.coverStyle(A) + '"></div>'
        + '<h3 style="font-size:' + (P.h * .5) + 'px;margin-top:' + (P.h * .4) + 'px">' + t.title + '</h3><div class="ar" style="font-size:' + (P.ar * .75) + 'px;margin-top:6px">' + ART + '</div><div class="bar" style="height:' + P.bar + 'px;margin-top:' + (P.h * .35) + 'px"><i style="width:' + pct + '%"></i></div></div>'
        + '<div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:' + (fs * .45) + 'px;font-family:Space Grotesk,sans-serif;font-weight:700;font-size:' + fs + 'px;line-height:1.15;letter-spacing:-.01em;-webkit-mask-image:linear-gradient(180deg,transparent,#000 18%,#000 78%,transparent);mask-image:linear-gradient(180deg,transparent,#000 18%,#000 78%,transparent)">'
        + L.slice(0, 8).map((l, i) => '<div style="color:' + (i === li ? 'var(--ink)' : 'var(--ink-dim)') + '">' + l[1] + '</div>').join('') + '</div></div>';
    }
    if (state === 'chrome' || state === 'paused') {
      const bs = kind === 'tv' ? 96 : 54, ic = kind === 'tv' ? 40 : 24, foc = kind === 'tv' ? 7 : 4;
      const btn = (svg, on) => '<span style="width:' + bs + 'px;height:' + bs + 'px;border-radius:50%;display:flex;align-items:center;justify-content:center;background:' + (on ? 'var(--ink)' : 'rgba(255,255,255,.12)') + ';color:' + (on ? 'var(--bg)' : '#fff') + (on ? ';box-shadow:0 0 0 ' + foc + 'px var(--accent-2)' : '') + '">' + svg.replace('<svg', '<svg style="width:' + ic + 'px;height:' + ic + 'px;fill:currentColor"') + '</span>';
      const lyr = G.lyr.replace('<svg', '<svg style="width:' + ic + 'px;height:' + ic + 'px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round"');
      inner += '<div style="position:absolute;left:0;right:0;bottom:0;height:34%;background:linear-gradient(0deg,rgba(0,0,0,.82),transparent)"></div>'
        + '<div style="position:absolute;left:0;right:0;bottom:' + (kind === 'tv' ? 64 : 26) + 'px;display:flex;justify-content:center;align-items:center;gap:' + (bs * .42) + 'px">' + btn(G.prev) + btn(state === 'paused' ? G.play : G.pause, true) + btn(G.next)
        + '<span style="width:' + bs + 'px;height:' + bs + 'px;border-radius:50%;display:flex;align-items:center;justify-content:center;background:rgba(255,255,255,.12);color:#fff">' + lyr + '</span></div>';
      if (state === 'paused') inner = inner.replace('<div class="k" style="font-size:' + P.k + 'px">Now playing</div>', '<div class="k" style="font-size:' + P.k + 'px">Paused</div>');
    }
    const W = kind === 'tv' ? 1920 : 1024, H = kind === 'tv' ? 1080 : 600;
    put('<div class="sp-rx' + (skin === 'noir' ? ' noir' : '') + '" data-skin="' + skin + '" style="left:' + x + 'px;top:' + y + 'px;width:' + (W * scale) + 'px;height:' + (H * scale) + 'px" data-screen-label="Receiver · ' + kind + ' · ' + skin + ' · ' + state + '"><div class="in" style="width:' + W + 'px;height:' + H + 'px;transform:scale(' + scale + ')">' + inner + '</div></div>');
  }
  const sk = [['aurora', 'Aurora'], ['midnight', 'Midnight'], ['noir', 'Noir']];
  sk.forEach((k, i) => { cap(i * 1040, Y, 'E' + (i + 1) + ' · TV 1920 × 1080 · ' + k[1] + ' · Now playing'); rx(i * 1040, Y + 40, 1920, 1080, .5, k[0], 'tv', 'np'); });
  Y += 660;
  sk.forEach((k, i) => { cap(i * 900, Y, 'E' + (i + 4) + ' · Køkken hub 1024 × 600 · ' + k[1]); rx(i * 900, Y + 40, 1024, 600, .8, k[0], 'hub', 'np'); });
  cap(2700, Y, 'E7 · Ended · the hub goes back to idle');
  rx(2700, Y + 40, 1024, 600, .8, 'aurora', 'hub', 'idle');
  note(2700, Y + 540, 'When the queue ends the screen <b>is</b> the idle view — no <i>finished</i> card, no last cover left hanging. On the TV the same.', 800);
  Y += 700;
  put('<div class="lbl w3" style="left:0;top:' + Y + 'px"><span class="tag pick">Owner · Q8 + Q9 · 2026-09-28</span><h3>The TV’s remote, and lyrics on the screen</h3><small>A receiver on a TV answers the D-pad, as the video receiver does (R264’s chrome). <b>OK / Enter</b> and the remote’s <b>Play/Pause</b> key play or pause · a <b>Stop</b> key, where the remote has one, stops the music (owner 2026-09-28) · <b>◀ ▶</b> previous / next · <b>hold ◀ ▶</b> seek 10 s · <b>▼</b> lyrics on/off · <b>Back</b> only hides the row. The row hides after 5 s. The hub has no remote: a <b>tap</b> raises the same row. Every press is reported, so the phone shows it within one report — the pair below.</small></div>');
  Y += 150;
  cap(0, Y, 'E8 · A D-pad press · the row rises · Aurora'); rx(0, Y + 40, 1920, 1080, .5, 'aurora', 'tv', 'chrome');
  cap(1040, Y, 'E9 · OK on the TV remote · paused · Midnight'); rx(1040, Y + 40, 1920, 1080, .5, 'midnight', 'tv', 'paused');
  cap(2080, Y, 'E9b · … and the phone knows');
  phone(2080, Y + 40, { skin: 'midnight' }, sbar() + nowPlaying({ dev: 'Stue TV', devGlyph: G.tv, paused: true, pos: 74 }) + nav(2), 'Now playing · paused from the TV');
  cap(2600, Y, 'E10 · Lyrics on the TV · ▼ turned them on · Noir'); rx(2600, Y + 40, 1920, 1080, .5, 'noir', 'tv', 'lyrics');
  cap(0, Y + 620, 'E11 · Lyrics on the hub · Aurora'); rx(0, Y + 660, 1024, 600, .8, 'aurora', 'hub', 'lyrics');
  note(1040, Y + 620, '<b>Lyrics on a display</b> (Q9, owner): synced lyrics only — a plain text block can’t follow the song, so the toggle does nothing on a song without timings. The choice is <b>remembered per display</b> and off at first; on the phone, the ⋯ menu gains <i>Lyrics on Stue TV</i> while casting to a screen. The current line is the receiver’s own clock, so phone and TV scroll together.', 900);
  note(2600, Y + 620, '<b>Q6 · best experience, decided:</b> the backend does not probe Google. The first real cast confirms it, and the card flips to <i>Speakers reachable</i> by itself — no button for the admin to press. <b>Q7:</b> the footnote stays (A4) — an iPhone listener learns why there are no speakers instead of wondering.', 900);
  Y += 1180;

  /* ================= F · strings + questions ================= */
  put('<div class="sp-panel" style="left:0;top:' + Y + 'px;width:1300px"><h2>Strings × en · da · fo</h2><p class="lead">The report’s four, corrected by one: <span class="mono">cast.play_on_phone</span> already ships as R299’s <span class="mono">cast_play_here</span>, so it is reused and Q7’s footnote takes its place. Added to <span class="mono">ravilo-i18n.js</span>; da/fo are drafts for the owner.</p><table class="sp-tbl">'
    + '<tr><th>Key</th><th>en</th><th>da</th><th>fo</th></tr>'
    + '<tr><td class="mono">cast_speaker</td><td>Speaker</td><td>Højttaler</td><td>Hátalari</td></tr>'
    + '<tr><td class="mono">cast_group</td><td>Speaker group</td><td>Højttalergruppe</td><td>Hátalarabólkur</td></tr>'
    + '<tr><td class="mono">cast_busy_with</td><td>Busy · {app}</td><td>Optaget · {app}</td><td>Upptikið · {app}</td></tr>'
    + '<tr><td class="mono">cast_speakers_ios</td><td>Speakers need the Android app for now</td><td>Højttalere kræver Android-appen indtil videre</td><td>Hátalarar krevja Android-appina fyri tað mundi</td></tr>'
    + '<tr><td class="mono">cast_play_here <span style="color:#8f95aa">(R299, reused)</span></td><td>Play on this phone</td><td>Afspil på denne telefon</td><td>Spæl á hesi telefonini</td></tr>'
    + '<tr><td class="mono">cast_sheet_music <span style="color:#f5b542">(not counted by the report)</span></td><td>Play on…</td><td>Afspil på…</td><td>Spæl á…</td></tr>'
    + '<tr><td class="mono">cast_still_playing <span style="color:#2dd49a">(Q2 pick)</span></td><td>Still playing on {device}</td><td>Spiller stadig på {device}</td><td>Spælir enn á {device}</td></tr>'
    + '<tr><td class="mono">cast_stop_room</td><td>Stop</td><td>Stop</td><td>Steðga</td></tr>'
    + '<tr><td class="mono">cast_lyrics_on <span style="color:#2dd49a">(Q9 pick)</span></td><td>Lyrics on {device}</td><td>Sangtekst på {device}</td><td>Sangtekstur á {device}</td></tr></table></div>');
  const QQ = [
    ['Q1', 'Starting a cast from an album page', 'Replace the queue with that album', 'owner: the lean', 'As on the phone (R322); <i>Add to queue</i> stays the way to append.'],
    ['Q2', 'Swipe-down on the mini bar while casting', 'Only dismiss the bar', 'owner: against the lean', 'C3: the room plays on, a toast says so with <b>Stop</b>; the lit glyph and the Playing tab are the way back.'],
    ['Q3', 'Where the volume lives', 'Keys + a slider in ⋯', 'owner: the lean', 'B4, B5 — small steps.'],
    ['Q4', 'A speaker against the session ceiling', 'Only while transcoding (WMA)', 'owner: the lean', 'Users &amp; devices says so on the row.'],
    ['Q5', 'Speaker groups', 'Round 1', 'owner: the lean', 'A1, B3.'],
    ['Q6', 'Backend verifies the registration itself', 'No — the first real cast confirms it, automatically', 'owner: best experience', 'Nothing for the admin to press; the card flips by itself. Revisit with road C.'],
    ['Q7', 'The iPhone', 'A footnote once in the sheet', 'owner: best experience', 'A4 — the listener learns why, instead of wondering.'],
    ['Q8', 'Music on a TV or the hub', 'The screen + the TV’s own remote (D-pad); tap on the hub', 'owner: against the lean', 'E8–E9b. OK/Enter and Play/Pause toggle; a Stop key stops; Back only hides.'],
    ['Q9', 'Lyrics on a display receiver', 'Round 1 · synced only · remembered per display', 'owner: against the lean', 'E10, E11, and <i>Lyrics on {device}</i> in the phone’s ⋯ menu.'],
  ];
  put('<div class="sp-panel" style="left:1360px;top:' + Y + 'px;width:1560px"><h2>§8 · Decided · 2026-09-28</h2><p class="lead">The owner’s answers. Where they said “whatever provides the best experience”, the pick is mine and says so.</p><table class="sp-tbl"><tr><th style="width:40px">#</th><th style="width:300px">Question</th><th>Pick</th><th style="width:500px">Where it shows</th></tr>'
    + QQ.map(q => '<tr><td><b>' + q[0] + '</b></td><td>' + q[1] + '</td><td><span class="sp-opt lean" style="border-color:#2dd49a">' + q[2] + '</span><div style="font-size:.66rem;color:#8f95aa;margin-top:3px">' + q[3] + '</div></td><td>' + q[4] + '</td></tr>').join('')
    + '</table><div class="sp-warn"><b>Two things to test before the phase is committed to (§9):</b> tick the box, wait, restart Stue and cast one MP3 with the receiver in headless mode; then lock the phone for an hour and reopen it — the bar must show the song the room is hearing. Add a third: press OK on the Stue TV remote and check the phone shows <i>paused</i> within one report.</div></div>');
  root.innerHTML = html;
})();
