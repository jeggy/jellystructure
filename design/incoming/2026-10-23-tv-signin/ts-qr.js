/* ts-qr.js — a real QR encoder, so the TV frames carry a code that actually scans.

   Round `2026-10-23-tv-signin`. The brief allows "a real QR of that string or a faithful
   placeholder". A placeholder would have been cheaper and would have been the wrong artefact:
   the one thing a reviewer and QA need to do with these pictures is point a phone at them, and
   a placeholder makes that impossible to try. So this is the encoder, written out.

   SCOPE, deliberately narrow. It encodes ONE shape — byte mode, version 3, error-correction
   level M, 29x29 modules — because that is what the round's single string needs and a general
   encoder would be a library nobody here maintains. FR-5's encoder in `:ravilo-ui` is Sjúrður's
   and is pure Kotlin on a Compose Canvas; this file is not a port target for it, it is how the
   picture gets a scannable code. The one number worth carrying across: `https://` + an
   18-character host + `/tv?c=` + six characters is 38 bytes, and version 3-M holds 44, so the
   household's real host has 6 bytes of headroom before the TV needs version 4.

   Verified by `verify-qr.py` beside it, which re-reads the matrix the other way round — reads
   the format info and checks its BCH, removes the mask, walks the data modules, checks all 26
   Reed-Solomon syndromes are zero and parses the byte-mode segment back to the string. That
   round trip is the only check available from this seat: there is no camera here and no decoder
   on the host, so a PHYSICAL SCAN IS STILL OWED and the report says so.

   No network reference, no dependency, ES5 register to match the rest of the round.
*/
window.TSQr = (function () {
  'use strict';

  /* ---- GF(256), primitive polynomial 0x11D, the QR field. ---- */
  var EXP = new Array(512), LOG = new Array(256);
  (function () {
    var x = 1;
    for (var i = 0; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if (x & 0x100) x ^= 0x11d; }
    for (var j = 255; j < 512; j++) EXP[j] = EXP[j - 255];
  })();
  function gmul(a, b) { return (a === 0 || b === 0) ? 0 : EXP[LOG[a] + LOG[b]]; }

  /* The generator polynomial for `n` error-correction codewords: (x-a^0)(x-a^1)…(x-a^(n-1)).
     Computed rather than tabled, so there is no table to mistype. */
  function generator(n) {
    var g = [1];
    for (var i = 0; i < n; i++) {
      var next = new Array(g.length + 1);
      for (var k = 0; k < next.length; k++) next[k] = 0;
      /* Coefficients are descending, so g(x)*x lands on next[j] and g(x)*a^i on next[j+1].
         Getting these two the other way round is what the first run of verify-qr.py caught:
         25 of 26 syndromes non-zero on a matrix that was otherwise perfectly well formed. */
      for (var j = 0; j < g.length; j++) {
        next[j] ^= g[j];
        next[j + 1] ^= gmul(g[j], EXP[i]);
      }
      g = next;
    }
    return g;
  }

  /* ---- version 3-M, the one shape this file encodes ---- */
  var SIZE = 29, DATA_CW = 44, EC_CW = 26;
  var ALIGN = [22];          /* one alignment pattern, centred at (22,22) */
  var DARK = [21, 8];        /* the dark module: row 4*version+9, column 8 */

  /* ---- the data codewords: mode, length, bytes, terminator, pad ---- */
  function codewords(text) {
    var bytes = [], i, c;
    for (i = 0; i < text.length; i++) {
      c = text.charCodeAt(i);
      if (c > 127) throw new Error('ts-qr: ASCII only, got ' + c + ' — the round\'s string is a URL');
      bytes.push(c);
    }
    if (bytes.length > DATA_CW - 2) throw new Error('ts-qr: ' + bytes.length + ' bytes will not fit version 3-M (max ' + (DATA_CW - 2) + ')');

    /* Bits, most significant first: 0100 (byte mode), an 8-bit count (versions 1-9), the bytes. */
    var bits = [];
    function put(value, width) { for (var k = width - 1; k >= 0; k--) bits.push((value >>> k) & 1); }
    put(4, 4);
    put(bytes.length, 8);
    for (i = 0; i < bytes.length; i++) put(bytes[i], 8);

    /* Terminator: up to four zero bits, then zeros to the byte boundary. */
    var cap = DATA_CW * 8;
    for (i = 0; i < 4 && bits.length < cap; i++) bits.push(0);
    while (bits.length % 8 !== 0) bits.push(0);

    var cw = [];
    for (i = 0; i < bits.length; i += 8) {
      var b = 0;
      for (var j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
      cw.push(b);
    }
    /* Pad codewords, 0xEC and 0x11 alternating. */
    var pads = [0xec, 0x11], p = 0;
    while (cw.length < DATA_CW) { cw.push(pads[p]); p ^= 1; }

    /* Reed-Solomon: the remainder of the data polynomial divided by the generator. One block at
       version 3-M, so there is no interleaving to get wrong. */
    var g = generator(EC_CW), rem = cw.slice();
    for (i = 0; i < EC_CW; i++) rem.push(0);
    for (i = 0; i < DATA_CW; i++) {
      var lead = rem[i];
      if (lead === 0) continue;
      for (var k2 = 0; k2 < g.length; k2++) rem[i + k2] ^= gmul(g[k2], lead);
    }
    return cw.concat(rem.slice(DATA_CW, DATA_CW + EC_CW));
  }

  /* ---- the function patterns ---- */
  function blank() {
    var m = [], r, c;
    for (r = 0; r < SIZE; r++) { m.push([]); for (c = 0; c < SIZE; c++) m[r].push(null); }
    return m;
  }
  function finder(m, r0, c0) {
    /* The 7x7 eye plus its one-module separator. Written as a ring test rather than a literal
       grid, so a typo cannot produce a plausible-looking wrong eye. */
    for (var dr = -1; dr <= 7; dr++) for (var dc = -1; dc <= 7; dc++) {
      var r = r0 + dr, c = c0 + dc;
      if (r < 0 || c < 0 || r >= SIZE || c >= SIZE) continue;
      /* Rings out from the centre: 0 and 1 are the dark 3x3 core, 2 is the light ring, 3 is the
         dark border. Dark everywhere but ring 2. The first version of this line had the test
         inverted, which drew a perfectly regular eye with its colours swapped — verify-qr.py
         passed it without a murmur, because a finder is a function pattern and the decoder only
         reserves it. The TEXT DUMP of the matrix is what showed it. */
      var ring = Math.max(Math.abs(dr - 3), Math.abs(dc - 3));
      m[r][c] = (dr < 0 || dc < 0 || dr > 6 || dc > 6) ? 0 : (ring === 2 ? 0 : 1);
    }
  }
  function patterns(m) {
    var i, j;
    finder(m, 0, 0); finder(m, 0, SIZE - 7); finder(m, SIZE - 7, 0);
    /* Timing: row 6 and column 6, alternating, dark at even coordinates. */
    for (i = 8; i < SIZE - 8; i++) { m[6][i] = (i % 2 === 0) ? 1 : 0; m[i][6] = (i % 2 === 0) ? 1 : 0; }
    /* Alignment: a 5x5 eye centred on each coordinate pair, skipping those under a finder. */
    for (i = 0; i < ALIGN.length; i++) for (j = 0; j < ALIGN.length; j++) {
      var cr = ALIGN[i], cc = ALIGN[j];
      for (var dr = -2; dr <= 2; dr++) for (var dc = -2; dc <= 2; dc++) {
        var ring = Math.max(Math.abs(dr), Math.abs(dc));
        m[cr + dr][cc + dc] = (ring === 1) ? 0 : 1;
      }
    }
    m[DARK[0]][DARK[1]] = 1;
    /* The format-info areas are reserved now and written after masking. */
    for (i = 0; i <= 8; i++) {
      if (m[8][i] === null) m[8][i] = 0;
      if (m[i][8] === null) m[i][8] = 0;
    }
    for (i = 0; i < 8; i++) {
      if (m[8][SIZE - 1 - i] === null) m[8][SIZE - 1 - i] = 0;
      if (m[SIZE - 1 - i][8] === null) m[SIZE - 1 - i][8] = 0;
    }
  }

  /* ---- data placement: two-module columns, right to left, boustrophedon, skipping column 6 ---- */
  function place(m, cw) {
    var bits = [];
    for (var i = 0; i < cw.length; i++) for (var k = 7; k >= 0; k--) bits.push((cw[i] >>> k) & 1);
    var p = 0, up = true;
    for (var col = SIZE - 1; col > 0; col -= 2) {
      if (col === 6) col--;                       /* the vertical timing pattern is not a column */
      for (var n = 0; n < SIZE; n++) {
        var row = up ? SIZE - 1 - n : n;
        for (var d = 0; d < 2; d++) {
          var c = col - d;
          if (m[row][c] === null) m[row][c] = p < bits.length ? bits[p++] : 0;
        }
      }
      up = !up;
    }
    return p;
  }

  /* ---- the eight masks, and the penalty that picks one ---- */
  var MASKS = [
    function (i, j) { return (i + j) % 2 === 0; },
    function (i) { return i % 2 === 0; },
    function (i, j) { return j % 3 === 0; },
    function (i, j) { return (i + j) % 3 === 0; },
    function (i, j) { return (Math.floor(i / 2) + Math.floor(j / 3)) % 2 === 0; },
    function (i, j) { return (i * j) % 2 + (i * j) % 3 === 0; },
    function (i, j) { return ((i * j) % 2 + (i * j) % 3) % 2 === 0; },
    function (i, j) { return ((i + j) % 2 + (i * j) % 3) % 2 === 0; }
  ];
  /* A module is data (maskable) exactly where the blank matrix was still null before placement,
     so the reserved map is taken once, from a matrix that has only the function patterns in it. */
  function reservedMap() {
    var m = blank(); patterns(m);
    var res = [], r, c;
    for (r = 0; r < SIZE; r++) { res.push([]); for (c = 0; c < SIZE; c++) res[r].push(m[r][c] !== null); }
    return res;
  }
  function penalty(m) {
    var n = SIZE, score = 0, r, c, i;
    /* Rule 1 — five or more of one colour in a row or column. */
    function runs(get) {
      var s = 0;
      for (var a = 0; a < n; a++) {
        var run = 1;
        for (var b = 1; b < n; b++) {
          if (get(a, b) === get(a, b - 1)) run++;
          else { if (run >= 5) s += run - 2; run = 1; }
        }
        if (run >= 5) s += run - 2;
      }
      return s;
    }
    score += runs(function (a, b) { return m[a][b]; });
    score += runs(function (a, b) { return m[b][a]; });
    /* Rule 2 — every 2x2 block of one colour. */
    for (r = 0; r < n - 1; r++) for (c = 0; c < n - 1; c++) {
      var v = m[r][c];
      if (v === m[r][c + 1] && v === m[r + 1][c] && v === m[r + 1][c + 1]) score += 3;
    }
    /* Rule 3 — the finder-like 1:1:3:1:1 pattern with four light modules on either side. */
    var A = [1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0], B = [0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1];
    function hasAt(get, a, b, pat) {
      for (var k = 0; k < 11; k++) if (get(a, b + k) !== pat[k]) return false;
      return true;
    }
    function rule3(get) {
      var s = 0;
      for (var a = 0; a < n; a++) for (var b = 0; b + 11 <= n; b++) {
        if (hasAt(get, a, b, A)) s += 40;
        if (hasAt(get, a, b, B)) s += 40;
      }
      return s;
    }
    score += rule3(function (a, b) { return m[a][b]; });
    score += rule3(function (a, b) { return m[b][a]; });
    /* Rule 4 — how far the proportion of dark modules is from half. */
    var dark = 0;
    for (r = 0; r < n; r++) for (c = 0; c < n; c++) dark += m[r][c];
    var pct = dark * 100 / (n * n);
    score += Math.floor(Math.abs(pct - 50) / 5) * 10;
    return score;
  }

  /* ---- format information: 2 bits level + 3 bits mask, BCH(15,5), XOR 0x5412 ---- */
  function formatBits(maskIndex) {
    var data = (0 /* level M */ << 3) | maskIndex;   /* level M is 0b00 */
    var v = data << 10;
    for (var i = 4; i >= 0; i--) if (v & (1 << (i + 10))) v ^= 0x537 << i;   /* 0x537 = 0b10100110111 */
    return ((data << 10) | v) ^ 0x5412;
  }
  function writeFormat(m, maskIndex) {
    var bits = formatBits(maskIndex), i, b;
    /* Copy one: around the top-left eye, bit 0 at (8,0) going right, then up the column. */
    for (i = 0; i <= 5; i++) m[8][i] = (bits >>> i) & 1;
    m[8][7] = (bits >>> 6) & 1;
    m[8][8] = (bits >>> 7) & 1;
    m[7][8] = (bits >>> 8) & 1;
    for (i = 9; i <= 14; i++) m[14 - i][8] = (bits >>> i) & 1;
    /* Copy two: bits 0-6 up the bottom-left column (SEVEN modules, not eight — row 21 of that
       column is the dark module and is not a format bit), then bits 7-14 along the top-right
       row (eight modules). Writing eight bits up the column clobbers the dark module and leaves
       (8,28) unwritten; both copies then still pass BCH on their own, which is why this needed
       the decoder to find rather than a second look at the markup. */
    for (i = 0; i <= 6; i++) m[SIZE - 1 - i][8] = (bits >>> i) & 1;
    for (i = 7; i <= 14; i++) m[8][SIZE - 8 + (i - 7)] = (bits >>> i) & 1;
    m[DARK[0]][DARK[1]] = 1;
  }

  /* ---- the whole thing ---- */
  function encode(text) {
    var cw = codewords(text), res = reservedMap();
    var best = null, bestScore = Infinity, maskIndex;
    for (maskIndex = 0; maskIndex < 8; maskIndex++) {
      var m = blank();
      patterns(m);
      /* Re-null the data area, place, then mask. `patterns` filled the format reservations with
         zeros; those are rewritten below and are not maskable. */
      for (var r = 0; r < SIZE; r++) for (var c = 0; c < SIZE; c++) if (!res[r][c]) m[r][c] = null;
      var used = place(m, cw);
      if (used !== cw.length * 8) throw new Error('ts-qr: placed ' + used + ' of ' + (cw.length * 8) + ' bits');
      for (r = 0; r < SIZE; r++) for (c = 0; c < SIZE; c++) if (!res[r][c] && MASKS[maskIndex](r, c)) m[r][c] ^= 1;
      writeFormat(m, maskIndex);
      var s = penalty(m);
      if (s < bestScore) { bestScore = s; best = m; best.mask = maskIndex; }
    }
    return best;
  }

  /* ---- the matrix as an SVG, sized by the module, with the mandatory 4-module quiet zone ----
     One `<rect>` per dark module rather than a path: Chromium renders either identically at
     these sizes, and a rect list is what a reviewer can read in the markup. `idSeed` is taken
     because PROCESS.md's trap is real — every id in this round comes from a counter. */
  var seq = 0;
  function svg(text, modulePx, opts) {
    var m = encode(text), o = opts || {};
    var quiet = o.quiet === undefined ? 4 : o.quiet;
    var n = SIZE + quiet * 2, side = n * modulePx;
    var dark = o.dark || '#0a0c13', light = o.light || '#ffffff';
    var id = 'tsqr' + (++seq);
    var out = '<svg class="ts-qr" width="' + side + '" height="' + side + '" viewBox="0 0 ' + n + ' ' + n
      + '" role="img" aria-labelledby="' + id + '" shape-rendering="crispEdges">'
      + '<title id="' + id + '">' + text.replace(/&/g, '&amp;').replace(/</g, '&lt;') + '</title>'
      + '<rect width="' + n + '" height="' + n + '" fill="' + light + '"/>';
    for (var r = 0; r < SIZE; r++) {
      /* Horizontal runs merged, so the markup is readable and the paint is one rect per run. */
      var c = 0;
      while (c < SIZE) {
        if (!m[r][c]) { c++; continue; }
        var start = c;
        while (c < SIZE && m[r][c]) c++;
        out += '<rect x="' + (start + quiet) + '" y="' + (r + quiet) + '" width="' + (c - start)
          + '" height="1" fill="' + dark + '"/>';
      }
    }
    return out + '</svg>';
  }

  /* The matrix as text, for verify-qr.py. Not used by any frame. */
  function dump(text) {
    var m = encode(text), out = [];
    for (var r = 0; r < SIZE; r++) out.push(m[r].join(''));
    return out.join('\n');
  }

  return { encode: encode, svg: svg, dump: dump, SIZE: SIZE, CAPACITY: DATA_CW - 2 };
})();
