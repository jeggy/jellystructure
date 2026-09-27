/* Music round 1 — the admin brief's seven questions (§H), drawn as marked options that switch the
   pages live. One store for every page (localStorage 'js-music-q'), so a pick made on the album
   page is still the pick on Settings. Mockup-only; the product has no such panel. */
(function () {
  const KEY = 'js-music-q';
  const Q = [
    { k: 'wma', n: 'H1', t: 'The 38 WMA files', d: 'Draw a <b>Convert…</b> action — a one-time repair job to AAC, with the honest “already lossy” sentence and a confirmation — or leave <i>re-encodes on a phone</i> as information only.', o: [['convert', 'Convert… action', 1], ['info', 'Information only']], pages: ['library', 'album', 'dashboard'] },
    { k: 'arith', n: 'H2', t: 'A candidate’s arithmetic', d: 'In Find match…, say how well a candidate fits as one sentence (<i>2 of 2 tracks agree on position and length</i>) or as a per-track mini table inside the candidate.', o: [['sentence', 'A sentence', 1], ['table', 'Per-track table']], pages: ['album'] },
    { k: 'genres', n: 'H3', t: 'Music genres on the Metadata page', d: 'Their own tab beside Genres, or a <i>Films &amp; series / Music</i> segment inside Genres. Either way never merged: MusicBrainz genres are a different id space from TMDB’s.', o: [['tab', 'Own tab', 1], ['segment', 'Segment in Genres']], pages: ['metadata'] },
    { k: 'disco', n: 'H4', t: 'Artist page — albums the library lacks', d: 'Only what is on disk, or also MusicBrainz’s discography greyed out (a shopping list, like Discover’s Request — a second feature).', o: [['disk', 'On disk only', 1], ['greyed', 'Show the rest greyed']], pages: ['artist'] },
    { k: 'prov', n: 'H5', t: 'Where the Music providers card lives', d: 'Settings → Connections beside Jellyfin/TMDB (they are metadata sources), or Download tools beside Bazarr.', o: [['connections', 'Connections', 1], ['downloads', 'Download tools']], pages: ['settings'] },
    { k: 'lyrics', n: 'H6', t: 'Fetch lyrics by default?', d: 'LRCLIB needs no key. On by default means the run gains <span class="mu-mono">fetch_lyrics</span> and the Tracks tab fills in; off means the switch is there and nothing is fetched until it is turned on.', o: [['on', 'On by default', 1], ['off', 'Off by default']], pages: ['settings', 'activity', 'album'] },
    { k: 'tags', n: 'H7', t: 'Write MusicBrainz ids into the files', d: 'A per-album action behind a confirmation, or not drawn at all — nothing touches a media file without being asked, and NFO + sidecars are enough for Jellyfin and Kodi.', o: [['none', 'Not drawn', 1], ['action', 'Per-album action']], pages: ['album'] },
    // part 2 — audiobooks (§M6)
    { k: 'abtags', n: 'M6·1', t: 'Audiobooks — write tags into the files', d: 'Jellyfin has no metadata file for audiobooks, so embedded tags are the only way its own apps show a narrator or a description. A switch in Settings (off by default), or leave the files alone for good.', o: [['draw', 'Draw the switch, off', 1], ['never', 'Leave the files alone']], pages: ['settings', 'audiobook'] },
    { k: 'abapply', n: 'M6·2', t: 'Suggestions rail — how a suggestion is taken', d: 'Apply one field at a time (a description from one provider, a cover from another), or only a whole card at once.', o: [['field', 'Per field', 1], ['card', 'Whole card only']], pages: ['audiobook'] },
    { k: 'abseries', n: 'M6·3', t: 'Series as a view of its own', d: 'A Series view in Library (present only when any book has one), or series is just a field on the book.', o: [['view', 'A view, when present', 1], ['field', 'A field only']], pages: ['library', 'audiobook', 'author'] },
    { k: 'ablisten', n: 'M6·4', t: 'The Listeners tab', d: 'Read-only, per household member — how far each has got. Or keep listening progress out of the admin entirely.', o: [['show', 'Show it, read-only', 1], ['hide', 'Keep it out']], pages: ['audiobook'] },
  ];
  const DEF = {}; Q.forEach(q => DEF[q.k] = q.o.find(o => o[2])[0]);
  let st = {}; try { st = JSON.parse(localStorage.getItem(KEY) || '{}'); } catch (e) {}
  const subs = [];
  function get(k) { return st[k] || DEF[k]; }
  function set(k, v) { st[k] = v; try { localStorage.setItem(KEY, JSON.stringify(st)); } catch (e) {} subs.forEach(f => f(k, v)); }
  function mount(el, page) {
    if (!el) return;
    const paint = () => {
      const here = Q.filter(q => q.pages.indexOf(page) >= 0).length;
      el.className = 'mu-qs';
      el.innerHTML = '<div class="row center" style="gap:8px;flex-wrap:wrap;margin-bottom:6px;"><h3>Music &amp; audiobooks · round-1 questions</h3><span class="tiny muted">design brief 2026-09-27 §H + §M6 · lean marked · mockup only</span><span class="spacer"></span>'
        + (here ? '<span class="tiny muted">' + here + ' of ' + Q.length + ' change this page</span>' : '') + '<span class="btn sm ghost" data-muq-reset>Reset to leans</span></div>'
        + Q.map(q => '<div class="mu-q' + (q.pages.indexOf(page) >= 0 ? ' here' : '') + '"><span class="qn">' + q.n + '</span><div class="qt">' + q.t + (q.pages.indexOf(page) >= 0 ? '<span class="mu-here">this page</span>' : '') + '</div>'
          + '<div class="qd">' + q.d + '</div><span class="seg">' + q.o.map(o => '<span data-muq="' + q.k + '" data-v="' + o[0] + '" class="' + (get(q.k) === o[0] ? 'on' : '') + '">' + o[1] + (o[2] ? '<span class="mu-lean">lean</span>' : '') + '</span>').join('') + '</span></div>').join('');
    };
    el.addEventListener('click', e => {
      if (e.target.closest('[data-muq-reset]')) { Object.keys(DEF).forEach(k => set(k, DEF[k])); paint(); return; }
      const b = e.target.closest('[data-muq]'); if (!b) return;
      set(b.dataset.muq, b.dataset.v); paint();
    });
    paint();
  }
  window.MusicQ = { get, set, mount, on: f => subs.push(f), Q };
})();
