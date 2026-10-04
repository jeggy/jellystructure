/* Playback sessions — §0 intro + vocabulary, §B1 directions (phone), §B2–§B7 on the phone. */
(function () {
  const K = window.PSK, { put, G, S, ses, tgt, mini, sbar, top, nav, home, listen, sheet, phone, remote, lbl, cap, note, rule, castic, bg, TG } = K;
  const PX = 470;

  /* ================= 0 · intro + vocabulary ================= */
  lbl(0, 0, { t: 'Round 1 · directions · 2026-10-02 · brief design-brief-playback-sessions-2026-10-02 · next free 303 / R360', cls: 'new' }, 'Playback sessions — one session, any screen, any speaker, any controller',
    'From now on every playback is a <b>session the server keeps</b>. It plays on one <b>target</b> (this phone, the Mac, a TV app, a display, a speaker or a group of speakers), and every Ravilo app of the same person can <b>see</b> it, <b>control</b> it, <b>move</b> it, or start a <b>new</b> one beside it. The household on this canvas: <b>Eyð</b> (signed in) has three sessions — <i>Cannery Lights</i> on <b>Stue + Gæsteværelse</b>, the audiobook <i>Vinterfærgen</i> on <b>this phone</b>, the film <i>Sommeren ’92</i> paused on <b>Soveværelse TV</b> — and her son <b>Olivar</b> is watching <i>Lundin og vinir</i> on the <b>Køkken hub</b>. Stue TV is free.<br><br>'
    + 'Read top to bottom: <b>§B1 three directions</b> for where the sessions are listed (A is the lean, and everything below A is drawn in it) · §B2 the bar when this app is a remote · §B3 one <i>Play on…</i> sheet · §B4 groups · §B5 the remote · §B6 starting while sessions run · §B7 moving · §B1 on the desktop · §C the questions. <b>Part 2</b> — <a href="Playback Sessions - Desktop, TV &amp; Admin.html">Playback Sessions - Desktop, TV &amp; Admin</a> — carries §B4a volume on non-mobile clients, §B8 desktop, §B9 TV, §B10 admin, §B11 states and §D strings. <b>Static frames — nothing is built into the maintained mockups until the picks; no spec yet.</b>', 'w3', true);
  put('<div class="panel" style="left:1500px;top:0;width:1060px"><h2>§A · The vocabulary — what a viewer reads</h2><p class="lead">The model has five nouns. The viewer meets three of them, as places and verbs — never as <i>session</i>, <i>target</i> or <i>controller</i>.</p><table class="tbl">'
    + '<tr><th style="width:150px">Concept</th><th style="width:300px">Viewer sees</th><th>Where, and the reason</th></tr>'
    + '<tr><td><b>Session</b></td><td><i>Playing everywhere</i> (the list) · <i>Playing on Stue</i> (one of them)</td><td>The word <i>session</i> never appears. The list is titled for what it shows; a row names the thing and the place. <b>Lean.</b></td></tr>'
    + '<tr><td><b>Target</b></td><td>The device or room by its own name: <i>This phone</i> · <i>MacBook</i> · <i>Stue TV</i> · <i>Køkken hub</i> · <i>Stue + Gæsteværelse</i></td><td>An icon carries the kind (phone · computer · TV · display · speaker · group); the name is the household’s. Never a product or protocol name.</td></tr>'
    + '<tr><td><b>Controller</b></td><td>Nothing (lean, Q4)</td><td>Who else is holding the remote does not change what you can do. Two presses at once: the later one wins and every screen just updates.</td></tr>'
    + '<tr><td><b>Move</b></td><td><i>Move to…</i> and its most common case <i>Play here</i></td><td><i>Play on…</i> stays the verb for starting; <i>Move to…</i> is for something already playing (Q5). They open the same list of places.</td></tr>'
    + '<tr><td><b>Group</b></td><td><i>Stue + Gæsteværelse</i> · 3+: <i>Stue + 2</i> </td><td><b>Never picked as one thing</b> (owner, 2026-10-03): you play on <b>one</b> place, then add another from the remote. There is no <i>everywhere</i>, <i>whole house</i> or saved-group row. Order = the order they joined.</td></tr>'
    + '</table><div class="warn"><b>Found while drawing:</b> the word <i>Playing</i> is already a place — the music bar’s <i>Playing</i> tab and the desktop’s <i>Playing</i> page mean “what this device plays”. Direction B builds on that; A and C keep it and title the new list <i>Playing everywhere</i>. In A, the <i>Playing</i> tab becomes the remote of whichever music session the bar shows.</div></div>');

  /* ================= B1 · where all sessions are listed ================= */
  let Y = 860;
  rule(Y - 40);
  lbl(0, Y, '§B1 · the one new screen', '“Playing everywhere” — three directions', 'Same household, same moment. Each frame shows Eyð’s three sessions and Olivar’s (Q1 lean: seen with his name, not controllable). <b>A</b> puts the list behind the cast glyph that every page already has; <b>B</b> makes the bar itself the switcher and grows the <i>Playing</i> tab; <b>C</b> lists places, not playbacks — a page of rooms. Desktop versions of each are in §B1-desktop below.', 'w3');
  Y += 130;
  lbl(0, Y, { t: 'Direction A · lean', cls: 'pick' }, 'A · The cast glyph is the door', 'One sheet, two halves: what is <b>playing</b> on top, the places you can <b>play on</b> below. The glyph shows a count when anything plays elsewhere. Works the same in films and music mode, on every page, on phone and desktop (a popover).', 'w2');
  lbl(1500, Y, { t: 'Direction B' }, 'B · The bar is the switcher', 'Sessions stack behind the mini bar like cards; swipe up on the bar fans them out. In music mode the <i>Playing</i> tab gets a strip of sessions on top. No new place to learn — but in films mode there is no <i>Playing</i> tab, and the stack hides how many there are.', 'w2');
  lbl(2560, Y, { t: 'Direction C' }, 'C · A page of rooms', 'Every place in the house, playing or not, on one page: Stue TV says <i>Nothing playing</i> and offers to start. Strongest picture of “the house” and the natural home of moving — but it is a sixth destination and lists idle devices.', 'w2');
  Y += 170;
  const allSes = ses(S.music) + ses(S.book, { cls: 'here' }) + ses(S.film) + ses(S.kid);
  cap(0, Y, 'A1 · From Home · four sessions, the places below');
  phone(0, Y + 36, {}, sbar() + top('Home', { lit: true, n: 3 }) + home() + mini(S.book, { more: 2 }) + nav('films', 0)
    + sheet('', '<div class="ps-sec">Playing everywhere<span class="r">4</span></div>' + allSes + '<div class="ps-sec">Play on…</div>'
      + tgt('phone', 'This phone', 'Playing · Vinterfærgen', { here: true, play: true }) + tgt('mac', 'MacBook', 'Ravilo is open') + tgt('tv', 'Stue TV', 'Ready') + tgt('speaker', 'Kontor', 'Ready')
      + '<div class="ps-col"><span>More places</span><span style="color:var(--ink-dim)">(5)</span><span class="ct">' + G.down + '</span></div>'), 'A1 · Playing everywhere sheet');
  note(0, Y + 970, '<b>A row</b>: what, where (icon + name), playing ≋ or <i>Paused</i>, a thin position line. Tap the row = open its remote (that is “joining”, no word for it). <b>⏯</b> works from the list. Olivar’s row has his initial on the artwork and no controls. The glyph’s <b>3</b> counts what plays <i>elsewhere</i> — this phone’s own book is not counted.');
  cap(PX, Y, 'A2 · ⋯ on a row · Move to… · Play here · Stop');
  phone(PX, Y + 36, {}, sbar() + top('Home', { lit: true, n: 3 }) + home() + mini(S.book, { more: 2 }) + nav('films', 0)
    + sheet('', '<div class="ps-sec">Playing everywhere<span class="r">4</span></div>' + ses(S.music) + ses(S.book, { cls: 'here' }) + ses(S.film, { cls: 'sel' }) + ses(S.kid) + '<div style="height:260px"></div>')
    + '<div class="ps-menu" style="top:560px">' + '<div>' + G.move + 'Move to…</div><div>' + G.here + 'Play here</div><div>' + G.playc + 'Open the remote</div><div class="stop">' + G.stop + 'Stop</div></div>', 'A2 · row menu');
  note(PX, Y + 970, '<b>Play here</b> is the most common move, so it is one step, not inside <i>Move to…</i>. <b>Stop</b> ends the session; the film keeps its resume point like any stop. On a session of another person the ⋯ is absent (Q1).');
  cap(PX * 2, Y, 'A3 · iPhone · a remote for all of them, no speakers to start');
  phone(PX * 2, Y + 36, { ios: true }, sbar('20.41') + top('Home', { lit: true, n: 3 }) + home() + nav('films', 0)
    + sheet('', '<div class="ps-sec">Playing everywhere<span class="r">4</span></div>' + ses(S.music) + ses(S.film) + ses(S.book, { line: TG.phone + '<span>Pixel 9</span>' + K.eq }) + ses(S.kid) + '<div class="ps-sec">Play on…</div>'
      + tgt('phone', 'This iPhone', '', { here: true }) + tgt('mac', 'MacBook', 'Ravilo is open') + tgt('tv', 'Stue TV', 'Ready'), '<div class="ps-foot">Speakers and displays start from a Ravilo app on Android or a computer. Anything already playing on them can be controlled from here.</div>'), 'A3 · iPhone');
  note(PX * 2, Y + 970, 'The server is the go-between, so the iPhone (and the web) can <b>control</b> every session — including a speaker group the Pixel started. It can <b>start</b> only on Ravilo apps (report §8 Q8). The phone that is “this phone” on the Pixel is <b>Pixel 9</b> here.');

  /* B: stacked bar + Playing tab strip */
  const BX = 1500;
  cap(BX, Y, 'B1 · Swipe up on the bar · the stack fans out');
  phone(BX, Y + 36, {}, sbar() + top('Home', {}) + home()
    + '<div class="scrim" style="z-index:29"></div><div class="ps-stack" style="position:absolute;left:0;right:0;bottom:104px;z-index:41">'
    + '<div style="padding:0 6px 6px;font-size:12px;font-weight:700;letter-spacing:.09em;text-transform:uppercase;color:var(--ink-soft)">Playing everywhere · 4</div>'
    + mini(S.kid, { cls: 'sm', noCtl: true, line: 'Olivar · ' + TG.hub + ' Køkken hub' }) + mini(S.film, { cls: 'sm' }) + mini(S.music, { cls: 'sm' }) + mini(S.book) + '</div>' + nav('films', 0), 'B1 · fanned stack');
  note(BX, Y + 970, 'Collapsed, the bar shows the most recent one with two <b>peeking edges</b> behind it (B2). Fanned, each card is a full mini bar. <b>Cost:</b> the count is hidden until you swipe, and four bars eat half the screen.');
  cap(BX + PX, Y, 'B2 · Music mode · the Playing tab with a session strip');
  const strip = '<div style="display:flex;gap:8px;padding:2px 0 12px;overflow:hidden">' + [[S.music, 1], [S.book], [S.film], [S.kid]].map(([s, on]) => '<div style="flex:none;display:flex;align-items:center;gap:8px;height:40px;padding:0 12px 0 5px;border-radius:20px;background:' + (on ? 'rgba(63,182,245,.16)' : 'rgba(255,255,255,.06)') + ';border:1px solid ' + (on ? 'rgba(63,182,245,.5)' : 'var(--line)') + ';font-size:13px;font-weight:600;color:' + (on ? 'var(--ink)' : 'var(--ink-soft)') + '"><i style="width:30px;height:30px;border-radius:50%;' + bg(s.art) + '"></i>' + (s.who ? 'Olivar' : s.tgt) + '</div>').join('') + '</div>';
  phone(BX + PX, Y + 36, {}, remote(S.music, { vol: 34 }).replace('<div class="ps-rem">', '<div class="ps-rem">' + strip) + nav('music', 2), 'B2 · Playing tab strip');
  note(BX + PX, Y + 970, 'The <i>Playing</i> tab already means “now playing”; B makes it “now playing <b>anywhere</b>”, one chip per session. Natural in music mode. In films mode the same strip has to live somewhere else — that is B’s weak side.');

  const CX = 2560;
  const room = (kind, name, s, o) => { o = o || {}; return '<div class="ps-room' + (s ? '' : ' idle') + (o.mine ? ' mine' : '') + '"><div class="hd">' + TG[kind] + name + '<span class="r">' + (o.r || '') + '</span></div><div class="bd">'
    + (s ? '<div class="c' + (s.kind === 'film' || s.kind === 'ep' ? ' wide' : '') + '" style="' + bg(s.art) + '"></div><div class="tx"><div class="nm">' + (s.kind === 'ep' ? s.title + ' · ' + s.sub : s.title) + '</div><div class="s">' + (o.s || s.sub) + '</div></div>' + (o.ctl === false ? '' : '<span class="pp" style="width:44px;height:44px;border-radius:50%;background:var(--chip);display:flex;align-items:center;justify-content:center">' + (s.state === 'playing' ? G.pause : G.play).replace('<svg', '<svg style="width:19px;height:19px;fill:var(--ink)"') + '</span>')
      : '<div class="tx"><div class="s">Nothing playing</div></div><span style="font-size:13px;font-weight:700;color:var(--accent-2)">Play something here</span>') + '</div></div>'; };
  cap(CX, Y, 'C1 · Profile → Rooms · every place, playing or not');
  phone(CX, Y + 36, {}, sbar() + '<div class="ps-top"><span class="ps-ib">' + G.back + '</span><h1>Rooms</h1><span class="sp"></span></div><div class="ps-body">'
    + room('phone', 'This phone', S.book, { mine: true, s: 'Kapitel 3 · 15 min left' }) + room('group', 'Stue + Gæsteværelse', S.music, { s: 'Harbour Lights · playing' }) + room('tv', 'Soveværelse TV', S.film, { s: 'Paused at 48:10' })
    + room('hub', 'Køkken hub', S.kid, { r: 'Olivar', ctl: false, s: 'Olivar is watching' }) + room('tv', 'Stue TV', null) + '</div>' + nav('films', 4), 'C1 · rooms page');
  note(CX, Y + 970, 'Places first, each a card. An idle room is <b>listed, not greyed</b> — it is a real place you can start on, so it does not break <i>absent, never greyed</i>; it is the page’s reason to exist. <b>Cost:</b> a sixth destination, and it lists only what this network can see.');
  cap(CX + PX, Y, 'C2 · Home · the doorway row when 2+ play elsewhere');
  phone(CX + PX, Y + 36, {}, sbar() + top('Home', { lit: true }) + '<div class="ps-body"><div style="display:flex;align-items:center;gap:12px;padding:12px 14px;border-radius:14px;background:var(--card);border:1px solid var(--line);margin:4px 0 6px"><div style="display:flex">'
    + [S.music, S.film, S.kid].map((s, i) => '<i style="width:34px;height:34px;border-radius:50%;' + bg(s.art) + ';box-shadow:0 0 0 2px var(--card);margin-left:' + (i ? -10 : 0) + 'px"></i>').join('') + '</div><div style="flex:1"><div style="font-size:14.5px;font-weight:600">Playing in 3 other rooms</div><div style="font-size:13px;color:var(--ink-soft);margin-top:2px">Stue + Gæsteværelse · Soveværelse TV · Køkken hub</div></div>' + G.go.replace('<svg', '<svg style="width:18px;height:18px;fill:none;stroke:var(--ink-dim);stroke-width:2.2"') + '</div>'
    + home().replace('<div class="ps-body">', '<div>') + '</div>' + mini(S.book) + nav('films', 0), 'C2 · Home doorway');
  note(CX + PX, Y + 970, 'C needs a way in that is not Profile: one row at the top of Home (and Listen) when anything plays elsewhere, absent otherwise. Tapping opens C1.');

  /* ================= B2 · the bar as a remote ================= */
  Y += 1180;
  rule(Y - 40);
  lbl(0, Y, { t: '§B2 · in direction A' }, 'The bar when this app is a remote', 'Today’s cast mini bar, generalised: artwork, title, <b>the place</b> in accent with its icon, ⏯, next for music. Several sessions: the bar shows <b>the most recent active one + a +N chip</b> that opens §B1’s sheet (lean). Origin is not shown — <i>where it plays</i> is what matters, not who started it (lean).', 'w3');
  Y += 130;
  cap(0, Y, 'B2·a · One session, elsewhere · films mode');
  phone(0, Y + 36, {}, sbar() + top('Home', { lit: true, n: 1 }) + home() + mini(S.music, { next: true }) + nav('films', 0), 'B2a · one remote session');
  note(0, Y + 970, 'The bar appears as soon as any of Eyð’s apps starts something anywhere — the server pushes it. Tap = the remote (§B5). In films mode the bar is the music bar (R337 Q2’s rule: films mode shows it only while music plays).');
  cap(PX, Y, 'B2·b · Several · the most recent + “+2”');
  phone(PX, Y + 36, {}, sbar() + top('Home', { lit: true, n: 3 }) + home() + mini(S.music, { more: 2, next: true }) + nav('films', 0), 'B2b · several sessions');
  note(PX, Y + 970, '<b>Which one is on the bar</b> (owner 10-04): <b>always the one you touched last</b> — starting, pausing, skipping or opening its remote counts. Only before you touch any: the newest playing. <b>+2</b> opens the sheet (A1) — the same as the glyph.');
  cap(PX * 2, Y, 'B2·c · This phone plays + one elsewhere');
  phone(PX * 2, Y + 36, {}, sbar() + top('Listen', { lit: true, n: 1 }) + listen() + mini(S.book, { more: 1 }) + nav('music', 0), 'B2c · local + remote');
  note(PX * 2, Y + 970, 'What plays <b>here</b> wins the bar: it is what the lock screen, the headphones and the volume keys control. The +1 is Stue + Gæsteværelse. The bar’s second line reads the chapter, as today — no place for <i>this phone</i>.');
  cap(PX * 3, Y, 'B2·d · Started elsewhere · the Mac sees the phone’s cast');
  put('<div class="ps-note" style="left:' + PX * 3 + 'px;top:' + (Y + 36) + 'px;width:412px">The desktop’s capsule is the same bar (§B8 draws it in the window): <b>Cannery Lights · Stue + Gæsteværelse</b> lights up on the MacBook within a second of the Pixel starting it, with the speaker-group chip. No <i>Started on Pixel 9</i> line (lean).</div>');
  phone(PX * 3, Y + 150, { ios: true }, sbar('20.41') + top('Home', { lit: true, n: 2 }) + home() + mini(S.music, { more: 1, next: true }) + nav('films', 0), 'B2d · iPhone remote bar');
  /* ================= B3 · Play on… ================= */
  Y += 1200;
  rule(Y - 40);
  lbl(0, Y, { t: '§B3 · one sheet for every place' }, 'Play on… — four tiers, one place at a time', '1 <b>This device</b> · 2 <b>your other Ravilo apps</b> that can play now · 3 <b>speakers and displays</b> on this network (busy rows name the person, R270) · 4 R265’s collapsible <b>All your TVs</b>. <b>No group or “everywhere” row</b> (owner): you pick one place; more rooms are added from the remote (§B4). Choosing a place while something plays <b>here</b> moves it there (lean, Q2). Choosing a place that already plays <b>your</b> other session opens that session’s remote (join).', 'w3');
  Y += 130;
  const full = '<div class="ps-sec">This device</div>' + tgt('phone', 'This phone', 'Playing · Cannery Lights', { here: true, play: true })
    + '<div class="ps-sec">Your Ravilo apps</div>' + tgt('mac', 'MacBook', 'Ready') + tgt('tv', 'Stue TV', 'Ravilo is on screen')
    + '<div class="ps-sec">Speakers and displays</div>' + tgt('speaker', 'Stue', 'Ready') + tgt('speaker', 'Gæsteværelse', 'Ready') + tgt('hub', 'Køkken hub', 'Busy · Olivar is watching', { rt: false })
    + tgt('speaker', 'Kontor', 'Ready');
  cap(0, Y, 'B3·a · Music playing here · every tier');
  phone(0, Y + 36, {}, sbar() + top('Listen', {}) + listen() + mini({ ...S.music, tgt: 'This phone', sub: 'Harbour Lights' }) + nav('music', 0) + sheet('Play on…', full, '<div class="ps-col"><span>All your TVs</span><span style="color:var(--ink-dim)">(2)</span><span class="ct">' + G.down + '</span></div>'), 'B3a · Play on full');
  note(0, Y + 970, '<b>Køkken hub</b> is busy with another person: the row names him and is not tappable (R270; Q1 decides whether a household setting lets Eyð take it). Soveværelse TV is under <i>All your TVs</i> — its app is not on screen, so it cannot play right now.');
  cap(PX, Y, 'B3·b · Tapped Stue TV · the session moves');
  phone(PX, Y + 36, {}, sbar() + top('Listen', { lit: true }) + listen() + mini(S.music, { state: 'moving', line: 'Moving to Stue TV…' }) + nav('music', 0), 'B3b · moving');
  note(PX, Y + 970, 'One line on the bar, no spinner wall: <i>Moving to Stue TV…</i> with a running hairline. The phone stops at the hand-over position, not before. Then the bar reads <i>Stue TV</i> in accent (§B7).');
  cap(PX * 2, Y, 'B3·c · Stue already plays your music · join');
  phone(PX * 2, Y + 36, {}, sbar() + top('Browse', { lit: true, n: 1 }) + listen() + nav('music', 1) + sheet('Play on…', '<div class="ps-sec">This device</div>' + tgt('phone', 'This phone', '', { here: true })
    + '<div class="ps-sec">Speakers and displays</div>' + tgt('group', 'Stue + Gæsteværelse', 'Playing · Cannery Lights', { play: true }) + tgt('hub', 'Køkken hub', 'Busy · Olivar is watching', { rt: false }) + tgt('speaker', 'Kontor', 'Ready')), 'B3c · join');
  note(PX * 2, Y + 970, 'Nothing plays on this phone. The group that is part of Eyð’s session is <b>one row</b> (its two speakers are not listed again) and reads <i>Playing · Cannery Lights</i> — tapping it opens that remote. Nothing restarts.');
  cap(PX * 3, Y, 'B3·d · Films mode · no speakers (video never goes to one)');
  phone(PX * 3, Y + 36, {}, sbar() + top('Home', {}) + home() + nav('films', 0) + sheet('Play on…', '<div class="ps-sec">This device</div>' + tgt('phone', 'This phone', '', { here: true })
    + '<div class="ps-sec">Your Ravilo apps</div>' + tgt('mac', 'MacBook', 'Ready') + tgt('tv', 'Stue TV', 'Ravilo is on screen') + '<div class="ps-sec">Displays</div>' + tgt('hub', 'Køkken hub', 'Busy · Olivar is watching', { rt: false }), '<div class="ps-col"><span>All your TVs</span><span style="color:var(--ink-dim)">(2)</span><span class="ct">' + G.down + '</span></div>'), 'B3d · films mode');
  note(PX * 3, Y + 970, 'Speakers are <b>absent</b> in films mode (Speakers round, decided). The tier is called <i>Displays</i> when only displays are left. The <b>desktop</b> draws this same list as a popover from the toolbar’s glyph (§B8).');

  /* ================= B4 · groups ================= */
  Y += 1180;
  rule(Y - 40);
  lbl(0, Y, { t: '§B4 · more rooms · owner 2026-10-03' }, 'Pick one, then add another', 'There is <b>no cast-everywhere button</b> and no <i>Make a group…</i>: <i>Play on…</i> starts on <b>one</b> place. While it plays, the remote’s <b>Speakers</b> sheet has <i>Add a speaker…</i>; each tap adds one room, which joins at <b>its real level</b> (never a default — R353). Names: <i>Stue</i> → <i>Stue + Gæsteværelse</i> → <i>Stue + 2</i>, in the order they joined.', 'w3');
  Y += 130;
  cap(0, Y, 'B4·a · Playing on Stue · Add a speaker… · tap one');
  phone(0, Y + 36, {}, remote({ ...S.music, tgt: 'Stue', tk: 'speaker' }, { vol: 40 }) + nav('music', 2) + sheet('Add a speaker', '<div class="ps-ask">Tap a room to add it to <b>Cannery Lights</b>. It joins at the volume it has now.</div>'
    + tgt('speaker', 'Stue', 'Playing · Cannery Lights', { here: true }) + tgt('speaker', 'Gæsteværelse', 'Ready · 24%') + tgt('speaker', 'Kontor', 'Ready · 30%') + tgt('hub', 'Køkken hub', 'Busy · Olivar is watching', { rt: false })), 'B4a · add a speaker');
  note(0, Y + 970, 'One tap adds one room — no ticks, no <i>Play on 2 speakers</i> button, nothing to confirm. The sheet stays open so a third can follow. A busy room is listed but not tappable. <span class="ps-tag dep">desktop depends on §8 Q5</span> the Mac/Linux build offer <i>Add a speaker…</i> only if our own sender can grow a group — drawn as if it can.');
  cap(PX, Y, 'B4·b · Playing · the remote’s Speakers sheet');
  phone(PX, Y + 36, {}, remote({ ...S.music, tgt: 'Stue + 2', tk: 'group' }, { vol: 36 }) + nav('music', 2) + sheet('Speakers', '<div style="padding:0 0 4px">' + '<div class="ps-tgt"><span class="ic">' + G.vol + '</span><span class="tx"><span class="nm" style="font-weight:700">Volume</span><div class="vl"><span class="tr"><i style="width:36%;background:var(--accent-2)"></i><u style="left:36%"></u></span><b>36%</b></div></span></div>'
    + tgt('speaker', 'Stue', '', { vol: 40, rt: false }) + tgt('speaker', 'Gæsteværelse', '', { vol: 24, rt: false }) + tgt('hub', 'Køkken hub', 'Joined 20.38', { vol: 45, rt: false }) + '</div>'
    + '<div class="ps-add">' + G.plus + '<span>Add a speaker…</span></div>'), 'B4b · speakers sheet');
  note(PX, Y + 970, 'On a phone the volume keys already drive the <b>Volume</b> row (Android shows its own panel). This sheet adds the per-room sliders and <b>removing</b> a room: swipe a row left, or untick in <i>Add a speaker…</i>. Master moves all rooms keeping their balance.');
  cap(PX * 2, Y, 'B4·c · Names · 1 · 2 · 3+');
  put('<div style="position:absolute;left:' + PX * 2 + 'px;top:' + (Y + 36) + 'px;width:412px;display:grid;gap:12px" data-skin="aurora">'
    + ['<div class="st-cell"><div class="h"><b>one room</b><span>how it always starts</span></div>' + mini({ ...S.music, tgt: 'Stue', tk: 'speaker' }, { next: true }) + '<div class="cp">Every cast starts on one place. More rooms come from <i>Add a speaker…</i>.</div></div>',
      '<div class="st-cell"><div class="h"><b>two rooms</b><span>one added</span></div>' + mini(S.music, { next: true }) + '</div>',
      '<div class="st-cell"><div class="h"><b>three or more</b><span>first + n</span></div>' + mini({ ...S.music, tgt: 'Stue + 2' }, { next: true }) + '<div class="cp">The full list is in the remote’s chip and the Speakers sheet.</div></div>',
      
      '<div class="st-cell"><div class="h"><b>a room left the group</b><span>R355</span></div>' + mini(S.music, { line: '<span class="on">' + G.group + '<span>Stue</span></span> · Gæsteværelse left' }) + '<div class="cp">For 5 s, then the bar reads just <b>Stue</b> (the group became one speaker).</div></div>'].join('') + '</div>');

  /* ================= B5 · the remote ================= */
  Y += 1180;
  rule(Y - 40);
  lbl(0, Y, { t: '§B5 · reuse R245’s remote + the Playing page' }, 'The remote for any session', 'Added: the <b>place line</b> at the top (tap = <i>Move to…</i>), <b>volume</b> for the place (and <i>Speakers</i> for a group), <b>Play here</b>, <b>Stop</b>, and the queue or episodes editable from every controller. Two controllers at once: the later press wins, both screens update — no dialog, no error.', 'w3');
  Y += 130;
  cap(0, Y, 'B5·a · Music on a group');
  phone(0, Y + 36, {}, remote(S.music, { vol: 34 }), 'B5a · music remote');
  cap(PX, Y, 'B5·b · A film on another TV');
  phone(PX, Y + 36, {}, remote(S.film, { vol: 22, kick: 'Paused' }), 'B5b · film remote');
  cap(PX * 2, Y, 'B5·c · Someone else’s session (Q1 lean: see, not control)');
  phone(PX * 2, Y + 36, {}, remote(S.kid, { vol: false, tline: 'Olivar · Køkken hub', st: 'playing' }).replace(/<div class="ps-tp">[\s\S]*?<\/div><div class="ps-rfoot">[\s\S]*?<\/div><\/div>$/, '<div class="ps-rem-note" style="margin-top:22px;padding:14px 16px;border-radius:12px;background:rgba(255,255,255,.05);border:1px solid var(--line);font-size:14px;line-height:1.5;color:var(--ink-soft)"><b style="color:var(--ink)">Olivar is watching on Køkken hub.</b> Only Olivar can pause or move it.</div></div>'), 'B5c · another person');
  note(PX * 3, Y + 36, '<b>Conflicts, as a state only.</b> Eyð taps ⏸ on the Pixel at 1:12; a moment earlier the MacBook pressed ⏭. The server applied ⏭ first, so Eyð’s pause was made against an old state: it is answered with the new state, not applied. Her screen shows the <b>next song, playing</b> — and the play button is right there. No toast. (Report §4.1, revisions.)<br><br><b>Queue and episodes</b> open the same sheets as today; an edit from any controller reaches every other within one report.<br><br><b>Not shown:</b> <i>Also controlled from MacBook</i> (Q4 lean).');

  /* ================= B6 · starting while sessions exist ================= */
  Y += 1180;
  rule(Y - 40);
  lbl(0, Y, { t: '§B6 · join or new' }, 'Starting playback while sessions run', 'Q2’s lean, drawn: on <b>this</b> device a new play replaces this device’s own session; <b>elsewhere</b> keeps playing and a new session starts here. It asks <b>every time</b> the new thing is the <b>same kind</b> already playing for you elsewhere (music on Stue, this series on a TV), and whenever you pick a busy place — never with an <i>Add</i> (owner 10-04).', 'w3');
  Y += 130;
  cap(0, Y, 'B6·a · A film on the phone while music plays in Stue');
  phone(0, Y + 36, {}, sbar() + top('Home', { lit: true, n: 1 }) + home() + mini({ ...S.film, tgt: 'This phone', sub: 'Paused at 0:04', state: 'paused', pct: 2 }, { more: 1 }) + nav('films', 0), 'B6a · film here, music elsewhere');
  note(0, Y + 970, 'Play → the film player opens here; nothing asks. Back on Home after pausing: the bar is the film (this phone) with <b>+1</b> — Cannery Lights never stopped in Stue + Gæsteværelse.');
  cap(PX, Y, 'B6·b · A song while your music plays on Stue · always asks');
  phone(PX, Y + 36, {}, sbar() + top('Browse', { lit: true, n: 1 }) + listen() + mini(S.music) + nav('music', 1) + sheet('Play “Boathouse” on…', '<div class="ps-ask">Your music is playing on <b>Stue + Gæsteværelse</b>.</div>'
    + tgt('group', 'Stue + Gæsteværelse', 'Instead of Cannery Lights', { play: true }) + tgt('phone', 'This phone', 'Stue keeps playing Cannery Lights'), ''), 'B6b · always asks');
  note(PX, Y + 970, '<b>Owner 10-04: always ask, never offer Add.</b> No <i>Remember</i> switch — it asks every time. Two answers only: play there instead, or play here and leave Stue playing. Adding to what plays stays in the song menu (<i>Play next</i>).');
  cap(PX * 2, Y, 'B6·c · A series page while it plays on a TV');
  phone(PX * 2, Y + 36, {}, sbar() + '<div class="ps-det"><div class="bk" style="' + bg('mes') + '"></div><div class="sc"></div><h2>Mesterholdet</h2></div><div class="ps-btns"><b class="pri">' + G.tv + ' Continue on Stue TV</b><div class="sub">S02E04 · playing there now · 31:20</div><b>' + G.play + ' Play S02E04 here</b></div><div class="ps-syn">Eight families, one kitchen, and a jury that has never cooked. Season 2.</div>' + mini({ ...S.film, art: 'mes', title: 'Mesterholdet', kind: 'ep', sub: 'S02E04', tgt: 'Stue TV', tk: 'tv', state: 'playing', pct: 70 }) + nav('films', 0), 'B6c · continue on TV');
  note(PX * 2, Y + 970, '<b>Continue on Stue TV</b> opens the remote (join). <b>Play here</b> = Q2’s rule: a new session here — Stue TV keeps playing (another evening; Stue TV is free on the rest of the canvas). Lean: <i>Play here</i> also offers <i>Move it here</i> in its ⋯, not as a third button.');
  cap(PX * 3, Y, 'B6·d · An audiobook is the same model (Q8 lean)');
  phone(PX * 3, Y + 36, {}, remote({ ...S.book, tgt: 'Køkken hub', tk: 'hub' }, { vol: 30, kick: 'Kapitel 3 · 1.25× · sleep in 22 min' }).replace('Queue', 'Chapters'), 'B6d · audiobook session');
  note(PX * 3, Y + 970, 'The speed and the sleep timer belong to the <b>session</b>, so they travel with a move and every controller sees them (Q8 lean). Moved to the Køkken hub while cooking; the kicker carries both.');

  /* ================= B7 · moving ================= */
  Y += 1180;
  rule(Y - 40);
  lbl(0, Y, { t: '§B7 · move a session' }, 'Move to… · and its common case, Play here', 'From the row’s ⋯ (A2), the remote’s place line, or the bar. The session continues there at its position and the old place stops. States: <b>moving</b> (one line), <b>moved</b> (accent, 3 s), <b>failed</b> (<i>Couldn’t move — still playing on Stue + Gæsteværelse</i>; nothing was lost).', 'w3');
  Y += 130;
  cap(0, Y, 'B7·a · Move “Cannery Lights” to…');
  phone(0, Y + 36, {}, remote(S.music, { vol: 34 }) + sheet('Move “Cannery Lights” to…', tgt('phone', 'This phone', 'Play here', { play: true }) + '<div class="ps-sec">Your Ravilo apps</div>' + tgt('mac', 'MacBook', 'Ready') + tgt('tv', 'Stue TV', 'Ravilo is on screen')
    + '<div class="ps-sec">Speakers and displays</div>' + tgt('group', 'Stue + Gæsteværelse', 'Playing here now', { here: true }) + tgt('speaker', 'Kontor', 'Ready') + tgt('hub', 'Køkken hub', 'Busy · Olivar is watching', { rt: false })), 'B7a · move to');
  note(0, Y + 970, 'Same list as <i>Play on…</i>, with <b>Play here</b> on top and the current place ticked. <span class="ps-tag dep">receiver work</span> speaker → speaker needs the receiver to accept a transfer (report §8 Q6); until then the server stops one and starts the other at the position.');
  const cell = (h, r, inner, cp) => '<div class="st-cell"><div class="h"><b>' + h + '</b><span>' + r + '</span></div>' + inner + (cp ? '<div class="cp">' + cp + '</div>' : '') + '</div>';
  put('<div style="position:absolute;left:' + PX + 'px;top:' + (Y + 36) + 'px;width:880px;display:grid;grid-template-columns:repeat(2,1fr);gap:16px" data-skin="aurora">'
    + cell('moving', 'one line', mini(S.music, { state: 'moving', line: 'Moving to Kontor…' }), 'Controls stay live; a press is applied wherever the session is when it lands.')
    + cell('moved', '3 s', mini({ ...S.music, tgt: 'Kontor', tk: 'speaker' }, { state: 'moved', line: 'Now on Kontor' }), 'Then the bar reads the place in accent as usual.')
    + cell('failed', 'nothing lost', mini(S.music, { state: 'failed', line: 'Couldn’t move — still playing on Stue + Gæsteværelse' }), 'The old place never stopped. Tap = try again from the sheet.')
    + cell('play here', 'the common move', mini({ ...S.music, tgt: 'This phone' }, { line: 'Harbour Lights' }), 'The phone continues at 1:12; Stue + Gæsteværelse fall silent at the same moment.')
    + cell('remote · moving', 'place line', '<div style="padding:12px 16px 14px"><span class="ps-tline">' + G.speaker + '<span>Moving to Kontor…</span></span></div>', 'The remote’s place line carries the same words; no overlay.')
    + cell('target went offline', 'during a move', mini(S.music, { state: 'failed', line: 'Kontor is offline — still playing on Stue + Gæsteværelse' }), 'Same shape as failed; names the reason in the household’s words.')
    + '</div>');
  K.Y = Y + 1180;
  K.flush();
})();
