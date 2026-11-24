/* swr-kit.js — the builders: two remote drawings, three directions, one screen each.

   Everything the pictures say comes from window.SWR. The two rules this file keeps:

   1. WHICH BODY A STATE GETS IS COMPUTED, never written into the data: `bodyKey()` reads the TV's
      own `appSettings` flag and the viewer's choice, exactly as the shipped guide reads
      `TvFacts.appSettingsMenu`. So no direction can disagree with another about what a 2024 set
      shows, and the four bodies FR-13 writes are four rows of one table rather than four strings
      pasted into three places.
   2. THE TWO REMOTES ARE ONE DRAWING EACH, used at every size. The choice card, the instruction
      illustration and direction B's detail crop are the same geometry re-scaled or re-cropped —
      so the remote the viewer taps and the remote they are then shown cannot be different
      drawings, which is the whole decision this round carries.

   Every SVG id is minted from a counter (PROCESS.md's hidden-subtree trap: Chromium will not
   resolve a paint server defined inside a display:none subtree, and this document holds every
   frame at once). This round defines no paint server at all — every key is a solid fill, every
   outline a stroke in currentColor, and the primary button's gradient is a CSS background — but
   the counter is here so that adding one cannot reintroduce the trap.

   NO PHOTOGRAPH, NO SAMSUNG LOGO, NO MODEL NUMBER appears in either drawing (FR-3). Both are our
   own vectors in the Ravilo theme: the shipped card surface for the body, a 55 % ink outline, and
   the shipped accent for what the viewer presses. The four coloured keys are the only non-theme
   colours, and they are data (SWR.KEYCOLORS) because the viewer matches them against a real
   remote.
*/
(function () {
  'use strict';

  var D = window.SWR;
  var LANG = 'en';
  var SVG_N = 0;
  function uid(p) { SVG_N += 1; return 'swr-' + p + '-' + SVG_N; }

  function setLang(l) { LANG = l; }
  function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function t(key, vars) {
    var s = D.STR[LANG][key];
    if (s === undefined) s = D.STR.en[key];
    if (s === undefined) return key;
    if (vars) Object.keys(vars).forEach(function (k) { s = s.split('{' + k + '}').join(vars[k]); });
    return s;
  }
  /* `**bold**` runs and nothing else — the shipped convention (SamsungGuideScreen's rich()). */
  function rich(s) {
    return esc(s).split('**').map(function (part, i) {
      return i % 2 === 1 ? '<b>' + part + '</b>' : part;
    }).join('');
  }

  /* FR-13's four bodies as one table. `pick` x `appSettings` -> key. */
  function bodyKey(pick, tv) {
    var base = pick === 'keys' ? 'step1.keys_body' : 'step1.nokeys_body';
    return tv.appSettings ? base + '_new' : base;
  }

  /* =================================================================================================
     THE GLYPHS — chrome only. All stroked currentColor, so each takes its control's own tint.
     ================================================================================================= */
  var ICON = {
    back:  '<path d="M14.6 5.4 7.6 12l7 6.6"/>',
    chev:  '<path d="M9.4 5.4 16 12l-6.6 6.6"/>',
    wifi:  '<path d="M3.4 9.2a13 13 0 0 1 17.2 0M6.6 12.8a8.4 8.4 0 0 1 10.8 0"/>'
           + '<circle cx="12" cy="17.6" r="1.4" fill="currentColor" stroke="none"/>',
    batt:  '<rect x="3.2" y="7.4" width="16" height="9.2" rx="2.4"/><path d="M21.2 10.6v2.8"/>'
           + '<rect x="5.4" y="9.6" width="9.6" height="4.8" rx="1.2" fill="currentColor" stroke="none"/>',
    cell:  '<path d="M4 18.6V15M9.3 18.6v-6.4M14.7 18.6V8.4M20 18.6V4.6"/>'
  };
  function ic(name, cls, sw) {
    return '<svg class="' + (cls || '') + '" viewBox="0 0 24 24" fill="none" stroke="currentColor"'
      + ' stroke-width="' + (sw || 2.1) + '" stroke-linecap="round" stroke-linejoin="round">'
      + ICON[name] + '</svg>';
  }

  /* =================================================================================================
     THE TWO REMOTES.

     Each is one <svg> with its own viewBox in "remote units", so the same geometry serves a 104 dp
     row thumbnail, a 150 dp card drawing and a 190 dp instruction illustration without a second
     set of numbers. CSS sets the height; the aspect ratio holds the width.

     What each must read as at a glance (the brief):
       KEYPAD  — tall and narrow, a full 3x3+0 keypad near the top, a D-pad lower down, a row of
                 four coloured keys.
       SMART   — short, rounded, a large circular D-pad, a handful of keys, and one key marked
                 123 / coloured dots.

     `lit` lights what the viewer presses: 'keys' fills 1-5 with the accent, '123' RINGS the
     Number/Color key instead of filling it. The difference is deliberate and the report says so:
     five keys are a block, so a fill reads them as one target, while the single 123 key IS its own
     marking — filling it would paint over the coloured dots that identify it on a newer remote.

     `numerals` prints the digit on each keypad key and "123" on the Number/Color key. It is off
     below about 120 dp of drawing height, where a digit would be under 4 px and a smudge is worse
     than a clean key. The report names the threshold.
  ================================================================================================= */

  /* A key: unlit, lit-filled, or dimmed. `n` is its printed digit, when numerals are on. */
  function key(cx, cy, r, state, n, fs) {
    var cls = 'swr-k' + (state === 'lit' ? ' lit' : '');
    var h = '<circle class="' + cls + '" cx="' + cx + '" cy="' + cy + '" r="' + r + '"/>';
    /* NO HALO RING. Drawn with one at first — an accent ring at a third alpha outside each lit
       key — and magnified it was two outlines around one target on the Smart Remote's key, and a
       chain of overlapping rings across 1-2-3 on the keypad, whose centres are 19 units apart
       with no room for a 10-unit ring. That is round 11's two-rings-read-as-two-targets defect
       and it is invisible at 1x. One rule instead: the accent IS "press this", drawn once — a
       fill where the key can carry it, a ring where the key's own marking must stay readable. */
    if (n !== undefined && n !== null && fs) {
      h += '<text class="swr-kn' + (state === 'lit' ? ' lit' : '') + '" x="' + cx + '" y="' + cy
        + '" font-size="' + fs + '">' + n + '</text>';
    }
    return h;
  }

  /* ---- the basic remote: long, with a real 0-9 keypad -------------------------------------- */
  function keypadRemote(lit, numerals) {
    var fs = numerals ? 9.5 : 0;
    var h = '<svg class="swr-rm keypad" viewBox="0 0 100 300" role="img">';
    /* body */
    h += '<rect class="swr-face" x="20" y="3" width="60" height="294" rx="18"/>';
    /* power and source, the two keys above the keypad */
    h += key(31, 22, 5, 'off') + key(69, 22, 5, 'off');
    /* the 3x3 keypad, 1-9, then 0 under it. 1-5 are what step 1 asks for. */
    var N = 0, row, col;
    for (row = 0; row < 3; row += 1) {
      for (col = 0; col < 3; col += 1) {
        N += 1;
        h += key(31 + col * 19, 56 + row * 24, 7.6,
          (lit === 'keys' && N <= 5) ? 'lit' : 'off', N, fs);
      }
    }
    h += key(50, 128, 7.6, 'off', 0, fs);
    /* the D-pad: a ring with a centre key, lower down */
    h += '<circle class="swr-ring" cx="50" cy="188" r="25"/>'
      + '<circle class="swr-k" cx="50" cy="188" r="9.5"/>';
    /* the four coloured keys — the row every Samsung remote carries */
    D.KEYCOLORS.forEach(function (c, i) {
      h += '<circle class="swr-ck" cx="' + (28.5 + i * 14.3) + '" cy="240" r="5.2" fill="' + c + '"/>';
    });
    /* two rockers at the foot: volume and channel */
    h += '<rect class="swr-k" x="24" y="258" width="18" height="34" rx="9"/>'
      + '<rect class="swr-k" x="58" y="258" width="18" height="34" rx="9"/>';
    return h + '</svg>';
  }

  /* ---- the Smart Remote: short, rounded, one Number/Color key ------------------------------- */
  function smartRemote(lit, numerals) {
    var h = '<svg class="swr-rm smart" viewBox="0 0 100 212" role="img">';
    h += '<rect class="swr-face" x="22" y="3" width="56" height="206" rx="26"/>';
    h += key(33, 24, 4.6, 'off') + key(67, 24, 4.6, 'off');
    /* The Number/Color key. A pill, not a circle, because it carries TWO markings: Samsung labels
       it 123 on an older Smart Remote and prints four coloured dots on a newer one, and the
       instruction names both. Digits above, dots below, inside the one key. */
    var on = lit === '123';
    /* The key is drawn larger than its real proportion on the set, because it is the subject of
       the sentence: at the first render it was 26 px wide in the illustration and its digits and
       dots were a smudge. A remote whose one named key is unreadable is a photograph of a remote,
       not an instruction. */
    h += '<rect class="swr-k' + (on ? ' ringed' : '') + '" x="23" y="37" width="34" height="27" rx="13.5"/>';
    if (numerals) {
      h += '<text class="swr-kn' + (on ? ' onring' : '') + '" x="40" y="46.4" font-size="10.4">123</text>';
    }
    D.KEYCOLORS.forEach(function (c, i) {
      h += '<circle class="swr-ck" cx="' + (31 + i * 6) + '" cy="'
        + (numerals ? 57.4 : 50.5) + '" r="2.1" fill="' + c + '"/>';
    });
    h += key(69, 50.5, 5, 'off');
    /* the big circular D-pad this remote is known for */
    h += '<circle class="swr-ring" cx="50" cy="104" r="25"/>'
      + '<circle class="swr-k" cx="50" cy="104" r="9"/>';
    /* back, home, play */
    h += key(32, 148, 4.8, 'off') + key(50, 148, 4.8, 'off') + key(68, 148, 4.8, 'off');
    /* volume and channel rockers */
    h += '<rect class="swr-k" x="26" y="166" width="17" height="34" rx="8.5"/>'
      + '<rect class="swr-k" x="57" y="166" width="17" height="34" rx="8.5"/>';
    return h + '</svg>';
  }

  /* ---- direction B's detail crops: the same keys, close enough to read ----------------------
     The crop is the remote's own face filling the shipped 220 x 150 slot, with its two side
     outlines at the edges to say that this IS the remote and not a floating keypad. The 0 key is
     deliberately cut by the bottom edge: a crop that shows no cut edge reads as a whole object. */
  function keypadDetail() {
    var h = '<svg class="swr-dt" viewBox="0 0 160 110" role="img">';
    h += '<rect class="swr-face" x="8" y="-30" width="144" height="180" rx="34"/>';
    var N = 0, row, col;
    for (row = 0; row < 3; row += 1) {
      for (col = 0; col < 3; col += 1) {
        N += 1;
        h += key(40 + col * 40, 26 + row * 30, 14.5, N <= 5 ? 'lit' : 'off', N, 15);
      }
    }
    h += key(80, 116, 14.5, 'off', 0, 15);   /* cut by the bottom edge */
    return h + '</svg>';
  }
  function smartDetail() {
    var h = '<svg class="swr-dt" viewBox="0 0 160 110" role="img">';
    h += '<rect class="swr-face" x="20" y="-40" width="120" height="190" rx="46"/>';
    h += key(48, 22, 10, 'off') + key(112, 22, 10, 'off');
    h += '<rect class="swr-k ringed" x="34" y="54" width="66" height="42" rx="21"/>';
    h += '<text class="swr-kn onring" x="67" y="70" font-size="17">123</text>';
    D.KEYCOLORS.forEach(function (c, i) {
      h += '<circle class="swr-ck" cx="' + (52.5 + i * 9.6) + '" cy="85" r="3.3" fill="' + c + '"/>';
    });
    h += key(120, 75, 10, 'off');
    return h + '</svg>';
  }

  /* =================================================================================================
     CHROME — the shipped status bar and the guide's own back row.
     ================================================================================================= */
  function sb() {
    return '<div class="swr-sb"><span>' + esc(D.CLOCK) + '</span><span class="swr-sbi">'
      + ic('cell') + ic('wifi') + ic('batt') + '</span></div>';
  }
  /* The shipped guide draws a bare "‹" at 26 sp with 10/4 dp padding inside a 12/10 row. This is
     the same control with the stroked chevron the rest of the phone app uses and a real 44 dp
     target. Reported as a change, small as it is — the same one the overview round made. */
  function backRow() {
    return '<div class="swr-bar"><button class="swr-bk" type="button">'
      + ic('back', '', 2.3) + '</button></div>';
  }
  function stepLine() {
    return '<div class="swr-step">' + esc(t('step', { n: '1' })).toUpperCase() + '</div>';
  }
  function cta(label) {
    return '<button class="swr-cta" type="button">' + esc(label) + '</button>';
  }
  function quiet(label) {
    return '<button class="swr-quiet" type="button">' + esc(label) + '</button>';
  }
  function bodyP(s) {
    return '<p class="swr-body">' + rich(s) + '</p>';
  }
  function title(s) {
    return '<h1 class="swr-h1">' + esc(s) + '</h1>';
  }
  /* `lit` defaults to on, because every instruction state lights what the viewer presses. The
     CHOICE state passes false: nothing has been chosen there, so nothing is being pressed, and an
     accent key on a card the viewer has not tapped reads as a selection already made. The first
     render had both cards lit and that is exactly how it read. */
  function remoteOf(pick, numerals, lit) {
    var on = lit !== false;
    return pick === 'keys'
      ? keypadRemote(on ? 'keys' : 'none', numerals)
      : smartRemote(on ? '123' : 'none', numerals);
  }

  /* =================================================================================================
     DIRECTION A — "Two remotes"  (recommended)

     The question IS the screen's headline, and the two drawn remotes are two tall cards side by
     side: the remotes' silhouettes are the cue, and a tall drawing is what shows a silhouette.
     After the tap the SAME drawing, bigger, is the step's illustration, with the keys to press lit
     — so the thing the viewer recognised and the thing they are shown are one object. The switch
     is the guide's own quiet link.

     Its one new component is the two-remote choice (`.swr-pick`); everything else is the shipped
     step page.
     ================================================================================================= */
  function choiceCardA(pick, numerals) {
    return '<button class="swr-pk" type="button">'
      + '<span class="swr-pkart">' + remoteOf(pick, numerals, false) + '</span>'
      + '<span class="swr-pklb">' + esc(t(pick === 'keys' ? 'step1.keys' : 'step1.nokeys'))
      + '</span></button>';
  }
  function screenA(s, tv, fs200) {
    if (!s.pick) {
      /* The choice state. No primary button: the tap is the step's whole action (FR-13).
         The drawings are unlit — nothing has been chosen, so nothing is being pressed. At the
         200 % font setting the pair stacks (see .swr-pick in the CSS and the x2 study); the
         numerals come off below 120 dp of drawing, which is every size here. */
      return stepLine() + title(t('step1.which'))
        + '<div class="swr-pick' + (s.forceSideBySide ? ' sbs' : '') + '">'
        + choiceCardA('keys', false) + choiceCardA('nokeys', false) + '</div>';
    }
    return stepLine()
      + '<div class="swr-art tall">' + remoteOf(s.pick, true) + '</div>'
      + title(t('step1.title')) + bodyP(t(bodyKey(s.pick, tv)))
      + cta(t('next')) + quiet(t('step1.other'));
  }

  /* =================================================================================================
     DIRECTION B — "Keys up close"

     The question is the headline, and the two choices are full-width rows: a small drawing at the
     left, the label and one line saying what that remote is. After the tap the illustration is not
     the remote but the KEYS, cropped into the shipped 220 x 150 slot at a size a digit can
     actually be read at.

     What it buys: the instruction shows exactly what to press, at four times the size direction A
     can. What it costs: the silhouette that identified the remote on the question screen is gone
     from the instruction, so a viewer who chose wrongly has less to notice it by — and two extra
     strings for the row's second line.
     ================================================================================================= */
  function choiceRowB(pick) {
    return '<button class="swr-rw" type="button">'
      + '<span class="swr-rwart">' + remoteOf(pick, false, false) + '</span>'
      + '<span class="swr-rwt"><b>' + esc(t(pick === 'keys' ? 'step1.keys' : 'step1.nokeys'))
      + '</b><em>' + esc(t(pick === 'keys' ? 'step1.keys_sub' : 'step1.nokeys_sub'))
      + '</em></span>' + ic('chev', 'swr-rwc', 2.2) + '</button>';
  }
  function screenB(s, tv) {
    if (!s.pick) {
      return stepLine() + title(t('step1.which'))
        + '<div class="swr-rows">' + choiceRowB('keys') + choiceRowB('nokeys') + '</div>';
    }
    return stepLine()
      + '<div class="swr-art">' + (s.pick === 'keys' ? keypadDetail() : smartDetail()) + '</div>'
      + title(t('step1.title')) + bodyP(t(bodyKey(s.pick, tv)))
      + cta(t('next')) + quiet(t('step1.other'));
  }

  /* =================================================================================================
     DIRECTION C — "Remote tabs"

     The smallest change to the shipped screen. Step 1 keeps its own title, *Open the hidden menu*,
     on every state; the question is a prompt line under it. The drawings stay at the shipped
     220 x 150 illustration size. After the tap the choice does not disappear: it becomes a two-tab
     control under the step indicator, which is both where the viewer sees what they chose and how
     they switch — so no quiet link is needed.

     What it buys: continuity (the step is never not itself), one affordance instead of two, and no
     change to the illustration slot. What it costs: a tab bar is the most prominent thing on a
     screen whose one action is *Next*, and the drawings are the smallest of the three.
     ================================================================================================= */
  function tabsC(pick) {
    return '<div class="swr-seg">'
      + '<button class="swr-sg' + (pick === 'keys' ? ' on' : '') + '" type="button">'
      + esc(t('step1.keys')) + '</button>'
      + '<button class="swr-sg' + (pick === 'nokeys' ? ' on' : '') + '" type="button">'
      + esc(t('step1.nokeys')) + '</button></div>';
  }
  function screenC(s, tv) {
    if (!s.pick) {
      return stepLine() + title(t('step1.title'))
        + '<p class="swr-prompt">' + esc(t('step1.which')) + '</p>'
        + '<div class="swr-pick c">' + choiceCardA('keys', false) + choiceCardA('nokeys', false)
        + '</div>';
    }
    return stepLine() + tabsC(s.pick)
      + '<div class="swr-art">' + remoteOf(s.pick, false) + '</div>'
      + title(t('step1.title')) + bodyP(t(bodyKey(s.pick, tv))) + cta(t('next'));
  }

  /* ---- the screen -------------------------------------------------------------------------- */
  function screen(dir, s) {
    var tv = D.TVS[s.tv];
    var body = dir === 'b' ? screenB(s, tv) : dir === 'c' ? screenC(s, tv) : screenA(s, tv);
    return sb() + backRow() + '<div class="swr-pg">' + body + '</div>';
  }

  window.SWRKit = {
    setLang: setLang, t: t, esc: esc, uid: uid, ic: ic,
    bodyKey: bodyKey, screen: screen,
    keypadRemote: keypadRemote, smartRemote: smartRemote
  };
})();
