/* cs-kit.js — the markup builders for the coming-soon-stacks round.

   ONE builder per thing, used by all three surfaces. The TV, the phone and the desktop frames
   call `card()` with the same group object and differ only in the wrapper class they sit in, so a
   difference between two surfaces in these pictures is a difference in CSS and never a second
   opinion about what a stack says. That mirrors the constitution's shared-first rule — one card
   component in common code — and it is also the cheapest way to keep 40 frames honest.

   Classes with no prefix are the SHIPPED Ravilo classes out of design/ravilo/ravilo.css and
   design/ravilo/mobile/ravilo-phone.css; everything this round invents is prefixed `cs-`.
*/
window.CSKit = (function (CS) {
  'use strict';

  const pad = n => String(n).padStart(2, '0');
  const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const fill = (s, vars) => String(s).replace(/\{(\w+)\}/g, (_, k) => vars[k]);

  let L = CS.I18N.en;
  const setLang = code => { L = CS.I18N[code] || CS.I18N.en; };
  const t = (k, vars) => vars ? fill(L[k], vars) : L[k];

  /* ---- dates. Weekday and month names are tabled per language in cs-data.js rather than taken
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
    if (k === n) return { kind: 'available' };
    if (k > 0) return { kind: 'mixed', k: k, n: n, dl: dl };
    if (dl) return { kind: 'downloading', pct: dl.pct };
    return { kind: 'none' };
  }

  /* DECISION 5 — which time a stack shows when its episodes differ.
     'earliest' (the lean) · 'range' · 'count' (needs the new {n} times string). */
  function timeLabel(g, rule) {
    if (g.kind === 'film') return t(g.release);
    /* A missing stack shows no air time. Its episodes were due on three different days, so one
       time is at best meaningless and at worst reads as "due at 04:00 today"; the "Was due" line
       under the art is the one that carries the date. */
    if (g.missing) return '';
    const times = g.eps.map(e => e.time).filter(Boolean);
    const uniq = times.filter((x, i) => times.indexOf(x) === i).sort();
    if (uniq.length <= 1) return uniq[0] || '';
    if (rule === 'range') return uniq[0] + '–' + uniq[uniq.length - 1];
    if (rule === 'count') return t('times_count', { n: uniq.length });
    return uniq[0];
  }

  function statusPill(st) {
    if (st.kind === 'available') return '<span class="up-stat avail-lib">✓ ' + esc(t('available')) + '</span>';
    if (st.kind === 'missing') return '<span class="up-stat missing">' + esc(t('missing')) + '</span>';
    if (st.kind === 'downloading') return '<span class="up-stat dl">' + st.pct + '%</span>';
    if (st.kind === 'mixed') {
      /* DECISION 6 — the badge on a mixed stack. The lean, drawn: "4 of 6 available".
         It is a --warn pill, not a green one: warn is our "dirty / mixed" semantic, and state is
         never expressed by accent colour alone, so the words carry it too. */
      return '<span class="up-stat cs-mixed">' + esc(t('available_of', { k: st.k, n: st.n })) + '</span>'
        + (st.dl ? '<span class="up-stat dl">' + st.dl.pct + '%</span>' : '');
    }
    return '';
  }

  /* The count badge. Deliberately NOT accent-coloured: the status pill already carries colour
     semantics, and a second coloured chip on the same art would compete with it. Glass + ink
     reads in all three skins, including noir, which has no accent tint to lean on. */
  function countBadge(g) {
    if (!g.stacked) return '';
    return '<span class="cs-count">' + esc(t('episodes_count', { n: g.eps.length })) + '</span>';
  }

  /* ---- the card ----------------------------------------------------------------------------- */

  /* opts: dir 'a'|'b'|'c' · focused · timeRule · wide (B only) */
  function card(g, opts) {
    const o = Object.assign({ dir: 'a', focused: false, timeRule: 'earliest' }, opts || {});
    const s = CS.SERIES[g.key];
    const st = status(g);
    const stacked = g.stacked;
    const art =
      '<div class="up-art">'
      /* Every layer of a stack is the same art, or a tint of it — the calendar holds one picture
         per series and no episode stills, so an edge CANNOT be a second poster. Tinting the
         series' own art is both the only honest fill and the only one that reads at three
         metres: the first render used the card grey and the edges vanished into the page. */
      + (o.dir === 'a' && stacked
        ? '<span class="cs-edge e2" style="background-image:' + s.grad + '"></span>'
          + '<span class="cs-edge e1" style="background-image:' + s.grad + '"></span>'
        : '')
      + '<div class="cs-artface">'
      + '<div class="grad" style="background:' + s.grad + '"></div>'
      + '<span class="cs-initials">' + esc(s.initials) + '</span>'
      + '<div class="up-art-top">'
      + '<span class="up-src sonarr">' + esc(g.kind === 'film' ? t('movie') : t('episode')) + '</span>'
      + statusPill(st)
      + '</div>'
      /* The count sits in the BOTTOM corner, beside the air time. It started in the top row and
         the mixed stack proved that wrong: Episode + "4 of 6 available" + "62%" + "6 episodes" is
         four chips in 430 px and the status pill wrapped to two lines. Down here it also reads
         better as copy — the time and the count are one fact about the release. */
      + '<div class="up-art-bot"><span class="up-time">' + esc(timeLabel(g, o.timeRule)) + '</span>'
      + (o.dir !== 'b' ? countBadge(g) : '') + '</div>'
      + '</div></div>';

    const dueLine = g.missing
      ? '<div class="up-cdue"><span class="up-cdue-ic">!</span>'
        + esc(t('was_due', { date: dayShort(g.eps[0].date) })) + '</div>'
      : '';

    const body =
      '<div class="up-cbody">'
      + '<div class="up-ctitle">' + esc(s.title) + '</div>'
      /* A film has no episode line. It does NOT repeat its release label here either: that label
         is already the bottom-left of its art, where the brief puts it, and a card that says
         "In Cinemas" twice is a card that has run out of things to say. */
      + (g.kind === 'film' ? '' : '<div class="up-csub">' + esc(rangeLabel(g)) + '</div>')
      + '<div class="up-cnet">' + esc([s.network, s.year].filter(Boolean).join(' · ')) + '</div>'
      + dueLine
      + '</div>';

    const cls = ['up-card', 'cs-' + o.dir, stacked ? 'cs-stacked' : '', o.focused ? 'focused' : ''];

    /* B — the wide list card. The art and the body keep their shipped geometry; the episode list
       sits BESIDE them, so the card's height is unchanged and only its width grows. See
       REPORT.md: B cannot also honour "nothing may change the card's width", and this is the
       narrowing that keeps the row's height and rhythm rather than its slot count. */
    if (o.dir === 'b' && stacked) {
      return '<div class="' + cls.concat('cs-bcard').join(' ') + '">'
        + '<div class="cs-bmain">' + art + body + '</div>'
        + '<div class="cs-blist">' + bList(g) + '</div>'
        + '</div>';
    }
    return '<div class="' + cls.join(' ') + '">' + art + body + '</div>';
  }

  /* B's episode list. Episode codes are IDs, so they are set in JetBrains Mono, which is this
     system's code-and-ID face by mandate. */
  function bList(g) {
    const head = '<div class="cs-bhead">' + esc(t('episodes_count', { n: g.eps.length })) + '</div>';
    return head + '<div class="cs-brows">' + g.eps.map(e => {
      const mark = e.status === 'available' ? '<span class="cs-dot ok"></span>'
        : e.status === 'downloading' ? '<span class="cs-dot dl"></span>'
        : e.status === 'missing' ? '<span class="cs-dot bad"></span>'
        : '<span class="cs-dot"></span>';
      const right = e.status === 'downloading' ? e.pct + '%'
        : g.missing ? dayShort(e.date) : (e.time || '');
      return '<div class="cs-brow">' + mark
        + '<span class="cs-bcode">' + esc(epCode(e)) + '</span>'
        + '<span class="cs-btime">' + esc(right) + '</span></div>';
    }).join('') + '</div>';
  }

  /* ---- the TV screen ------------------------------------------------------------------------ */

  /* opts: dir · filter · minStack · timeRule · countRule 'cards'|'episodes' · focusKey ·
           days · missing · offline · lang */
  function tvScreen(opts) {
    const o = Object.assign({
      dir: 'a', filter: 'all', minStack: 2, timeRule: 'earliest', countRule: 'cards',
      days: CS.DAYS, missing: false, offline: false, focusKey: null,
    }, opts || {});

    const dayGroups = o.days.map(d => ({
      date: d, groups: CS.groupsFor(d, { minStack: o.minStack, filter: o.filter }),
    })).filter(d => d.groups.length);

    const miss = o.filter === 'movies' ? [] : CS.missingGroups({ minStack: o.minStack });

    /* DECISION 4 — what the date strip counts. 'cards' (the lean) counts what the row shows;
       'episodes' counts the releases behind them. The two differ only where a stack exists, which
       is exactly why the choice matters. */
    const stripCount = d => o.countRule === 'episodes'
      ? d.groups.reduce((n, g) => n + Math.max(1, g.eps.length), 0)
      : d.groups.length;

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
    const rail = !dayGroups.length ? '' :
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

    const sections = dayGroups.map(d => {
      const rel = relDay(d.date);
      const n = d.groups.length;
      return '<div class="crow up-sec"><div class="crow-head up-sechead">'
        + '<div class="up-daybadge' + (d.date === CS.TODAY ? ' today' : '') + '">'
        + '<span class="dow">' + esc(dow(d.date)) + '</span><span class="dnum">' + dnum(d.date) + '</span></div>'
        + '<div class="up-sectitle"><h2>' + esc(rel || dowLong(d.date)) + '</h2>'
        + '<span class="up-secfull">' + esc(dowLong(d.date) + ', ' + dayShort(d.date)) + '</span></div>'
        + '<span class="up-seccount">' + esc(n === 1 ? t('release1') : t('releases', { n: n })) + '</span>'
        + '</div><div class="track focus-row">'
        + d.groups.map(g => card(g, {
          dir: o.dir, timeRule: o.timeRule,
          focused: o.focusKey === g.key + '@' + d.date,
        })).join('')
        + '</div></div>';
    }).join('');

    const missingRow = (o.missing && miss.length)
      ? '<div class="crow up-sec up-missing"><div class="crow-head up-sechead">'
        + '<div class="up-mbadge"><span class="dow">!</span></div>'
        + '<div class="up-sectitle"><h2>' + esc(t('missing_title')) + '</h2>'
        + '<span class="up-secfull">' + esc(t('missing_sub')) + '</span></div>'
        + '<span class="up-seccount">' + esc(t('missing_jump', { count: miss.reduce((n, g) => n + g.eps.length, 0) })) + '</span>'
        + '</div><div class="track focus-row">'
        + miss.map(g => card(g, { dir: o.dir, timeRule: o.timeRule,
          focused: o.focusKey === 'miss-' + g.key })).join('')
        + '</div></div>'
      : '';

    return appbar()
      + '<div class="screen"><div class="screen-scroll"><div class="upscreen">'
      + (o.offline ? offlineStrip() : '')
      + head + rail + sections + missingRow
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

  function offlineStrip() {
    return '<div class="cs-offline"><b>' + esc(t('offline_title')) + '</b>'
      + '<span>' + esc(t('offline_body')) + '</span></div>';
  }

  /* ---- DECISION 3 — what selecting a stack opens -------------------------------------------- */

  /* The not-held detail, with the stack's episodes listed in its schedule. `state` is
     'ready' | 'loading' | 'error' — a detail with nothing behind it yet is a state the client has
     to render, because it renders server-pushed state and cannot invent the schedule. */
  function detail(key, state) {
    const s = CS.SERIES[key];
    const g = CS.missingGroups && null;
    void g;
    const eps = CS.EPISODES.filter(e => e.key === key).sort((a, b) => a.s - b.s || a.e - b.e);
    let panel;
    if (state === 'loading') {
      panel = '<div class="cs-sched cs-loading"><div class="cs-shead">' + esc(t('schedule')) + '</div>'
        + '<div class="cs-skel"></div><div class="cs-skel"></div><div class="cs-skel"></div>'
        + '<div class="cs-loadlbl">' + esc(t('detail_loading')) + '</div></div>';
    } else if (state === 'error') {
      panel = '<div class="cs-sched cs-err"><div class="cs-shead">' + esc(t('schedule')) + '</div>'
        + '<div class="cs-errtitle">' + esc(t('detail_err_title')) + '</div>'
        + '<div class="cs-errbody">' + esc(t('detail_err_body')) + '</div>'
        + '<div class="cs-errbtn focused">' + esc(t('detail_err_ok')) + '</div></div>';
    } else {
      panel = '<div class="cs-sched"><div class="cs-shead">' + esc(t('schedule'))
        + '<span>' + esc(t('episodes_count', { n: eps.length })) + '</span></div>'
        + eps.map(e => '<div class="cs-srow">'
          + '<span class="cs-bcode">' + esc(epCode(e)) + '</span>'
          + '<span class="cs-sday">' + esc((relDay(e.date) || dowLong(e.date)) + ' · ' + dayShort(e.date)) + '</span>'
          + '<span class="cs-stime">' + esc(e.time) + '</span></div>').join('')
        + '<div class="cs-sfoot">' + esc(t('foot_upcoming')) + '</div></div>';
    }
    return appbar()
      + '<div class="screen"><div class="screen-scroll"><div class="ddt">'
      + '<div class="ddt-hero"><div class="ddt-bg">'
      + '<div class="grad" style="position:absolute;inset:0;background:' + s.grad + '"></div>'
      + '<div class="ddt-scrim"></div></div>'
      + '<div class="ddt-body">'
      + '<div class="ddt-kicker">' + esc(t('series')) + ' · ' + esc(t('coming')) + '</div>'
      + '<div class="ddt-title">' + esc(s.title) + '</div>'
      + '<div class="hero-meta"><span>' + s.year + '</span><span>' + esc(s.network) + '</span>'
      + (s.held ? '' : '<span class="cs-nothold">' + esc(t('not_held')) + '</span>') + '</div>'
      + '<div class="cs-dcols">' + panel + '</div>'
      + '</div></div><div class="screen-end"></div></div></div></div>';
  }

  /* ---- the phone ---------------------------------------------------------------------------- */

  /* The phone's Coming Soon is a LIST today (.t10row), not a card grid — see REPORT.md. So a
     stack on the phone is a stacked row: the poster carries the offset edges, the meta line
     carries the range, and the count sits where a list row's trailing value sits. */
  function phoneRow(g, o) {
    const s = CS.SERIES[g.key];
    const st = status(g);
    /* A film contributes no episode range, and its release label is already the time slot — so it
       does not say "Movie · In Cinemas", which is the same thing twice. */
    const meta = [g.kind === 'film' ? '' : rangeLabel(g), timeLabel(g, o.timeRule), s.network]
      .filter(Boolean).join(' · ');
    const pill = st.kind === 'available' ? '<span class="st avail"><span class="dot"></span>' + esc(t('available')) + '</span>'
      : st.kind === 'missing' ? '<span class="st none"><span class="dot"></span>'
        + esc(t('was_due', { date: dayShort(g.eps[0].date) })) + '</span>'
      : st.kind === 'downloading' ? '<span class="st fetch"><span class="dot"></span>' + st.pct + '%</span>'
      : st.kind === 'mixed' ? '<span class="st cs-pmixed"><span class="dot"></span>'
        + esc(t('available_of', { k: st.k, n: st.n })) + '</span>' : '';

    const poster = '<div class="cs-pstack' + (g.stacked && o.dir === 'a' ? ' on' : '') + '">'
      + (g.stacked && o.dir === 'a'
        ? '<span class="cs-pedge e2" style="background-image:' + s.grad + '"></span>'
          + '<span class="cs-pedge e1" style="background-image:' + s.grad + '"></span>' : '')
      + '<div class="t10p" style="background:' + s.grad + '">' + esc(s.initials) + '</div></div>';

    const count = g.stacked
      ? '<span class="cs-pcount">' + esc(t('episodes_count', { n: g.eps.length })) + '</span>' : '';

    const expanded = (o.dir === 'b' && g.stacked)
      ? '<div class="cs-plist">' + g.eps.map(e =>
        '<div class="cs-prow"><span class="cs-bcode">' + esc(epCode(e)) + '</span>'
        + '<span class="cs-ptime">' + esc(e.status === 'downloading' ? e.pct + '%'
          : (g.missing ? dayShort(e.date) : e.time)) + '</span></div>').join('') + '</div>'
      : '';

    return '<div class="t10row' + (g.stacked ? ' cs-prowstack' : '') + (expanded ? ' cs-pexpanded' : '')
      + (o.focusKey === g.key ? ' cs-kfocus' : '') + '">'
      + poster
      + '<div class="t10b"><div class="nm">' + esc(s.title) + '</div>'
      + '<div class="mt">' + esc(meta) + '</div>' + pill + expanded + '</div>'
      + count + '</div>';
  }

  function phoneScreen(opts) {
    const o = Object.assign({ dir: 'a', filter: 'all', minStack: 2, timeRule: 'earliest',
      days: CS.DAYS, missing: false, focusKey: null }, opts || {});
    const days = o.days.map(d => ({
      date: d, groups: CS.groupsFor(d, { minStack: o.minStack, filter: o.filter }),
    })).filter(d => d.groups.length);
    const miss = o.filter === 'movies' ? [] : CS.missingGroups({ minStack: o.minStack });

    let html = '<div class="cs-pseg"><span class="cur">' + esc(t('coming')) + '</span>'
      + '<span>' + esc(t('request')) + '</span></div>';
    html += '<div class="cs-pchips"><span class="cur">' + esc(t('all')) + '</span>'
      + '<span>' + esc(t('series')) + '</span><span>' + esc(t('movies')) + '</span></div>';
    days.forEach(d => {
      const rel = relDay(d.date);
      html += '<div class="row" style="margin-top:2px;"><h3 style="padding-bottom:4px;">'
        + esc(rel || (dowLong(d.date) + ', ' + dayShort(d.date)))
        + '<span class="more">' + d.groups.length + '</span></h3></div>'
        + '<div class="t10">' + d.groups.map(g => phoneRow(g, o)).join('') + '</div>';
    });
    if (o.missing && miss.length) {
      html += '<div class="row" style="margin-top:2px;"><h3 style="padding-bottom:4px;">'
        + esc(t('missing_title')) + '<span class="more">' + miss.length + '</span></h3></div>'
        + '<div class="t10">' + miss.map(g => phoneRow(g, o)).join('') + '</div>';
    }
    return html + '<div style="height:24px;"></div>';
  }

  /* ---- the desktop -------------------------------------------------------------------------- */

  /* The desktop's wide layout. The rail on the left is INDICATIVE, not the shipped desktop shell:
     the desktop mockup in this repository has no Coming Soon page at all (REPORT.md). What is
     under test here is the card and the row, at desktop scale and with keyboard focus. */
  function desktopScreen(opts) {
    const o = Object.assign({ dir: 'a', minStack: 2, timeRule: 'earliest', filter: 'all',
      days: CS.DAYS, focusKey: null }, opts || {});
    const days = o.days.map(d => ({
      date: d, groups: CS.groupsFor(d, { minStack: o.minStack, filter: o.filter }),
    })).filter(d => d.groups.length);

    const rail = '<div class="cs-drail">'
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
            dir: o.dir, timeRule: o.timeRule, focused: o.focusKey === g.key + '@' + d.date,
          })).join('') + '</div></div>';
      }).join('')
      + '</div>';

    return '<div class="cs-dshell">' + rail + body + '</div>';
  }

  return { setLang, t, card, tvScreen, phoneScreen, desktopScreen, detail,
    status, rangeLabel, timeLabel, epCode, dayShort };
})(window.CS);
