/* Playback sessions — §B9 TV, §B10 admin, §B11 states, §C questions, §D strings. */
(function () {
  const K = window.PSK, { put, G, S, ses, mini, tvShot, lbl, cap, note, rule, bg, TG } = K;
  let Y = K.Y;
  const P = window.PS_PART || 1;
  K.mute = P === 1;
  const big = (g, s, c) => g.replace('<svg', '<svg style="width:' + s + 'px;height:' + s + 'px;fill:none;stroke:' + (c || 'currentColor') + ';stroke-width:2;stroke-linecap:round;stroke-linejoin:round"');

  /* ================= B9 · TV ================= */
  rule(Y - 40);
  lbl(0, Y, '§B9 · the TV app', 'The TV as a target, as a viewer, and as a volume remote', 'As a <b>target</b>, the TV says nothing new: a session moved to it opens the player at its position with R303’s top-right identity and the usual chrome (lean). As a <b>viewer</b>, a Home row <i>Playing in other rooms</i> (lean: round 2 — drawn so you can judge it). As a <b>volume remote</b> (§B4a), a panel the D-pad drives: ↑/↓ per room, ←/→ change, OK mutes, focus always visible (R350).', 'w3');
  Y += 130;
  cap(0, Y, 'B9·a · Target · the phone moved “Sommeren ’92” to Stue TV · first frame', 960);
  tvShot(0, Y + 36, '<div class="vid" style="' + bg('som') + '"></div><div class="tvov"><div class="k">Film</div><h3>Sommeren ’92</h3><div class="bar"><div class="d" style="width:43%"></div><div class="kn" style="left:43%"></div></div><div class="tm"><span>48:10</span><span>1:52:04</span></div></div>', 'B9a TV target');
  note(0, Y + 600, 'No <i>From Pixel 9</i> toast (lean). The chrome shows for its usual 4 s because the position jumped; the phone’s bar now reads <b>Stue TV</b>. A film with a logo shows it top right (R303); this one has none, so nothing.', 960);
  cap(1020, Y, 'B9·b · Viewer · Home row “Playing in other rooms” · round 2', 960);
  const card = (s, o) => { o = o || {}; return '<div class="ps-tvc' + (o.focus ? ' focus' : '') + '"><div class="a" style="' + bg(s.art) + '"></div><div class="b"><div class="t">' + (s.kind === 'ep' ? s.title + ' · ' + s.sub : s.title) + '</div><div class="s' + (s.who ? ' who' : '') + '">' + big(TG[s.tk], 26) + (s.who ? 'Olivar · ' : '') + s.tgt + '</div><div class="pr"><i style="width:' + s.pct + '%"></i></div></div></div>'; };
  tvShot(1020, Y + 36, '<div style="position:absolute;inset:0 0 50% 0;' + bg('mes') + ';opacity:.55"></div><div style="position:absolute;inset:0;background:linear-gradient(0deg,var(--bg) 46%,transparent)"></div><div class="ps-tvh" style="top:320px">Playing in other rooms<small>3</small></div><div class="ps-tvrow" style="top:410px">' + card(S.music, { focus: true }) + card({ ...S.book, tgt: 'Pixel 9' }) + card(S.kid) + '</div>', 'B9b TV row');
  note(1020, Y + 600, '<span class="ps-tag r2">round 2 lean</span> Absent when nothing plays elsewhere. OK on Eyð’s own card opens a TV remote for it (round 2: show, not control — so in round 1 the row is not there at all). Olivar’s card is shown only on <b>his</b> profile or as seen-not-controlled (Q1).', 960);
  cap(2040, Y, 'B9·c · Volume panel · D-pad · focus on Stue', 960);
  tvShot(2040, Y + 36, '<div style="position:absolute;inset:0;' + bg('salt') + ';opacity:.35"></div><div class="ps-tvpan"><h3>Cannery Lights</h3><div class="sub">' + big(G.group, 28) + 'Stue + Gæsteværelse</div>'
    + '<div class="ps-tvv master"><span class="ic">' + G.vol + '</span><div><div class="nm">Volume</div><div class="tr"><i style="width:32%"></i></div></div><span class="v">32%</span></div><div class="ps-tvsep"></div>'
    + '<div class="ps-tvv focus"><span class="ic">' + G.speaker + '</span><div><div class="nm">Stue</div><div class="tr"><i style="width:40%"></i></div><div class="hint">◀ ▶ change · OK mutes</div></div><span class="v">40%</span></div>'
    + '<div class="ps-tvv muted"><span class="ic">' + G.speaker + '</span><div><div class="nm">Gæsteværelse</div><div class="tr"><i style="width:24%"></i></div></div><span class="v">Muted</span></div>'
    + '<div class="ps-tvv"><span class="ic">' + G.plus + '</span><div><div class="nm">Add a speaker…</div></div><span></span></div>'
    + '<div class="ps-tvkeys"><span><b>▲▼</b>rooms</span><span><b>◀▶</b>volume</span><span><b>OK</b>mute</span><span><b>Back</b>close</span></div></div>', 'B9c TV volume');
  note(2040, Y + 600, 'Opened from the TV’s own player chrome (a <i>Speakers</i> button, only when the session is on a group) or from B9·b’s card in round 2. The focused row is lit <b>and</b> its slider ringed — never focus by colour alone (R350). The TV remote’s own volume keys stay the TV’s.', 960);

  /* ================= B10 · admin ================= */
  Y += 780;
  rule(Y - 40);
  lbl(0, Y, '§B10 · jellystructure admin', 'Users &amp; devices → Sessions · and the Activity timeline', 'The admin sees every session of every person: who, what, where, state, which apps hold a remote, since when — and is a <b>full remote</b> for any of them (owner 10-04): play/pause, skip, seek, queue, tracks, volume, <i>Move to…</i>, <b>End</b>. The household switch never limits the admin. Admin copy may name the technical kind of a place in small type; a viewer never sees it.', 'w3');
  Y += 130;
  const av = (c, n) => '<span class="av" style="background:' + c + '">' + n + '</span>';
  const tr = (who, what, wsub, where, wk, st, stc, ctl, since) => '<tr><td>' + who + '</td><td><b>' + what + '</b><span class="sm">' + wsub + '</span></td><td><b>' + where + '</b><span class="sm">' + wk + '</span></td><td><span class="pill ' + stc + '" style="white-space:nowrap"><span class="d"></span>' + st + '</span></td><td>' + ctl + '</td><td class="mono">' + since + '</td><td style="white-space:nowrap"><span class="btn">' + (stc === 'p' ? '▶' : '❚❚') + '</span> <span class="btn">⏭</span> <span class="btn bad">End</span></td></tr>';
  put('<div class="ps-adm" style="left:0;top:' + (Y + 36) + 'px;width:1600px" data-screen-label="B10 admin sessions"><div class="tbar"><b>Ravilo · Users &amp; devices</b><span style="margin-left:auto;font-size:.8rem;color:#9aa0b4">4 playing · 2 people</span></div><div class="tabs"><span>People</span><span>Devices</span><span class="on">Sessions<i>4</i></span><span>Screens</span></div><div class="bd">'
    + '<p class="lead">Every playback is a session the server keeps. A session survives a backend restart (its place re-attaches and continues). The buttons act on the place at once, like any Ravilo remote; click a row for the whole remote. <b>End</b> stops the place and keeps the position.</p><table><tr><th style="width:170px">Person</th><th>Playing</th><th>Where</th><th style="width:150px">State</th><th>Remotes</th><th style="width:120px">Since</th><th style="width:150px"></th></tr>'
    + tr(av('linear-gradient(120deg,#7b6ef0,#3fb6f5)', 'E') + 'Eyð', 'Cannery Lights', 'Harbour Lights · song 3 of 12', 'Stue + Gæsteværelse', 'speaker group · 2 · leader Stue', 'Playing 1:12', '', 'Pixel 9 · MacBook', '20.31')
    + '<tr><td></td><td colspan="6" style="padding:10px 12px 14px;background:rgba(79,142,240,.07)"><div style="display:flex;flex-wrap:wrap;gap:8px 18px;align-items:center;font-size:.82rem"><span class="btn">⏮</span><span class="btn">❚❚</span><span class="btn">⏭</span><span style="flex:1;min-width:220px;height:4px;border-radius:2px;background:linear-gradient(90deg,#4f8ef0 28%,rgba(0,0,0,.15) 28%)"></span><span class="mono" style="white-space:nowrap">1:12 / 4:16</span><span>Queue · 3 of 12 ›</span><span>Shuffle off · Repeat off</span><span>Volume 60% · Stue 70 · Gæsteværelse 50 ›</span><span class="btn">Move to…</span><span class="btn">Add a speaker…</span><span style="color:#9aa0b4">Every press shows in Eyð’s apps at once and as <i>from the admin</i> in Activity.</span></div></td></tr>'
    + tr(av('linear-gradient(120deg,#7b6ef0,#3fb6f5)', 'E') + 'Eyð', 'Vinterfærgen', 'audiobook · Kapitel 3 · 1.25×', 'Pixel 9', 'phone app', 'Playing 22:40', '', 'Pixel 9', '20.12')
    + tr(av('linear-gradient(120deg,#7b6ef0,#3fb6f5)', 'E') + 'Eyð', 'Sommeren ’92', 'film', 'Soveværelse TV', 'TV app · off screen', 'Paused 48:10', 'p', '—', 'yesterday 22.48')
    + tr(av('#e0792f', 'O') + 'Olivar', 'Lundin og vinir · S01E05', 'series', 'Køkken hub', 'display receiver', 'Playing 7:02', '', 'iPad', '20.36')
    + '</table></div></div>');
  put('<div class="ps-adm" style="left:1660px;top:' + (Y + 36) + 'px;width:900px" data-screen-label="B10 activity"><div class="tbar"><b>Activity · Playback</b><span style="margin-left:auto;font-size:.8rem;color:#9aa0b4">today</span></div><div class="bd"><div class="tl">'
    + '<div class="st"><span class="t">20.39</span><b>Kontor</b> left Eyð’s group <b>Stue + Gæsteværelse + Kontor</b> (unplugged)</div>'
    + '<div class="mv"><span class="t">20.36</span>Eyð moved <b>Cannery Lights</b> from Pixel 9 → <b>Stue + Gæsteværelse</b> at 0:48</div>'
    + '<div class="st"><span class="t">20.36</span>Olivar started <b>Lundin og vinir · S01E05</b> on <b>Køkken hub</b> from iPad</div>'
    + '<div class="er"><span class="t">20.33</span>Eyð’s move to <b>Kontor</b> failed — Kontor did not answer; still on Pixel 9</div>'
    + '<div class="st"><span class="t">20.31</span>Eyð started <b>Salt on the Window</b> on Pixel 9</div>'
    + '<div class="st"><span class="t">20.12</span>Eyð started <b>Vinterfærgen</b> on Pixel 9</div>'
    + '<div class="en"><span class="t">19.58</span>Server restarted — 2 sessions came back (Soveværelse TV paused, Stue TV ended)</div></div></div></div>');

  /* ================= B11 · states ================= */
  Y += 560;
  rule(Y - 40);
  lbl(0, Y, '§B11 · every state, one strip', 'States — the sheet row and the bar, with what the desktop and TV say', 'Each cell: the §B1-A row above the §B2 bar for that state, then one line on the desktop capsule and the TV. Words are drafts (§D).', 'w3');
  Y += 120;
  const cell = (h, r, row, bar, cp) => '<div class="st-cell"><div class="h"><b>' + h + '</b><span>' + r + '</span></div>' + (row ? '<div style="background:var(--bg-2)">' + row + '</div>' : '') + (bar || '') + '<div class="cp">' + cp + '</div></div>';
  const cells = [
    cell('none', 'absent', '', '<div style="height:64px;margin:10px;border-radius:15px;border:1px dashed rgba(255,255,255,.14);display:flex;align-items:center;justify-content:center;font-size:12px;color:#6b7290">no bar, no count, no section</div>', 'The sheet shows only <i>Play on…</i>. Desktop: no capsule. TV: no row.'),
    cell('one', 'elsewhere', ses(S.music), mini(S.music, { next: true }), 'Glyph count 1. Desktop capsule with the place chip. TV: nothing (round 1).'),
    cell('several', '+N', ses(S.film), mini(S.music, { more: 2, next: true }), 'Bar = B2·b’s rule. Desktop: capsule + <b>+2</b>. TV row (round 2) lists 3.'),
    cell('loading', 'starting', ses(S.music, { line: '<span>Starting on Stue + Gæsteværelse…</span>', lc: 'wrap', pct: 0 }), mini(S.music, { line: '<span class="on">' + G.group + '<span>Starting on Stue + Gæsteværelse…</span></span>' }), 'One line, the hairline runs. No spinner over the artwork.'),
    cell('place offline', 'kept', ses(S.film, { line: TG.tv + '<span>Soveværelse TV is offline</span>', lc: 'warn' }), mini(S.film, { cls: 'wrapd', line: 'Soveværelse TV is offline · paused at 48:10' }), 'The session waits at its position; ⋯ offers <b>Play here</b>. Ends by itself after 24 h (lean).'),
    cell('busy · another person', 'R270', '<div class="ps-tgt"><span class="ic">' + TG.hub + '</span><span class="tx"><span class="nm">Køkken hub</span><span class="st">Busy · Olivar is watching</span></span></div>', '', 'A place, not a session of yours: not tappable. Desktop and TV say the same words.'),
    cell('failed to start', 'nothing lost', ses(S.music, { line: '<span>Couldn’t play on Stue + Gæsteværelse</span>', lc: 'bad', act: 'Try again', bar: false }), mini(S.music, { state: 'failed', line: 'Couldn’t play on Stue + Gæsteværelse' }), 'Shown 10 s on the bar, then the bar goes; the row stays until dismissed.'),
    cell('failed while playing', 'kept place', ses(S.music, { line: '<span>Stopped on Stue · 1:12 kept</span>', lc: 'bad', act: 'Resume', bar: false }), mini(S.music, { state: 'failed', line: 'Stopped on Stue + Gæsteværelse — tap to resume' }), 'The place is kept (R358). <b>Resume</b> tries the same place; ⋯ → <b>Play here</b>.'),
    cell('reconnecting', 'this app lost the server', ses(S.music, { line: '<span class="st">Reconnecting…</span>', pct: 31 }), mini(S.music, { line: 'Reconnecting… · Stue keeps playing' }), 'Controls wait (not greyed — they apply when back). Music on the speakers never stopped.'),
    cell('ended', 'fades after a minute', ses(S.film, { cls: 'ended', line: '<span class="st">Ended on Soveværelse TV</span>', pp: false, more: false, bar: false }), '', 'Q7 lean: the row fades out after 60 s; no history list. The bar moves to the next session or goes.'),
    cell('moved', '3 s', ses({ ...S.music, tgt: 'Kontor', tk: 'speaker' }), mini({ ...S.music, tgt: 'Kontor', tk: 'speaker' }, { state: 'moved', line: 'Now on Kontor' }), 'Desktop chip changes in place. TV (as target) opens the player.'),
    cell('a room leaves the group', 'R355', ses({ ...S.music, tgt: 'Stue', tk: 'speaker' }), mini(S.music, { line: '<span class="on">' + G.group + '<span>Stue</span></span> · Gæsteværelse left' }), 'Volume lists keep the room with <i>Add back</i> (§B4a).'),
    cell('server restarting', 'sessions come back', ses(S.music, { line: '<span class="st">Reconnecting…</span>' }), mini(S.music, { line: 'Reconnecting… · Stue keeps playing' }), 'Same as reconnecting for a viewer. Places re-attach; the list rebuilds from the server.'),
    cell('another person’s', 'Q1 lean', ses(S.kid), '', 'Seen with his name, no controls. With a household setting on, it gets ⏯ and ⋯.'),
    cell('paused for hours', 'still a session', ses(S.film), mini(S.film), 'A paused session stays listed (it is still holding the TV) until it ends or 24 h pass.'),
    cell('this device', 'not counted', ses(S.book, { cls: 'here' }), mini(S.book, { more: 1 }), 'The glyph’s count leaves it out; the bar shows it first (B2·c).'),
  ];
  put('<div class="st-strip" data-skin="aurora" style="left:0;top:' + Y + 'px">' + cells.join('') + '</div>');

  /* ================= C · questions ================= */
  Y += 1520;
  const yC = Y;
  if (P === 1) { Y = K.Y; K.mute = false; } else K.mute = true;
  rule(Y - 40);
  const q = (n, t, p, opts, lean, fr) => '<div class="q"><h5>' + n + ' · ' + t + '</h5><p>' + p + '</p><div class="ch">' + opts.map((o, i) => '<span' + (i === lean ? ' class="lean"' : '') + '>' + o + '</span>').join('') + '</div>' + (fr ? '<div class="fr">' + fr + '</div>' : '') + '</div>';
  put('<div class="panel" style="left:0;top:' + Y + 'px;width:1700px"><h2>§C · Round-1 questions for the owner</h2><p class="lead">Leans are drawn on the canvas. Q0 is mine — the direction of §B1; the brief’s eight follow.</p><div class="qs">'
    + q('Q0', 'Where all sessions are listed', 'The one new screen. A is drawn through the rest of the canvas.', ['A · the cast glyph’s sheet + popover', 'B · the bar stack + the Playing tab', 'C · a Rooms page'], 0, '§B1')
    + q('Q1', 'Household visibility', 'Can people see each other’s sessions? Control them?', ['Own only', 'See with the name, control own', 'See and control (household setting)'], 1, 'A1 · B5·c · 4a·3 · B11')
    + q('Q2', 'Pressing Play while a session runs elsewhere — decided (owner, 10-04)', 'Same kind playing for you elsewhere, or a busy place → <b>always ask</b>, never remembered, never an <i>Add</i>.', ['Always ask, no Add'], 0, 'B6·a · B6·b · B6·c')
    + q('Q3', 'Where the list lives on the phone', 'Ties to Q0.', ['Cast glyph sheet', 'Mini bar sheet', 'Its own tab', 'Profile'], 0, 'A1')
    + q('Q4', 'Show the other remotes', '<i>Also controlled from MacBook</i>.', ['Never', 'On the remote only', 'Everywhere'], 0, 'B5')
    + q('Q5', 'The word for moving', 'For something already playing.', ['Move to…', 'Play on… (reused)', 'Send to…'], 0, 'A2 · B7·a')
    + q('Q6', 'More than one room — decided (owner, 10-03)', 'No cast-everywhere button, no saved or <i>Make a group…</i> row. Start on one place, then <i>Add a speaker…</i> from the remote.', ['Pick one, then add another'], 0, 'B4')
    + q('Q7', 'Ended sessions', 'Fade after a minute, or a <i>Recently played here</i> list?', ['Fade after 60 s', 'Keep a recent list'], 0, 'B11 ended')
    + q('Q8', 'Audiobooks as sessions', 'Speed and the sleep timer belong to the session and travel with a move.', ['Same model, speed/sleep on the session', 'Same model, speed/sleep per device', 'Audiobooks stay local'], 0, 'B6·d')
    + '</div></div>');
  put('<div class="panel" style="left:1820px;top:' + Y + 'px;width:780px"><h2>Also found</h2><ul>'
    + '<li><b>The brief’s numbers are stale.</b> It says next free 303 / R356; <i>main</i> now has R356–R359 (the phone stays the remote for a cast, volume reporting, a failed cast keeps the place, a long queue). Next free is <b>303 / R360</b> — check again before numbering.</li>'
    + '<li><b>“Playing” is taken</b> as a tab and a desktop page (§A note). A and C title the new list <i>Playing everywhere</i>; B redefines <i>Playing</i>.</li>'
    + '<li><b>Which session the bar shows</b> — <b>owner 10-04: always the one you touched last</b> (starting one counts); only before you touch any, the newest playing.</li>'
    + '<li><b>A paused session holds its place.</b> Sommeren ’92 paused on Soveværelse TV still owns that TV; lean: a paused session ends by itself after 24 h (like the 12 h resume record, longer).</li>'
    + '<li><b>The iPhone and the web become first-class remotes</b> for speaker sessions (A3, 4a·5) — they never talk to the speakers, only to the server.</li>'
    + '<li><b>Two frames depend on research</b> (marked <span class="ps-tag dep">depends</span>): building a group and a room’s own volume from the desktop (§8 Q5/Q5a), and speaker → speaker moves (§8 Q6).</li></ul></div>');

  /* ================= D · strings ================= */
  Y += 900;
  if (P === 1) { K.Y = Y; K.mute = false; K.flush(); document.body.style.height = (Y + 100) + 'px'; return; }
  K.mute = false; Y = yC;
  rule(Y - 40);
  const S3 = [
    ['session.everywhere', 'Playing everywhere', 'Spiller overalt', 'Spælir allastaðni'],
    ['session.playing_on', 'Playing on {target}', 'Spiller på {target}', 'Spælir á {target}'],
    ['session.move_to', 'Move to…', 'Flyt til…', 'Flyt til…'],
    ['session.move_title', 'Move “{title}” to…', 'Flyt “{title}” til…', 'Flyt “{title}” til…'],
    ['session.play_here', 'Play here', 'Afspil her', 'Spæl her'],
    ['session.stop', 'Stop', 'Stop', 'Steðga'],
    ['session.join', 'Open the remote', 'Åbn fjernbetjeningen', 'Lat fjarstýringina upp'],
    ['session.person_listening', '{person} is listening', '{person} lytter', '{person} lurtar'],
    ['session.person_watching', '{person} is watching', '{person} ser', '{person} hyggur'],
    ['session.speakers', 'Speakers', 'Højttalere', 'Hátalarar'],
    ['session.volume', 'Volume', 'Lydstyrke', 'Ljóðstyrki'],
    ['session.mute_room', 'Mute {room}', 'Slå lyden fra i {room}', 'Sløkk ljóðið í {room}'],
    ['session.group_name', '{first} + {n}', '{first} + {n}', '{first} + {n}'],
    ['session.moving', 'Moving to {target}…', 'Flytter til {target}…', 'Flytir til {target}…'],
    ['session.moved', 'Now on {target}', 'Nu på {target}', 'Nú á {target}'],
    ['session.move_failed', 'Couldn’t move — still playing on {target}', 'Kunne ikke flytte — spiller stadig på {target}', 'Kundi ikki flyta — spælir enn á {target}'],
    ['session.offline', '{target} is offline', '{target} er offline', '{target} er ikki á netinum'],
    ['session.other_rooms', 'Playing in other rooms', 'Spiller i andre rum', 'Spælir í øðrum rúmum'],
    ['session.continue_on', 'Continue on {target}', 'Fortsæt på {target}', 'Halt fram á {target}'],
    ['session.play_where', 'Play “{title}” on…', 'Afspil “{title}” på…', 'Spæl “{title}” á…'],
    ['session.remember', 'Remember on this phone', 'Husk på denne telefon', 'Minst til á hesi telefonini'],
    ['session.left_group', '{room} left', '{room} forlod gruppen', '{room} fór úr bólkinum'],
    ['session.add_back', 'Add back', 'Tilføj igen', 'Legg aftur afturat'],
    ['session.add_speaker', 'Add a speaker…', 'Tilføj en højttaler…', 'Legg ein hátalara afturat…'],
    ['session.reconnecting', 'Reconnecting…', 'Forbinder igen…', 'Bindur í aftur…'],
    ['session.start_fresh', 'Start fresh', 'Start forfra', 'Byrja av nýggjum'],
  ];
  put('<div class="panel" style="left:0;top:' + Y + 'px;width:1700px"><h2>§D · Strings — en · da · fo</h2><p class="lead">da and fo are <b>drafts</b>; where a key already ships in <span class="mono">i18n/*.json</span> the shipped value wins. <i>Also controlled from {device}</i> is left out (Q4 lean). <i>Start fresh</i> is kept for the series case if Q2 picks <i>always ask</i>.</p><table class="tbl"><tr><th style="width:220px">Key</th><th>en</th><th>da</th><th>fo</th></tr>'
    + S3.map(r => '<tr><td class="mono">' + r[0] + '</td><td><b>' + r[1] + '</b></td><td>' + r[2] + '</td><td>' + r[3] + '</td></tr>').join('') + '</table></div>');
  put('<div class="panel" style="left:1820px;top:' + Y + 'px;width:780px"><h2>After the picks (brief §E)</h2><ul>'
    + '<li>Build the picked direction into <b>Ravilo Mobile.html</b> (Pixel + iPhone), <b>Ravilo Desktop.html</b> (mac + GNOME, Large → Compact), <b>Ravilo TV.html</b> (§B9) and <b>app/ravilo-users.html</b> (§B10).</li>'
    + '<li>Strings into <span class="mono">ravilo-i18n.js</span>.</li>'
    + '<li>Specs in the report’s order (§5): read-only sessions → control from any app → start on any place → groups + non-mobile volume → several sessions + moving → admin.</li></ul></div>');
  K.Y = Y + 1200;
  K.flush();
  document.body.style.height = K.Y + 'px';
})();
