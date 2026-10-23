/* amp-kit.js — the frame builder for the album-⋯-placement round (window.AMPK).

   HOW THIS ROUND REUSES THE LAST ONE. The brief asks for the music-playlists round's stylesheet
   and kit to be LINKED, not copied, and they are: frames.html loads
   ../2026-10-23-music-playlists/music-playlists.css and .../mpl-kit.js, which in turn link the
   shipped ../../ravilo/music-play-all/ stylesheet and kit. So the bezel, the status bar, the app
   bar, the bottom bar, the mini player, the cover, the type, the skins and the three woff2 are the
   ones two previous rounds settled, byte for byte, and the pictures are diffable against both.

   MPLK exports phone() — not the album header's action row, which is exactly what this round
   varies. Rather than restate the whole phone frame here (which would be the copy the brief rules
   out), every frame is built by calling MPLK.phone() for the WHOLE album page and then making
   three declared edits to the result, at three named classes:

       .mpl-dm     gains R373's "+ {k} extras"            (editions albums only)
       .mpl-acts   is replaced wholesale by this round's   (always — it IS the question)
       .mpl-dtop / .mpl-dbody  gain the ⋯                  (options B and C only)

   Those three are the entire surgery. Anything not named there is the linked kit's, untouched.

   The frame is built with mine:false, which is how MPLK.phone() is asked for an album header with
   NO ⋯ in its action row — this round puts the ⋯ back, in five different places. */
(function () {
  'use strict';
  const A = window.AMP, K = window.MPLK;
  const ic = window.MPK.ic;

  let LANG = 'en';
  const setLang = (l) => { LANG = l; K.setLang(l); };
  /* this round's own words; everything else comes through K.T from the linked round's table */
  const T2 = (k, v) => {
    const s = (A.W[LANG] && A.W[LANG][k] != null) ? A.W[LANG][k] : A.W.en[k];
    return String(s == null ? k : s).replace(/\{(\w+)\}/g, (m, x) => (v && v[x] != null ? v[x] : m));
  };

  /* ---- the pieces every option is built from ---------------------------------------------- */

  const more = (cls, hot) => `<div class="${cls}${hot ? ' hot' : ''}">${ic('more')}</div>`;

  /* The ⋯ menu. FR-6 gives it ONE row today, Add to playlist…, and says more will join it — so
     every option is drawn as the place secondary album actions live, not as a one-off. Option A
     is the only one whose menu has to carry the extras as well, because it is the only one that
     takes the two ▾ menus away. */
  const menu = (opt, cls) => `<div class="mpl-menu${cls ? ' ' + cls : ''}">`
    + (opt === 'A'
      ? `<div class="mi">${ic('play')}${T2('play_extras_row')}</div>`
        + `<div class="mi">${ic('shuffle')}${T2('shuffle_extras_row')}</div><div class="sep"></div>`
      : '')
    + `<div class="mi">${ic('plist')}${K.T('add_playlist')}</div></div>`;

  /* R373's split buttons, at the SHIPPED numbers from ravilo-music.css (.re-acts / .re-split):
     the ▾ half is 44 px wide, not the 40 the playlists round drew. amp.css restates the 44. */
  const splitPlay = (label, wide) =>
    `<div class="sp${wide ? ' amp-wide' : ''}"><div class="bt pri a">${ic('play')}<span class="lb">${label}</span></div>`
    + `<div class="bt pri dd">${ic('chev')}</div></div>`;
  const splitShuffle = (compact) =>
    `<div class="sp sh${compact ? ' amp-ic' : ''}"><div class="bt a">${ic('shuffle')}`
    + (compact ? '' : `<span class="lb">${K.T('shuffle')}</span>`)
    + `</div><div class="bt dd">${ic('chev')}</div></div>`;

  /* Today's row, for an album that has no editions: Play · Shuffle, both short in all three
     languages, with the ⋯ beside them when the option leaves it there. */
  const plainRow = (o, withMore) => {
    const hot = o.state === 'menu';
    return `<div class="mpl-acts"><div class="bt pri">${ic('play')}${K.T('play')}</div>`
      + `<div class="bt">${ic('shuffle')}${K.T('shuffle')}</div>`
      + (withMore ? more('bt mb', hot) + (hot ? menu(o.option) : '') : '') + `</div>`;
  };

  /* ---- the action row, per option --------------------------------------------------------- */
  function acts(o) {
    const hot = o.state === 'menu';
    const mb = more('bt mb', hot);
    if (o.album === 'plain') {
      /* B and C take the ⋯ off this row on EVERY album, which is the point of them: a plain
         album under B or C keeps Play and Shuffle and nothing else. */
      return plainRow(o, o.option !== 'B' && o.option !== 'C');
    }
    const play = o.state === 'relabel' ? T2('play_extras') : T2('play_album');
    if (o.option === 'A') {
      /* As delivered on the playlists round: both ▾s give up their split and the one ⋯ carries
         every secondary way to act on the album. */
      return `<div class="mpl-acts"><div class="bt pri">${ic('play')}${T2('play_album')}</div>`
        + `<div class="bt">${ic('shuffle')}${K.T('shuffle')}</div>`
        + mb + (hot ? menu('A') : '') + `</div>`;
    }
    if (o.option === 'B' || o.option === 'C') {
      /* R373's row, untouched. The ⋯ is elsewhere on the page. */
      return `<div class="mpl-acts ed">${splitPlay(play)}${splitShuffle(false)}</div>`;
    }
    if (o.option === 'D') {
      return `<div class="mpl-acts ed">${splitPlay(play)}${splitShuffle(true)}${mb}`
        + (hot ? menu('D') : '') + `</div>`;
    }
    /* E — the primary takes a row of its own, so its label has the whole 324 dp; Shuffle ▾ and
       the ⋯ share the row under it. */
    return `<div class="amp-stack"><div class="mpl-acts ed">${splitPlay(play, true)}</div>`
      + `<div class="mpl-acts ed">${splitShuffle(false)}${mb}`
      + (hot ? menu('E') : '') + `</div></div>`;
  }

  /* ---- a phone frame ----------------------------------------------------------------------
     o.id      the element id, which names the PNG
     o.option  'A' | 'B' | 'C' | 'D' | 'E'
     o.album   'ed' (the album has editions) | 'plain'
     o.state   'rest' | 'relabel' | 'menu'
     o.lang    'en' | 'da' | 'fo'       o.skin  'aurora' | 'midnight' | 'noir' */
  function phone(o) {
    setLang(o.lang || 'en');
    const skin = o.skin || 'aurora';
    const host = document.createElement('div');
    host.innerHTML = K.phone({ skin: skin, page: 'album', kind: 'album', mine: false });
    const fr = host.firstElementChild;
    fr.id = o.id;
    const hot = o.state === 'menu';

    /* edit 1 — R373 point 1: the header carries "+ {k} extra(s)" after the official album's own
       count and length. The playlists round's editions frame left it out, so nothing in that
       picture said the album HAD extras at all. */
    if (o.album === 'ed') {
      fr.querySelector('.mpl-dm').insertAdjacentHTML('beforeend',
        `<span>·</span><span class="amp-ex">${T2('n_extras', { n: A.EXTRAS })}</span>`);
    }

    /* edit 2 — the action row, which is the question */
    fr.querySelector('.mpl-acts').outerHTML = acts(o);

    /* edit 3 — where the ⋯ goes, for the two options that take it off the action row */
    if (o.option === 'B') {
      fr.querySelector('.mpl-dtop').insertAdjacentHTML('beforeend',
        more('amp-topmore', hot) + (hot ? menu('B', 'amp-mtop') : ''));
    } else if (o.option === 'C') {
      const body = fr.querySelector('.mpl-dbody');
      body.classList.add('amp-c');
      body.insertAdjacentHTML('afterbegin',
        more('amp-titlemore', hot) + (hot ? menu('C', 'amp-mtitle') : ''));
    }
    return fr;
  }

  /* ---- one desktop frame ------------------------------------------------------------------
     Drawn for the recommendation only, and only to answer the brief's own question about it: the
     desktop header has room today, so does E's two-row phone row imply a desktop change? It does
     not — this frame is R373's split pair plus FR-6's ⋯ on one 1120 px row, which is what
     dk-album-more already is with the split buttons put back. */
  function desk(o) {
    setLang(o.lang || 'en');
    const host = document.createElement('div');
    host.innerHTML = K.desk({ skin: o.skin || 'aurora', page: 'album', kind: 'album', mine: false });
    const fr = host.firstElementChild;
    fr.id = o.id;
    const play = o.state === 'relabel' ? T2('play_extras') : T2('play_album');
    fr.querySelector('.mpl-dhead .ml').insertAdjacentHTML('beforeend',
      `<span class="amp-ex">${T2('n_extras', { n: A.EXTRAS })}</span>`);
    fr.querySelector('.mpl-dhead .btns').innerHTML =
      `<div class="amp-dsp"><div class="mpl-bt pri">${ic('play')}${play}</div>`
      + `<div class="mpl-bt pri dd">${ic('chev')}</div></div>`
      + `<div class="amp-dsp"><div class="mpl-bt">${ic('shuffle')}${K.T('shuffle')}</div>`
      + `<div class="mpl-bt dd">${ic('chev')}</div></div>`
      + `<div class="mpl-bt mpl-dmb">${ic('more')}</div>`;
    return fr;
  }

  window.AMPK = { phone, desk, T2, setLang };
})();
