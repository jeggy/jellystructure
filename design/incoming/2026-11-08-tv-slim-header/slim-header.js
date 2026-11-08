/* Slim TV header — builds every frame from slim-header-data.js.
 *
 * Nothing in here holds a measurement. `dp()` and `sp()` are the only places a number becomes a
 * pixel, and they implement FR-2 literally: dp scales by s, text scales by t = 1 - (1 - s)/2.
 */
(function () {
  'use strict';

  var D = window.SLIM;
  var S = D.SHIPPED;

  /* ---- units ------------------------------------------------------------- */
  function ctx(scale) {
    var s = scale == null ? 1 : scale;
    var t = D.textFactor(s);
    function dpn(v) { return v * D.PX_PER_DP * s; }
    function spn(v) { return v * D.PX_PER_DP * t; }
    return {
      s: s, t: t, dpn: dpn, spn: spn,
      dp: function (v) { return dpn(v) + 'px'; },
      sp: function (v) { return spn(v) + 'px'; },
    };
  }

  /* ---- SVG, with every id minted from a counter -------------------------
   * PROCESS.md trap: a document holding many frames hides some of them, and Chromium will not
   * resolve a gradient defined inside a display:none subtree — so two frames sharing one id means
   * the later one paints nothing. Every mark below gets its own id.
   */
  var seq = 0;
  function brandMark(px) {
    var id = 'shgrad' + (++seq);
    return '<svg viewBox="12 20 76 76" width="' + px + '" height="' + px + '" aria-hidden="true">'
      + '<defs><linearGradient id="' + id + '" x1="0" y1="0" x2="1" y2="1">'
      + '<stop offset="0" stop-color="#AA5CC3"></stop><stop offset="1" stop-color="#00A4DC"></stop>'
      + '</linearGradient></defs>'
      + '<path d="M22 52 C22 24 78 24 78 52 C66 45 59 45 50 49 C41 45 34 45 22 52 Z" fill="url(#' + id + ')"></path>'
      + '<g stroke="url(#' + id + ')" stroke-width="4.5" stroke-linecap="round" fill="none">'
      + '<path d="M33 51 q-5 12 1 20 q5 8 0 14" opacity=".9"></path>'
      + '<path d="M44 52 q-4 13 1 21 q4 9 0 13" opacity=".72"></path>'
      + '<path d="M56 52 q4 13 -1 21 q-4 9 0 13" opacity=".72"></path>'
      + '<path d="M67 51 q5 12 -1 20 q-5 8 0 14" opacity=".9"></path></g></svg>';
  }

  /* AppBar.kt SearchIcon: a circle r7 at 11,11 and a 16.5,16.5 -> 21,21 handle in a 24 box. */
  function searchGlyph(px) {
    return '<svg viewBox="0 0 24 24" width="' + px + '" height="' + px + '" fill="none"'
      + ' stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">'
      + '<circle cx="11" cy="11" r="7"></circle><path d="M16.5 16.5 L21 21"></path></svg>';
  }

  /* Idea A's glyph. Deliberately NOT the two crossing arrows: `detail.shuffle` / `Blanda` already
   * owns that glyph on a series page, and "play these in a random order" is a different promise
   * from "open one title I have not seen". A sparkle reads as a silhouette at three metres. */
  function sparkleGlyph(px) {
    return '<svg viewBox="0 0 24 24" width="' + px + '" height="' + px + '" fill="currentColor" aria-hidden="true">'
      + '<path d="M9.6 2.4 L11.3 7.9 L16.8 9.6 L11.3 11.3 L9.6 16.8 L7.9 11.3 L2.4 9.6 L7.9 7.9 Z"></path>'
      + '<path d="M17.8 14.0 L18.7 16.8 L21.5 17.7 L18.7 18.6 L17.8 21.4 L16.9 18.6 L14.1 17.7 L16.9 16.8 Z"></path>'
      + '</svg>';
  }

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  function st(o) {
    var out = [];
    for (var k in o) if (o[k] != null && o[k] !== '') out.push(k + ':' + o[k]);
    return out.join(';');
  }

  /* ---- the app bar ------------------------------------------------------- */
  /* o = { c, b, solid, lang, activeNav, focus, surprise } where
   *   focus    — 'nav' | 'search' | 'avatar' | 'surprise' | null
   *   surprise — null | 'pill' | 'icon'   (Idea A, off by default) */
  function appBar(o) {
    var c = o.c, b = o.b, L = D.STR[o.lang || 'en'];
    var active = o.activeNav == null ? 0 : o.activeNav;

    var html = '<div class="sh-bar' + (o.solid ? ' solid' : '') + '" style="'
      + st({ height: c.dp(b.height), 'padding-left': c.dp(S.padH), 'padding-right': c.dp(S.padH),
             gap: c.dp(b.clusterGap) }) + '">';

    /* brand lockup */
    html += '<div class="sh-brand" style="' + st({ gap: c.dp(b.brandGap) }) + '">'
      + brandMark(c.dpn(b.mark))
      + '<span class="sh-wm" style="' + st({ 'font-size': c.sp(b.wordmark),
          'letter-spacing': c.sp(b.wordmarkTrack) }) + '">Ravilo</span></div>';

    /* nav pills */
    html += '<div class="sh-nav" style="' + st({ gap: c.dp(b.clusterGap) }) + '">';
    L.nav.forEach(function (label, i) {
      var cls = 'sh-navitem' + (i === active ? ' cur' : '')
        + (o.focus === 'nav' && i === active ? ' focused' : '');
      html += '<span class="' + cls + '" style="' + st({
        'font-size': c.sp(b.navSp),
        padding: c.dp(b.navPadV) + ' ' + c.dp(b.navPadH),
        'border-radius': c.dp(b.navRadius),
      }) + '">' + esc(label) + '</span>';
    });
    html += '</div>';

    /* right cluster — the outer row's own gap, as it ships */
    html += '<div class="sh-right" style="' + st({ gap: c.dp(b.clusterGap) }) + '">';

    if (o.surprise === 'pill') {
      var p = D.SURPRISE.pill;
      html += '<span class="sh-surprise' + (o.focus === 'surprise' ? ' focused' : '') + '" style="'
        + st({ 'font-size': c.sp(p.sp), gap: c.dp(p.glyphGap),
               padding: c.dp(p.padV) + ' ' + c.dp(p.padH),
               'border-radius': c.dp(p.radius), 'border-width': c.dp(p.border) }) + '">'
        + sparkleGlyph(c.dpn(p.glyph)) + esc(L.surprise) + '</span>';
    } else if (o.surprise === 'icon') {
      html += circle({ c: c, b: b, cls: 'sh-search', focused: o.focus === 'surprise',
                       inner: sparkleGlyph(c.dpn(b.glyph)) });
    }

    html += circle({ c: c, b: b, cls: 'sh-search', focused: o.focus === 'search',
                     inner: searchGlyph(c.dpn(b.glyph)) });

    html += '<span class="sh-clock" style="' + st({ 'font-size': c.sp(b.clock) }) + '">'
      + esc(D.CLOCK) + '</span>';

    html += circle({ c: c, b: b, cls: 'sh-av', focused: o.focus === 'avatar',
                     fontSize: c.sp(b.initials), inner: esc(D.VIEWER.initials) });

    html += '</div></div>';
    return html;
  }

  /* focusRing(): the ring is drawn OUTSIDE the circle, so the fill never clips it. `ringGap` holds
   * it that many dp clear of the fill, which is how RaviloButton's own ring already behaves and the
   * only way `--ring` reads against the `--accent` fill underneath it. */
  function circle(o) {
    var c = o.c, b = o.b;
    var gap = b.ringGap || 0;
    var ringCss = o.focused
      ? '<i style="' + st({ position: 'absolute', inset: '-' + (c.dpn(b.ring) + c.dpn(gap)) + 'px',
          'border-radius': '50%', 'border-style': 'solid', 'border-color': 'var(--ring)',
          'border-width': c.dp(b.ring) }) + '"></i>'
      : '';
    return '<span class="sh-circle ' + o.cls + (o.focused ? ' focused' : '') + '" style="'
      + st({ width: c.dp(b.circle), height: c.dp(b.circle), 'font-size': o.fontSize }) + '">'
      + o.inner + ringCss + '</span>';
  }

  /* ---- tiles ------------------------------------------------------------- */
  function poster(o) {
    var c = o.c;
    var w = S.poster.w * S.tileScale, h = S.poster.h * S.tileScale;
    return tile(c, o, w, h, null);
  }
  function landscape(o) {
    var c = o.c;
    var w = S.land.w * S.tileScale, h = S.land.h * S.tileScale;
    return tile(c, o, w, h, o.pct);
  }
  function tile(c, o, w, h, pct) {
    var foc = o.focused;
    var art = '<span class="sh-art" style="' + st({
      display: 'block', width: c.dp(w), height: c.dp(h),
      'border-radius': c.dp(S.tileRadius), background: D.grad(o.title),
      'outline-width': foc ? c.dp(S.tileRing) : null,
      'outline-offset': foc ? '0px' : null,
      'box-shadow': foc ? '0 ' + c.dp(11) + ' ' + c.dp(25) + ' rgba(0,0,0,.6)' : null,
    }) + '">'
      + '<span class="ini" style="' + st({ 'font-size': c.sp(26) }) + '">'
      + esc(D.initials(o.title)) + '</span>'
      + (pct != null && pct > 0
          ? '<span class="sh-pbar" style="' + st({ height: c.dp(4) }) + '"><i style="'
            + st({ width: pct + '%' }) + '"></i></span>'
          : '')
      + '</span>';

    return '<span class="sh-tile' + (foc ? ' focused' : '') + '" style="' + st({
      display: 'block', width: c.dp(w),
      transform: foc ? 'scale(' + S.tileFocusScale + ')' : null,
      'transform-origin': foc ? 'center center' : null,
    }) + '">' + art
      + '<span class="lab" style="' + st({ display: 'block', 'font-size': c.sp(S.tileLabelSp),
          'margin-top': c.dp(S.tileLabelGap) }) + '">' + esc(o.title) + '</span>'
      + (o.sub ? '<span class="sub" style="' + st({ display: 'block',
            'font-size': c.sp(S.tileSubSp) }) + '">' + esc(o.sub) + '</span>' : '')
      + '</span>';
  }

  /* ---- a content row ----------------------------------------------------- */
  /* o = { c, row, lang, quiet (Idea B), focusTile (index|null), seeAll } */
  function contentRow(o) {
    var c = o.c, row = o.row, L = D.STR[o.lang || 'en'];
    var title = row.key === 'continue' ? L.continueRow : row.title;

    var head = '<div class="sh-rowhead" style="' + st({
      'padding-left': c.dp(S.padH), 'padding-right': c.dp(S.padH),
      'margin-bottom': c.dp(S.rowHeadPadB) }) + '"><span class="lhs" style="'
      + st({ gap: o.quiet ? c.dp(D.QUIET_HEAD.dotGap) : '0' }) + '">';

    if (o.quiet) {
      head += '<span class="sh-rowtitle" style="' + st({ 'font-size': c.sp(D.QUIET_HEAD.titleSp),
          'letter-spacing': c.sp(S.rowTitleTrack) }) + '">' + esc(title) + '</span>';
      if (row.count != null) {
        head += '<span class="sh-rowcount" style="' + st({ 'font-size': c.sp(D.QUIET_HEAD.countSp) })
          + '">·</span><span class="sh-rowcount" style="'
          + st({ 'font-size': c.sp(D.QUIET_HEAD.countSp) }) + '">' + row.count + '</span>';
      }
    } else {
      head += '<span class="sh-rowtitle" style="' + st({ 'font-size': c.sp(S.rowTitleSp),
          'letter-spacing': c.sp(S.rowTitleTrack) }) + '">' + esc(title) + '</span>';
    }
    head += '</span>'
      + (o.seeAll ? '<span class="sh-seeall" style="' + st({ 'font-size': c.sp(S.seeAllSp) }) + '">'
          + esc(L.seeAll) + '</span>' : '')
      + '</div>';

    var items = '';
    if (row.kind === 'land') {
      D.CONTINUE.slice(0, S.landPerRow).forEach(function (it, i) {
        items += landscape({ c: c, title: it.title, sub: it.ep, pct: it.pct,
                             focused: o.focusTile === i });
      });
    } else {
      for (var i = 0; i < S.tilesPerRow; i++) {
        var t = D.TITLES[(i + (row.key === 'new' ? 2 : row.key === 'drama' ? 4 : 0)) % D.TITLES.length];
        items += poster({ c: c, title: t, focused: o.focusTile === i });
      }
    }

    var track = '<div class="sh-track" style="' + st({
      gap: c.dp(S.itemSpacing),
      'padding-top': c.dp(S.trackPadV), 'padding-bottom': c.dp(S.trackPadV),
      'padding-left': c.dp(S.padH), 'padding-right': c.dp(S.padH) }) + '">' + items + '</div>';

    return '<div class="sh-row" style="' + st({ 'margin-bottom': c.dp(S.rowGap) }) + '">'
      + head + track + '</div>';
  }

  /* ---- buttons ----------------------------------------------------------- */
  function button(o) {
    var c = o.c;
    var ring = o.focused
      ? '<i style="' + st({ position: 'absolute',
          inset: '-' + (c.dpn(S.btnRingOutset) + c.dpn(S.btnRing)) + 'px',
          'border-radius': c.dp(S.btnRadius + S.btnRingOutset), 'border-style': 'solid',
          'border-color': 'var(--ring)', 'border-width': c.dp(S.btnRing) }) + '"></i>'
      : '';
    var cls = 'sh-btn ' + (o.style === 'pri' ? 'pri' + (o.focused ? '' : ' rest') : 'ghost')
      + (o.focused ? ' focused' : '');
    return '<span class="' + cls + '" style="' + st({
      'min-height': c.dp(S.btnMinH), 'border-radius': c.dp(S.btnRadius),
      padding: c.dp(S.btnPadV) + ' ' + c.dp(S.btnPadH), 'font-size': c.sp(S.btnSp),
      'border-width': o.style === 'pri' ? null : c.dp(1),
      'min-width': o.minW ? c.dp(o.minW) : null,
    }) + '">' + esc(o.label) + ring + '</span>';
  }

  /* ---- the hero (HeroCarousel) ------------------------------------------- */
  function hero(o) {
    var c = o.c, H = D.HERO, L = D.STR[o.lang || 'en'];
    var body = ''
      + '<span class="sh-kicker" style="' + st({ display: 'inline-flex',
          'font-size': c.sp(S.heroKickerSp), 'letter-spacing': c.sp(1),
          'margin-bottom': c.dp(10) }) + '">' + esc(H.kicker) + '</span>'
      + '<div class="sh-tagline" style="' + st({ 'font-size': c.sp(S.heroTaglineSp),
          'margin-bottom': c.dp(8) }) + '">' + esc(H.tagline) + '</div>'
      + '<div class="sh-hero-title" style="' + st({ 'font-size': c.sp(S.heroTitleSp),
          'margin-bottom': c.dp(12) }) + '">' + esc(H.title) + '</div>'
      + '<div class="sh-meta" style="' + st({ gap: c.dp(10), 'font-size': c.sp(S.heroMetaSp),
          'margin-bottom': c.dp(8) }) + '">'
        + '<span>' + H.year + '</span>'
        + '<span class="chip" style="' + st({ 'font-size': c.sp(S.heroChipSp),
            padding: c.dp(2) + ' ' + c.dp(8), 'border-radius': c.dp(6) }) + '">' + esc(H.cert) + '</span>'
        + '<span>' + esc(H.genre) + '</span>'
      + '</div>'
      + '<div class="sh-meta" style="' + st({ gap: c.dp(10), 'font-size': c.sp(S.heroRatingSp),
          'margin-bottom': c.dp(10) }) + '"><span>★ ' + H.rating + '</span></div>'
      + '<div class="sh-syn" style="' + st({ 'font-size': c.sp(S.heroSynSp),
          width: c.dp(S.heroBodyW), 'margin-bottom': c.dp(16) }) + '">' + esc(H.syn) + '</div>'
      + '<div class="sh-actions" style="' + st({ gap: c.dp(S.btnGap) }) + '">'
        + button({ c: c, label: L.play, style: 'pri', focused: false, minW: 200 })
        + button({ c: c, label: L.myList, style: 'ghost' })
      + '</div>';

    return '<div class="sh-hero" style="' + st({ height: c.dp(S.heroHeight) }) + '">'
      + '<div class="sh-hero-bg" style="' + st({ background: D.grad(H.title + '-bg') }) + '"></div>'
      + '<div class="sh-hero-scrim"></div>'
      + '<div class="sh-hero-body" style="' + st({ left: c.dp(S.padH),
          bottom: c.dp(S.heroBodyBot), width: c.dp(S.heroBodyW) }) + '">' + body + '</div>'
      + '</div>';
  }

  /* ---- whole screens ----------------------------------------------------- */
  /* Home, at the top: the hero behind a transparent bar. */
  function homeHero(o) {
    var c = o.c;
    return tv(o, hero({ c: c, lang: o.lang })
      + '<div class="sh-page" style="' + st({ top: c.dp(S.heroHeight) }) + '">'
      + contentRow({ c: c, row: D.ROWS[0], lang: o.lang, quiet: o.quiet, seeAll: true })
      + '</div>'
      + appBar({ c: c, b: o.b, solid: false, lang: o.lang, activeNav: 0, focus: o.focus || 'nav',
                 surprise: o.surprise }));
  }

  /* Home, scrolled: the bar is solid and the content starts under it. Both variants use the SAME
   * 20 dp gap under the bar, which is the point — FR-9 says content offsets by the bar's height,
   * so a Slim bar hands those 12 dp straight back to the rows. */
  function homeScrolled(o) {
    var c = o.c;
    var rows = '';
    [1, 2, 3].forEach(function (i, n) {
      rows += contentRow({ c: c, row: D.ROWS[i], lang: o.lang, quiet: o.quiet,
                           focusTile: (o.focusTileRow === n) ? o.focusTile : null,
                           seeAll: !o.quiet });
    });
    return tv(o, '<div class="sh-page" style="' + st({ top: c.dp(o.b.height + 20) }) + '">'
      + rows + '</div>'
      + appBar({ c: c, b: o.b, solid: true, lang: o.lang, activeNav: 0,
                 focus: o.focus === undefined ? 'nav' : o.focus, surprise: o.surprise }));
  }

  /* Discover, under whichever bar. */
  function discover(o) {
    var c = o.c, L = D.STR[o.lang || 'en'];
    var chips = '';
    L.segs.forEach(function (label, i) {
      chips += '<span class="sh-chip' + (i === 4 ? (o.chipFocus ? ' focused' : ' cur') : '')
        + '" style="' + st({
        padding: c.dp(S.chipPadV) + ' ' + c.dp(S.chipPadH), 'border-radius': c.dp(S.chipRadius),
        'font-size': c.sp(S.chipSp) }) + '">' + esc(label) + '</span>';
    });

    var body = '<div style="' + st({ 'padding-left': c.dp(S.padH), 'padding-right': c.dp(S.padH),
        'padding-bottom': c.dp(16) }) + '">'
      + '<div class="sh-ptitle" style="' + st({ 'font-size': c.sp(S.discoverTitleSp) }) + '">'
        + esc(L.discover) + '</div>'
      + '<div class="sh-psub" style="' + st({ 'font-size': c.sp(S.discoverSubSp) }) + '">'
        + esc(L.discoverSub) + '</div>'
      + '<div style="' + st({ height: c.dp(S.discoverTitleGap) }) + '"></div>'
      + '<span class="sh-chipbar" style="' + st({ 'border-radius': c.dp(S.chipBarRadius),
          padding: c.dp(S.chipBarPad), gap: c.dp(0) }) + '">' + chips + '</span>'
      + '</div>';

    var rows = contentRow({ c: c, row: D.ROWS[1], lang: o.lang, quiet: o.quiet })
      + contentRow({ c: c, row: D.ROWS[3], lang: o.lang, quiet: o.quiet });

    return tv(o, '<div class="sh-page" style="' + st({
        top: c.dp(o.b.height + S.pageTitleGap) }) + '">' + body + rows + '</div>'
      + appBar({ c: c, b: o.b, solid: true, lang: o.lang, activeNav: 3, focus: null,
                 surprise: o.surprise }));
  }

  /* The detail page "Surprise me" lands on. */
  function landingDetail(o) {
    var c = o.c, M = D.LANDING, L = D.STR[o.lang || 'en'];
    var body = ''
      + '<span class="sh-kicker" style="' + st({ display: 'inline-flex',
          'font-size': c.sp(S.heroKickerSp), 'letter-spacing': c.sp(1),
          'margin-bottom': c.dp(10) }) + '">' + esc(M.kicker) + '</span>'
      + '<div class="sh-hero-title" style="' + st({ 'font-size': c.sp(S.heroTitleSp),
          'margin-bottom': c.dp(12) }) + '">' + esc(M.title) + '</div>'
      + '<div class="sh-meta" style="' + st({ gap: c.dp(10), 'font-size': c.sp(S.heroMetaSp),
          'margin-bottom': c.dp(8) }) + '">'
        + '<span>' + M.year + '</span>'
        + '<span class="chip" style="' + st({ 'font-size': c.sp(S.heroChipSp),
            padding: c.dp(2) + ' ' + c.dp(8), 'border-radius': c.dp(6) }) + '">' + esc(M.cert) + '</span>'
        + '<span>' + esc(M.runtime) + '</span><span>' + esc(M.genre) + '</span>'
        + '<span>★ ' + M.rating + '</span>'
      + '</div>'
      + '<div class="sh-syn" style="' + st({ 'font-size': c.sp(S.heroSynSp),
          width: c.dp(S.heroBodyW), 'margin-bottom': c.dp(16) }) + '">' + esc(M.syn) + '</div>'
      + '<div class="sh-actions" style="' + st({ gap: c.dp(S.btnGap) }) + '">'
        + button({ c: c, label: L.play, style: 'pri', focused: o.focus !== 'another', minW: 200 })
        + button({ c: c, label: L.another, style: 'ghost', focused: o.focus === 'another' })
        + button({ c: c, label: L.myList, style: 'ghost' })
      + '</div>';

    return tv(o, '<div class="sh-hero" style="' + st({ height: c.dp(S.heroHeight + 40) }) + '">'
      + '<div class="sh-hero-bg" style="' + st({ background: D.grad(M.title + '-bg') }) + '"></div>'
      + '<div class="sh-hero-scrim"></div>'
      + '<div class="sh-hero-body" style="' + st({ left: c.dp(S.padH),
          bottom: c.dp(S.heroBodyBot), width: c.dp(S.heroBodyW) }) + '">' + body + '</div></div>'
      + '<div class="sh-page" style="' + st({ top: c.dp(S.heroHeight + 40) }) + '">'
      + contentRow({ c: c, row: { key: 'related', title: 'More like this', count: 12,
                                  kind: 'poster' }, lang: o.lang, quiet: o.quiet }) + '</div>'
      + appBar({ c: c, b: o.b, solid: false, lang: o.lang, activeNav: 0, focus: null,
                 surprise: o.surprise }));
  }

  function tv(o, inner) {
    return '<div class="sh-tv"' + (o.skin ? ' data-skin="' + o.skin + '"' : '') + '>'
      + inner + '</div>';
  }

  /* ---- the measured specimen canvas -------------------------------------- */
  function specimen() {
    var c = ctx(1);
    var keys = ['standard', 'slim48', 'slim44'];
    var base = D.BARS.standard;
    var html = '<div class="sh-spec">'
      + '<h2 class="sh-h">The three bars, measured</h2>'
      + '<p class="sh-sub">Each bar is drawn at 1:1 and measured under it. Every number is <b>dp</b> '
      + 'for geometry and <b>sp</b> for type, at Screen scale 100 %; green is a change from today. '
      + 'The binding constraint is not the type — it is the <b>search and avatar circles</b>. Each '
      + 'carries a focus ring drawn outside it, so what has to fit in the bar is the whole focusable '
      + 'disc, not the circle. That is why Slim resizes the circles and not only the text, and it is '
      + 'the number the <b>44 vs 48</b> decision turns on.</p>';

    keys.forEach(function (k) {
      var b = D.BARS[k];
      html += '<p class="sh-cap"><span class="sh-tag' + (k === 'slim48' ? ' rec' : '') + '">'
        + (k === 'slim48' ? 'RECOMMENDED' : k.toUpperCase()) + '</span>'
        + esc(b.label) + ' — ' + esc(b.note) + '</p>';
      html += '<div class="sh-spec-strip" style="' + st({ height: c.dp(b.height) }) + '">'
        + appBar({ c: c, b: b, solid: true, lang: 'en', activeNav: 0, focus: 'nav' })
        + '</div>';

      var dims = [
        ['bar height', b.height, base.height, 'dp'],
        ['brand mark', b.mark, base.mark, 'dp'],
        ['wordmark', b.wordmark, base.wordmark, 'sp'],
        ['nav pill', b.navSp, base.navSp, 'sp'],
        ['pill padding', b.navPadH + '×' + b.navPadV, base.navPadH + '×' + base.navPadV, 'dp'],
        ['pill corner', b.navRadius, base.navRadius, 'dp'],
        ['gap', b.clusterGap, base.clusterGap, 'dp'],
        ['circle', b.circle, base.circle, 'dp'],
        ['glyph', b.glyph, base.glyph, 'dp'],
        ['focus ring', b.ring + (b.ringGap ? ' +' + b.ringGap + ' clear' : ' flush'),
          base.ring + (base.ringGap ? ' +' + base.ringGap + ' clear' : ' flush'), 'dp'],
        ['avatar initials', b.initials, base.initials, 'sp'],
        ['clock', b.clock, base.clock, 'sp'],
      ];
      html += '<div class="sh-dims">';
      dims.forEach(function (d) {
        var changed = String(d[1]) !== String(d[2]);
        html += '<span class="sh-dim' + (changed ? ' chg' : '') + '">' + esc(d[0]) + ' <b>'
          + esc(d[1]) + '</b> ' + d[3] + '</span>';
      });
      /* The one number that decides 44 vs 48: what is left above and below the focusable disc
       * once the ring and its clearance are counted. */
      var disc = b.circle + 2 * (b.ring + (b.ringGap || 0));
      var clear = (b.height - disc) / 2;
      html += '<span class="sh-dim' + (clear >= 4 ? ' chg' : '') + '">focusable disc <b>'
        + disc + '</b> dp → <b>' + clear + '</b> dp clear, top and bottom</span>';
      html += '</div>';
    });
    return html + '</div>';
  }

  /* ---- the row-heading comparison ---------------------------------------- */
  function headingCompare() {
    var c = ctx(1);
    function crop(quiet, rows) {
      var inner = '';
      rows.forEach(function (r) {
        inner += contentRow({ c: c, row: r, lang: 'en', quiet: quiet, seeAll: !quiet });
      });
      /* 464 dp, not a clean half-screen: two crops plus the gap have to fit inside 1920 px. */
      return '<div class="sh-crop" style="' + st({ width: c.dp(464),
        height: c.dp(230), 'padding-top': c.dp(14) }) + '">'
        + '<div style="' + st({ transform: 'translateX(-' + c.dp(S.padH - 16) + ')' }) + '">'
        + inner + '</div></div>';
    }
    var rows = [D.ROWS[1]];
    return '<div class="sh-spec">'
      + '<h2 class="sh-h">Idea B — the row heading, at 1:1</h2>'
      + '<p class="sh-sub">A 464 dp crop of the screen\'s left edge, both at Screen scale 100 %. '
      + 'Today the heading is <b>18 sp SemiBold</b> with <b>See All</b> in accent at the row\'s '
      + 'right edge — which on a whole screen is 960 dp away, and here is the crop\'s edge. '
      + 'B drops the heading to <b>16 sp</b>, puts the count beside it at <b>13 sp</b> in '
      + '<code>--ink-dim</code>, and drops See All. 13 sp is not a new size: it is what '
      + '<code>ContentRow</code>\'s existing <code>trailingInfo</code> slot already uses.</p>'
      + '<div class="sh-pair">'
      + '<div><p class="sh-cap"><span class="sh-tag">TODAY</span>18 sp · See All</p>' + crop(false, rows) + '</div>'
      + '<div><p class="sh-cap"><span class="sh-tag rec">IDEA B</span>16 sp · count</p>' + crop(true, rows) + '</div>'
      + '</div></div>';
  }

  /* ---- frame registry ---------------------------------------------------- */
  var FRAMES = [];
  function frame(id, html, cls) {
    FRAMES.push('<section class="sh-frame ' + (cls || 'sh-only') + '" id="' + id + '">'
      + html + '</section>');
  }
  function band(h, sub) {
    return '<div class="sh-band"><p class="sh-h">' + h + '</p><p class="sh-sub">' + sub + '</p></div>';
  }

  var B = D.BARS;
  var c100 = ctx(1), c90 = ctx(0.9);

  /* 1 — Standard vs Slim, over the hero and scrolled, Home focused */
  frame('standard-home-hero', homeHero({ c: c100, b: B.standard }));
  frame('slim48-home-hero', homeHero({ c: c100, b: B.slim48 }));
  frame('standard-home-scrolled', homeScrolled({ c: c100, b: B.standard }));
  frame('slim48-home-scrolled', homeScrolled({ c: c100, b: B.slim48 }));
  frame('slim44-home-scrolled', homeScrolled({ c: c100, b: B.slim44 }));

  /* 2 — Slim with focus on search, then the avatar */
  frame('slim48-focus-search', homeScrolled({ c: c100, b: B.slim48, focus: 'search' }));
  frame('slim48-focus-avatar', homeScrolled({ c: c100, b: B.slim48, focus: 'avatar' }));

  /* 3 — the scale picture the owner judges the feel from (the stacked sheet is built last, below,
   *     so its two caption bands cannot push the plain TV frames onto a fractional offset) */
  frame('slim48-scale90-home-scrolled', homeScrolled({ c: c90, b: B.slim48 }));

  /* 4 — Discover with Slim */
  frame('slim48-discover', discover({ c: c100, b: B.slim48 }));
  frame('standard-discover', discover({ c: c100, b: B.standard }));

  /* 5 — the two browse ideas */
  /* At rest first: the question A1 has to answer is whether a pill in the right cluster reads as
   * an action rather than a fifth tab. Only the resting frame answers it. */
  frame('A1-bar-at-rest',
    homeScrolled({ c: c100, b: B.slim48, surprise: 'pill', focus: 'nav' }));
  frame('A1-bar-surprise-focused',
    homeScrolled({ c: c100, b: B.slim48, surprise: 'pill', focus: 'surprise' }));
  frame('A2-bar-surprise-focused',
    homeScrolled({ c: c100, b: B.slim48, surprise: 'icon', focus: 'surprise' }));
  frame('A1-detail-brotsjor',
    landingDetail({ c: c100, b: B.slim48, surprise: 'pill' }));
  frame('A1-detail-another-focused',
    landingDetail({ c: c100, b: B.slim48, surprise: 'pill', focus: 'another' }));
  frame('B-home-scrolled',
    homeScrolled({ c: c100, b: B.slim48, quiet: true, focusTileRow: 0, focusTile: 1, focus: null }));

  /* everything the round proposes, together, at 90 % */
  frame('slim48-scale90-with-A-and-B',
    homeScrolled({ c: c90, b: B.slim48, quiet: true, surprise: 'pill',
                   focusTileRow: 0, focusTile: 1, focus: null }));

  /* the house's own required states: the longest strings, Danish and Faroese, and the skins */
  frame('slim48-home-scrolled-da', homeScrolled({ c: c100, b: B.slim48, lang: 'da' }));
  /* fo also carries the round's longest string (`Sjónvarpsrásir`) and the chip focus treatment. */
  frame('slim48-discover-fo', discover({ c: c100, b: B.slim48, lang: 'fo', chipFocus: true }));
  frame('slim48-home-scrolled-noir', homeScrolled({ c: c100, b: B.slim48, skin: 'noir' }));
  frame('slim48-home-scrolled-midnight', homeScrolled({ c: c100, b: B.slim48, skin: 'midnight' }));

  /* The stacked comparison and the annotation canvases go last: see the note in slim-header.css
   * about fractional offsets. */
  frame('scale-100-standard-vs-90-slim',
    band('Today — Standard header, Screen scale 100 %',
         'Content size COMPACT, as prod is set. Home scrolled, Home focused.')
    + homeScrolled({ c: c100, b: B.standard })
    + band('Slim header, Screen scale 90 %',
           'Every dp × 0.9 and every sp × 0.95, per FR-2. The same three rows, drawn smaller — '
           + 'the gaps keep their proportion to the tiles and the text is reduced half as much.')
    + homeScrolled({ c: c90, b: B.slim48 }), 'sh-stack');
  frame('bar-geometry', specimen(), 'sh-spec-frame');
  frame('B-heading-compare', headingCompare(), 'sh-spec-frame');

  document.getElementById('sheet').innerHTML = FRAMES.join('\n');
})();
