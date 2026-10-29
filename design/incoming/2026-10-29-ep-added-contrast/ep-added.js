/* ep-added.js — builds the round's two frames and measures the ratio off the rendered pixels.
 *
 * The row markup is a transcription of the `.ep-toggle-row` block that
 * src/wasmJsMain/kotlin/dev/jellystructure/ui/MediaDetail.kt returns for an episode: the same
 * classes, the same inline styles, in the same order. The only departures, both noted at their
 * line, are the still (a flat gradient instead of a TMDB URL, because the render has no network
 * and a broadcaster's picture is not ours to land) and the stand-in strings.
 *
 * Nothing here is a candidate for production.
 */
(function () {
  var O = window.EAOPT || { theme: 'light' };
  var light = O.theme === 'light';
  if (light) document.documentElement.setAttribute('data-theme', 'light');
  document.getElementById('ea-themename').textContent = light ? 'light' : 'dark';

  /* ---- the stand-in episode. An invented title; it names nothing in the library. ---- */
  var EP = { code: 'S02E05', title: 'Mountain Road', added: '2026-09-14', lang: 'dan' };

  function esc(s) {
    return String(s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  /* The shipped row, transcribed. `MediaDetail.kt` wraps it in a bordered div on --fill-2 and puts
     the toggle row inside; .ep-added is a `tiny` span sitting directly in that row, which is why
     nothing re-colours it and why --ink-dim reaches the pixels unchanged. */
  function row() {
    return '' +
      '<div style="border:1px solid var(--line);border-radius:6px;margin-bottom:6px;overflow:hidden;background:var(--fill-2);">' +
        '<div class="ep-toggle-row" style="display:flex;align-items:center;gap:10px;padding:9px 12px;cursor:pointer;">' +
          /* departure 1: the shipped still is an <img> on a TMDB URL. A gradient stands in. */
          '<div class="ea-still"></div>' +
          '<span class="num" style="min-width:64px;font-size:.82rem;">' + esc(EP.code) + '</span>' +
          '<span style="font-size:.85rem;font-weight:500;flex:none;max-width:240px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">' + esc(EP.title) + '</span>' +
          '<span class="tiny ep-added">added ' + esc(EP.added) + '</span>' +
          '<span class="lang" style="font-size:.72rem;flex:none;">' + esc(EP.lang) + '</span>' +
          '<div style="display:flex;gap:4px;flex-wrap:wrap;flex:1;">' +
            '<span class="chip mono" style="font-size:.72rem;">dan &middot; eac3 &#9733;</span>' +
            '<span class="chip mono" style="font-size:.72rem;">eng &middot; ac3</span>' +
            '<span class="chip mono" style="font-size:.72rem;">dan &middot; subrip</span>' +
          '</div>' +
          '<span class="ep-chev" style="color:var(--ink-soft);font-size:.9rem;margin-left:4px;">&rsaquo;</span>' +
        '</div>' +
      '</div>';
  }

  /* ---- contrast, measured ---- */

  function parse(c) {
    var m = c.match(/rgba?\(([^)]+)\)/);
    if (!m) return null;
    var p = m[1].split(/[,\s/]+/).filter(function (x) { return x !== ''; }).map(Number);
    return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
  }

  /* --ink-dim is an alpha, so the colour that actually reaches the eye is the composite over
     whatever surface is behind the text. Walk up for the first non-transparent background. */
  function surfaceOf(el) {
    for (var n = el; n && n !== document.documentElement; n = n.parentElement) {
      var bg = parse(getComputedStyle(n).backgroundColor);
      if (bg && bg.a > 0) return over(bg, { r: 255, g: 255, b: 255, a: 1 });
    }
    return { r: 255, g: 255, b: 255 };
  }

  function over(fg, bg) {
    return {
      r: fg.a * fg.r + (1 - fg.a) * bg.r,
      g: fg.a * fg.g + (1 - fg.a) * bg.g,
      b: fg.a * fg.b + (1 - fg.a) * bg.b
    };
  }

  function lum(c) {
    function ch(v) { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }
    return 0.2126 * ch(c.r) + 0.7152 * ch(c.g) + 0.0722 * ch(c.b);
  }

  function ratio(el) {
    var bg = surfaceOf(el);
    var fg = over(parse(getComputedStyle(el).color), bg);
    var a = lum(fg), b = lum(bg);
    var hi = Math.max(a, b), lo = Math.min(a, b);
    return (hi + 0.05) / (lo + 0.05);
  }

  function hex(c) {
    return '#' + [c.r, c.g, c.b].map(function (v) {
      return ('0' + Math.round(v).toString(16)).slice(-2);
    }).join('');
  }

  function measure(el) {
    var r = ratio(el);
    var bg = surfaceOf(el);
    var fg = over(parse(getComputedStyle(el).color), bg);
    var pass = r >= 4.5;
    return '' +
      '<div class="ea-measure">' +
        '<div class="ea-m"><span class="ea-k">measured</span><span class="ea-v">' + r.toFixed(2) + ' : 1</span>' +
          '<span class="ea-verdict ' + (pass ? 'pass' : 'fail') + '">' + (pass ? 'passes 4.5 : 1' : 'fails 4.5 : 1') + '</span></div>' +
        '<div class="ea-m"><span class="ea-k">token</span><span class="ea-v">' + getComputedStyle(el).color + '</span></div>' +
        '<div class="ea-m"><span class="ea-k">effective ink</span><span class="ea-v">' + hex(fg) + '</span></div>' +
        '<div class="ea-m"><span class="ea-k">on surface</span><span class="ea-v">' + hex(bg) + '</span></div>' +
      '</div>';
  }

  /* ---- assemble ---- */

  var halves = [
    { cls: 'before', title: 'Before &mdash; what the admin ships today', note: '<code>.ep-added</code> resolves to <b>--ink-dim</b>.' },
    { cls: 'after',  title: 'After &mdash; this round&rsquo;s fix',       note: light
        ? '<code>.ep-added</code> retargeted to <b>--ink-soft</b> in the light theme only.'
        : 'Unchanged. The light override does not apply here, so the dark theme ships exactly as it did.' }
  ];

  var html = '<div class="ea-frame" id="ep-added-' + (light ? 'light' : 'dark') + '"><div class="ea-inner"><div class="ea-pair">';
  halves.forEach(function (h) {
    html += '<div class="ea-half ' + h.cls + '">' +
      '<div class="ea-ftitle">' + h.title + '</div>' +
      '<div class="ea-fnote">' + h.note + '</div>' +
      row() +
      '<div class="ea-slot"></div>' +
      '</div>';
  });
  html += '</div></div></div>';
  document.getElementById('ea-frames').innerHTML = html;

  /* measured after layout, off the real elements, so the number in the picture is the number the
     browser resolved and not one this file asserted. */
  Array.prototype.forEach.call(document.querySelectorAll('.ea-half'), function (half) {
    half.querySelector('.ea-slot').innerHTML = measure(half.querySelector('.ep-added'));
  });
})();
