/* cs2-kit.js — the markup builders for round 11 rev 2 (`coming-soon-stack-shape`).

   ONE builder per thing, used by all three surfaces. The TV, the phone and the desktop frames
   call `card()` with the same group object and differ only in the wrapper class they sit in, so a
   difference between two surfaces in these pictures is a difference in CSS and never a second
   opinion about what a stack says. That mirrors the constitution's shared-first rule — one card
   component in common code, which the brief confirms is what the Compose app actually has — and
   it is also the cheapest way to keep 34 frames honest.

   The directions, by their `dir` code:

     a  Stacked edge        round 11's, reproduced unchanged, as the COMPARISON frame only.
     d  Season band         a solid band across the art's lower third: numeral + plural + time.
     e  Episode rail        one state-coloured segment per episode along the art's top edge.
     f  Focus reveal        compact at rest; the whole lockup inside the SAME footprint on focus.
     g  Tall season row     the number lives ABOVE the art in a header; the row grows 78 px.

   Classes with no prefix are the SHIPPED Ravilo classes out of design/ravilo/ravilo.css;
   everything this round invents is prefixed `cs-`.
*/
window.CS2Kit = (function (CS) {
  'use strict';

  const pad = n => String(n).padStart(2, '0');
  const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const fill = (s, vars) => String(s).replace(/\{(\w+)\}/g, (_, k) => vars[k]);

  let L = CS.I18N.en;
  const setLang = code => { L = CS.I18N[code] || CS.I18N.en; };
  const t = (k, vars) => vars ? fill(L[k], vars) : L[k];

  /* ---- dates. Weekday and month names are tabled per language in cs2-data.js rather than taken
     from toLocaleDateString: Chromium has no Faroese locale, and a round that silently fell back
     to English for `fo` would be reporting a translation it never drew. */
  const dObj = iso => new Date(iso + 'T00:00:00');
  const dow = iso => L.dow[dObj(iso).getDay()];
  const dowLong = iso => L.dowLong[dObj(iso).getDay()];
  const dnum = iso => pad(dObj(iso).getDate());
  const dayShort = iso => fill(L.dateFmt, { mon: L.mon, d: dObj(iso).getDate() });
  const relDay = iso => {
    const off = Math.round((dObj(iso) - dObj(CS.TODAY)) / 86400000);
    return off === 0 ? t('day_today') : off === 1 ? t('day_tomorrow') : null;
  };

  /* ---- what a group of episodes says about itself ------------------------------------------- */

  function epCode(e) { return 'S' + pad(e.s) + 'E' + pad(e.e); }

  /* The subtitle under the art. A single episode keeps the shipped "S35E08"; a stack collapses to
     a code range, which is the same register as the line it replaces and needs no new string. */
  function rangeLabel(g) {
    if (!g.eps.length) return '';
    const f = g.eps[0], l = g.eps[g.eps.length - 1];
    if (g.eps.length === 1) return epCode(f);
    return f.s === l.s ? epCode(f) + '–E' + pad(l.e) : epCode(f) + '–' + epCode(l);
  }

  function status(g) {
    if (g.missing) return { kind: 'missing' };
    const n = g.eps.length;
    if (!n) return { kind: 'none' };
    const k = g.eps.filter(e => e.status === 'available').length;
    const dl = g.eps.find(e => e.status === 'downloading');
    if (k === n) return { kind: 'available', k: k, n: n };
    if (k > 0) return { kind: 'mixed', k: k, n: n, dl: dl };
    if (dl) return { kind: 'downloading', pct: dl.pct, k: k, n: n };
    return { kind: 'none', k: k, n: n };
  }

  /* FIXED by the owner, 2026-10-22: a stack shows its EARLIEST air time. There is no longer a
     time rule to choose, so this function has no options — the two-episode stack at 21:00 and
     21:45 says 21:00 and that is the whole of it. */
  function timeLabel(g) {
    if (g.kind === 'film') return t(g.release);
    /* A missing stack shows no air time. Its episodes were due on three different days, so one
       time is at best meaningless and at worst reads as "due at 04:00 today"; the "Was due" line
       under the art is the one that carries the date. */
    if (g.missing) return '';
    const times = g.eps.map(e => e.time).filter(Boolean).sort();
    return times[0] || '';
  }

  function statusPill(st, opts) {
    const o = opts || {};
    /* On a direction whose own lockup already spells the mix out (D's band, G's header), the pill
       carries ONLY the download percent. Round 11's lesson that cost a render: four chips in
       430 px wrapped the pill to two lines, and a card that says "4 of 6 available" twice is a
       card that has run out of things to say. */
    if (o.mixInLockup && st.kind === 'mixed') {
      return st.dl ? '<span class="up-stat dl">' + st.dl.pct + '%</span>' : '';
    }
    if (st.kind === 'available') return '<span class="up-stat avail-lib">✓ ' + esc(t('available')) + '</span>';
    if (st.kind === 'missing') return '<span class="up-stat missing">' + esc(t('missing')) + '</span>';
    if (st.kind === 'downloading') return '<span class="up-stat dl">' + st.pct + '%</span>';
    if (st.kind === 'mixed') {
      /* --warn is this system's "dirty / mixed" semantic. The words carry the state too, because
         state is never expressed by accent colour alone — and in NOIR the accent IS amber, so a
         pill that leaned on colour would be indistinguishable from the skin's own accent. */
      return '<span class="up-stat cs-mixed">' + esc(t('available_of', { k: st.k, n: st.n })) + '</span>'
        + (st.dl ? '<span class="up-stat dl">' + st.dl.pct + '%</span>' : '');
    }
    return '';
  }

  /* The kind chip. It is drawn on NON-stacked cards only: on a stack every direction in this
     round already says "episodes" in larger type somewhere on the card, and "Episode" beside
     "8 episodes" is the same thing twice. Direction A is the exception — it is reproduced exactly
     as the owner already saw it, chip included.

     The shipped stylesheet paints this chip through a class named after a downloader. This round
     uses `.up-src.cs-kind` instead, identical in paint, so no source name appears anywhere in the
     round's files — not even as a CSS class. The brief asks for no source names and this is the
     cheapest way to mean it. */
  function kindChip(g, dir) {
    if (g.stacked && dir !== 'a') return '';
    return '<span class="up-src cs-kind">' + esc(g.kind === 'film' ? t('movie') : t('episode'))
      + '</span>';
  }

  /* ---- the number lockup, shared by D, F and G ----------------------------------------------
     The numeral is set on its own, in Space Grotesk at display size, because the numeral is the
     one thing that has to survive three metres. The plural noun beside it is a SEPARATE string
     (`up.episodes_word`), not a slice of "{n} episodes": splitting a formatted string on its
     number is a trick that works in these three languages and silently breaks in the fourth.
     See REPORT.md for the fallback when a language cannot take a bare plural. */
  const MARK_MAX = 12;   /* above this the rail collapses to one proportional bar — see below */

  function lockup(g, st, opts) {
    const o = opts || {};
    const second = st.kind === 'mixed'
      ? '<span class="cs-lmix">' + esc(t('available_of', { k: st.k, n: st.n })) + '</span>'
      : '';
    return '<span class="cs-lnum">' + g.eps.length + '</span>'
      + '<span class="cs-lcol"><span class="cs-lword">' + esc(t('episodes_word')) + '</span>'
      + second + '</span>'
      + (o.right || '');
  }

  /* What anchors the right end of D's band. Normally the earliest air time; on a MISSING stack
     there is no air time at all, and the first render left two thirds of the band empty — a bar
     that is anchored at both ends in every other state and hollow in this one reads as something
     that failed to load. The status pill moves down into the slot instead, which also clears the
     lone pill off the top of the artwork. The "Was due" date keeps its shipped place under the
     title: the pill is the state, the line is the date, and they are not the same fact. */
  function bandRight(g, st) {
    if (g.missing) return '<span class="cs-lright">' + statusPill(st) + '</span>';
    return '<span class="cs-ltime">' + esc(timeLabel(g)) + '</span>';
  }

  /* ---- the per-episode rail, used by E and G -------------------------------------------------
     One segment per episode, in episode order, each carrying its own state. Above MARK_MAX the
     segments would be thinner than the gaps between them and at three metres would read as a
     solid bar anyway, so past that the rail BECOMES one bar with k of n filled. That is the rule
     a spec has to carry, and the 24-episode frame is what it was measured on. */
  function rail(g, st) {
    const n = g.eps.length;
    if (n < 2) return '';
    if (n > MARK_MAX) {
      const pctDone = Math.round((st.k || 0) / n * 100);
      return '<div class="cs-rail cs-rail-bar" title="">'
        + '<span class="cs-railfill" style="width:' + pctDone + '%"></span></div>';
    }
    return '<div class="cs-rail">' + g.eps.map(e => {
      if (e.status === 'available') return '<span class="cs-seg ok"></span>';
      if (e.status === 'missing') return '<span class="cs-seg bad"></span>';
      if (e.status === 'downloading') {
        return '<span class="cs-seg dl"><span class="cs-segfill" style="width:'
          + e.pct + '%"></span></span>';
      }
      return '<span class="cs-seg"></span>';
    }).join('') + '</div>';
  }

  /* ---- the card ----------------------------------------------------------------------------- */

  /* opts: dir 'a'|'d'|'e'|'f'|'g' · focused */
  function card(g, opts) {
    const o = Object.assign({ dir: 'a', focused: false }, opts || {});
    const s = CS.SERIES[g.key];
    const st = status(g);
    const stacked = g.stacked;
    const dir = o.dir;
    const mixInLockup = stacked && (dir === 'd' || dir === 'g' || (dir === 'f' && o.focused));
    /* Only D and a focused F actually have a right-hand slot in their lockup, so only they can
       take the missing pill off the artwork. G's header is left-aligned in every state and has
       no such slot — its pill stays where the shipped card puts it. */
    const pillInLockup = stacked && (dir === 'd' || (dir === 'f' && o.focused));

    /* ---- A, the comparison direction. Round 11's offset edges, unchanged: every layer is the
       series' own art dimmed in two steps, because the calendar holds one picture per series and
       no episode stills, so an edge CANNOT be a second poster. */
    const edges = (dir === 'a' && stacked)
      ? '<span class="cs-edge e2" style="background-image:' + s.grad + '"></span>'
        + '<span class="cs-edge e1" style="background-image:' + s.grad + '"></span>'
      : '';

    /* ---- D, the season band. A solid surface so the numeral has flat backing whatever the art
       behind it is, and the air time rides its right end — which is also why D needs no
       `.up-art-bot` at all. The band is INSIDE `.cs-artface`, which clips: round 11 lost a render
       to a positive z-index on a child of an auto-z-index absolute box painting over the card in
       front, and `overflow: hidden` on the face is what makes that impossible here. */
    const band = (dir === 'd' && stacked)
      ? '<div class="cs-band">' + lockup(g, st, { right: bandRight(g, st) }) + '</div>'
      : '';

    /* ---- E, the rail along the art's top edge. */
    const topRail = (dir === 'e' && stacked) ? rail(g, st) : '';

    /* ---- F, the reveal. At rest the card is the shipped card plus a small count chip, so a row
       full of stacks stays calm; focused, the lockup and the rail arrive INSIDE the same
       430 × 242 footprint. Nothing moves and nothing grows — which is the point, and also the
       whole of what R367 removed. */
    const reveal = (dir === 'f' && stacked && o.focused)
      ? '<div class="cs-reveal">'
        + '<div class="cs-rtop">' + lockup(g, st, { right: bandRight(g, st) }) + '</div>'
        + rail(g, st)
        + '</div>'
      : '';

    /* ---- G, the header above the art. G is the only direction that covers no art at all, and
       the only one that costs the row height: see REPORT.md for the price. */
    const header = (dir === 'g' && stacked)
      ? '<div class="cs-ghead">' + lockup(g, st, { time: false }) + '</div>'
      : '';
    const gRail = (dir === 'g' && stacked) ? rail(g, st) : '';

    /* The bottom row of the art: the air time, and a count badge where the direction has no other
       place for the number. D and G have the lockup; F has it on focus and a small chip at rest.
       E keeps a count badge deliberately LARGE — the rail says "several", and E's answer to "how
       many" is this badge, which is exactly the thing the owner is being asked to judge. */
    const countBadge = !stacked ? ''
      : dir === 'e' ? '<span class="cs-count cs-count-lg">'
          + esc(t('episodes_count', { n: g.eps.length })) + '</span>'
      : dir === 'f' && !o.focused ? '<span class="cs-count">'
          + esc(t('episodes_count', { n: g.eps.length })) + '</span>'
      : dir === 'a' ? '<span class="cs-count">'
          + esc(t('episodes_count', { n: g.eps.length })) + '</span>'
      : '';
    const artBot = (dir === 'd' && stacked) || (dir === 'f' && stacked && o.focused) ? ''
      : '<div class="up-art-bot"><span class="up-time">' + esc(timeLabel(g)) + '</span>'
        + countBadge + '</div>';

    const art =
      '<div class="up-art">'
      + edges
      + '<div class="cs-artface">'
      + '<div class="grad" style="background:' + s.grad + '"></div>'
      + '<span class="cs-initials">' + esc(s.initials) + '</span>'
      + topRail + gRail
      /* A missing stack's pill has moved into D's band (and F's reveal), so it is not drawn up
         here as well — the same pill in two places on one card is the thing round 11 had to
         take out of a render twice. */
      + '<div class="up-art-top">' + kindChip(g, dir)
      + (pillInLockup && g.missing ? ''
        : statusPill(st, { mixInLockup: mixInLockup })) + '</div>'
      + artBot + band + reveal
      + '</div></div>';

    const dueLine = g.missing
      ? '<div class="up-cdue"><span class="up-cdue-ic">!</span>'
        + esc(t('was_due', { date: dayShort(g.eps[0].date) })) + '</div>'
      : '';

    const body =
      '<div class="up-cbody">'
      + '<div class="up-ctitle">' + esc(s.title) + '</div>'
      /* A film has no episode line, and it does NOT repeat its release label here: that label is
         already the bottom-left of its art. A card that says "In Cinemas" twice is a card that
         has run out of things to say. */
      + (g.kind === 'film' ? '' : '<div class="up-csub">' + esc(rangeLabel(g)) + '</div>')
      + '<div class="up-cnet">' + esc([s.network, s.year].filter(Boolean).join(' · ')) + '</div>'
      + dueLine
      + '</div>';

    const cls = ['up-card', 'cs-' + dir, stacked ? 'cs-stacked' : '', o.focused ? 'focused' : ''];

    /* G's header and art are wrapped so the focus ring can be ONE ring round the pair. Ringing
       the header and the art separately is what you get for free from a two-sibling card, and
       round 11 shipped exactly that defect into a render: a D-pad user reads two outlines with a
       gap down the middle as two targets, and a stack is one focus stop. */
    if (dir === 'g' && stacked) {
      return '<div class="' + cls.join(' ') + '">'
        + '<div class="cs-gwrap">' + header + art + '</div>' + body + '</div>';
    }
    return '<div class="' + cls.join(' ') + '">' + art + body + '</div>';
  }

  /* ---- the TV screen ------------------------------------------------------------------------ */

  /* opts: dir · filter · focusKey · days · missing · lang */
  function tvScreen(opts) {
    const o = Object.assign({
      dir: 'd', filter: 'all', days: CS.DAYS, missing: false, focusKey: null,
    }, opts || {});

    const dayGroups = o.days.map(d => ({
      date: d, groups: CS.groupsFor(d, { filter: o.filter }),
    })).filter(d => d.groups.length);

    const miss = o.filter === 'movies' ? [] : CS.missingGroups();

    /* FIXED by the owner, 2026-10-22: the date strip counts EPISODES, as it does today. An
       8-episode drop adds 8 to its day, so the strip's number is no longer the number of cards in
       the row — and every direction in this round makes the episode count visible on the card,
       which is what keeps the two from contradicting each other. */
    const stripCount = d => d.groups.reduce((n, g) => n + Math.max(1, g.eps.length), 0);

    const chip = (k, label) => '<div class="upchip' + (k === o.filter ? ' cur' : '')
      + (o.focusKey === 'chip-' + k ? ' focused' : '') + '">'
      + '<span class="upchip-dot ' + k + '"></span>' + esc(label) + '</div>';

    const head =
      '<div class="uphead">'
      + '<div class="uphead-row"><h1>' + esc(t('discover')) + '</h1>'
      + '<span class="disc-sub">' + esc(t('subtitle')) + '</span></div>'
      + '<div class="upfilter">'
      + '<div class="discseg"><div class="dseg cur">' + esc(t('coming')) + '</div>'
      + '<div class="dseg">' + esc(t('request')) + '</div></div>'
      + chip('all', t('all')) + chip('series', t('series')) + chip('movies', t('movies'))
      + (miss.length ? '<div class="up-missjump"><span class="up-missjump-ic">!</span>'
        + esc(t('missing')) + '<span class="up-missjump-n">'
        + miss.reduce((n, g) => n + g.eps.length, 0) + '</span></div>' : '')
      + '</div></div>';

    /* No rail when no day row is in frame: the Missing row is not a day and has no place on the
       date strip, so the frame that draws it on its own draws no strip either. */
    const strip = !dayGroups.length ? '' :
      '<div class="crow up-railwrap"><div class="crow-head"><h2 class="up-railh">'
      + esc(dayShort(dayGroups[0].date) + ' — ' + dayShort(dayGroups[dayGroups.length - 1].date))
      + '</h2></div><div class="track focus-row up-rail">'
      + dayGroups.map(d => {
        const rel = relDay(d.date);
        return '<div class="up-day' + (d.date === CS.TODAY ? ' today' : '')
          + (o.focusKey === 'day-' + d.date ? ' focused' : '') + '">'
          + '<span class="up-dow">' + esc(rel || dow(d.date)) + '</span>'
          + '<span class="up-dnum">' + dnum(d.date) + '</span>'
          + '<span class="up-dmon">' + esc(L.mon) + '</span>'
          + '<span class="up-dcount">' + stripCount(d) + '</span></div>';
      }).join('')
      + '</div></div>';

    /* NO day-header count line. The brief is explicit that the shipped app has none, and round 11
       found the shipped i18n set carries no key for one either — so the `{n} releases` line round
       11 drew is gone from this round entirely rather than restyled. */
    const sections = dayGroups.map(d => {
      const rel = relDay(d.date);
      return '<div class="crow up-sec">'
        + '<div class="crow-head up-sechead">'
        + '<div class="up-daybadge' + (d.date === CS.TODAY ? ' today' : '') + '">'
        + '<span class="dow">' + esc(dow(d.date)) + '</span><span class="dnum">' + dnum(d.date) + '</span></div>'
        + '<div class="up-sectitle"><h2>' + esc(rel || dowLong(d.date)) + '</h2>'
        + '<span class="up-secfull">' + esc(dowLong(d.date) + ', ' + dayShort(d.date)) + '</span></div>'
        + '</div><div class="track focus-row">'
        + d.groups.map(g => card(g, {
          dir: o.dir, focused: o.focusKey === g.key + '@' + d.date,
        })).join('')
        + '</div></div>';
    }).join('');

    const missingRow = (o.missing && miss.length)
      ? '<div class="crow up-sec up-missing">'
        + '<div class="crow-head up-sechead">'
        + '<div class="up-mbadge"><span class="dow">!</span></div>'
        + '<div class="up-sectitle"><h2>' + esc(t('missing_title')) + '</h2>'
        + '<span class="up-secfull">' + esc(t('missing_sub')) + '</span></div>'
        + '</div><div class="track focus-row">'
        + miss.map(g => card(g, { dir: o.dir, focused: o.focusKey === 'miss-' + g.key })).join('')
        + '</div></div>'
      : '';

    return appbar()
      + '<div class="screen"><div class="screen-scroll"><div class="upscreen">'
      + head + strip + sections + missingRow
      + '<div class="screen-end"></div></div></div></div>';
  }

  function appbar() {
    return '<div class="appbar">'
      + '<div class="brand"><span class="cs-mark"></span><span class="wm">Ravilo</span></div>'
      + '<div class="topnav">'
      + '<div class="navitem">' + esc(L.nav[0]) + '</div>'
      + '<div class="navitem cur">' + esc(t('discover')) + '</div>'
      + L.nav.slice(1).map(n => '<div class="navitem">' + esc(n) + '</div>').join('')
      + '</div>'
      + '<div class="right"><span class="clock">21:04</span>'
      + '<span class="avatar">H</span></div></div>';
  }

  /* ---- the phone, 412 dp -------------------------------------------------------------------
     The brief fixes this: the Compose app is ONE card component across TV, phone and desktop, so
     the phone draws the CARD and not the list row round 11 drew. (Round 11's finding stands as a
     fact about the MOCKUP — `ravilo-phone.js` renders Coming Soon as `.t10row` list rows — and it
     is the mockup that disagrees with the app, not this round.) The card here is therefore the
     same `card()` output at phone scale, in a horizontally scrolling row, which is what a card
     row is on a handset. */
  function phoneScreen(opts) {
    const o = Object.assign({ dir: 'd', filter: 'all', days: CS.DAYS, missing: false,
      focusKey: null }, opts || {});
    const days = o.days.map(d => ({
      date: d, groups: CS.groupsFor(d, { filter: o.filter }),
    })).filter(d => d.groups.length);
    const miss = o.filter === 'movies' ? [] : CS.missingGroups();

    let html = '<div class="cs-pseg"><span class="cur">' + esc(t('coming')) + '</span>'
      + '<span>' + esc(t('request')) + '</span></div>';
    html += '<div class="cs-pchips"><span class="cur">' + esc(t('all')) + '</span>'
      + '<span>' + esc(t('series')) + '</span><span>' + esc(t('movies')) + '</span></div>';
    days.forEach(d => {
      const rel = relDay(d.date);
      html += '<div class="cs-phead"><h3>' + esc(rel || (dowLong(d.date) + ', ' + dayShort(d.date)))
        + '</h3></div>'
        + '<div class="cs-ptrack">' + d.groups.map(g => card(g, {
          dir: o.dir, focused: o.focusKey === g.key + '@' + d.date,
        })).join('') + '</div>';
    });
    if (o.missing && miss.length) {
      html += '<div class="cs-phead"><h3>' + esc(t('missing_title')) + '</h3></div>'
        + '<div class="cs-ptrack">' + miss.map(g => card(g, { dir: o.dir })).join('') + '</div>';
    }
    return html + '<div style="height:20px;"></div>';
  }

  /* ---- the desktop -------------------------------------------------------------------------
     The rail on the left is INDICATIVE, not the shipped desktop shell: the desktop mockup in this
     repository has no Coming Soon page at all (round 11's finding 6, unchanged). What is under
     test here is the card and the row, at desktop scale and with keyboard focus. */
  function desktopScreen(opts) {
    const o = Object.assign({ dir: 'd', filter: 'all', days: CS.DAYS, focusKey: null }, opts || {});
    const days = o.days.map(d => ({
      date: d, groups: CS.groupsFor(d, { filter: o.filter }),
    })).filter(d => d.groups.length);

    const rail2 = '<div class="cs-drail">'
      + '<div class="cs-dbrand"><span class="cs-mark"></span>Ravilo</div>'
      + [L.nav[0], t('discover'), 'My List'].concat(L.nav.slice(1))
        .map((n, i) => '<div class="cs-dnav' + (i === 1 ? ' on' : '') + '">' + esc(n) + '</div>').join('')
      + '</div>';

    const body = '<div class="cs-dmain"><div class="cs-dhead">'
      + '<h1>' + esc(t('coming')) + '</h1>'
      + '<span class="cs-dsub">' + esc(t('subtitle')) + '</span></div>'
      + '<div class="cs-dchips"><span class="cur">' + esc(t('all')) + '</span>'
      + '<span>' + esc(t('series')) + '</span><span>' + esc(t('movies')) + '</span></div>'
      + days.map(d => {
        const rel = relDay(d.date);
        return '<div class="cs-dday"><div class="cs-ddayhead">'
          + '<h2>' + esc(rel || dowLong(d.date)) + '</h2>'
          + '<span>' + esc(dayShort(d.date)) + '</span></div>'
          + '<div class="cs-dgrid">' + d.groups.map(g => card(g, {
            dir: o.dir, focused: o.focusKey === g.key + '@' + d.date,
          })).join('') + '</div></div>';
      }).join('')
      + '</div>';

    return '<div class="cs-dshell">' + rail2 + body + '</div>';
  }

  return { setLang, t, card, tvScreen, phoneScreen, desktopScreen,
    status, rangeLabel, timeLabel, epCode, dayShort, MARK_MAX };
})(window.CS2);
