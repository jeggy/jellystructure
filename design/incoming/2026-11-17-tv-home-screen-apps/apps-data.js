/* Ravilo as the TV's home screen — the round's one source of numbers and strings.
 *
 * PROCESS.md: "Derive every number in every picture from one array." Every px the frames draw
 * comes from here: the two shipped bars, the shipped screen geometry they sit in, the three Apps
 * layouts, the two header directions, and the stand-in data the brief named exactly.
 *
 * UNITS. Geometry is **dp**, type is **sp**, exactly as the Compose source states them — never px.
 * A TV is 960 x 540 dp on the 1920 x 1080 stage, so PX_PER_DP = 2.
 *
 * PROVENANCE. Every SHIPPED and BARS number below was read out of the Kotlin on 2026-11-17, not out
 * of design/ravilo/ravilo.css (whose `.appbar` is still a 46 dp drawing of a 60 dp bar — the defect
 * the slim-header round raised and nobody has landed yet):
 *   - BARS                     theme/Dimens.kt, AppBarGeometry.Standard / .Slim (DEB-3967 FR-9)
 *   - BARS[*].ringGap          components/AppBar.kt, BAR_RING_OUTSET = 4.dp, "Both bars"
 *   - SHIPPED paddings, gaps   theme/Dimens.kt, RaviloDimens
 *   - PILL                     components/SurpriseMe.kt, SurprisePill — the bar's own pill precedent
 *   - SIGNIN                   screens/TvSignInScreen.kt, in full
 *   - BTN                      components/RaviloButton (ButtonStyle.PRIMARY / .GHOST)
 */
(function () {
  'use strict';

  var PX_PER_DP = 2;            // 1920 px stage / 960 dp screen
  var SCREEN_W = 960;           // dp
  var SCREEN_H = 540;           // dp

  /* ---------------------------------------------------------------------------
   * The two shipped bars. The brief asks for the Apps entry in BOTH, so neither
   * is "the" bar here and nothing below prefers one.
   * ------------------------------------------------------------------------- */
  var BARS = {
    standard: {
      key: 'standard', label: 'Standard', note: '60 dp — the shipped default',
      height: 60,
      mark: 26, wordmark: 22, wordmarkTrack: -1, brandGap: 8,
      navSp: 15, navPadH: 12, navPadV: 6, navRadius: 11,
      itemGap: 18,
      circle: 40, glyph: 22, initials: 14, clock: 15,
      /* AppBar.kt: a 2 dp stroke held 2 dp clear of the fill, outer edge 4 dp out. BOTH bars —
       * the slim-header round's proposal shipped and Standard got it too. */
      ring: 2, ringGap: 2,
    },
    slim: {
      key: 'slim', label: 'Slim', note: '48 dp — the server-set alternative',
      height: 48,
      mark: 20, wordmark: 17, wordmarkTrack: -0.8, brandGap: 7,
      navSp: 14, navPadH: 11, navPadV: 5, navRadius: 9,
      itemGap: 14,
      circle: 32, glyph: 18, initials: 12, clock: 13,
      ring: 2, ringGap: 2,
    },
  };

  /* ---------------------------------------------------------------------------
   * The screen the entry and the Apps grid sit in. Shipped values only.
   * ------------------------------------------------------------------------- */
  var SHIPPED = {
    padH: 48,               // RaviloDimens.screenPadH
    rowGap: 36,             // RaviloDimens.rowGap
    trackPadV: 20,          // RaviloDimens.trackPadV
    itemSpacing: 16,        // RaviloDimens.itemSpacing — the gap between tiles, here and on Home
    rowHeadPadB: 10,        // RaviloDimens.rowHeadPadB
    pageTitleGap: 24,       // DiscoverScreen: Spacer(appBarHeight + 24.dp)

    heroHeight: 460,        // HeroCarousel heightDp
    heroBodyBot: 44,        // RaviloDimens.heroBodyBot
    heroBodyW: 560,

    rowTitleSp: 18, rowTitleTrack: -0.3, seeAllSp: 15,
    poster: { w: 155, h: 232 },   // Tile.kt POSTER_W / POSTER_H
    land: { w: 256, h: 144 },     // Tile.kt LANDSCAPE_W / LANDSCAPE_H
    tileLabelSp: 18, tileSubSp: 14, tileLabelGap: 8,
    tileRing: 3, tileRadius: 6, tileFocusScale: 1.07,

    /* Content size = COMPACT on prod (RaviloConfig.uiDensity): every CONTENT tile is x 0.82. The
     * Apps grid is NOT a content row and does not take this — see the report. */
    tileScale: 0.82,
    tilesPerRow: 9, landPerRow: 6,

    heroKickerSp: 11, heroTaglineSp: 14, heroTitleSp: 44, heroMetaSp: 13,
    heroChipSp: 12, heroRatingSp: 15, heroSynSp: 14,

    /* DiscoverScreen's page head, which the Apps screen reuses rather than inventing one. */
    pageTitleSp: 22, pageSubSp: 13, pageTitleGapInner: 12,
  };

  /* RaviloButton — the shipped button, used on the sign-in and can't-reach footers. */
  var BTN = {
    minH: 44, radius: 10, padH: 18, padV: 8, sp: 15,
    ring: 2, ringOutset: 5, gap: 14,     // TvSignInScreen footer: spacedBy(14.dp)
  };

  /* SurprisePill (components/SurpriseMe.kt) — the only pill the TV bar already has, and therefore
   * the measurements direction B's pill has to borrow rather than invent:
   *   shape   RoundedCornerShape(navCorner + 6.dp)
   *   border  1 dp textSecondary at 0.4 (0 when focused)
   *   padding horizontal navPadH + 2, vertical navPadV
   *   gap     7 dp, glyph navType + 2 dp, label navType SemiBold
   */
  var PILL = { radiusAdd: 6, border: 1, padHAdd: 2, gap: 7, glyphAdd: 2 };

  /* ---------------------------------------------------------------------------
   * The three Apps layouts. Every one is 960 dp wide inside the 48 dp gutter, so
   * the tile width is a consequence of the across-count and nothing is eyeballed.
   *
   *   tileW = (SCREEN_W - 2*padH - (across-1)*itemSpacing) / across
   *   tileH = tileW * 9/16          (an Android TV banner is 16:9 — 320x180 px xhdpi)
   * ------------------------------------------------------------------------- */
  function gridLayout(across) {
    var inner = SCREEN_W - 2 * SHIPPED.padH;
    var w = (inner - (across - 1) * SHIPPED.itemSpacing) / across;
    return {
      kind: 'grid', across: across,
      tileW: w, tileH: w * 9 / 16,
      rowGap: 24,
      radius: 8,                 /* a banner is a 16:9 plate; the 12 dp content-tile radius eats
                                  * a banner's own corner artwork, and 6 dp reads as square. */
      /* FR-11: a banner, OR an icon and a label where the app has none. NO LABEL UNDER A BANNER —
       * the first render drew one under every cell and every banner tile then said its own name
       * twice, half a second apart in the eye. The fallback's icon and label sit INSIDE the cell,
       * which is both what the spec describes and what keeps every cell exactly 16:9. */
      fbIcon: 0.46, fbLabelSp: 13, fbGap: 7,
    };
  }
  var LAYOUTS = {
    grid5: gridLayout(5),
    grid6: gridLayout(6),
    /* Direction C, the brief's "if a list reads better at 10 ft". An icon-and-label row, where the
     * icon is the neutral square Android ships for an app with no TV banner — so EVERY app is drawn
     * the way the two fallback apps have to be drawn anyway, and nothing depends on a banner being
     * legible.
     *
     * THREE columns, and the arithmetic is why. A single column of 18 rows was ruled out before it
     * was drawn: at a 10-foot row height it shows 5 of 18 and scrolls three times, which gives up
     * the one thing a list is meant to win. Three columns of six rows is 372 dp of the 407 dp a
     * Slim bar leaves, so direction C shows all eighteen in one view, like grid6 and unlike grid5. */
    list: {
      kind: 'list', cols: 3, colGap: 24,
      rowH: 56, rowGapV: 6, iconSize: 40, iconRadius: 9, gap: 16,
      labelSp: 19, rowRadius: 10,
    },
  };

  /* ---------------------------------------------------------------------------
   * The stand-in data, exactly as the brief names it.
   *
   * These app names are GENERIC STAND-INS for "an app with a TV launcher entry", not anyone's
   * installed apps: every instance ships this screen and the list is read off whatever TV it runs
   * on. No real logo, wordmark, artwork or brand colour is drawn — every banner is a flat plate in
   * a colour DERIVED FROM THE NAME'S HASH (see `plate`), which is deliberately arbitrary.
   * ------------------------------------------------------------------------- */
  var APPS = [
    { name: 'Apple TV' },
    { name: 'BBC iPlayer' },
    { name: 'Disney+' },
    { name: 'Downloader', banner: false },   // icon + label fallback
    { name: 'DR TV' },
    { name: 'Google Play Store' },           // the round's longest app label
    { name: 'HBO Max' },
    { name: 'Kodi' },
    { name: 'Netflix' },
    { name: 'Plex' },
    { name: 'Prime Video' },
    { name: 'SkyShowtime' },
    { name: 'Spotify' },
    { name: 'Twitch' },
    { name: 'VLC', banner: false },          // icon + label fallback
    { name: 'YouTube' },
  ];

  /* FR-11's order: Android settings first, the stock home second, then the apps A-Z. The two fixed
   * entries are drawn as tiles in the same grid rather than as a separate band — see the report. */
  function appsList(lang, settingsLabel, onlyFixed) {
    var L = STR[lang || 'en'];
    var fixed = [
      { name: L[settingsLabel || 'tile_tv_settings'], glyph: 'gear', fixed: true, key: 'settings' },
      /* FR-5: labelled with the stock home's own name as Android reports it. On the Streamer that
       * is "Google TV", and it is a proper noun in every language. */
      { name: 'Google TV', glyph: 'home', fixed: true, key: 'stockhome' },
    ];
    if (onlyFixed) return fixed;
    return fixed.concat(APPS);
  }

  /* The brief's header stand-ins, exactly. */
  var NAV_BRIEF = ['Home', 'Movies', 'Series', 'Music'];
  var AVATAR = 'M';
  var CLOCK = '20:41';

  /* The SHIPPED tab set, for the one frame that answers "does the entry still fit?" honestly.
   * raviloNavItems() is Home / Movies / Series / Discover and there is no `nav.music` key at all.
   * `Discover` is 3 characters longer than `Music`, so the brief's set understates the width the
   * entry has to fit into. See the report. */
  var NAV_SHIPPED = { en: ['Home', 'Movies', 'Series', 'Discover'],
                      da: ['Hjem', 'Film', 'Serier', 'Opdag'],
                      fo: ['Heim', 'Filmar', 'Seriur', 'Uppdaga'] };

  var SIGNIN = {
    /* TvSignInScreen.kt, in full. Padding 32 x 28; the brand row is 46 dp; the footer is 84 dp. */
    padH: 32, padV: 28, brandRowH: 46, brandMark: 22, brandSp: 16,
    bodyMaxW: 530,            // widthIn(max = 530.dp) with a QR
    titleSp: 29,
    codeLabelSp: 11, codeLabelTrack: 0.7,
    codeSp: 56, codeTrack: 7,       // with a QR; 68 sp without one
    howSp: 15, howLine: 22, howMinH: 44,
    chipRadius: 7, chipPadH: 11, chipPadV: 6, chipGap: 7,
    chipKeySp: 9, chipKeyTrack: 0.5, chipValSp: 13,
    qrSide: 222, qrRadius: 10, qrGap: 36,
    footerH: 84,
    backHintSp: 10,
    gap1: 15, gap2: 4, gap3: 13, gap4: 13,
    /* The brief's stand-ins. */
    code: 'K7Q-4XN',
    host: 'ravilo.example.net',
    tvName: 'Stue',           // deviceDisplayName() — invented, already the corpus's room name
    unreachableSp: 24,
  };

  /* ---------------------------------------------------------------------------
   * The Home screen behind the bar. The corpus, reused rather than re-invented
   * (SKILL-house: "Reuse a name before inventing one") — these are the same
   * strings design/ravilo/ravilo-data.js and the slim-header round use.
   * ------------------------------------------------------------------------- */
  var TITLES = ['Heimferð', 'Brotsjór', 'Nýggj Spor', 'Arvur',
                'Hraðar Ljós II', 'Nordvest', 'Havets Hjarta'];
  var ROWS = [
    { key: 'continue', title: 'Continue Watching', kind: 'land' },
    { key: 'nordic', title: 'Nordic Noir', kind: 'poster' },
    { key: 'new', title: 'Newly Added Movies', kind: 'poster' },
    { key: 'drama', title: 'Drama', kind: 'poster' },
  ];
  var CONTINUE = [
    { title: 'Nordvest', ep: 'S03E01 · 22 min left', pct: 62 },
    { title: 'Havets Hjarta', ep: 'S03E06 · Next up', pct: 0 },
    { title: 'Arvur', ep: 'S02E01 · Next up', pct: 0 },
    { title: 'Hraðar Ljós II', ep: '38 min left', pct: 41 },
    { title: 'Heimferð', ep: 'S01E02 · 11 min left', pct: 78 },
    { title: 'Brotsjór', ep: 'Next up', pct: 0 },
  ];
  var HERO = {
    title: 'Heimferð', kicker: 'FEATURED', tagline: 'Kringvarp Original',
    year: 2023, cert: '16', rating: '8.1', genre: 'Crime · Drama · Mystery',
    syn: 'A detective comes home to the town she left, and to a death the town has agreed not to talk about.',
  };
  /* The music pill's stand-in, for the crowded-bar frame only. */
  var MUSIC_NOW = { track: 'Vágatunnilin', artist: 'Eyð Durhuus' };

  /* ---------------------------------------------------------------------------
   * Strings. English is the source; da and fo are DRAFTS for every new key and
   * the shipped i18n/*.json value for every key that already exists.
   * SKILL-house: the shipped table wins wherever a string is already there.
   * ------------------------------------------------------------------------- */
  var STR = {
    en: {
      nav: NAV_BRIEF,
      /* NEW. The entry, and the screen's own title. */
      apps: 'Apps',
      apps_sub: 'Open another app, the TV’s settings, or Google TV.',
      apps_nothing_else: 'Nothing else is installed on this TV.',
      /* NEW. Apps opened from sign-in or from can't-reach has no app bar and no tabs, so the one
       * way back is the Back key. The constitution's "Back is always meaningful" wants that said:
       * the shipped sign-in screen already says it with `tvsignin.back_to_profiles`, in this type
       * and this colour, so this is that sentence and not a new device. */
      back_to_signin: 'Back returns to sign-in',
      /* NEW, and the open decision: the lean and the alternative. */
      tile_tv_settings: 'TV settings',
      tile_android_settings: 'Android settings',
      /* SHIPPED (i18n/en.json), quoted. */
      signin_title: 'Sign in on this TV',
      code_label: 'Your code',
      how: 'Scan the code with your phone’s camera, or open Ravilo on a phone or computer and choose Sign in a TV.',
      this_tv: 'This TV',
      password_instead: 'Sign in with a password instead',
      back_to_profiles: 'Back returns to the profiles',
      unreachable: 'Couldn’t reach the server — try again',
      retry: 'Retry',
      /* NEW — the can't-reach screen names the server the brief names. */
      server: 'Server',
      continueRow: 'Continue Watching', seeAll: 'See All',
      surprise: 'Surprise me',
    },
    da: {
      nav: ['Hjem', 'Film', 'Serier', 'Musik'],
      apps: 'Apps',
      apps_sub: 'Åbn en anden app, TV’ets indstillinger eller Google TV.',
      apps_nothing_else: 'Der er ikke installeret andet på dette TV.',
      back_to_signin: 'Tilbage fører til login',
      tile_tv_settings: 'TV-indstillinger',
      tile_android_settings: 'Android-indstillinger',
      signin_title: 'Log ind på dette TV',
      code_label: 'Din kode',
      how: 'Skan koden med telefonens kamera, eller åbn Ravilo på en telefon eller computer og vælg Log et TV ind.',
      this_tv: 'Dette TV',
      password_instead: 'Log ind med kodeord i stedet',
      back_to_profiles: 'Tilbage fører til profilerne',
      unreachable: 'Kunne ikke nå serveren — prøv igen',
      retry: 'Prøv igen',
      server: 'Server',
      continueRow: 'Fortsæt med at se', seeAll: 'Se alle',
      surprise: 'Overrask mig',
    },
    fo: {
      nav: ['Heim', 'Filmar', 'Seriur', 'Tónleikur'],
      /* DRAFT. `Appir` is the plural of `app`, which i18n/lexicon/fo.txt already holds in the
       * singular (`app`, `appina`, `appini`) but not in the plural. A native read is owed. */
      apps: 'Appir',
      apps_sub: 'Lat upp eina aðra app, stillingarnar á sjónvarpinum ella Google TV.',
      apps_nothing_else: 'Einki annað er innlagt á hesum sjónvarpinum.',
      back_to_signin: 'Aftur fer til innritan',
      /* DRAFT, and the round's longest label by a wide margin — 21 characters against 11. */
      tile_tv_settings: 'Sjónvarpsstillingar',
      tile_android_settings: 'Android-stillingar',
      signin_title: 'Rita inn á hesum sjónvarpinum',
      code_label: 'Koda tín',
      how: 'Skanna koduna við kameranum á telefonini, ella lat Ravilo upp á eini telefon ella teldu og vel Rita eitt sjónvarp inn.',
      this_tv: 'Hetta sjónvarpið',
      password_instead: 'Rita inn við loyniorði í staðin',
      back_to_profiles: 'Aftur fer til vangarnar',
      unreachable: 'Kundi ikki ná ambætaranum — royn aftur',
      retry: 'Royn aftur',
      server: 'Ambætari',
      continueRow: 'Halt fram at síggja', seeAll: 'Síggj alt',
      surprise: 'Yvirraska meg',
    },
  };

  /* ---------------------------------------------------------------------------
   * Plates and art.
   *
   * A banner is a flat 16:9 plate in ONE solid colour with the app's name in white type. The
   * colour comes from the name's hash so it is visibly arbitrary rather than a brand's, and the
   * lightness is then DRIVEN DOWN until white type on it clears 4.5:1 — at 10 feet a banner whose
   * own name is unreadable is a blank tile, and a hue-only rule puts yellow and cyan plates well
   * over the line. `whiteContrast` is the check, in the file, so it can be re-run rather than
   * trusted.
   * ------------------------------------------------------------------------- */
  function hash(s) {
    var h = 2166136261;
    for (var i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
    return Math.abs(h);
  }
  function hslToRgb(h, s, l) {
    h /= 360; s /= 100; l /= 100;
    function f(n) {
      var k = (n + h * 12) % 12;
      return l - s * Math.min(l, 1 - l) * Math.max(-1, Math.min(Math.min(k - 3, 9 - k), 1));
    }
    return [f(0), f(8), f(4)];
  }
  function relLum(rgb) {
    var c = rgb.map(function (v) {
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  }
  function whiteContrast(h, s, l) {
    return 1.05 / (relLum(hslToRgb(h, s, l)) + 0.05);
  }
  /* The plate for an app name.
   *
   * The hue is the app's position in the A-Z list spread EVENLY round the wheel, not its name's
   * hash. A hash clusters: the first render gave DR TV, HBO Max, Kodi and Netflix four purples
   * nobody could tell apart, against a brief that asks for "one distinct solid colour" each. An
   * even spread makes every plate distinct by construction, and it is still one rule derived from
   * one list rather than sixteen colours somebody picked.
   *
   * The lightness is then driven DOWN from 42 % until white type on it clears 4.5:1, because a
   * banner whose own name cannot be read at three metres is a blank tile. The saturation still
   * comes from the name, so two apps on neighbouring hues do not look like a gradient.
   */
  var PLATE_ORDER = null;
  function plate(name) {
    if (!PLATE_ORDER) {
      PLATE_ORDER = {};
      APPS.forEach(function (a, i) { PLATE_ORDER[a.name] = i; });
    }
    var i = PLATE_ORDER[name] == null ? hash(name) % APPS.length : PLATE_ORDER[name];
    /* 23 deg of offset so no plate lands on pure red, one even step per app, and a half-turn for
     * every odd one so that two cells side by side in the grid are never neighbours on the wheel.
     * `hueSpread` below proves the sixteen come out distinct — the first version of this line
     * folded two of them onto the same hue and drew Apple TV and Plex the same orange. */
    var h = Math.round(23 + i * (360 / APPS.length) + (i % 2 ? 180 : 0)) % 360;
    var s = 44 + (hash(name) >> 9) % 18;        // 44-61 %
    var l = 42;
    while (l > 10 && whiteContrast(h, s, l) < 4.5) l -= 1;
    return { css: 'hsl(' + h + ' ' + s + '% ' + l + '%)', h: h, s: s, l: l,
             contrast: whiteContrast(h, s, l) };
  }
  /* The smallest gap between any two plate hues, and which pair owns it. A number, not a glance:
   * "one distinct solid colour" each is the brief's words, and the eye is a poor judge of sixteen. */
  function hueSpread() {
    var hs = APPS.filter(function (a) { return a.banner !== false; })
      .map(function (a) { return { n: a.name, h: plate(a.name).h }; })
      .sort(function (x, y) { return x.h - y.h; });
    var worst = { gap: 360, a: null, b: null };
    for (var i = 1; i < hs.length; i++) {
      var g = hs[i].h - hs[i - 1].h;
      if (g < worst.gap) worst = { gap: g, a: hs[i - 1].n, b: hs[i].n };
    }
    return worst;
  }

  /* The neutral rounded square Android draws for an app with no TV banner. Deliberately ONE
   * neutral for both fallbacks: the point of the fallback is that the TV has no artwork to show,
   * and inventing a colour for it would draw a banner the TV does not have. */
  var FALLBACK_ICON = { bg: '#2a2f3e', ink: 'rgba(255,255,255,.82)' };

  /* The corpus's own poster gradient, so a title looks the same here as everywhere else. */
  function grad(s) {
    var h = hash(s);
    var a = h % 360, b = (a + 36 + (h >> 3) % 50) % 360;
    var l1 = 30 + (h >> 5) % 12, l2 = 12 + (h >> 7) % 8;
    return 'linear-gradient(145deg, hsl(' + a + ' 48% ' + l1 + '%), hsl(' + b + ' 52% ' + l2 + '%))';
  }
  function initials(t) {
    return t.replace(/[^A-Za-zÀ-ÿ0-9 ]/g, '').split(/\s+/).slice(0, 2)
      .map(function (w) { return w[0]; }).join('').toUpperCase();
  }

  window.HA = {
    PX_PER_DP: PX_PER_DP, SCREEN_W: SCREEN_W, SCREEN_H: SCREEN_H,
    BARS: BARS, SHIPPED: SHIPPED, BTN: BTN, PILL: PILL, LAYOUTS: LAYOUTS,
    APPS: APPS, appsList: appsList, NAV_SHIPPED: NAV_SHIPPED,
    AVATAR: AVATAR, CLOCK: CLOCK, SIGNIN: SIGNIN,
    TITLES: TITLES, ROWS: ROWS, CONTINUE: CONTINUE, HERO: HERO, MUSIC_NOW: MUSIC_NOW,
    STR: STR,
    plate: plate, whiteContrast: whiteContrast, hueSpread: hueSpread,
    FALLBACK_ICON: FALLBACK_ICON,
    grad: grad, initials: initials,
  };
})();
