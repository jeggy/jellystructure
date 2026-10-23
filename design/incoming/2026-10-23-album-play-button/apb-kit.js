/* apb-kit.js — the frame builder for the single-Play-album-button round (window.APBK).

   HOW THIS ROUND REUSES THE LAST TWO. The brief asks for the album-more-placement and
   music-playlists rounds' stylesheets and kits to be LINKED, not copied, and they are:
   frames.html loads music-play-all.css + kit (shipped), music-playlists.css + mpl-kit.js, and
   amp.css + amp-kit.js, then this round's pair last. So the bezel, the status bar, the app bar,
   the bottom bar, the mini player, the cover, the type, the skins, the three woff2 and the
   album header's "+ {k} extras" are the ones three previous rounds settled, byte for byte, and
   all four rounds' PNGs are diffable.

   MPLK exports phone() for the WHOLE album page but not its action row, which is exactly what
   this round varies. Rather than restate the phone frame here (which would be the copy the brief
   rules out), every frame is MPLK.phone() with TWO declared edits to the result:

       .mpl-dm     gains R373's "+ {k} extras"           (editions albums only)
       .mpl-acts   is replaced wholesale by this round's  (always — it IS the question)

   Those two are the entire surgery, and they are the same two the album-more-placement round made
   minus its third (the ⋯ never leaves the action row in this round, because the owner has fixed
   where it goes). Anything not named there is the linked kit's, untouched.

   The frame is built with mine:false, which is how MPLK.phone() is asked for an album header with
   NO ⋯ of its own in the action row — this round puts the single ⋯ there itself, to the right of
   the split button, which is where the owner put it. */
(function () {
  'use strict';
  const A = window.APB, K = window.MPLK;
  const ic = window.MPK.ic;

  let LANG = 'en';
  const setLang = (l) => { LANG = l; K.setLang(l); };
  /* this round's own words; everything else comes through K.T from the linked round's table */
  const T2 = (k, v) => {
    const s = (A.W[LANG] && A.W[LANG][k] != null) ? A.W[LANG][k] : A.W.en[k];
    return String(s == null ? k : s).replace(/\{(\w+)\}/g, (m, x) => (v && v[x] != null ? v[x] : m));
  };
  const V = (k) => A.VARIANTS.filter((x) => x.k === k)[0];

  /* ---- the four rows the menu is built from -----------------------------------------------
     Each is {glyph, label, sub, order} — `order` is 'al' or 'alx', the shipped pick values
     (ravilo-music.js:250), and it is what the ✓ and the relabel both key off. A shuffle row
     carries the same order as the play row above it, because Shuffle has never been a pick of
     its own: it shuffles inside whichever order is chosen (FR-R373-2 point 3, "both buttons
     follow it"). The subtitles are R373's own, and they belong to the ORDER, so they go on the
     play row and not on the shuffle row that repeats the same order. */
  const ROW = {
    play_al:  () => ({ g: 'play', lb: T2('play_album'),  sub: T2('sub_album',  { n: A.OFFICIAL }), o: 'al' }),
    play_alx: () => ({ g: 'play', lb: T2('play_extras'), sub: T2('sub_extras', { n: A.ALL }),      o: 'alx' }),
    shuf_al:  () => ({ g: 'shuffle', lb: K.T('shuffle'),          sub: null, o: 'al' }),
    shuf_alx: () => ({ g: 'shuffle', lb: T2('shuffle_extras'),    sub: null, o: 'alx' }),
  };

  /* the two groupings the brief asks to be drawn, as the owner wrote the first of them */
  const GROUPS = {
    order:  [['play_al', 'shuf_al'], ['play_alx', 'shuf_alx']],
    action: [['play_al', 'play_alx'], ['shuf_al', 'shuf_alx']],
  };

  /* one menu row. `pick` is the remembered order; the ✓ goes on the row that IS that order and
     is a play row — a tick on a shuffle row would claim the shuffle is remembered too, which
     nothing in R373 says and which REPORT.md asks about rather than drawing as settled. */
  function mrow(r, v, pick) {
    const on = v.tick && r.o === pick && r.g === 'play';
    const sub = (v.subs && r.sub) ? `<small>${r.sub}</small>` : '';
    return `<div class="mi${on ? ' on' : ''}">${ic(r.g)}`
      + `<span class="tx"><b>${r.lb}</b>${sub}</span>`
      + (on ? `<i>✓</i>` : '') + `</div>`;
  }

  /* the whole ▾ menu. `album` is 'ed' or 'plain': a plain album has ONE order, so its menu is the
     first group only and it carries no subtitle in any variant — the subtitle exists to tell two
     orders apart, and there is nothing to tell apart. That is also what makes "do the two album
     kinds read as one design?" a question the pictures can answer. */
  function ddmenu(v, o, cls) {
    const plain = o.album === 'plain';
    const pick = o.state === 'relabel' ? 'alx' : 'al';
    const vv = plain ? Object.assign({}, v, { subs: false, tick: false }) : v;
    const groups = plain
      ? [['play_al', 'shuf_al']]
      : GROUPS[v.group];
    const body = groups
      .map((g) => g.map((n) => mrow(ROW[n](), vv, pick)).join(''))
      .join('<div class="sep"></div>');
    return `<div class="${cls}">`
      + (v.heading ? `<div class="hd">${T2('more_play')}</div>` : '')
      + body + `</div>`;
  }

  /* the ⋯ menu — FR-6's one row today, drawn as the home for the album actions the owner says
     will join it, exactly as the linked round draws it (.mpl-menu, right-aligned) */
  const moremenu = () => `<div class="mpl-menu"><div class="mi">${ic('plist')}${K.T('add_playlist')}</div></div>`;

  /* ---- the action row --------------------------------------------------------------------- */

  /* the single split button. The main part's label: an editions album in the 'relabel' state
     shows the last pick when the variant relabels; everything else shows Play album. A PLAIN
     album says Play album too, so that the two album kinds are one design — note that this
     changes the shipped plain-album button, which says Play (music.play) today, and REPORT.md
     raises it rather than hiding it in a picture. */
  function split(v, o) {
    const relabelled = o.album === 'ed' && o.state === 'relabel' && v.relabel;
    const label = relabelled ? T2('play_extras') : T2('play_album');
    /* o.seam === 'one' is NOT a variant — see apb.css. Every variant frame is drawn exactly as
       production draws the split, seam and all, so the four are compared against the real row. */
    return `<div class="sp${o.seam === 'one' ? ' apb-onegrad' : ''}">`
      + `<div class="bt pri a">${ic('play')}<span class="lb">${label}</span></div>`
      + `<div class="bt pri dd">${ic('chev')}</div></div>`;
  }

  function acts(v, o) {
    const hot = o.state === 'more';
    return `<div class="mpl-acts ed">`
      + split(v, o)
      + `<div class="bt mb${hot ? ' hot' : ''}">${ic('more')}</div>`
      + (o.state === 'menu' ? ddmenu(v, o, 'apb-menu') : '')
      + (hot ? moremenu() : '')
      + `</div>`;
  }

  /* ---- a phone frame ----------------------------------------------------------------------
     o.id       the element id, which names the PNG
     o.variant  'A' | 'B' | 'C' | 'D'
     o.album    'ed' (the album has editions) | 'plain'
     o.state    'rest' | 'relabel' (after a pick) | 'menu' (the ▾ open) | 'more' (the ⋯ open)
     o.lang     'en' | 'da' | 'fo'        o.skin  'aurora' | 'midnight' | 'noir' */
  function phone(o) {
    setLang(o.lang || 'en');
    const v = V(o.variant);
    const host = document.createElement('div');
    host.innerHTML = K.phone({ skin: o.skin || 'aurora', page: 'album', kind: 'album', mine: false });
    const fr = host.firstElementChild;
    fr.id = o.id;

    /* edit 1 — R373 point 1: the header carries "+ {k} extra(s)" after the official album's own
       count and length, so that an album WITH extras does not draw as one without. */
    if (o.album === 'ed') {
      fr.querySelector('.mpl-dm').insertAdjacentHTML('beforeend',
        `<span>·</span><span class="apb-ex">${T2('n_extras', { n: A.EXTRAS })}</span>`);
    }

    /* edit 2 — the action row, which is the question */
    fr.querySelector('.mpl-acts').outerHTML = acts(v, o);
    return fr;
  }

  /* ---- a desktop frame -------------------------------------------------------------------
     The brief's question 5: the desktop has room for two buttons, but the owner asked for one, so
     the desktop header is drawn in the same shape — one split, one ⋯ — rather than keeping the
     pair and letting the two surfaces disagree. One frame per variant at rest, plus the ▾ open
     where the set asks for it. */
  function desk(o) {
    setLang(o.lang || 'en');
    const v = V(o.variant);
    const host = document.createElement('div');
    host.innerHTML = K.desk({ skin: o.skin || 'aurora', page: 'album', kind: 'album', mine: false });
    const fr = host.firstElementChild;
    fr.id = o.id;
    const relabelled = o.state === 'relabel' && v.relabel;
    const label = relabelled ? T2('play_extras') : T2('play_album');
    fr.querySelector('.mpl-dhead .ml').insertAdjacentHTML('beforeend',
      `<span class="apb-ex">${T2('n_extras', { n: A.EXTRAS })}</span>`);
    fr.querySelector('.mpl-dhead .btns').innerHTML =
      `<div class="apb-dsp"><div class="mpl-bt pri">${ic('play')}${label}</div>`
      + `<div class="mpl-bt pri dd">${ic('chev')}</div></div>`
      /* The ⋯ is wrapped, and its menu hangs from the WRAPPER rather than from .btns. The linked
         round's own ⋯ menu is `left:auto;right:0` against .btns, which is right when the ⋯ is the
         last thing in a short row — and wrong here: .btns is as wide as the h1 above it, so the
         menu landed about 120 px to the RIGHT of the button it belongs to, reading as a menu from
         nothing. Only the picture said so. */
      + `<span class="apb-dmw"><span class="mpl-bt mpl-dmb${o.state === 'more' ? ' hot' : ''}">${ic('more')}</span>`
      + (o.state === 'more' ? `<div class="mpl-dmenu"><div class="mi">${ic('plist')}${K.T('add_playlist')}</div></div>` : '')
      + `</span>`
      + (o.state === 'menu' ? ddmenu(v, { album: o.album || 'ed', state: 'rest' }, 'apb-dmenu') : '');
    return fr;
  }

  window.APBK = { phone, desk, T2, setLang, V };
})();
