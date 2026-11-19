/* setup-frames.js — builds every frame of this round from window.SETUP and nothing else.
   frames.html is parameterised:
       ?set=phone | tv | desktop | canvas
       &lang=en|da|fo      &skin=aurora|midnight|noir|graphite|daylight
   The SET decides which shipped token file frames.html loaded, which is why it is a parameter and
   not a section: the TV, the phone and the desktop are three token files that name their tokens the
   SAME way, and loading two into one document would let one answer for the other. */
(function () {
  var S = window.SETUP;
  var Q = new URLSearchParams(location.search);
  var SET = Q.get('set') || 'phone';
  var LANG = Q.get('lang') || 'en';
  var SKIN = Q.get('skin') || (SET === 'desktop' ? 'aurora' : 'aurora');
  var GLYPHSET = Q.get('glyphs') || 'pilcrow';

  var sheet = document.getElementById('sheet');
  var idSeq = 0;   /* every SVG id in this document is minted from a counter — a gradient defined
                      inside a display:none subtree never resolves, and the symptom lands in a
                      VISIBLE frame. There are no SVGs in this round yet; the counter stays so the
                      next hand to add one does not have to remember why. */

  function el(tag, cls, html) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (html != null) n.innerHTML = html;
    return n;
  }
  function esc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }

  /* A frame: the picture, its caption, and the id render-design.sh names the PNG after. */
  function frame(id, tag, title, sub) {
    var f = el('div', 'sh-frame');
    f.id = id;
    var cap = el('div', 'sh-cap');
    cap.appendChild(el('b', null, esc(tag)));
    cap.appendChild(el('h3', null, esc(title)));
    if (sub) cap.appendChild(el('span', null, esc(sub)));
    f.appendChild(cap);
    sheet.appendChild(f);
    return f;
  }

  /* ---- the mark line, built from the state's own segments ---- */
  function markLine(state) {
    var g = S.GLYPHS[GLYPHSET];
    var html = state.marks.map(function (seg) {
      return seg.mark ? '<i>' + esc(g[seg.mark]) + '</i>' : esc(seg.t);
    }).join('');
    return el('div', 'sh-marks', html);
  }

  /* ---- the warning + fix button (FR-4, FR-5) ---- */
  function warning(state, dir, opts) {
    opts = opts || {};
    var w = el('div', 'sh-warn ' + dir);
    w.appendChild(el('div', 'rail'));
    var t = el('div', 'txt');
    t.appendChild(el('div', 'sentence', esc(S.str(state.sentence, LANG))));
    t.appendChild(markLine(state));
    w.appendChild(t);

    /* FR-5's label embeds the cleaned address, so it is as long as the address is. */
    var pat = S.str('setup.use_fixed_address', LANG);
    var btn = el('button', 'sh-fix' + (opts.focused ? ' focused' : ''),
      esc(pat).replace('{address}', '<span class="addr">' + esc(state.cleaned) + '</span>'));
    if (dir === 'A') t.appendChild(btn); else w.appendChild(btn);
    return w;
  }

  /* ---- the whole setup column, as ServerSetupScreen draws it ---- */
  function column(opts) {
    var state = S.STATES[opts.state];
    var c = el('div', 'sh-col');
    c.appendChild(el('div', 'sh-wordmark', 'Ravilo'));
    c.appendChild(el('div', 'sh-prompt', esc(S.str('setup.server_prompt', LANG))));

    var fieldWrap = el('div', opts.ring ? 'sh-ring' : '');
    var field = el('div', 'sh-field' + (opts.fieldFocus ? ' focus' : ''));
    field.appendChild(el('label', null, esc(S.str('setup.server_label', LANG))));
    field.appendChild(el('div', 'val',
      esc(state.fieldDraws) + (opts.caret ? '<span class="caret"></span>' : '')));
    fieldWrap.appendChild(field);
    c.appendChild(fieldWrap);

    if (state.marks) c.appendChild(warning(state, opts.dir || 'A', opts));

    var warned = !!state.marks;
    var connect = el('div', 'sh-connect ' +
      (warned ? 'off' : (opts.connectFocus ? 'focused' : 'ready')),
      esc(S.str('receiver.setup_connect', LANG)));
    c.appendChild(connect);

    if (opts.apps) c.appendChild(el('div', 'sh-apps', esc(S.str('nav.apps', LANG))));
    return c;
  }

  /* ---- the surfaces ---- */
  function tv(opts) {
    var stage = el('div', 'sh-app sh-tv');
    applyWarn(stage);
    stage.appendChild(column(opts));
    return stage;
  }

  function phone(opts) {
    var p = el('div', 'sh-app sh-phone' + (opts.kbd ? ' kbd' : ''));
    applyWarn(p);
    var screen = el('div', 'screen');
    var sb = el('div', 'sh-statusbar');
    sb.appendChild(el('span', null, '21:20'));
    sb.appendChild(el('span', null, '&#9679;&#9679;&#9679;'));
    screen.appendChild(sb);
    screen.appendChild(column(opts));
    p.appendChild(screen);
    p.appendChild(el('div', 'punch'));
    if (opts.kbd) p.appendChild(ime(opts));
    return p;
  }

  /* The system keyboard, drawn only so the frames can answer one question: with the IME up, is the
     fix button still on screen? It is chrome, not design — deliberately plain. */
  function ime(opts) {
    var k = el('div', 'sh-ime');
    k.appendChild(el('div', 'sug', 'jelly.example.net'));
    [['q w e r t y u i o p', 1], ['a s d f g h j k l', 1], ['. z x c v b n m /', 1]].forEach(function (r) {
      var row = el('div', 'krow');
      r[0].split(' ').forEach(function (c) { row.appendChild(el('div', 'key', c)); });
      k.appendChild(row);
    });
    var last = el('div', 'krow');
    last.appendChild(el('div', 'key wide', '?123'));
    last.appendChild(el('div', 'key', ','));
    last.appendChild(el('div', 'key', ' '));
    last.appendChild(el('div', 'key', '.'));
    /* FR-6: while the warning shows, Go does nothing. Drawn as a dead key, not a live one. */
    last.appendChild(el('div', 'key wide go' + (opts.goOff ? ' off' : ''), 'Go'));
    k.appendChild(last);
    return k;
  }

  function desktop(opts) {
    var w = el('div', 'sh-app sh-desk');
    applyWarn(w);
    w.appendChild(el('div', 'lights', '<i></i><i></i><i></i>'));
    w.appendChild(column(opts));
    return w;
  }

  /* The warning colour for the skin in frame, with its reason in setup-data.js. */
  function applyWarn(node, skin) {
    var w = S.WARN[skin || SKIN] || S.WARN.aurora;
    node.style.setProperty('--warn-ink', w.ink);
    node.style.setProperty('--warn-rail', w.rail);
  }

  /* ============================================================
     THE FRAME LISTS. A variant renders FEWER frames than the base: the Danish and Faroese runs
     draw the frames where length is the question, the skin runs draw the one frame that proves
     the skin. Which is which is HERE, so REPORT.md's render commands and this file cannot
     disagree about what exists.
     ============================================================ */

  var BUILD = {
    phone: function () {
      if (LANG !== 'en') {
        frame('A-phone-' + LANG, 'A · ' + LANG.toUpperCase(), 'State A, keyboard down',
          'the longest of the three sentences, and the button label that carries the address')
          .appendChild(phone({ state: 'A', dir: 'A' }));
        frame('A-phone-' + LANG + '-kbd', 'A · ' + LANG.toUpperCase(), 'State A, keyboard up', '')
          .appendChild(phone({ state: 'A', dir: 'A', kbd: true, fieldFocus: true, caret: true, goOff: true }));
        return;
      }
      if (SKIN === 'daylight') {
        frame('A-phone-daylight', 'A · DAYLIGHT', 'State A in the light phone theme',
          '#f5b542 is 1.8 : 1 on Daylight’s #fbfbfd; this frame is drawn at #8a5a00 / #c98a12')
          .appendChild(phone({ state: 'A', dir: 'A' }));
        return;
      }
      frame('A-phone-linebreak-kbd-down', 'A', 'State A — a pasted line break, keyboard down',
        'the field draws only the first line; the mark line is the whole fault')
        .appendChild(phone({ state: 'A', dir: 'A' }));
      frame('A-phone-linebreak-kbd-up', 'A', 'State A — keyboard up',
        'R349 FR-R349-1: the form scrolls inside what the IME leaves')
        .appendChild(phone({ state: 'A', dir: 'A', kbd: true, fieldFocus: true, caret: true, goOff: true }));
      frame('A-phone-space-kbd-down', 'A', 'State B — a space in the middle', '')
        .appendChild(phone({ state: 'B', dir: 'A' }));
      frame('A-phone-clean', 'A', 'State C — after the button',
        'the field holds exactly the cleaned address, no warning, Connect enabled')
        .appendChild(phone({ state: 'C', connectFocus: false }));
      frame('B-phone-space', 'B', 'Direction B — the button beside the warning, State B',
        'at 412 dp the label breaks mid-word and the sentence takes two lines')
        .appendChild(phone({ state: 'B', dir: 'B' }));
      frame('B-phone-linebreak-kbd-up', 'B', 'Direction B — State A, keyboard up', '')
        .appendChild(phone({ state: 'A', dir: 'B', kbd: true, fieldFocus: true, caret: true, goOff: true }));
    },

    tv: function () {
      if (LANG !== 'en') {
        frame('A-tv-' + LANG, 'A · ' + LANG.toUpperCase(), 'State A at 1920 × 1080', '')
          .appendChild(tv({ state: 'A', dir: 'A', focused: true }));
        return;
      }
      if (SKIN !== 'aurora') {
        frame('A-tv-' + SKIN, 'A · ' + SKIN.toUpperCase(), 'State A in the ' + SKIN + ' skin',
          (S.WARN[SKIN] || {}).why || '')
          .appendChild(tv({ state: 'A', dir: 'A', focused: true }));
        return;
      }
      frame('A-tv-linebreak-fix-focused', 'A', 'State A, D-pad focus on the fix button',
        'FR-5: the D-pad order is field → fix button → Connect, and Up reverses it')
        .appendChild(tv({ state: 'A', dir: 'A', focused: true }));
      frame('A-tv-linebreak-field-focused', 'A', 'State A, focus on the address',
        'R349 FR-R349-7: the address is focused like a button first, with the 3 dp ring')
        .appendChild(tv({ state: 'A', dir: 'A', ring: true }));
      frame('A-tv-space-fix-focused', 'A', 'State B, focus on the fix button', '')
        .appendChild(tv({ state: 'B', dir: 'A', focused: true }));
      frame('A-tv-clean-connect-focused', 'A', 'State C — Connect enabled and focused', '')
        .appendChild(tv({ state: 'C', connectFocus: true }));
      frame('A-tv-linebreak-apps-row', 'A', 'State A with the Apps row present',
        'DEB-4912 FR-12 draws Apps only while the TV’s home-screen hook is on')
        .appendChild(tv({ state: 'A', dir: 'A', focused: true, apps: true }));
      frame('B-tv-linebreak-fix-focused', 'B', 'Direction B at 1920 × 1080',
        'the button is beside the warning, so Down from the field moves sideways')
        .appendChild(tv({ state: 'A', dir: 'B', focused: true }));
    },

    desktop: function () {
      frame('A-desktop-linebreak', 'A', 'State A in the desktop window', '1200 × 760, no sidebar yet')
        .appendChild(desktop({ state: 'A', dir: 'A' }));
      frame('A-desktop-clean', 'A', 'State C in the desktop window', '')
        .appendChild(desktop({ state: 'C' }));
      frame('B-desktop-linebreak', 'B', 'Direction B in the desktop window', '')
        .appendChild(desktop({ state: 'A', dir: 'B' }));
    },

    canvas: function () { canvas(); }
  };

  /* ============================================================
     THE CANVAS FRAMES — the comparisons and the measured anatomy.
     Dark ink on the light-gray backdrop; the component itself sits on a dark plate.
     ============================================================ */
  function plate(build) {
    var p = el('div', 'sh-app sh-plate');
    applyWarn(p);
    p.style.setProperty('--bg', '#0a0c13'); p.style.setProperty('--bg-2', '#0e111b');
    p.style.setProperty('--ink', '#f3f4fb'); p.style.setProperty('--ink-soft', '#aeb4cb');
    p.style.setProperty('--ink-dim', '#6b7290'); p.style.setProperty('--card-2', '#1b2031');
    p.style.setProperty('--accent', '#7b6ef0');
    build(p);
    return p;
  }

  function canvas() {
    /* --- 1. A versus B, the same state, side by side --- */
    var f1 = frame('compare-A-vs-B', 'COMPARE', 'Where the button sits, relative to the warning line',
      'State A, English, 412 dp of column — the narrowest the component ever gets');
    var pair = el('div', 'sh-pair');
    [['A', 'A — the button under the mark line', 'sh-pick', 'recommended'],
     ['B', 'B — the button beside the warning', 'sh-pick sh-against', 'not recommended']
    ].forEach(function (d) {
      var col = el('div');
      col.appendChild(el('h4', null, esc(d[1]) + ' <span class="' + d[2] + '">' + d[3] + '</span>'));
      col.appendChild(plate(function (p) {
        p.style.width = '412px';
        var w = warning(S.STATES.A, d[0], {});
        p.appendChild(w);
      }));
      pair.appendChild(col);
    });
    f1.appendChild(pair);
    f1.appendChild(el('p', 'sh-note',
      '<em>A is recommended.</em> Three reasons, and the first is the only one that is not taste. '
      + '<em>(1) The D-pad.</em> FR-5 fixes the order field → fix button → Connect. In A that is '
      + 'three rows and Down means down. In B the button is beside the mark line, so Down from the '
      + 'field has to move the ring sideways into a column that holds two other things, and Down '
      + 'again has to leave it — the same press doing two different things one row apart. '
      + '<em>(2) The label is unbounded.</em> FR-5’s label carries the cleaned address, so it is as '
      + 'wide as the address is; B gives it a fixed share of one row and the Faroese <span class="mono">'
      + 'Brúka jelly.example.net</span> already overflows it. A gives it the full column and lets '
      + 'it wrap. <em>(3)</em> B saves about 54 dp of height, which matters only with the phone '
      + 'keyboard up — and the keyboard-up frames show A fits there anyway.'));

    /* --- 2. the mark colour --- */
    var f2 = frame('mark-colour', 'COLOUR', 'The mark is in the warning colour; the address is not',
      'ravilo.css --warn #f5b542, the colour .dplaynote and .ep-playnote already use');
    var pair2 = el('div', 'sh-pair');
    [['the whole line in --warn', '#f5b542', 'sh-pick sh-against', 'not this'],
     ['only the mark in --warn', null, 'sh-pick', 'this']
    ].forEach(function (d, i) {
      var col = el('div');
      col.appendChild(el('h4', null, esc(d[0]) + ' <span class="' + d[2] + '">' + d[3] + '</span>'));
      col.appendChild(plate(function (p) {
        p.style.width = '412px';
        var line = markLine(S.STATES.A);
        if (i === 0) line.style.color = '#f5b542';
        p.appendChild(line);
        var line2 = markLine(S.STATES.B);
        if (i === 0) line2.style.color = '#f5b542';
        line2.style.marginTop = '10px';
        p.appendChild(line2);
      }));
      pair2.appendChild(col);
    });
    f2.appendChild(pair2);
    f2.appendChild(el('p', 'sh-note',
      'FR-4 asks for the mark “in the warning colour”. Drawing the whole line that way satisfies '
      + 'the sentence and defeats its purpose: the viewer is looking for one character in a string, '
      + 'and a string that is entirely warn has nothing in it to find. The address stays '
      + '<em>--ink-soft</em>, which also makes the mark line read as a quotation of what was typed '
      + 'rather than as a second error message.'));

    /* --- 3. the warning colour across the five themes --- */
    var f3 = frame('warn-across-themes', 'COLOUR', 'The warning colour in each theme',
      'two of the five cannot take #f5b542, and neither has a --warn token today');
    var tbl = el('table', 'sh-tbl');
    tbl.innerHTML = '<thead><tr><th>Theme</th><th>Mark &amp; rail</th><th>Why</th></tr></thead>';
    var tb = el('tbody');
    Object.keys(S.WARN).forEach(function (k) {
      var w = S.WARN[k];
      tb.innerHTML += '<tr><td><b>' + k + '</b></td>'
        + '<td><span class="sh-swatch" style="background:' + w.ink + '"></span>'
        + '<span class="mono">' + w.ink + '</span>'
        + (w.rail !== w.ink ? ' <span class="mono">rail ' + w.rail + '</span>' : '') + '</td>'
        + '<td>' + w.why + '</td></tr>';
    });
    tbl.appendChild(tb);
    f3.appendChild(tbl);
    f3.appendChild(el('p', 'sh-note',
      '<em>This is the round’s one token finding.</em> <span class="mono">RaviloColors</span> has no '
      + 'warn field at all, and neither <span class="mono">ravilo-phone.css</span> nor '
      + '<span class="mono">desktop-directions.css</span> carries <span class="mono">--warn</span> — only '
      + '<span class="mono">ravilo.css</span> does. The promotion adds one, and it is not one value: '
      + 'Noir’s accent <em>is</em> #f5b542, so a warn drawn in it would be the accent colour, and '
      + 'Daylight is a light theme where #f5b542 reads at 1.8 : 1. Both are drawn in the skin frames.'));

    /* --- 3b. how much address the mark line actually has room for ---
       Measured after layout, not estimated. FR-4 says the address is shown "on one line"; this
       frame is how much line there is before that becomes a promise the component cannot keep. */
    var f3b = frame('mark-line-width', 'MEASURE', 'How wide the mark line is, and how wide it can be',
      'measured in the renderer after layout — every number here is read off the DOM');
    var probe = el('div', 'sh-app');
    probe.style.cssText = 'position:absolute;visibility:hidden;left:-9999px;--u:1px';
    document.body.appendChild(probe);
    function markWidth(segs) {
      var g = S.GLYPHS[GLYPHSET];
      var n = el('div', 'sh-marks');
      n.style.cssText = 'white-space:pre;display:inline-block';
      n.innerHTML = segs.map(function (s2) { return s2.mark ? '<i>' + esc(g[s2.mark]) + '</i>' : esc(s2.t); }).join('');
      probe.appendChild(n);
      var w = n.getBoundingClientRect().width;
      n.remove();
      return w;
    }
    var wA = markWidth(S.STATES.A.marks), wB = markWidth(S.STATES.B.marks);
    var perChar = markWidth([{ t: 'nnnnnnnnnn' }]) / 10;
    /* The room each surface gives the mark line is MEASURED off a real one of that surface, not
       arithmetic in a comment. The first version of this table had the TV at 946 dp because it
       read the stage's 960 px as 960 dp; the stage is density 2, so that column is 480 dp and the
       table was wrong by a factor of two while the DRAWING was right. Measuring removes the class. */
    var SURFACES = [
      { n: 'Phone (Pixel 9, 412 dp)', build: function () { return phone({ state: 'A', dir: 'A' }); } },
      { n: 'Android TV (1920 × 1080 stage, density 2)', build: function () { return tv({ state: 'A', dir: 'A' }); } },
      { n: 'Desktop window (1200 × 760)', build: function () { return desktop({ state: 'A', dir: 'A' }); } }
    ];
    var stand = el('div');
    stand.style.cssText = 'position:absolute;left:-9999px;top:0;visibility:hidden';
    document.body.appendChild(stand);
    SURFACES.forEach(function (s2) {
      var node = s2.build();
      stand.appendChild(node);
      var txt = node.querySelector('.sh-warn .txt');
      var u = parseFloat(getComputedStyle(node).getPropertyValue('--u')) || 1;
      s2.dp = txt.getBoundingClientRect().width / u;
      node.remove();
    });
    stand.remove();
    var t3b = el('table', 'sh-tbl');
    t3b.innerHTML = '<thead><tr><th>Surface</th><th>Room for the mark line</th>'
      + '<th>State A needs</th><th>State B needs</th><th>Characters before it wraps</th></tr></thead>';
    var tb3b = el('tbody');
    SURFACES.forEach(function (s2) {
      tb3b.innerHTML += '<tr><td><b>' + s2.n + '</b></td>'
        + '<td class="mono">' + s2.dp.toFixed(0) + ' dp</td>'
        + '<td class="mono">' + wA.toFixed(0) + ' dp</td>'
        + '<td class="mono">' + wB.toFixed(0) + ' dp</td>'
        + '<td class="mono">' + Math.floor(s2.dp / perChar) + '</td></tr>';
    });
    t3b.appendChild(tb3b);
    f3b.appendChild(t3b);
    probe.remove();
    f3b.appendChild(el('p', 'sh-note',
      '<em>The brief’s address does not overflow the mark line on any of the three surfaces</em>, '
      + 'and the narrowest of them still has room for ' + Math.floor(SURFACES[0].dp / perChar)
      + ' monospaced characters against State A’s ' + S.STATES.A.fieldValue.replace('\n', '¶').length
      + '. So no frame in this round can show the overflow honestly: the brief names one address and '
      + 'one trailer, and inventing a longer one is the thing the stand-in rule exists to stop. '
      + '<em>The rule still has to be decided, because a pasted address is not bounded</em> — a chat '
      + 'app’s trailer can be a sentence. The round draws the mark line as <em>wrapping, '
      + 'break-anywhere</em>, and recommends that over the two alternatives: clipping can hide the '
      + 'very mark the line exists to show, and a horizontally scrolling line is unreachable with a '
      + 'D-pad. If Barður wants the overflow drawn, the next brief needs to name the long address.'));

    /* --- 3c. the mark itself: which glyph, and does it need a wash behind it --- */
    var f3c = frame('mark-glyph-in-place', 'GLYPH', 'The mark, four ways, at the size it is read',
      'glyph-probe.png is the measurement; this is the judgement');
    var opts4 = [
      ['A', 'U+00B6 ¶ with an 18 % wash', 'pilcrow', false, 'sh-pick', 'recommended'],
      ['B', 'U+00B6 ¶ bare', 'pilcrow', true, '', ''],
      ['C', 'U+23CE ⏎ with an 18 % wash', 'spec', false, 'sh-pick sh-against', 'not in any of our three faces'],
      ['D', 'U+23CE ⏎ bare', 'spec', true, 'sh-pick sh-against', 'not in any of our three faces']
    ];
    var grid = el('div', 'sh-pair');
    opts4.forEach(function (o) {
      var col = el('div');
      col.appendChild(el('h4', null, esc(o[1]) + (o[5] ? ' <span class="' + o[4] + '">' + o[5] + '</span>' : '')));
      col.appendChild(plate(function (p) {
        p.style.width = '300px';
        var g = S.GLYPHS[o[2]];
        [S.STATES.A, S.STATES.B].forEach(function (st, i) {
          var n = el('div', 'sh-marks' + (o[3] ? ' plain' : ''));
          n.innerHTML = st.marks.map(function (s2) {
            return s2.mark ? '<i>' + esc(g[s2.mark]) + '</i>' : esc(s2.t);
          }).join('');
          if (i) n.style.marginTop = '12px';
          p.appendChild(n);
        });
      }));
      grid.appendChild(col);
    });
    f3c.appendChild(grid);
    f3c.appendChild(el('p', 'sh-note',
      '<em>The glyph.</em> FR-4 names U+23CE for a line break. <span class="mono">glyph-probe.png</span> '
      + 'measured it against the three self-hosted woff2 and it is <em>in none of them</em> — it draws '
      + 'from whatever fallback the device has, in a face and a weight we do not control, and on a '
      + 'device that has none it is tofu. U+00B6, the pilcrow, is in all three, is the typographer’s '
      + 'own mark for exactly this, and is the round’s recommendation. U+00B7 for a space needs no '
      + 'decision: it is in all three. <em>The wash.</em> A bare mark reads as a character of the '
      + 'address, which is the one thing it must not be; an 18 % wash of the warning colour behind it '
      + 'says "this is not in your text, this is where your text went wrong" without a second colour '
      + 'or a second weight. The glyph itself stays in the warning colour, as FR-4 asks.'));

    /* --- 4. the measured anatomy, which is what gets ported --- */
    var f4 = frame('anatomy', 'SPEC', 'The component, measured',
      'in dp; the TV stage renders these at 2 px per dp, the phone and the desktop at 1');
    var wrap = el('div', 'sh-pair');
    wrap.appendChild(plate(function (p) {
      p.style.width = '412px';
      p.appendChild(warning(S.STATES.A, 'A', {}));
    }));
    var t4 = el('table', 'sh-tbl');
    t4.innerHTML = '<thead><tr><th>Part</th><th>Value</th></tr></thead>';
    var rows = [
      ['rail', S.SPEC.railW + ' dp wide, 2 dp radius, full height of the sentence + mark line + button, in the warn colour'],
      ['rail → text', S.SPEC.railGap + ' dp'],
      ['field → warning', S.SPEC.warnGapTop + ' dp (the column’s own gap is ' + S.SPEC.colGapBefore + ' dp; the warning belongs to the field, so it sits closer)'],
      ['sentence', 'Sora 600, ' + S.SPEC.sentenceSize + ' sp, line-height 1.35, in the warn colour'],
      ['sentence → marks', S.SPEC.warnGapInner + ' dp'],
      ['mark line', 'JetBrains Mono 500, ' + S.SPEC.markSize + ' sp, tracking ' + S.SPEC.markTrack + ' sp, --ink-soft; each MARK is 700 and in the warn colour'],
      ['mark line wrapping', 'wraps, break-anywhere. It does not clip and it does not scroll — see mark-line-width for why, and for the one thing this round could not draw'],
      ['marks → fix button', S.SPEC.btnGapTop + ' dp'],
      ['fix button', 'outline: 1 dp border in the warn colour, no fill, --ink text, Sora 600 ' + S.SPEC.btnSize + ' sp, radius ' + S.SPEC.btnR + ' dp, min-height ' + S.SPEC.btnH + ' dp, full column width, label wraps'],
      ['fix button, focused', 'the 2 dp ring Connect already uses (--ring), and the border takes the ring colour. Not the inverted fill — that treatment belongs to chips and nav items'],
      ['the cleaned address in the label', 'JetBrains Mono 600, so the thing the button promises to put in the field is set in the same face the mark line quotes it in'],
      ['Connect, while the warning shows', 'FR-6: the disabled state the screen already has — --card-2 fill, --ink-dim label. Unchanged'],
      ['Connect', 'unchanged: ' + S.SPEC.connectH + ' dp, radius ' + S.SPEC.btnR + ' dp']
    ];
    var tb4 = el('tbody');
    rows.forEach(function (r) { tb4.innerHTML += '<tr><td><b>' + r[0] + '</b></td><td>' + r[1] + '</td></tr>'; });
    t4.appendChild(tb4);
    wrap.appendChild(t4);
    f4.appendChild(wrap);

    /* --- 5. the strings, flat, in three languages --- */
    var f5 = frame('strings', 'WORDS', 'The three new strings, in three languages',
      'FR-7’s own table, rendered from it — the drawing cannot disagree with the spec');
    var t5 = el('table', 'sh-tbl');
    t5.innerHTML = '<thead><tr><th>Key</th><th>English</th><th>Dansk</th><th>Føroyskt</th></tr></thead>';
    var tb5 = el('tbody');
    Object.keys(S.NEW_STRINGS).forEach(function (k) {
      var v = S.NEW_STRINGS[k];
      tb5.innerHTML += '<tr><td class="mono">' + k + '</td><td>' + esc(v.en) + '</td><td>'
        + esc(v.da) + '</td><td>' + esc(v.fo) + '</td></tr>';
    });
    t5.appendChild(tb5);
    f5.appendChild(t5);
    f5.appendChild(el('p', 'sh-note',
      'The round adds <em>no string of its own</em> — these three are the spec’s, drawn here so '
      + 'the length of each is visible rather than counted. The longest is the Faroese sentence at '
      + S.NEW_STRINGS['setup.hidden_line_break'].fo.length + ' characters, and the Faroese button '
      + 'label at <span class="mono">Brúka jelly.example.net</span>. Both are drawn on the phone '
      + 'in the <span class="mono">fo</span> frames, which is where they have the least room.'));
  }

  document.documentElement.lang = LANG;
  document.documentElement.dataset.skin = SKIN;
  (BUILD[SET] || BUILD.phone)();
  document.documentElement.dataset.ready = String(idSeq >= 0);
})();
