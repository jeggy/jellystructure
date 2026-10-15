/* Mac update — renderers for "Mac Update - Directions.html". Every [data-mu] mount is drawn here.
   Spec: data-mu="kind state lang skin opts" — kind: set · acct · win · rail · ph-about · ph-acct · ph-home · ph-profile. */
(function () {
  const P = {
    check: '<path d="M5 12.5l4.2 4.2L19 7"/>', down: '<path d="M12 4v12M6.5 11l5.5 5.5 5.5-5.5M5 20h14"/>',
    up: '<path d="M12 20V8M6.5 13 12 7.5l5.5 5.5M5 4h14"/>', clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
    alert: '<path d="M12 4 2.8 19.5h18.4z"/><path d="M12 10v4.2M12 17h.01"/>', x: '<path d="M6 6l12 12M18 6 6 18"/>',
    folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
    off: '<path d="M2 8.5a15 15 0 0 1 20 0M5.5 12a10 10 0 0 1 13 0M9 15.5a5 5 0 0 1 6 0"/><path d="M12 19h.01"/><path d="M3 3l18 18"/>',
    restart: '<path d="M4 12a8 8 0 1 0 2.6-5.9"/><path d="M4 4v4.5h4.5"/>', gear: '<circle cx="12" cy="12" r="3"/><path d="M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1"/>',
    play: '<path d="M8 5v14l11-7z"/>', pause: '<path d="M7 5h4v14H7zM13 5h4v14h-4z"/>', person: '<circle cx="12" cy="8" r="4"/><path d="M4 21c1-4 4-6 8-6s7 2 8 6"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>', home: '<path d="M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/>',
    compass: '<circle cx="12" cy="12" r="9"/><path d="M15.5 8.5l-2 5-5 2 2-5z"/>', bookmark: '<path d="M6 3h12v18l-6-4-6 4z"/>',
    film: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 4v16M17 4v16M3 9h4M17 9h4M3 15h4M17 15h4"/>', tv: '<rect x="3" y="5" width="18" height="12" rx="2"/><path d="M8 21h8"/>',
    phones: '<path d="M4 15v-3a8 8 0 0 1 16 0v3"/><rect x="3" y="14" width="4" height="6" rx="1"/><rect x="17" y="14" width="4" height="6" rx="1"/>',
    disc: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="2.5"/>', note: '<path d="M9 18V5l11-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="M20 20l-4-4"/>', back: '<path d="M15 5l-7 7 7 7"/>', fwd: '<path d="M9 5l7 7-7 7"/>', chev: '<path d="M9 6l6 6-6 6"/>',
    side: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M9 4v16"/>', lock: '<rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>', chat: '<path d="M4 5h16v11H9l-5 4z"/>',
    out: '<path d="M10 4H5v16h5M15 8l4 4-4 4M19 12H9"/>',
  };
  const ic = (n, c) => `<svg class="ic${n === 'play' || n === 'pause' ? ' f' : ''}${c ? ' ' + c : ''}" viewBox="0 0 24 24">${P[n]}</svg>`;
  const L = '<div class="lights"><i></i><i></i><i></i></div>';

  /* ---------- strings: en shipped/new · da · fo drafts ---------- */
  const S = {
    en: {
      settings: 'Settings', general: 'General', playback: 'Playback', account: 'Account', about: 'About', signed_in_as: 'Signed in as {name}', pw_change: 'Change password',
      su_title: 'Software update', su_version: 'Ravilo {v}', su_uptodate: 'Up to date', su_checked: 'Checked today at {t}', su_check_now: 'Check now',
      su_checking: 'Checking for updates…', su_downloading: 'Ravilo {v} is downloading', su_progress: '{a} of {b} MB',
      su_dl_sub: 'It downloads in the background. Ravilo asks before it restarts.',
      su_available: 'Ravilo {v} for Mac is available', su_size: '{n} MB', su_download: 'Download',
      su_ready: 'Ravilo {v} is ready', su_ready_sub: 'Restart Ravilo to finish. It takes a few seconds.', su_restart: 'Restart to update', su_whats_new: 'What’s new',
      su_wait_sub: 'Something is playing, so the update waits. Restart to update appears here when it stops.',
      su_held: 'Ravilo {v} is out', su_held_sub: 'Your server runs {sv}, so this Mac waits for the server. It updates once the server has {v}.',
      su_fail_dl: 'The update didn’t download', su_fail_dl_sub: 'Check that this Mac is online, then try again.',
      su_fail_ver: 'The update couldn’t be confirmed', su_fail_ver_sub: 'Ravilo installs only an update it can confirm is genuine. Try again to download it fresh.',
      su_try_again: 'Try again',
      su_move_title: 'Move Ravilo to your Applications folder',
      su_move_tmp: 'Ravilo is running from a temporary copy, which can’t reach your home network. Quit Ravilo, drag it to your Applications folder, and open it from there.',
      su_move_dmg: 'Ravilo is running from the disk image, which can’t reach your home network. Quit Ravilo, drag it to your Applications folder, and open it from there.',
      su_quit: 'Quit Ravilo', su_offline: 'Can’t check while offline', su_offline_sub: 'Last checked today at {t}. Ravilo checks again when this Mac is back online.',
      su_auto: 'Download updates automatically', su_auto_on: 'Ravilo still asks before it restarts.', su_auto_off: 'Ravilo tells you when an update is out and downloads it when you say so.',
      su_closing: 'Closing Ravilo to install {v}…', su_closing_sub: 'macOS takes it from here and opens Ravilo again.',
      su_notice: 'Update ready', su_notice_restart: 'Restart', su_notice_wait: 'After playback', su_hide: 'Hide',
      su_notes_title: 'New in {v}', su_n1: 'Faster start on the music tab', su_n2: 'Subtitles keep their size after a restart', su_n3: 'A clearer message when the server can’t be reached',
      menu_check: 'Check for Updates…', su_row_version: 'Version', su_row_server: 'Server', su_ship_install: 'Open the download and drag Ravilo to your Applications folder.',
      pm_profile: 'Your profile', sup_title: 'Support', sign_out: 'Sign out', films: 'Films & series', music: 'Music',
    },
    da: {
      settings: 'Indstillinger', general: 'Generelt', playback: 'Afspilning', account: 'Konto', about: 'Om', signed_in_as: 'Logget ind som {name}', pw_change: 'Skift adgangskode',
      su_title: 'Softwareopdatering', su_version: 'Ravilo {v}', su_uptodate: 'Opdateret', su_checked: 'Tjekket i dag kl. {t}', su_check_now: 'Tjek nu',
      su_checking: 'Søger efter opdateringer…', su_downloading: 'Ravilo {v} hentes', su_progress: '{a} af {b} MB',
      su_dl_sub: 'Den hentes i baggrunden. Ravilo spørger, før den genstarter.',
      su_available: 'Ravilo {v} til Mac er tilgængelig', su_size: '{n} MB', su_download: 'Hent',
      su_ready: 'Ravilo {v} er klar', su_ready_sub: 'Genstart Ravilo for at gøre det færdigt. Det tager få sekunder.', su_restart: 'Genstart for at opdatere', su_whats_new: 'Nyheder',
      su_wait_sub: 'Der afspilles noget, så opdateringen venter. Genstart for at opdatere vises her, når det stopper.',
      su_held: 'Ravilo {v} er udkommet', su_held_sub: 'Din server kører {sv}, så denne Mac venter på serveren. Den opdateres, når serveren har {v}.',
      su_fail_dl: 'Opdateringen blev ikke hentet', su_fail_dl_sub: 'Tjek, at denne Mac er online, og prøv igen.',
      su_fail_ver: 'Opdateringen kunne ikke bekræftes', su_fail_ver_sub: 'Ravilo installerer kun en opdatering, den kan bekræfte er ægte. Prøv igen for at hente den på ny.',
      su_try_again: 'Prøv igen',
      su_move_title: 'Flyt Ravilo til mappen Programmer',
      su_move_tmp: 'Ravilo kører fra en midlertidig kopi, som ikke kan nå dit hjemmenetværk. Afslut Ravilo, træk den til mappen Programmer, og åbn den derfra.',
      su_move_dmg: 'Ravilo kører fra diskbilledet, som ikke kan nå dit hjemmenetværk. Afslut Ravilo, træk den til mappen Programmer, og åbn den derfra.',
      su_quit: 'Afslut Ravilo', su_offline: 'Kan ikke søge, mens du er offline', su_offline_sub: 'Senest tjekket i dag kl. {t}. Ravilo søger igen, når denne Mac er online igen.',
      su_auto: 'Hent opdateringer automatisk', su_auto_on: 'Ravilo spørger stadig, før den genstarter.', su_auto_off: 'Ravilo fortæller, når der er en opdatering, og henter den, når du beder om det.',
      su_closing: 'Lukker Ravilo for at installere {v}…', su_closing_sub: 'macOS tager over herfra og åbner Ravilo igen.',
      su_notice: 'Opdatering klar', su_notice_restart: 'Genstart', su_notice_wait: 'Efter afspilning', su_hide: 'Skjul',
      su_notes_title: 'Nyt i {v}', su_n1: 'Hurtigere start på musikfanen', su_n2: 'Underteksterne beholder deres størrelse efter en genstart', su_n3: 'En tydeligere besked, når serveren ikke kan nås',
      menu_check: 'Søg efter opdateringer…', su_row_version: 'Version', su_row_server: 'Server', su_ship_install: 'Åbn overførslen, og træk Ravilo til mappen Programmer.',
      pm_profile: 'Din profil', sup_title: 'Support', sign_out: 'Log ud', films: 'Film og serier', music: 'Musik',
    },
    fo: {
      settings: 'Stillingar', general: 'Alment', playback: 'Avspæling', account: 'Kontu', about: 'Um', signed_in_as: 'Innritaður sum {name}', pw_change: 'Skift loyniord',
      su_title: 'Ritbúnaðardagføring', su_version: 'Ravilo {v}', su_uptodate: 'Dagført', su_checked: 'Kannað í dag kl. {t}', su_check_now: 'Kanna nú',
      su_checking: 'Leitar eftir dagføringum…', su_downloading: 'Ravilo {v} verður tikið niður', su_progress: '{a} av {b} MB',
      su_dl_sub: 'Tað verður tikið niður í bakgrundini. Ravilo spyr, áðrenn tað byrjar umaftur.',
      su_available: 'Ravilo {v} til Mac er tøkt', su_size: '{n} MB', su_download: 'Tak niður',
      su_ready: 'Ravilo {v} er klárt', su_ready_sub: 'Byrja Ravilo umaftur fyri at gera liðugt. Tað tekur fáein sekund.', su_restart: 'Byrja umaftur og dagfør', su_whats_new: 'Hvat er nýtt',
      su_wait_sub: 'Okkurt verður spælt, so dagføringin bíðar. Byrja umaftur og dagfør kemur her, tá ið tað steðgar.',
      su_held: 'Ravilo {v} er komið út', su_held_sub: 'Ambætarin hjá tær koyrir {sv}, so henda Mac bíðar eftir ambætaranum. Hon verður dagførd, tá ið ambætarin hevur {v}.',
      su_fail_dl: 'Dagføringin varð ikki tikin niður', su_fail_dl_sub: 'Kanna, at henda Mac er á netinum, og royn so aftur.',
      su_fail_ver: 'Dagføringin kundi ikki váttast', su_fail_ver_sub: 'Ravilo leggur bert eina dagføring inn, sum tað kann vátta er ekta. Royn aftur fyri at taka hana niður av nýggjum.',
      su_try_again: 'Royn aftur',
      su_move_title: 'Flyt Ravilo í Applications-mappuna',
      su_move_tmp: 'Ravilo koyrir frá einum fyribilsavriti, sum ikki røkkur heimanetið hjá tær. Lat Ravilo aftur, drag tað í Applications-mappuna og opna tað haðani.',
      su_move_dmg: 'Ravilo koyrir frá diskmyndini, sum ikki røkkur heimanetið hjá tær. Lat Ravilo aftur, drag tað í Applications-mappuna og opna tað haðani.',
      su_quit: 'Lat Ravilo aftur', su_offline: 'Kann ikki kanna uttan samband', su_offline_sub: 'Seinast kannað í dag kl. {t}. Ravilo kannar aftur, tá ið henda Mac er á netinum aftur.',
      su_auto: 'Tak dagføringar niður sjálvvirkandi', su_auto_on: 'Ravilo spyr framvegis, áðrenn tað byrjar umaftur.', su_auto_off: 'Ravilo sigur frá, tá ið ein dagføring er komin, og tekur hana niður, tá ið tú biður um tað.',
      su_closing: 'Letur Ravilo aftur fyri at leggja {v} inn…', su_closing_sub: 'macOS tekur við haðani og opnar Ravilo aftur.',
      su_notice: 'Dagføring klár', su_notice_restart: 'Byrja umaftur', su_notice_wait: 'Aftaná avspæling', su_hide: 'Fjal',
      su_notes_title: 'Nýtt í {v}', su_n1: 'Skjótari byrjan á tónleikateiginum', su_n2: 'Undirtekstirnir halda stødd síni eftir eina umbyrjan', su_n3: 'Greiðari boð, tá ið ambætarin ikki røkkst',
      menu_check: 'Leita eftir dagføringum…', su_row_version: 'Útgáva', su_row_server: 'Ambætari', su_ship_install: 'Opna niðurtøkuna og drag Ravilo í Applications-mappuna.',
      pm_profile: 'Tín vangamynd', sup_title: 'Support', sign_out: 'Rita út', films: 'Filmar og røð', music: 'Tónleikur',
    },
  };
  const V = { inst: '1.52', avail: '1.53', size: 94, host: 'media.example.net', srv: '1.52', got: 41 };
  const T = { en: '09:14', da: '09.14', fo: '09.14' };
  const t = (l, k, o) => (S[l][k] || S.en[k]).replace(/\{(\w+)\}/g, (_, n) => o && n in o ? o[n] : '{' + n + '}');
  const esc = s => s.replace(/&/g, '&amp;');

  /* ---------- the block ---------- */
  function block(st, l, o) {
    o = o || {};
    const v = V.inst, a = V.avail;
    let ic1 = 'check', cls = '', bc = '', ttl = '', sub = '', extra = '', act = '';
    const btn = (k, pri) => `<span class="btn${pri ? ' pri' : ''}">${esc(t(l, k))}</span>`;
    const lnk = `<span class="lnk">${t(l, 'su_whats_new')} ›</span>`;
    switch (st) {
      case 'uptodate': cls = 'ok'; ttl = `${t(l, 'su_version', { v })} · ${t(l, 'su_uptodate')}`; sub = t(l, 'su_checked', { t: T[l] }); act = btn('su_check_now'); break;
      case 'checking': ic1 = null; ttl = t(l, 'su_version', { v }); sub = t(l, 'su_checking'); break;
      case 'downloading': case 'dlstart': {
        const g = st === 'dlstart' ? 6 : V.got; ic1 = 'down'; cls = 'dl'; ttl = t(l, 'su_downloading', { v: a });
        extra = `<div class="pbar"><i style="width:${Math.round(g / V.size * 100)}%"></i></div><div class="pnum"><span>${t(l, 'su_progress', { a: g, b: V.size })}</span></div>`; sub = t(l, 'su_dl_sub'); break; }
      case 'available': ic1 = 'down'; cls = 'dl'; ttl = t(l, 'su_available', { v: a }); sub = t(l, 'su_size', { n: V.size }); act = btn('su_download', 1); break;
      case 'ready': ic1 = 'up'; bc = 'ready'; ttl = t(l, 'su_ready', { v: a }); sub = t(l, 'su_ready_sub'); act = btn('su_restart', 1) + (o.notes ? '' : lnk); break;
      case 'wait': ic1 = 'up'; bc = 'wait'; ttl = t(l, 'su_ready', { v: a }); sub = t(l, 'su_wait_sub'); act = o.notes ? '' : lnk; break;
      case 'held': ic1 = 'clock'; ttl = t(l, 'su_held', { v: a }); sub = t(l, 'su_held_sub', { sv: V.srv, v: a }); extra = `<span class="host">${V.host} · ${V.srv}</span>`; act = btn('su_check_now'); break;
      case 'faildl': ic1 = 'alert'; cls = 'bad'; bc = 'bad'; ttl = t(l, 'su_fail_dl'); sub = t(l, 'su_fail_dl_sub'); act = btn('su_try_again'); break;
      case 'failver': ic1 = 'alert'; cls = 'bad'; bc = 'bad'; ttl = t(l, 'su_fail_ver'); sub = t(l, 'su_fail_ver_sub'); act = btn('su_try_again'); break;
      case 'cantmp': case 'candmg': ic1 = 'folder'; cls = 'warn'; bc = 'warn'; ttl = t(l, 'su_move_title'); sub = t(l, st === 'cantmp' ? 'su_move_tmp' : 'su_move_dmg'); act = btn('su_quit'); break;
      case 'offline': ic1 = 'off'; ttl = `${t(l, 'su_version', { v })} · ${t(l, 'su_offline')}`; sub = t(l, 'su_offline_sub', { t: T[l] }); break;
      case 'closing': ic1 = null; bc = 'ready'; ttl = t(l, 'su_closing', { v: a }); sub = t(l, 'su_closing_sub'); break;
    }
    const si = ic1 ? ic(ic1) : '<span class="ring"></span>';
    const notes = o.notes && (st === 'ready' || st === 'wait') ? `<div class="mu-notes"><b>${t(l, 'su_notes_title', { v: a })}</b><ul><li>${t(l, 'su_n1')}</li><li>${t(l, 'su_n2')}</li><li>${t(l, 'su_n3')}</li></ul></div>` : '';
    const auto = !(st === 'cantmp' || st === 'candmg' || st === 'closing' || o.noToggle);
    const off = o.off || st === 'available';
    const tg = auto ? `<div class="r tgr"><div class="tt"><b>${t(l, 'su_auto')}</b><span>${t(l, off ? 'su_auto_off' : 'su_auto_on')}</span></div><span class="tg${off ? ' off' : ''}"></span></div>` : '';
    const head = o.noHead ? '' : `<div class="mu-h6">${t(l, 'su_title')}</div>`;
    return `<section class="mu-blk ${bc}">${head}<div class="mu-grp"><div class="mu-st ${cls}"><span class="si">${si}</span><div class="tx"><b>${ttl}</b><span>${sub}</span>${extra}</div>${act ? `<div class="act">${act}</div>` : ''}</div>${notes}${tg}</div></section>`;
  }

  /* ---------- desktop: Settings window ---------- */
  function setWin(tab, l, skin, body) {
    const tabs = [['general', 'gear'], ['playback', 'play'], ['account', 'person'], ['about', 'info']];
    return `<div class="mu mu-set" data-skin="${skin}"><div class="tb">${L}${tabs.map(([k, i]) => `<span class="t${k === tab ? ' on' : ''}">${ic(i)}${esc(t(l, k))}</span>`).join('')}</div><div class="bd">${body}</div></div>`;
  }
  const appHead = (l) => `<div class="mu-app"><span class="ai"></span><div><b>Ravilo</b><span>${t(l, 'su_row_version')} ${V.inst}</span></div></div>`;
  const serverGrp = (l) => `<div class="mu-grp"><div class="r"><span class="k">${t(l, 'su_row_server')}</span><span class="v m">${V.host}</span></div></div>`;
  function aboutBody(st, l, o) {
    return appHead(l) + (st === 'dev' ? '' : block(st, l, o)) + serverGrp(l);
  }
  function acctRows(st, l, o) {
    const name = '<span class="v" style="font-family:\'JetBrains Mono\',monospace;font-size:12px">{name}</span>';
    let line;
    if (o.today) line = `<div class="r" style="align-items:flex-start;padding:12px 14px"><div style="flex:1"><span class="k">${t(l, 'su_available', { v: V.avail })}</span><div style="font-size:11.5px;color:var(--ink-dim);margin-top:4px;line-height:1.45">${t(l, 'su_ship_install')}</div></div><span class="btn">${t(l, 'su_download')}</span></div>`;
    else line = `<div class="r" style="padding:10px 14px;background:var(--acc-soft);box-shadow:inset 3px 0 0 var(--accent)"><span class="k" style="font-weight:600">${t(l, 'su_ready', { v: V.avail })}</span><span class="btn pri" style="margin-left:auto">${t(l, 'su_restart')}</span></div><div class="r tgr"><div class="tt"><b>${t(l, 'su_auto')}</b><span>${t(l, 'su_auto_on')}</span></div><span class="tg"></span></div>`;
    return `<div class="mu-grp"><div class="r"><span class="k">${t(l, 'signed_in_as', { name: '' }).trim()}</span>${name}</div>${line}<div class="r"><span class="k">${t(l, 'pw_change')}</span>${ic('chev', 'chev')}</div></div>`;
  }

  /* ---------- desktop: main window ---------- */
  const NAVF = [['home', 'Home', 1], ['compass', 'Discover'], ['bookmark', 'My List'], [null, 'Library'], ['film', 'Films'], ['tv', 'Series']];
  const NAVM = [['phones', 'Listen', 1], ['disc', 'Albums'], ['note', 'Songs']];
  function side(o, l) {
    const nav = (o.play ? NAVM : NAVF).map(([i, n, on]) => i ? `<a class="${on ? 'on' : ''}">${ic(i)}<span>${n}</span></a>` : `<h6>${n}</h6>`).join('');
    const dot = o.n && o.n !== 'banner' ? '<span class="dot"></span>' : '';
    const row = o.n === 'row' ? (o.play ? `<div class="urow wait">${ic('up')}<b>${t(l, 'su_notice')}</b><em>${t(l, 'su_notice_wait')}</em></div>` : `<div class="urow">${ic('up')}<b>${t(l, 'su_notice')}</b><span class="rs">${t(l, 'su_notice_restart')}</span><span class="x">${ic('x')}</span></div>`) : '';
    return `<aside class="mside">${L}<div class="sgm"><b class="${o.play ? '' : 'on'}">${esc(t(l, 'films'))}</b><b class="${o.play ? 'on' : ''}">${t(l, 'music')}</b></div><div class="sfield">${ic('search')}<span>Search</span></div><div class="nv">${nav}</div><div class="foot">${row}<div class="who"><span class="avt">${ic('person')}</span><div><div class="nm">${t(l, 'pm_profile')}</div><div class="sub">${dot}${t(l, 'settings')} · ${t(l, 'sign_out')}</div></div></div></div></aside>`;
  }
  function railSide(o, l) {
    const nav = (o.play ? NAVM : NAVF).filter(x => x[0]).map(([i, n, on]) => `<a class="${on ? 'on' : ''}">${ic(i)}<span>${n}</span></a>`).join('');
    const ru = o.n === 'row' ? `<div class="ru">${ic('up')}<span>${o.play ? t(l, 'su_notice') : t(l, 'su_notice_restart')}</span></div>` : '';
    return `<aside class="mside rail">${L}<div class="sgm v"><b class="${o.play ? '' : 'on'}">${ic('film')}</b><b class="${o.play ? 'on' : ''}">${ic('note')}</b></div><div class="nv"><a>${ic('search')}<span>Search</span></a>${nav}</div><div class="foot">${ru}<span class="avt" style="width:30px;height:30px">${ic('person')}${o.n && o.n !== 'banner' ? '<span class="dot"></span>' : ''}</span></div></aside>`;
  }
  function content(o, l) {
    const ban = o.n === 'banner' ? (o.play ? `<div class="banner wait">${ic('up')}<b>${t(l, 'su_ready', { v: V.avail })}</b><span>· ${t(l, 'su_notice_wait')}</span><span class="x" style="margin-left:auto">${ic('x')}</span></div>` : `<div class="banner">${ic('up')}<b>${t(l, 'su_ready', { v: V.avail })}</b><span class="bb">${t(l, 'su_restart')}</span><span class="x">${ic('x')}</span></div>`) : '';
    const page = o.play ? `<div class="pg"><h1>Listen</h1><h4>Recently played</h4><div class="tiles"><i></i><i></i><i></i><i></i><i></i></div><h4>Albums</h4><div class="tiles"><i></i><i></i><i></i><i></i><i></i></div></div>`
      : `<div class="pg"><h1>Home</h1><h4>Continue watching</h4><div class="tiles w"><i></i><i></i><i></i></div><h4>New in Films</h4><div class="tiles"><i></i><i></i><i></i><i></i><i></i></div></div>`;
    const cap = o.play ? `<div class="caps"><span class="art"></span><div><div class="t">Music is playing</div><div class="s">stand-in · no title</div></div><span class="pp">${ic('pause')}</span><span class="sk"><i></i></span></div>` : '';
    return `<div class="mn"><div class="mtb"><span class="glb">${ic('back')}${ic('fwd')}</span></div>${ban}${page}${cap}</div>`;
  }
  const mainWin = (o, l, skin, rail) => `<div class="mu mu-win${rail ? ' rl' : ''}" data-skin="${skin}">${rail ? railSide(o, l) : side(o, l)}${content(o, l)}</div>`;

  /* ---------- mobile family ---------- */
  const bnav = (on, dot) => `<div class="bnav">${[['home', 'Home'], ['compass', 'Discover'], ['bookmark', 'My List'], ['person', 'Profile']].map(([i, n]) => `<div class="it${n === on ? ' on' : ''}"><span class="p">${ic(i)}${n === 'Profile' && dot ? '<span class="dot"></span>' : ''}</span><b>${n}</b></div>`).join('')}</div>`;
  const mini = (txt) => `<div class="mini"><span class="art"></span><div><div class="t">${txt}</div><div class="s">stand-in · no title</div></div><span class="pp">${ic('pause')}</span></div>`;
  const phone = (skin, ab, body, foot) => `<div class="mu mu-ph" data-skin="${skin}"><div class="strip">${L}</div>${ab}<div class="pb">${body}</div>${foot}</div>`;
  const abBack = title => `<div class="ab"><span class="bk">${ic('back')}</span><h1>${esc(title)}</h1></div>`;

  function render(spec) {
    const [kind, st, l = 'en', skin = 'aurora', ...opts] = spec.trim().split(/\s+/);
    const o = {}; opts.forEach(x => { const [k, v] = x.split('='); o[k] = v === undefined ? 1 : v; });
    switch (kind) {
      case 'set': return setWin('about', l, skin, aboutBody(st, l, o));
      case 'acct': return setWin('account', l, skin, acctRows(st, l, o));
      case 'win': return mainWin(o, l, skin, 0);
      case 'rail': return mainWin(o, l, skin, 1);
      case 'ph-about': return phone(skin, abBack(t(l, 'about')), appHead(l) + (st === 'dev' ? '' : block(st, l, o)) + serverGrp(l), (o.play ? mini('Music is playing') : '') + bnav('Profile', o.dot));
      case 'ph-acct': return phone(skin, abBack(t(l, 'account')), acctRows(st, l, o), bnav('Profile'));
      case 'ph-home': {
        const ban = o.n === 'banner' ? (o.play ? `<div class="pban wait">${ic('up')}<b>${t(l, 'su_notice')}</b><span>${t(l, 'su_notice_wait')}</span></div>` : `<div class="pban">${ic('up')}<b>${t(l, 'su_notice')}</b><span class="bb">${t(l, 'su_notice_restart')}</span></div>`) : '';
        const d = o.n === 'row' || o.n === 'dot';
        return phone(skin, `<div class="ab"><span class="wm">Ravilo</span><span class="sp"></span><span class="avt">${ic('person')}${d ? '<span class="dot"></span>' : ''}</span></div>`, '<div class="hero"></div><div class="rw"><i></i><i></i><i></i></div>', ban + (o.play ? mini('Music is playing') : '') + bnav('Home', d));
      }
      case 'ph-profile': {
        const row = o.play ? `<div class="purow wait">${ic('up')}<b>${t(l, 'su_notice')}</b><em>${t(l, 'su_notice_wait')}</em></div>` : `<div class="purow">${ic('up')}<b>${t(l, 'su_notice')}</b><span class="rs">${t(l, 'su_notice_restart')}</span><span class="x">${ic('x')}</span></div>`;
        const rows = `<div class="mu-grp"><div class="r">${ic('gear')}<span class="k">${t(l, 'settings')}</span><span class="dot" style="margin-left:auto;box-shadow:none"></span>${ic('chev', 'chev')}</div><div class="r">${ic('chat')}<span class="k">${t(l, 'sup_title')}</span>${ic('chev', 'chev')}</div><div class="r">${ic('out')}<span class="k">${t(l, 'sign_out')}</span></div></div>`;
        return phone(skin, '<div class="ab"><h1>Profile</h1></div>', `<div class="prf"><span class="avt">${ic('person')}</span><div><b>${t(l, 'pm_profile')}</b><span>${V.host}</span></div></div>${row}<div class="mode"><b class="on">${esc(t(l, 'films'))}</b><b>${t(l, 'music')}</b></div>${rows}`, (o.play ? mini('Music is playing') : '') + bnav('Profile', 1));
      }
    }
    return '';
  }
  document.querySelectorAll('[data-mu]').forEach(el => { el.outerHTML = render(el.getAttribute('data-mu')); });
  window.MU = { S, render, block };
})();
