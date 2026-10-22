/* Container conversion — direction C "Standing container action" (avi-mkv-migration round).
   Demo switches (query string):
     ?container=avi            show the AVI stand-in (Sample Film) and the standing card
     &edit=failed              a language edit just failed: card lights up, row says "Not kept"
     &convert=ok|tool|unverified|seeding|unknown|path|noest|offline   force an outcome / state
   Nothing converts as a side effect: the only start control is "Start conversion" in the dialog. */
(function () {
  const q = new URLSearchParams(location.search);
  if (q.get('container') !== 'avi') return;
  const mount = document.getElementById('cv-mount');
  if (!mount) return;
  const OUT = q.get('convert') || 'ok';
  const SRC = '/media/movies/Sample Film (2001)/Sample Film.avi';
  const DIR = '/media/movies/Sample Film (2001)/';
  const LANG = 'Faroese (fao)';
  let S = q.get('edit') === 'failed' ? 'hot' : 'std', base = 'Sample Film.mkv', step = 1, back = null;
  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const scope = f => `<span class="cv-scope">File · ${esc(f || 'Sample Film.avi')}</span>`;
  const why = `AVI has no place to store a language for Audio 1, so ${LANG} can’t be saved in this file. Converting the file to MKV can keep it.`;
  const copy = 'Video and audio are copied without re-encoding';
  const steps = (cur, marks) => `<ol class="cv-steps">${['Copy streams into MKV', 'Re-check the language tag', 'Put the new file in place'].map((t, i) => {
    const m = marks ? marks[i] : (i + 1 < cur ? 'dn' : i + 1 === cur ? 'on' : '');
    const note = marks ? [' <em>· finished</em>', ' <em>· no language tag on Audio 1</em>', ' <em>· not done</em>'][i] : '';
    return `<li class="${m}"><i>${m === 'x' ? '✕' : i + 1}</i>${t}${note}</li>`; }).join('')}</ol>`;

  const V = {
    std: () => `<section class="cv-card${S === 'hot' ? ' hot' : ''}" aria-labelledby="cv-h"><div class="cv-std"><span class="cv-lb">Container</span><span class="chip">AVI</span><span class="cv-txt" id="cv-h">AVI can’t store track languages, so some edits won’t stick.</span><span class="btn sm${S === 'hot' ? ' primary' : ''}" role="button" tabindex="0" data-a="open">Convert to MKV…</span></div></section>`,
    preview: () => `<section class="cv-card lim"><div class="cv-h"><span class="cv-ico info">i</span><span class="badge info">AVI limitation</span>${scope()}</div><h3 tabindex="-1">Convert to MKV — what would change</h3><p>${why}</p><dl class="cv-pvt">
      <dt>Container</dt><dd><span class="chip">AVI</span> → <span class="chip">MKV</span></dd>
      <dt>Extension</dt><dd><span class="mono">.avi</span> → <span class="mono">.mkv</span></dd>
      <dt>Streams</dt><dd>Video 1 · copied untouched<br>Audio 1 · MP3 · copied untouched<div class="cv-m">${copy}</div></dd>
      <dt>Language requested</dt><dd>Audio 1: Unknown → <b>${LANG}</b></dd>
      <dt>Estimate</dt><dd>${OUT === 'noest' ? '<span class="cv-unk">Estimate unavailable</span><div class="cv-m">Time and temporary space can’t be worked out for this file.</div>' : 'about 2 minutes · 1.4 GB of temporary space'}<div class="cv-m">Temporary files go in a hidden folder beside the source, on the same disk.</div></dd>
      <dt>Before it starts</dt><dd>Torrent safety is checked again when you press Start conversion.</dd>
      <dt>Old file</dt><dd><span class="badge cv-tent">Tentative</span> Proposed: Sample Film.avi stays beside the new file. Removing it would be a separate step, later.</dd></dl>
      <div class="cv-acts"><span class="btn primary" role="button" tabindex="0" data-a="confirm">Continue to confirmation</span><span class="btn ghost" role="button" tabindex="0" data-a="close">Cancel</span></div></section>`,
    seeding: () => `<section class="cv-card blk"><div class="cv-h"><span class="cv-ico warn">⛨</span><span class="badge warn">Blocked</span>${scope()}</div><h3 tabindex="-1">This file can’t be converted right now</h3><p><b>This file is linked to an active seeding torrent.</b> This file is shared with a torrent that is still seeding (hard link), so it can't be changed in place.</p><div class="cv-acts"><span class="btn" role="button" tabindex="0" data-a="open">Check again</span><span class="btn ghost" role="button" tabindex="0" data-a="seedtab">Open the Seeding tab</span><span class="btn ghost" role="button" tabindex="0" data-a="close">Close</span></div></section>`,
    unknown: () => `<section class="cv-card blk"><div class="cv-h"><span class="cv-ico warn">?</span><span class="badge warn">Blocked</span>${scope()}</div><h3 tabindex="-1">This file can’t be converted right now</h3><p><b>The torrent safety check couldn’t be reached.</b> So this file is treated as shared. Conversion stays unavailable until the check answers.</p><div class="cv-acts"><span class="btn" role="button" tabindex="0" data-a="open">Check again</span><span class="btn ghost" role="button" tabindex="0" data-a="close">Close</span></div></section>`,
    path: () => `<section class="cv-card pth"><div class="cv-h"><span class="cv-ico pth">≠</span><span class="badge bad">Path mismatch</span>${scope()}</div><h3 tabindex="-1">Conversion unavailable</h3><p>The recorded path does not match the source file; migration is unavailable until the path is resolved.</p><div class="cv-acts"><span class="btn ghost" role="button" tabindex="0" data-a="close">Close</span></div></section>`,
    running: () => `<section class="cv-card job${OUT === 'offline' && step > 1 ? ' off' : ''}" aria-live="polite"><div class="cv-h"><span class="cv-ico hi">↻</span><span class="badge info">Running</span>${scope()}</div><h3 tabindex="-1">Converting to MKV</h3>${OUT === 'offline' && step > 1 ? '<div class="cv-offb"><b>Connection to the server lost</b><span>Reconnecting…</span></div><span class="cv-stp">Last known: Re-check the language tag</span>' : `<span class="cv-stp">Step ${step} of 3</span>`}${steps(step)}<p>The original stays untouched until the re-check passes.</p><p class="cv-m">${OUT === 'offline' && step > 1 ? 'The conversion runs on the server and isn’t affected. Status updates when the connection returns.' : 'You can leave this page. The job keeps running on the server — follow it in <a href="activity.html">Activity ▸ Jobs</a>.'}</p></section>`,
    ok: () => `<section class="cv-card okk"><div class="cv-h"><span class="cv-ico ok">✓</span><span class="badge ok">Verified</span>${scope(base)}</div><h3 tabindex="-1">Converted to MKV — language confirmed</h3><p>The re-check found ${LANG} on Audio 1 in ${esc(base)}.</p><div class="cv-files"><span class="cv-lb">Now on disk</span><div class="f"><span class="mono">${esc(DIR + base)}</span><span class="badge ok">new · in place</span></div><div class="f"><span class="mono">${SRC}</span><span class="badge">original · kept beside it</span></div></div><div class="cv-next"><span class="cv-lb">Before other devices see it</span><span>Other devices still see the old file until Jellyfin has indexed the new one.</span><span><span class="badge">Waiting for Jellyfin to index</span></span></div><p class="cv-m"><span class="badge cv-tent">Tentative</span> Removing the original would be its own, later step.</p></section>`,
    tool: () => `<section class="cv-card fail"><div class="cv-h"><span class="cv-ico bad">!</span><span class="badge bad">Failed</span>${scope()}</div><h3 tabindex="-1">The conversion to MKV didn’t finish</h3><p>Nothing was put in place. The original Sample Film.avi is untouched and still there.</p><span class="cv-stp">Stopped at: Copy streams into MKV</span><div class="cv-acts"><span class="btn" role="button" tabindex="0" data-a="open">Try again</span><a class="btn ghost" href="activity.html">Show job log</a></div></section>`,
    unverified: () => `<section class="cv-card fail"><div class="cv-h"><span class="cv-ico bad">✕</span><span class="badge bad">Not applied</span>${scope()}</div><h3 tabindex="-1">Conversion failed — no language tag after the re-check</h3><p>The copy step reported success, but re-checking the new file found no language on Audio 1. The new file was not put in place, and the original Sample Film.avi is untouched.</p>${steps(0, ['dn', 'x', ''])}<div class="cv-acts"><span class="btn" role="button" tabindex="0" data-a="open">Try again</span><a class="btn ghost" href="activity.html">Show job log</a></div></section>`
  };
  V.hot = V.std;

  /* AVI stand-in: hide the Sintel/MKV-specific chrome and show Sample Film's one audio track instead */
  function standIn() {
    ['cover-video-alert', 'cascade-alert', 'mkv-banner', 'sidecar-card', 'bazarr-card', 'audio-flags', 'sub-flags', 'track-list'].forEach(id => { const el = document.getElementById(id); if (el) el.style.display = 'none'; });
    const cr = document.querySelector('.backrow .crumb'); if (cr) cr.innerHTML = '<a href="library.html">Movies</a> / Sample Film';
    const h1 = document.querySelector('.pagebar h1'); if (h1) h1.innerHTML = 'Sample Film <span class="muted">(2001)</span>';
    document.title = 'Jellystructure — Sample Film';
    const list = document.getElementById('track-list');
    if (list) list.insertAdjacentHTML('afterend', `<div id="cv-tracks"><div class="trk"><div class="trk-main"><span class="grip">⋮⋮</span><span class="pos">1</span><span class="tiny muted" style="font-weight:700;letter-spacing:.06em;">AUDIO</span><div class="trk-mid"><b>Audio 1</b><span class="chip" id="cv-lang">Unknown</span><span class="chip mono">MP3</span><span class="badge warn" id="cv-tri">No language tag · in Triage</span><span class="mono tiny muted">${MID}</span></div><div class="trk-right"><span style="color:var(--warn)" title="default">★</span></div></div></div></div>`);
  }
  const MID = 'demo-film-001';
  function rowNote() {
    const row = document.querySelector('#cv-tracks .trk');
    if (!row) return;
    const old = row.querySelector('.cv-row'); if (old) old.remove();
    const msg = { hot: `<b>Not kept — this AVI can’t store a language.</b><span>Requested: ${LANG}. Convert to MKV on the file card above ↑</span>`, running: '<b>Locked while the conversion runs</b>', tool: '<b>Still Unknown — the conversion to MKV didn’t finish.</b>', unverified: '<b>Still Unknown — the new file had no language tag.</b>' }[S];
    if (msg) row.insertAdjacentHTML('beforeend', `<div class="trk-sub cv-row">${msg}</div>`);
  }
  function render(focus) {
    mount.innerHTML = V[S]();
    rowNote();
    if (focus) { const h = mount.querySelector('h3'); if (h) h.focus(); }
  }
  function openPreview() {
    S = ['seeding', 'unknown', 'path'].includes(OUT) ? OUT : 'preview';
    render(true);
  }
  function dialog() {
    back = document.createElement('div');
    back.className = 'modal-back cv-back open';
    back.innerHTML = `<div class="modal cv-dlg" role="dialog" aria-modal="true" aria-labelledby="cv-dh"><h3 id="cv-dh" tabindex="-1">Convert Sample Film to MKV?</h3><div class="cv-cmp"><div class="cv-side"><span class="cv-lb">Source</span><div class="cv-path">${SRC}</div><span class="muted tiny">AVI · 1.4 GB</span><span class="cv-keep">Stays in place until the new file is verified</span></div><div class="cv-arr">→</div><div class="cv-side"><span class="cv-lb">Proposed destination</span><div class="cv-path" id="cv-dst">${esc(DIR + base)}</div><div class="cv-fld"><label for="cv-base">File name</label><input id="cv-base" value="${esc(base)}" spellcheck="false"><span class="cv-hint">Shown as proposed. Change it if you need to — nothing else is renamed.</span></div></div></div><div class="cv-sum">Audio 1 → <b>${LANG}</b> · ${copy} · ${OUT === 'noest' ? 'Estimate unavailable' : 'about 2 minutes · 1.4 GB of temporary space'}</div><div class="cv-acts"><span class="btn ghost" role="button" tabindex="0" data-d="cancel">Cancel</span><span class="btn primary" role="button" tabindex="0" data-d="start">Start conversion</span></div></div>`;
    document.body.appendChild(back);
    back.querySelector('h3').focus();
    const inp = back.querySelector('#cv-base');
    inp.addEventListener('input', () => { back.querySelector('#cv-dst').textContent = DIR + inp.value; });
    back.addEventListener('click', e => { const d = e.target.closest('[data-d]'); if (e.target === back) closeDlg(true); else if (d) d.dataset.d === 'start' ? start(inp.value.trim() || base) : closeDlg(true); });
    back.addEventListener('keydown', e => {
      if (e.key === 'Escape') { e.stopPropagation(); closeDlg(true); }
      if (e.key === 'Enter' && e.target.dataset.d) e.target.click();
      if (e.key === 'Tab') { const f = [...back.querySelectorAll('input,[tabindex="0"]')]; const i = f.indexOf(document.activeElement);
        if (e.shiftKey && i <= 0) { e.preventDefault(); f[f.length - 1].focus(); } else if (!e.shiftKey && i === f.length - 1) { e.preventDefault(); f[0].focus(); } }
    });
  }
  function closeDlg(ret) { if (back) back.remove(); back = null; if (ret) { const c = mount.querySelector('[data-a="confirm"]'); if (c) c.focus(); } }
  function start(name) {
    base = name; closeDlg(false); S = 'running'; step = 1; render(true);
    setTimeout(() => { step = 2; render(); }, 2200);
    if (OUT === 'offline') return;
    setTimeout(() => { S = OUT === 'tool' ? 'tool' : OUT === 'unverified' ? 'unverified' : 'ok'; render(true); if (S === 'ok') verified(); }, OUT === 'tool' ? 1600 : 3600);
  }
  function verified() {
    const f = document.getElementById('trk-file'); if (f) f.textContent = base;
    const l = document.getElementById('cv-lang'); if (l) l.textContent = LANG;
    const t = document.getElementById('cv-tri'); if (t) t.remove();
  }
  mount.addEventListener('click', e => {
    const a = e.target.closest('[data-a]'); if (!a) return;
    ({ open: openPreview, confirm: dialog, close: () => { S = q.get('edit') === 'failed' ? 'hot' : 'std'; render(); const o = mount.querySelector('[data-a="open"]'); if (o) o.focus(); }, seedtab: () => { const t = document.querySelector('#tabbar [data-tab="seeding"]'); if (t) t.click(); } })[a.dataset.a]();
  });
  mount.addEventListener('keydown', e => { if ((e.key === 'Enter' || e.key === ' ') && e.target.dataset.a) { e.preventDefault(); e.target.click(); } });
  const f = document.getElementById('trk-file'); if (f) f.textContent = 'Sample Film.avi';
  standIn();
  render();
})();
