/* Ravilo.html — the shell. One design-only fence (never in the app) over one stage that shows the picked platform.
   Platform macOS · Linux · TV · Mobile, Theme ×5, Mode, then the platform's own controls: the desktop's Window sizes,
   the TV's Focus + State pickers, the phone's Device + Preview pickers. The TV and phone rows are MIRRORED from the
   surface's own pickers (tv-markup.js · ravilo-phone-markup.js), so a button added there shows up here by itself.
   localStorage: ravilo-one {plat, skin, mode, size, dev}. URL: ?platform=mac|linux|tv|mobile &skin= &mode=films|music */
(function () {
  const KEY = 'ravilo-one';
  const U = new URLSearchParams(location.search);
  let S = { plat: 'mac', skin: 'aurora', mode: 'films', size: 'large', dev: 'and', state: null };
  try { Object.assign(S, JSON.parse(localStorage.getItem(KEY) || '{}')); } catch (e) {}
  const up = U.get('platform'); if (up && /^(mac|linux|tv|mobile)$/.test(up)) S.plat = up;
  ['skin', 'mode'].forEach(k => { if (U.get(k)) S[k] = U.get(k); });
  const save = () => { try { localStorage.setItem(KEY, JSON.stringify(S)); } catch (e) {} };

  const PLATS = [['mac', 'macOS'], ['linux', 'Linux'], ['tv', 'TV'], ['mobile', 'Mobile']];
  const SKINS = [['aurora', 'Aurora', '#0a0c13', '#7b6ef0'], ['midnight', 'Midnight', '#04101a', '#19d6c6'], ['noir', 'Noir', '#080807', '#f5b542'], ['graphite', 'Graphite', '#1c1c1f', '#4f8ef0'], ['daylight', 'Daylight', '#fbfbfd', '#5b54e8']];
  const SIZES = [['large', 'Large', '≥ 1200'], ['expanded', 'Expanded', '840–1199'], ['medium', 'Medium', '600–839'], ['compact', 'Compact', '< 600']];
  const TV_SKINS = ['aurora', 'midnight', 'noir'];
  const MIRROR = { tv: ['#focuspick', '#statepick'], mobile: ['#devPick', '#prevPick'] };
  const NOTE = { mac: 'macOS 26 · ⌘1 / ⌘2 switch · Space plays', linux: 'GNOME (libadwaita) · Ctrl+1 / Ctrl+2 switch · Space plays', tv: 'Click the TV, then ↑ ↓ ← → move · ↵ select · esc back', mobile: 'Tap, swipe and scroll as on a phone' };
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const isDesk = () => S.plat === 'mac' || S.plat === 'linux';
  const kind = () => isDesk() ? 'desktop' : S.plat === 'tv' ? 'tv' : 'phone';
  const tvSkin = k => TV_SKINS.indexOf(k) >= 0 ? k : 'aurora';

  function surfaceURL() {
    const q = new URLSearchParams({ surface: kind() });
    if (isDesk()) { q.set('os', S.plat); q.set('size', S.size); q.set('skin', S.skin); q.set('mode', S.mode); }
    else if (S.plat === 'tv') q.set('skin', tvSkin(S.skin));
    /* the phone keeps its own theme setting (Settings ▸ Theme, Match the phone's light/dark, the light ⇄ dark preview):
       no ?skin= here, since that would pin it and switch the follow off */
    else { q.set('mode', S.mode === 'music' ? 'music' : 'video'); if (S.dev === 'ios') q.set('dev', 'ios'); }
    /* every other parameter on the shell's URL goes through to the surface, so the old files' deep links still work:
       TV ?focus= ?dwell= ?static= · phone ?tab= ?sheet= ?appearance= ?emptywalls= · desktop ?mode= … */
    U.forEach((v, k) => { if (k !== 'platform' && !q.has(k)) q.set(k, v); });
    if (S.state) q.set('carry', '1');
    return 'Ravilo.html?' + q.toString();
  }

  document.body.insertAdjacentHTML('afterbegin', '<div class="rv1"><div class="rv1-fence" id="rv1Fence"></div><div class="rv1-stage"><iframe id="rv1Frame" title="Ravilo"></iframe></div></div>');
  const fence = document.getElementById('rv1Fence'), frame = document.getElementById('rv1Frame');
  const W = () => { try { return frame.contentWindow; } catch (e) { return null; } };
  const D = () => { try { return frame.contentDocument; } catch (e) { return null; } };
  let loadedKind = null, miss = [];
  /* where you are + what is playing travel with you: read them off the surface that is leaving, hand them to the next
     (shape: mobile/ravilo-phone-state.js). The TV has films & series only: it never overwrites a music place, and what is
     playing (the TV has no music) is kept from before. */
  const take = st => {
    if (!st) return false;
    if (loadedKind === 'tv' && S.state && S.state.mode === 'music') return false;
    const play = st.play || (S.state && S.state.play);
    const next = Object.assign({}, st, play ? { play } : {});
    const ch = JSON.stringify(next) !== JSON.stringify(S.state); S.state = next; return ch;
  };
  const grab = () => { const w = W(); try { if (w && w.RaviloState) take(w.RaviloState.get()); } catch (e) {} };
  function load() {
    grab(); save();
    if (S.state) { if (S.state.mode) S.state.mode = S.mode; try { localStorage.setItem('ravilo-carry', JSON.stringify(S.state)); } catch (e) {} }
    miss = []; loadedKind = kind(); frame.src = surfaceURL();
  }
  frame.addEventListener('load', () => { const w = W(); miss = (w && w.RaviloState && w.RaviloState.miss) || []; paint(); setTimeout(() => { const w2 = W(); if (w2) w2.focus(); }, 60); });

  /* ---- mirrored groups: read the surface's own pickers ---- */
  function groups() {
    const d = D(), sels = MIRROR[S.plat]; if (!d || !sels) return [];
    return sels.map(sel => d.querySelector(sel)).filter(Boolean).map(el => {
      const b = el.querySelector('b'); const label = b ? b.textContent.trim() : '';
      return { el, label: label.charAt(0) + label.slice(1).toLowerCase(), btns: [...el.querySelectorAll('button')], ins: [...el.querySelectorAll('input')] };
    });
  }
  function mirrorHTML() {
    return groups().map((g, gi) => {
      const chips = g.btns.length > 6;
      const bs = g.btns.map((b, bi) => `<button data-mx="${gi}:${bi}" class="${b.classList.contains('on') ? 'on' : ''}"${b.title ? ` title="${esc(b.title)}"` : ''}>${esc(b.textContent.trim())}</button>`).join('');
      const ins = g.ins.map((i, ii) => `<label class="rv1-num"><input data-mxi="${gi}:${ii}" type="number" value="${esc(i.value)}" min="${esc(i.min)}" step="${esc(i.step)}">ms</label>`).join('');
      return `<div class="grp"><span>${esc(g.label)}</span><div class="${chips ? 'rv1-chips' : 'rv1-seg'}">${bs}</div>${ins}</div>`;
    }).join('');
  }
  const mirrorSig = () => groups().map(g => g.btns.map(b => b.classList.contains('on') ? 1 : 0).join('') + g.ins.map(i => i.value).join(',')).join('|');

  /* ---- the fence ---- */
  function seg(k, list, cur, dis) {
    return `<div class="rv1-seg">${list.map(([v, l, sub]) => `<button data-f="${k}:${v}" class="${cur === v ? 'on' : ''}"${dis ? ` disabled title="${esc(dis)}"` : ''}>${l}${sub ? `<small>${sub}</small>` : ''}</button>`).join('')}</div>`;
  }
  function tvNote() {
    const name = isDesk() ? 'the desktop’s' : S.plat === 'tv' ? 'the TV’s' : 'the phone’s';
    const out = [];
    if (S.plat === 'tv' && S.state && S.state.mode === 'music') out.push('The TV has no music: it opens on Home. Your place in music waits for the next platform');
    if (S.plat === 'tv' && TV_SKINS.indexOf(S.skin) < 0) out.push(S.skin === 'daylight' ? 'The TV is dark-only: it shows the viewer’s dark pick (Aurora here)' : 'Graphite isn’t drawn on the TV yet — Aurora stands in');
    if (miss.length) out.push(`“${esc(miss.join('”, “'))}” isn’t among ${name} stand-in titles — showing the page it was on`);
    return out.map(x => `<span class="rv1-warn">${x}</span>`).join('');
  }
  let lastSig = '';
  function paint() {
    const sw = SKINS.map(([k, l, a, b]) => `<button data-f="skin:${k}" class="${S.skin === k ? 'on' : ''}" title="${l}" style="background:linear-gradient(135deg,${a} 50%,${b} 50%)"></button>`).join('');
    const sub = isDesk()
      ? `<div class="grp"><span>Window</span>${seg('size', SIZES, S.size)}</div>`
      : mirrorHTML();
    fence.innerHTML = `<div class="rv1-row"><span class="tag">Design only · not in the app</span>`
      + `<div class="grp"><span>Platform</span>${seg('plat', PLATS, S.plat)}</div>`
      + `<div class="grp"><span>Theme</span><div class="rv1-sw">${sw}</div></div>`
      + `<div class="grp"><span>Mode</span>${seg('mode', [['films', 'Films &amp; series'], ['music', 'Music']], S.plat === 'tv' ? '' : S.mode, S.plat === 'tv' ? 'The TV has no music mode' : '')}</div>`
      + `<span class="note" title="${esc(NOTE[S.plat])}">${NOTE[S.plat]}</span><a class="solo" href="${esc(surfaceURL())}" target="_blank" title="This platform on its own, with its own pickers">Open alone ↗</a></div>`
      + `<div class="rv1-row sub">${sub}${tvNote()}</div>`;
    lastSig = mirrorSig();
  }

  /* ---- apply to the live surface (no reload where the surface has a hook) ---- */
  function applySkin() {
    const w = W(); if (!w) return;
    if (kind() === 'desktop' && w.RaviloDesk) return w.RaviloDesk.set('skin', S.skin);
    if (kind() === 'tv' && w.RaviloTV) return w.RaviloTV.setSkin(tvSkin(S.skin));
    /* the phone: write the same setting its Settings ▸ Theme writes. Following the system and the swatch fits the
       phone's light/dark right now → only that slot changes; otherwise follow goes off and the one pick holds. */
    if (kind() === 'phone' && w.RaviloTheme) {
      const T = w.RaviloTheme, lightNow = !T.osDark();
      if (T.pref.sync && T.isLight(S.skin) === lightNow) T.set(lightNow ? { light: S.skin } : { dark: S.skin });
      else T.set({ sync: false, single: S.skin });
      return;
    }
    load();
  }
  function applyMode() {
    const w = W(); if (!w) return;
    if (kind() === 'desktop' && w.RaviloDesk) return w.RaviloDesk.set('mode', S.mode);
    if (kind() === 'phone' && w.RaviloMusic) return w.RaviloMusic.setMode(S.mode === 'music' ? 'music' : 'video');
  }

  fence.addEventListener('click', e => {
    const f = e.target.closest('[data-f]');
    if (f) {
      const [k, v] = f.dataset.f.split(':'); if (S[k] === v) return;
      S[k] = v; save();
      if (k === 'plat') { miss = []; const w = W(); if (kind() === 'desktop' && loadedKind === 'desktop' && w && w.RaviloDesk) w.RaviloDesk.set('os', v); else load(); }
      else if (k === 'skin') applySkin();
      else if (k === 'mode') applyMode();
      else if (k === 'size') { const w = W(); if (w && w.RaviloDesk) w.RaviloDesk.set('size', v); else load(); }
      paint(); const w = W(); if (w) w.focus(); return;
    }
    const m = e.target.closest('[data-mx]');
    if (m) {
      const [gi, bi] = m.dataset.mx.split(':').map(Number), g = groups()[gi]; if (!g || !g.btns[bi]) return;
      g.btns[bi].click();
      if (g.btns[bi].dataset.dev) { S.dev = g.btns[bi].dataset.dev; save(); }
      setTimeout(() => { paint(); const w = W(); if (w) w.focus(); }, 60);
    }
  });
  fence.addEventListener('change', e => {
    const i = e.target.closest('[data-mxi]'); if (!i) return;
    const [gi, ii] = i.dataset.mxi.split(':').map(Number), g = groups()[gi]; if (!g || !g.ins[ii]) return;
    g.ins[ii].value = i.value; g.ins[ii].dispatchEvent(new Event('change'));
  });

  /* ---- read back what the viewer changed inside the app (the in-app mode switch, Settings ▸ Theme, …) ---- */
  setInterval(() => {
    const w = W(), d = D(); if (!w || !d || !d.documentElement || d.readyState !== 'complete' || loadedKind !== kind()) return;
    let ch = false;
    const set = (k, v) => { if (v && S[k] !== v) { S[k] = v; ch = true; } };
    if (kind() === 'desktop' && w.RaviloDesk) { const x = w.RaviloDesk.get(); set('mode', x.mode); set('skin', x.skin); set('size', x.size); set('plat', x.os === 'linux' ? 'linux' : 'mac'); }
    if (kind() === 'phone') { if (w.RaviloMusic) set('mode', w.RaviloMusic.MS.mode === 'music' ? 'music' : 'films'); set('skin', d.documentElement.getAttribute('data-skin')); }
    if (kind() === 'tv') { const k = d.documentElement.getAttribute('data-skin'); if (k && k !== tvSkin(S.skin)) set('skin', k); }
    if (w.RaviloState) { try { take(w.RaviloState.get()); } catch (e) {} }
    save();
    const ae = document.activeElement;
    if (ch || (mirrorSig() !== lastSig && !(ae && ae.matches && ae.matches('[data-mxi]')))) paint();
  }, 700);

  paint(); load();
})();
