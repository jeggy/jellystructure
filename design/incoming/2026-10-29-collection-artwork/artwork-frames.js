/* Builds every frame in this round from window.ARTWORK and nothing else, so two pictures
   cannot disagree about a number. One `?set=` per render-design.sh run; the id of each
   .frame is the PNG's file name. Ids minted from a counter where any SVG id is needed. */
(function () {
  var A = window.ARTWORK;
  var T = A.tile;
  var q = new URLSearchParams(location.search);
  var set = q.get('set') || 'tiles';
  var root = document.getElementById('root');
  var uid = 0;
  function nextId() { return 'ca-' + (++uid); }

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function firstStop(brand) {
    var m = brand.match(/#[0-9a-fA-F]{6}/);
    return m ? m[0] : '#888888';
  }

  function rgba(hex, a) {
    var h = hex.slice(1);
    return 'rgba(' + parseInt(h.slice(0, 2), 16) + ',' + parseInt(h.slice(2, 4), 16) + ',' +
      parseInt(h.slice(4, 6), 16) + ',' + a + ')';
  }

  /* One channel tile, drawn to ChannelButtonSpec's values. */
  function tile(opts) {
    var wrap = el('div', 'tile-wrap');
    var t = el('div', 'tile' + (opts.focused ? ' focused' : ''));
    t.style.background = opts.brand;
    if (opts.focused) {
      var ring = A.themes[opts.theme].ring;
      t.style.boxShadow = 'inset 0 0 0 ' + T.ringWidth + 'px ' + ring + ', 0 ' +
        Math.round(T.glowElev / 2) + 'px ' + T.glowElev + 'px ' + rgba(firstStop(opts.brand), 0.55) +
        ', 0 0 ' + Math.round(T.glowElev / 2) + 'px ' + rgba(firstStop(opts.brand), 0.55);
    }
    t.appendChild(el('div', 'sheen'));

    if (opts.watermark) {
      var w = el('div', 'watermark', opts.watermark.slice(0, T.watermarkTake).toUpperCase());
      w.style.fontSize = T.watermarkSize + 'px';
      w.style.left = T.watermarkPadStart + 'px';
      w.style.bottom = T.watermarkPadBottom + 'px';
      w.style.color = 'rgba(255,255,255,' + T.watermarkAlpha + ')';
      t.appendChild(w);
    }

    if (opts.logo) {
      var img = el('img', 'logo');
      img.id = nextId();
      img.src = opts.logo;
      img.alt = '';
      var p = opts.padding || { top: 0, right: 0, bottom: 0, left: 0 };
      img.style.padding = p.top + 'px ' + p.right + 'px ' + p.bottom + 'px ' + p.left + 'px';
      t.appendChild(img);
    }

    /* The series hub has no name yet, so it has no wordmark and no watermark. The dashed
       slot is canvas furniture marking where both will go — it is in no SVG and no file. */
    if (opts.pending) {
      var pad = opts.padding || { top: 0, right: 0, bottom: 0, left: 0 };
      var inner = 224 - pad.left - pad.right;
      var slot = el('div', 'pending');
      /* Where the wordmark will start: after the badge in A, after the smaller glyph in B. */
      slot.style.left = (pad.left + inner * (opts.pendingFrom || 0.30)) + 'px';
      slot.style.right = pad.right + 'px';
      slot.style.top = (pad.top + 16) + 'px';
      slot.style.bottom = (pad.bottom + 16) + 'px';
      t.appendChild(slot);
    }
    wrap.appendChild(t);
    return wrap;
  }

  function frame(id, caption, sub) {
    var f = el('div', 'frame');
    f.id = id;
    if (caption) f.appendChild(el('div', 'cap', caption));
    if (sub) f.appendChild(el('div', 'sub', sub));
    root.appendChild(f);
    return f;
  }

  function logoFor(item, dir) { return dir === 'a' ? item.a : item.b; }

  /* The series hub was drawn twice: direction A carries the finished `Ét afsnit til`
     lockup the owner picked, direction B keeps the mark and the empty name slot it was
     drawn with during the hold, because B gets nothing more. Everything per-direction
     about it — its id, its watermark, whether a slot is drawn — goes through these. */
  function idFor(item, dir) { return dir === 'b' && item.bId ? item.bId : item.id; }
  function onHold(item, dir) {
    return dir === 'b' && item.bNameOnHold !== undefined ? item.bNameOnHold : item.nameOnHold;
  }

  var DIRS = [
    { key: 'a', name: 'Direction A — house badge' },
    { key: 'b', name: 'Direction B — wordmark-led' }
  ];
  var THEMES = ['aurora', 'daylight'];

  /* ---------------------------------------------------------------- tiles alone */
  function buildTiles() {
    DIRS.forEach(function (d) {
      A.new.forEach(function (item) {
        [false, true].forEach(function (foc) {
          var held = onHold(item, d.key);
          var id = d.key + '-' + idFor(item, d.key) + (foc ? '-focus' : '');
          var f = frame(id, d.name.split(' — ')[0] + ' · ' + idFor(item, d.key) + (foc ? ' · focused' : ''),
            held
              ? 'Direction B as it was drawn, and frozen there: the mark, the empty name slot, ' +
                'no watermark. The name was picked after this, and only A carries it.'
              : item.brand);
          var stage = el('div', 'stage aurora');
          stage.appendChild(tile({
            brand: item.brand, watermark: held ? null : item.watermark,
            logo: logoFor(item, d.key), padding: item.padding, focused: foc,
            theme: 'aurora', pending: held,
            pendingFrom: d.key === 'b' ? 0.22 : 0.30
          }));
          f.appendChild(stage);
        });
      });
    });
  }

  /* ---------------------------------------------------------------- the rail */
  function railTiles(dir, theme, focusedId) {
    var strip = el('div', 'rail ' + theme);
    A.existing.forEach(function (ex) {
      strip.appendChild(tile({
        brand: ex.brand, watermark: null, logo: 'marks/stand-in.svg',
        padding: { top: 18, right: 20, bottom: 18, left: 20 }, theme: theme
      }));
    });
    A.new.forEach(function (item) {
      strip.appendChild(tile({
        brand: item.brand, watermark: onHold(item, dir) ? null : item.watermark,
        logo: logoFor(item, dir), padding: item.padding,
        focused: focusedId === idFor(item, dir), theme: theme, pending: onHold(item, dir),
        pendingFrom: dir === 'b' ? 0.22 : 0.30
      }));
    });
    return strip;
  }

  function buildRails(width) {
    var isPhone = width === 412;
    DIRS.forEach(function (d) {
      THEMES.forEach(function (theme) {
        /* Eight 224 dp tiles with 16 dp gaps are 1904 dp wide, so the rail overflows even a
           1920 stage: it scrolls on a TV too, not only on a phone. The TV frames therefore
           scroll by the least that brings the focused tile fully into view, which is what the
           focus engine does; the `-all-eight` frame drops the clip so the family can be seen
           whole. */
        var perScreen = Math.floor((width - 48 + 16) / (T.w + 16));
        var states = [{ id: null, suffix: '', off: 0 }];
        if (isPhone) states.push({ id: null, suffix: '-scrolled', off: 5 });
        else states.push({ id: null, suffix: '-all-eight', off: 0, noClip: true });
        A.new.forEach(function (item, i) {
          var index = A.existing.length + i;
          states.push({
            id: idFor(item, d.key), suffix: '-focus-' + idFor(item, d.key),
            off: isPhone ? index : Math.max(0, index - perScreen + 1)
          });
        });
        states.forEach(function (st) {
          var id = 'rail-' + (isPhone ? 'phone' : 'tv') + '-' + d.key + '-' + theme + st.suffix;
          var sub = isPhone
            ? '412 px — the rail scrolls; ' + (st.off ? 'scrolled to tile ' + (st.off + 1) : 'at the start')
            : st.noClip
              ? 'all eight at their natural width, 1904 px of tiles — wider than the 1920 stage allows'
              : '1920 px — the five already on the rail, then the three new ones' +
                (st.off ? '; scrolled by ' + st.off + ' to bring the focused tile into view' : '');
          var f = frame(id, d.name + ' · ' + theme + (st.id ? ' · ' + st.id + ' focused' : ''), sub);
          var clip = el('div');
          clip.style.width = st.noClip ? 'max-content' : width + 'px';
          clip.style.overflow = 'hidden';
          clip.style.background = A.themes[theme].page;
          var inner = railTiles(d.key, theme, st.id);
          inner.style.width = 'max-content';
          if (st.off) inner.style.marginLeft = '-' + (st.off * (T.w + 16)) + 'px';
          clip.appendChild(inner);
          f.appendChild(clip);
          f.className = 'frame wide';
        });
      });
    });
  }

  /* ------------------------------------------------- Chromium beside Skia */
  function buildCompare() {
    var FILES = [
      ['upload/sagaer.svg', 'upload-sagaer', A.new[1].brand, 'A · Sagaer · the upload file'],
      ['upload/tidsmaskinen.svg', 'upload-tidsmaskinen', A.new[2].brand, 'A · Tidsmaskinen · the upload file'],
      ['upload/b/sagaer.svg', 'upload-b-sagaer', A.new[1].brand, 'B · Sagaer · the upload file'],
      ['upload/b/tidsmaskinen.svg', 'upload-b-tidsmaskinen', A.new[2].brand, 'B · Tidsmaskinen · the upload file'],
      ['upload/et-afsnit-til.svg', 'upload-et-afsnit-til', A.new[0].brand, 'A · Ét afsnit til · the upload file'],
      ['marks/serie-hub-b.svg', 'marks-serie-hub-b', A.new[0].brand, 'B · series hub · mark only'],
      ['variants/tidsmaskinen-b-tittleclock.svg', 'variants-tidsmaskinen-b-tittleclock',
        A.new[2].brand, 'B · Tidsmaskinen · the tittle clock, drawn and dropped']
    ];
    FILES.forEach(function (row) {
      var f = frame('compare-' + row[1], row[3], 'Chromium (left) and Skia SVGDOM (right), same file, 320x100 at 1.5x');
      var c = el('div', 'compare');
      var p1 = el('div', 'pane');
      p1.appendChild(el('div', 'lbl', 'Chromium'));
      var box = el('div', 'swatch');
      box.style.width = '480px';
      box.style.height = '150px';
      box.style.background = row[2];
      box.style.borderRadius = '8px';
      var img = el('img');
      img.id = nextId();
      img.src = row[0];
      img.alt = '';
      img.style.width = '480px';
      img.style.height = '150px';
      box.appendChild(img);
      p1.appendChild(box);
      var p2 = el('div', 'pane');
      p2.appendChild(el('div', 'lbl', 'Skia SVGDOM'));
      var skia = el('img');
      skia.id = nextId();
      skia.src = 'png/collection-artwork-skia-' + row[1] + '.png';
      skia.alt = '';
      skia.style.borderRadius = '8px';
      p2.appendChild(skia);
      c.appendChild(p1);
      c.appendChild(p2);
      f.appendChild(c);
    });
  }

  /* ------------------------------------------------- one line or two */
  function buildLines() {
    var m = A.metrics.logos;
    [['a', 'Direction A'], ['b', 'Direction B']].forEach(function (d) {
      var one = A.variants.filter(function (v) { return v.id === 'tidsmaskinen-' + d[0] + '-oneline'; })[0];
      var two = A.variants.filter(function (v) { return v.id === 'tidsmaskinen-' + d[0] + '-twoline'; })[0];
      var sag = A.new[1];
      var f = frame('lines-' + d[0], d[1] + ' · Tidsmaskinen on one line and on two',
        'Cap height is the number to compare: Sagaer sets the family at ' +
        m['upload/sagaer.svg'].capHeight + ' units in the 320x100 box.');
      var row = el('div', 'row');
      [[one, 'One line'], [two, 'Two lines'],
      [{ file: d[0] === 'a' ? sag.a : sag.b, brand: sag.brand, watermark: sag.watermark, padding: sag.padding },
        'Sagaer, for comparison']].forEach(function (pair) {
          var v = pair[0];
          var key = v.file;
          var col = el('div', 'col');
          col.appendChild(el('div', 'lbl', pair[1] + ' — cap ' +
            (m[key] && m[key].capHeight != null ? m[key].capHeight : '—') + ' units'));
          var stage = el('div', 'stage aurora');
          stage.appendChild(tile({
            brand: v.brand, watermark: v.watermark, logo: key, padding: v.padding, theme: 'aurora'
          }));
          col.appendChild(stage);
          row.appendChild(col);
        });
      f.appendChild(row);
    });
  }

  /* The same one-line-or-two test, for the name the owner picked. */
  function buildHubLines() {
    var m = A.metrics.logos;
    var hub = A.new[0];
    var f = frame('lines-et-afsnit-til', 'Ét afsnit til on one line and on two',
      'Sagaer sets the family at ' + m['upload/sagaer.svg'].capHeight +
      ' units in the 320x100 box; the accented É reaches above the cap line, so the two-line ' +
      'setting is laid out on the accent rather than the cap.');
    var row = el('div', 'row');
    [['variants/et-afsnit-til-a-oneline.svg', 'One line'],
    ['upload/et-afsnit-til.svg', 'Two lines'],
    ['upload/sagaer.svg', 'Sagaer, for comparison']].forEach(function (pair) {
      var col = el('div', 'col');
      var cap = m[pair[0]] && m[pair[0]].capHeight;
      col.appendChild(el('div', 'lbl', pair[1] + ' — cap ' + (cap != null ? cap : '—') + ' units' +
        (cap != null ? ' (' + Math.round(cap / m['upload/sagaer.svg'].capHeight * 1000) / 10 + '%)' : '')));
      var stage = el('div', 'stage aurora');
      var isSag = pair[0].indexOf('sagaer') !== -1;
      stage.appendChild(tile({
        brand: isSag ? A.new[1].brand : hub.brand,
        watermark: isSag ? A.new[1].watermark : hub.watermark,
        logo: pair[0], padding: isSag ? A.new[1].padding : hub.padding, theme: 'aurora'
      }));
      col.appendChild(stage);
      row.appendChild(col);
    });
    f.appendChild(row);
  }

  /* ------------------------------------------------- the checkerboard */
  function boardFrame(id, caption, rows) {
    var f = frame(id, caption,
      'Each logo on the transparent ground and on a dark one: the checker shows there is no ' +
      'backing box, the dark strip shows the shape that white is drawing.');
    rows.forEach(function (row) {
      f.appendChild(el('div', 'lbl', row[1]));
      var pair = el('div', 'row');
      [['checker', ''], ['checker dark-pane', '']].forEach(function (kind) {
        var box = el('div', kind[0] === 'checker' ? 'checker' : 'checker dark-pane');
        var img = el('img');
        img.id = nextId();
        img.src = row[0];
        img.alt = '';
        box.appendChild(img);
        pair.appendChild(box);
      });
      f.appendChild(pair);
    });
  }

  function buildBoard() {
    boardFrame('board-a', 'Direction A · the three logos on a checkerboard', [
      ['upload/sagaer.svg', 'Sagaer'],
      ['upload/tidsmaskinen.svg', 'Tidsmaskinen'],
      ['upload/et-afsnit-til.svg', 'Ét afsnit til']
    ]);
    boardFrame('board-b', 'Direction B · the same three on a checkerboard', [
      ['upload/b/sagaer.svg', 'Sagaer'],
      ['upload/b/tidsmaskinen.svg', 'Tidsmaskinen'],
      ['marks/serie-hub-b.svg', 'Series hub — glyph only, name on hold']
    ]);
  }

  ({
    tiles: buildTiles,
    'rails-tv': function () { buildRails(1920); },
    'rails-phone': function () { buildRails(412); },
    compare: buildCompare,
    lines: buildLines,
    'hub-lines': buildHubLines,
    board: buildBoard
  })[set]();
})();
