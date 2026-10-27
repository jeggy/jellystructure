(function () {
  const toast = (m) => (window.toast ? window.toast(m) : console.log(m));
  const main = document.querySelector('main');
  const $ = (s) => document.querySelector(s);

  /* ---- preview fence ---- */
  let timers = [];
  function clearRun() { timers.forEach(clearTimeout); timers = []; document.querySelectorAll('.af-step').forEach(s => s.classList.remove('run', 'done', 'fail')); }
  function setKey(on) {
    try { localStorage.setItem('js-ai-key', on ? '1' : '0'); } catch (e) {}
    const nav = document.querySelector('.app-side a.nav[href="ai.html"]');
    if (nav) nav.style.display = on ? '' : 'none';
    document.querySelectorAll('.app-side .status .row').forEach(r => { if (/AI key/.test(r.textContent)) r.style.display = on ? '' : 'none'; });
  }
  function setState(s) {
    clearRun();
    main.dataset.state = s;
    document.querySelectorAll('#af-state span').forEach(x => x.classList.toggle('on', x.dataset.s === s));
    setKey(s !== 'nokey');
    if (s === 'tmdb') openTq('royndin-2026e01');
    if (s === 'run') playRun();
    if (s === 'review') steps.forEach(st => st.classList.add('done'));
    if (s === 'fail') { steps.forEach((st, i) => st.classList.add(i < 2 ? 'done' : i === 2 ? 'fail' : '')); $('#af-series-fx').textContent = '1 video · no recurring title · no season/episode numbering → stopped, nothing written'; }
    else $('#af-series-fx').textContent = '13 items with recurring title + season grouping · matches TMDB “Royndin” (3 seasons) · not a film';
  }
  $('#af-state').addEventListener('click', e => { const t = e.target.closest('[data-s]'); if (t) setState(t.dataset.s); });

  /* ---- investigation animation ---- */
  const steps = [...document.querySelectorAll('.af-step')];
  function playRun() {
    const durs = [1400, 2200, 1200, 1800, 1500];
    let t = 200;
    steps.forEach((st, i) => {
      timers.push(setTimeout(() => { steps.slice(0, i).forEach(p => p.classList.add('done')); st.classList.add('run'); }, t));
      t += durs[i];
    });
    timers.push(setTimeout(() => { if (main.dataset.state === 'run') setState('review'); }, t));
  }
  $('#af-go').addEventListener('click', () => setState('run'));
  $('#af-stop').addEventListener('click', () => { setState('list'); toast('Investigation stopped — nothing was written'); });
  $('#af-discard').addEventListener('click', () => setState('list'));
  $('#af-showall').addEventListener('click', e => { e.preventDefault(); toast('All 13 mappings (mockup)'); });

  /* ---- TMDB echo ---- */
  const ECHO = {
    '9000001': '<b>Royndin</b> · TV series · 3 seasons listed on TMDB · Faroese · <a href="series.html">not in library yet</a>',
    '': 'Enter a TMDB id to confirm the series.',
  };
  $('#af-tmdb').addEventListener('input', e => {
    const v = e.target.value.trim();
    $('#af-echo').innerHTML = '<span class="ph"></span><span>' + (ECHO[v] || (v.length < 4 ? ECHO[''] : '<b>TMDB ' + v + '</b> · <span style="color:var(--warn)">this id is a film — Auto-fetch needs a series</span>')) + '</span>';
    $('#af-go').classList.toggle('disabled', !ECHO[v] || !v);
  });

  /* ---- fetchers ---- */
  const F = [
    { id: 'royndin', name: 'Royndin', src: 'varpid.example.fo/sendingar/royndin', tmdb: 9000001, pat: 'S{year}E{partur|(n:m)}', sched: 'Daily · 04:00', last: { ok: true, text: 'no new episodes', when: '04:00 today' }, q: '1080p HLS', on: true, have: 13,
      runs: [['new', '+1 · S2026E01 (1080p, 1.9 GB) · not on TMDB → queued', 'today 04:00'], ['ok', 'no new episodes', 'yesterday 04:00'], ['new', '+1 · S2025E03 (1080p, 1.8 GB)', 'Oct 12 04:00'], ['ok', 'no new episodes', 'Oct 11 04:00']] },
    { id: 'nordvest', name: 'Nordvest', src: 'play.example.dk/serier/nordvest', tmdb: 9000002, pat: 'S{nn}E{nn}', sched: 'Every 6 h', last: { ok: true, text: '+2 episodes', when: '3h ago' }, q: '1080p DASH', on: true, have: 18,
      runs: [['new', '+2 · S02E07, S02E08', '3h ago'], ['ok', 'no new episodes', '9h ago'], ['ok', 'no new episodes', '15h ago']] },
    { id: 'babel', name: 'Babel Fish', src: 'watch.example.tv/babel-fish/episodes', tmdb: 9000003, pat: 'S{nn}E{nn}', sched: 'Weekly · Mon', last: { ok: false, text: 'page changed · 0 videos found', when: 'Mon 04:00' }, q: '720p MP4', on: true, have: 22,
      runs: [['bad', 'page layout changed — manifest URL not found · re-investigate?', 'Mon 04:00'], ['ok', 'no new episodes', 'Oct 6'], ['ok', 'no new episodes', 'Sep 29']] },
  ];
  const script = (f) => [
    '<span class="c"># auto-fetch · ' + f.name + ' · tmdb ' + f.tmdb + ' · written by the model, read-only here</span>',
    '<span class="k">source</span>   https://' + f.src,
    '<span class="k">list</span>     GET /api/v2/series/{slug}/episodes  → title, media_id, published',
    '<span class="k">manifest</span> GET /api/v2/media/{media_id}/manifest → pick max(height)  <span class="c"># ' + f.q + '</span>',
    '<span class="k">pattern</span>  ' + f.pat,
    '<span class="k">diff</span>     remote − library(tmdb ' + f.tmdb + ')  <span class="c"># already-present episodes are skipped, never re-downloaded</span>',
    '<span class="k">fetch</span>    for each missing → download → verify duration → place as S{ss}E{ee}',
    '<span class="k">tmdb</span>     if episode ∉ tmdb(' + f.tmdb + ') → queue "Not on TMDB yet" with name/overview from the page + still from video  <span class="c"># never submits to TMDB, never invents text</span>',
    '<span class="k">done</span>     report: new · skipped · failed  <span class="c"># 0 new is a normal, successful run</span>',
  ].join('\n');
  const rowsEl = $('#af-rows');
  function render() {
    rowsEl.innerHTML = F.map(f =>
      '<tr class="af-row" data-id="' + f.id + '">' +
        '<td class="nm"><b>' + f.name + '</b><div class="src">' + f.src + '</div></td>' +
        '<td class="pat hide-m">' + f.pat + '</td>' +
        '<td class="sched">' + f.sched + '</td>' +
        '<td class="st"><div class="last"><span class="dot ' + (f.last.ok ? 'ok' : 'bad') + '"></span><span>' + f.last.text + '</span></div><div class="last when">' + f.last.when + '</div></td>' +
        '<td class="q hide-m tiny muted">' + f.q + '</td>' +
        '<td class="tg"><span class="toggle ' + (f.on ? 'on' : '') + '" data-tg="' + f.id + '" title="Enabled"></span></td>' +
        '<td class="acts-cell"><div class="acts"><span class="btn sm ghost" data-run="' + f.id + '">▶ Run now</span></div></td>' +
      '</tr>' +
      '<tr class="af-det" data-for="' + f.id + '" style="display:none"><td colspan="7"><div class="af-open"><div class="af-det-in">' +
        '<div><div class="af-h">Script</div><pre class="af-script">' + script(f) + '</pre>' +
          '<div class="af-cfg"><div class="field"><label>Name</label><input class="input" value="' + f.name + '" style="width:180px"></div><div class="field"><label>Check</label><span class="select" style="width:160px"><span>' + f.sched + '</span></span></div><div class="af-cfg-go"><span class="btn sm">Save</span><span class="spacer"></span><span class="btn sm ghost" data-reinv="' + f.id + '">↻ Re-investigate page</span><span class="btn sm ghost" style="color:var(--bad)" data-del="' + f.id + '">Delete</span></div></div></div>' +
        '<div><div class="af-h">Last runs</div><div class="af-runs">' + f.runs.map(r => '<div class="af-run"><span class="dot ' + (r[0] === 'bad' ? 'bad' : r[0] === 'new' ? 'on' : 'ok') + '"></span><span>' + r[1] + '</span><span class="when">' + r[2] + '</span></div>').join('') + '</div>' +
          '<div class="tiny muted" style="margin-top:10px;line-height:1.55">' + f.have + ' episodes in the library from this fetcher · TMDB <a href="series.html">' + f.tmdb + '</a> · full log in <a href="activity.html">Activity</a></div></div>' +
      '</div></div></td></tr>'
    ).join('');
  }
  render();
  rowsEl.addEventListener('click', e => {
    const tg = e.target.closest('[data-tg]'); if (tg) { tg.classList.toggle('on'); toast(tg.classList.contains('on') ? 'Enabled' : 'Paused — the script and its history are kept'); return; }
    const run = e.target.closest('[data-run]'); if (run) { toast('Running ' + run.dataset.run + ' — it will only download what is missing'); return; }
    const del = e.target.closest('[data-del]'); if (del) { toast('Delete removes the script and schedule, never the downloaded files'); return; }
    const ri = e.target.closest('[data-reinv]'); if (ri) { setState('run'); window.scrollTo({ top: 0, behavior: 'smooth' }); return; }
    if (e.target.closest('input,.select,.btn')) return;
    const row = e.target.closest('.af-row'); if (!row) return;
    const det = rowsEl.querySelector('.af-det[data-for="' + row.dataset.id + '"]');
    const open = det.style.display === 'none';
    rowsEl.querySelectorAll('.af-det').forEach(d => d.style.display = 'none');
    rowsEl.querySelectorAll('.af-row').forEach(r => r.classList.remove('open'));
    if (open) { det.style.display = ''; row.classList.add('open'); }
  });
  $('#af-save').addEventListener('click', () => { toast('Saved — first run started'); setState('list'); });
  $('#af-runall').addEventListener('click', () => toast('3 fetchers queued — nothing already in the library is touched'));

  /* ---- not on TMDB yet ---- */
  const TQ = [
    { id: 'royndin-2026e01', series: 'Royndin', se: 'S2026E01', page: 'Royndin 2026 (1:3)', got: 'today 04:00', air: '2026-10-13', run: '44:12',
      title: 'Útfall', overview: 'The first part of the 2026 season: two crews are set down on opposite shores of the same sound and have to be across it before the tide turns. One boat is built to float and one to be carried, and only one of them makes the crossing.',
      titleSrc: 'from the page — the episode\'s own heading', ovSrc: 'from the page — the episode\'s description, verbatim', stills: ['06:12', '18:40', '31:05'], pick: 1 },
    { id: 'nordvest-s02e08', series: 'Nordvest', se: 'S02E08', page: 'Nordvest · Afsnit 8', got: '3h ago', air: '2026-10-14', run: '58:30',
      title: '', overview: '', titleSrc: 'the page gives only “Afsnit 8” — no episode name', ovSrc: 'the page has no description for this episode', stills: ['04:02', '22:18', '49:51'], pick: 0 },
    { id: 'nordvest-s02e07', series: 'Nordvest', se: 'S02E07', page: 'Nordvest · Afsnit 7', got: '3h ago', air: '2026-10-07', run: '57:05',
      title: '', overview: 'Afsnit 7 · Nordvest.', titleSrc: 'the page gives only “Afsnit 7” — no episode name', ovSrc: 'from the page — the episode\'s description, verbatim', stills: ['09:30', '27:44', '40:12'], pick: 2 },
  ];  const tqEl = $('#af-tq');
  const srcField = (label, val, src, missing) => '<div class="tq-field"><div class="lab">' + label + (val ? ' <span class="btn sm ghost" data-copy="' + val.replace(/"/g, '&quot;') + '">Copy</span>' : '') + '</div>' +
    (val ? '<div class="val">' + val + '</div><div class="src">' + src + '</div>' : '<div class="val none">' + missing + '</div><div class="src">' + src + ' — nothing is invented; leave the field empty on TMDB or write it yourself there.</div>') + '</div>';
  function renderTq() {
    tqEl.innerHTML = TQ.map(t =>
      '<div class="tq-row" data-tq="' + t.id + '"><span class="thumb"></span>' +
        '<div class="nm"><b>' + t.series + ' · <span class="mono">' + t.se + '</span></b><div class="sub">on the page as “' + t.page + '” · downloaded ' + t.got + ' · ' + t.run + '</div></div>' +
        '<div class="st">' + (!t.title || !t.overview ? '<span class="badge" title="The source page is missing the episode name and/or overview">source incomplete</span>' : '') + '<span class="badge warn">not on TMDB</span><span class="btn sm ghost">TMDB entry →</span></div></div>' +
      '<div class="tq-det" data-tqd="' + t.id + '" style="display:none"><div class="tq-in">' +
        '<div>' +
          '<div class="tq-field"><div class="lab">Episode <span class="btn sm ghost" data-copy="' + t.se + '">Copy</span></div><div class="val mono">' + t.series + ' · ' + t.se + ' · aired ' + t.air + ' · ' + t.run + '</div><div class="src">Season and episode from the fetcher\'s pattern; air date from the page\'s publish date.</div></div>' +
          srcField('Episode name', t.title, t.titleSrc, 'No episode name on the source page') +
          srcField('Overview', t.overview, t.ovSrc, 'No overview found for this episode on the source page') +
        '</div>' +
        '<div>' +
          '<div class="tq-field"><div class="lab">Episode still <span class="btn sm ghost" data-dl="' + t.id + '">↓ Download 1920×1080</span></div>' +
            '<div class="tq-big">frame at ' + t.stills[t.pick] + ' · 1920×1080 · jpg</div>' +
            '<div class="tq-stills">' + t.stills.map((s, i) => '<div class="tq-still' + (i === t.pick ? ' on' : '') + '" data-pick="' + i + '"><span>' + s + '</span></div>').join('') + '</div>' +
            '<div class="src">Three frames the model picked: faces visible, no title card, no black bars. Click another or <a href="#" data-more="' + t.id + '">pick a different moment</a>.</div></div>' +
        '</div>' +
      '</div>' +
      '<div class="tq-foot"><a class="btn sm" href="https://www.themoviedb.org/tv/9000001/season/2026/episode/1/edit" target="_blank" rel="noopener">Open on TMDB ↗</a><span class="btn sm ghost" data-copyall="' + t.id + '">Copy all as text</span><span class="spacer"></span><span class="tiny muted">Once TMDB has it, the next metadata refresh picks it up and this row clears itself.</span><span class="btn sm primary" data-sub="' + t.id + '">Mark as submitted</span></div>' +
      '</div>'
    ).join('');
    $('#af-tq-count').textContent = TQ.length + ' episode' + (TQ.length === 1 ? '' : 's');
  }
  function openTq(id) {
    tqEl.querySelectorAll('.tq-det').forEach(d => d.style.display = 'none');
    tqEl.querySelectorAll('.tq-row').forEach(r => r.classList.remove('open'));
    const d = tqEl.querySelector('[data-tqd="' + id + '"]'); if (d) { d.style.display = ''; tqEl.querySelector('[data-tq="' + id + '"]').classList.add('open'); }
  }
  renderTq();
  tqEl.addEventListener('click', e => {
    const c = e.target.closest('[data-copy]'); if (c) { toast('Copied'); return; }
    if (e.target.closest('[data-copyall]')) { toast('Copied episode name, overview, air date and still filename'); return; }
    if (e.target.closest('[data-dl]')) { toast('Downloading still (1920×1080 jpg)'); return; }
    const m = e.target.closest('[data-more]'); if (m) { e.preventDefault(); toast('Frame picker (mockup)'); return; }
    const p = e.target.closest('[data-pick]'); if (p) { const det = p.closest('.tq-det'); det.querySelectorAll('.tq-still').forEach(s => s.classList.remove('on')); p.classList.add('on'); det.querySelector('.tq-big').textContent = 'frame at ' + p.textContent + ' · 1920×1080 · jpg'; return; }
    const sub = e.target.closest('[data-sub]'); if (sub) { const i = TQ.findIndex(t => t.id === sub.dataset.sub); TQ.splice(i, 1); renderTq(); toast('Marked as submitted — clears for good once TMDB returns it'); return; }
    if (e.target.closest('.tq-field .val')) return;
    const row = e.target.closest('.tq-row'); if (!row) return;
    const det = tqEl.querySelector('[data-tqd="' + row.dataset.tq + '"]');
    if (det.style.display === 'none') openTq(row.dataset.tq); else { det.style.display = 'none'; row.classList.remove('open'); }
  });

  const u = new URLSearchParams(location.search).get('state'); if (u) setState(u); else setKey(true);
})();
