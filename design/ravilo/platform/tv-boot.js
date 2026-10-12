/* The TV surface's boot (was the inline script of "Ravilo TV.html"). ?skin= ?static=1 ?chrome=0 ?focus=off|line|row ?dwell=ms */
(function () {
  const params = new URLSearchParams(location.search);
  const skin = document.documentElement.getAttribute('data-skin') || 'aurora';
  const interactive = params.get('static') !== '1';
  if (params.get('chrome') === '0') document.getElementById('chrome').remove();

  /* Focus detail is real product config, owned by Jellystructure per Jellyfin user and mocked through the same
     localStorage key app/ravilo-config.html writes. ?focus= lands before mount: the TV reads its config once on start. */
  const FDKEY = 'js-ravilo-focusdetail';
  const fdq = params.get('focus'), dwq = params.get('dwell');
  if (fdq === 'off' || fdq === 'line' || fdq === 'row' || dwq != null) {
    let cur = {}; try { cur = JSON.parse(localStorage.getItem(FDKEY) || '{}') || {}; } catch (e) {}
    if (fdq) { cur.line = fdq !== 'off'; cur.rowOpen = fdq === 'row'; }
    if (dwq != null && Number.isFinite(+dwq)) cur.delay = Math.max(0, Math.round(+dwq));
    localStorage.setItem(FDKEY, JSON.stringify(cur));
  }

  const stage = document.getElementById('stage');
  window.fitStage(stage);
  const app = mountRavilo(stage, { interactive });

  /* the one Ravilo file: where you are travels between the TV, the phone and the desktop (shape: mobile/ravilo-phone-state.js).
     Films & series only: the TV has no music, so a music place lands on Home and is left alone for the next platform. */
  const pageOf = v => {
    if (!v) return 'home';
    if (v.type === 'movie' || v.type === 'series') return pageOf(v.from);
    if (v.type === 'browse') return v.nav === 'movies' ? 'films' : v.nav === 'series' ? 'series' : v.nav === 'discover' ? 'discover' : pageOf(v.from);
    if (v.type === 'grid') return v.kind === 'mylist' ? 'mylist' : 'home';
    if (v.type === 'search') return v.seerr ? 'discover' : 'search';
    return /discover|taxonomy|upcoming/i.test(v.type) ? 'discover' : 'home';
  };
  window.RaviloState = {
    miss: [],
    get() {
      const v = app.view(), det = (v.type === 'movie' || v.type === 'series') && v.item;
      return { mode: 'films', page: pageOf(v), stack: det ? [{ kind: 'title', id: det.title }] : [], q: v.type === 'search' && !v.seerr ? (v.query || '') : '' };
    },
    set(st) {
      const miss = []; this.miss = miss; if (!st || st.mode === 'music') { app.navTo('home'); return miss; }
      const pg = st.page;
      if (pg === 'search') app.go({ type: 'search', query: st.q || '' }); else app.navTo(['films', 'series', 'discover', 'mylist'].indexOf(pg) >= 0 ? pg : 'home');
      (st.stack || []).forEach(d => {
        if (d.kind !== 'title') return;
        const it = app.catalog().find(x => x.title === d.id);
        if (it) app.go({ type: it.kind === 'series' ? 'series' : 'movie', item: it, from: app.view() }); else miss.push(d.id);
      });
      return miss;
    }
  };
  if (params.get('carry') === '1') { let st = null; try { st = JSON.parse(localStorage.getItem('ravilo-carry') || 'null'); } catch (e) {} if (st) setTimeout(() => window.RaviloState.set(st), 60); }

  const pick = document.getElementById('skinpick');
  const paintSkin = k => { if (pick) pick.querySelectorAll('button').forEach(x => x.classList.toggle('on', x.dataset.skin === k)); };
  paintSkin(skin);
  if (pick) pick.addEventListener('click', e => {
    const b = e.target.closest('button'); if (!b) return;
    document.documentElement.setAttribute('data-skin', b.dataset.skin); paintSkin(b.dataset.skin);
  });
  /* the shell's Theme row calls this: the TV is dark-only and draws three of the five themes today */
  window.RaviloTV = { setSkin(k) { document.documentElement.setAttribute('data-skin', k); paintSkin(k); } };

  /* focus-detail picker — writes the very same setting as Jellystructure's switch */
  const fdpick = document.getElementById('focuspick');
  if (fdpick) {
    const readFD = () => { let r = {}; try { r = JSON.parse(localStorage.getItem(FDKEY) || '{}') || {}; } catch (e) {} const d = Math.round(Number(r.delay)); return { line: r.line !== false, rowOpen: r.rowOpen !== false, delay: Number.isFinite(d) && d >= 0 ? d : 170 }; };
    const msIn = document.getElementById('fddelay');
    const paintFD = () => {
      const c = readFD(), v = c.rowOpen ? 'row' : (c.line ? 'line' : 'off');
      fdpick.querySelectorAll('button').forEach(b => b.classList.toggle('on', b.dataset.fd === v));
      if (document.activeElement !== msIn) msIn.value = c.delay;
    };
    const writeFD = next => {
      localStorage.setItem(FDKEY, JSON.stringify(Object.assign(readFD(), next)));
      window.dispatchEvent(new Event('ravilo:focuscfg'));
      paintFD();
    };
    fdpick.addEventListener('click', e => {
      const b = e.target.closest('button'); if (!b) return;
      writeFD({ line: b.dataset.fd !== 'off', rowOpen: b.dataset.fd === 'row' });
    });
    msIn.addEventListener('change', () => writeFD({ delay: Math.max(0, Math.round(+msIn.value || 0)) }));
    window.addEventListener('storage', e => { if (e.key === FDKEY) paintFD(); });
    paintFD();
  }
})();
