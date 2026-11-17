/* Ravilo as the TV's home screen — every frame, built from apps-data.js.
 *
 * Nothing in here holds a measurement. `dp()` and `sp()` are the only places a number becomes a
 * pixel. The stage is 1920 x 1080 for a 960 x 540 dp screen, so dp -> px is x 2.
 */
(function () {
  'use strict';

  var D = window.HA, S = D.SHIPPED, B = D.BARS, L = D.LAYOUTS, BTN = D.BTN;

  /* ---- units ------------------------------------------------------------- */
  function ctx() {
    function dpn(v) { return v * D.PX_PER_DP; }
    return {
      dpn: dpn, spn: dpn,
      dp: function (v) { return dpn(v) + 'px'; },
      sp: function (v) { return dpn(v) + 'px'; },
    };
  }
  var c = ctx();

  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }
  function st(o) {
    var out = [];
    for (var k in o) if (o[k] != null && o[k] !== '') out.push(k + ':' + o[k]);
    return out.join(';');
  }
  function T(lang) { return D.STR[lang || 'en']; }

  /* ---- SVG, with every id minted from a counter -------------------------
   * PROCESS.md trap: a sheet holding many frames hides none of them here, but Chromium will not
   * resolve a paint server defined inside a display:none subtree, and every later reference to a
   * shared id then paints NOTHING in a visible frame. One counter, no shared ids, ever.
   */
  var seq = 0;
  function brandMark(px) {
    var id = 'hagrad' + (++seq);
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

  /* The Apps glyph: FOUR rounded squares, 2 x 2.
   *
   * Not the nine-dot grid every phone launcher uses. Nine dots in a 22 dp circle puts each dot
   * under 4 dp, which at three metres is a smudge; four squares at 8 dp each keep a silhouette. It
   * is also not a grid of POSTERS, which is what Ravilo's own content already looks like — this
   * glyph has to say "the TV's other apps", and squares are the one shape nothing in Ravilo uses. */
  function appsGlyph(px) {
    return '<svg viewBox="0 0 24 24" width="' + px + '" height="' + px + '" fill="currentColor" aria-hidden="true">'
      + '<rect x="3" y="3" width="8" height="8" rx="2.2"></rect>'
      + '<rect x="13" y="3" width="8" height="8" rx="2.2"></rect>'
      + '<rect x="3" y="13" width="8" height="8" rx="2.2"></rect>'
      + '<rect x="13" y="13" width="8" height="8" rx="2.2"></rect></svg>';
  }

  /* The first tile's gear, and the stock home's house. Both drawn as strokes so they read on the
   * card fill at any size and carry no fill-vs-ink ambiguity when focus inverts. */
  /* A cog, not a sun. The first render drew a small circle, eight radial spokes AND a faint outer
   * ring, which at 1x read as an asterisk inside a halo. This is the ring itself as a thick stroke
   * with eight teeth sitting ON it, which is the silhouette a gear has at three metres. */
  function gearGlyph(px) {
    var teeth = '';
    for (var i = 0; i < 8; i++) {
      var a = i * Math.PI / 4;
      var x1 = 12 + Math.cos(a) * 7.2, y1 = 12 + Math.sin(a) * 7.2;
      var x2 = 12 + Math.cos(a) * 10.4, y2 = 12 + Math.sin(a) * 10.4;
      teeth += 'M' + x1.toFixed(2) + ' ' + y1.toFixed(2) + ' L' + x2.toFixed(2) + ' ' + y2.toFixed(2) + ' ';
    }
    return '<svg viewBox="0 0 24 24" width="' + px + '" height="' + px + '" fill="none"'
      + ' stroke="currentColor" stroke-linecap="round" aria-hidden="true">'
      + '<circle cx="12" cy="12" r="7.2" stroke-width="3.1"></circle>'
      + '<path d="' + teeth + '" stroke-width="2.6"></path>'
      + '<circle cx="12" cy="12" r="2.6" stroke-width="2" opacity=".55"></circle></svg>';
  }
  function homeGlyph(px) {
    return '<svg viewBox="0 0 24 24" width="' + px + '" height="' + px + '" fill="none"'
      + ' stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
      + '<path d="M3.4 10.4 L12 3.6 L20.6 10.4 L20.6 20 L3.4 20 Z"></path>'
      + '<path d="M9.4 20 L9.4 13.6 L14.6 13.6 L14.6 20"></path></svg>';
  }
  /* SurpriseMe.kt's sparkle and the music pill's note — the crowded-bar frame only. */
  function sparkleGlyph(px) {
    return '<svg viewBox="0 0 24 24" width="' + px + '" height="' + px + '" fill="currentColor" aria-hidden="true">'
      + '<path d="M10 4 Q12 10 19 13 Q12 16 10 22 Q8 16 1 13 Q8 10 10 4 Z"></path>'
      + '<path d="M19 1 Q19.9 4.1 23 5 Q19.9 5.9 19 9 Q18.1 5.9 15 5 Q18.1 4.1 19 1 Z"></path></svg>';
  }
  /* PlayPauseGlyph at its playing state — the pill's trailing mark is a play/pause control, not a
   * music note. */
  function playGlyph(px) {
    return '<svg viewBox="0 0 24 24" width="' + px + '" height="' + px + '" fill="currentColor" aria-hidden="true">'
      + '<rect x="5" y="3.5" width="4.6" height="17" rx="1.1"></rect>'
      + '<rect x="14.4" y="3.5" width="4.6" height="17" rx="1.1"></rect></svg>';
  }
  function castGlyph(px) {
    return '<svg viewBox="0 0 24 24" width="' + px + '" height="' + px + '" fill="none"'
      + ' stroke="currentColor" stroke-width="1.9" stroke-linecap="round" aria-hidden="true">'
      + '<path d="M3 6.5 L3 5 L21 5 L21 19 L12.5 19"></path>'
      + '<path d="M3 10.5 a8 8 0 0 1 8 8"></path><path d="M3 14.8 a4 4 0 0 1 4 4"></path>'
      + '<circle cx="3.4" cy="18.6" r=".9" fill="currentColor"></circle></svg>';
  }

  /* ---- the focus ring, drawn OUTSIDE its target --------------------------
   * focusRing(..., outset = BAR_RING_OUTSET) — a 2 dp stroke whose inner edge is `ringGap` dp clear
   * of the fill, so the fill can never clip it and `--ring` #8e82ff is not sitting on `--accent`
   * #7b6ef0, which is a 4 % difference nobody sees from a sofa. BOTH bars, as it now ships.
   */
  function ring(o) {
    return '<i style="' + st({
      position: 'absolute', inset: '-' + (c.dpn(o.w) + c.dpn(o.gap)) + 'px',
      'border-radius': o.radius, 'border-style': 'solid', 'border-color': 'var(--ring)',
      'border-width': c.dp(o.w), 'pointer-events': 'none',
    }) + '"></i>';
  }

  /* ---- the app bar -------------------------------------------------------
   * o = {
   *   b       the bar geometry (B.standard | B.slim)
   *   entry   null            — the hook is off: FR-10 says the entry is not there
   *           'A'             — direction A: a round button beside Search
   *           'B'             — direction B: an Apps pill at the end of the nav pills
   *           'B2'            — B with the glyph before the label
   *   focus   'nav' | 'apps' | 'search' | 'avatar' | null
   *   appsCur direction B only: Apps is the screen you are on
   *   nav     the tab labels; null = the brief's stand-in set for this language
   *   crowded draw Cast, the music pill and Surprise as well — every optional item at once
   *   signedOut no avatar and no search: Apps has to work with nobody signed in
   * }
   */
  function appBar(o) {
    var b = o.b, t = T(o.lang), nav = o.nav || t.nav;
    var active = o.activeNav == null ? 0 : o.activeNav;
    var html = '<div class="ha-bar' + (o.solid ? ' solid' : '') + '" style="'
      + st({ height: c.dp(b.height), 'padding-left': c.dp(S.padH), 'padding-right': c.dp(S.padH),
             gap: c.dp(b.itemGap) }) + '">';

    html += '<div class="ha-brand" style="' + st({ gap: c.dp(b.brandGap) }) + '">'
      + brandMark(c.dpn(b.mark))
      + '<span class="ha-wm" style="' + st({ 'font-size': c.sp(b.wordmark),
          'letter-spacing': c.sp(b.wordmarkTrack) }) + '">Ravilo</span></div>';

    /* the tabs, and direction B's pill at the end of them */
    html += '<div class="ha-nav" style="' + st({ gap: c.dp(b.itemGap) }) + '">';
    nav.forEach(function (label, i) {
      var cls = 'ha-navitem' + (i === active && !o.appsCur ? ' cur' : '')
        + (o.focus === 'nav' && i === active ? ' focused' : '');
      html += '<span class="' + cls + '" style="' + st({
        'font-size': c.sp(b.navSp), padding: c.dp(b.navPadV) + ' ' + c.dp(b.navPadH),
        'border-radius': c.dp(b.navRadius) }) + '">' + esc(label) + '</span>';
    });
    if (o.entry === 'B' || o.entry === 'B2') {
      var foc = o.focus === 'apps';
      /* A tab's own geometry and a tab's own focus treatment — the inverted fill. Anything else
       * here would be a third treatment in a row of four that already has one. */
      html += '<span class="ha-navitem' + (o.appsCur ? ' cur' : '') + (foc ? ' focused' : '')
        + '" style="' + st({ 'font-size': c.sp(b.navSp),
            padding: c.dp(b.navPadV) + ' ' + c.dp(b.navPadH), 'border-radius': c.dp(b.navRadius),
            display: 'inline-flex', 'align-items': 'center', gap: c.dp(6) }) + '">'
        + (o.entry === 'B2' ? appsGlyph(c.dpn(b.navSp + 1)) : '')
        + esc(t.apps) + '</span>';
    }
    html += '</div>';

    /* the right cluster, in the order AppBar.kt builds it:
     *   Cast · music pill · Surprise · [A's Apps] · Search · clock · avatar
     * A's button sits immediately BEFORE Search: the cluster is where the bar keeps its actions,
     * and nothing that already ships moves to make room. */
    html += '<div class="ha-right" style="' + st({ gap: c.dp(b.itemGap) }) + '">';
    if (o.crowded) {
      html += circle({ b: b, cls: 'ha-ic', inner: castGlyph(c.dpn(b.glyph)) });
      html += musicPill(b, o.lang);
      html += barPill({ b: b, lang: o.lang, label: t.surprise, glyph: sparkleGlyph(c.dpn(b.navSp + D.PILL.glyphAdd)) });
    }
    if (o.entry === 'A') {
      html += circle({ b: b, cls: 'ha-ic', focused: o.focus === 'apps',
                       inner: appsGlyph(c.dpn(b.glyph)) });
    } else if (o.entry === 'A2') {
      /* A2 — the labelled pill in the RIGHT CLUSTER. Not in the brief; drawn because the
       * slim-header round settled the same question the other way for Surprise me ("a sparkle
       * does not tell a viewer what it does"), and a reviewer who remembers that will ask. This is
       * A's position with B's label: an action, not a tab, and it says what it is. */
      html += barPill({ b: b, focused: o.focus === 'apps', label: t.apps,
                        glyph: appsGlyph(c.dpn(b.navSp + D.PILL.glyphAdd)) });
    }
    if (!o.signedOut) {
      html += circle({ b: b, cls: 'ha-ic', focused: o.focus === 'search',
                       inner: searchGlyph(c.dpn(b.glyph)) });
    }
    html += '<span class="ha-clock" style="' + st({ 'font-size': c.sp(b.clock) }) + '">'
      + esc(D.CLOCK) + '</span>';
    if (!o.signedOut) {
      html += circle({ b: b, cls: 'ha-av', focused: o.focus === 'avatar',
                       fontSize: c.sp(b.initials), inner: esc(D.AVATAR) });
    }
    html += '</div></div>';
    return html;
  }

  function circle(o) {
    var b = o.b;
    return '<span class="ha-circle ' + o.cls + (o.focused ? ' focused' : '') + '" style="'
      + st({ width: c.dp(b.circle), height: c.dp(b.circle), 'font-size': o.fontSize }) + '">'
      + o.inner
      + (o.focused ? ring({ w: b.ring, gap: b.ringGap, radius: '50%' }) : '') + '</span>';
  }

  /* SurprisePill's geometry exactly: radius navCorner + 6, 1 dp hairline, padding navPadH + 2 by
   * navPadV, gap 7, glyph navType + 2, label navType SemiBold. */
  function barPill(o) {
    var b = o.b, P = D.PILL;
    return '<span class="ha-pill' + (o.focused ? ' focused' : '') + '" style="' + st({
      'font-size': c.sp(b.navSp), gap: c.dp(P.gap),
      padding: c.dp(b.navPadV) + ' ' + c.dp(b.navPadH + P.padHAdd),
      'border-radius': c.dp(b.navRadius + P.radiusAdd), 'border-width': c.dp(P.border),
      position: 'relative' }) + '">' + o.glyph + esc(o.label)
      + (o.focused ? ring({ w: b.ring, gap: b.ringGap,
                            radius: c.dp(b.navRadius + P.radiusAdd + b.ringGap) }) : '') + '</span>';
  }
  /* TvMusicPill (music/TvNowPlaying.kt), to its own numbers — NOT a guess, because this pill is
   * the single widest optional item in the bar and the width arithmetic turns on it. A 28 dp round
   * cover, 8 dp, the title at 14 sp capped at 110 dp and ellipsised, 6 dp, a 12 dp play glyph;
   * radius 20, fill `text` at 0.10, padding 4 / 14 / 4 / 4. There is NO artist on it: the first
   * draft of this frame drew "track · artist" at full length and overstated the crowded bar by
   * about 90 dp, which would have put a defect on the Apps entry that belongs elsewhere. */
  function musicPill(b, lang) {
    return '<span class="ha-pill ha-musicpill" style="' + st({
      'font-size': c.sp(14), gap: '0',
      padding: c.dp(4) + ' ' + c.dp(14) + ' ' + c.dp(4) + ' ' + c.dp(4),
      'border-radius': c.dp(20), 'border-width': '0' }) + '">'
      + '<span class="cover" style="' + st({ width: c.dp(28), height: c.dp(28),
          'border-radius': c.dp(14), background: D.grad(D.MUSIC_NOW.track),
          'margin-right': c.dp(8) }) + '"></span>'
      + '<span class="ttl" style="' + st({ 'max-width': c.dp(110), 'margin-right': c.dp(6) }) + '">'
      + esc(D.MUSIC_NOW.track) + '</span>'
      + playGlyph(c.dpn(12)) + '</span>';
  }

  /* ---- RaviloButton ----------------------------------------------------- */
  function button(o) {
    var cls = 'ha-btn ' + (o.style === 'pri' ? 'pri' + (o.focused ? '' : ' rest') : 'ghost')
      + (o.focused ? ' focused' : '');
    return '<span class="' + cls + '" style="' + st({
      'min-height': c.dp(BTN.minH), 'border-radius': c.dp(BTN.radius),
      padding: c.dp(BTN.padV) + ' ' + c.dp(BTN.padH), 'font-size': c.sp(BTN.sp),
      'border-width': o.style === 'pri' ? null : c.dp(1),
      gap: o.glyph ? c.dp(8) : null, position: 'relative',
    }) + '">' + (o.glyph || '') + esc(o.label)
      + (o.focused ? ring({ w: BTN.ring, gap: BTN.ringOutset,
                            radius: c.dp(BTN.radius + BTN.ringOutset) }) : '') + '</span>';
  }

  /* ---- content tiles and rows (Home behind the bar) ---------------------- */
  function tile(o, w, h, pct) {
    var foc = o.focused;
    return '<span class="ha-tile' + (foc ? ' focused' : '') + '" style="' + st({
      display: 'block', width: c.dp(w),
      transform: foc ? 'scale(' + S.tileFocusScale + ')' : null }) + '">'
      + '<span class="ha-art" style="' + st({ display: 'block', width: c.dp(w), height: c.dp(h),
          'border-radius': c.dp(S.tileRadius), background: D.grad(o.title),
          'outline-width': foc ? c.dp(S.tileRing) : null, 'outline-offset': foc ? '0px' : null }) + '">'
      + '<span class="ini" style="' + st({ 'font-size': c.sp(26) }) + '">'
      + esc(D.initials(o.title)) + '</span>'
      + (pct > 0 ? '<span class="ha-pbar" style="' + st({ height: c.dp(4) }) + '"><i style="'
          + st({ width: pct + '%' }) + '"></i></span>' : '')
      + '</span>'
      + '<span class="lab" style="' + st({ display: 'block', 'font-size': c.sp(S.tileLabelSp),
          'margin-top': c.dp(S.tileLabelGap) }) + '">' + esc(o.title) + '</span>'
      + (o.sub ? '<span class="sub" style="' + st({ display: 'block',
            'font-size': c.sp(S.tileSubSp) }) + '">' + esc(o.sub) + '</span>' : '')
      + '</span>';
  }
  function contentRow(o) {
    var t = T(o.lang), row = o.row;
    var title = row.key === 'continue' ? t.continueRow : row.title;
    var head = '<div class="ha-rowhead" style="' + st({ 'padding-left': c.dp(S.padH),
        'padding-right': c.dp(S.padH), 'margin-bottom': c.dp(S.rowHeadPadB) }) + '">'
      + '<span class="ha-rowtitle" style="' + st({ 'font-size': c.sp(S.rowTitleSp),
          'letter-spacing': c.sp(S.rowTitleTrack) }) + '">' + esc(title) + '</span>'
      + '<span class="ha-seeall" style="' + st({ 'font-size': c.sp(S.seeAllSp) }) + '">'
      + esc(t.seeAll) + '</span></div>';

    var items = '';
    if (row.kind === 'land') {
      D.CONTINUE.slice(0, S.landPerRow).forEach(function (it) {
        items += tile({ title: it.title, sub: it.ep }, S.land.w * S.tileScale,
                      S.land.h * S.tileScale, it.pct);
      });
    } else {
      var off = row.key === 'new' ? 2 : row.key === 'drama' ? 4 : 0;
      for (var i = 0; i < S.tilesPerRow; i++) {
        items += tile({ title: D.TITLES[(i + off) % D.TITLES.length] },
                      S.poster.w * S.tileScale, S.poster.h * S.tileScale, 0);
      }
    }
    var track = '<div class="ha-track" style="' + st({ gap: c.dp(S.itemSpacing),
        'padding-top': c.dp(S.trackPadV), 'padding-bottom': c.dp(S.trackPadV),
        'padding-left': c.dp(S.padH), 'padding-right': c.dp(S.padH) }) + '">' + items + '</div>';
    return '<div style="' + st({ 'margin-bottom': c.dp(S.rowGap) }) + '">' + head + track + '</div>';
  }

  function hero(o) {
    var t = T(o.lang), H = D.HERO;
    var body = '<span class="ha-kicker" style="' + st({ 'font-size': c.sp(S.heroKickerSp),
          'letter-spacing': c.sp(1), 'margin-bottom': c.dp(10) }) + '">' + esc(H.kicker) + '</span>'
      + '<div class="ha-tagline" style="' + st({ 'font-size': c.sp(S.heroTaglineSp),
          'margin-bottom': c.dp(8) }) + '">' + esc(H.tagline) + '</div>'
      + '<div class="ha-hero-title" style="' + st({ 'font-size': c.sp(S.heroTitleSp),
          'margin-bottom': c.dp(12) }) + '">' + esc(H.title) + '</div>'
      + '<div class="ha-meta" style="' + st({ gap: c.dp(10), 'font-size': c.sp(S.heroMetaSp),
          'margin-bottom': c.dp(10) }) + '"><span>' + H.year + '</span>'
        + '<span class="chip" style="' + st({ 'font-size': c.sp(S.heroChipSp),
            padding: c.dp(2) + ' ' + c.dp(8), 'border-radius': c.dp(6) }) + '">' + esc(H.cert) + '</span>'
        + '<span>' + esc(H.genre) + '</span><span>★ ' + H.rating + '</span></div>'
      + '<div class="ha-syn" style="' + st({ 'font-size': c.sp(S.heroSynSp),
          width: c.dp(S.heroBodyW), 'margin-bottom': c.dp(16) }) + '">' + esc(H.syn) + '</div>'
      + '<div class="ha-actions" style="' + st({ gap: c.dp(10) }) + '">'
        + button({ label: 'Play', style: 'pri' }) + '</div>';
    return '<div class="ha-hero" style="' + st({ height: c.dp(S.heroHeight) }) + '">'
      + '<div class="ha-hero-bg" style="' + st({ background: D.grad(H.title + '-bg') }) + '"></div>'
      + '<div class="ha-hero-scrim"></div>'
      + '<div class="ha-hero-body" style="' + st({ left: c.dp(S.padH),
          bottom: c.dp(S.heroBodyBot), width: c.dp(S.heroBodyW) }) + '">' + body + '</div></div>';
  }

  /* ---- Home ------------------------------------------------------------- */
  function homeHero(o) {
    return tv(o, hero(o)
      + '<div class="ha-page" style="' + st({ top: c.dp(S.heroHeight) }) + '">'
      + contentRow({ row: D.ROWS[0], lang: o.lang }) + '</div>'
      + appBar(merge(o, { solid: false, activeNav: 0 })));
  }
  function homeScrolled(o) {
    var rows = '';
    [1, 2, 3].forEach(function (i) { rows += contentRow({ row: D.ROWS[i], lang: o.lang }); });
    return tv(o, '<div class="ha-page" style="' + st({ top: c.dp(o.b.height + 20) }) + '">'
      + rows + '</div>' + appBar(merge(o, { solid: true, activeNav: 0 })));
  }

  /* ---- the page head the Apps screen reuses from DiscoverScreen ---------- */
  function pageHead(o) {
    var t = T(o.lang);
    return '<div style="' + st({ 'padding-left': c.dp(S.padH), 'padding-right': c.dp(S.padH) }) + '">'
      + '<div class="ha-ptitle" style="' + st({ 'font-size': c.sp(S.pageTitleSp) }) + '">'
      + esc(t.apps) + '</div>'
      + '<div class="ha-psub" style="' + st({ 'font-size': c.sp(S.pageSubSp) }) + '">'
      + esc(o.note ? t[o.note] : t.apps_sub) + '</div>'
      + '<div style="' + st({ height: c.dp(16) }) + '"></div></div>';
  }

  /* ---- the Apps grid ----------------------------------------------------
   * o = { lang, layout, focusIndex, scrollRows, settingsLabel, onlyFixed }
   */
  function appsGrid(o) {
    var g = o.layout, t = T(o.lang);
    var items = D.appsList(o.lang, o.settingsLabel, o.onlyFixed);
    var cells = items.map(function (it, i) {
      return appCell(it, g, o.focusIndex === i);
    }).join('');
    return '<div class="ha-grid" style="' + st({
      'padding-left': c.dp(S.padH), 'padding-right': c.dp(S.padH),
      gap: c.dp(g.rowGap) + ' ' + c.dp(S.itemSpacing), width: c.dp(D.SCREEN_W) }) + '">'
      + cells + '</div>';
  }
  /* Every cell is exactly the 16:9 banner cell, and what goes INSIDE it is FR-11's three cases.
   * There is no label under a cell: the first render put one under all eighteen and every banner
   * tile then said its own name twice. FR-11 is explicit — a banner, *or* an icon and a label. */
  function appCell(it, g, focused) {
    var box = {
      width: c.dp(g.tileW), height: c.dp(g.tileH), 'border-radius': c.dp(g.radius),
      'outline-width': focused ? c.dp(S.tileRing) : null, 'outline-offset': focused ? '0px' : null,
      /* Tile.kt's lift, and it is not decoration here. The ring is `--ring`, which in NOIR is amber
       * — and a banner's colour belongs to the app, so a focused Apple TV plate was an amber ring
       * on an orange plate and the focus all but vanished. The lift and the scale separate the
       * focused cell from its neighbours whatever colour the app shipped. */
      'box-shadow': focused ? '0 ' + c.dp(11) + ' ' + c.dp(25) + ' rgba(0,0,0,.6)' : null,
    };
    var inner;
    if (it.fixed) {
      /* The two fixed entries are a CARD, not a coloured plate: they are not apps and the TV has
       * no artwork for them. `--card-2` under the shipped hairline is the surface every other
       * "a control, not content" plate in Ravilo already uses. Glyph and label together, because
       * a bare gear is not a name. */
      inner = '<span class="ha-plate fixed" style="' + st(merge(box, {
        'border-width': c.dp(1), 'flex-direction': 'column', gap: c.dp(g.fbGap) })) + '">'
        + (it.glyph === 'gear' ? gearGlyph(c.dpn(g.tileH * g.fbIcon * 0.8))
                               : homeGlyph(c.dpn(g.tileH * g.fbIcon * 0.8)))
        + '<span class="fblab" style="' + st({ 'font-size': c.sp(g.fbLabelSp),
            'max-width': c.dp(g.tileW - 12) }) + '">' + esc(it.name) + '</span></span>';
    } else if (it.banner === false) {
      /* FR-11's fallback: the app's icon and its label, inside the banner's own cell. */
      inner = '<span class="ha-plate fb" style="' + st(merge(box, {
        'border-width': c.dp(1), 'flex-direction': 'column', gap: c.dp(g.fbGap) })) + '">'
        + '<span class="sq" style="' + st({ width: c.dp(g.tileH * g.fbIcon),
            height: c.dp(g.tileH * g.fbIcon), 'border-radius': c.dp(g.tileH * g.fbIcon * 0.22),
            background: D.FALLBACK_ICON.bg, color: D.FALLBACK_ICON.ink,
            'font-size': c.sp(g.tileH * g.fbIcon * 0.5) }) + '">'
        + esc(it.name.charAt(0)) + '</span>'
        + '<span class="fblab" style="' + st({ 'font-size': c.sp(g.fbLabelSp),
            'max-width': c.dp(g.tileW - 12) }) + '">' + esc(it.name) + '</span></span>';
    } else {
      var p = D.plate(it.name);
      inner = '<span class="ha-plate" style="' + st(merge(box, {
        background: p.css, padding: c.dp(10) })) + '">'
        + '<span class="nm" style="' + st({ 'font-size': c.sp(g.tileH * 0.19) }) + '">'
        + esc(it.name) + '</span></span>';
    }
    return '<span class="ha-app' + (focused ? ' focused' : '') + '" style="' + st({
      display: 'block', width: c.dp(g.tileW), height: c.dp(g.tileH),
      transform: focused ? 'scale(' + S.tileFocusScale + ')' : null }) + '">' + inner + '</span>';
  }

  /* ---- the Apps list, direction C --------------------------------------- */
  function appsListUi(o) {
    var g = L.list;
    var items = D.appsList(o.lang, o.settingsLabel, o.onlyFixed);
    var inner = D.SCREEN_W - 2 * S.padH;
    var colW = (inner - (g.cols - 1) * g.colGap) / g.cols;
    var perCol = Math.ceil(items.length / g.cols);
    var cols = '';
    for (var k = 0; k < g.cols; k++) {
      var rows = '';
      items.slice(k * perCol, (k + 1) * perCol).forEach(function (it, j) {
        var i = k * perCol + j;
        var focused = o.focusIndex === i;
        var ico;
        if (it.fixed) {
          ico = '<span class="ico" style="' + st({ width: c.dp(g.iconSize), height: c.dp(g.iconSize),
              'border-radius': c.dp(g.iconRadius), background: 'var(--card-2)',
              border: c.dp(1) + ' solid var(--line)',
              /* NOT inverted on focus. The row's fill inverts; the icon tile does not, and the
               * first render drew the focused gear near-black on its own near-black square. An
               * app's icon does not change colour when you focus it either. */
              color: 'var(--ink)' }) + '">'
            + (it.glyph === 'gear' ? gearGlyph(c.dpn(g.iconSize * 0.54))
                                   : homeGlyph(c.dpn(g.iconSize * 0.54))) + '</span>';
        } else if (it.banner === false) {
          ico = '<span class="ico" style="' + st({ width: c.dp(g.iconSize), height: c.dp(g.iconSize),
              'border-radius': c.dp(g.iconRadius), background: D.FALLBACK_ICON.bg,
              color: D.FALLBACK_ICON.ink, 'font-size': c.sp(g.iconSize * 0.42) }) + '">'
            + esc(it.name.charAt(0)) + '</span>';
        } else {
          ico = '<span class="ico" style="' + st({ width: c.dp(g.iconSize), height: c.dp(g.iconSize),
              'border-radius': c.dp(g.iconRadius), background: D.plate(it.name).css,
              'font-size': c.sp(g.iconSize * 0.42) }) + '">' + esc(it.name.charAt(0)) + '</span>';
        }
        rows += '<span class="ha-lrow' + (focused ? ' focused' : '') + '" style="' + st({
          height: c.dp(g.rowH), 'border-radius': c.dp(g.rowRadius), gap: c.dp(g.gap),
          'padding-left': c.dp(8), 'padding-right': c.dp(8), 'margin-bottom': c.dp(g.rowGapV),
          width: c.dp(colW) }) + '">' + ico
          + '<span class="nm" style="' + st({ 'font-size': c.sp(g.labelSp) }) + '">'
          + esc(it.name) + '</span>'
          /* The inverted fill ALONE, with no ring on top of it. SKILL-house allows two focus
           * treatments and a row takes the chip one; the first render drew the fill AND a 3 dp
           * ring, which on a D-pad reads as two targets (round 11's defect, exactly). */
          + '</span>';
      });
      cols += '<div class="ha-listcol" style="' + st({ width: c.dp(colW) }) + '">' + rows + '</div>';
    }
    return '<div class="ha-list" style="' + st({ 'padding-left': c.dp(S.padH),
      'padding-right': c.dp(S.padH), gap: c.dp(g.colGap) }) + '">' + cols + '</div>';
  }

  /* ---- the Apps screen -------------------------------------------------- */
  function appsScreen(o) {
    /* Signed out there is no app bar, so the column starts under the sign-in screen's own 46 dp
     * brand row instead — the same row, not a second one. */
    var top = o.signedOut ? D.SIGNIN.brandRowH : o.b.height;
    var body = o.layout.kind === 'list' ? appsListUi(o) : appsGrid(o);
    /* The PAGE HEAD SCROLLS WITH THE GRID, and the whole column is clipped at the bar.
     * The first render translated only the grid and left the head where it was, so the second row
     * of tiles slid straight over "Open another app, the TV's settings, or Google TV." — two
     * things in the same pixels, in the one frame that exists to show scrolling. DiscoverScreen's
     * title scrolls away under the bar in exactly this way. */
    var column = '<div style="' + st({ height: c.dp(S.pageTitleGap) }) + '"></div>'
      + pageHead(o) + body;
    var scroll = o.scrollPx ? '<div style="' + st({
      transform: 'translateY(-' + c.dp(o.scrollPx) + ')' }) + '">' + column + '</div>' : column;
    var head = '';
    if (o.signedOut) {
      /* FR-11: Apps works with nobody signed in. There is no app bar on a signed-out TV, so the
       * screen carries its own brand row — the sign-in screen's 46 dp one, not a second design. */
      head = '<div class="ha-si-brand" style="' + st({ height: c.dp(D.SIGNIN.brandRowH),
          gap: c.dp(8), 'padding-left': c.dp(S.padH), 'padding-right': c.dp(S.padH) }) + '">'
        + brandMark(c.dpn(D.SIGNIN.brandMark))
        + '<span class="ha-wm" style="' + st({ 'font-size': c.sp(D.SIGNIN.brandSp) }) + '">Ravilo</span>'
        + '<span class="ha-backhint" style="' + st({ 'font-size': c.sp(D.SIGNIN.backHintSp),
            'margin-left': 'auto' }) + '">' + esc(T(o.lang).back_to_signin) + '</span>'
        + '</div>';
    }
    return tv(o, '<div class="ha-page" style="' + st({ top: c.dp(top), bottom: '0',
        overflow: 'hidden' }) + '">' + scroll + '</div>'
      + head
      + (o.signedOut ? '' : appBar(merge(o, { solid: true, activeNav: -1, appsCur: true }))));
  }

  /* ---- sign-in and can't-reach (TvSignInScreen, both states) -------------
   * o = { lang, unreachable, exit, focus }
   *   exit  null      — today's footer, for the comparison
   *         'button'  — the recommendation: an Apps ghost button in the footer row that is already
   *                     there, beside Retry / the password button
   *   focus 'apps' | 'retry' | 'password'
   */
  function signInScreen(o) {
    var t = T(o.lang), M = D.SIGNIN;
    var body;
    if (o.unreachable) {
      body = '<div class="ha-si-err" style="' + st({ gap: c.dp(18) }) + '">'
        + '<div class="ha-si-errline" style="' + st({ 'font-size': c.sp(M.unreachableSp),
            'max-width': c.dp(620) }) + '">' + esc(t.unreachable) + '</div>'
        /* The brief names the server on this screen, and nothing on it names one today. Drawn as
         * the SAME chip the code screen already uses for "This TV" — see the report. It is a
         * PROPOSAL, so the `today` frames must not carry it: a "today" picture with a chip today
         * does not have is how a comparison lies. */
        + (o.today ? '' : '<span class="ha-chip" style="' + st({ 'border-radius': c.dp(M.chipRadius),
            padding: c.dp(M.chipPadV) + ' ' + c.dp(M.chipPadH), gap: c.dp(M.chipGap),
            'align-self': 'center' }) + '">'
        + '<span class="k" style="' + st({ 'font-size': c.sp(M.chipKeySp),
            'letter-spacing': c.sp(M.chipKeyTrack) }) + '">' + esc(t.server) + '</span>'
        + '<span class="v" style="' + st({ 'font-size': c.sp(M.chipValSp) }) + '">'
        + esc(M.host) + '</span></span>') + '</div>';
    } else {
      var col = '<div class="ha-si-col" style="' + st({ width: c.dp(M.bodyMaxW) }) + '">'
        + '<div class="ha-si-title" style="' + st({ 'font-size': c.sp(M.titleSp) }) + '">'
        + esc(t.signin_title) + '</div>'
        + '<div style="' + st({ height: c.dp(M.gap1) }) + '"></div>'
        + '<div class="ha-si-klabel" style="' + st({ 'font-size': c.sp(M.codeLabelSp),
            'letter-spacing': c.sp(M.codeLabelTrack) }) + '">' + esc(t.code_label) + '</div>'
        + '<div style="' + st({ height: c.dp(M.gap2) }) + '"></div>'
        /* groupCode(): three characters, a SPACE, three characters. The brief writes the code with
         * a hyphen; the shipped screen groups with a space and this is the shipped screen. */
        + '<div class="ha-si-code" style="' + st({ 'font-size': c.sp(M.codeSp),
            'letter-spacing': c.sp(M.codeTrack) }) + '">'
        + esc(M.code.replace('-', ' ')) + '</div>'
        + '<div style="' + st({ height: c.dp(M.gap3) }) + '"></div>'
        + '<div class="ha-si-how" style="' + st({ 'font-size': c.sp(M.howSp),
            'line-height': c.sp(M.howLine), 'min-height': c.dp(M.howMinH),
            display: 'flex', 'align-items': 'center' }) + '">' + esc(t.how) + '</div>'
        + '<div style="' + st({ height: c.dp(M.gap4) }) + '"></div>'
        + '<span class="ha-chip" style="' + st({ 'border-radius': c.dp(M.chipRadius),
            padding: c.dp(M.chipPadV) + ' ' + c.dp(M.chipPadH), gap: c.dp(M.chipGap) }) + '">'
        + '<span class="k" style="' + st({ 'font-size': c.sp(M.chipKeySp),
            'letter-spacing': c.sp(M.chipKeyTrack) }) + '">' + esc(t.this_tv) + '</span>'
        + '<span class="v" style="' + st({ 'font-size': c.sp(M.chipValSp) }) + '">'
        + esc(M.tvName) + '</span></span></div>';
      body = '<div class="ha-si-row" style="' + st({ gap: c.dp(M.qrGap) }) + '">'
        + '<span class="ha-qr" style="' + st({ width: c.dp(M.qrSide), height: c.dp(M.qrSide),
            'border-radius': c.dp(M.qrRadius) }) + '">'
        + window.TSQr.svg('https://' + M.host + '/tv?c=' + M.code.replace('-', ''), 12,
                          { dark: '#0a0c13', light: '#ffffff' })
        + '</span>' + col + '</div>';
    }

    var apps = o.exit === 'button'
      ? button({ label: t.apps, style: 'ghost', focused: o.focus === 'apps',
                 glyph: appsGlyph(c.dpn(BTN.sp + 3)) })
      : '';
    var pw = button({ label: t.password_instead, style: 'ghost', focused: o.focus === 'password' });
    var foot = '<div class="ha-si-foot" style="' + st({ height: c.dp(M.footerH),
        gap: c.dp(BTN.gap) }) + '">'
      + (o.unreachable ? button({ label: t.retry, style: 'pri', focused: o.focus === 'retry' }) : '')
      /* `appsFirst` puts Apps straight after Retry instead of after the password button. On the
       * can't-reach screen that ordering is a real question and not a styling one: signing in with
       * a password cannot work while the server is unreachable, and Apps is the only control on the
       * screen that reaches Wi-Fi. Both drawn; the report asks. */
      + (o.appsFirst ? apps + pw : pw + apps)
      + '<span class="ha-backhint" style="' + st({ 'font-size': c.sp(M.backHintSp),
          'padding-left': c.dp(8) }) + '">' + esc(t.back_to_profiles) + '</span></div>';

    return tv(o, '<div class="ha-si" style="' + st({ padding: c.dp(M.padV) + ' ' + c.dp(M.padH) }) + '">'
      + '<div class="ha-si-brand" style="' + st({ height: c.dp(M.brandRowH), gap: c.dp(8) }) + '">'
      + brandMark(c.dpn(M.brandMark))
      + '<span class="ha-wm" style="' + st({ 'font-size': c.sp(M.brandSp) }) + '">Ravilo</span></div>'
      + '<div class="ha-si-body">' + body + '</div>' + foot + '</div>');
  }

  function tv(o, inner) {
    return '<div class="ha-tv"' + (o.skin ? ' data-skin="' + o.skin + '"' : '') + '>' + inner + '</div>';
  }
  function merge(a, b) {
    var o = {}; var k;
    for (k in a) o[k] = a[k];
    for (k in b) o[k] = b[k];
    return o;
  }

  /* ---- annotation canvas 1: the two bars, both directions, measured ------ */
  function barSpec() {
    var rows = [
      { b: B.standard, entry: 'A', tag: 'A · STANDARD' },
      { b: B.standard, entry: 'A2', tag: 'A2 · STANDARD', rec: true },
      { b: B.standard, entry: 'B', tag: 'B · STANDARD' },
      { b: B.slim, entry: 'A', tag: 'A · SLIM' },
      { b: B.slim, entry: 'A2', tag: 'A2 · SLIM', rec: true },
      { b: B.slim, entry: 'B', tag: 'B · SLIM' },
      { b: B.slim, entry: null, tag: 'HOOK OFF · SLIM' },
    ];
    var html = '<div class="ha-spec"><h2 class="ha-h">The Apps entry in both bars, at 1:1</h2>'
      + '<p class="ha-sub">Each bar is the shipped geometry with one item added and nothing moved, '
      + 'and each is shown with the entry <b>focused</b>. <b>A</b> is a round button the size of '
      + 'Search, in the right cluster immediately before it. <b>A2</b> is that position with a '
      + 'label, in <code>SurprisePill</code>\'s shape — the pill the bar already has. <b>B</b> is an '
      + '<i>Apps</i> pill at the end of the nav pills, in a tab\'s own padding, corner and '
      + 'inverted-fill focus; note that B\'s focused pill and the focused <i>Home</i> tab are the '
      + 'same object. The last strip is <b>FR-10\'s other half</b>: with the hook off the entry is '
      + 'not there, and that is the bar every TV has today. The numbers under each strip are the '
      + '<b>bar\'s</b>, not the direction\'s — they are what all three directions are built from. '
      + '<b>dp</b> for geometry, <b>sp</b> for type.</p>';
    rows.forEach(function (r) {
      html += '<p class="ha-cap"><span class="ha-tag' + (r.rec ? ' rec' : '') + '">'
        + r.tag + '</span>' + esc(r.b.label) + ' — ' + esc(r.b.note) + '</p>'
        + '<div class="ha-spec-strip" style="' + st({ height: c.dp(r.b.height) }) + '">'
        + appBar({ b: r.b, entry: r.entry, solid: true, lang: 'en', activeNav: 0, focus: 'apps' })
        + '</div>';
      var b = r.b, P = D.PILL;
      var discA = b.circle + 2 * (b.ring + b.ringGap);
      var dims = [
        ['bar height', b.height, 'dp'],
        ['A — circle', b.circle, 'dp'], ['A — glyph', b.glyph, 'dp'],
        ['A — focusable disc', discA, 'dp'],
        ['A — clear above/below', (b.height - discA) / 2, 'dp'],
        ['B — pill type', b.navSp, 'sp'],
        ['B — pill padding', b.navPadH + '×' + b.navPadV, 'dp'],
        ['B — pill corner', b.navRadius, 'dp'],
        ['item gap', b.itemGap, 'dp'],
        ['focus ring', b.ring + ' + ' + b.ringGap + ' clear', 'dp'],
      ];
      html += '<div class="ha-dims">';
      dims.forEach(function (d) {
        var cls = d[0] === 'A — clear above/below' ? (d[1] >= 4 ? ' ok' : ' bad') : '';
        html += '<span class="ha-dim' + cls + '">' + esc(d[0]) + ' <b>' + esc(d[1]) + '</b> '
          + d[2] + '</span>';
      });
      html += '</div>';
    });
    return html + '</div>';
  }

  /* ---- annotation canvas 2: the three layouts, measured ----------------- */
  function layoutSpec() {
    var defs = [
      { k: 'grid5', g: L.grid5, tag: 'GRID 5', note: "the brief's lean" },
      { k: 'grid6', g: L.grid6, tag: 'GRID 6', note: 'the whole TV in one view', rec: true },
      { k: 'list', g: L.list, tag: 'LIST C', note: 'icon and label, three columns' },
    ];
    var n = D.appsList('en').length;
    var html = '<div class="ha-spec"><h2 class="ha-h">The three layouts, and what each one shows '
      + 'without scrolling</h2>'
      + '<p class="ha-sub">Eighteen entries — Android settings, Google TV and sixteen apps. The '
      + 'space a layout has is the screen minus the bar, the 24 dp DiscoverScreen gap and the page '
      + 'head: <b>' + Math.round(visibleSpace(B.slim)) + ' dp</b> under a Slim bar, <b>'
      + Math.round(visibleSpace(B.standard)) + ' dp</b> under a Standard one. Everything below is '
      + 'arithmetic from <code>apps-data.js</code>, not an impression of the pictures.</p>'
      + '<div class="ha-dims" style="padding-bottom:4px">';
    defs.forEach(function (d) {
      var g = d.g, rows, pitch, perRow;
      if (g.kind === 'grid') {
        perRow = g.across;
        pitch = g.tileH + g.rowGap;
        rows = Math.ceil(n / perRow);
      } else {
        perRow = g.cols;
        pitch = g.rowH + g.rowGapV;
        rows = Math.ceil(n / g.cols);
      }
      var fitSlim = Math.floor(visibleSpace(B.slim) / pitch);
      var shown = Math.min(n, fitSlim * perRow);
      html += '<div style="width:100%;padding-bottom:10px">'
        + '<span class="ha-tag' + (d.rec ? ' rec' : (shown < n ? ' no' : '')) + '">' + d.tag + '</span>'
        + '<span class="ha-dim">' + esc(d.note) + ' · '
        + (g.kind === 'grid' ? 'tile <b>' + round1(g.tileW) + ' × ' + round1(g.tileH) + '</b> dp'
                             : 'row <b>' + round1(g.rowH) + '</b> dp tall, ' + g.cols + ' columns')
        + ' · pitch <b>' + round1(pitch) + '</b> dp · <b>' + rows + '</b> rows · </span>'
        + '<span class="ha-dim ' + (shown >= n ? 'ok' : 'bad') + '">visible without scrolling <b>'
        + shown + ' of ' + n + '</b></span></div>';
    });
    return html + '</div></div>';
  }
  /* The page head's own height, so "scroll by one row" can be written as that and not as a guess. */
  function headHeight() {
    return S.pageTitleGap + S.pageTitleSp * 1.2 + S.pageSubSp * 1.35 + 16;
  }
  function visibleSpace(b) {
    /* bar + DiscoverScreen's 24 dp + the page head (title line, sub line, 16 dp) */
    var head = S.pageTitleSp * 1.2 + S.pageSubSp * 1.35 + 16;
    return D.SCREEN_H - (b.height + S.pageTitleGap + head);
  }
  function round1(v) { return Math.round(v * 10) / 10; }

  /* ---- annotation canvas 3: the first tile's label, and the plate check -- */
  function labelSpec() {
    function crop(which, lang, g) {
      g = g || L.grid5;
      var items = D.appsList(lang, which, true).concat(D.APPS.slice(0, 3));
      var cells = items.map(function (it, i) { return appCell(it, g, i === 0); }).join('');
      return '<div class="ha-crop" style="' + st({ width: c.dp(464), 'padding-top': c.dp(18),
          'padding-bottom': c.dp(14), 'padding-left': c.dp(14) }) + '">'
        + '<div class="ha-grid" style="' + st({ gap: c.dp(g.rowGap) + ' ' + c.dp(S.itemSpacing),
            width: c.dp(D.SCREEN_W) }) + '">' + cells + '</div></div>';
    }
    var html = '<div class="ha-spec">'
      + '<h2 class="ha-h">The first tile\'s label — <i>TV settings</i> or <i>Android settings</i></h2>'
      + '<p class="ha-sub">A 464 dp crop of the grid\'s left edge, the first tile focused, at 1:1. '
      + 'In English the decision is not a fit — both labels sit in either cell, and the question is '
      + 'only whether the viewer needs to be told this is <b>not Ravilo\'s own Settings</b>, which is '
      + 'the whole reason the brief leans away from plain <i>Settings</i>. <b>In Faroese it is also '
      + 'a fit</b>, and the two rows below are why.</p>'
      + '<div class="ha-pair">'
      + '<div><p class="ha-cap"><span class="ha-tag rec">LEAN</span>TV settings</p>'
      + crop('tile_tv_settings', 'en') + '</div>'
      + '<div><p class="ha-cap"><span class="ha-tag">ALTERNATIVE</span>Android settings</p>'
      + crop('tile_android_settings', 'en') + '</div></div>'
      + '<p class="ha-cap" style="padding-top:18px"><b>Faroese, in grid5\'s 160 dp cell.</b> The lean '
      + 'is <i>Sjónvarpsstillingar</i>, nineteen characters, and at this width both labels still fit '
      + 'whole.</p>'
      + '<div class="ha-pair">'
      + '<div><p class="ha-cap">fo · <i>Sjónvarpsstillingar</i></p>' + crop('tile_tv_settings', 'fo') + '</div>'
      + '<div><p class="ha-cap">fo · <i>Android-stillingar</i></p>' + crop('tile_android_settings', 'fo') + '</div>'
      + '</div>'
      + '<p class="ha-cap" style="padding-top:18px"><b>And the same two in grid6\'s 131 dp cell, '
      + 'which is where it breaks — for BOTH of them.</b> <i>Sjónvarpsstillingar</i> ellipsises, and '
      + 'so does <i>Android-stillingar</i>: 131 dp holds about fourteen characters at 13 sp and the '
      + 'shorter of the two is eighteen. <b>So the label decision does not rescue six across</b>, '
      + 'and this is what the layout decision actually costs: six across shows all eighteen entries '
      + 'without scrolling and truncates the Faroese label whichever word we choose; five across '
      + 'holds every label whole and scrolls. (A third way, not drawn: let the two fixed tiles\' '
      + 'label wrap to two lines. <i>Android-stillingar</i> would then break at its hyphen and fit; '
      + '<i>Sjónvarpsstillingar</i> is one word and still would not.)</p>'
      + '<div class="ha-pair">'
      + '<div><p class="ha-cap">fo · grid6 · <i>Sjónvarpsstillingar</i></p>'
      + crop('tile_tv_settings', 'fo', L.grid6) + '</div>'
      + '<div><p class="ha-cap">fo · grid6 · <i>Android-stillingar</i></p>'
      + crop('tile_android_settings', 'fo', L.grid6) + '</div>'
      + '</div>';

    /* The plate contrast table — white type on a flat plate, computed rather than eyeballed. */
    html += '<p class="ha-cap" style="padding-top:22px">Every stand-in plate, and its contrast '
      + 'against the white name on it. The plate colour is derived from the app\'s name and then '
      + 'darkened until it clears <b>4.5:1</b>, because a banner whose own name cannot be read at '
      + 'three metres is a blank tile.</p><div class="ha-dims">';
    D.APPS.forEach(function (a) {
      if (a.banner === false) {
        html += '<span class="ha-dim">' + esc(a.name) + ' <b>icon + label</b></span>';
        return;
      }
      var p = D.plate(a.name);
      html += '<span class="ha-dim' + (p.contrast >= 4.5 ? ' ok' : ' bad') + '">' + esc(a.name)
        + ' <b>' + round1(p.contrast) + ':1</b> at L ' + p.l + ' %</span>';
    });
    return html + '</div></div>';
  }

  /* ---- annotation canvas 4: does the bar still fit? ---------------------
   * The crowded frames show the right cluster running off the screen. A picture of an overflow is
   * not a measurement of one, so this canvas MEASURES each combination in the browser after layout
   * and prints the content width against the 960 dp the screen has. Nothing here is typed in.
   *
   * The bar is one Row with `spacedBy(itemGap)` and a `weight(1f)` spacer between the tabs and the
   * right cluster. When the content exceeds the width the spacer goes to zero and the row is
   * clipped at the screen's edge — which is what the crowded pictures show happening to Search,
   * the clock and the avatar.
   */
  var WIDTH_CASES = [
    { id: 'w1', b: B.standard, nav: null, entry: null, tag: 'Standard · today', note: 'the bar with the hook off' },
    { id: 'w2', b: B.standard, nav: null, entry: 'A', tag: 'Standard · A' },
    { id: 'w2b', b: B.standard, nav: null, entry: 'A2', tag: 'Standard · A2' },
    { id: 'w3', b: B.standard, nav: null, entry: 'B', tag: 'Standard · B' },
    { id: 'w4', b: B.standard, nav: D.NAV_SHIPPED.en, entry: 'A', tag: 'Standard · A · shipped tabs' },
    { id: 'w5', b: B.standard, nav: D.NAV_SHIPPED.en, entry: 'B', tag: 'Standard · B · shipped tabs' },
    { id: 'w6', b: B.standard, nav: D.NAV_SHIPPED.fo, entry: 'B', lang: 'fo', tag: 'Standard · B · shipped tabs, Faroese' },
    { id: 'w7', b: B.slim, nav: D.NAV_SHIPPED.en, entry: 'A', tag: 'Slim · A · shipped tabs' },
    { id: 'w8', b: B.slim, nav: D.NAV_SHIPPED.en, entry: 'B', tag: 'Slim · B · shipped tabs' },
    { id: 'w8b', b: B.slim, nav: D.NAV_SHIPPED.en, entry: 'A2', tag: 'Slim · A2 · shipped tabs' },
    { id: 'w9', b: B.slim, nav: D.NAV_SHIPPED.en, entry: 'A', crowded: true,
      tag: 'Slim · A · everything at once', note: 'Cast, the now-playing pill and Surprise me as well' },
    { id: 'w10', b: B.slim, nav: D.NAV_SHIPPED.en, entry: 'B', crowded: true,
      tag: 'Slim · B · everything at once' },
    { id: 'w11', b: B.slim, nav: D.NAV_SHIPPED.en, entry: null, crowded: true,
      tag: 'Slim · hook off · everything at once', note: 'the same bar WITHOUT the Apps entry' },
  ];
  function widthSpec() {
    var html = '<div class="ha-spec"><h2 class="ha-h">Does the bar still fit?</h2>'
      + '<p class="ha-sub">Measured in the browser after layout, not estimated. The content is the '
      + 'brand lockup, the tabs, the right cluster and the gaps between them, inside the 48 dp '
      + 'gutter; the screen is <b>960 dp</b>. Green is room left, red is content the row has to '
      + 'clip — and the row clips at the <b>right</b> edge, so what disappears first is the avatar, '
      + 'then the clock, then Search. The last line is the same bar with no Apps entry at all, '
      + 'which is how much of the overflow is <i>already there</i>.</p><div class="ha-dims">';
    WIDTH_CASES.forEach(function (w) {
      html += '<div style="width:100%;padding-bottom:7px">'
        + '<span class="ha-tag">' + esc(w.tag) + '</span>'
        + '<span class="ha-dim" id="' + w.id + '-out">measuring…</span>'
        + (w.note ? '<span class="ha-dim" style="color:#4a4f5e"> — ' + esc(w.note) + '</span>' : '')
        + '</div>';
    });
    html += '</div></div>'
      /* laid out but off-screen: `display:none` would measure nothing, and would also stop
       * Chromium resolving the brand mark's gradient (PROCESS.md's SVG-id trap, the other way up) */
      + '<div id="ha-measure" style="position:absolute;left:-20000px;top:0;width:1920px"></div>';
    return html;
  }
  function runWidthMeasurements() {
    var host = document.getElementById('ha-measure');
    if (!host) return;
    WIDTH_CASES.forEach(function (w) {
      host.innerHTML = '<div style="position:relative;width:1920px;height:' + c.dp(w.b.height) + '">'
        + appBar({ b: w.b, nav: w.nav, entry: w.entry, crowded: w.crowded, lang: w.lang,
                   solid: true, activeNav: 0, focus: null }) + '</div>';
      var bar = host.querySelector('.ha-bar');
      var groups = [bar.querySelector('.ha-brand'), bar.querySelector('.ha-nav'),
                    bar.querySelector('.ha-right')];
      var content = 0;
      groups.forEach(function (g) { content += g.getBoundingClientRect().width; });
      /* the two gaps between the three groups, plus the gutter either side */
      var total = content + 2 * c.dpn(w.b.itemGap) + 2 * c.dpn(S.padH);
      var dp = total / D.PX_PER_DP;
      var left = D.SCREEN_W - dp;
      var out = document.getElementById(w.id + '-out');
      out.className = 'ha-dim ' + (left >= 0 ? 'ok' : 'bad');
      out.innerHTML = 'content <b>' + Math.round(dp) + '</b> dp of 960 · '
        + (left >= 0 ? '<b>' + Math.round(left) + ' dp left</b>'
                     : '<b>' + Math.round(-left) + ' dp clipped</b>');
    });
    host.parentNode.removeChild(host);
  }

  /* ---- frame registry --------------------------------------------------- */
  var FRAMES = [];
  function frame(id, html, cls) {
    FRAMES.push('<section class="ha-frame ' + (cls || 'ha-only') + '" id="' + id + '">' + html + '</section>');
  }
  function band(h, sub) {
    return '<div class="ha-band"><p class="ha-h">' + h + '</p><p class="ha-sub">' + sub + '</p></div>';
  }

  /* --- 1. where the entry sits: both directions, both bars, focused ------ */
  frame('A-standard-home-apps-focused', homeScrolled({ b: B.standard, entry: 'A', focus: 'apps' }));
  frame('A-slim-home-apps-focused', homeScrolled({ b: B.slim, entry: 'A', focus: 'apps' }));
  frame('B-standard-home-apps-focused', homeScrolled({ b: B.standard, entry: 'B', focus: 'apps' }));
  frame('B-slim-home-apps-focused', homeScrolled({ b: B.slim, entry: 'B', focus: 'apps' }));
  frame('B2-slim-home-apps-focused', homeScrolled({ b: B.slim, entry: 'B2', focus: 'apps' }));

  /* A2 — A's position, B's label */
  frame('A2-standard-home-apps-focused', homeScrolled({ b: B.standard, entry: 'A2', focus: 'apps' }));
  frame('A2-slim-home-apps-focused', homeScrolled({ b: B.slim, entry: 'A2', focus: 'apps' }));

  /* --- 2. at rest, which is the question a focused frame cannot answer --- */
  frame('A-standard-at-rest', homeScrolled({ b: B.standard, entry: 'A', focus: 'nav' }));
  frame('A2-standard-at-rest', homeScrolled({ b: B.standard, entry: 'A2', focus: 'nav' }));
  frame('B-standard-at-rest', homeScrolled({ b: B.standard, entry: 'B', focus: 'nav' }));
  /* FR-10's other half: with the hook off there is no entry. */
  frame('hook-off-standard-at-rest', homeScrolled({ b: B.standard, entry: null, focus: 'nav' }));
  /* over the hero, where the bar is transparent and the entry has artwork behind it */
  frame('A-standard-home-hero', homeHero({ b: B.standard, entry: 'A', focus: 'apps' }));

  /* --- 3. the width question, twice ------------------------------------- */
  /* every optional right-cluster item at once: Cast, the music pill, Surprise me, then Apps */
  frame('A-slim-crowded', homeScrolled({ b: B.slim, entry: 'A', focus: 'apps', crowded: true }));
  frame('B-slim-crowded', homeScrolled({ b: B.slim, entry: 'B', focus: 'apps', crowded: true }));
  /* the SHIPPED tab set, which is three characters wider than the brief's stand-in */
  frame('B-standard-shipped-tabs', homeScrolled({ b: B.standard, entry: 'B', focus: 'apps',
    nav: D.NAV_SHIPPED.en }));
  frame('B-fo-shipped-tabs', homeScrolled({ b: B.standard, entry: 'B', focus: 'apps', lang: 'fo',
    nav: D.NAV_SHIPPED.fo }));

  /* --- 4. the Apps screen, the three layouts ---------------------------- */
  frame('grid5-apps-first-view',
    appsScreen({ b: B.slim, entry: 'A', layout: L.grid5, focusIndex: 0 }));
  /* Netflix is index 10 — the first cell of grid5's third row. Scrolled by one row pitch so the
   * last row, with the VLC fallback, is in frame. */
  frame('grid5-apps-netflix-scrolled',
    appsScreen({ b: B.slim, entry: 'A', layout: L.grid5, focusIndex: 10,
                 /* the head and the first row scrolled away under the bar, so rows 2-4 are in
                  * frame and the last one — with the VLC fallback — is whole */
                 scrollPx: headHeight() + L.grid5.tileH + L.grid5.rowGap }));
  frame('grid6-apps-first-view',
    appsScreen({ b: B.slim, entry: 'A', layout: L.grid6, focusIndex: 0 }));
  frame('grid6-apps-netflix',
    appsScreen({ b: B.slim, entry: 'A', layout: L.grid6, focusIndex: 10 }));
  frame('list-apps-first-view',
    appsScreen({ b: B.slim, entry: 'A', layout: L.list, focusIndex: 0 }));
  frame('list-apps-netflix',
    appsScreen({ b: B.slim, entry: 'A', layout: L.list, focusIndex: 10 }));
  /* the entry marked as the screen you are on — B can do this, A cannot */
  frame('B-grid6-apps-first-view',
    appsScreen({ b: B.slim, entry: 'B', layout: L.grid6, focusIndex: 0 }));

  /* --- 5. the Apps screen's own required states ------------------------- */
  frame('grid6-apps-da', appsScreen({ b: B.slim, entry: 'A', layout: L.grid6, focusIndex: 0, lang: 'da' }));
  frame('grid6-apps-fo', appsScreen({ b: B.slim, entry: 'A', layout: L.grid6, focusIndex: 0, lang: 'fo' }));
  frame('grid6-apps-noir', appsScreen({ b: B.slim, entry: 'A', layout: L.grid6, focusIndex: 2, skin: 'noir' }));
  frame('grid6-apps-midnight', appsScreen({ b: B.slim, entry: 'A', layout: L.grid6, focusIndex: 2, skin: 'midnight' }));
  /* a TV with nothing else installed: the two fixed entries are always there, so this is as empty
   * as Apps can be, and it is an invitation rather than an apology */
  frame('grid6-apps-nothing-else',
    appsScreen({ b: B.slim, entry: 'A', layout: L.grid6, focusIndex: 0, onlyFixed: true,
                 note: 'apps_nothing_else' }));
  /* FR-11: with nobody signed in. No bar, because a signed-out TV has none. */
  frame('grid6-apps-signed-out',
    appsScreen({ b: B.slim, entry: 'A', layout: L.grid6, focusIndex: 0, signedOut: true }));

  /* --- 6. the exits FR-12 asks for ------------------------------------- */
  frame('signin-apps-focused', signInScreen({ exit: 'button', focus: 'apps' }));
  frame('signin-today', signInScreen({ exit: null, focus: 'password', today: true }));
  frame('signin-apps-fo', signInScreen({ exit: 'button', focus: 'apps', lang: 'fo' }));
  frame('cantreach-apps-focused', signInScreen({ unreachable: true, exit: 'button', focus: 'apps' }));
  /* arrival focus: Retry is the way out of the error and keeps it. The open question in the report. */
  frame('cantreach-retry-focused', signInScreen({ unreachable: true, exit: 'button', focus: 'retry' }));
  frame('cantreach-today', signInScreen({ unreachable: true, exit: null, focus: 'retry', today: true }));
  /* the ordering question: Apps straight after Retry, ahead of a password button that cannot work
   * while the server is unreachable */
  frame('cantreach-apps-second', signInScreen({ unreachable: true, exit: 'button', focus: 'apps',
    appsFirst: true }));
  frame('cantreach-apps-da', signInScreen({ unreachable: true, exit: 'button', focus: 'apps', lang: 'da' }));

  /* --- 7. stacked comparisons and the annotation canvases, LAST ---------
   * A text canvas lays out to a fractional height and pushes every frame after it onto a
   * fractional y, which Playwright rounds outwards into a 1081 px TV picture. */
  frame('compare-A-vs-B-standard',
    band('Direction A — a round button beside Search, AT REST',
         'Standard bar, Home scrolled, focus on Home. The right cluster is where the bar keeps its '
         + 'actions, so the entry does not read as a fifth tab — but a bare glyph also does not say '
         + 'what it opens.')
    + homeScrolled({ b: B.standard, entry: 'A', focus: 'nav' })
    + band('Direction A2 — the same position, with the label — AT REST (recommended)',
           'SurprisePill\'s shape, which the bar already has. An action, not a tab, and it says '
           + 'what it is. It is the widest of the three — 110 dp more bar than today against A\'s '
           + '58 — and a Standard bar still has 150 dp spare after it. See the width canvas.')
    + homeScrolled({ b: B.standard, entry: 'A2', focus: 'nav' })
    + band('Direction B — an Apps pill at the end of the nav pills, AT REST',
           'Apps now sits among the places you can be, in a tab\'s own padding, corner and weight. '
           + 'This is the frame that answers B: it reads as a fifth section of Ravilo\'s library '
           + 'rather than as the way out of Ravilo.')
    + homeScrolled({ b: B.standard, entry: 'B', focus: 'nav' }), 'ha-stack');
  frame('compare-grid5-vs-grid6',
    band('Five across — the brief\'s lean',
         '160 × 90 dp banners. Eighteen entries make four rows and the screen holds three of them: '
         + '15 of 18 are in frame and Twitch, VLC and YouTube are below the fold, so Apps scrolls '
         + 'from the first frame.')
    + appsScreen({ b: B.slim, entry: 'A', layout: L.grid5, focusIndex: 0 })
    + band('Six across — the whole TV in one view',
           '131 × 73 dp banners. Three rows of six is every entry on one screen: no scrolling, no '
           + 'focus memory to get wrong, and nothing below the fold.')
    + appsScreen({ b: B.slim, entry: 'A', layout: L.grid6, focusIndex: 0 }), 'ha-stack');
  frame('bar-geometry', barSpec(), 'ha-spec-frame');
  frame('bar-width-arithmetic', widthSpec(), 'ha-spec-frame');
  frame('layout-arithmetic', layoutSpec(), 'ha-spec-frame');
  frame('first-tile-label', labelSpec(), 'ha-spec-frame');

  document.getElementById('sheet').innerHTML = FRAMES.join('\n');
  runWidthMeasurements();
})();
