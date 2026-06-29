/* ============================================================
   Seeding & cross-seed surface (Phase 97 — FR-XS1)
   window.Seeding.{ renderMovie, renderSeries, pillHTML }
   Read-only view of qBittorrent state, cross-referenced to the
   Radarr/Sonarr grab history. Frontend renders server state only.
   ============================================================ */
(function () {
  'use strict';

  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const STATE_LABEL = { seeding: 'Seeding', paused: 'Paused', errored: 'Errored' };
  const SCOPE_LABEL = { complete: 'Complete series', season: 'Season pack', episode: 'Single episode', movie: 'Movie' };
  const GROUP_LABEL = { complete: 'Complete-series packs', season: 'Season packs', episode: 'Single episodes', movie: 'Torrents' };

  /* ---------- snapshot cache (Phase 99) ----------
     The seeding picture comes from one shared, short-lived snapshot of the whole qBittorrent
     torrent list (resolved against the tracker registry once), NOT a per-title live query.
     Every detail page + the library "seeded on" filter read the same cached snapshot, so opening
     a title or flipping the filter is instant and never fans out N calls to qBittorrent. */
  const TTL = 600;                // seconds the snapshot is considered fresh (default 10 min; set on Settings ▸ Download tools)
  let snapshotAt = Date.now();    // when the current snapshot was taken
  let lastRender = null;          // re-run the active render after a manual refresh
  function ageSecs() { return Math.max(0, Math.round((Date.now() - snapshotAt) / 1000)); }
  function ageStr() {
    const s = ageSecs();
    if (s < 1) return 'just now';
    if (s < 60) return s + 's ago';
    const m = Math.floor(s / 60), r = s % 60;
    return r ? `${m}m ${r}s ago` : `${m}m ago`;
  }
  function ttlStr() { return TTL % 60 === 0 ? (TTL / 60) + 'm' : TTL + 's'; }
  function freshnessHTML() {
    const stale = ageSecs() > TTL;
    return `<div class="sd-fresh ${stale ? 'stale' : ''}">
      <span class="sd-fresh-dot"></span>
      <span>qBittorrent snapshot · updated <b class="sd-fresh-age">${ageStr()}</b> <span class="muted">· auto-refreshes every ${ttlStr()}</span></span>
      <span class="spacer"></span>
      <span class="btn sm ghost" data-act="refresh">↻ Refresh now</span>
    </div>`;
  }
  // one ticker keeps every visible age label live and flips the bar to "stale" past the TTL
  setInterval(() => {
    document.querySelectorAll('.sd-fresh-age').forEach(el => { el.textContent = ageStr(); });
    document.querySelectorAll('.sd-fresh').forEach(el => el.classList.toggle('stale', ageSecs() > TTL));
  }, 1000);

  /* ---------- tracker table (defined on Metadata ▸ Trackers, Phase 98) ----------
     A torrent announces to ONE tracker, but the tracker may expose several mirror
     hosts in any order. We resolve a torrent's announce URLs to one named tracker
     by host; the passkey in the path is per-user and ignored. ------------------- */
  const TRACKERS = [
    { name: 'NordicHD', priv: true, hosts: ['t.nordicswarm.org', 't.polarswarm.org', 't.nordicbytes.org'] },
    { name: 'FilmBytes', priv: true, hosts: ['announce.filmbytes.org'] },
    { name: 'OpenTrackers', priv: false, hosts: ['open.tracker.net', 'tracker.opentrackr.org'] },
  ];
  function hostOf(url) { try { return new URL(url).hostname; } catch (e) { return (url.split('/')[2] || url).split(':')[0]; } }
  function resolveTracker(announce) {
    const hosts = (announce || []).map(hostOf);
    for (const t of TRACKERS) {
      const matched = hosts.find(h => t.hosts.includes(h));
      if (matched) return { name: t.name, priv: t.priv, matchedHost: matched, mirrorCount: hosts.length, unmapped: false };
    }
    return { name: hosts[0] || 'unknown', priv: false, matchedHost: hosts[0], mirrorCount: hosts.length, unmapped: true };
  }

  /* ---------- demo data ---------- */
  // A movie file can sit in several torrents (cross-seed); each torrent is on one tracker.
  const MOVIE = {
    file: 'Sintel (2010).mkv',
    torrents: [
      { hash: 'b1f4c0a9e2', name: 'Sintel.2010.1080p.BluRay.x264-AMIANA', tracker: 'NordicHD', priv: true, scope: 'movie',
        announce: ['https://t.polarswarm.org/announce/0021b118ead08ed4', 'https://t.nordicswarm.org/announce/0021b118ead08ed4', 'https://t.nordicbytes.org/announce/0021b118ead08ed4'],
        state: 'seeding', ratio: 5.42, seeders: 11, leechers: 0, uploaded: '58.1 GB', added: '2024-11-02', seedTime: '7mo',
        arr: { app: 'radarr', indexer: 'NordicHD (Prowlarr)' }, xseed: 'g1' },
      { hash: '7c2d8b3f10', name: 'Sintel.2010.1080p.BluRay.x264-AMIANA', tracker: 'FilmBytes', priv: true, scope: 'movie',
        announce: ['https://announce.filmbytes.org/announce/9f3c2a71'],
        state: 'seeding', ratio: 2.10, seeders: 4, leechers: 1, uploaded: '8.4 GB', added: '2024-11-02', seedTime: '7mo',
        arr: null, xseed: 'g1', xseedNote: 'cross-seeded from NordicHD grab' },
    ],
  };

  // Nordvest — 2 seasons. Episode files belong to single-ep + season + complete torrents at once.
  const SERIES = {
    seasons: [{ n: 1, episodes: 8 }, { n: 2, episodes: 6 }],
    torrents: [
      { hash: 'aa11bb22cc', name: 'Nordvest.S01-S02.COMPLETE.1080p.WEB-DL.x264-NORD', tracker: 'NordicHD', priv: true,
        announce: ['https://t.nordicbytes.org/announce/0021b118ead08ed4', 'https://t.nordicswarm.org/announce/0021b118ead08ed4', 'https://t.polarswarm.org/announce/0021b118ead08ed4'],
        scope: 'complete', covers: 'all', state: 'seeding', ratio: 4.18, seeders: 6, leechers: 0, uploaded: '142 GB',
        added: '2024-12-18', seedTime: '6mo', arr: { app: 'sonarr', indexer: 'NordicHD (Prowlarr)' }, xseed: null },
      { hash: 'dd33ee44ff', name: 'Nordvest.S01.1080p.WEB-DL.x264-NORD', tracker: 'NordicHD', priv: true,
        announce: ['https://t.polarswarm.org/announce/0021b118ead08ed4', 'https://t.nordicbytes.org/announce/0021b118ead08ed4', 'https://t.nordicswarm.org/announce/0021b118ead08ed4'],
        scope: 'season', covers: { s: 1 }, state: 'seeding', ratio: 6.91, seeders: 9, leechers: 0, uploaded: '88 GB',
        added: '2024-03-04', seedTime: '15mo', arr: { app: 'sonarr', indexer: 'NordicHD (Prowlarr)' }, xseed: 'gs1' },
      { hash: '5566778899', name: 'Nordvest.S01.1080p.WEB-DL.x264-NORD', tracker: 'FilmBytes', priv: true,
        announce: ['https://announce.filmbytes.org/announce/9f3c2a71'],
        scope: 'season', covers: { s: 1 }, state: 'seeding', ratio: 1.07, seeders: 3, leechers: 0, uploaded: '12 GB',
        added: '2024-03-05', seedTime: '15mo', arr: null, xseed: 'gs1', xseedNote: 'cross-seeded from NordicHD S01 pack' },
      { hash: 'ab12cd34ef', name: 'Nordvest.S02.1080p.WEB-DL.x264-NORD', tracker: 'NordicHD', priv: true,
        announce: ['https://t.nordicswarm.org/announce/0021b118ead08ed4', 'https://t.polarswarm.org/announce/0021b118ead08ed4'],
        scope: 'season', covers: { s: 2 }, state: 'paused', ratio: 0.42, seeders: 2, leechers: 0, uploaded: '3.1 GB',
        added: '2024-12-12', seedTime: '6mo', arr: { app: 'sonarr', indexer: 'NordicHD (Prowlarr)' }, xseed: null },
      { hash: '99fe88dc77', name: 'Nordvest.S01E03.Hvalvik.1080p.WEB.x264-PUBLIC', tracker: 'OpenTrackers', priv: false,
        announce: ['http://open.tracker.net:1337/announce', 'udp://tracker.opentrackr.org:1337/announce'],
        scope: 'episode', covers: { s: 1, e: 3 }, state: 'seeding', ratio: 0.88, seeders: 1, leechers: 4, uploaded: '0.9 GB',
        added: '2024-03-01', seedTime: '15mo', arr: null, xseed: null },
      { hash: '11aa22bb33', name: 'Nordvest.S02E01.1080p.WEB.x264-EARLY', tracker: 't.newswarm.io', priv: false,
        announce: ['https://t.newswarm.io/announce/77ab21'],
        scope: 'episode', covers: { s: 2, e: 1 }, state: 'errored', ratio: 0.00, seeders: 0, leechers: 0, uploaded: '0 B',
        added: '2024-12-09', seedTime: '—', arr: null, xseed: null, error: 'tracker down · unregistered torrent' },
    ],
  };

  /* ---------- helpers ---------- */
  function flatEps(seasons) {
    const out = [];
    seasons.forEach(se => { for (let e = 1; e <= se.episodes; e++) out.push({ s: se.n, e, key: `S${pad(se.n)}E${pad(e)}` }); });
    return out;
  }
  const pad = n => String(n).padStart(2, '0');
  function torrentCovers(t, ep) {
    if (t.covers === 'all') return true;
    if (t.scope === 'season') return t.covers.s === ep.s;
    if (t.scope === 'episode') return t.covers.s === ep.s && t.covers.e === ep.e;
    return false;
  }
  function coveredEps(t, eps) { return eps.filter(ep => torrentCovers(t, ep)); }
  const isActive = t => t.state === 'seeding';
  const isRisk = t => t.state === 'errored' || t.seeders === 0 || (t.priv && t.ratio < 1 && t.state !== 'paused');

  function trackerSet(torrents) { return [...new Set(torrents.map(t => resolveTracker(t.announce).name))]; }

  /* ---------- pagebar pill ---------- */
  function pillHTML(torrents) {
    const active = torrents.filter(isActive);
    if (!torrents.length) return `<span class="sd-pill clear" title="No torrents reference this file — edits are safe."><span class="sd-dot"></span>Not seeded<span class="sd-sub">· safe to edit</span></span>`;
    const trk = trackerSet(torrents).length;
    const lead = active.length ? `Seeded ×${active.length}` : `${torrents.length} torrent${torrents.length > 1 ? 's' : ''} · paused`;
    return `<span class="sd-pill seeded" title="Cross-seed: this file is in ${torrents.length} torrent(s) across ${trk} tracker(s). Active seeding blocks track edits."><span class="sd-seed">🌱</span>${lead}<span class="sd-sub">· ${trk} tracker${trk > 1 ? 's' : ''}</span></span>`;
  }

  /* ---------- summary + guard ---------- */
  function summaryHTML(torrents, eps) {
    const active = torrents.filter(isActive).length;
    const trk = trackerSet(torrents).length;
    const risks = torrents.filter(isRisk).length;
    return `
      <div class="sd-summary">
        <div class="sd-stat"><span class="v">${torrents.length}</span><span class="k">torrents</span></div>
        <div class="sd-stat-sep"></div>
        <div class="sd-stat"><span class="v ${active ? 'warn' : ''}">${active}</span><span class="k">actively seeding</span></div>
        <div class="sd-stat-sep"></div>
        <div class="sd-stat"><span class="v">${trk}</span><span class="k">tracker${trk !== 1 ? 's' : ''}</span></div>
        <div class="sd-stat-sep"></div>
        <div class="sd-stat"><span class="v ${risks ? 'bad' : 'ok'}">${risks || '✓'}</span><span class="k">${risks ? 'need attention' : 'all healthy'}</span></div>
      </div>`;
  }
  function guardHTML(torrents, scopeWord) {
    const active = torrents.filter(isActive);
    if (!torrents.length) return `<div class="sd-guard ok"><span class="gi">✓</span><div class="gt"><b>Not seeded — safe to edit.</b><div class="sub">No qBittorrent torrent references ${scopeWord}. Track re-orders and tag edits run without restriction.</div></div></div>`;
    if (!active.length) return `<div class="sd-guard warn"><span class="gi">⛨</span><div class="gt"><b>Registered but paused.</b><div class="sub">${torrents.length} torrent(s) reference ${scopeWord} but none are actively seeding. Editing may break a hash if you later resume — proceed with care.</div></div></div>`;
    return `<div class="sd-guard block"><span class="gi">⛨</span><div class="gt"><b>Edit guard active — ${active.length} torrent(s) seeding.</b><div class="sub">Track-order &amp; tag edits to seeded files are blocked so info-hashes don't break. Cross-seeded copies share the same files, so one edit would desync every linked tracker. Pause the relevant torrent(s) to edit.</div></div></div>`;
  }

  /* ---------- coverage chart (series) ---------- */
  function coverageChart(torrents, eps) {
    const nCols = eps.length;
    // group torrents by scope for row ordering
    const order = ['complete', 'season', 'episode'];
    const groups = order.map(sc => ({ sc, items: torrents.filter(t => t.scope === sc) })).filter(g => g.items.length);
    const colTmpl = `grid-template-columns: 200px repeat(${nCols}, minmax(30px, 1fr));`;
    const seasonStartIdx = {}; let acc = 0;
    SERIES.seasons.forEach(se => { seasonStartIdx[acc] = se.n; acc += se.episodes; });
    const isSeasonStart = i => seasonStartIdx.hasOwnProperty(i);

    // season header spans
    let seasonsRow = `<div class="gcell sd-rowlabel" style="grid-row:1;">Season</div>`;
    let c = 2;
    SERIES.seasons.forEach(se => { seasonsRow += `<div class="scn" style="grid-column:${c}/${c + se.episodes};">Season ${se.n}</div>`; c += se.episodes; });

    let epsRow = `<div class="gcell sd-rowlabel">Episode</div>`;
    eps.forEach((ep, i) => { epsRow += `<div class="epn ${isSeasonStart(i) ? 'seasonstart' : ''}">${pad(ep.e)}</div>`; });

    // depth strip
    const depth = eps.map(ep => {
      const covering = torrents.filter(t => torrentCovers(t, ep));
      const locked = covering.some(isActive);
      return { n: covering.length, locked };
    });
    const maxDepth = Math.max(1, ...depth.map(d => d.n));
    let depthRow = `<div class="gcell sd-rowlabel">Seeded ×<span class="tiny muted" style="margin-left:5px;">depth</span></div>`;
    depth.forEach((d, i) => {
      const bars = Array.from({ length: d.n }, () => `<i style="height:${Math.round(6 + (10 * 1))}px"></i>`).join('');
      depthRow += `<div class="dcell ${isSeasonStart(i) ? 'seasonstart' : ''}" title="${eps[i].key}: in ${d.n} torrent(s)${d.locked ? ' · edit-locked' : ''}">
        <span class="dbars">${bars}</span><span class="dn ${d.locked ? 'locked' : (d.n ? '' : 'zero')}">${d.locked ? '🔒' : ''}${d.n}</span></div>`;
    });

    // torrent bar rows
    let rows = '';
    groups.forEach(g => {
      rows += `<div class="sd-grouplbl" style="grid-column:1/${nCols + 2};">${GROUP_LABEL[g.sc]}</div>`;
      g.items.forEach(t => {
        const cov = coveredEps(t, eps);
        if (!cov.length) return;
        const startGlobal = eps.findIndex(e => e.key === cov[0].key);
        const span = cov.length;
        const gcStart = startGlobal + 2, gcEnd = gcStart + span;
        let track = `<div class="gcell sd-rowlabel"><span class="tk">${esc(resolveTracker(t.announce).name)}</span><span class="badge ${badgeForState(t.state)} scope">${STATE_LABEL[t.state]}</span></div>`;
        // background track cells (for season separators)
        for (let i = 0; i < nCols; i++) track += `<div class="sd-track ${isSeasonStart(i) ? 'seasonstart' : ''}" style="grid-column:${i + 2};"></div>`;
        const lock = isActive(t) ? '<span class="lk">🔒</span>' : '';
        let bar;
        if (t.scope === 'episode') {
          bar = `<div class="sd-bar ep ${t.state}" data-hash="${t.hash}" style="grid-column:${gcStart}/${gcEnd};" title="${esc(t.name)} · ${cov[0].key}">${lock || (t.state === 'errored' ? '!' : '●')}</div>`;
        } else {
          const label = `<span class="bt">${esc(shortName(t.name))}</span>`;
          bar = `<div class="sd-bar ${t.state}" data-hash="${t.hash}" style="grid-column:${gcStart}/${gcEnd};" title="${esc(t.name)}">${lock}<span>${SCOPE_LABEL[t.scope]}</span>${label}</div>`;
        }
        track += bar;
        rows += `<div class="sd-trow" style="${colTmpl}">${track}</div>`;
      });
    });

    return `
      <div class="sd-cov">
        <div class="sd-cov-scroll">
          <div class="sd-grid sd-axis-seasons" style="${colTmpl}">${seasonsRow}</div>
          <div class="sd-grid sd-axis-eps" style="${colTmpl}">${epsRow}</div>
          <div class="sd-grid sd-depth" style="${colTmpl}">${depthRow}</div>
          ${rows}
        </div>
        <div class="sd-legend">
          <span class="lg"><span class="sw seeding"></span> seeding (edit-locked 🔒)</span>
          <span class="lg"><span class="sw paused"></span> paused</span>
          <span class="lg"><span class="sw errored"></span> errored</span>
          <span class="lg" style="margin-left:auto;color:var(--ink-dim);">Bars span the episodes each torrent contains · overlapping rows = cross-seed depth</span>
        </div>
      </div>`;
  }
  const badgeForState = s => s === 'seeding' ? 'ok' : (s === 'errored' ? 'bad' : '');
  function shortName(n) { return n.replace(/^Nordvest\.|^Sintel\./, '').replace(/\.(1080p|WEB-DL|WEB|BluRay).*$/, ''); }

  /* ---------- torrent detail list ---------- */
  function torrentCard(t, eps) {
    const cov = eps ? coveredEps(t, eps) : [];
    const risk = isRisk(t);
    const tr = resolveTracker(t.announce);
    const ratioCls = (tr.priv && t.ratio < 1) ? 'lowratio' : '';
    const seedCls = t.seeders === 0 ? 'noseed' : '';
    const xseed = t.xseed ? `<span class="sd-xseed" title="${esc(t.xseedNote || 'Part of a cross-seed group — same files, multiple trackers')}">⇄ cross-seed</span>` : '';
    const arr = t.arr ? `<span class="sd-arr"><span class="ab ${t.arr.app}">${t.arr.app}</span> grabbed via ${esc(t.arr.indexer)}</span>` : `<span class="sd-arr"><span class="muted">manual / not from *arr</span></span>`;
    const coverPills = eps ? `<dt>Covers</dt><dd><div class="sd-coverpills">${cov.length === eps.length ? '<span class="ep">all ' + eps.length + ' episodes</span>' : cov.map(e => `<span class="ep">${e.key}</span>`).join('')}</div></dd>` : '';
    const mirrorNote = tr.mirrorCount > 1 ? ` <span class="muted">(+${tr.mirrorCount - 1} mirror${tr.mirrorCount > 2 ? 's' : ''}, same tracker)</span>` : '';
    const announceRow = tr.unmapped
      ? `<dt>Announce</dt><dd><span class="sd-unmapped">⚠ unmapped host <code>${esc(tr.matchedHost)}</code></span> — <a href="metadata.html#tab=trackers">name this tracker →</a></dd>`
      : `<dt>Announce</dt><dd>via <code>${esc(tr.matchedHost)}</code>${mirrorNote}</dd>`;
    const trackerCell = tr.unmapped
      ? `<span class="sd-tracker"><span class="pvt unmapped" title="No tracker defined for this announce host">unmapped</span>${esc(tr.name)}</span>`
      : `<span class="sd-tracker"><span class="pvt ${tr.priv ? 'private' : 'public'}">${tr.priv ? 'private' : 'public'}</span>${esc(tr.name)}</span>`;
    return `
      <div class="sd-card ${t.state} ${risk ? '' : ''}" data-hash="${t.hash}">
        <div class="sd-crow">
          <span class="sd-chev">›</span>
          <span class="sd-state ${t.state}"><span class="sdot"></span>${STATE_LABEL[t.state]}</span>
          <span class="sd-tname" title="${esc(t.name)}">${esc(t.name)}</span>
          ${trackerCell}
          ${xseed}
          <span class="sd-metrics">
            <span class="m ${ratioCls}" title="share ratio">ratio <b>${t.ratio.toFixed(2)}</b></span>
            <span class="m ${seedCls}" title="seeders / leechers in swarm"><b>${t.seeders}</b>S <b>${t.leechers}</b>L</span>
            <span class="m">up <b>${t.uploaded}</b></span>
          </span>
        </div>
        <div class="sd-cbody">
          ${t.error ? `<div class="sd-guard block" style="margin-bottom:10px;"><span class="gi">✕</span><div class="gt"><b>Errored.</b><div class="sub">${esc(t.error)}</div></div></div>` : ''}
          <dl class="sd-kv">
            <dt>Scope</dt><dd>${SCOPE_LABEL[t.scope]}</dd>
            ${coverPills}
            ${announceRow}
            <dt>Provenance</dt><dd>${arr}</dd>
            <dt>Seeding for</dt><dd>${t.seedTime} · added ${t.added}</dd>
            <dt>Info-hash</dt><dd><code>${t.hash}…</code></dd>
            ${t.xseed ? `<dt>Cross-seed</dt><dd>${esc(t.xseedNote || 'linked to other tracker(s) — identical files')}</dd>` : ''}
          </dl>
          <div class="sd-cact">
            <span class="btn sm ghost" data-act="open">↗ Open in qBittorrent</span>
            <span class="btn sm ghost" data-act="hash">⧉ Copy info-hash</span>
            ${t.scope !== 'episode' && t.scope !== 'movie' ? '<span class="btn sm ghost" data-act="reveal">⊞ Reveal covered episodes</span>' : ''}
          </div>
        </div>
      </div>`;
  }

  function listHTML(torrents, eps) {
    if (!torrents.length) return `<div class="sd-empty">No torrents reference this file.</div>`;
    if (!eps) return `<div class="sd-list">${torrents.map(t => torrentCard(t, null)).join('')}</div>`;
    const order = ['complete', 'season', 'episode'];
    let html = '';
    order.forEach(sc => {
      const items = torrents.filter(t => t.scope === sc);
      if (!items.length) return;
      html += `<div class="sd-grp-h">${GROUP_LABEL[sc]} <span class="chip">${items.length}</span></div>`;
      html += `<div class="sd-list">${items.map(t => torrentCard(t, eps)).join('')}</div>`;
    });
    return html;
  }

  /* ---------- interaction wiring ---------- */
  function wire(root, eps) {
    // expand cards
    root.querySelectorAll('.sd-card .sd-crow').forEach(row => row.addEventListener('click', e => {
      if (e.target.closest('[data-act]')) return;
      row.parentElement.classList.toggle('open');
    }));
    // actions
    root.querySelectorAll('[data-act]').forEach(b => b.addEventListener('click', e => {
      e.stopPropagation();
      const act = b.dataset.act;
      const card = b.closest('.sd-card');
      const hash = card && card.dataset.hash;
      if (act === 'refresh') { snapshotAt = Date.now(); if (lastRender) lastRender(); toast(root, 'Re-queried qBittorrent — snapshot refreshed'); }
      else if (act === 'hash') { toast(root, 'Info-hash copied to clipboard'); }
      else if (act === 'open') { toast(root, 'Opening torrent in qBittorrent web UI…'); }
      else if (act === 'reveal') { highlightBars(root, hash); toast(root, 'Highlighted covered episodes in the chart above'); }
    }));
    // chart bar ↔ card sync
    root.querySelectorAll('.sd-bar').forEach(bar => bar.addEventListener('click', () => {
      const hash = bar.dataset.hash;
      root.querySelectorAll('.sd-bar').forEach(b => b.classList.toggle('sel', b === bar));
      const card = root.querySelector(`.sd-card[data-hash="${hash}"]`);
      if (card) {
        root.querySelectorAll('.sd-card').forEach(c => c.classList.toggle('sel', c === card));
        card.classList.add('open');
      }
    }));
  }
  function highlightBars(root, hash) {
    root.querySelectorAll('.sd-bar').forEach(b => b.classList.toggle('sel', b.dataset.hash === hash));
  }
  let toastEl = null, toastTimer = null;
  function toast(root, msg) {
    if (!toastEl) { toastEl = document.createElement('div'); toastEl.className = 'toast sd-toast'; document.body.appendChild(toastEl); }
    toastEl.textContent = msg;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { if (toastEl) { toastEl.remove(); toastEl = null; } }, 1900);
  }

  /* ---------- public API ---------- */
  function renderMovie(el, data) {
    const d = data || MOVIE;
    lastRender = () => renderMovie(el, data);
    el.innerHTML = freshnessHTML() + summaryHTML(d.torrents) + guardHTML(d.torrents, 'this file') + listHTML(d.torrents, null);
    wire(el, null);
    return d.torrents;
  }
  function renderSeries(el, data) {
    const d = data || SERIES;
    const eps = flatEps(d.seasons);
    lastRender = () => renderSeries(el, data);
    el.innerHTML = freshnessHTML() + summaryHTML(d.torrents, eps) + guardHTML(d.torrents, 'episodes of this series')
      + coverageChart(d.torrents, eps) + listHTML(d.torrents, eps);
    wire(el, eps);
    return d.torrents;
  }

  window.Seeding = { renderMovie, renderSeries, pillHTML, MOVIE, SERIES };
})();
