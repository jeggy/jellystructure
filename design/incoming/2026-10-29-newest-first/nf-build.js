/* nf-build.js — round `newest-first`, 2026-10-29.

   Builds every frame from window.NF (nf-data.js) using the SHIPPED component classes. Where a
   class name appears here it is a class in design/ravilo/ravilo.css, ravilo-player.css,
   design/ravilo/mobile/ravilo-phone.css, or design/app/wf.css + app.css + detail.css, unless it
   starts `nf-`, which is this round's own and is accounted for in newest-first.css.

   TRAP THIS FILE EXISTS TO AVOID (PROCESS.md, *Two traps that are not visible in the markup*):
   every SVG id is MINTED FROM A COUNTER by `uid()`. This document holds many frames and the
   renderer hides all but one; Chromium will not resolve a paint server defined inside a
   display:none subtree, so a shared gradient id would paint NOTHING in a visible frame, and it
   would not reproduce when that frame is opened alone.
*/

(function (W) {
  'use strict';
  const NF = W.NF;
  if (!NF) throw new Error('nf-build.js loaded without nf-data.js');

  let _id = 0;
  const uid = (p) => p + '-' + (++_id);
  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

  /* ---------------------------------------------------------------- glyphs, all stroked paths.
     Stroked rather than filled, and never a bare colour: the constitution excerpt forbids colour
     alone for the mark, and a stroked glyph is still a glyph in a noir skin and on a screen with
     the colour turned down. */
  const G = {
    /* the newest-first arrow: a down arrow onto a baseline — "the top of this list is the top" */
    newest: '<svg class="nf-ar" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4v11"/><polyline points="7 11 12 16 17 11"/><line x1="5" y1="20" x2="19" y2="20"/></svg>',
    sort: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h11M4 12h8M4 17h5"/><polyline points="17 9 20 6 23 9"/><line x1="20" y1="6" x2="20" y2="18"/></svg>',
    shuffle: '<svg viewBox="0 0 24 24" aria-hidden="true"><polyline points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/><line x1="4" y1="4" x2="9" y2="9"/></svg>',
    play: '<svg viewBox="0 0 24 24" aria-hidden="true" style="fill:currentColor;stroke:none"><path d="M7 4.5v15l13-7.5z"/></svg>',
    trailer: '<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="M10 9.5v5l4.5-2.5z"/></svg>',
    plus: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>',
  };

  /* the brand mark, its gradient id minted per call */
  function mark() {
    const g = uid('nfJelly');
    return '<svg class="mark" viewBox="12 20 76 76" aria-hidden="true">'
      + '<defs><linearGradient id="' + g + '" x1="0" y1="0" x2="1" y2="1">'
      + '<stop offset="0" stop-color="var(--accent)"/><stop offset="1" stop-color="var(--accent-2)"/></linearGradient></defs>'
      + '<path d="M22 52 C22 24 78 24 78 52 C66 45 59 45 50 49 C41 45 34 45 22 52 Z" fill="url(#' + g + ')"/>'
      + '<g stroke="url(#' + g + ')" stroke-width="4.5" stroke-linecap="round" fill="none">'
      + '<path d="M33 51 q-5 12 1 20 q5 8 0 14" opacity=".9"/><path d="M44 52 q-4 13 1 21 q4 9 0 13" opacity=".72"/>'
      + '<path d="M56 52 q4 13 -1 21 q-4 9 0 13" opacity=".72"/><path d="M67 51 q5 12 -1 20 q-5 8 0 14" opacity=".9"/>'
      + '</g></svg>';
  }

  /* ============================================================================== TV: the chrome.
     The shipped .appbar / .brand / .topnav / .navitem / .clock / .avatar, with Series current,
     nothing in the bar focused (focus is always inside the page on these frames) and the brief's
     clock. The viewer's initial is Anna's. */
  function appbar(cur, lang) {
    const nav = [['Home', 'home'], ['Movies', 'movies'], ['Series', 'series'], ['My List', 'list'], ['Discover', 'discover']];
    return '<div class="appbar"><div class="brand">' + mark() + '<span class="wm">Ravilo</span></div>'
      + '<div class="topnav">' + nav.map(([l, k]) =>
          '<span class="navitem' + (k === cur ? ' cur' : '') + '">' + esc(l) + '</span>').join('') + '</div>'
      + '<div class="right"><span class="clock">' + NF.CLOCK + '</span>'
      + '<span class="avatar">' + NF.VIEWER.slice(0, 1) + '</span></div></div>';
  }

  /* ============================================================================== TV: the hero.
     The shipped .detail > .dhero, with the invented poster art drawn as the series' own two-stop
     gradient — never a broadcaster's picture, which the brief and invariant 7 both forbid. */
  function dhero(opts) {
    const L = opts.lang || 'en';
    const art = 'linear-gradient(118deg,#13304a 0%,#1c5570 38%,#3f8aa3 62%,#d9a765 100%)';
    return '<div class="dhero" style="height:620px">'
      + '<div class="hero-bg" style="background:' + art + '"></div>'
      + '<div class="hero-scrim"></div>'
      + '<div class="dhero-body">'
      + '<div class="hero-kicker">' + esc(NF.t('season', L)).toUpperCase() + ' ' + NF.SEASON
      + '<span class="n">' + NF.KEPT + ' ' + esc(NF.t('episodes', L).toLowerCase()) + '</span></div>'
      + '<div class="dhero-title">Dagbók</div>'
      /* The meta line says what the SERIES is. It deliberately does NOT carry an air date: the
         kicker above already names the season and the count, and an "Air date · Thu 29 Oct" on a
         series' meta row reads as the series having aired on one day. The newest episode's date
         belongs to the button and the rail, where it is a thing you can press. */
      /* No age certificate: the brief names the stand-ins exactly and does not name one, and an
         invented rating is an invented fact however small the badge is. */
      + '<div class="hero-meta"><span class="tag">HD</span><span>' + NF.RUNTIME_MIN + ' min</span></div>'
      + '<div class="dsyn-block"><div class="hero-syn dsyn">Dagliga sendingin — ein nýggj hvønn dag.</div></div>'
      + opts.pills
      + '<div class="dactions">' + opts.actions + '</div>'
      + '</div></div>';
  }

  /* the hero's newest-first MARK — the shipped .dnext pill plus this round's nf-mark */
  function markPill(L) {
    return '<span class="dnext nf-mark" role="note" aria-label="' + esc(NF.NEW.newestA11y[L] || NF.NEW.newestA11y.en) + '">'
      + G.newest + esc(NF.t('newest', L)) + '</span>';
  }

  /* the hero's STATE pill — one fact about the viewer, in the slot today's page already uses.
     It carries NO DATE. The button directly under it already names the episode, and on today's
     page both say a six-character code so the repetition is cheap; here both would say a
     ten-character date twice in two pills a centimetre apart, which at three metres reads as one
     fact smeared over two controls. So the pill owns the STATE word and the button owns the DATE,
     and between them nothing is said twice. (The shipped page does repeat itself this way — see
     `.dnext` on a series today. That is inherited, not introduced, and the report says so.) */
  function statePill(state, L) {
    if (state === 's1') return '';
    if (state === 's2') return '<span class="dnext"><span class="dnext-dot"></span>'
      + esc(NF.t('newEpisode', L)) + '</span>';
    if (state === 's3') {
      const left = Math.round((NF.RUNTIME_MIN * 60 - NF.PROGRESS.s3.secs) / 60);
      return '<span class="dnext"><span class="dnext-dot"></span>' + left + ' min left</span>';
    }
    /* s4 — `detail.all_watched` is NOT used here: see nf-data.js NEW.caughtUp for why. */
    return '<span class="dnext done"><span class="dnext-ck">✓</span>'
      + esc(NF.NEW.caughtUp[L] || NF.NEW.caughtUp.en) + '</span>';
  }

  /* the primary button, from a Play RULE's answer — never a typed label */
  function primaryBtn(decision, L, focused) {
    const d = esc(NF.fmt(decision.iso, L));
    const label = decision.kind === 'resume' ? NF.t('resume', L)
      : decision.kind === 'playNewest' ? (NF.NEW.playNewest[L] || NF.NEW.playNewest.en)
      : decision.kind === 'startOver' ? 'Start over'
      : NF.t('play', L);
    const sub = decision.kind === 'play' || decision.kind === 'startOver' ? NF.se(decision.iso) : d;
    return '<span class="btn primary' + (focused ? ' focused' : '') + '">' + G.play
      + esc(label) + ' · ' + esc(sub) + '</span>';
  }
  const restBtns = () => '<span class="btn ghost">' + G.trailer + 'Trailer</span>'
    + '<span class="btn ghost">' + G.plus + 'My List</span>';

  /* ============================================================ TV: an episode card in the rail.
     The shipped .ep-card / .ep-play / .ep-still / .ep-dur / .ep-check / .ep-prog / .ep-info, with
     the LABEL LADDER swapped for option A: the air date on .ep-t's rung, the programme's own
     heading on .ep-d's. `mode` is 'date' (option A), 'date+code' (the variant frame) or 'code'
     (today's page and option C, which label by the episode code as the product does now). */
  function epCard(ep, prog, mode, L, focused) {
    const p = prog[ep.iso];
    const pct = p.watched ? 100 : Math.round(p.secs / ep.secs * 100);
    const isNewest = ep.iso === NF.newestIso;
    /* The NEW ribbon: the shipped .ep-ribbon, carrying the shipped `tile.badge_new` word. Drawn
       only on a newest-first page, only on the newest episode, only while it is untouched — and
       only when SOMETHING OLDER HAS BEEN WATCHED. That last clause is the one that matters and it
       is easy to get wrong: on a series nobody has started (s1) every episode is equally new, so a
       ribbon on the newest one is not information, it is decoration on an arbitrary card. "New"
       means "newer than where you got to", which needs a place you got to. */
    const anyOlderWatched = NF.EPISODES.some((e) => e.iso < ep.iso && prog[e.iso].watched);
    const ribbon = (mode !== 'code' && isNewest && !p.watched && p.secs === 0 && anyOlderWatched)
      ? '<span class="ep-ribbon" aria-label="' + esc((NF.NEW.newOn[L] || NF.NEW.newOn.en).replace('{date}', NF.fmt(ep.iso, L)))
        + '">' + esc(NF.t('badgeNew', L)) + '</span>' : '';
    let label, under;
    if (mode === 'code') { label = NF.se(ep.iso); under = ep.title; }
    else { label = NF.fmt(ep.iso, L); under = ep.title; }
    return '<div class="ep-card' + (p.watched ? ' watched' : '') + (p.secs > 0 && !p.watched ? ' inprogress' : '') + '">'
      + '<div class="ep-play' + (focused ? ' focused' : '') + '">'
      + '<div class="ep-still"><div class="grad" style="background:' + ep.still + '"></div>'
      + '<span class="ep-dur">' + ep.mins + ' min</span>'
      + ribbon
      + (p.watched ? '<span class="ep-check" aria-label="' + esc(NF.t('watched', L)) + '">✓</span>' : '')
      + (p.secs > 0 && !p.watched ? '<span class="ep-prog"><i style="width:' + pct + '%"></i></span>' : '')
      + '<span class="play"><span>' + G.play + '</span></span>'
      + '</div>'
      + '<div class="ep-info"><div class="ep-t nf-airlab">' + esc(label) + '</div>'
      + '<div class="ep-d">' + esc(under) + '</div>'
      + (mode === 'date+code' ? '<div class="nf-code">' + esc(NF.se(ep.iso)) + '</div>' : '')
      + '</div></div></div>';
  }

  /* ================================================================== TV: the whole series page.
     `o` = { option:'a'|'c'|'today', state, order:'newest'|'oldest', focus, mode, lang }
     Option A's page is today's page MINUS Shuffle, MINUS Start over and MINUS the watched count,
     PLUS the hero mark — which is the brief's own definition of the bundle, expressed once here so
     the four A frames cannot drift from each other. */
  function tvSeries(o) {
    const L = o.lang || 'en';
    /* Every TV frame sits on one of the brief's four named states — including the reference frame
       and option C, which the brief puts in s1 (`the rail oldest first and Play · S2026E291`).
       Putting C on s1 too is what makes c1 a ONE-DIFFERENCE diff against the reference: the same
       page, the same progress, the same button, plus the control. Any other state would have the
       owner comparing two things at once and attributing one to the other. */
    const prog = NF.progressOf(o.state || 's1');
    const decision = o.option === 'a' ? NF.playA(prog) : NF.playToday(prog);
    const eps = o.order === 'newest' ? NF.EPISODES.slice().reverse() : NF.EPISODES.slice();
    const watched = NF.watchedCount(prog);

    /* the hero's pill row */
    let pills = '';
    if (o.option === 'a') pills = '<div class="dnext-row">' + markPill(L) + statePill(o.state, L) + '</div>';
    else {
      /* today's page and option C: the shipped Up next / Resume pill, worked out by today's rule */
      const d = NF.se(decision.iso);
      pills = '<div class="dnext-row"><span class="dnext"><span class="dnext-dot"></span>'
        + (decision.kind === 'resume' ? esc(NF.t('resume', L)) + ' ' + d : 'Up next · ' + d)
        + '</span></div>';
    }

    /* the actions. Shuffle is a Ravilo page's second action on any series with 9+ episodes
       (R343); option A removes it and the reference frame keeps it. */
    let actions = primaryBtn(decision, L, o.focus === 'play') + restBtns();
    if (o.option !== 'a') actions = primaryBtn(decision, L, o.focus === 'play')
      + '<span class="btn ghost' + (o.focus === 'shuffle' ? ' focused' : '') + '">' + G.shuffle + esc(NF.t('shuffle', L)) + '</span>'
      + restBtns();

    /* the Episodes header. Option A drops the watched count and its bar; today's page and option C
       keep both, because that is what ships. */
    let head = '<div class="dsec-head"><h2>' + esc(NF.t('episodes', L)) + '</h2>';
    if (o.option === 'a') {
      head += '<span class="dsec-sub">' + esc(NF.t('season', L)) + ' ' + NF.SEASON + '</span>';
    } else {
      head += '<span class="dsec-sub">' + esc(NF.t('epsWatched', L).replace('{w}', watched).replace('{t}', NF.KEPT)) + '</span>'
        + '<span class="seasonbar"><i style="width:' + Math.round(watched / NF.KEPT * 100) + '%"></i></span>';
    }
    head += '</div>';

    /* the season pills, and — on option C only — the sort control after the shipped divider */
    let pillRow = '<div class="seasonpills"><span class="spill cur">' + esc(NF.t('season', L)) + ' ' + NF.SEASON + '</span>';
    if (o.option === 'c') {
      pillRow += '<span class="spill-sep"></span><span class="nf-sortlab">' + esc(NF.t('sort', L)) + '</span>'
        + '<span class="spill nf-sortpill' + (o.order === 'oldest' ? ' cur' : '')
        + (o.focus === 'oldest' ? ' focused' : '') + '">' + esc(NF.t('oldest', L)) + '</span>'
        + '<span class="spill nf-sortpill' + (o.order === 'newest' ? ' cur' : '')
        + (o.focus === 'newest' ? ' focused' : '') + '">' + esc(NF.t('newest', L)) + '</span>';
    }
    pillRow += '</div>';

    const focusIso = o.focusEp || null;
    const rail = '<div class="eprow" style="display:flex;gap:24px">'
      + eps.map((e) => epCard(e, prog, o.mode || 'date', L, e.iso === focusIso)).join('') + '</div>';

    return '<div class="nf-tv" data-skin="' + (o.skin || 'aurora') + '" lang="' + L + '">'
      + appbar('series', L)
      + '<div class="detail" style="position:absolute;inset:0">'
      + dhero({ lang: L, pills, actions })
      + '<div class="dsec">' + head + pillRow + rail + '</div>'
      + '</div>'
      + (o.stamp ? '<span class="nf-stamp">' + esc(o.stamp) + '</span>' : '')
      + '</div>';
  }

  /* ============================================================================ TV: Home's row.
     The shipped .rail / .rail-head / .tile.cont, with Dagbók's tile among the Home mock's own
     Continue tiles. `h` is the brief's h1 / h2 / h3. */
  function tvHome(o) {
    const L = o.lang || 'en';
    /* NO `.tt` title overlay on the art. The shipped `.tile.land .art .tt` is the fallback for a
       tile with no picture; drawn together with the `.label` under the card — which this row also
       has — every tile said its own name twice, once over the artwork and once below it. One
       name, under the card, which is where the rest of Home puts it. */
    const tile = (t, focused) => {
      const art = 'linear-gradient(140deg,oklch(.40 .085 ' + t.h + '),oklch(.63 .105 ' + ((t.h + 44) % 360) + '))';
      return '<div class="tile land cont' + (focused ? ' focused' : '') + '" style="width:360px">'
        + '<div class="art"><div class="grad" style="background:' + art + '"></div>'
        + (t.badge ? '<span class="cont-badge nf-tilenew">' + esc(t.badge) + '</span>' : '')
        + (t.pct > 0 ? '<span class="pbar"><i style="width:' + t.pct + '%"></i></span>'
            : t.emptyBar ? '<span class="nf-barslot" aria-hidden="true"></span>' : '')
        + '</div>'
        + '<div class="label">' + esc(t.title) + '</div>'
        + '<div class="sub cont-sub">' + esc(t.sub) + '</div></div>';
    };
    /* Dagbók's own tile, per state. The BADGE carries the brief's own words and the SUB carries
       what the badge does not, so between them nothing is said twice — and the label stays the
       series name, exactly like every neighbour's. */
    const d = NF.fmt(NF.newestIso, L);
    let dag = null;
    if (o.h === 'h1') dag = {
      title: 'Dagbók', h: NF.doy(NF.newestIso) * 31 % 360,
      badge: (NF.NEW.newOn[L] || NF.NEW.newOn.en).replace('{date}', d),
      sub: NF.RUNTIME_MIN + ' min', pct: 0, emptyBar: !!o.emptyBar,
    };
    if (o.h === 'h2') dag = {
      title: 'Dagbók', h: NF.doy(NF.newestIso) * 31 % 360, badge: d,
      sub: Math.round((NF.RUNTIME_MIN * 60 - NF.PROGRESS.s3.secs) / 60) + ' min left',
      pct: Math.round(NF.PROGRESS.s3.secs / (NF.RUNTIME_MIN * 60) * 100),
    };
    const tiles = (dag ? [dag] : []).concat(NF.OTHER_TILES);
    return '<div class="nf-tv" data-skin="' + (o.skin || 'aurora') + '" lang="' + L + '">'
      + appbar('home', L)
      + '<div class="hero" style="height:520px;flex:none"><div class="hero-slide on">'
      + '<div class="hero-bg" style="background:linear-gradient(118deg,#1a2340 0%,#2b4f74 42%,#4e86a0 68%,#caa06b 100%)"></div>'
      /* The hero is `Jarnvegur` and NOT `Havets Hjarta`: the latter is one of the shipped Home
         mock's own Continue tiles, so putting it in the hero too made the same title appear twice
         in one frame, four hundred pixels apart, for no reason the picture explains. Both names
         are from the shipped corpus (design/ravilo/ravilo-data.js). */
      + '<div class="hero-scrim"></div><div class="hero-body"><div class="hero-kicker">Featured</div>'
      + '<div class="hero-title">Jarnvegur</div>'
      + '<div class="hero-syn">The last line out of the valley runs one more winter.</div>'
      + '<div class="hero-actions"><span class="btn primary">' + G.play + esc(NF.t('play', L)) + '</span>'
      + '<span class="btn ghost">' + G.plus + 'My List</span></div></div></div></div>'
      /* The first tile is FOCUSED. A TV frame has to show the focused state of what is focusable
         in it (SKILL-house.md, 10-foot focus), and on this row the focused tile is also the one
         the round is about — so the badge is seen at the moment a viewer is actually reading it. */
      + '<div class="rail"><div class="rail-head"><h2>' + esc(NF.t('contRow', L)) + '</h2>'
      + '<span class="more">See all ›</span></div>'
      + '<div style="display:flex;gap:24px;padding:0 64px">'
      + tiles.map((t, i) => tile(t, i === 0)).join('') + '</div></div>'
      + (o.stamp ? '<span class="nf-stamp">' + esc(o.stamp) + '</span>' : '')
      + '</div>';
  }

  /* ================================================================== TV: the player at the end.
     The shipped .player / .pl-* chrome over a static credit roll. Anna is in the credits of Wed
     28 Oct, which the brief fixes, so every number here is that episode's. */
  function tvPlayer(o) {
    const L = o.lang || 'en';
    const ep = NF.EPISODES.find((e) => e.iso === NF.prevIso);
    const pos = ep.secs - 24;                       /* in the credits, 24 s from the end */
    const hhmm = (s) => Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
    const credits = '<div class="nf-credits"><div class="nf-cr-col"><div class="nf-cr-h">Dagbók</div>'
      + [['Framleiðari', 'Hanna Mikkelsen'], ['Klipping', 'Jákup Olsen'], ['Ljóð', 'Birita Nielsen'],
         ['Grafikkur', 'Sunneva Hansen'], ['Framført av', 'Anna Dahl']]
        .map(([k, v]) => '<div class="nf-cr-r"><span>' + esc(k) + '</span><span>' + esc(v) + '</span></div>').join('')
      + '</div></div>';

    /* the Up next card, option B only. The shipped .pl-nextup / .pl-nu-* with the countdown ring
       and the shipped `player.up_next` kicker — today's next-up card language, pointed at the next
       NEWER unwatched episode, which in the brief's state is Thu 29 Oct. */
    let nextup = '';
    if (o.upnext) {
      const nx = NF.EPISODES.find((e) => e.iso === NF.newestIso);
      const secs = 8;
      /* The ring lives INSIDE `.pl-nu-shot` as `.ring`, which is where ravilo-player.css puts it
         (56×56, bottom-right of the still). Drawn as a sibling it sat outside the card's own
         composition and the countdown read as a separate control. `r` is the circumference of
         r=24: 2πr = 150.8, and the dash offset is the fraction already elapsed. */
      const circ = 2 * Math.PI * 24;
      nextup = '<div class="pl-nextup">'
        + '<div class="nu-kick">' + esc(NF.t('upNext', L)) + '</div>'
        + '<div class="pl-nu-body">'
        + '<div class="pl-nu-shot"><span class="g" style="background:' + nx.still + '"></span>'
        + '<svg class="ring" viewBox="0 0 56 56">'
        + '<circle class="nu-ring-bg" cx="28" cy="28" r="24"/>'
        + '<circle class="nu-ring-fg" cx="28" cy="28" r="24" stroke-dasharray="' + circ.toFixed(1) + '"'
        + ' stroke-dashoffset="' + (circ * 0.33).toFixed(1) + '"/>'
        + '<text class="nu-ring-num" x="28" y="28">' + secs + '</text></svg></div>'
        + '<div class="pl-nu-info"><div class="pl-nu-ep">' + esc(NF.fmt(nx.iso, L)) + '</div>'
        + '<div class="pl-nu-title">' + esc(nx.title) + '</div>'
        + '<div class="pl-nu-desc">' + nx.mins + ' min · ' + esc(NF.t('newEpisode', L)) + '</div>'
        + '<div class="pl-nu-acts"><span class="pl-nu-btn primary focused">' + G.play
        + '<span class="ct">' + esc(NF.t('playIn', L).replace('{secs}', secs)) + '</span></span>'
        + '<span class="pl-nu-btn">' + esc(NF.t('watchCredits', L)) + '</span></div></div>'
        + '</div></div>';
    }

    return '<div class="nf-tv" data-skin="' + (o.skin || 'aurora') + '" lang="' + L + '" style="background:#000">'
      /* `.nextup` on the player is what ravilo-player.css reveals `.pl-nextup` with — the card is
         opacity:0 without it, which is how the first render of this frame drew NOTHING at all. */
      + '<div class="player' + (o.upnext ? ' nextup' : '') + '" style="position:absolute;inset:0;opacity:1">'
      + '<div class="pl-stage">' + credits + '</div>'
      + '<div class="pl-chrome" style="opacity:1">'
      + '<div class="pl-scrim-top"></div><div class="pl-scrim-bot"></div>'
      + '<div class="pl-top"><span class="pl-back">‹ Back</span>'
      + '<span class="pl-stream"><span class="pl-pill">1080p</span><span class="pl-pill">fo</span></span></div>'
      + '<div class="pl-bottom"><div class="pl-meta">'
      /* The kicker is the DATE alone. The title under it is the episode's own heading, which on
         this programme already begins "Dagbók" — a kicker that repeated it put the series name on
         the screen twice, one line apart. */
      + '<div class="pl-kicker">' + esc(NF.fmt(ep.iso, L)) + '</div>'
      + '<div class="pl-title">' + esc(ep.title) + '</div>'
      + '<div class="pl-sub2">' + esc(NF.t('season', L)) + ' ' + NF.SEASON + ' · ' + esc(NF.se(ep.iso)) + '</div>'
      + '</div>'
      + '<div class="pl-seekrow"><span class="pl-time">' + hhmm(pos) + '</span>'
      + '<span class="pl-bar-wrap"><span class="pl-bar">'
      + '<span class="pl-buffered" style="width:100%"></span>'
      + '<span class="pl-played" style="width:' + (pos / ep.secs * 100).toFixed(1) + '%"></span>'
      + '<span class="pl-handle" style="left:' + (pos / ep.secs * 100).toFixed(1) + '%"></span>'
      + '</span></span><span class="pl-time">\u22120:' + String(ep.secs - pos).padStart(2, '0') + '</span></div>'
      + '</div></div>'
      + nextup
      + (o.stamp ? '<span class="nf-stamp">' + esc(o.stamp) + '</span>' : '')
      + '</div></div>';
  }

  /* =============================================================== PHONE: the series page (412).
     The shipped .detail / .dscroll / .dhero / .dbody / .dtitle / .dmeta / .dbtns / .btn.play /
     .seclbl / .melist / .merow-single / .methumb / .memeta2 — the phone's own vocabulary, not a
     scaled TV frame. Portrait, and a phone held upright has no player chrome of its own (R244). */
  function phSeries(o) {
    const L = o.lang || 'en';
    const prog = NF.progressOf(o.state || 's1');
    const decision = o.option === 'a' ? NF.playA(prog) : NF.playToday(prog);
    const eps = o.order === 'newest' ? NF.EPISODES.slice().reverse() : NF.EPISODES.slice();
    const watched = NF.watchedCount(prog);
    const d = NF.fmt(NF.newestIso, L);

    const row = (ep) => {
      const p = prog[ep.iso];
      const pct = p.watched ? 100 : Math.round(p.secs / ep.secs * 100);
      const isNewest = ep.iso === NF.newestIso;
      /* same rule as the TV rail: "new" needs a place you got to. See epCard(). */
      const anyOlderWatched = NF.EPISODES.some((e) => e.iso < ep.iso && prog[e.iso].watched);
      const label = o.option === 'a' ? NF.fmt(ep.iso, L) : NF.se(ep.iso);
      return '<div class="merow-single">'
        + '<div class="methumb" style="background:' + ep.still + '">'
        + (p.watched ? '<span class="mwk">✓</span>' : '')
        + (o.option === 'a' && isNewest && !p.watched && p.secs === 0 && anyOlderWatched
            ? '<span class="nf-phnew">' + esc(NF.t('badgeNew', L)) + '</span>' : '')
        + (p.secs > 0 && !p.watched
            ? '<span style="position:absolute;left:5px;right:5px;bottom:5px;height:3px;border-radius:3px;background:rgba(255,255,255,.3)">'
              + '<i style="display:block;height:100%;border-radius:3px;background:var(--accent);width:' + pct + '%"></i></span>' : '')
        + '</div>'
        + '<div class="memeta2"><div class="mt2 nf-airlab">' + esc(label) + '</div>'
        + '<div class="md2">' + esc(ep.title) + ' · ' + ep.mins + ' min</div></div></div>';
    };

    /* the Episodes header. Option A: the season, no count, no Shuffle. Option C: today's header
       plus the sort control, which on a phone is a TAP and so is the shipped .mshuf button shape
       rather than two focusable pills — a phone has no D-pad and no focus to show. */
    let sec = '<div class="seclbl mrow">' + esc(NF.t('episodes', L))
      + '<span style="font-weight:400;color:var(--ink-dim);font-family:Sora,sans-serif;font-size:12px;">'
      + esc(NF.t('season', L)) + ' ' + NF.SEASON
      + (o.option === 'a' ? '' : ' · ' + esc(NF.t('epsWatched', L).replace('{w}', watched).replace('{t}', NF.KEPT)))
      + '</span>';
    if (o.option === 'c') sec += '<button class="mshuf">' + G.sort + esc(NF.t(o.order === 'newest' ? 'newest' : 'oldest', L)) + '</button>';
    sec += '</div>';

    const pill = o.option === 'a'
      ? '<div style="display:flex;gap:7px;flex-wrap:wrap;margin-top:12px">'
        + '<span class="dnext nf-mark" style="font-size:12.5px;padding:6px 11px;margin:0" role="note" aria-label="'
        + esc(NF.NEW.newestA11y[L] || NF.NEW.newestA11y.en) + '">'
        + G.newest.replace('class="nf-ar"', 'class="nf-ar" style="width:14px;height:14px"')
        + esc(NF.t('newest', L)) + '</span>'
        + (o.state === 's2' ? '<span class="dnext" style="font-size:12.5px;padding:6px 11px;margin:0">'
            + '<span class="dnext-dot" style="width:7px;height:7px"></span>' + esc(NF.t('newEpisode', L)) + '</span>' : '')
        + '</div>'
      : '';

    const label = decision.kind === 'resume' ? NF.t('resume', L)
      : decision.kind === 'playNewest' ? (NF.NEW.playNewest[L] || NF.NEW.playNewest.en)
      : NF.t('play', L);
    const sub = decision.kind === 'play' ? NF.se(decision.iso) : NF.fmt(decision.iso, L);

    return '<div class="nf-ph" data-skin="' + (o.skin || 'aurora') + '" lang="' + L + '">'
      + '<div class="statusbar"><span>' + NF.CLOCK + '</span></div>'
      + '<div class="detail on" style="position:absolute;inset:0;transform:none"><div class="dscroll">'
      + '<div class="dhero" style="background:linear-gradient(118deg,#13304a 0%,#1c5570 38%,#3f8aa3 62%,#d9a765 100%)">'
      + '<div class="scrim"></div><button class="dback">‹</button></div>'
      + '<div class="dbody"><div class="dtitle">Dagbók</div>'
      + '<div class="dmeta"><span>' + esc(NF.t('season', L)) + ' ' + NF.SEASON + '</span><span>·</span>'
      + '<span>' + NF.KEPT + ' ' + esc(NF.t('episodes', L).toLowerCase()) + '</span><span>·</span>'
      + '<span>' + NF.RUNTIME_MIN + ' min</span></div>'
      + pill
      + '<div class="dbtns"><button class="btn play">' + G.play + esc(label) + ' · ' + esc(sub) + '</button>'
      + '<button class="btn ghost">＋</button></div>'
      + '<div class="dsyn">Dagliga sendingin — ein nýggj hvønn dag.</div>'
      + sec
      + '<div class="melist">' + eps.map(row).join('') + '</div>'
      + '</div></div></div>'
      + (o.stamp ? '<span class="nf-stamp">' + esc(o.stamp) + '</span>' : '')
      + '</div>';
  }

  /* ============================================================== PHONE: Home's Continue row. */
  function phHome(o) {
    const L = o.lang || 'en';
    const d = NF.fmt(NF.newestIso, L);
    const wide = (t) => {
      const art = 'linear-gradient(140deg,oklch(.40 .085 ' + t.h + '),oklch(.63 .105 ' + ((t.h + 44) % 360) + '))';
      return '<div class="tile" style="width:196px">'
        + '<div class="poster" style="width:196px;height:110px;border-radius:12px;background:' + art + '">'
        + (t.badge ? '<span class="pb" style="left:7px;right:auto;top:7px">' + esc(t.badge) + '</span>' : '')
        + (t.pct > 0 ? '<span style="position:absolute;left:8px;right:8px;bottom:8px;height:4px;border-radius:3px;background:rgba(0,0,0,.45)">'
            + '<i style="display:block;height:100%;border-radius:3px;background:var(--accent);width:' + t.pct + '%"></i></span>'
          : t.emptyBar ? '<span class="nf-barslot" style="left:8px;right:8px;bottom:8px;height:4px"></span>' : '')
        + '</div>'
        + '<div class="tlabel">' + esc(t.title) + '</div><div class="tsub">' + esc(t.sub) + '</div></div>';
    };
    let dag = null;
    if (o.h === 'h1') dag = { title: 'Dagbók', h: NF.doy(NF.newestIso) * 31 % 360,
      badge: (NF.NEW.newOn[L] || NF.NEW.newOn.en).replace('{date}', d),
      sub: NF.RUNTIME_MIN + ' min', pct: 0, emptyBar: !!o.emptyBar };
    if (o.h === 'h2') dag = { title: 'Dagbók', h: NF.doy(NF.newestIso) * 31 % 360, badge: d,
      sub: Math.round((NF.RUNTIME_MIN * 60 - NF.PROGRESS.s3.secs) / 60) + ' min left',
      pct: Math.round(NF.PROGRESS.s3.secs / (NF.RUNTIME_MIN * 60) * 100) };
    const tiles = (dag ? [dag] : []).concat(NF.OTHER_TILES);
    return '<div class="nf-ph" data-skin="' + (o.skin || 'aurora') + '" lang="' + L + '">'
      + '<div class="statusbar"><span>' + NF.CLOCK + '</span></div>'
      + '<div class="appbar"><div class="brand">' + mark() + '<span class="wm">Ravilo</span></div></div>'
      + '<div class="scroll" style="padding-top:4px">'
      + '<div class="hero"><div class="hslide"><div class="hcard" style="height:300px;background:linear-gradient(118deg,#1a2340 0%,#2b4f74 42%,#4e86a0 68%,#caa06b 100%)">'
      + '<div class="scrim"></div><div class="body"><div class="htitle">Jarnvegur</div>'
      + '<div class="hbtns"><button class="btn play">' + G.play + esc(NF.t('play', L)) + '</button>'
      + '<button class="btn ghost">ⓘ</button></div></div></div></div></div>'
      + '<div class="row"><h3>' + esc(NF.t('contRow', L)) + '<span class="more">See all ›</span></h3>'
      + '<div class="track">' + tiles.map(wide).join('') + '</div></div>'
      /* A second row, in the shipped poster shape with shipped corpus names. Home is never one
         row, and a 915 px frame holding a single rail reads as an unfinished picture rather than
         as a phone screen — which makes "is Dagbók's tile right?" harder to answer, not easier,
         because there is nothing around it to judge it against. */
      + '<div class="row"><h3>Newly Added Series<span class="more">See all ›</span></h3><div class="track">'
      + [['Arvur', 160], ['Havets Folk', 95], ['Nordvest', 224], ['Trolde', 300], ['Vintereventyr', 20]]
          .map(([t, h]) => '<div class="tile"><div class="poster" style="background:'
            + 'linear-gradient(140deg,oklch(.40 .085 ' + h + '),oklch(.63 .105 ' + ((h + 44) % 360) + '))">'
            + '<span class="ini">' + esc(t.slice(0, 2).toUpperCase()) + '</span></div>'
            + '<div class="tlabel">' + esc(t) + '</div></div>').join('')
      + '</div></div>'
      + '</div>'
      + (o.stamp ? '<span class="nf-stamp">' + esc(o.stamp) + '</span>' : '')
      + '</div>';
  }

  /* ========================================================================= ADMIN: the switch.
     The shipped .card / .row / .field / label / .seg / .hint / .chip / .badge / .ep / .ep-row —
     every one of them `wf.css` or `detail.css`. The brief's preference is met exactly: NO new
     control, the existing `.seg`. The round's own `nf-order` is the row's geometry and the dashed
     provenance rule, nothing else.

     The constitution excerpt rules the whole frame: the admin renders server-pushed state only, so
     the `.seg` shows WHAT THE SERVER STORED and the provenance line is the server's sentence. The
     round draws no pending or optimistic position. */
  function adminCard(o) {
    const order = o.order;                                   /* 'oldest' | 'newest' */
    const eps = order === 'newest' ? NF.EPISODES.slice().reverse() : NF.EPISODES.slice();
    const seg = '<span class="seg">'
      + '<span' + (order === 'oldest' ? ' class="on"' : '') + '>' + NF.SHIPPED.oldest.en + '</span>'
      + '<span' + (order === 'newest' ? ' class="on"' : '') + '>' + NF.SHIPPED.newest.en + '</span>'
      + '</span>';
    /* The provenance line is drawn on BOTH values and not only on the one Auto-fetch chose: a line
       that appears only when the fetcher set it reads as that value being special rather than as
       one of two the admin may pick. On the hand-set value it says who set it last instead. */
    const prov = order === 'newest'
      ? '<div class="nf-prov"><span class="badge info">Auto-fetch</span><span><b>'
        + NF.NEW.setByFetch.en + '.</b> Dagbók’s episodes are numbered by air date, so Auto-fetch set '
        + 'this when it confirmed the series. You can change it.</span></div>'
      : '<div class="nf-prov"><span class="badge flat">Set here</span><span>Changed on this page, '
        + '29 Oct 2026 21:38. Auto-fetch set <b>' + NF.SHIPPED.newest.en + '</b> and will not set it again.</span></div>';

    const epRow = (ep) => '<div class="ep"><div class="ep-row">'
      + '<div class="ep-thumb" style="border-radius:6px;background:' + ep.still + '"></div>'
      + '<div class="ep-meta"><div class="t"><span class="num mono">' + NF.se(ep.iso) + '</span> ' + esc(ep.title) + '</div>'
      + '<div class="s">' + ep.mins + ' min · <span class="ep-flags"><span class="badge ok">complete</span></span>'
      + ' · <span class="ep-added">aired ' + esc(NF.fmt(ep.iso, 'en')) + '</span></div></div>'
      + '<span class="ep-chev">›</span></div></div>';

    return '<div class="nf-ad"' + (o.theme === 'light' ? ' data-theme="light"' : '') + '>'
      + '<div class="card">'
      /* No season picker in the head: this programme has ONE season, and a segmented control with
         a single segment is a control that cannot be operated. The shipped page draws the picker
         because its stand-in has two seasons. */
      + '<div class="row center"><h4 style="margin:0;white-space:nowrap;">' + esc(NF.t('season', 'en')) + ' ' + NF.SEASON + '</h4>'
      + '<span class="chip">' + NF.KEPT + ' episodes</span><span class="spacer"></span></div>'
      + '<hr class="dash" style="margin:12px 0;">'
      + '<div class="nf-order"><div class="nf-order-ctl">'
      + '<label style="font-size:.76rem;font-weight:600;letter-spacing:.02em;color:var(--ink-soft);text-transform:uppercase">'
      + NF.NEW.orderLabel.en + '</label>' + seg
      + '<span class="hint">How Ravilo lists this series’ episodes, and what <b>Play</b> starts.</span>'
      + '</div>' + prov + '</div>'
      /* The list below simply FOLLOWS the stored value — there is no third line restating it. An
         earlier revision carried one ("episode order in this list follows the setting: Newest
         first") and it put the same two words on the screen three times in one card. The list is
         the demonstration; the control is the control; the provenance line is the server's. */
      + '<hr class="dash" style="margin:14px 0 12px;">'
      + '<div>' + eps.slice(0, 4).map(epRow).join('') + '</div>'
      + '<div class="tiny muted" style="margin-top:10px">… and ' + (NF.KEPT - 4) + ' more</div>'
      + '</div></div>';
  }

  W.NFB = { tvSeries, tvHome, tvPlayer, phSeries, phHome, adminCard, appbar, G, esc };
})(window);
