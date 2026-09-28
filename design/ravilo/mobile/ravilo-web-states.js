/* Built, never drawn (audit 2026-09-27 §3) — drawn IN PLACE in Ravilo Mobile.html, 2026-09-28. The app already does all of
   this; the mockup now shows it. The PREVIEW buttons under the phone reach each state. Words are the shipped strings.
     R263  the install card (browser tab on a phone only; iPhone taught two steps, Android one Install; Not now = never again
           on this device), the "Ravilo updated · Reload" toast, the iOS-below-18.2 notice in place of the app
     R212  "Showing saved content — trying to reconnect" while Home is painted from the snapshot cache
     R280  one LoadErrorState per page: REAUTH · FORBIDDEN · GONE · UNREACHABLE (Home keeps Sign out as a second action)
     R317/R318  the Sort sheet: Recommended first (no direction), every other field with its direction in words
     R309  the play button names the whole file: "Resume S01E01–E03" */
(function () {
  const H = window.RaviloHost; if (!H) return;
  const esc = H.esc, $ = id => document.getElementById(id);
  const phone = () => document.querySelector('.phone');
  const host = () => $('pmSheet').parentNode;
  const S = { web: false, dismissed: false, stale: false, err: null };
  const ERRS = ['REAUTH', 'FORBIDDEN', 'GONE', 'UNREACHABLE'];
  const SHARE = '<svg viewBox="0 0 24 24"><path d="M12 3v12M8 7l4-4 4 4"/><path d="M6 11H5a1 1 0 0 0-1 1v8a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-8a1 1 0 0 0-1-1h-1"/></svg>';
  const isIOS = () => phone().classList.contains('ios');

  function homeTop() {
    let h = '';
    if (S.stale) h += '<div class="ws-stale"><i></i>Showing saved content — trying to reconnect</div>';
    if (S.web && !S.dismissed) h += '<div class="ws-card"><h4>' + SHARE + 'Add Ravilo to your Home Screen</h4>'
      + (isIOS() ? '<ol><li><b>1</b>Tap Share ' + SHARE + '</li><li><b>2</b>Tap Add to Home Screen</li></ol>' : '')
      + '<div class="ln">You’ll sign in again in the installed app.</div>'
      + '<div class="ac"><button class="ws-btn" data-ws="notnow">Not now</button>' + (isIOS() ? '' : '<button class="ws-btn pr" data-ws="install">Install</button>') + '</div></div>';
    return h;
  }
  function error(tab) {
    if (!S.err) return '';
    const E = {
      REAUTH: ['Sign in again', 'This device’s session has ended. Sign in again to carry on.', ['Sign in', 'reauth']],
      FORBIDDEN: ['Not available on this profile', 'This profile can’t see this.', ['Back', 'back']],
      GONE: ['This isn’t here any more', '', ['Back', 'back']],
      UNREACHABLE: ['Couldn’t reach the server', 'Check the connection and try again.', ['Retry', 'retry']],
    }[S.err];
    const second = S.err === 'UNREACHABLE' && tab === 'home' ? '<button class="ws-btn" data-ws="signout">Sign out</button>' : '';
    return '<div class="ws-err"><h3>' + esc(E[0]) + '</h3>' + (E[1] ? '<p>' + esc(E[1]) + '</p>' : '') + '<div class="ac"><button class="ws-btn pr" data-ws="' + E[2][1] + '">' + esc(E[2][0]) + '</button>' + second + '</div></div>';
  }
  function playLabel(it) {
    if (it.kind !== 'series' || !window.RAVILO.episodesFor) return 'Play';
    const eps = RAVILO.episodesFor(it, 0), e = eps[0];
    if (!e || !e.file) return 'Play';
    const g = eps.filter(x => x.file === e.file), code = (window.__raviloEpCode || ((s, a, b) => 'S0' + s + 'E' + a + '–E' + b))(1, g[0].n, g[g.length - 1].n);
    return 'Resume ' + code;
  }
  function sortSheet(list, cur, rev, pick) {
    closeSort();
    const sc = document.createElement('div'); sc.className = 'ws-scrim'; sc.id = 'wsScrim';
    const sh = document.createElement('div'); sh.className = 'ws-sheet'; sh.id = 'wsSheet';
    sh.innerHTML = '<div class="gr"></div><h4>Sort</h4>' + list.map((o, i) => {
      const sub = o[1] ? o[1][i === cur && rev ? 1 : 0] : o[2];
      return '<button class="ws-sr' + (i === cur ? ' on' : '') + '" data-wsort="' + i + '"><span class="tx"><div class="n">' + esc(o[0]) + '</div><div class="s">' + esc(sub) + '</div></span><span class="ck"></span></button>';
    }).join('');
    host().append(sc, sh);
    sc.onclick = closeSort;
    sh.onclick = e => { const b = e.target.closest('[data-wsort]'); if (!b) return; closeSort(); pick(+b.dataset.wsort); };
  }
  function closeSort() { ['wsScrim', 'wsSheet'].forEach(id => { const x = $(id); if (x) x.remove(); }); }
  function toast() {
    if ($('wsToast')) return;
    const t = document.createElement('div'); t.className = 'ws-toast'; t.id = 'wsToast';
    t.innerHTML = '<span>Ravilo updated</span><button data-ws="reload">Reload</button>';
    host().appendChild(t);
  }
  function oldIOS(on) {
    const x = $('wsOld'); if (x) x.remove(); if (!on) return;
    const d = document.createElement('div'); d.className = 'ws-old'; d.id = 'wsOld';
    d.innerHTML = '<span class="mk"></span><h3>This iPhone needs an update</h3><p>Ravilo needs iOS 18.2 or newer. Update in Settings → General → Software Update, then open this page again.</p>';
    host().appendChild(d);
  }
  document.addEventListener('click', e => {
    const a = e.target.closest('[data-ws]'); if (!a) return;
    const k = a.dataset.ws;
    if (k === 'notnow') { S.dismissed = true; H.render(); }
    else if (k === 'install') { S.dismissed = true; H.render(); H.phToast('Installing Ravilo…'); }
    else if (k === 'reload') { a.closest('.ws-toast').remove(); H.phToast('Up to date'); }
    else if (k === 'signout') H.openSignOut();
    else if (k === 'retry' || k === 'reauth' || k === 'back') { S.err = null; paintPv(); H.render(); }
  });
  function paintPv() {
    const pv = $('prevPick'); if (!pv) return;
    const on = { webtab: S.web, stale: S.stale, err: !!S.err, oldios: !!$('wsOld'), update: !!$('wsToast') };
    Object.keys(on).forEach(k => { const b = pv.querySelector('[data-pv="' + k + '"]'); if (b) b.classList.toggle('on', on[k]); });
    const eb = pv.querySelector('[data-pv="err"]'); if (eb) eb.textContent = S.err ? 'Load error · ' + S.err : 'Load error · R280';
  }
  $('prevPick').addEventListener('click', e => {
    const b = e.target.closest('button[data-pv]'); if (!b) return;
    const k = b.dataset.pv;
    if (k === 'webtab') { S.web = !S.web; S.dismissed = false; if (H.tab !== 'home') H.setTab('home'); H.render(); }
    else if (k === 'update') { const t = $('wsToast'); t ? t.remove() : toast(); }
    else if (k === 'oldios') { const on = !$('wsOld'); if (on && !isIOS()) { const d = document.querySelector('[data-dev="ios"]'); if (d) d.click(); } oldIOS(on); }
    else if (k === 'stale') { S.stale = !S.stale; if (H.tab !== 'home') H.setTab('home'); H.render(); }
    else if (k === 'err') { const i = S.err ? ERRS.indexOf(S.err) + 1 : 0; S.err = ERRS[i] || null; H.render(); }
    else return;
    paintPv();
  });
  window.RaviloWeb = { homeTop, error, playLabel, sortSheet };
  H.render();
})();
