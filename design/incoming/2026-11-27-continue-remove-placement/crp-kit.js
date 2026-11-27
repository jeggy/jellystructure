/* crp-kit.js — every frame this round renders, built from crp-data.js.
   Round slug `continue-remove-placement`, 2026-11-27.

   The builders below use the SHIPPED class names of each surface, because the shipped stylesheet
   is linked rather than copied (PROCESS.md step 3): `.appbar`, `.dhero`, `.dhero-body`,
   `.dactions`, `.btn` on TV; the adopted phone-downloads round's `.pd-*` on the phone;
   `.win`, `.hero`, `.hb`, `.btns`, `.bt` on desktop. So every token, radius, weight and font in
   these pictures is the production one, and a reviewer comparing a picture with the stylesheet is
   comparing it with the same bytes.

   Nothing here invents a string, a title, a duration or a width: all of them come from
   window.CRP in crp-data.js.
*/
(function () {
  'use strict';

  var D = window.CRP;
  var R = window.RAVILO;                   /* grad() / initials() / castFor() — the shipped corpus's
                                              own functions, so colours and initials match every
                                              other Ravilo mockup rather than drifting per round. */
  var LANG = 'en';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }
  function t(key, vars) {
    var s = (D.STR[LANG] && D.STR[LANG][key]);
    if (s == null) s = D.STR.en[key];
    if (s == null) return key;
    if (vars) Object.keys(vars).forEach(function (k) { s = s.split('{' + k + '}').join(vars[k]); });
    return s;
  }
  function setLang(l) { LANG = l; }

  /* ---- SVG ids are minted from a counter, never written by hand. A document holding several
     frames hides the ones for another width, and Chromium will not resolve a paint server defined
     inside a display:none subtree — every later reference to that id then paints nothing, in a
     VISIBLE frame, and it does not reproduce when that frame is opened alone (PROCESS.md). This
     round defines no gradient in SVG, but the counter exists so a later edit cannot reintroduce
     the trap. ------------------------------------------------------------------------------- */
  var svgSeq = 0;
  function svgId(stem) { return 'crp-' + stem + '-' + (++svgSeq); }
  window.CRPKitSvgId = svgId;

  /* =============================================================================================
     THE ACTION'S LABEL — one function, three states, every surface. This is the round's whole
     subject, so it is written once and read everywhere.

     `none` returns null, and every builder below renders NOTHING when it does. That is what makes
     the `none` frames a real control: the row in them is the shipped row, produced by the same
     code path, not a hand-drawn "before" picture.
  ============================================================================================= */
  function cwLabel(state) {
    if (state === 'remove') return t('action.continue_remove');
    if (state === 'restore') return t('action.continue_restore');
    return null;                                  /* `none` — spec FR-6, "neither: no action" */
  }

  /* =============================================================================================
     TV — Android TV, 1920x1080.
  ============================================================================================= */

  var TV_ICONS = { play: '▶', resume: '▶', watched: '○', trailer: '▷', list: '＋' };

  function tvAppbar() {
    return '<div class="appbar">'
      + '<div class="brand"><span class="wm">Ravilo</span></div>'
      + '<div class="topnav">'
      +   '<div class="navitem">' + esc(t('nav.home')) + '</div>'
      +   '<div class="navitem cur">' + esc(t('nav.library')) + '</div>'
      +   '<div class="navitem">' + esc(t('nav.discover')) + '</div>'
      +   '<div class="navitem">' + esc(t('nav.search')) + '</div>'
      + '</div>'
      + '<div class="right"><span class="clock">' + esc(D.CLOCK) + '</span>'
      +   '<span class="avatar">ER</span></div>'
      + '</div>';
  }

  /* The TV action row. `focus` is 'play' | 'action' | null — exactly one visible focus target,
     per the constitution, and the shipped `.focused` class is what carries it (focus is a class
     on this surface, not a pseudo-class: a D-pad has no hover).

     ORDER, and it is the brief's lean: the new action is LAST, after My List. Spec FR-6 — "It is
     never the first focus target. Play stays first on TV, and the action never moves Play or the
     buttons before it." Last is the only position in this row that keeps all three promises at
     once, so option A and option B draw the same TV row and the TV frames serve both. */
  function tvActions(o) {
    var lbl = cwLabel(o.state);
    var h = '<div class="dactions">';
    h += '<div class="btn primary' + (o.focus === 'play' ? ' focused' : '') + '">'
      +  '<span class="ic">' + TV_ICONS.play + '</span> ' + esc(o.playLabel) + '</div>';
    if (!o.series) {
      h += '<div class="btn ghost"><span class="ic">' + TV_ICONS.watched + '</span> '
        +  esc(t('action.mark_watched')) + '</div>';
    }
    h += '<div class="btn ghost"><span class="ic">' + TV_ICONS.trailer + '</span> '
      +  esc(t('action.trailer')) + '</div>';
    h += '<div class="btn ghost"><span class="ic">' + TV_ICONS.list + '</span> '
      +  esc(t('action.my_list')) + '</div>';
    if (o.opt === 'c') {
      /* Option C on TV: the row's last slot is an overflow, and the action moves into a panel.
         Drawn so the cost is visible, not argued: one more focus stop that says nothing, and an
         overlay the detail page does not have. */
      h += '<div class="btn ghost' + (o.focus === 'action' ? ' focused' : '') + '">'
        +  '<span class="ic">⋯</span></div>';
    } else if (lbl) {
      h += '<div class="btn ghost' + (o.focus === 'action' ? ' focused' : '') + '">'
        +  esc(lbl) + '</div>';
    }
    h += '</div>';
    return h;
  }

  function tvCastRow() {
    var cast = (R.castFor({ title: D.FILM.title }) || []).slice(0, 7);
    var cells = cast.map(function (c) {
      return '<div class="cast"><div class="cast-av" style="background:' + R.grad(c.n) + '">'
        + '<span class="cast-in">' + esc(R.initials(c.n)) + '</span></div>'
        + '<div class="cast-n">' + esc(c.n) + '</div>'
        + '<div class="cast-r">' + esc(c.r) + '</div></div>';
    }).join('');
    return '<div class="crow"><div class="crow-head"><h2>' + esc(t('sec.cast')) + '</h2></div>'
      + '<div class="track">' + cells + '</div></div>';
  }

  function tvEpisodesHead() {
    var s = D.SERIES;
    var pct = Math.round(s.watched / s.epsInSeason * 100);
    var pills = '';
    for (var i = 1; i <= s.seasons; i++) {
      pills += '<div class="spill' + (i === s.season ? ' cur' : '') + '">Season ' + i + '</div>';
    }
    return '<div class="dsec"><div class="dsec-head"><h2>' + esc(t('sec.episodes')) + '</h2>'
      + '<span class="dsec-sub">' + esc(t('watched_of')) + '</span>'
      + '<span class="seasonbar"><i style="width:' + pct + '%"></i></span></div>'
      + '<div class="seasonpills">' + pills + '</div></div>';
  }

  /* Option C's TV panel. A sheet is a phone shape; on a TV the family's own overlay is a centred
     panel, and the constitution's "Back is always meaningful … must never strand focus" is why it
     has to be drawn at all rather than assumed. */
  /* Option C's TV panel, drawn at its BEST. The first render put it centred, where it covered the
     action row it had just been opened from AND the ⋯ that opened it — PROCESS.md's round-8
     defect ("a sheet covering the very button its own sentence told the reader to press"), and
     unfair to the option besides: an option has to be judged in the shape its designer would
     ship. It is anchored above the row instead, right-aligned to the gutter, so the row, the ⋯
     and the title all stay readable behind the scrim.

     The geometry: .appbar is 92, .dhero is 620, .dhero-body sits 44 from the hero's bottom, so
     the row's LOWER edge is at 92 + 620 - 44 = 668 and, .btn being 60 tall, its UPPER edge is at
     608. The panel's floor has to clear 608, not 668 — the second render used the lower edge and
     the panel still cut across the top of the row it was meant to leave alone, which is the same
     defect one step smaller. 16 px of air above 608 is 592, and 1080 - 592 = 488. Every one of
     those numbers is from ravilo.css. */
  function tvPanel(state) {
    var lbl = cwLabel(state);
    return '<div class="crp-scrim"></div>'
      + '<div style="position:absolute;z-index:72;right:64px;bottom:488px;width:760px;'
      + 'padding:30px 34px 34px;border-radius:20px;background:var(--card);'
      + 'border:1px solid var(--line);box-shadow:0 40px 110px rgba(0,0,0,.6)">'
      + '<div style="font-family:\'Space Grotesk\',sans-serif;font-size:26px;font-weight:600;'
      + 'margin-bottom:20px;color:var(--ink-soft)">' + esc(t('sheet.title')) + '</div>'
      + '<div class="btn ghost focused" style="justify-content:center">' + esc(lbl) + '</div>'
      + '</div>';
  }

  function tvFrame(o) {
    var film = D.FILM, ser = D.SERIES, isSer = !!o.series;
    var item = isSer ? ser : film;
    var playLabel = isSer
      ? t('action.resume') + ' · S0' + ser.season + 'E0' + ser.ep
      : t('action.resume') + ' · ' + t('fd.min_left', { n: film.minLeft });
    var meta = isSer
      ? '<span class="tag">HD</span><span>' + ser.year + '</span><span class="rt">' + esc(ser.rating)
        + '</span><span>' + esc(t('meta.seasons', { n: ser.seasons })) + '</span>'
      : '<span class="tag">HD</span><span>' + film.year + '</span><span class="rt">' + esc(film.rating)
        + '</span><span>' + esc(t('meta.runtime',
            { h: Math.floor(film.runtimeMin / 60), m: film.runtimeMin % 60 })) + '</span>';
    var syn = isSer ? t('series.syn') : t('film.syn');

    var el = document.createElement('div');
    el.className = 'crp-tv crp-frame';
    el.id = o.id;
    el.innerHTML = tvAppbar()
      + '<div class="crp-tvscroll"><div class="detail">'
      +   '<div class="dhero">'
      +     '<div class="hero-bg"><div class="crp-art" style="background:' + R.grad(item.title) + '"></div>'
      +       '<div class="hero-scrim"></div></div>'
      +     '<div class="dhero-body">'
      +       '<div class="hero-kicker"><span>' + esc(isSer ? t('kicker.series') : t('kicker.film'))
      +         '</span><span class="n">' + item.year + '</span></div>'
      +       '<div class="dhero-title">' + esc(item.title) + '</div>'
      +       '<div class="hero-meta">' + meta + '</div>'
      +       (isSer ? '<div class="dnext-row"><div class="dnext"><span class="dnext-dot"></span>'
      +         esc(t('ep.upnext')) + '</div></div>' : '')
      +       '<div class="dsyn-block"><div class="hero-syn dsyn">' + esc(syn) + '</div>'
      +         '<span class="syn-toggle">▾ ' + esc(t('more')) + '</span></div>'
      +       tvActions({ state: o.state, focus: o.focus, series: isSer, opt: o.opt, playLabel: playLabel })
      +     '</div>'
      +   '</div>'
      +   (isSer ? tvEpisodesHead() : tvCastRow())
      + '</div></div>'
      + (o.panel ? tvPanel(o.state) : '');
    return el;
  }

  /* =============================================================================================
     PHONE — 412x915, the adopted phone-downloads round's box and row.
  ============================================================================================= */

  function phSb() {
    return '<div class="pd-sb"><span>' + esc(D.CLOCK) + '</span>'
      + '<span class="pd-ic">LTE ▮▮▮ 86%</span></div>';
  }

  /* The shipped row as the owner amended it: Play flexible, Download a fixed-width pill, My List
     a 44 dp icon. `extra` is whatever this option appends INSIDE the row; `under` is whatever it
     puts on a line of its own beneath. */
  function phRow(o) {
    var dl = '<button class="pd-dl"><span class="pd-lbl">↓ ' + esc(t('download.action'))
      + '</span></button>';
    return '<div class="pd-dbtns a' + (o.four ? ' crp-four' : '') + '">'
      /* The label is wrapped so it can ellipsize. The shipped .pd-btn is a centred flex box with
         no overflow rule, and the first render of option A let Play's words spill OUTSIDE the
         white pill — arresting, but not an honest prediction: Compose's Row would clip the text,
         not paint it over the button's edge. It ellipsizes here for the same reason. The measured
         overflow is 205 px either way, and the report gives that number rather than the picture's
         drama. */
      + '<button class="pd-btn play"><span class="crp-plbl">▶ ' + esc(o.playLabel)
      + '</span></button>'
      + dl
      + '<button class="pd-mylist-ico" aria-label="' + esc(t('action.my_list')) + '">＋</button>'
      + (o.extra || '')
      + '</div>'
      + (o.under || '');
  }

  function phActions(o) {
    var lbl = cwLabel(o.state);
    if (o.opt === 'a') {
      /* Option A: a fourth item in the row. Nothing is widened to make it fit. */
      var extra = lbl
        ? '<button class="crp-cw"><span>' + esc(lbl) + '</span></button>'
        : '';
      return phRow({ playLabel: o.playLabel, extra: extra, four: !!lbl });
    }
    if (o.opt === 'c') {
      /* Option C: a 44 dp overflow in the row's last slot. It is drawn even in the `none` state,
         because a row whose last slot appears and disappears with the playstate is a different
         (and worse) design than one whose overflow is always there — and the picture is the only
         way to see which one is being proposed. */
      return phRow({ playLabel: o.playLabel,
        extra: '<button class="crp-of" aria-label="' + esc(t('overflow.aria')) + '">⋯</button>' });
    }
    /* Option B: the row untouched, the action on a line of its own beneath it. */
    var under = lbl
      ? '<button class="crp-cwfull"><span class="crp-lbl">' + esc(lbl) + '</span></button>'
      : '';
    return phRow({ playLabel: o.playLabel, under: under });
  }

  function phSheet(state) {
    var lbl = cwLabel(state);
    return '<div class="crp-scrim"></div>'
      + '<div class="crp-sheet"><div class="crp-grab"></div>'
      + '<div class="crp-sh">' + esc(t('sheet.title')) + '</div>'
      + '<div class="crp-srow"><span class="crp-sico">○</span>'
      +   esc(t('action.mark_watched')) + '</div>'
      + '<div class="crp-srow"><span class="crp-sico">▷</span>'
      +   esc(t('action.trailer')) + '</div>'
      + '<div class="crp-srow"><span class="crp-sico">−</span>' + esc(lbl) + '</div>'
      + '</div>';
  }

  function phFrame(o) {
    var film = D.FILM, ser = D.SERIES, isSer = !!o.series;
    var item = isSer ? ser : film;
    var playLabel = isSer
      ? t('action.resume') + ' · S0' + ser.season + 'E0' + ser.ep
      : t('action.resume');
    var meta = isSer
      ? '<span>' + ser.year + '</span><span class="pg">' + esc(ser.rating) + '</span><span>'
        + esc(t('meta.seasons', { n: ser.seasons })) + '</span>'
      : '<span>' + film.year + '</span><span class="pg">' + esc(film.rating) + '</span><span>'
        + esc(t('meta.runtime', { h: Math.floor(film.runtimeMin / 60), m: film.runtimeMin % 60 }))
        + '</span>';

    var el = document.createElement('div');
    el.className = 'pd-ph art crp-frame';
    el.id = o.id;
    el.innerHTML = phSb()
      + '<div class="pd-detail">'
      +   '<div class="pd-dhero" style="background:' + R.grad(item.title) + '">'
      +     '<div class="pd-scrim"></div></div>'
      +   '<button class="pd-back2">←</button>'
      +   '<div class="pd-dbody">'
      +     '<div class="pd-dtitle">' + esc(item.title) + '</div>'
      +     '<div class="pd-dmeta">' + meta + '</div>'
      +     phActions({ opt: o.opt, state: o.state, playLabel: playLabel })
      +     '<div class="pd-dsyn">' + esc(isSer ? t('series.syn') : t('film.syn')) + '</div>'
      +   '</div>'
      + '</div>'
      + (o.sheet ? phSheet(o.state) : '');
    return el;
  }

  /* =============================================================================================
     DESKTOP / RAVILO WEB — the shipped window, 1440 wide, detail page.
  ============================================================================================= */

  var DK_ICON = {
    play: '<svg class="ic" viewBox="0 0 24 24"><path d="M8 5l11 7-11 7z"/></svg>',
    list: '<svg class="ic" viewBox="0 0 24 24"><path d="M6 4h12v16l-6-4-6 4z"/></svg>'
  };

  function dkActions(o) {
    var lbl = cwLabel(o.state);
    var row = '<div class="btns' + (o.opt === 'a' ? ' crp-arow' : '') + '">'
      + '<button class="bt pri">' + DK_ICON.play + esc(o.playLabel) + '</button>'
      + '<button class="bt">' + DK_ICON.list + esc(t('action.my_list')) + '</button>';
    if (o.opt === 'c') {
      row += '<button class="bt crp-ofd" aria-label="' + esc(t('overflow.aria')) + '">⋯</button>';
    } else if (o.opt === 'a' && lbl) {
      row += '<button class="bt"><span class="crp-dlbl">' + esc(lbl) + '</span></button>';
    }
    row += '</div>';
    if (o.opt === 'b' && lbl) {
      row += '<button class="crp-cwdesk">' + esc(lbl) + '</button>';
    }
    return row;
  }

  /* The "More like this" row under the desktop hero. The titles are the shipped corpus's, read
     out of RAVILO.relatedFor() so this round invents no name beyond the brief's two. */
  function dkPosters() {
    var rel = (R.relatedFor({ title: D.FILM.title }) || []).slice(0, 8);
    return rel.map(function (it) {
      return '<div class="po"><div style="aspect-ratio:2/3;border-radius:10px;background:'
        + R.grad(it.title) + ';display:flex;align-items:center;justify-content:center;'
        + 'font-family:\'Space Grotesk\',sans-serif;font-weight:700;font-size:24px;'
        + 'color:rgba(255,255,255,.82)">' + esc(R.initials(it.title)) + '</div></div>';
    }).join('');
  }

  function dkFrame(o) {
    var film = D.FILM, ser = D.SERIES, isSer = !!o.series;
    var item = isSer ? ser : film;
    var playLabel = isSer
      ? t('action.resume') + ' · S0' + ser.season + 'E0' + ser.ep
      : t('action.resume') + ' · ' + t('fd.min_left', { n: film.minLeft });
    var meta = isSer
      ? '<span>' + ser.year + '</span><span>' + esc(t('meta.seasons', { n: ser.seasons }))
        + '</span><span class="tg">' + esc(ser.genres[0]) + '</span>'
      : '<span>' + film.year + '</span><span>'
        + esc(t('meta.runtime', { h: Math.floor(film.runtimeMin / 60), m: film.runtimeMin % 60 }))
        + '</span><span class="tg">' + esc(film.genres[0]) + '</span>';

    var menu = o.menu
      ? '<div class="crp-menu">'
        + '<div class="crp-mrow">' + esc(t('action.mark_watched')) + '</div>'
        + '<div class="crp-mrow on">' + esc(cwLabel(o.state)) + '</div>'
        + '</div>'
      : '';

    var el = document.createElement('div');
    el.className = 'crp-desk crp-frame';
    el.id = o.id;
    el.innerHTML = '<div class="win gn noside dx-win dx-fd' + (o.light ? ' lt' : '') + '">'
      + '<div class="main"><div class="dx-fd">'
      +   '<div class="hero" style="background:' + R.grad(item.title) + '">'
      +     '<div class="hb">'
      +       '<div class="kick">' + esc(isSer ? t('kicker.series') : t('kicker.film')) + '</div>'
      +       '<div class="ttl">' + esc(item.title) + '</div>'
      +       '<div class="meta">' + meta + '</div>'
      +       '<p class="syn">' + esc(isSer ? t('series.syn') : t('film.syn')) + '</p>'
      +       dkActions({ opt: o.opt, state: o.state, playLabel: playLabel })
      +       menu
      +     '</div>'
      +   '</div>'
      /* A section below the hero, so the frame is a page rather than a hero floating in an empty
         window. The shipped `.pad` / `.h1` / `.row` / `.po` are used as they are; the posters are
         the corpus's own deterministic gradients and initials, never a picture of anything real. */
      +   '<div class="pad"><div class="h1">' + esc(t('sec.more')) + '</div>'
      +     '<div class="row">' + dkPosters() + '</div></div>'
      + '</div></div></div>';
    return el;
  }

  /* =============================================================================================
     THE MEASUREMENT STUDY. Every bar below is MEASURED in the browser at the surface's own font
     and size, not estimated: the label is laid out in a hidden span with the same font shorthand
     the control uses, its width is read with getBoundingClientRect(), the control's horizontal
     padding and border are added, and the result is compared with the box the control has to fit
     inside (GEO in crp-data.js). A number in REPORT.md can then be checked against a picture.
  ============================================================================================= */
  var FONT = {
    tv:    '600 21px Sora, sans-serif',     /* .btn */
    phone: '600 15px Sora, sans-serif',     /* .pd-btn / .pd-dl / .crp-cwfull */
    desk:  '600 13.5px Sora, sans-serif'    /* .bt / .crp-cwdesk */
  };

  function measure(text, font) {
    var s = document.createElement('span');
    s.style.cssText = 'position:absolute;left:-9999px;top:0;white-space:nowrap;font:' + font;
    s.textContent = text;
    document.body.appendChild(s);
    var w = s.getBoundingClientRect().width;
    s.parentNode.removeChild(s);
    return w;
  }

  /* One control's needed width: its own text at its own font, plus everything the stylesheet puts
     around that text. A `fixed` control (the 44 dp icon-only My List) skips the measurement. */
  function partWidth(p) {
    if (p.fixed) return { w: p.fixed, label: p.label || '' };
    var text = t(p.key) + (p.suffixKey ? ' · ' + t(p.suffixKey, p.suffixVars) : '');
    return { w: Math.round(measure(text, FONT[p.font]) + p.extra), label: text };
  }

  function studyFrame(id) {
    var el = document.createElement('div');
    el.className = 'crp-study crp-frame';
    el.id = id;
    var html = '<h3>Does the <u>row</u> still fit? Measured, at each surface’s own type.</h3>'
      + '<p>The question is not whether the new label fits — on its own it fits everywhere. It '
      + 'is whether the action row still fits once the label is in it. Each bar below is a whole '
      + 'row: every control’s text laid out in Sora at that surface’s size and weight, plus '
      + 'that control’s own padding, border and icon from the stylesheet, plus the row’s '
      + 'gaps. The box it sits in is the row’s real width — 1792&nbsp;px on TV (1920 less the '
      + '64&nbsp;px gutter either side), 372&nbsp;px on the phone (412 less the body’s 20 either '
      + 'side) and 480&nbsp;px on desktop and Ravilo Web (<code>.hero .hb</code>’s '
      + '<code>max-width</code>, which does <u>not</u> grow with the window). A red bar does not '
      + 'fit, and the segment that runs past the edge is the control that loses its label.</p>';

    D.STUDY.forEach(function (row) {
      var parts = row.parts.map(partWidth);
      var gaps = row.gap * (parts.length - 1);
      var need = parts.reduce(function (a, p) { return a + p.w; }, 0) + gaps;
      var over = need > row.limit;
      var scale = Math.min(1, 1060 / row.limit);
      var segs = parts.map(function (p, i) {
        return '<span class="crp-seg' + (i % 2 ? ' alt' : '') + '" style="width:'
          + (p.w * scale) + 'px">' + esc(p.label) + '</span>';
      }).join(row.gap ? '<span class="crp-gap" style="width:' + (row.gap * scale) + 'px"></span>' : '');
      html += '<div class="crp-srowm">'
        /* The number lives on the key line, not beside the bar. In the first render it sat at the
           box's right edge, where a row that OVERFLOWS prints its last control's label straight
           over it — the two readings a reviewer most needs, on top of each other, in exactly the
           frames that matter. Only the picture showed it. */
        + '<div class="crp-skey">' + esc(row.surface) + ' · ' + esc(row.note)
        +   ' · <b>' + esc(LANG) + '</b> · <b>' + need + ' / ' + row.limit + ' px</b>'
        +   (over ? ' · <u>does not fit, by ' + (need - row.limit) + ' px</u>' : '') + '</div>'
        + '<div class="crp-box' + (over ? ' over' : '') + '" style="width:'
        +     Math.round(row.limit * scale) + 'px">'
        +   '<div class="crp-fill' + (over ? ' over' : '') + '">' + segs + '</div>'
        + '</div></div>';
    });
    el.innerHTML = html;
    return el;
  }

  window.CRPKit = {
    setLang: setLang, t: t, cwLabel: cwLabel,
    tv: tvFrame, phone: phFrame, desk: dkFrame, study: studyFrame
  };
})();
