/* Playback sessions — round-1 canvas kit (window.PSK). Stand-in household: Eyð (signed in), Olivar (her son).
   Targets: This phone (Pixel 9) · MacBook · Stue TV · Soveværelse TV · Køkken hub (display) · Stue + Gæsteværelse (speakers, grown one at a time — no group or “everywhere” target, owner 2026-10-03). Static frames only — nothing is wired into the maintained mockups until the §C picks. */
(function () {
  const root = document.getElementById('ps-root');
  let html = '';
  const put = s => { if (!window.PSK || !window.PSK.mute) html += s; };
  const flush = () => { root.insertAdjacentHTML('beforeend', html); html = ''; };
  const sv = p => '<svg viewBox="0 0 24 24">' + p + '</svg>';
  const G = {
    speaker: sv('<rect x="6" y="2.5" width="12" height="19" rx="3"></rect><circle cx="12" cy="14.5" r="3.2"></circle><circle cx="12" cy="7" r="1"></circle>'),
    group: sv('<rect x="2.5" y="5" width="8.5" height="15" rx="2.4"></rect><rect x="13" y="5" width="8.5" height="15" rx="2.4"></rect><circle cx="6.75" cy="14" r="2.1"></circle><circle cx="17.25" cy="14" r="2.1"></circle>'),
    tv: sv('<rect x="2" y="4" width="20" height="13" rx="2"></rect><path d="M9 21h6"></path>'),
    hub: sv('<rect x="3" y="4" width="18" height="11" rx="2"></rect><path d="M7 15l-2 5h14l-2-5"></path>'),
    phone: sv('<rect x="6" y="2" width="12" height="20" rx="2.5"></rect><path d="M11 18h2"></path>'),
    laptop: sv('<rect x="4" y="5" width="16" height="11" rx="1.5"></rect><path d="M2 19h20"></path>'),
    web: sv('<circle cx="12" cy="12" r="9"></circle><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"></path>'),
    go: sv('<path d="M9 6l6 6-6 6"></path>'),
    down: sv('<path d="M6 9l6 6 6-6"></path>'),
    up: sv('<path d="M6 15l6-6 6 6"></path>'),
    plus: sv('<path d="M12 5v14M5 12h14"></path>'),
    check: sv('<path d="M5 12.5l4.5 4.5L19 7.5"></path>'),
    x: sv('<path d="M6 6l12 12M18 6L6 18"></path>'),
    play: sv('<path d="M8 5v14l11-7z"></path>'),
    pause: sv('<path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z"></path>'),
    prev: sv('<path d="M6 5h2v14H6zM20 5v14L9 12z"></path>'),
    next: sv('<path d="M16 5h2v14h-2zM4 5v14l11-7z"></path>'),
    more: sv('<circle cx="5" cy="12" r="1.7"></circle><circle cx="12" cy="12" r="1.7"></circle><circle cx="19" cy="12" r="1.7"></circle>'),
    vol: sv('<path d="M11 5L6 9H2v6h4l5 4z"></path><path d="M15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14"></path>'),
    mute: sv('<path d="M11 5L6 9H2v6h4l5 4z"></path><path d="M16 9l5 6M21 9l-5 6"></path>'),
    stop: sv('<rect x="6" y="6" width="12" height="12" rx="2"></rect>'),
    move: sv('<path d="M4 12h13M13 7l5 5-5 5"></path><path d="M20 4v16"></path>'),
    here: sv('<rect x="7" y="2" width="10" height="20" rx="2.5"></rect><path d="M12 7v7M9 11l3 3 3-3"></path>'),
    queue: sv('<path d="M4 6h16M4 12h10M4 18h10M18 14v6l4-3z"></path>'),
    lyr: sv('<path d="M4 6h12M4 11h9M4 16h6"></path><circle cx="17.5" cy="16.5" r="2.5"></circle><path d="M20 16.5V8l-2 1"></path>'),
    shuffle: sv('<path d="M16 3h5v5M4 20L21 3M21 16v5h-5M15 15l6 6M4 4l5 5"></path>'),
    repeat: sv('<path d="M17 1l4 4-4 4"></path><path d="M3 11V9a4 4 0 0 1 4-4h14M7 23l-4-4 4-4"></path><path d="M21 13v2a4 4 0 0 1-4 4H3"></path>'),
    warn: sv('<path d="M12 3l10 18H2z"></path><path d="M12 10v5M12 18h.01"></path>'),
    search: sv('<circle cx="11" cy="11" r="7"></circle><path d="M20 20l-4-4"></path>'),
    home: sv('<path d="M4 11l8-7 8 7"></path><path d="M6 10v9h12v-9"></path>'),
    lib: sv('<rect x="3" y="4" width="13" height="16" rx="2"></rect><path d="M19 6v14M7 8h5M7 12h5"></path>'),
    compass: sv('<circle cx="12" cy="12" r="9"></circle><path d="M15.5 8.5l-2 5-5 2 2-5z"></path>'),
    phones: sv('<path d="M4 15v-3a8 8 0 0 1 16 0v3"></path><rect x="3" y="14" width="4" height="6" rx="1.5"></rect><rect x="17" y="14" width="4" height="6" rx="1.5"></rect>'),
    grid: sv('<rect x="4" y="4" width="7" height="7" rx="1.5"></rect><rect x="13" y="4" width="7" height="7" rx="1.5"></rect><rect x="4" y="13" width="7" height="7" rx="1.5"></rect><rect x="13" y="13" width="7" height="7" rx="1.5"></rect>'),
    playc: sv('<circle cx="12" cy="12" r="9"></circle><path d="M10 8.5v7l5.5-3.5z"></path>'),
    person: sv('<circle cx="12" cy="9" r="4"></circle><path d="M5 20a7 7 0 0 1 14 0"></path>'),
    disc: sv('<circle cx="12" cy="12" r="9"></circle><circle cx="12" cy="12" r="2.5"></circle>'),
    note: sv('<path d="M9 18V5l11-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="17" cy="16" r="3"></circle>'),
    film: sv('<rect x="3" y="4" width="18" height="16" rx="2"></rect><path d="M7 4v16M17 4v16M3 9h4M17 9h4M3 15h4M17 15h4"></path>'),
    rooms: sv('<path d="M3 21V9l9-6 9 6v12"></path><path d="M9 21v-6h6v6"></path>'),
    wave: sv('<path d="M3 12h2M7 8v8M11 5v14M15 8v8M19 11v2"></path>'),
    book: sv('<path d="M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2z"></path><path d="M4 19V5"></path>'),
    back: sv('<path d="M15 6l-6 6 6 6"></path>'),
    refresh: sv('<path d="M20 11a8 8 0 1 0-2.3 5.7"></path><path d="M20 4v7h-7"></path>'),
  };
  const castic = conn => '<span class="castic' + (conn ? ' conn' : '') + '"></span>';
  const ART = {
    salt: 'linear-gradient(140deg,#3a5f8a,#b3d4e8)', quiet: 'linear-gradient(140deg,#6b5a2a,#e8d28a)', low: 'linear-gradient(140deg,#1f3f55,#5fb8c9)',
    som: 'linear-gradient(140deg,#7a3b2e,#e0a36b)', vin: 'linear-gradient(150deg,#22324a,#7e9ab8 60%,#d8e4ee)', lun: 'linear-gradient(140deg,#2f4f3a,#9cc9a0)',
    mes: 'linear-gradient(140deg,#4b2f6b,#d49ae0)', sig: 'linear-gradient(140deg,#5a2a3a,#e07a8f)', lic: 'linear-gradient(140deg,#2f4f3a,#c9d98a)',
  };
  const bg = k => 'background:' + (ART[k] || k);
  const TG = { phone: G.phone, mac: G.laptop, tv: G.tv, hub: G.hub, speaker: G.speaker, group: G.group, web: G.web, vol: G.vol };
  /* the sessions of the scenario */
  const S = {
    music: { kind: 'music', art: 'salt', title: 'Cannery Lights', sub: 'Harbour Lights', tgt: 'Stue + Gæsteværelse', tk: 'group', state: 'playing', pos: '1:12', len: '3:55', pct: 31 },
    film: { kind: 'film', art: 'som', title: 'Sommeren ’92', sub: 'Film', tgt: 'Soveværelse TV', tk: 'tv', state: 'paused', pos: '48:10', len: '1:52:04', pct: 43 },
    book: { kind: 'book', art: 'vin', title: 'Vinterfærgen', sub: 'Kapitel 3 · Ingrid Lykke', tgt: 'This phone', tk: 'phone', state: 'playing', pos: '22:40', len: '38:15', pct: 59 },
    kid: { kind: 'ep', art: 'lun', title: 'Lundin og vinir', sub: 'S01E05', tgt: 'Køkken hub', tk: 'hub', who: 'Olivar', state: 'playing', pct: 64 },
  };
  const eq = '<span class="ps-eq"><i></i><i></i><i></i></span>';
  const wide = s => s.kind === 'film' || s.kind === 'ep';
  const tgtName = (s, viewer) => (viewer === 'mac' && s.tgt === 'This phone') ? 'Pixel 9' : s.tgt;
  function stateWord(st) { return { playing: eq, paused: '<span class="st">Paused</span>', buffering: '<span class="st">Loading…</span>' }[st] || ''; }

  /* a session row (sheet, popover) */
  function ses(s, o) {
    o = o || {};
    const nm = o.title || (s.kind === 'ep' ? s.title + ' · ' + s.sub : s.title);
    let line;
    if (o.line) line = '<div class="on ' + (o.lc || '') + '">' + o.line + '</div>';
    else if (s.who) line = '<div class="on who">' + s.who + ' · ' + TG[s.tk] + tgtName(s, o.viewer) + ' · watching</div>';
    else line = '<div class="on">' + TG[s.tk] + '<span>' + tgtName(s, o.viewer) + '</span>' + stateWord(o.state || s.state) + '</div>';
    const ctl = s.who && !o.ctl ? '' : (o.pp === false ? '' : '<span class="pp">' + ((o.state || s.state) === 'playing' ? G.pause : G.play) + '</span>') + (o.more === false ? '' : '<span class="mo">' + G.more + '</span>');
    return '<div class="ps-ses' + (o.cls ? ' ' + o.cls : '') + '"><div class="c' + (wide(s) ? ' wide' : '') + '" style="' + bg(s.art) + '">' + (s.who ? '<span class="av">' + s.who[0] + '</span>' : '') + '</div>'
      + '<div class="tx"><div class="nm">' + nm + '</div>' + line + (o.bar === false ? '' : '<div class="pr"><i style="width:' + (o.pct != null ? o.pct : s.pct) + '%"></i></div>') + '</div>' + (o.act ? '<span class="act">' + o.act + '</span>' : ctl) + '</div>';
  }
  /* a target row (Play on…) */
  function tgt(kind, name, st, o) {
    o = o || {};
    const right = o.ck != null ? '<span class="ck' + (o.ck ? ' on' : '') + '">' + (o.ck ? G.check : '') + '</span>' : o.here ? '<span class="rt">' + G.check + '</span>' : o.rt === false ? '' : '<span class="rt">' + G.go + '</span>';
    const vol = o.vol != null ? '<div class="vl"><span class="tr"><i style="width:' + o.vol + '%"></i><u style="left:' + o.vol + '%"></u></span><b>' + o.vol + '%</b></div>' : '';
    return '<div class="ps-tgt' + (o.here ? ' here' : '') + (o.cls ? ' ' + o.cls : '') + '"><span class="ic">' + TG[kind] + '</span><span class="tx"><span class="nm">' + name + '</span>' + (st ? '<span class="st' + (o.play ? ' play' : '') + '">' + st + '</span>' : '') + vol + '</span>' + right + '</div>';
  }
  /* the mini bar */
  function mini(s, o) {
    o = o || {};
    let d;
    if (o.line) d = o.line;
    else if (s.tgt === 'This phone') d = s.sub;
    else d = '<span class="on">' + TG[s.tk] + '<span>' + tgtName(s, o.viewer) + '</span></span>';
    const cls = o.state === 'failed' ? ' fail' : o.state === 'moved' ? ' ok' : '';
    return '<div class="ps-mini' + cls + (o.cls ? ' ' + o.cls : '') + '"><div class="c' + (wide(s) ? ' wide' : '') + '" style="' + bg(s.art) + '"></div><div class="tx"><div class="n">' + (s.kind === 'ep' ? s.title + ' · ' + s.sub : s.title) + '</div><div class="d">' + d + '</div></div>'
      + (o.more ? '<span class="more">+' + o.more + '</span>' : '') + (o.noCtl ? '' : '<span class="b">' + ((o.st || s.state) === 'playing' ? G.pause : G.play) + '</span>') + (o.next && s.kind === 'music' ? '<span class="b">' + G.next + '</span>' : '')
      + '<span class="pg' + (o.state === 'moving' ? ' mv' : '') + '"><i style="width:' + (o.state === 'moving' ? 10 : s.pct) + '%"></i></span></div>';
  }
  const sbar = t => '<div class="ps-sbar"><span>' + (t || '20.41') + '</span><span>▾ ▾ ▮</span></div>';
  function top(title, o) {
    o = o || {};
    return '<div class="ps-top"><h1>' + title + '</h1><span class="sp"></span>' + (o.extra || '') + '<span class="ps-ib' + (o.lit ? ' lit' : '') + (o.focus ? ' focus' : '') + '">' + castic(o.lit) + (o.n ? '<span class="n">' + o.n + '</span>' : '') + '</span></div>';
  }
  const FN = [['Home', G.home], ['Library', G.lib], ['Search', G.search], ['Discover', G.compass], ['Profile', G.person]];
  const MN = [['Listen', G.phones], ['Browse', G.grid], ['Playing', G.playc], ['Queue', G.queue], ['Profile', G.person]];
  const nav = (set, on) => '<nav class="ps-bn">' + (set === 'music' ? MN : FN).map((n, i) => '<div class="it' + (i === on ? ' on' : '') + '"><span class="p">' + n[1] + '</span><b>' + n[0] + '</b></div>').join('') + '</nav>';
  const home = () => '<div class="ps-body"><div class="ps-hero" style="' + bg('mes') + '"><span class="k">New episodes</span><span class="t">Mesterholdet</span></div><div class="ps-h3">Continue watching</div><div class="ps-row4">'
    + [['Sommeren ’92', 'som'], ['Lundin og vinir', 'lun'], ['Signal Lamp', 'sig']].map(p => '<div class="ps-po" style="' + bg(p[1]) + '"><span>' + p[0] + '</span></div>').join('') + '</div><div class="ps-h3">My List</div><div class="ps-row4">'
    + [['Kelp Forest', 'low'], ['Quiet Coast', 'quiet'], ['Glass Winter', 'lic']].map(p => '<div class="ps-po" style="' + bg(p[1]) + '"><span>' + p[0] + '</span></div>').join('') + '</div></div>';
  const listen = () => '<div class="ps-body"><div class="ps-h3">Recently played</div><div class="ps-grid">' + [['Salt on the Window', 'Harbour Lights', 'salt'], ['Quiet Hours', 'Marta Vang', 'quiet'], ['Low Water', 'The Ferrymen', 'low'], ['Signal Lamp', 'Copperline', 'sig']]
    .map(a => '<div class="ps-alb"><div class="c" style="' + bg(a[2]) + '"></div><div class="t">' + a[0] + '</div><div class="a">' + a[1] + '</div></div>').join('') + '</div></div>';
  const sheet = (title, rows, extra, cls) => '<div class="scrim"></div><div class="sheet' + (cls ? ' ' + cls : '') + '"><div class="grab"></div>' + (title ? '<div class="sh"><h4>' + title + '</h4></div>' : '<div style="height:8px"></div>') + rows + (extra || '') + '</div>';
  function phone(x, y, o, inner, label) {
    const ios = o.ios;
    put('<div class="dv ' + (ios ? 'ios' : 'and') + '" data-skin="' + (o.skin || 'aurora') + '" style="left:' + x + 'px;top:' + y + 'px" data-screen-label="' + label + '">'
      + (ios ? '<div class="isl"></div>' : '<div class="pnch"></div>') + '<div class="scn">' + inner + '</div>' + (ios ? '<div class="hind"></div>' : '') + '</div>');
  }
  /* the remote (phone) */
  function remote(s, o) {
    o = o || {};
    const music = s.kind === 'music';
    const vol = o.vol === false ? '' : '<div class="ps-rvol">' + G.vol + '<span class="tr"><i style="width:' + (o.vol || 32) + '%"></i></span><b>' + (o.vol || 32) + '%</b>' + (s.tk === 'group' ? '<span class="spk">Speakers' + G.down + '</span>' : '') + '</div>';
    return sbar() + '<div class="ps-rem"><div class="bgt" style="' + bg(s.art) + '"></div><div class="ps-rhead"><span class="x">' + G.down + '</span><span class="sp"></span><span class="ps-tline">' + TG[s.tk] + '<span>' + (o.tline || 'Playing on ' + s.tgt) + '</span><svg class="cv" viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"></path></svg></span><span class="sp"></span><span class="x">' + G.more.replace('<svg', '<svg style="fill:currentColor;stroke:none"') + '</span></div>'
      + '<div class="ps-cov' + (wide(s) ? ' wide' : '') + '" style="' + bg(s.art) + '">' + (wide(s) ? '<span class="ttl">' + s.title + '</span>' : '') + '</div>'
      + '<div class="ps-meta">' + (o.kick ? '<div class="k">' + o.kick + '</div>' : '') + '<h3>' + s.title + '</h3><div class="ar">' + s.sub + '</div></div>'
      + '<div class="ps-seek"><div class="tr"><i style="width:' + s.pct + '%"></i><u style="left:' + s.pct + '%"></u></div><div class="tm"><span>' + s.pos + '</span><span>' + s.len + '</span></div></div>'
      + '<div class="ps-tp"><span class="b sm">' + (music ? G.shuffle : G.lyr) + '</span><span class="b">' + G.prev + '</span><span class="pp">' + ((o.st || s.state) === 'playing' ? G.pause : G.play) + '</span><span class="b">' + G.next + '</span><span class="b sm">' + (music ? G.repeat : G.queue) + '</span></div>'
      + vol + '<div class="ps-rfoot"><b>' + G.move + 'Move to…</b><b>' + G.here + 'Play here</b><b>' + (music ? G.queue + 'Queue' : G.lib + 'Episodes') + '</b><b class="stop">' + G.stop + 'Stop</b></div></div>';
  }
  /* desktop windows */
  const MACNAV = {
    music: [[null, [['phones', 'Listen'], ['wave', 'Playing'], ['queue', 'Queue']]], ['Library', [['person', 'Artists', '23'], ['disc', 'Albums', '30'], ['note', 'Songs', '60'], ['grid', 'Genres', '18'], ['lib', 'Playlists', '2'], ['book', 'Audiobooks', '5']]]],
    films: [[null, [['home', 'Home'], ['compass', 'Discover'], ['lib', 'My List']]], ['Library', [['film', 'Films', '412'], ['tv', 'Series', '96']]]],
  };
  function side(o) {
    const set = MACNAV[o.mode === 'music' ? 'music' : 'films'];
    return '<aside class="side">' + (o.gn ? '<div style="display:flex;align-items:center;gap:8px;padding:2px 6px 14px;font-weight:700;font-size:14px"><span style="width:20px;height:20px;border-radius:6px;background:var(--grad)"></span>Ravilo</div>' : '<div class="lights"><i></i><i></i><i></i></div>')
      + '<div class="seg"><b class="' + (o.mode !== 'music' ? 'on' : '') + '">Films &amp; series</b><b class="' + (o.mode === 'music' ? 'on' : '') + '">Music</b></div>'
      + set.map(([h, items]) => (h ? '<h6>' + h + '</h6>' : '') + items.map(([i, l, n]) => { const extra = (o.extraNav || {})[l]; return '<a class="' + (l === o.active ? 'on' : '') + '">' + G[i] + '<span>' + l + '</span>' + (extra || (n ? '<span class="n">' + n + '</span>' : '')) + '</a>'; }).join('')).join('')
      + (o.navAdd || '') + '<div class="who"><i>E</i><span>Eyð</span></div></aside>';
  }
  function win(x, y, o, label) {
    const w = o.w || 1180, h = o.h || 720;
    const tb = '<div class="wtb">' + (o.gn ? '<span class="bt">' + G.search + '</span>' : '') + '<span class="tt">' + (o.title || '') + '</span><span class="sp"></span>' + (o.tbExtra || '')
      + '<span class="bt' + (o.castLit ? ' lit' : '') + '">' + castic(o.castLit) + (o.castN ? '<span class="n">' + o.castN + '</span>' : '') + '</span>' + (o.gn ? '<span class="bt">' + sv('<path d="M4 7h16M4 12h16M4 17h16"></path>') + '</span><span class="gx">✕</span>' : '<span class="bt">' + G.search + '</span>') + '</div>';
    put('<div class="ps-w ' + (o.gn ? 'gn' : 'mac') + '" data-skin="aurora" style="left:' + x + 'px;top:' + y + 'px;width:' + w + 'px;height:' + h + 'px" data-screen-label="' + label + '">' + side(o)
      + '<div class="main">' + tb + '<div class="pg">' + (o.page || '') + '</div>' + (o.gn ? '' : (o.bar || '')) + (o.pop || '') + '</div>' + (o.gn ? (o.bar || '') : '') + '</div>');
  }
  function capsule(s, o) {
    o = o || {};
    return '<div class="ps-cap"><span class="c' + (wide(s) ? ' wide' : '') + '" style="' + bg(s.art) + '"></span><div class="tx"><div class="t">' + s.title + '</div><div class="s">' + s.sub + '</div></div>'
      + '<div class="tp"><b>' + G.prev + '</b><b class="pp">' + (s.state === 'playing' ? G.pause : G.play) + '</b><b>' + G.next + '</b></div><div class="sk"><span>' + s.pos + '</span><span class="tr"><i style="width:' + s.pct + '%"></i></span><span>' + s.len + '</span></div>'
      + (s.tgt !== 'This phone' || o.chip ? '<span class="chip">' + TG[o.tk || s.tk] + '<span>' + (o.chip || s.tgt) + '</span></span>' : '') + '<span class="ib' + (o.volOn ? ' on' : '') + '">' + G.vol + '</span><span class="ib">' + G.lyr + '</span><span class="ib">' + G.queue + '</span>' + (o.more ? '<span class="more">+' + o.more + '</span>' : '') + '</div>';
  }
  function gbar(s, o) {
    o = o || {};
    return '<div class="ps-gbar"><span class="c" style="' + bg(s.art) + '"></span><div class="tx"><div class="t">' + s.title + '</div><div class="s">' + s.sub + '</div></div><div class="tp"><b>' + G.prev + '</b><b class="pp">' + (s.state === 'playing' ? G.pause : G.play) + '</b><b>' + G.next + '</b></div>'
      + '<div class="sk"><span>' + s.pos + '</span><span class="tr"><i style="width:' + s.pct + '%"></i></span><span>' + s.len + '</span></div><span class="gb">' + TG[s.tk] + s.tgt + '</span><span class="gb' + (o.volOn ? ' on' : '') + '">' + G.vol + '32 %' + G.down + '</span>' + (o.more ? '<span class="gb">+' + o.more + '</span>' : '') + '</div>';
  }
  /* volume list (popover / page) */
  function vrow(kind, name, v, o) {
    o = o || {};
    if (o.gone) return '<div class="ps-vrow gone"><span class="ic">' + TG[kind] + '</span><span class="nm">' + name + '<span class="v">—</span></span><span class="mu">' + G.plus + '</span><span class="sub">' + o.gone + '</span></div>';
    return '<div class="ps-vrow' + (o.master ? ' master' : '') + (o.muted ? ' muted' : '') + (o.focus ? ' focus' : '') + '"><span class="ic">' + TG[kind] + '</span><span class="nm">' + name + (o.bdg ? '<span class="bdg">' + o.bdg + '</span>' : '') + '<span class="v">' + (o.muted ? 'Muted' : v + '%') + '</span></span>'
      + '<span class="mu' + (o.muted ? ' on' : '') + '">' + (o.muted ? G.mute : G.vol) + '</span><span class="tr"><i style="width:' + v + '%"></i><u style="left:' + v + '%"></u></span>' + (o.sub ? '<span class="sub">' + o.sub + '</span>' : '') + '</div>';
  }
  function tvShot(x, y, inner, label, scale) {
    const k = scale || .5;
    put('<div class="shot" data-skin="aurora" style="left:' + x + 'px;top:' + y + 'px;width:' + 1920 * k + 'px;height:' + 1080 * k + 'px" data-screen-label="' + label + '"><div class="inner" style="transform:scale(' + k + ')"><div class="tvs" style="background:var(--bg)">' + inner + '</div></div></div>');
  }
  /* canvas furniture */
  const lbl = (x, y, tag, title, text, w, big) => put('<div class="lbl ' + (w || 'w3') + '" style="left:' + x + 'px;top:' + y + 'px">' + (tag ? '<span class="tag' + (tag.cls ? ' ' + tag.cls : '') + '">' + (tag.t || tag) + '</span>' : '') + (big ? '<h2>' + title + '</h2>' : '<h3>' + title + '</h3>') + (text ? '<small>' + text + '</small>' : '') + '</div>');
  const cap = (x, y, t, w) => put('<div class="cap" style="left:' + x + 'px;top:' + y + 'px' + (w ? ';width:' + w + 'px' : '') + '">' + t + '</div>');
  const note = (x, y, t, w) => put('<div class="ps-note" style="left:' + x + 'px;top:' + y + 'px' + (w ? ';width:' + w + 'px' : '') + '">' + t + '</div>');
  const rule = (y, w) => put('<div class="rule" style="left:0;top:' + y + 'px;width:' + (w || 3600) + 'px"></div>');
  window.PSK = { put, flush, G, castic, ART, bg, TG, S, eq, ses, tgt, mini, sbar, top, nav, home, listen, sheet, phone, remote, win, capsule, gbar, vrow, tvShot, lbl, cap, note, rule, sv, Y: 0 };
})();
