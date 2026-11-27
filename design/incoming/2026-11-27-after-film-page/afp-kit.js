/* afp-kit.js — every frame this round renders, built from afp-data.js.
   Round slug `after-film-page`, 2026-11-27.

   The page: when the player would close a finished film, this opens instead. Up to six unwatched
   titles from that film's own More Like This list; picking one opens that title's page and never
   plays it; one Back leaves to the finished film's page. Nothing plays and nothing loads a stream
   until the viewer picks, so there is no player, no countdown and no autoplay anywhere below.

   The builders use the SHIPPED class names of each surface, because the shipped stylesheet is
   linked rather than copied (PROCESS.md step 3):
     TV       ravilo.css          .hero-bg .hero-scrim .hero-kicker .dhero-title .crow .crow-head
                                  .track .tile.poster .art .grad .tt .label .sub .focused .appbar
     phone    ravilo-phone.css    .row h3 .track .tile .poster .ini .tlabel .tsub .dtitle
     desktop  desktop-directions  .win .gn .gside.rail .seg.v .nav .main .pad .h1 .row .po .bt
              + ravilo-desktop.css .dx-win
   So every token, radius, weight and font in these pictures is the production one, and a reviewer
   comparing a picture with the stylesheet is comparing it with the same bytes. Only the page's own
   composition is new, and every rule of it is `afp-`-prefixed in after-film.css.

   Nothing here invents a string, a title, a year, a count or a width: all of them come from
   window.AFP in afp-data.js.
*/
(function () {
  'use strict';

  var D = window.AFP;
  var R = window.RAVILO;          /* grad() / initials() — the shipped corpus's own functions, so
                                     these seven titles sit in the same colour world as every
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
     VISIBLE frame, and it does not reproduce when that frame is opened alone (PROCESS.md). The
     logo stand-in below defines a clip, so the counter is load-bearing here and not a formality.
     ------------------------------------------------------------------------------------------ */
  var svgSeq = 0;
  function svgId(stem) { return 'afp-' + stem + '-' + (++svgSeq); }

  /* =============================================================================================
     THE IDENTITY — the round's QUESTION, written once and read on every surface.

       mode 'text' = option (a): the film's title as text.
       mode 'logo' = option (b): its logo art.

     A logo file is a PICTURE, and the corpus has no artwork for an invented title, so option (b)
     draws a wordmark stand-in: the title set in Space Grotesk 700 inside an <svg> that behaves
     like an <img> (fixed height, width from the intrinsic ratio), exactly as the shipped
     `.dhero-logo` does. JUDGE THE PLACEMENT AND THE SIZE, NOT THE ARTWORK — a real logo is
     wider, looser and often not a wordmark at all, and REPORT.md says so.

     The size is this page's own, not the title page's: `.dhero-logo` is 116 px tall because on the
     title page the identity IS the subject. Here the subject is the row, so the logo is drawn at
     GEO.tv.logoAfter (96 px) and the text at 56 px rather than `.dhero-title`'s 72.

     The two numbers are not the same because they are not measuring the same thing. A logo's
     height includes its mark, its rules and whatever line sits under the word; only part of it
     is the word. The first render drew the lockup at 72 px, where its wordmark came out a third
     shorter than the text option's and would have lost at three metres on a difference nobody
     was asking about. At 96 px the WORD is about 42 px tall against the text option's ~40 px cap
     height, so the two options are drawn at the same legibility and differ only in character.
     Only the two PNGs side by side showed it.
     ============================================================================================= */
  function logoSvg(title, px) {
    var clip = svgId('logoclip');
    /* THE FIRST RENDER OF THIS ROUND DREW THE LOGO AS THE TITLE IN CAPS WITH A SMALL RULE, AND
       THE TWO OPTIONS CAME OUT ALL BUT IDENTICAL — which would have put a question in front of
       the owner that his own pictures could not answer. Only looking at the two PNGs side by side
       showed it. A real film logo is ARTWORK: wide-tracked custom lettering, its own proportions,
       usually a mark, and a silhouette that is nothing like the UI's display face set as a line
       of text. The stand-in below is drawn that way — a centred lockup, 2.4 : 1 wider than the
       text option at the same height, rules either side of a mark — so the pictures differ by the
       thing being asked about.

       JUDGE THE PLACEMENT AND THE SIZE, NOT THE ARTWORK. The corpus has no logo file for an
       invented title; this is a drawing standing in for one, and REPORT.md says so.

       650x150 is the box `.dhero-logo`'s max-width/height pair implies at this page's height; the
       whole thing is then scaled to `px` by CSS, so a longer title shrinks rather than
       overflowing — which is what an <img> with max-width would do. */
    var t0 = esc(title.toUpperCase());
    return '<svg class="afp-logo" style="height:' + px + 'px" width="650" height="150"'
      + ' viewBox="0 0 650 150" role="img" aria-label="' + esc(title) + '">'
      + '<defs><clipPath id="' + clip + '"><rect x="0" y="0" width="650" height="150"/></clipPath></defs>'
      + '<g clip-path="url(#' + clip + ')" fill="#ffffff">'
      /* the mark and its rules — a title card's lockup, not a line of UI type.
         LEFT-ANCHORED, not centred. The first render centred the lockup inside its own box, and
         magnifying the PNG showed the wordmark starting 70 px to the right of the kicker above it
         and the heading below it — three things that should share one edge, on three different
         ones. A logo file that is tightly cropped and placed with the shipped `.dhero-logo`'s
         `object-position: left bottom` lands on the gutter; this drawing has to do the same, or
         the picture would be arguing against option (b) with a drafting slip. */
      + '<rect x="0" y="25" width="86" height="2" opacity=".75"/>'
      + '<rect x="116" y="25" width="86" height="2" opacity=".75"/>'
      + '<path d="M101 16l9 10-9 10-9-10z" opacity=".9"/>'
      + '<text x="0" y="104" font-family="Space Grotesk, sans-serif"'
      + ' font-size="66" font-weight="500" letter-spacing="14" fill="#ffffff">' + t0 + '</text>'
      + '<text x="3" y="134" font-family="Sora, sans-serif"'
      + ' font-size="15" font-weight="600" letter-spacing="10" opacity=".66"'
      + ' fill="#ffffff">A FILM</text>'
      + '</g></svg>';
  }

  /* The kicker is the shipped `.hero-kicker`: uppercase, tracked 2 px, `--accent-2`, with the
     year in `--ink-soft` through its own `.n`. In Noir the accent is amber and the kicker takes
     it, which is why Noir is drawn rather than assumed. */
  function identity(o) {
    var f = D.FILM;
    /* The kicker is the two words and nothing else. The first render put the film's YEAR beside
       them through the shipped `.hero-kicker .n`, as the title page does — and "END OF FILM 2024"
       reads as a date on the ending rather than the film's year. The title page earns that number
       because it is deciding whether to watch; a viewer who has just finished the film does not
       need it. One fewer thing in the frame, and the brief asks for the kicker and the title. */
    var h = '<div class="hero-kicker"><span>' + esc(t('player.end_of_movie')) + '</span></div>';
    if (o.mode === 'logo') h += logoSvg(f.title, o.logoPx);
    else h += '<div class="afp-ttl" style="font-size:' + o.textPx + 'px">' + esc(f.title) + '</div>';
    return '<div class="afp-ident' + (o.mode === 'logo' ? ' logo' : '') + '">' + h + '</div>';
  }

  /* =============================================================================================
     TV — Android TV, 1920x1080, drawn and measured at 1920 (SKILL-house).
     ============================================================================================= */

  function tvAppbar() {
    /* Only the `appbar` variant uses this. The recommended layout has no app bar; REPORT.md says
       why, and the variant exists so the choice is a picture rather than an argument. */
    return '<div class="appbar">'
      + '<div class="brand"><span class="wm">Ravilo</span></div>'
      + '<div class="topnav">'
      +   '<div class="navitem">Home</div>'
      +   '<div class="navitem cur">Library</div>'
      +   '<div class="navitem">Discover</div>'
      +   '<div class="navitem">Search</div>'
      + '</div>'
      + '<div class="right"><span class="clock">' + esc(D.CLOCK) + '</span>'
      +   '<span class="avatar">ER</span></div>'
      + '</div>';
  }

  /* A TV tile. The shipped `.tile.poster` — 210 px wide, 2/3 art, label + sub beneath — and the
     shipped `.focused`: scale(1.07) plus the 4 px `--ring`. Focus is a CLASS on this surface, not
     a pseudo-class: a D-pad has no hover (SKILL-house). Exactly one tile in a frame carries it,
     per the constitution's "always exactly one visible focus target".

     No poster artwork exists for an invented title, so the art is RAVILO.grad() with the title set
     across it through the shipped `.tt` — the same no-artwork tile every other Ravilo mockup
     draws, which doubles as this round's "picture has not arrived" state. */
  function tvTile(p, focused) {
    return '<div class="tile poster' + (focused ? ' focused' : '') + '">'
      + '<div class="art"><div class="grad" style="background:' + R.grad(p.title) + '"></div>'
      +   '<div class="tt">' + esc(p.title) + '</div></div>'
      + '<div class="label">' + esc(p.title) + '</div>'
      + '<div class="sub">' + p.year + '</div>'
      + '</div>';
  }

  function tvRow(picks, focusIndex) {
    var tiles = picks.map(function (p, i) { return tvTile(p, i === focusIndex); }).join('');
    return '<div class="crow active">'
      + '<div class="crow-head"><h2>' + esc(t('section.related')) + '</h2></div>'
      + '<div class="track">' + tiles + '</div>'
      + '</div>';
  }

  function tvFrame(o) {
    var picks = o.two ? D.PICKS_TWO : (o.picks || D.PICKS);
    var g = D.GEO.tv;
    var el = document.createElement('div');
    el.className = 'afp-tv afp-frame';
    el.id = o.id;

    /* The backdrop and the TITLE PAGE'S OWN SCRIM, reused as the brief requires: `.hero-scrim` is
       `--hero-tint` (a left-to-right wash) over `--hero-floor` (a bottom-up wash), both skin
       tokens. The page's content sits bottom-left, which is where that pair is darkest — the same
       composition the title page uses, for the same reason. */
    var bg = '<div class="hero-bg"><div class="afp-art" style="background:' + R.grad(D.FILM.title)
      + '"></div><div class="hero-scrim"></div></div>';

    /* The empty case: the list has nothing unwatched left. "Up to 6" includes 0, the brief names
       no such state, and the spec excerpt does not say what the page does — so it is DRAWN and
       put to Bárður as a question rather than decided here (REPORT.md, open questions). This
       frame is what the page would look like if it opened with nothing to offer. */
    var body = o.empty
      ? '<div class="afp-stack">' + identity({ mode: o.mode, logoPx: g.logoAfter, textPx: 56 })
        + '<div class="crow"><div class="crow-head"><h2>' + esc(t('section.related'))
        + '</h2></div><div class="afp-empty">— nothing drawn: this state has no words yet —</div>'
        + '</div></div>'
      : '<div class="afp-stack">'
        + identity({ mode: o.mode, logoPx: g.logoAfter, textPx: 56 })
        + tvRow(picks, 0)
        + '</div>';

    el.innerHTML = bg + (o.appbar ? tvAppbar() : '') + body;
    return el;
  }

  /* =============================================================================================
     PHONE — 412x915 portrait and the same device rotated, 915x412. The screen itself, with no
     bezel: the bezel is mockup chrome and the Compose port measures the screen.

     The film was just watched FULLSCREEN, which on a phone is landscape — so landscape is the
     case this page actually opens in, and it is drawn first. Portrait is drawn because a phone
     held upright is the default everywhere else in the family (R244).

     A pointer/touch surface has no Back key, so a visible back control is drawn here as well as
     on desktop and web. The shipped `.dback` is a `backdrop-filter` circle, which breaks the
     screenshot render (PROCESS.md); this is the same circle with a flat fill, and REPORT.md says
     so rather than letting the picture imply the glass is gone.
     ============================================================================================= */

  function phStrip(landscape) {
    return '<div class="afp-phstrip' + (landscape ? ' land' : '') + '">'
      + '<span>' + esc(D.CLOCK) + '</span><span class="afp-phsig">LTE ▮▮▮ 86%</span></div>';
  }

  function phBack() {
    return '<button class="afp-phback" aria-label="' + esc(t('action.back')) + '">←</button>';
  }

  function phTile(p) {
    return '<div class="tile">'
      + '<div class="poster" style="background:' + R.grad(p.title) + '">'
      +   '<span class="ini">' + esc(R.initials(p.title)) + '</span></div>'
      + '<div class="tlabel">' + esc(p.title) + '</div>'
      + '<div class="tsub">' + p.year + '</div>'
      + '</div>';
  }

  function phFrame(o) {
    var picks = o.two ? D.PICKS_TWO : (o.picks || D.PICKS);
    var land = !!o.land;
    var tiles = picks.map(phTile).join('');

    /* Two phone treatments for the row, and the difference only matters in PORTRAIT:
         'track' — the shipped horizontally scrolling `.track`. Consistent with every other row
                   in the app, and at 412 it shows three of the six.
         'grid'  — a 2-up grid, `.afp-pgrid`. All six visible at once with no scrolling.
       In LANDSCAPE all six fit a single `.track` (see the fit study), so the two treatments draw
       the same picture and only `track` is rendered there. */
    var row = (o.grid && !land)
      ? '<div class="afp-pgrid">' + tiles + '</div>'
      : '<div class="track">' + tiles + '</div>';

    var el = document.createElement('div');
    el.className = 'afp-ph afp-frame' + (land ? ' land' : '');
    el.id = o.id;
    el.innerHTML = '<div class="afp-phbg" style="background:' + R.grad(D.FILM.title) + '"></div>'
      + '<div class="afp-phscrim"></div>'
      + phStrip(land)
      + phBack()
      + '<div class="afp-phbody">'
      +   identity({ mode: o.mode, logoPx: land ? 52 : 60, textPx: D.GEO.phone.title })
      +   '<div class="row"><h3>' + esc(t('section.related')) + '</h3>' + row + '</div>'
      + '</div>';
    return el;
  }

  /* =============================================================================================
     DESKTOP AND RAVILO WEB — the shipped window with the sidebar RAIL (84 px), at 1280 wide, and
     Ravilo Web at the brief's 900. Both clients share ravilo-ui's pages, so one builder serves
     both and the width is what differs.

     The back control is the brief's lean: a ghost `Back` top-left, which on this surface is the
     shipped `.bt` (38 px, radius 10, chip fill) and not a new component.
     ============================================================================================= */

  var IC = {
    menu:  'M3 6h14M3 10h14M3 14h14',
    film:  'M3 4h14v12H3zM3 8h14M3 12h14M7 4v12M13 4v12',
    note:  'M7 15V5l8-2v10M7 15a2 2 0 11-4 0 2 2 0 014 0zM15 13a2 2 0 11-4 0 2 2 0 014 0z',
    search:'M9 3a6 6 0 100 12A6 6 0 009 3zM13.5 13.5L17 17',
    home:  'M3 9l7-6 7 6v8H3z',
    comp:  'M10 2a8 8 0 100 16 8 8 0 000-16zM13 7l-2 4-4 2 2-4z',
    mark:  'M5 3h10v14l-5-4-5 4z',
    tv:    'M3 5h14v9H3zM7 17h6',
    back:  'M12 4l-6 6 6 6'
  };
  function ic(k) {
    return '<svg class="ic" viewBox="0 0 20 20" fill="none" stroke="currentColor"'
      + ' stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="'
      + IC[k] + '"/></svg>';
  }

  /* The shipped rail, reproduced from railHTML() in design/ravilo/desktop/ravilo-desktop.js: the
     GNOME head, the vertical `.seg.v` mode switch, Search, then the nav with its `<hr>` between
     the two groups, then the avatar pinned to the bottom. Nothing in the rail is this round's. */
  function deskRail() {
    return '<aside class="gside rail">'
      + '<div class="ghb" style="justify-content:center;padding:0">'
      +   '<span class="gb">' + ic('menu') + '</span></div>'
      + '<div class="seg v"><b class="on">' + ic('film') + '</b><b>' + ic('note') + '</b></div>'
      + '<div class="nav"><a>' + ic('search') + '<span>Search</span></a></div>'
      + '<div class="nav">'
      +   '<a>' + ic('home') + '<span>Home</span></a>'
      +   '<a>' + ic('comp') + '<span>Discover</span></a>'
      +   '<a>' + ic('mark') + '<span>My List</span></a>'
      +   '<hr>'
      +   '<a class="on">' + ic('film') + '<span>Films</span></a>'
      +   '<a>' + ic('tv') + '<span>Series</span></a>'
      + '</div>'
      + '<span class="av" style="margin:auto auto 4px">E</span>'
      + '</aside>';
  }

  function deskPoster(p, w) {
    var st = 'aspect-ratio:2/3;border-radius:10px;background:' + R.grad(p.title)
      + ';display:flex;align-items:center;justify-content:center;'
      + 'font-family:\'Space Grotesk\',sans-serif;font-weight:700;font-size:22px;'
      + 'color:rgba(255,255,255,.86)';
    return '<div class="po"' + (w ? ' style="width:' + w + 'px"' : '') + '>'
      + '<div style="' + st + '">' + esc(R.initials(p.title)) + '</div>'
      + '<div class="afp-pol">' + esc(p.title) + '</div>'
      + '<div class="afp-pos">' + p.year + '</div>'
      + '</div>';
  }

  function deskFrame(o) {
    var picks = o.two ? D.PICKS_TWO : (o.picks || D.PICKS);
    var g = D.GEO.desk;
    var size = o.web ? g.web : g.window;

    /* The one measured decision on this surface. At 1280 the row's box is
       1280 − 84 rail − 2×28 pad = 1140, and six shipped `.po` at 138 plus five 14 px gaps need
       898 — it fits untouched. At the brief's 900 the box is 760 and the same row needs 898, so
       it does NOT fit. `fitW` divides the box between exactly six posters instead of letting the
       row scroll: this page HAS six items and never more, so a scroller would hide part of the
       only choice the page exists to offer. The `scroll` variant draws the alternative, at the
       shipped 138, so the cost of each is a picture. */
    var box = size[0] - g.rail - 2 * g.pad;
    var need = picks.length * g.po + (picks.length - 1) * g.poGap;
    var fitW = (o.scroll || need <= box)
      ? 0
      : Math.floor((box - (picks.length - 1) * g.poGap) / picks.length);

    var el = document.createElement('div');
    el.className = 'afp-desk afp-frame';
    el.id = o.id;
    el.innerHTML = '<div class="win gn dx-win afp-win' + (o.light ? ' lt' : '') + '"'
      + ' style="width:' + size[0] + 'px;height:' + size[1] + 'px">'
      + deskRail()
      /* THE SHIPPED WINDOW HEADER, and the round's answer to the brief's second question.
         `header()` in design/ravilo/desktop/ravilo-desktop.js already puts a `.ghb center` bar at
         the top of `.main` with a back chevron whenever the navigation stack is not empty — so a
         pointer client does NOT lack a back control, and the brief's lean would add a SECOND one
         to a page that has one. The `header` variant draws the shipped bar and no page control;
         the default draws the brief's lean so the two are a picture rather than an argument.
         Neither is free: the bar costs the backdrop its top 46 px and the page its full bleed. */
      + '<div class="main">'
      +   (o.header
        ? '<div class="ghb center"><span class="gb">' + ic('back') + '</span>'
          + '<span class="tt">' + esc(D.FILM.title) + '</span><span class="sp"></span></div>'
        : '')
      +   '<div class="afp-page">'
      +   '<div class="afp-dbg" style="background:' + R.grad(D.FILM.title) + '"></div>'
      +   '<div class="afp-dscrim"></div>'
      +   (o.header ? '' : '<button class="bt afp-dback">' + esc(t('action.back')) + '</button>')
      +   '<div class="afp-dstack">'
      +     '<div class="pad">'
      +       identity({ mode: o.mode, logoPx: 62, textPx: 38 })
      +       '<div class="h1 afp-h1">' + esc(t('section.related')) + '</div>'
      +     '</div>'
      +     '<div class="pad"><div class="row' + (o.scroll ? ' afp-scroll' : '') + '">'
      +       picks.map(function (p) { return deskPoster(p, fitW); }).join('')
      +     '</div></div>'
      +   '</div>'
      + '</div></div></div>';
    return el;
  }

  /* =============================================================================================
     THE FIT STUDY. Every bar is COMPUTED from GEO in afp-data.js — tile width times the count,
     plus the gaps — and compared with the box the row has to sit in. A number in REPORT.md can
     then be checked against a picture instead of trusted. Dark ink on the light canvas backdrop
     (PROCESS.md), so it is readable where it is drawn.
     ============================================================================================= */
  function fitFrame(id) {
    var el = document.createElement('div');
    el.className = 'afp-study afp-frame';
    el.id = id;
    var html = '<h3>Does a row of <u>six</u> fit?</h3>'
      + '<p>The page offers up to six titles and never more, so whether six fit in one row is the '
      + 'whole layout question on every surface. Each bar is six tiles at that surface’s shipped '
      + 'tile width plus five shipped gaps, against the box the row actually has: the stage less '
      + 'its gutters on TV, the screen less the track’s padding on the phone, and the window less '
      + 'the 84&nbsp;px rail and the 28&nbsp;px page padding on desktop and Ravilo Web. A red bar '
      + 'does not fit, and the segments past the dashed edge are the titles the viewer would have '
      + 'to scroll to see.</p>';

    D.FIT.forEach(function (row) {
      var need = row.n * row.w + (row.n - 1) * row.gap;
      var over = need > row.limit;
      var scale = Math.min(1, 1060 / Math.max(row.limit, need));
      var segs = [];
      for (var i = 0; i < row.n; i++) {
        if (i) segs.push('<span class="afp-gap" style="width:' + (row.gap * scale) + 'px"></span>');
        segs.push('<span class="afp-seg' + (i % 2 ? ' alt' : '') + '" style="width:'
          + (row.w * scale) + 'px">' + (i + 1) + '</span>');
      }
      html += '<div class="afp-srow">'
        + '<div class="afp-skey">' + esc(row.surface) + ' · ' + esc(row.note)
        +   ' · <b>' + need + ' / ' + row.limit + ' px</b>'
        +   (over ? ' · <u>does not fit, by ' + (need - row.limit) + ' px</u>'
                  : ' · fits, ' + (row.limit - need) + ' px spare') + '</div>'
        + '<div class="afp-box' + (over ? ' over' : '') + '" style="width:'
        +     Math.round(row.limit * scale) + 'px">'
        +   '<div class="afp-fill' + (over ? ' over' : '') + '">' + segs.join('') + '</div>'
        + '</div></div>';
    });
    el.innerHTML = html;
    return el;
  }

  window.AFPKit = {
    setLang: setLang, t: t,
    tv: tvFrame, phone: phFrame, desk: deskFrame, fit: fitFrame
  };
})();
