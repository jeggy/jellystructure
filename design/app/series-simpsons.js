/* The Simpsons — big-series demo: scalable season picker + per-episode track editor.
   Seasons/episodes are generated; a few episodes per season carry issues so the
   attention dots and the editor cases are exercised. */
(function () {
  // real-ish episode counts per season (S1..S35)
  const COUNTS = [13,22,24,22,22,25,25,25,25,23,22,21,22,22,25,21,22,22,20,21,23,22,22,22,22,22,22,22,18,21,22,22,22,18,18];
  const SEASONS = COUNTS.map((n, i) => ({ s: i + 1, eps: n }));
  const TOTAL = COUNTS.reduce((a, b) => a + b, 0);

  // episode titles (cycled) + deterministic issue assignment
  const TITLES = ['Bart the Genius','Homer\u2019s Odyssey','There\u2019s No Disgrace Like Home','Moaning Lisa','The Telltale Head','Life on the Fast Lane','Krusty Gets Busted','Some Enchanted Evening','Treehouse of Horror','Bart Gets an F','Two Cars in Every Garage','Bart the Daredevil','Itchy & Scratchy','Bart Gets Hit by a Car','One Fish Two Fish','The Way We Was','Homer vs. Lisa','Principal Charming','Oh Brother','Old Money','Lisa\u2019s Substitute','War of the Simpsons','Three Men and a Comic','Blood Feud','Stark Raving Dad'];
  function epTitle(s, e) { return TITLES[(s * 7 + e) % TITLES.length]; }
  // issue kinds: 0 none, 1 untagged, 2 multiple-default, 3 missing still/overview
  function epIssue(s, e) {
    const h = (s * 31 + e * 17) % 23;
    if (e === 3 && s % 2 === 1) return 1;        // an untagged track
    if (e === 6 && s % 3 === 0) return 2;        // multiple default audio
    if (h === 0) return 3;                        // missing still/overview
    return 0;
  }
  function seasonIssues(s) {
    let untag = 0, multi = 0, miss = 0;
    for (let e = 1; e <= SEASONS[s-1].eps; e++) { const k = epIssue(s, e); if (k===1) untag++; else if (k===2) multi++; else if (k===3) miss++; }
    return { untag, multi, miss, total: untag + multi + miss };
  }
  // total attention across the series
  const seriesAttn = SEASONS.reduce((a, s) => a + seasonIssues(s.s).total, 0);

  let cur = 1; // current season

  /* ---------- language picker (searchable, full names) ---------- */
  window.RAVILO_LANGS = window.RAVILO_LANGS || [
    {code:'en',name:'English',endo:'English'},{code:'es',name:'Spanish',endo:'Espa\u00f1ol'},
    {code:'fr',name:'French',endo:'Fran\u00e7ais'},{code:'de',name:'German',endo:'Deutsch'},
    {code:'pt',name:'Portuguese',endo:'Portugu\u00eas'},{code:'it',name:'Italian',endo:'Italiano'},
    {code:'ja',name:'Japanese',endo:'\u65e5\u672c\u8a9e'},{code:'da',name:'Danish',endo:'Dansk'},
    {code:'nl',name:'Dutch',endo:'Nederlands'},{code:'pl',name:'Polish',endo:'Polski'},
    {code:'sv',name:'Swedish',endo:'Svenska'},{code:'fi',name:'Finnish',endo:'Suomi'},
  ];
  window.openLangMenu = window.openLangMenu || function (anchor, current, onPick) {
    document.querySelectorAll('.langmenu').forEach(m => m.remove());
    const data = window.RAVILO_LANGS;
    const menu = document.createElement('div'); menu.className = 'langmenu';
    menu.innerHTML = '<div class="lm-search"><span class="muted">\u2315</span><input type="text" placeholder="Search language\u2026" autocomplete="off"></div><div class="lm-list"></div>';
    document.body.appendChild(menu);
    const r = anchor.getBoundingClientRect();
    menu.style.top = (window.scrollY + r.bottom + 6) + 'px';
    menu.style.left = (window.scrollX + Math.min(r.left, window.innerWidth - 280)) + 'px';
    const search = menu.querySelector('input'), listEl = menu.querySelector('.lm-list');
    let active = 0, filtered = data.slice();
    function draw() { listEl.innerHTML = filtered.length ? filtered.map((l,i)=>'<div class="lm-opt'+(i===active?' active':'')+(l.code===current?' cur':'')+'" data-code="'+l.code+'"><span class="lm-name">'+l.name+'</span><span class="lm-endo">'+l.endo+'</span><span class="lm-code">'+l.code+'</span></div>').join('') : '<div class="lm-empty" style="padding:14px;text-align:center;color:var(--ink-dim);font-size:.82rem;">No language matches</div>'; }
    function filter() { const q = search.value.trim().toLowerCase(); filtered = data.filter(l=>!q||l.name.toLowerCase().includes(q)||l.endo.toLowerCase().includes(q)||l.code.includes(q)); active=0; draw(); }
    function choose(c){ if(c) onPick(c); close(); }
    function close(){ menu.remove(); document.removeEventListener('click', outside, true); }
    function outside(e){ if(!menu.contains(e.target) && !anchor.contains(e.target)) close(); }
    search.addEventListener('input', filter);
    search.addEventListener('keydown', e => { if(e.key==='ArrowDown'){active=Math.min(filtered.length-1,active+1);draw();e.preventDefault();} else if(e.key==='ArrowUp'){active=Math.max(0,active-1);draw();e.preventDefault();} else if(e.key==='Enter'){if(filtered[active])choose(filtered[active].code);e.preventDefault();} else if(e.key==='Escape'){close();e.preventDefault();} });
    listEl.addEventListener('click', e => { const o=e.target.closest('.lm-opt'); if(o) choose(o.dataset.code); });
    draw(); setTimeout(()=>{ document.addEventListener('click', outside, true); search.focus(); }, 0);
  };

  /* ---------- scalable season picker ---------- */
  const btn = document.getElementById('sp-btn'), menu = document.getElementById('sp-menu');
  const listEl = document.getElementById('sp-list'), q = document.getElementById('sp-q');
  const prev = document.getElementById('sp-prev'), next = document.getElementById('sp-next');

  function drawMenu(filter) {
    const f = (filter || '').trim();
    const rows = SEASONS.filter(s => !f || String(s.s).includes(f));
    listEl.innerHTML = rows.map(s => {
      const iss = seasonIssues(s.s);
      return '<div class="sp-opt'+(s.s===cur?' cur':'')+'" data-s="'+s.s+'">' +
        '<span class="nm">Season '+s.s+'</span>' +
        (iss.total ? '<span class="att" title="'+iss.total+' need attention"></span>' : '') +
        '<span class="ec">'+s.eps+' eps</span></div>';
    }).join('') || '<div style="padding:14px;text-align:center;color:var(--ink-dim);font-size:.82rem;">No season '+f+'</div>';
  }
  function openMenu() { drawMenu(''); q.value=''; menu.classList.add('open'); setTimeout(()=>{ document.addEventListener('click', spOutside, true); q.focus(); },0); }
  function closeMenu() { menu.classList.remove('open'); document.removeEventListener('click', spOutside, true); }
  function spOutside(e){ if(!menu.contains(e.target) && !btn.contains(e.target)) closeMenu(); }
  btn.addEventListener('click', () => menu.classList.contains('open') ? closeMenu() : openMenu());
  q.addEventListener('input', () => drawMenu(q.value));
  q.addEventListener('keydown', e => { if (e.key==='Enter') { const n=parseInt(q.value,10); if(n>=1&&n<=SEASONS.length){ selectSeason(n); closeMenu(); } } else if (e.key==='Escape') closeMenu(); });
  listEl.addEventListener('click', e => { const o=e.target.closest('[data-s]'); if(o){ selectSeason(+o.dataset.s); closeMenu(); } });
  prev.addEventListener('click', () => selectSeason(cur-1));
  next.addEventListener('click', () => selectSeason(cur+1));

  function selectSeason(n) {
    if (n < 1 || n > SEASONS.length) return;
    cur = n;
    const eps = SEASONS[n-1].eps;
    document.getElementById('season-title').textContent = 'Season ' + n;
    btn.childNodes[0].nodeValue = 'Season ' + n + ' ';
    document.getElementById('sp-btn-cnt').textContent = eps + ' episodes';
    prev.toggleAttribute('disabled', n === 1);
    next.toggleAttribute('disabled', n === SEASONS.length);
    renderSeasonbar(n); renderEpisodes(n);
  }

  function renderSeasonbar(n) {
    const iss = seasonIssues(n);
    const bar = document.getElementById('seasonbar');
    const chips = ['<span class="muted tiny">episode-level:</span>'];
    if (iss.untag) chips.push('<span class="chip"><span class="dot bad"></span> '+iss.untag+' untagged</span>');
    if (iss.multi) chips.push('<span class="chip"><span class="dot bad"></span> '+iss.multi+' multiple-default</span>');
    if (iss.miss) chips.push('<span class="chip"><span class="dot warn"></span> '+iss.miss+' missing still/overview</span>');
    if (!iss.total) chips.push('<span class="chip"><span class="dot ok"></span> all complete</span>');
    chips.push('<span class="spacer" style="flex:1"></span><span class="btn sm ghost" id="expand-issues">Expand all issues</span>');
    bar.innerHTML = chips.join('');
    bar.querySelector('#expand-issues').addEventListener('click', () => document.querySelectorAll('.ep.attn').forEach(e => e.classList.add('open')));
  }

  function pad(n){ return String(n).padStart(2,'0'); }
  function fmt(s){ return Math.floor(s/60)+':'+pad(Math.round(s%60)); }
  /* Phase 15x: per-episode intro/credits segment editor (write-through). Values are
     synthesized deterministically here so the demo shows detected / low-confidence /
     not-detected / stinger cases. */
  function segBlock(n, e) {
    const runMin = 21 + (e % 3), runSec = runMin * 60;
    const st = (n * 3 + e) % 5;
    const src = (st === 0 || st === 1 || st === 2) ? 'fingerprint' : (st === 3 ? 'heuristic' : null);
    const stinger = ((n + e) % 11 === 0);
    const id = 'S' + pad(n) + 'E' + pad(e);
    if (!src) {
      return '<div class="segwrap nodet">' +
        '<div class="seg-head"><span class="seg-lbl">Intro &amp; credits</span><span class="badge bad" style="margin-left:8px;">not detected</span></div>' +
        '<div class="segbar empty"><span class="seg-empty">No markers — Ravilo falls back to a Next-Up card 34s before the file ends.</span></div>' +
        '<div class="seg-foot"><span class="muted tiny">Run detection to enable Skip Intro / Skip Credits here.</span><span class="spacer"></span>' +
          '<span class="btn sm seg-rescan" data-ep="' + id + '">↻ Detect segments</span></div>' +
      '</div>';
    }
    const iS = 28 + (e % 4) * 4, iE = iS + 70 + (e % 3) * 8, cS = runSec - (52 + (e % 3) * 8);
    const conf = (src === 'fingerprint' ? 0.90 + (e % 6) / 60 : 0.55 + (e % 4) / 40).toFixed(2);
    const pct = v => (v / runSec * 100);
    const srcBadge = src === 'fingerprint'
      ? '<span class="badge seg-src-fp">fingerprint · ' + conf + '</span>'
      : '<span class="badge warn">heuristic · ' + conf + '</span>';
    return '<div class="segwrap">' +
      '<div class="seg-head"><span class="seg-lbl">Intro &amp; credits</span>' +
        '<span class="seg-legend"><span><i class="sw sw-i"></i>Intro</span><span><i class="sw sw-c"></i>Credits</span>' +
        (stinger ? '<span><i class="sw sw-s"></i>Post-credits scene</span>' : '') + '</span></div>' +
      '<div class="segbar">' +
        '<div class="seg-ticks"></div>' +
        '<div class="seg-intro" style="left:' + pct(iS).toFixed(1) + '%;width:' + (pct(iE) - pct(iS)).toFixed(1) + '%"><i class="h l"></i><i class="h r"></i></div>' +
        '<div class="seg-cred" style="left:' + pct(cS).toFixed(1) + '%"><i class="h"></i></div>' +
        (stinger ? '<div class="seg-stg" style="left:97%"></div>' : '') +
      '</div>' +
      '<div class="seg-foot">' +
        '<span class="chip mono">Intro ' + fmt(iS) + '–' + fmt(iE) + '</span>' +
        '<span class="chip mono">Credits ' + fmt(cS) + '</span>' + srcBadge +
        (stinger ? '<span class="badge seg-stinger" title="TMDB tag: aftercreditsstinger">★ scene after credits</span>' : '') +
        '<span class="spacer"></span>' +
        '<span class="btn sm ghost seg-rescan" data-ep="' + id + '">↻ Re-scan</span>' +
        '<span class="btn sm ghost seg-lock" data-ep="' + id + '">🔓 Lock</span>' +
      '</div>' +
    '</div>';
  }
  function renderEpisodes(n) {
    const eps = SEASONS[n-1].eps;
    let html = '';
    for (let e = 1; e <= eps; e++) {
      const id = 'S'+pad(n)+'E'+pad(e);
      const k = epIssue(n, e);
      const attn = k !== 0;
      let flags = '<span class="badge ok">complete</span>';
      if (k===1) flags = '<span class="badge bad">1 untagged track</span>';
      else if (k===2) flags = '<span class="badge bad">multiple default audio</span>';
      else if (k===3) flags = '<span class="badge bad">missing still</span><span class="badge bad">no overview</span>';
      const still = k===3 ? '<span style="font-size:.5rem;background:var(--bad-soft);border-color:var(--bad);color:var(--bad);">no still</span>' : '<span style="font-size:.52rem;">still</span>';
      html += '<div class="ep'+(attn?' attn':'')+'" data-ep="'+id+'">'+
        '<div class="ep-row"><div class="imgslot ep-thumb">'+still+'</div>'+
          '<div class="ep-meta"><div class="t"><span class="num">'+id+'</span> '+epTitle(n,e)+' <span class="lang" style="font-size:.7rem;">en</span></div>'+
          '<div class="s">'+(21+ (e%3))+' min · <span class="ep-flags">'+flags+'</span></div></div>'+
          '<span class="ep-chev">\u203a</span></div>'+
        '<div class="ep-body"><div class="grid2">'+
          '<div class="imgslot" style="height:150px;">'+(k===3?'<span style="background:var(--bad-soft);border-color:var(--bad);color:var(--bad);">still · missing</span>':'<span>still / thumb</span>')+'</div>'+
          '<div class="col" style="gap:10px;">'+
            '<div class="field" style="margin:0;"><label>Episode title</label><input class="input" value="'+epTitle(n,e).replace(/"/g,'&quot;')+'"></div>'+
            '<div class="field" style="margin:0;"><label>Overview</label><textarea class="input" rows="2" '+(k===3?'placeholder="No overview — fetch from TMDB or write one…" style="resize:vertical;background:var(--bad-soft);border-color:var(--bad);"':'style="resize:vertical;"')+'>'+(k===3?'':'Springfield gets up to its usual antics in this episode.')+'</textarea></div>'+
            '<div class="row center" style="gap:8px;flex-wrap:wrap;"><span class="muted tiny">tracks:</span><span class="chip mono">en · eac3 ★</span><span class="chip mono">en · ac3 SDH</span><span class="spacer"></span>'+
              (k===1?'<span class="badge bad">1 untagged</span>':'')+(k===2?'<span class="badge bad">2 defaults</span>':'')+
              '<span class="btn sm '+(attn?'':'ghost')+' ep-tracks" data-ep="'+id+'" data-k="'+k+'">'+(attn?'Fix':'Edit')+' tracks &amp; order \u2192</span></div>'+
          '</div>'+
        '</div>' + segBlock(n, e) + '</div></div>';
    }
    const wrap = document.getElementById('eplist');
    wrap.innerHTML = html;
    wrap.querySelectorAll('.ep-row').forEach(r => r.addEventListener('click', () => r.closest('.ep').classList.toggle('open')));
    wrap.querySelectorAll('.ep-tracks').forEach(b => b.addEventListener('click', e => { e.stopPropagation(); openEditor(b.dataset.ep, +b.dataset.k); }));
    wrap.querySelectorAll('.seg-rescan').forEach(b => b.addEventListener('click', ev => { ev.stopPropagation(); teToast('Re-scanning ' + b.dataset.ep + ' for intro & credits…'); }));
    wrap.querySelectorAll('.seg-lock').forEach(b => b.addEventListener('click', ev => {
      ev.stopPropagation(); b.classList.toggle('on'); const on = b.classList.contains('on');
      b.innerHTML = on ? '🔒 Locked' : '🔓 Lock';
      const sw = b.closest('.segwrap'); if (sw) sw.classList.toggle('locked', on);
      teToast(on ? 'Locked — scans won’t overwrite these markers' : 'Unlocked — scans may update these markers');
    }));
  }

  /* ---------- episode track editor (same merged editor) ---------- */
  const NAME = Object.fromEntries(window.RAVILO_LANGS.map(l=>[l.code,l.name]).concat([['','untagged']]));
  const teModal = document.getElementById('te-modal');
  const teList = document.getElementById('te-list'), teSeg = document.getElementById('te-seg'), teExplain = document.getElementById('te-explain');
  let model, orig, tkind, tfile;

  function datasetFor(id, k) {
    const audio = [
      {sp:'0:a:0',lang:'en',codec:'eac3 · 5.1',title:'',def:true},
      {sp:'0:a:1',lang:'es',codec:'ac3 · stereo',title:'Latino',def:false},
    ];
    const subs = [{sp:'0:s:0',lang:'en',codec:'srt',title:'',def:true,forced:false}];
    if (k===1) audio.push({sp:'0:a:2',lang:'',codec:'ac3',title:'commentary',def:false});
    if (k===2) audio[1].def = true; // two defaults
    return { audio, subs };
  }
  function openEditor(id, k) {
    const src = datasetFor(id, k);
    tfile = 'The Simpsons - ' + id + '.mkv'; tkind = 'audio';
    model = { audio: JSON.parse(JSON.stringify(src.audio)), subs: JSON.parse(JSON.stringify(src.subs)) };
    orig = JSON.parse(JSON.stringify(model));
    [...teSeg.children].forEach(x => x.classList.toggle('on', x.dataset.tk==='audio'));
    document.getElementById('te-ep').textContent = id;
    document.getElementById('te-file').textContent = tfile;
    renderTE(); teModal.classList.add('open');
  }
  function renderTE() {
    const arr = model[tkind];
    teList.innerHTML = arr.map((t,i) => {
      const untag = !t.lang, dCount = arr.filter(x=>x.def).length;
      const star = (t.def && dCount===1) ? '<span class="badge warn">★ default</span>' : '<span class="btn sm '+(t.def?'':'ghost')+'" data-act="default" data-i="'+i+'">'+(t.def?'★ keep this one':'set default ★')+'</span>';
      const forced = tkind==='subs' ? '<label class="row center tiny" style="gap:6px;cursor:pointer;"><span class="mini-toggle'+(t.forced?' on':'')+'" data-act="forced" data-i="'+i+'"></span> forced</label>' : '';
      const lang = untag ? '<span class="lang-pickwrap" data-act="lang" data-i="'+i+'"><span class="badge bad">no language</span> <span class="muted" style="font-size:.7rem;">▾</span></span>'
        : '<span class="lang-pickwrap" data-act="lang" data-i="'+i+'"><span class="lang">'+t.lang+'</span> <span class="muted tiny">'+(NAME[t.lang]||'')+'</span> <span class="muted" style="font-size:.7rem;">▾</span></span>';
      return '<div class="trk'+(t.def?' isdef':'')+(untag?' untagged':'')+'" draggable="true" data-i="'+i+'"><div class="trk-main">'+
        '<span class="te-grip">⠿</span><span class="te-ord"><span class="ord-btn" data-act="up" data-i="'+i+'"'+(i===0?' disabled':'')+'>▲</span><span class="ord-btn" data-act="down" data-i="'+i+'"'+(i===arr.length-1?' disabled':'')+'>▼</span></span>'+
        '<span class="te-pos">'+(i+1)+'</span><span class="num" style="width:48px;">'+t.sp+'</span>'+lang+
        '<span class="muted tiny mono">'+t.codec+(t.title?' · "'+t.title+'"':'')+'</span><span class="spacer"></span>'+forced+star+'</div></div>';
    }).join('');
    const dc = model.audio.filter(t=>t.def).length;
    teExplain.innerHTML = (tkind==='audio' && dc>1)
      ? '<span class="tiny"><b style="color:var(--bad)">Multiple default audio tracks.</b> A file should have exactly one — ★ the one to keep; the others are cleared.</span>'
      : (tkind==='audio' ? '<span class="tiny"><b>▲▼ Order</b> drives the metadata language (first match wins). <b>★ Default</b> is what the player auto-selects — independent of order.</span>'
      : '<span class="tiny"><b>★ Default</b> is the auto-shown subtitle. <b>Forced</b> is a separate flag — foreign-dialogue only; can be forced without being default.</span>');
    diffTE();
  }
  teList.addEventListener('click', e => {
    const el = e.target.closest('[data-act]'); if(!el) return;
    const i=+el.dataset.i, arr=model[tkind], act=el.dataset.act;
    if(act==='up'&&i>0){[arr[i-1],arr[i]]=[arr[i],arr[i-1]];renderTE();}
    else if(act==='down'&&i<arr.length-1){[arr[i+1],arr[i]]=[arr[i],arr[i+1]];renderTE();}
    else if(act==='default'){arr.forEach((t,k)=>t.def=(k===i));renderTE();}
    else if(act==='forced'){arr[i].forced=!arr[i].forced;renderTE();}
    else if(act==='lang'){ const a=el.closest('.lang-pickwrap')||el; window.openLangMenu(a,arr[i].lang,c=>{arr[i].lang=c;renderTE();}); }
  });
  let dI=null;
  teList.addEventListener('dragstart', e=>{const r=e.target.closest('.trk');if(!r)return;dI=+r.dataset.i;r.classList.add('dragging');});
  teList.addEventListener('dragend', ()=>{dI=null;teList.querySelectorAll('.trk').forEach(r=>r.classList.remove('dragging','drop-before','drop-after'));});
  teList.addEventListener('dragover', e=>{e.preventDefault();const r=e.target.closest('.trk');if(!r||dI===null)return;const o=+r.dataset.i;teList.querySelectorAll('.trk').forEach(x=>x.classList.remove('drop-before','drop-after'));if(o!==dI)r.classList.add(o<dI?'drop-before':'drop-after');});
  teList.addEventListener('drop', e=>{e.preventDefault();const r=e.target.closest('.trk');if(!r||dI===null)return;const to=+r.dataset.i,arr=model[tkind];if(to===dI)return;const[m]=arr.splice(dI,1);arr.splice(to,0,m);renderTE();});
  teSeg.addEventListener('click', e=>{const s=e.target.closest('[data-tk]');if(!s)return;[...teSeg.children].forEach(x=>x.classList.toggle('on',x===s));tkind=s.dataset.tk==='subs'?'subs':'audio';renderTE();});

  function tname(sp){const m=/0:([as]):(\d+)/.exec(sp);return 'track:'+m[1]+(+m[2]+1);}
  function diffTE() {
    const ops=[]; let reorder=false;
    ['audio','subs'].forEach(k=>{const o=orig[k],n=model[k];
      if(n.some((t,i)=>o[i].sp!==t.sp)){reorder=true;ops.push({ic:'⇅',text:(k==='audio'?'Audio':'Subtitle')+' order → '+n.map(t=>t.sp.split(':').pop()).join(', ')});}
      n.forEach(t=>{const ob=o.find(x=>x.sp===t.sp);if(!ob)return;
        if(ob.lang!==t.lang)ops.push({ic:'🏷',text:t.sp+' language → '+(t.lang||'untagged')});
        if(ob.def!==t.def&&t.def)ops.push({ic:'★',text:t.sp+' default '+k});
        if(k==='subs'&&ob.forced!==t.forced)ops.push({ic:'⮕',text:t.sp+' forced '+(t.forced?'on':'off')});});});
    document.getElementById('te-count').textContent=ops.length;
    document.getElementById('te-staged').style.display=ops.length?'':'none';
    document.getElementById('te-clean').style.display=ops.length?'none':'';
    if(!ops.length)return;
    document.getElementById('te-ops').innerHTML=ops.map(o=>'<div class="staged-op"><span class="op-ic">'+o.ic+'</span><span>'+o.text+'</span></div>').join('');
    const lines=[],parts=[];
    ['audio','subs'].forEach(k=>model[k].forEach(t=>{const ob=orig[k].find(x=>x.sp===t.sp);if(!ob)return;
      if(ob.lang!==t.lang&&t.lang)parts.push('  --edit '+tname(t.sp)+' --set language='+t.lang);
      if(ob.def!==t.def)parts.push('  --edit '+tname(t.sp)+' --set flag-default='+(t.def?1:0));
      if(k==='subs'&&ob.forced!==t.forced)parts.push('  --edit '+tname(t.sp)+' --set flag-forced='+(t.forced?1:0));}));
    if(parts.length)lines.push('mkvpropedit "'+tfile+'" \\\n'+parts.join(' \\\n'));
    if(reorder){if(lines.length)lines.push('');const maps=model.audio.concat(model.subs).map(t=>'-map '+t.sp).join(' ');lines.push('ffmpeg -i "'+tfile+'" \\\n  '+maps+' -map 0:v -c copy \\\n  "'+tfile+'.reordered.mkv"  # -c copy = no re-encode');}
    document.getElementById('te-cmd').innerHTML=lines.join('\n').replace(/(--edit|--set|-map|-c copy|-i)/g,'<span class="flag">$1</span>');
    document.getElementById('te-cost').innerHTML=reorder?'<span class="badge warn">⚠ remux</span> <span class="tiny muted">reordering needs an ffmpeg -c copy remux (rewrites the file)</span>':'<span class="badge ok">instant</span> <span class="tiny muted">mkvpropedit edits flags in place · ~40ms</span>';
  }
  function teToast(m){const t=document.createElement('div');t.className='toast';t.textContent=m;document.body.appendChild(t);setTimeout(()=>t.remove(),1700);}
  function teReset(){['audio','subs'].forEach(k=>model[k]=JSON.parse(JSON.stringify(orig[k])));renderTE();}
  document.getElementById('te-discard').addEventListener('click', teReset);
  document.getElementById('te-apply').addEventListener('click', ()=>{['audio','subs'].forEach(k=>orig[k]=JSON.parse(JSON.stringify(model[k])));renderTE();teToast('Applied to '+tfile+' ✓');});
  document.getElementById('te-x').addEventListener('click', ()=>teModal.classList.remove('open'));
  teModal.addEventListener('click', e=>{if(e.target===teModal)teModal.classList.remove('open');});

  /* ---------- tabs + re-pull + misc ---------- */
  const tabbar=document.getElementById('tabbar'), panels=document.querySelectorAll('.tabpanel');
  function showTab(n){ if(![...tabbar.children].some(t=>t.dataset.tab===n))n='episodes'; [...tabbar.children].forEach(t=>t.classList.toggle('on',t.dataset.tab===n)); panels.forEach(p=>p.style.display=p.dataset.panel===n?'':'none'); }
  tabbar.addEventListener('click', e=>{const t=e.target.closest('[data-tab]');if(t)location.hash='tab='+t.dataset.tab;});
  window.addEventListener('hashchange', ()=>{const m=/tab=([a-z]+)/.exec(location.hash||'');showTab(m?m[1]:'episodes');});
  const rm=document.getElementById('repull-modal');
  document.getElementById('repull-jf-btn').addEventListener('click',()=>rm.classList.add('open'));
  document.getElementById('repull-x').addEventListener('click',()=>rm.classList.remove('open'));
  document.getElementById('repull-cancel').addEventListener('click',()=>rm.classList.remove('open'));
  document.getElementById('repull-go').addEventListener('click',e=>{e.target.textContent='Re-pulling…';setTimeout(()=>{e.target.textContent='Re-pull';rm.classList.remove('open');},900);});
  document.addEventListener('keydown', e=>{ if(e.key==='Escape'){ rm.classList.remove('open'); teModal.classList.remove('open'); } });

  // headline counts
  document.getElementById('series-attn').textContent = seriesAttn + ' episodes need attention';
  document.getElementById('total-eps').textContent = TOTAL + ' episodes';

  // ---- series poster (generated, brand-safe key art) ----
  (function paintPoster() {
    const el = document.getElementById('series-poster'); if (!el) return;
    el.style.position = 'relative';
    el.style.background = 'linear-gradient(150deg,#ffd21e 0%,#f0a30a 48%,#7c4dff 100%)';
    el.innerHTML =
      '<div style="position:absolute;inset:0;background:radial-gradient(120% 80% at 80% 0%,rgba(255,255,255,.35),transparent 60%);"></div>' +
      '<div style="position:absolute;inset:0;display:flex;flex-direction:column;justify-content:flex-end;padding:18px;">' +
        '<div style="font-family:\'Space Grotesk\',sans-serif;font-weight:700;font-size:1.5rem;line-height:1.05;color:#1a1206;text-shadow:0 1px 0 rgba(255,255,255,.25);">The Simpsons</div>' +
        '<div style="font-size:.8rem;font-weight:600;color:rgba(26,18,6,.7);margin-top:4px;">1989 · 35 seasons</div>' +
      '</div>';
  })();

  const m=/tab=([a-z]+)/.exec(location.hash||''); showTab(m?m[1]:'episodes');
  selectSeason(1);
})();
