/* nf-build.js — the ADOPTED design for a newest-first series, 2026-10-29.

   Builds every frame from window.NF (nf-data.js) using the SHIPPED component classes. Where a
   class name appears here it is a class in design/ravilo/ravilo.css, ravilo-player.css,
   design/ravilo/mobile/ravilo-phone.css, or design/app/wf.css + app.css + detail.css, unless it
   starts `nf-`, which is this directory's own and is accounted for in newest-first.css. The one
   exception is the admin's `.eporder` family, which was PROMOTED INTO design/app/detail.css with
   the adoption and is therefore production CSS, not reference — it carries no `nf-` prefix for
   exactly that reason, and scripts/check-mobile-css.sh has a line per rule.

   Every option switch the round carried is gone: there is one direction here, A, as adopted. See
   nf-data.js's header for what was cut and why.

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
    /* THE MARK'S GLYPH, CHANGED ON ADOPTION (FR-12). The round drew a down arrow onto a baseline —
       "the top of this list is the top" — and the owner read it as DOWNLOAD, which is fair: an
       arrow pointing into a line under it is the download glyph on every platform the family ships
       on, and this pill sits on a page that has no download anywhere near it.
       What replaces it is the sort-descending glyph: three bars of DECREASING length, so the shape
       itself says "ordered", beside a down arrow with NO line under it, so nothing is being put
       anywhere. It is still a stroked path and still carried by shape rather than colour, which is
       what makes it survive the noir skin and a screen with the colour turned down.
       THE GLYPH'S CONTENT IS INSET FROM ITS OWN BOX on both sides, and that is load-bearing rather
       than tidiness: on the phone this svg is 14 px inside a text run with no flex gap of its own
       (`ravilo-phone.css` has no `.dnext` rule at all, so the mark there is ink on the page, not a
       pill), and a path drawn to x=23 of a 24 viewBox put the arrowhead hard against the N of
       *Newest*. Content spans 3→21; the round's arrow spanned 5→19 and read correctly for the same
       reason. */
    newest: '<svg class="nf-ar" viewBox="0 0 24 24" aria-hidden="true"><path d="M3 6h10M3 12h7M3 18h4"/><polyline points="15 14 18 18 21 14"/><line x1="18" y1="5" x2="18" y2="18"/></svg>',
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

  /* the primary button, from the Play RULE's answer — never a typed label (FR-10) */
  function primaryBtn(decision, L, focused) {
    const label = decision.kind === 'resume' ? NF.t('resume', L)
      : (NF.NEW.playNewest[L] || NF.NEW.playNewest.en);
    return '<span class="btn primary' + (focused ? ' focused' : '') + '">' + G.play
      + esc(label) + ' · ' + esc(NF.fmt(decision.iso, L)) + '</span>';
  }
  const restBtns = () => '<span class="btn ghost">' + G.trailer + 'Trailer</span>'
    + '<span class="btn ghost">' + G.plus + 'My List</span>';

  /* ============================================================ TV: an episode card in the rail.
     The shipped .ep-card / .ep-play / .ep-still / .ep-dur / .ep-check / .ep-prog / .ep-info, with
     the LABEL LADDER swapped (FR-9): the air date on .ep-t's rung, the programme's own heading on
     .ep-d's. The round's `-code` variant — the episode code as a third line — was declined. */
  function epCard(ep, prog, L, focused) {
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
    const ribbon = (isNewest && !p.watched && p.secs === 0 && anyOlderWatched)
      ? '<span class="ep-ribbon" aria-label="' + esc((NF.NEW.newOn[L] || NF.NEW.newOn.en).replace('{date}', NF.fmt(ep.iso, L)))
        + '">' + esc(NF.t('badgeNew', L)) + '</span>' : '';
    const label = NF.fmt(ep.iso, L), under = ep.title;
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
      + '</div></div></div>';
  }

  /* ================================================================== TV: the whole series page.
     `o` = { state, focus:'play'|null, focusEp, lang, skin }
     The page is today's page MINUS Shuffle, MINUS Start over and MINUS every watched count (FR-11),
     PLUS the hero mark and its state pill (FR-12), with the rail reversed and labelled by air date
     (FR-8, FR-9). It is expressed once here so the four state frames cannot drift from each other. */
  function tvSeries(o) {
    const L = o.lang || 'en';
    /* Every frame sits on one of the spec's four acceptance states (Acceptance 2–5). */
    const prog = NF.progressOf(o.state || 's1');
    const decision = NF.playA(prog);
    const eps = NF.EPISODES.slice().reverse();

    /* the hero's pill row: the series' MARK, then the viewer's STATE (FR-12) */
    const pills = '<div class="dnext-row">' + markPill(L) + statePill(o.state, L) + '</div>';

    /* the actions. No Shuffle (FR-3, FR-11) and no Start over: R343 drew Shuffle on any series with
       9+ episodes, and a marked series takes neither. */
    const actions = primaryBtn(decision, L, o.focus === 'play') + restBtns();

    /* the Episodes header: the season, and no count or bar (FR-11) */
    const head = '<div class="dsec-head"><h2>' + esc(NF.t('episodes', L)) + '</h2>'
      + '<span class="dsec-sub">' + esc(NF.t('season', L)) + ' ' + NF.SEASON + '</span></div>';

    const pillRow = '<div class="seasonpills"><span class="spill cur">'
      + esc(NF.t('season', L)) + ' ' + NF.SEASON + '</span></div>';

    const focusIso = o.focusEp || null;
    const rail = '<div class="eprow" style="display:flex;gap:24px">'
      + eps.map((e) => epCard(e, prog, L, e.iso === focusIso)).join('') + '</div>';

    return '<div class="nf-tv" data-skin="' + (o.skin || 'aurora') + '" lang="' + L + '">'
      + appbar('series', L)
      + '<div class="detail" style="position:absolute;inset:0">'
      + dhero({ lang: L, pills, actions })
      + '<div class="dsec">' + head + pillRow + rail + '</div>'
      + '</div></div>';
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
        + (t.pct > 0 ? '<span class="pbar"><i style="width:' + t.pct + '%"></i></span>' : '')
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
      sub: NF.RUNTIME_MIN + ' min', pct: 0,
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

    /* NOTHING FOLLOWS AN EPISODE (FR-13, owner decision 2). There is no `.pl-nextup` card and no
       `.nextup` class on the player, and there is no credits card of any kind either — no *Skip
       credits*, no *Watch credits*, no *End of episode*. The round drew option B's *Up next* card
       beside this frame and the owner declined it AND went further, so the only thing in the
       player at the end of a newest-first episode is the shipped chrome. The point of this picture
       is what is not in it; the chrome below is the shipped `.pl-*` set, unchanged. */
    return '<div class="nf-tv" data-skin="' + (o.skin || 'aurora') + '" lang="' + L + '" style="background:#000">'
      + '<div class="player" style="position:absolute;inset:0;opacity:1">'
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
      + '</div></div>';
  }

  /* =============================================================== PHONE: the series page (412).
     The shipped .detail / .dscroll / .dhero / .dbody / .dtitle / .dmeta / .dbtns / .btn.play /
     .seclbl / .melist / .merow-single / .methumb / .memeta2 — the phone's own vocabulary, not a
     scaled TV frame. Portrait, and a phone held upright has no player chrome of its own (R244). */
  function phSeries(o) {
    const L = o.lang || 'en';
    const prog = NF.progressOf(o.state || 's1');
    const decision = NF.playA(prog);
    const eps = NF.EPISODES.slice().reverse();

    const row = (ep) => {
      const p = prog[ep.iso];
      const pct = p.watched ? 100 : Math.round(p.secs / ep.secs * 100);
      const isNewest = ep.iso === NF.newestIso;
      /* same rule as the TV rail: "new" needs a place you got to. See epCard(). */
      const anyOlderWatched = NF.EPISODES.some((e) => e.iso < ep.iso && prog[e.iso].watched);
      const label = NF.fmt(ep.iso, L);
      return '<div class="merow-single">'
        + '<div class="methumb" style="background:' + ep.still + '">'
        + (p.watched ? '<span class="mwk">✓</span>' : '')
        + (isNewest && !p.watched && p.secs === 0 && anyOlderWatched
            ? '<span class="nf-phnew">' + esc(NF.t('badgeNew', L)) + '</span>' : '')
        + (p.secs > 0 && !p.watched
            ? '<span style="position:absolute;left:5px;right:5px;bottom:5px;height:3px;border-radius:3px;background:rgba(255,255,255,.3)">'
              + '<i style="display:block;height:100%;border-radius:3px;background:var(--accent);width:' + pct + '%"></i></span>' : '')
        + '</div>'
        + '<div class="memeta2"><div class="mt2 nf-airlab">' + esc(label) + '</div>'
        + '<div class="md2">' + esc(ep.title) + ' · ' + ep.mins + ' min</div></div></div>';
    };

    /* the Episodes header: the season, no watched count and no Shuffle (FR-11). */
    const sec = '<div class="seclbl mrow">' + esc(NF.t('episodes', L))
      + '<span style="font-weight:400;color:var(--ink-dim);font-family:Sora,sans-serif;font-size:12px;">'
      + esc(NF.t('season', L)) + ' ' + NF.SEASON
      + '</span></div>';

    const pill = '<div style="display:flex;gap:7px;flex-wrap:wrap;margin-top:12px">'
        + '<span class="dnext nf-mark" style="font-size:12.5px;padding:6px 11px;margin:0" role="note" aria-label="'
        + esc(NF.NEW.newestA11y[L] || NF.NEW.newestA11y.en) + '">'
        + G.newest.replace('class="nf-ar"', 'class="nf-ar" style="width:14px;height:14px"')
        + esc(NF.t('newest', L)) + '</span>'
        + (o.state === 's2' ? '<span class="dnext" style="font-size:12.5px;padding:6px 11px;margin:0">'
            + '<span class="dnext-dot" style="width:7px;height:7px"></span>' + esc(NF.t('newEpisode', L)) + '</span>' : '')
        + '</div>';

    const label = decision.kind === 'resume' ? NF.t('resume', L)
      : (NF.NEW.playNewest[L] || NF.NEW.playNewest.en);
    const sub = NF.fmt(decision.iso, L);

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
            + '<i style="display:block;height:100%;border-radius:3px;background:var(--accent);width:' + t.pct + '%"></i></span>' : '')
        + '</div>'
        + '<div class="tlabel">' + esc(t.title) + '</div><div class="tsub">' + esc(t.sub) + '</div></div>';
    };
    let dag = null;
    if (o.h === 'h1') dag = { title: 'Dagbók', h: NF.doy(NF.newestIso) * 31 % 360,
      badge: (NF.NEW.newOn[L] || NF.NEW.newOn.en).replace('{date}', d),
      sub: NF.RUNTIME_MIN + ' min', pct: 0 };
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
      + '</div>';
  }

  /* ========================================================================= ADMIN: the switch.
     FR-17. The shipped .card / .row / .seg / .hint / .chip / .badge / .ep / .ep-row — every one of
     them `wf.css` or `detail.css`. NO new control: the existing `.seg`, which is what the brief
     asked for and what the card's head already uses for the season picker.

     THE GEOMETRY IS PRODUCTION CSS NOW. The round drew it as `nf-order` in its own stylesheet and
     said where it would belong if adopted; on adoption the three rules moved into
     design/app/detail.css as `.eporder`, `.eporder .eporder-ctl` and `.eporder .eporder-prov`
     (plus the light theme's dashed rule and the label), hand-edited into the `.ep-*` family, with a
     line each in scripts/check-mobile-css.sh. That is why nothing in this function carries an
     `nf-` prefix any more and why the admin set links detail.css.

     The constitution excerpt rules the whole frame: the admin renders server-pushed state only, so
     the `.seg` shows WHAT THE SERVER STORED and the note is the server's sentence. No pending or
     optimistic position is drawn anywhere. */
  function adminCard(o) {
    const order = o.order;                                   /* 'oldest' | 'newest' */
    const eps = order === 'newest' ? NF.EPISODES.slice().reverse() : NF.EPISODES.slice();
    const seg = '<span class="seg">'
      + '<span' + (order === 'oldest' ? ' class="on"' : '') + '>' + NF.SHIPPED.oldest.en + '</span>'
      + '<span' + (order === 'newest' ? ' class="on"' : '') + '>' + NF.SHIPPED.newest.en + '</span>'
      + '</span>';
    /* THE NOTE, in FR-17's words and no others. The round drew a longer sentence on each value —
       the auto one explained that the fetcher had confirmed the series and added *You can change
       it.*, the hand-set one carried a timestamp — and the spec cut both to one sentence. The
       longer versions are in the round's history; nf-data.js self-checks these two against FR-17.

       The badge each line carried went with them: *Set by Auto-fetch…* already names the source in
       words, so a chip beside it reading `Auto-fetch` said the same thing twice a centimetre apart,
       which is the defect the round's own look-at-every-PNG pass found three times over.

       It is still drawn on BOTH values and not only on the one Auto-fetch chose: a line that
       appears only for the fetcher's value reads as that value being special rather than as one of
       two an operator may pick. FR-17's third state, `"off"`, has no note at all — it is the
       absence of this line, which is why there is no third frame of it. */
    const prov = '<div class="eporder-prov">'
      + esc(order === 'newest' ? NF.NEW.setByFetch.en : NF.NEW.setByHand.en) + '</div>';

    const epRow = (ep) => '<div class="ep"><div class="ep-row">'
      + '<div class="ep-thumb" style="border-radius:6px;background:' + ep.still + '"></div>'
      + '<div class="ep-meta"><div class="t"><span class="num mono">' + NF.se(ep.iso) + '</span> ' + esc(ep.title) + '</div>'
      + '<div class="s">' + ep.mins + ' min · <span class="ep-flags"><span class="badge ok">complete</span></span>'
      + ' · <span class="ep-added">aired ' + esc(NF.fmt(ep.iso, 'en')) + '</span></div></div>'
      + '<span class="ep-chev">\u203a</span></div></div>';

    return '<div class="nf-ad"' + (o.theme === 'light' ? ' data-theme="light"' : '') + '>'
      + '<div class="card">'
      /* No season picker in the head: this programme has ONE season, and a segmented control with
         a single segment is a control that cannot be operated. The shipped page draws the picker
         because its stand-in has two seasons. */
      + '<div class="row center"><h4 style="margin:0;white-space:nowrap;">' + esc(NF.t('season', 'en')) + ' ' + NF.SEASON + '</h4>'
      + '<span class="chip">' + NF.KEPT + ' episodes</span><span class="spacer"></span></div>'
      + '<hr class="dash" style="margin:12px 0;">'
      /* At the TOP of the episodes card, which is where FR-17 puts it. */
      /* The column is the shipped `.field`, not a new one: it already lays out label → control →
         hint, and `.hint` is styled by wf.css as `.field .hint` and NOWHERE ELSE. The round drew
         the span one level out, where the class exists but does not apply, so the sentence came
         out at body size and shouted over the control it explains — the round-9 failure mode, and
         the one defect the look-at-every-PNG pass caught in this promotion. */
      + '<div class="eporder"><div class="field">'
      + '<label>' + NF.NEW.orderLabel.en + '</label>' + seg
      + '<span class="hint">How Ravilo lists this series\u2019 episodes, and what <b>Play</b> starts.</span>'
      + '</div>' + prov + '</div>'
      /* The list below simply FOLLOWS the stored value — there is no third line restating it. An
         earlier revision carried one ("episode order in this list follows the setting: Newest
         first") and it put the same two words on the screen three times in one card. The list is
         the demonstration; the control is the control; the note is the server's. */
      + '<hr class="dash" style="margin:14px 0 12px;">'
      + '<div>' + eps.slice(0, 4).map(epRow).join('') + '</div>'
      + '<div class="tiny muted" style="margin-top:10px">\u2026 and ' + (NF.KEPT - 4) + ' more</div>'
      + '</div></div>';
  }

  W.NFB = { tvSeries, tvHome, tvPlayer, phSeries, phHome, adminCard, appbar, G, esc };
})(window);
