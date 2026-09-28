/* Phase 284 (prospective) — "the file is the record": the Files tab (research-reports/music-tags-in-the-files
   §7). ONE component, two field sets: the Album page (music) and the Book page (audiobooks). Picard's
   pending-changes idiom: one row per file, what the file says, and — when this page states something else — the
   page's value beneath it with the mark for what will happen. Also: TagsQ, the §8 ten questions as marked options
   (localStorage 'js-tags-q'), shared by every page that shows a piece of this. Mockup-only fences. */
(function () {
  const esc = s => (s == null ? '' : String(s)).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  /* ---- §8 questions ---- */
  const KEY = 'js-tags-q';
  const QS = [
    { k: 'def', n: 'Q1', t: 'The switch’s default', d: 'Off (281’s precedent), or on — the file is the record, so an unwritten match is unfinished work.', o: [['on', 'On for music, off for books', 1], ['off', 'Off for both']] },
    { k: 'junk', n: 'Q2', t: 'Frames jellystructure doesn’t manage', d: 'Keep them (a comment someone typed is theirs), with a checkbox that names the junk it would remove — or scrub to Picard’s clean set.', o: [['keep', 'Keep + named-junk checkbox', 1], ['scrub', 'Scrub to the clean set']] },
    { k: 'cover', n: 'Q3', t: 'The cover', d: 'cover.jpg beside the files (what Jellyfin and Kodi read), with <i>also embed</i> per album for phones’ file browsers and cars — or embedded in every file.', o: [['sidecar', 'Sidecar, embed per album', 1], ['embed', 'Embed in every file']] },
    { k: 'id3', n: 'Q4', t: 'ID3 version', d: 'All 22 MP3s are v2.3; Picard defaults to v2.4, which some cars and old Windows readers can’t parse.', o: [['keep', 'Keep the file’s · v2.4 only where none', 1], ['v24', 'Always v2.4']] },
    { k: 'gain', n: 'Q5', t: 'ReplayGain', d: 'Write Jellyfin’s loudness numbers as REPLAYGAIN_* — once the tag exists Jellyfin prefers it, so the two never disagree.', o: [['jf', 'Jellyfin’s numbers', 1], ['none', 'Don’t write gain']] },
    { k: 'lyr', n: 'Q6', t: 'Lyrics', d: 'Jellyfin turns embedded lyrics into a sidecar anyway, and a .lrc is easier to edit than a USLT frame.', o: [['sidecar', 'Sidecar only', 1], ['embed', 'Also embed']] },
    { k: 'foreign', n: 'Q7', t: 'A foreign edit found by a scan', d: 'Someone re-tagged in Picard, Lidarr wrote ids on import: new information, or a problem to hold against?', o: [['file', 'The file wins, unless locked', 1], ['hold', 'Warn and hold ours']] },
    { k: 'wma', n: 'Q8', t: 'WMA', d: 'Tag in place (every other reader sees it; Jellyfin won’t see the ids) or require Convert first.', o: [['both', 'Both offered, the panel says who sees what', 1], ['convert', 'Convert first']] },
    { k: 'where', n: 'Q9', t: 'Where a typed fact goes', d: 'A title typed on Tracks is a tag; a biography is artist.nfo; a comment is neither. The Files tab’s column groups make the split visible.', o: [['stated', 'As stated', 1]] },
    { k: 'save', n: 'Q10', t: 'The Save button once files and NFO are both written', d: 'Keep <i>Save → NFO · Save → files · Sync Jellyfin</i> and rename the fourth <i>Save everything</i> — or fold it all into one.', o: [['three', 'Keep them · “Save everything”', 1], ['one', 'One Save that does it all']] },
  ];
  const DEF = {}; QS.forEach(q => DEF[q.k] = q.o.find(o => o[2])[0]);
  let st = {}; try { st = JSON.parse(localStorage.getItem(KEY) || '{}'); } catch (e) {}
  const subs = [];
  const get = k => st[k] || DEF[k];
  function set(k, v) { st[k] = v; try { localStorage.setItem(KEY, JSON.stringify(st)); } catch (e) {} subs.forEach(f => f(k, v)); }
  function mountQs(el) {
    if (!el) return;
    const paint = () => {
      el.innerHTML = ''; el.className = ''; el.hidden = true; return; // owner 2026-09-28: decided — panel removed
      el.className = 'mu-qs';
      el.innerHTML = '<div class="row center" style="gap:8px;flex-wrap:wrap;margin-bottom:6px;"><h3>Tags in the files · decided</h3><span class="tiny muted">owner 2026-09-28: every lean taken · prospective 284 · the other option stays switchable here, mockup only</span><span class="spacer"></span><span class="btn sm ghost" data-tq-reset>Reset to leans</span></div>'
        + QS.map(q => '<div class="mu-q"><span class="qn">' + q.n + '</span><div class="qt">' + q.t + '</div><div class="qd">' + q.d + '</div><span class="seg">' + q.o.map(o => '<span data-tq="' + q.k + '" data-v="' + o[0] + '" class="' + (get(q.k) === o[0] ? 'on' : '') + '">' + o[1] + (o[2] ? '<span class="mu-lean">lean</span>' : '') + '</span>').join('') + '</span></div>').join('');
    };
    el.addEventListener('click', e => {
      if (e.target.closest('[data-tq-reset]')) { Object.keys(DEF).forEach(k => set(k, DEF[k])); paint(); return; }
      const b = e.target.closest('[data-tq]'); if (!b) return; set(b.dataset.tq, b.dataset.v); paint();
    });
    paint();
  }
  window.TagsQ = { get, set, on: f => subs.push(f), mount: mountQs, Q: QS };

  /* ---- the Files tab ---- */
  const STATES = [['agree', 'All agree'], ['never', 'Never written'], ['differ', '3 differ'], ['seeding', 'Everything seeding'], ['notagger', 'No tagger'], ['wma', 'An album of WMA'], ['foreign', 'Foreign edit found'], ['locked', 'Locked, file disagrees'], ['junk', 'Junk frames'], ['failed', 'Failed verify']];
  const BOOK_STATES = STATES.filter(s => s[0] !== 'wma' && s[0] !== 'locked');
  const SEED = '<svg viewBox="0 0 11 12" width="10" height="11"><rect x="1" y="5" width="9" height="6.2" rx="1.6" fill="currentColor"/><path d="M3.1 5V3.5a2.4 2.4 0 0 1 4.8 0V5" fill="none" stroke="currentColor" stroke-width="1.4"/></svg>';
  const S = { state: null, written: false, failed: false, embed: false, rmJunk: false, took: null };
  // measured 2026-09-28 (§1): no file carries a MusicBrainz id and nothing has ever been written — so every album opens "never written"
  const initState = spec => spec.kind === 'book' ? 'never' : (spec.tracks.length && spec.tracks.every(t => t.codec === 'WMA') ? 'wma' : 'never');
  // the switches (§7.3): music defaults per Q1, books stay 281's (off)
  const writeOn = kind => { const v = localStorage.getItem(kind === 'book' ? 'js-ab-tagwrite' : 'js-mu-tagwrite'); return v == null ? (kind !== 'book' && get('def') === 'on') : v === '1'; };

  function cols(spec) {
    if (spec.kind === 'book') return [
      ['Identity', [['title', 'Title'], ['album', 'Album'], ['artist', 'Author'], ['composer', 'Narrator'], ['track', 'Part']]],
      ['About the book', [['comment', 'Description'], ['publisher', 'Publisher'], ['genre', 'Genre']]],
      ['Extras', [['cover', 'Cover'], ['junk', 'Other frames']]]];
    return [
      ['Identity', [['title', 'Title'], ['artist', 'Artist'], ['albumartist', 'Album artist'], ['track', 'Track']]],
      ['Ids', [['rec', 'Recording'], ['rel', 'Release']]],
      ['Loudness', [['gain', 'Track gain']]],
      ['Extras', [['cover', 'Cover'], ['lyrics', 'Lyrics'], ['junk', 'Other frames']]]];
  }
  // one row per file → { key: { f: what the file says, p: what this page states (null = the same), k: mark } }
  function row(spec, t, i, state) {
    const wmaAll = state === 'wma', codec = wmaAll ? 'WMA' : t.codec, isWma = codec === 'WMA';
    const done = S.written && !(S.failed && i === 1);
    const base = state === 'never' || state === 'seeding' || state === 'notagger' || state === 'junk' || state === 'wma' || state === 'failed';
    const c = {}, same = v => ({ f: v, p: null, k: 'same' });
    const want = (f, p, k) => done ? same(p) : (f === p ? same(f) : { f, p, k: k || 'write' });
    if (spec.kind === 'book') {
      const P = { title: t.title, album: spec.title, artist: spec.author, composer: spec.narrator || '', track: t.n + '/' + spec.total, comment: spec.desc || '', publisher: spec.publisher || '', genre: spec.genre };
      const F = { title: t.title, album: spec.title, artist: spec.author, composer: '', track: String(t.n), comment: '', publisher: '', genre: 'Audiobook' };
      if (state === 'agree' || state === 'foreign') Object.assign(F, P);
      if (state === 'differ' && i < 3) { Object.assign(F, P); if (i === 0) F.title = t.title.toLowerCase(); if (i === 1) F.composer = ''; if (i === 2) F.track = String(t.n); }
      else if (state === 'differ') Object.assign(F, P);
      ['title', 'album', 'artist', 'composer', 'track', 'comment', 'publisher', 'genre'].forEach(k => {
        if (!P[k] && !F[k]) c[k] = { f: '', p: null, k: 'empty' };
        else if (!P[k] && F[k]) c[k] = { f: F[k], p: null, k: 'fileonly' };
        else c[k] = want(F[k], P[k]);
      });
      if (state === 'foreign') c.artist = { f: spec.author, p: null, k: 'foreign' };
    } else {
      const trackP = t.n + '/' + spec.total;
      const P = { title: t.title, artist: t.artist, albumartist: spec.artist, track: trackP, rec: t.rec, rel: spec.rel, gain: t.gain };
      const F = base ? { title: t.title, artist: t.artist, albumartist: isWma || i % 3 === 0 ? spec.artist : '', track: String(t.n), rec: '', rel: '', gain: '' } : Object.assign({}, P);
      if (state === 'differ') { if (i === 0) F.title = t.title.toLowerCase(); if (i === 1) F.albumartist = spec.artist + ' & friends'; if (i === 2) F.track = String(t.n); }
      if (state === 'locked') F.albumartist = spec.artist + ' (Remastered)';
      ['title', 'artist', 'albumartist', 'track'].forEach(k => c[k] = want(F[k], P[k]));
      ['rec', 'rel'].forEach(k => { const x = want(F[k], P[k] || ''); if (isWma && x.k === 'write') x.k = 'noreach'; c[k] = x; });
      c.gain = get('gain') === 'none' ? (F.gain ? { f: F.gain, p: null, k: 'fileonly' } : { f: '', p: null, k: 'empty' }) : want(F.gain, P.gain);
      if (state === 'foreign') c.albumartist = { f: F.albumartist, p: null, k: 'foreign' };
      if (state === 'locked' && !S.took) c.albumartist = { f: F.albumartist, p: spec.artist, k: 'hold' };
      if (state === 'locked' && S.took === 'file') c.albumartist = { f: F.albumartist, p: null, k: 'same' };
      if (state === 'locked' && S.took === 'ours') c.albumartist = { f: spec.artist, p: null, k: 'same' };
      c.lyrics = t.lyrics ? (get('lyr') === 'embed' && !done ? { f: '', p: 'embed from .lrc', k: 'write' } : { f: '.lrc beside', p: null, k: 'side' }) : { f: '', p: null, k: 'empty' };
    }
    const embedOn = get('cover') === 'embed' || S.embed;
    c.cover = spec.cover ? (embedOn && !done ? { f: '', p: 'embed cover.jpg', k: 'write' } : { f: embedOn && done ? 'embedded' : 'cover.jpg beside', p: null, k: 'side' }) : { f: '', p: null, k: 'empty' };
    const junk = isWma ? ['WM/EncodingSettings'] : state === 'junk' || i % 4 !== 3 ? ['PRIV ×' + (1 + i % 3)].concat(state === 'junk' && i % 3 === 0 ? ['WCOM'] : []) : [];
    const rm = (S.rmJunk || get('junk') === 'scrub') && !done;
    c.junk = junk.length ? { f: junk.join(' · '), p: rm ? 'removed' : null, k: rm ? 'junkrm' : 'junk' } : { f: '', p: null, k: 'empty' };
    const seeding = state === 'seeding';
    const id3 = isWma ? 'WMA · ASF' : spec.kind === 'book' ? 'MP3 · ID3v2.3' : 'MP3 · ID3v2.3' + (get('id3') === 'v24' && !done ? ' → v2.4' : '');
    const failed = S.failed && S.written && i === 1;
    return { t, c, seeding, id3, isWma, failed, file: spec.kind === 'book' ? String(t.n).padStart(2, '0') + ' - ' + t.title + '.mp3' : String(t.n).padStart(2, '0') + ' ' + t.title + (isWma ? '.wma' : '.mp3') };
  }
  const differs = r => !r.seeding && !r.failed && Object.values(r.c).some(x => x.k === 'write' || x.k === 'noreach' || x.k === 'junkrm' || x.k === 'hold');
  function cellHTML(x, seeding) {
    const f = x.f ? esc(x.f) : '<span class="ft-none">—</span>';
    if (seeding && (x.k === 'write' || x.k === 'noreach')) return '<td class="ft-c seed"><span class="fv">' + f + '</span><span class="pv">' + SEED + ' left as it is</span></td>';
    if (x.k === 'write') return '<td class="ft-c w"><span class="fv">' + f + '</span><span class="pv">→ ' + esc(x.p) + '</span></td>';
    if (x.k === 'noreach') return '<td class="ft-c a" title="Written for every other reader; Jellyfin reads no MusicBrainz ids from WMA"><span class="fv">' + f + '</span><span class="pv">→ ' + esc(x.p) + ' · not for Jellyfin</span></td>';
    if (x.k === 'fileonly') return '<td class="ft-c fo" title="The file has a value this page doesn’t — kept"><span class="fv">' + f + '</span><span class="pv">file only · kept</span></td>';
    if (x.k === 'junk') return '<td class="ft-c j"><span class="fv mono">' + f + '</span><span class="pv">kept</span></td>';
    if (x.k === 'junkrm') return '<td class="ft-c jr"><span class="fv mono">' + f + '</span><span class="pv">→ removed</span></td>';
    if (x.k === 'foreign') return '<td class="ft-c fr"><span class="fv">' + f + '</span><span class="pv">changed outside · taken</span></td>';
    if (x.k === 'hold') return '<td class="ft-c h"><span class="fv">' + f + '</span><span class="pv">locked · ours says ' + esc(x.p) + '</span></td>';
    if (x.k === 'side') return '<td class="ft-c sd"><span class="fv">' + f + '</span></td>';
    return '<td class="ft-c' + (x.k === 'empty' ? ' e' : '') + '"><span class="fv">' + f + '</span></td>';
  }
  function html(spec) {
    const state = S.state, R = spec.tracks.map((t, i) => row(spec, t, i, state)), C = cols(spec);
    const nd = R.filter(differs).length, nSeed = R.filter(r => r.seeding).length, nWma = R.filter(r => r.isWma).length;
    const noun = spec.kind === 'book' ? 'part' : 'file';
    const written = S.written ? 'written by jellystructure just now' : state === 'never' || state === 'wma' || state === 'notagger' || state === 'seeding' || state === 'junk' || state === 'failed' ? 'never written' : 'written by jellystructure Tuesday';
    const junkN = {}; R.forEach(r => r.c.junk.f && r.c.junk.f.split(' · ').forEach(j => { const k = j.replace(/ ×\d+/, ''); junkN[k] = (junkN[k] || 0) + (+((j.match(/×(\d+)/) || [0, 1])[1])); }));
    const junkTxt = Object.entries(junkN).map(([k, v]) => k + ' ×' + v).join(', ');
    const tagger = state !== 'notagger';
    let reason = '';
    if (!writeOn(spec.kind)) reason = 'Tag writing is off in Settings → Music providers';
    else if (!tagger) reason = 'This server has no tagger';
    else if (nSeed === R.length) reason = 'Every file is seeding';
    else if (state === 'wma' && get('wma') === 'convert') reason = 'Convert these first — WMA is tagged in the new files';
    else if (!nd) reason = 'Nothing differs';
    let h = '<div class="ft-fence"><span class="fl">Preview · the Files tab’s states</span><span class="seg">' + (spec.kind === 'book' ? BOOK_STATES : STATES).map(s => '<span data-ftst="' + s[0] + '" class="' + (state === s[0] ? 'on' : '') + '">' + s[1] + '</span>').join('') + '</span></div>';
    h += '<div class="ft-head"><b>Tags in ' + R.length + ' ' + noun + (R.length > 1 ? 's' : '') + '</b><span>·</span><span>' + (nd ? nd + ' say' + (nd === 1 ? 's' : '') + ' something different from this page' : 'all say what this page says') + '</span><span>·</span><span class="muted">' + written + '</span>'
      + (nSeed ? '<span class="ft-seedl">' + SEED + ' ' + (nSeed === R.length ? 'every file is seeding and will be left alone' : nSeed + ' file' + (nSeed > 1 ? 's are' : ' is') + ' seeding and will be left alone') + '</span>' : '')
      + (!tagger ? '<span class="ft-seedl">This server has no tagger — the image is missing python3-mutagen</span>' : '') + '</div>';
    if (state === 'foreign') h += '<div class="ft-info"><span class="badge info">From the last scan</span><div><b>These files were changed outside jellystructure</b> — album artist on ' + R.length + ' files, by another tagger. ' + (get('foreign') === 'file' ? 'The page now says what the files say; History has the line.' : 'This page still holds its own version until you choose.') + '</div>' + (get('foreign') === 'hold' ? '<span class="btn sm" data-ft="takefile">Take the files’</span><span class="btn sm ghost" data-ft="writeours">Write ours</span>' : '') + '</div>';
    if (state === 'locked' && !S.took) h += '<div class="ft-info w"><span class="badge warn">Locked</span><div><b>The files disagree with this locked album</b> on album artist. A lock is the one thing that holds our version against the file.</div><span class="btn sm" data-ft="takefile">Take the file’s</span><span class="btn sm primary" data-ft="writeours">Write ours</span></div>';
    if (state === 'wma') h += '<div class="ft-info a"><span class="badge warn">WMA</span><div><b>Every other reader sees these tags; Jellyfin won’t see the ids.</b> Its tag library has no mapping for MusicBrainz ids in WMA. Convert makes new files with the full set, which Jellyfin reads.</div></div>';
    h += '<div class="mu-scroll"><table class="ft-grid"><thead><tr class="g"><th rowspan="2" class="ft-file">File</th>' + C.map(g => '<th colspan="' + g[1].length + '">' + g[0] + '</th>').join('') + '</tr><tr>' + C.map(g => g[1].map(c => '<th>' + c[1] + '</th>').join('')).join('') + '</tr></thead><tbody>'
      + R.map(r => '<tr id="ft-' + esc(r.t.id) + '" class="' + (r.failed ? 'failed' : '') + (differs(r) ? ' d' : '') + '"><td class="ft-file"><span class="mono">' + esc(r.file) + '</span><span class="ft-fmt' + (r.isWma ? ' w' : '') + '">' + r.id3 + '</span>' + (r.failed ? '<span class="ft-fail">left as it was: ffprobe could not read the result</span>' : '') + '</td>'
        + C.map(g => g[1].map(c => cellHTML(r.c[c[0]], r.seeding)).join('')).join('') + '</tr>').join('')
      + '</tbody></table></div>';
    h += '<div class="ft-legend"><span><i class="w"></i>will write</span><span><i class="fo"></i>file only · kept</span>' + (spec.kind !== 'book' ? '<span><i class="a"></i>written · Jellyfin won’t read it from WMA</span>' : '') + '<span>' + SEED + ' seeding · left alone</span><span><i class="j"></i>a frame we don’t manage</span>'
      + (spec.kind !== 'book' ? '<span class="muted">Recording = the MusicBrainz recording · Release = the release this track is on. Five more ids are written with them.</span>' : '') + '</div>';
    const embedOn = get('cover') === 'embed';
    h += '<div class="ft-acts"><span class="btn primary" data-ft="write"' + (reason ? ' aria-disabled="true" title="' + esc(reason) + '"' : '') + '>Write tags to ' + (nd || R.length - nSeed) + ' ' + noun + ((nd || R.length - nSeed) === 1 ? '' : 's') + '</span>'
      + (reason ? '<span class="tiny muted">' + esc(reason) + '</span>' : '')
      + (spec.cover && !embedOn ? '<label class="ft-chk"><input type="checkbox" data-ft="embed"' + (S.embed ? ' checked' : '') + '> Also embed the cover</label>' : '')
      + (junkTxt && get('junk') === 'keep' ? '<label class="ft-chk"><input type="checkbox" data-ft="rmjunk"' + (S.rmJunk ? ' checked' : '') + '> Also remove junk frames (' + esc(junkTxt) + ')</label>' : '')
      + (nWma && spec.kind !== 'book' ? '<span class="btn" data-ft="convert">Convert and tag ' + nWma + '</span>' : '') + '</div>';
    if (S.written) h += '<div class="ft-after">' + (S.failed ? '<span class="dot warn"></span> Tags written into ' + (R.length - 1) + ' ' + noun + 's · 1 left as it was (ffprobe could not read the result). ' : '<span class="dot ok"></span> Tags written. ') + 'Jellyfin re-read the ' + (spec.kind === 'book' ? 'book' : 'album') + '. History has the entry.</div>';
    h += '<div class="tiny muted" style="margin-top:10px;line-height:1.55">' + (spec.kind === 'book'
      ? 'The narrator goes into the composer field and the description into the comment — where Jellyfin, Audiobookshelf and a phone’s player look. Nothing is written as a side effect of a scan.'
      : 'What goes where (Q9): a fact about the song is a tag; the biography is <span class="mono">artist.nfo</span>; our match reasoning, locks and history stay in jellystructure’s tables. A scan never writes; a file seeding in qBittorrent is never touched.') + '</div>';
    return h;
  }
  let cur = null, subbed = false;
  function mount(el, spec, onChange) {
    if (!S.state) S.state = new URLSearchParams(location.search).get('files') || initState(spec);
    const paint = () => { el.innerHTML = html(spec); onChange && onChange(); };
    cur = { el, paint };
    if (!subbed) { subbed = true; TagsQ.on(() => { if (cur && cur.el.querySelector('.ft-grid')) cur.paint(); }); }
    el.onclick = e => {
      const s = e.target.closest('[data-ftst]'); if (s) { S.state = s.dataset.ftst; S.written = false; S.failed = false; S.took = null; return paint(); }
      const a = e.target.closest('[data-ft]'); if (!a || a.tagName === 'INPUT') return;
      const k = a.dataset.ft;
      if (k === 'write') { if (a.getAttribute('aria-disabled')) return; S.written = true; S.failed = S.state === 'failed'; if (S.state === 'differ' || S.state === 'never' || S.state === 'junk') S.state = S.state; return paint(); }
      if (k === 'takefile') { S.took = 'file'; return paint(); }
      if (k === 'writeours') { S.took = 'ours'; S.written = true; return paint(); }
      if (k === 'convert') { const t = document.createElement('div'); t.className = 'toast'; t.textContent = 'Convert… with tagging on — the new files get the full set'; document.body.appendChild(t); setTimeout(() => t.remove(), 2200); }
    };
    el.onchange = e => { const a = e.target.closest('[data-ft]'); if (!a) return; if (a.dataset.ft === 'embed') S.embed = a.checked; if (a.dataset.ft === 'rmjunk') S.rmJunk = a.checked; paint(); };
    paint();
  }
  function highlight(id) { const r = document.getElementById('ft-' + id); if (!r) return; r.classList.add('hl'); const y = r.getBoundingClientRect().top + window.scrollY - 140; window.scrollTo({ top: y, behavior: 'smooth' }); setTimeout(() => r.classList.remove('hl'), 1800); }
  // the Tracks tab's glyph (§7.5): file agrees · file differs · file has no ids
  function glyph(spec, t, i) {
    if (!S.state) S.state = new URLSearchParams(location.search).get('files') || initState(spec);
    const r = row(spec, t, i, S.state);
    const noIds = r.c.rec && !r.c.rec.f;
    const g = differs(r) ? (noIds ? ['n', '∅', 'file has no ids'] : ['d', '≠', 'file differs from this page']) : ['ok', '✓', 'file agrees'];
    return '<a class="ft-g ' + g[0] + '" href="#" data-ftrow="' + esc(t.id) + '" title="' + g[2] + ' — open in Files">' + g[1] + '</a>';
  }
  function summary(spec) { if (!S.state) S.state = initState(spec); const R = spec.tracks.map((t, i) => row(spec, t, i, S.state)); return { n: R.length, differ: R.filter(differs).length, seeding: R.filter(r => r.seeding).length }; }
  // §7.2 — the split Save button, shared by Album · Artist · Book. Q10 lean: keep the separate entries, rename the first "Save everything".
  function saveMenu(o) {
    const on = writeOn(o.kind), all = o.nfo + (on ? ' + tags in ' + o.n + ' file' + (o.n === 1 ? '' : 's') : '');
    if (get('save') === 'one') return '<span class="btn primary" data-a="savesync" title="Writes ' + esc(all) + ', then asks Jellyfin to re-read">Save ↻</span>';
    const it = (a, ic, l, sub, off) => '<div class="menu-item" data-a="' + a + '"' + (off ? ' style="opacity:.45;cursor:not-allowed" data-off="1"' : '') + '><span class="mi-ic">' + ic + '</span><span>' + l + '<span class="mi-sub">' + sub + '</span></span></div>';
    const prim = on ? 'Save everything' : 'Save &amp; Sync';
    return '<span class="split"><span class="btn primary" data-a="savesync">' + prim + ' ↻</span><span class="btn primary split-caret menu-btn"><span class="caret">▾</span></span><div class="menu">'
      + it('savesync', '↻', prim, 'Write ' + esc(all) + ', then ask Jellyfin to re-read')
      + it('save', '↓', 'Save → ' + (o.nfo === 'cover.jpg' ? 'cover.jpg' : 'NFO'), 'Write ' + esc(o.nfo) + (o.where ? ' in the ' + o.where + ' folder' : ''))
      + it('savefiles', '✎', 'Save → files', on ? 'Tags in ' + o.n + ' file' + (o.n === 1 ? '' : 's') + (o.differ ? ' · ' + o.differ + ' say something different' : ' · all agree') + ' · seeding files skipped' : 'Tag writing is off in Settings → Music providers', !on)
      + it('sync', '↻', 'Sync Jellyfin', 'Ask Jellyfin to re-read (no rewrite)') + '</div></span>';
  }
  window.FilesTab = { mount, glyph, summary, highlight, writeOn, saveMenu, S };
})();
