/* Ravilo TV — R212's stale line and R280's LoadErrorState, drawn in place (audit 2026-09-27 §3, 2026-09-28).
   Preview only: the STATE picker in the chrome sets one. The first action takes focus; ←/→ moves between two;
   OK on it clears the state (Retry / Back / Sign in all end the mockup's error). Home's UNREACHABLE keeps Sign out. */
(function () {
  const pick = document.getElementById('statepick'); if (!pick) return;
  const t = (k) => window.t(k);
  let cur = '', fi = 0;
  const screen = () => document.querySelector('.screen');
  const isHome = () => { const n = document.querySelector('.appbar .navitem.cur'); return !n || n.dataset.nav === 'home'; };
  function clear() { document.querySelectorAll('.st-stale,.st-err').forEach(x => x.remove()); }
  function paint() {
    clear(); const sc = screen(); if (!sc || !cur) return;
    if (cur === 'stale') { const d = document.createElement('div'); d.className = 'st-stale'; d.innerHTML = '<i></i>' + t('stale_line'); sc.appendChild(d); return; }
    const E = { REAUTH: ['err_reauth_t', 'err_reauth_p', 'err_sign_in'], FORBIDDEN: ['err_forbidden_t', 'err_forbidden_p', 'back'], GONE: ['err_gone_t', null, 'back'], UNREACHABLE: ['err_unreach_t', 'err_unreach_p', 'err_retry'] }[cur];
    const acts = [t(E[2])]; if (cur === 'UNREACHABLE' && isHome()) acts.push(t('pm_sign_out') !== 'pm_sign_out' ? t('pm_sign_out') : 'Sign out');
    fi = 0;
    const d = document.createElement('div'); d.className = 'st-err';
    d.innerHTML = '<h3>' + t(E[0]) + '</h3>' + (E[1] ? '<p>' + t(E[1]) + '</p>' : '') + '<div class="acts">' + acts.map((a, i) => '<span class="b' + (i === 0 ? ' f' : '') + '" data-sti="' + i + '">' + a + '</span>').join('') + '</div>';
    d.addEventListener('click', e => { if (e.target.closest('[data-sti]')) set(''); });
    sc.appendChild(d);
  }
  function set(s) { cur = s; pick.querySelectorAll('button').forEach(b => b.classList.toggle('on', b.dataset.st === s)); paint(); }
  pick.addEventListener('click', e => { const b = e.target.closest('button[data-st]'); if (b) set(b.dataset.st); });
  document.addEventListener('keydown', e => {
    const d = document.querySelector('.st-err'); if (!d) return;
    const bs = d.querySelectorAll('.b');
    if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') { fi = Math.max(0, Math.min(bs.length - 1, fi + (e.key === 'ArrowRight' ? 1 : -1))); bs.forEach((b, i) => b.classList.toggle('f', i === fi)); }
    else if (e.key === 'Enter') set('');
    else return;
    e.preventDefault(); e.stopImmediatePropagation();
  }, true);
})();
