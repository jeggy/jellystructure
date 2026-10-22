/* Container conversion (AVI → MKV) — direction A, "file action reached from the failed row".
   DEB-2137, promoted by hand from the 2026-10-22 avi-mkv-migration round with the owner's D2/D3
   answers applied. Mockup-only: this file is NOT in syncDesignAssets and never ships. The real
   admin draws the same states from the server payload (spec FR-9) in MediaDetail.kt/TrackEditor.kt;
   the CSS it uses is production and lives in detail.css.

   This script derives every frame from the one STR table below and the one SCENE object, so the
   pictures cannot disagree with each other or with the strings handed to i18n.

   Demo switches (query string):
     ?container=avi          show the AVI stand-in (Sample Film) — required, nothing runs without it
     &cv=<state>             force one of the states in V below; omit for the live flow
     &lang=en|da|fo          draw the frame in that language (en default; da/fo are drafts)
     &ep=1                   draw the row as an EPISODE: same sentence, no link (FR-2)
   Nothing converts as a side effect: the only start control is "Start migration" in the dialog. */
(function () {
  'use strict';

  /* ---- strings (FR-10). en ships as written; da and fo are drafts awaiting a native read. ------
     A key that already ships in i18n/*.json (language names, Cancel, the seeding sentence,
     Activity ▸ Jobs) keeps its shipped string — the copy here is only so the mockup renders. */
  const STR = {
    en: {
      unknown: 'Unknown', fao: 'Faroese (fao)', triage: 'No language tag · in Triage',
      notKept: 'Not kept — this AVI can’t store a language.', requested: 'Requested: {lang}.',
      why: 'Why, and what you can do',
      stillFail: 'Still Unknown — the conversion to MKV didn’t finish.',
      stillUnv: 'Still Unknown — the new file had no language tag.',
      blkRow: 'Not kept — converting this file to MKV is blocked right now.',
      pthRow: 'Not kept — migration is unavailable for this file.',
      seeAbove: 'See above', locked: 'Locked while the conversion runs', fileScope: 'File · {file}',

      limBadge: 'AVI limitation', limTitle: 'This AVI can’t keep an audio language',
      limBody: 'AVI has no place to store a language for {track}, so {lang} can’t be saved in this file. Converting the file to MKV can keep it.',
      streamCopy: 'Video and audio are copied without re-encoding',
      review: 'Review the conversion to MKV', keep: 'Keep as AVI',
      keepNote: 'Keep as AVI: the language stays Unknown and the track stays in Triage.',

      pvTitle: 'Convert to MKV — what would change',
      container: 'Container', ext: 'Extension', streams: 'Streams', copied: 'copied untouched',
      langReq: 'Language requested', estimate: 'Estimate',
      estVal: 'about 2 minutes · 1.4 GB of temporary space',
      tempWhere: 'Temporary files go in a hidden folder beside the source, on the same disk.',
      estUnk: 'Estimate unavailable',
      estUnkBody: 'Time and temporary space can’t be worked out for this file.',
      before: 'Before it starts',
      beforeVal: 'Torrent safety is checked again when you press Start migration.',
      /* D2 — decided. The original goes into the hidden folder and is kept until the owner
         removes it. No "Tentative" badge anywhere: there is nothing tentative left. */
      oldFile: 'Old file',
      oldVal: '{file} moves into a hidden folder beside the new file and is kept until you remove it.',
      cont: 'Continue to confirmation', cancel: 'Cancel',

      cfTitle: 'Convert {title} to MKV?', source: 'Source',
      /* D3 — the name is fixed (same name, new extension) and shown as text. "Proposed" is gone
         from the label with the field it described, and fnameHint is dropped. */
      dest: 'Destination', fname: 'File name',
      srcStays: 'Stays in place until the new file is verified',
      start: 'Start migration', close: 'Close',

      blkBadge: 'Blocked', blkTitle: 'This file can’t be converted right now',
      seedShort: 'This file is linked to an active seeding torrent',
      seedLong: 'This file is shared with a torrent that is still seeding (hard link), so it can’t be changed in place.',
      unkShort: 'The torrent safety check couldn’t be reached',
      unkBody: 'So this file is treated as shared. Migration stays unavailable until the check answers.',
      /* FR-5.4 and FR-5.5 — two more reasons the same Blocked notice carries. No new frame. */
      takenShort: 'A file named {file} is already there, so nothing was converted.',
      takenBody: 'Nothing on disk was touched. Move or remove that file, then check again.',
      roomShort: 'Not enough free space on this disk: {need} needed, {free} free.',
      roomBody: 'The conversion needs room for a second copy of the file while it runs. Free some space, then check again.',
      checkAgain: 'Check again', openSeeding: 'Open the Seeding tab',
      whyNeeded: 'Why it came up: AVI can’t store a language for {track}, so {lang} needs a conversion to MKV.',

      pathBadge: 'Path mismatch', pathTitle: 'Migration unavailable',
      pathBody: 'The recorded path does not match the source file; migration is unavailable until the path is resolved',

      runBadge: 'Running', runTitle: 'Converting to MKV',
      step1: 'Copy streams into MKV', step2: 'Re-check the language tag', step3: 'Put the new file in place',
      stepOf: 'Step {n} of 3', orig: 'The original stays untouched until the re-check passes.',
      leave: 'You can leave this page. The job keeps running on the server — follow it in Activity ▸ Jobs.',

      okBadge: 'Verified', okTitle: 'Converted to MKV — language confirmed',
      okBody: 'The re-check found {lang} on {track} in {file}.',
      nowDisk: 'Now on disk', newIn: 'new · in place',
      origKept: 'original · kept in a hidden folder', keptUntil: 'Kept until you remove it.',
      othersH: 'Before other devices see it',
      others: 'Other devices still see the old file until Jellyfin has indexed the new one.',
      waitIdx: 'Waiting for Jellyfin to index',
      /* FR-8 — the same kind of secondary line as "Waiting for Jellyfin to index". Radarr has no
         API to set the link, so the repair is a person. */
      radarr: 'Radarr hasn’t taken the new file — re-link it in Radarr.',
      radarrBadge: 'Needs a re-link in Radarr',

      failBadge: 'Failed', failTitle: 'The conversion to MKV didn’t finish',
      failBody: 'Nothing was put in place. The original {file} is untouched and still there.',
      stoppedAt: 'Stopped at: {step}',

      unvBadge: 'Not applied', unvTitle: 'Migration failed — no language tag after the re-check',
      unvBody: 'The copy step reported success, but re-checking the new file found no language on {track}. The new file was not put in place, and the original {file} is untouched.',
      stepDone: 'finished', stepNoTag: 'no language tag on {track}', stepNot: 'not done',
      tryAgain: 'Try again', showLog: 'Show job log',

      offTitle: 'Connection to the server lost',
      offBody: 'The conversion runs on the server and isn’t affected. Status updates when the connection returns.',
      lastKnown: 'Last known: {step}', reconnecting: 'Reconnecting…', loading: 'Loading tracks…'
    },
    da: {
      unknown: 'Ukendt', fao: 'Færøsk (fao)', triage: 'Intet sprogmærke · i Triage',
      notKept: 'Ikke gemt — denne AVI kan ikke gemme et sprog.', requested: 'Ønsket: {lang}.',
      why: 'Hvorfor, og hvad du kan gøre',
      stillFail: 'Stadig Ukendt — konverteringen til MKV blev ikke færdig.',
      stillUnv: 'Stadig Ukendt — den nye fil havde intet sprogmærke.',
      blkRow: 'Ikke gemt — konvertering af filen til MKV er blokeret lige nu.',
      pthRow: 'Ikke gemt — migrering er ikke mulig for denne fil.',
      seeAbove: 'Se ovenfor', locked: 'Låst, mens konverteringen kører', fileScope: 'Fil · {file}',

      limBadge: 'AVI-begrænsning', limTitle: 'Denne AVI kan ikke beholde et lydsprog',
      limBody: 'AVI har ingen plads til et sprog for {track}, så {lang} kan ikke gemmes i denne fil. Konverteres filen til MKV, kan sproget gemmes.',
      streamCopy: 'Video og lyd kopieres uden genkodning',
      review: 'Gennemse konverteringen til MKV', keep: 'Behold som AVI',
      keepNote: 'Behold som AVI: sproget forbliver Ukendt, og sporet bliver i Triage.',

      pvTitle: 'Konvertér til MKV — hvad der ændres',
      container: 'Container', ext: 'Filendelse', streams: 'Spor', copied: 'kopieres uændret',
      langReq: 'Ønsket sprog', estimate: 'Skøn',
      estVal: 'ca. 2 minutter · 1,4 GB midlertidig plads',
      tempWhere: 'Midlertidige filer lægges i en skjult mappe ved siden af kilden, på samme disk.',
      estUnk: 'Intet skøn',
      estUnkBody: 'Tid og midlertidig plads kan ikke beregnes for denne fil.',
      before: 'Før start',
      beforeVal: 'Torrent-sikkerheden tjekkes igen, når du trykker Start migrering.',
      oldFile: 'Gammel fil',
      oldVal: '{file} flyttes ind i en skjult mappe ved siden af den nye fil og bliver liggende, indtil du fjerner den.',
      cont: 'Fortsæt til bekræftelse', cancel: 'Annuller',

      cfTitle: 'Konvertér {title} til MKV?', source: 'Kilde',
      dest: 'Destination', fname: 'Filnavn',
      srcStays: 'Bliver liggende, indtil den nye fil er bekræftet',
      start: 'Start migrering', close: 'Luk',

      blkBadge: 'Blokeret', blkTitle: 'Denne fil kan ikke konverteres lige nu',
      seedShort: 'Denne fil er knyttet til en aktiv torrent, der seeder',
      seedLong: 'Denne fil deles med en torrent, der stadig seeder (hårdt link), så den kan ikke ændres på stedet.',
      unkShort: 'Torrent-sikkerhedstjekket kunne ikke nås',
      unkBody: 'Derfor behandles filen som delt. Migrering er ikke mulig, før tjekket svarer.',
      takenShort: 'Der ligger allerede en fil med navnet {file}, så intet blev konverteret.',
      takenBody: 'Intet på disken blev rørt. Flyt eller fjern den fil, og tjek så igen.',
      roomShort: 'Ikke nok ledig plads på denne disk: {need} krævet, {free} ledigt.',
      roomBody: 'Konverteringen har brug for plads til en kopi mere af filen, mens den kører. Frigør noget plads, og tjek så igen.',
      checkAgain: 'Tjek igen', openSeeding: 'Åbn fanen Seeding',
      whyNeeded: 'Hvorfor det dukkede op: AVI kan ikke gemme et sprog for {track}, så {lang} kræver en konvertering til MKV.',

      pathBadge: 'Sti stemmer ikke', pathTitle: 'Migrering ikke mulig',
      pathBody: 'Den registrerede sti svarer ikke til kildefilen; migrering er ikke mulig, før stien er afklaret',

      runBadge: 'Kører', runTitle: 'Konverterer til MKV',
      step1: 'Kopiér spor over i MKV', step2: 'Kontrollér sprogmærket igen', step3: 'Sæt den nye fil på plads',
      stepOf: 'Trin {n} af 3', orig: 'Originalen forbliver urørt, indtil kontrollen er bestået.',
      leave: 'Du kan forlade siden. Jobbet kører videre på serveren — følg det under Aktivitet ▸ Jobs.',

      okBadge: 'Bekræftet', okTitle: 'Konverteret til MKV — sprog bekræftet',
      okBody: 'Kontrollen fandt {lang} på {track} i {file}.',
      nowDisk: 'Nu på disken', newIn: 'ny · på plads',
      origKept: 'original · gemt i en skjult mappe', keptUntil: 'Bliver liggende, indtil du fjerner den.',
      othersH: 'Før andre enheder ser den',
      others: 'Andre enheder ser stadig den gamle fil, indtil Jellyfin har indekseret den nye.',
      waitIdx: 'Venter på, at Jellyfin indekserer',
      radarr: 'Radarr har ikke taget den nye fil — kæd den sammen igen i Radarr.',
      radarrBadge: 'Skal kædes sammen igen i Radarr',

      failBadge: 'Mislykkedes', failTitle: 'Konverteringen til MKV blev ikke færdig',
      failBody: 'Intet blev sat på plads. Originalen {file} er urørt og ligger der stadig.',
      stoppedAt: 'Stoppede ved: {step}',

      unvBadge: 'Ikke anvendt', unvTitle: 'Migrering mislykkedes — intet sprogmærke efter kontrollen',
      unvBody: 'Kopieringen meldte succes, men kontrollen af den nye fil fandt intet sprog på {track}. Den nye fil blev ikke sat på plads, og originalen {file} er urørt.',
      stepDone: 'færdig', stepNoTag: 'intet sprogmærke på {track}', stepNot: 'ikke udført',
      tryAgain: 'Prøv igen', showLog: 'Vis joblog',

      offTitle: 'Forbindelsen til serveren er afbrudt',
      offBody: 'Konverteringen kører på serveren og påvirkes ikke. Status opdateres, når forbindelsen er tilbage.',
      lastKnown: 'Sidst kendt: {step}', reconnecting: 'Genopretter forbindelsen…', loading: 'Indlæser spor…'
    },
    fo: {
      unknown: 'Ókent', fao: 'Føroyskt (fao)', triage: 'Einki málmerki · í Triage',
      notKept: 'Ikki goymt — henda AVI-fílan kann ikki goyma eitt mál.', requested: 'Ynskt: {lang}.',
      why: 'Hví, og hvat tú kanst gera',
      stillFail: 'Enn Ókent — umbroytingin til MKV varð ikki liðug.',
      stillUnv: 'Enn Ókent — nýggja fílan hevði einki málmerki.',
      blkRow: 'Ikki goymt — at umbroyta fíluna til MKV er forðað júst nú.',
      pthRow: 'Ikki goymt — flyting er ikki møgulig fyri hesa fílu.',
      seeAbove: 'Sí omanfyri', locked: 'Læst, meðan umbroytingin gongur', fileScope: 'Fíla · {file}',

      limBadge: 'AVI-avmarking', limTitle: 'Henda AVI-fílan kann ikki varðveita eitt ljóðmál',
      limBody: 'AVI hevur einki pláss at goyma mál fyri {track}, so {lang} kann ikki goymast í hesi fíluni. Verður fílan umbroytt til MKV, kann málið goymast.',
      streamCopy: 'Video og ljóð verða avritað uttan umkoding',
      review: 'Kanna umbroytingina til MKV', keep: 'Varðveit sum AVI',
      keepNote: 'Varðveit sum AVI: málið verður verandi Ókent, og sporið verður verandi í Triage.',

      pvTitle: 'Umbroyt til MKV — hvat broytist',
      container: 'Fíluílat', ext: 'Fíluending', streams: 'Spor', copied: 'avritað óbroytt',
      langReq: 'Ynskt mál', estimate: 'Mett',
      estVal: 'umleið 2 minuttir · 1,4 GB av fyribilsplássi',
      tempWhere: 'Fyribilsfílur verða lagdar í eina fjalda mappu við síðuna av keldufíluni, á sama diski.',
      estUnk: 'Einki mett',
      estUnkBody: 'Tíð og fyribilspláss kunnu ikki roknast út fyri hesa fílu.',
      before: 'Áðrenn byrjað verður',
      beforeVal: 'Torrent-trygdin verður kannað aftur, tá ið tú trýstir á Byrja flyting.',
      oldFile: 'Gamla fílan',
      oldVal: '{file} verður flutt inn í eina fjalda mappu við síðuna av nýggju fíluni og verður verandi, til tú strikar hana.',
      cont: 'Halt fram til váttan', cancel: 'Angra',

      cfTitle: 'Umbroyt {title} til MKV?', source: 'Kelda',
      dest: 'Nýtt stað', fname: 'Fílunavn',
      srcStays: 'Verður liggjandi, til nýggja fílan er váttað',
      start: 'Byrja flyting', close: 'Lat aftur',

      blkBadge: 'Forðað', blkTitle: 'Henda fílan kann ikki umbroytast júst nú',
      seedShort: 'Henda fílan er knýtt at einum virknum torrenti, sum seedar',
      seedLong: 'Henda fílan er deild við ein torrent, sum enn seedar (hart leinki), so hon kann ikki broytast á staðnum.',
      unkShort: 'Torrent-trygdarkanningin kundi ikki náast',
      unkBody: 'Tí verður fílan viðgjørd sum deild. Flyting er ikki møgulig, fyrr enn kanningin svarar.',
      takenShort: 'Ein fíla við navninum {file} er longu har, so einki varð umbroytt.',
      takenBody: 'Einki á diskinum varð nert. Flyt ella strika ta fíluna, og kanna so aftur.',
      roomShort: 'Ikki nóg mikið leyst pláss á hesum diski: {need} kravt, {free} leyst.',
      roomBody: 'Umbroytingin hevur tørv á plássi til eitt avrit afturat av fíluni, meðan hon gongur. Ger pláss leyst, og kanna so aftur.',
      checkAgain: 'Kanna aftur', openSeeding: 'Opna Seeding-teigin',
      whyNeeded: 'Hví hetta kom fram: AVI kann ikki goyma mál fyri {track}, so {lang} krevur, at fílan verður umbroytt til MKV.',

      pathBadge: 'Leiðin samsvarar ikki', pathTitle: 'Flyting ikki møgulig',
      pathBody: 'Skrásetta leiðin samsvarar ikki við keldufíluna; flyting er ikki møgulig, fyrr enn leiðin er greidd',

      runBadge: 'Koyrir', runTitle: 'Umbroytir til MKV',
      step1: 'Avrita spor yvir í MKV', step2: 'Kanna málmerkið aftur', step3: 'Set nýggju fíluna á pláss',
      stepOf: 'Stig {n} av 3', orig: 'Upprunafílan verður óbroytt, til kanningin er góðkend.',
      leave: 'Tú kanst fara av síðuni. Arbeiðið heldur fram á ambætaranum — fylg við í Virksemi ▸ Arbeiði.',

      okBadge: 'Váttað', okTitle: 'Umbroytt til MKV — mál váttað',
      okBody: 'Kanningin fann {lang} á {track} í {file}.',
      nowDisk: 'Nú á diskinum', newIn: 'nýggj · á plássi',
      origKept: 'upprunalig · goymd í eini fjaldari mappu', keptUntil: 'Verður verandi, til tú strikar hana.',
      othersH: 'Áðrenn onnur tól síggja hana',
      others: 'Onnur tól síggja enn gomlu fíluna, til Jellyfin hevur skrásett ta nýggju.',
      waitIdx: 'Bíðar eftir, at Jellyfin skrásetur',
      radarr: 'Radarr hevur ikki tikið nýggju fíluna — knýt hana saman av nýggjum í Radarr.',
      radarrBadge: 'Skal knýtast saman av nýggjum í Radarr',

      failBadge: 'Miseydnaðist', failTitle: 'Umbroytingin til MKV varð ikki liðug',
      failBody: 'Einki varð sett á pláss. Upprunafílan {file} er óbroytt og liggur enn har.',
      stoppedAt: 'Steðgaði við: {step}',

      unvBadge: 'Ikki nýtt', unvTitle: 'Flyting miseydnaðist — einki málmerki eftir kanningina',
      unvBody: 'Avritingin boðaði frá, at tað eydnaðist, men kanningin av nýggju fíluni fann einki mál á {track}. Nýggja fílan varð ikki sett á pláss, og upprunafílan {file} er óbroytt.',
      stepDone: 'liðugt', stepNoTag: 'einki málmerki á {track}', stepNot: 'ikki gjørt',
      tryAgain: 'Royn aftur', showLog: 'Vís arbeiðsloggin',

      offTitle: 'Sambandið við ambætaran er slitið',
      offBody: 'Umbroytingin gongur á ambætaranum og verður ikki ávirkað. Støðan dagførist, tá ið sambandið kemur aftur.',
      lastKnown: 'Seinast kent: {step}', reconnecting: 'Knýtir samband aftur…', loading: 'Lesur spor inn…'
    }
  };

  /* ---- the one scene every frame is derived from. Stand-in data only. --------------------- */
  const SCENE = {
    dir: '/media/movies/Sample Film (2001)/',
    avi: 'Sample Film.avi',
    mkv: 'Sample Film.mkv',
    hidden: '.jellystructure/',
    title: 'Sample Film',
    id: 'demo-film-001',
    track: 'Audio 1',
    codec: 'MP3',
    size: '1.4 GB',
    need: '1.4 GB',
    free: '220 MB'
  };
  SCENE.src = SCENE.dir + SCENE.avi;
  SCENE.dst = SCENE.dir + SCENE.mkv;
  SCENE.kept = SCENE.dir + SCENE.hidden + SCENE.avi;

  const q = new URLSearchParams(location.search);
  if (q.get('container') !== 'avi') return;
  const mount = document.getElementById('cv-mount');
  if (!mount) return;

  const L = STR[q.get('lang')] || STR.en;
  const FORCED = q.get('cv') || '';
  const EPISODE = q.get('ep') === '1';

  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const fill = (s, over) => String(s)
    .replaceAll('{track}', (over && over.track) || SCENE.track)
    .replaceAll('{lang}', L.fao)
    .replaceAll('{file}', (over && over.file) || SCENE.avi)
    .replaceAll('{title}', SCENE.title)
    .replaceAll('{need}', SCENE.need)
    .replaceAll('{free}', SCENE.free)
    .replaceAll('{step}', (over && over.step) || '')
    .replaceAll('{n}', (over && over.n) || '');
  const scope = f => `<span class="cv-scope">${esc(fill(L.fileScope, { file: f || SCENE.avi }))}</span>`;
  const btn = (t, cls, act) => `<span class="btn${cls ? ' ' + cls : ''}" role="button" tabindex="0"${act ? ` data-a="${act}"` : ''}>${esc(t)}</span>`;

  /* three steps, shared by running / offline / failed-unverified (FR-7) */
  const steps = marks => `<ol class="cv-steps">${[L.step1, L.step2, L.step3].map((t, i) => {
    const m = marks[i] || '';
    const note = { dn: L.stepDone, x: fill(L.stepNoTag), no: L.stepNot }[m === 'x' ? 'x' : m];
    const em = (marks.notes && note) ? ` <em>· ${esc(note)}</em>` : '';
    return `<li class="${m === 'no' ? '' : m}"><i>${m === 'x' ? '✕' : i + 1}</i>${esc(t)}${em}</li>`;
  }).join('')}</ol>`;
  const running = n => { const m = [n > 1 ? 'dn' : 'on', n === 2 ? 'on' : n > 2 ? 'dn' : '', n === 3 ? 'on' : '']; return steps(m); };
  const unvSteps = () => { const m = ['dn', 'x', 'no']; m.notes = true; return steps(m); };

  /* ---- the notices (FR-3 … FR-9). One section per state, all on .cv-card. ----------------- */
  const nLimitation = () => `<section class="cv-card lim" role="region" aria-labelledby="cv-h3">
    <div class="cv-h"><span class="cv-ico info">i</span><span class="badge info">${esc(L.limBadge)}</span>${scope()}</div>
    <h3 id="cv-h3" tabindex="-1">${esc(L.limTitle)}</h3>
    <p>${esc(fill(L.limBody))} <b>${esc(L.streamCopy)}.</b></p>
    <div class="cv-acts">${btn(L.review, 'primary', 'review')}${btn(L.keep, 'ghost', 'keep')}</div>
    <p class="cv-m">${esc(L.keepNote)}</p></section>`;

  const nPreview = noEst => `<section class="cv-card lim" role="region" aria-labelledby="cv-h3">
    <div class="cv-h"><span class="cv-ico info">i</span><span class="badge info">${esc(L.limBadge)}</span>${scope()}</div>
    <h3 id="cv-h3" tabindex="-1">${esc(L.pvTitle)}</h3>
    <dl class="cv-pvt">
      <dt>${esc(L.container)}</dt><dd><span class="chip">AVI</span> → <span class="chip">MKV</span></dd>
      <dt>${esc(L.ext)}</dt><dd><span class="mono">.avi</span> → <span class="mono">.mkv</span></dd>
      <dt>${esc(L.streams)}</dt><dd>Video 1 · ${esc(L.copied)}<br>${esc(SCENE.track)} · ${esc(SCENE.codec)} · ${esc(L.copied)}<div class="cv-m">${esc(L.streamCopy)}</div></dd>
      <dt>${esc(L.langReq)}</dt><dd>${esc(SCENE.track)}: ${esc(L.unknown)} → <b>${esc(L.fao)}</b></dd>
      <dt>${esc(L.estimate)}</dt><dd>${noEst
        ? `<span class="cv-unk">${esc(L.estUnk)}</span><div class="cv-m">${esc(L.estUnkBody)}</div>`
        : esc(L.estVal)}<div class="cv-m">${esc(L.tempWhere)}</div></dd>
      <dt>${esc(L.before)}</dt><dd>${esc(L.beforeVal)}</dd>
      <dt>${esc(L.oldFile)}</dt><dd>${esc(fill(L.oldVal))}</dd>
    </dl>
    <div class="cv-acts">${btn(L.cont, 'primary', 'confirm')}${btn(L.cancel, 'ghost', 'close')}</div></section>`;

  /* FR-5 — one Blocked notice, five reasons. Seeding and unknown keep their own glyph; the two
     new ones (name taken, no room) reuse the shape with their own sentence, as the spec says. */
  const nBlocked = (glyph, short, body, extra) => `<section class="cv-card blk" role="region" aria-labelledby="cv-h3">
    <div class="cv-h"><span class="cv-ico warn">${glyph}</span><span class="badge warn">${esc(L.blkBadge)}</span>${scope()}</div>
    <h3 id="cv-h3" tabindex="-1">${esc(L.blkTitle)}</h3>
    <p><b>${esc(short)}</b> ${esc(body)}</p>
    <p class="cv-m">${esc(fill(L.whyNeeded))}</p>
    <div class="cv-acts">${btn(L.checkAgain, '', 'review')}${extra || ''}${btn(L.close, 'ghost', 'close')}</div></section>`;
  const nSeeding = () => nBlocked('⛨', L.seedShort + '.', L.seedLong, btn(L.openSeeding, 'ghost', 'seedtab'));
  const nUnknown = () => nBlocked('?', L.unkShort + '.', L.unkBody);
  const nTaken = () => nBlocked('!', fill(L.takenShort, { file: SCENE.mkv }), L.takenBody);
  const nNoRoom = () => nBlocked('!', fill(L.roomShort), L.roomBody);

  const nPath = () => `<section class="cv-card pth" role="region" aria-labelledby="cv-h3">
    <div class="cv-h"><span class="cv-ico pth">≠</span><span class="badge bad">${esc(L.pathBadge)}</span>${scope()}</div>
    <h3 id="cv-h3" tabindex="-1">${esc(L.pathTitle)}</h3>
    <p>${esc(L.pathBody)}.</p></section>`;

  const nRunning = (n, off) => `<section class="cv-card job${off ? ' off' : ''}" role="region" aria-labelledby="cv-h3" aria-live="polite">
    <div class="cv-h"><span class="cv-ico hi">↻</span><span class="badge info">${esc(L.runBadge)}</span>${scope()}</div>
    <h3 id="cv-h3" tabindex="-1">${esc(L.runTitle)}</h3>
    ${off ? `<div class="cv-offb"><b>${esc(L.offTitle)}</b><span>${esc(L.reconnecting)}</span></div>` : ''}
    <span class="cv-stp">${esc(off ? fill(L.lastKnown, { step: L.step2 }) : fill(L.stepOf, { n: n }))}</span>
    ${running(off ? 2 : n)}
    <p>${esc(L.orig)}</p>
    <p class="cv-m">${off ? esc(L.offBody) : esc(L.leave).replace('Activity ▸ Jobs', '<a href="activity.html">Activity ▸ Jobs</a>').replace('Aktivitet ▸ Jobs', '<a href="activity.html">Aktivitet ▸ Jobs</a>').replace('Virksemi ▸ Arbeiði', '<a href="activity.html">Virksemi ▸ Arbeiði</a>')}</p></section>`;

  /* FR-8 — verified. Two pending consequences are drawn as two secondary lines of one kind. */
  const nVerified = () => `<section class="cv-card okk" role="region" aria-labelledby="cv-h3">
    <div class="cv-h"><span class="cv-ico ok">✓</span><span class="badge ok">${esc(L.okBadge)}</span>${scope(SCENE.mkv)}</div>
    <h3 id="cv-h3" tabindex="-1">${esc(L.okTitle)}</h3>
    <p>${esc(fill(L.okBody, { file: SCENE.mkv }))}</p>
    <div class="cv-files"><span class="cv-lb">${esc(L.nowDisk)}</span>
      <div class="f"><span class="mono">${esc(SCENE.dst)}</span><span class="badge ok">${esc(L.newIn)}</span></div>
      <div class="f"><span class="mono">${esc(SCENE.kept)}</span><span class="badge">${esc(L.origKept)}</span></div>
    </div>
    <div class="cv-next">
      <div class="cv-pend"><span class="cv-lb">${esc(L.othersH)}</span><span>${esc(L.others)}</span><span><span class="badge">${esc(L.waitIdx)}</span></span></div>
      <div class="cv-pend"><span>${esc(L.radarr)}</span><span><span class="badge warn">${esc(L.radarrBadge)}</span></span></div>
    </div>
    <p class="cv-m">${esc(L.keptUntil)}</p></section>`;

  const nFailedTool = () => `<section class="cv-card fail" role="region" aria-labelledby="cv-h3">
    <div class="cv-h"><span class="cv-ico bad">!</span><span class="badge bad">${esc(L.failBadge)}</span>${scope()}</div>
    <h3 id="cv-h3" tabindex="-1">${esc(L.failTitle)}</h3>
    <p>${esc(fill(L.failBody))}</p>
    <span class="cv-stp">${esc(fill(L.stoppedAt, { step: L.step1 }))}</span>
    <div class="cv-acts">${btn(L.tryAgain, '', 'review')}<a class="btn ghost" href="activity.html">${esc(L.showLog)}</a></div></section>`;

  const nFailedUnverified = () => `<section class="cv-card fail" role="region" aria-labelledby="cv-h3">
    <div class="cv-h"><span class="cv-ico bad">✕</span><span class="badge bad">${esc(L.unvBadge)}</span>${scope()}</div>
    <h3 id="cv-h3" tabindex="-1">${esc(L.unvTitle)}</h3>
    <p>${esc(fill(L.unvBody))}</p>
    ${unvSteps()}
    <div class="cv-acts">${btn(L.tryAgain, '', 'review')}<a class="btn ghost" href="activity.html">${esc(L.showLog)}</a></div></section>`;

  /* ---- the confirmation dialog (FR-6). D3: the file name is text, not a field. ------------- */
  function dialogHtml(noEst) {
    return `<div class="modal cv-dlg" role="dialog" aria-modal="true" aria-labelledby="cv-dh">
      <h3 id="cv-dh" tabindex="-1">${esc(fill(L.cfTitle))}</h3>
      <div class="cv-cmp">
        <div class="cv-side"><span class="cv-lb">${esc(L.source)}</span><div class="cv-path">${esc(SCENE.src)}</div>
          <span class="tiny muted">AVI · ${esc(SCENE.size)}</span><span class="cv-keep">${esc(L.srcStays)}</span></div>
        <div class="cv-arr">→</div>
        <div class="cv-side"><span class="cv-lb">${esc(L.dest)}</span><div class="cv-path">${esc(SCENE.dst)}</div>
          <div class="cv-fld"><label id="cv-fnl">${esc(L.fname)}</label>
            <span class="cv-fname" aria-labelledby="cv-fnl">${esc(SCENE.mkv)}</span></div></div>
      </div>
      <div class="cv-sum">${esc(SCENE.track)} → <b>${esc(L.fao)}</b> · ${esc(L.streamCopy)} · ${esc(noEst ? L.estUnk : L.estVal)}</div>
      <div class="cv-acts">${btn(L.close, 'ghost', 'dlg-close')}${btn(L.start, 'primary', 'dlg-start')}</div></div>`;
  }

  /* ---- the track row (FR-2, FR-9) --------------------------------------------------------- */
  const ROW_NOTE = {
    limitation: () => `<span class="cv-ico s info">!</span><b>${esc(L.notKept)}</b><span>${esc(fill(L.requested))}</span>` +
      (EPISODE ? '' : `<a href="#cv-mount" data-a="why">${esc(L.why)} ↑</a>`),
    preview: () => ROW_NOTE.limitation(),
    'estimate-unknown': () => ROW_NOTE.limitation(),
    confirm: () => ROW_NOTE.limitation(),
    'blocked-seeding': () => `<span class="cv-ico s warn">!</span><b>${esc(L.blkRow)}</b><a href="#cv-mount" data-a="why">${esc(L.seeAbove)} ↑</a>`,
    'blocked-unknown': () => ROW_NOTE['blocked-seeding'](),
    'blocked-name-taken': () => ROW_NOTE['blocked-seeding'](),
    'blocked-no-room': () => ROW_NOTE['blocked-seeding'](),
    'blocked-path-mismatch': () => `<span class="cv-ico s pth">≠</span><b>${esc(L.pthRow)}</b><a href="#cv-mount" data-a="why">${esc(L.seeAbove)} ↑</a>`,
    running: () => `<b>${esc(L.locked)}</b><span>${esc(fill(L.requested))}</span>`,
    offline: () => ROW_NOTE.running(),
    'failed-tool': () => `<span class="cv-ico s bad">!</span><b>${esc(L.stillFail)}</b><a href="#cv-mount" data-a="why">${esc(L.seeAbove)} ↑</a>`,
    'failed-unverified': () => `<span class="cv-ico s bad">!</span><b>${esc(L.stillUnv)}</b><a href="#cv-mount" data-a="why">${esc(L.seeAbove)} ↑</a>`
  };
  const FLAGGED = ['limitation', 'preview', 'estimate-unknown', 'confirm', 'blocked-seeding', 'blocked-unknown',
    'blocked-name-taken', 'blocked-no-room', 'blocked-path-mismatch', 'failed-tool', 'failed-unverified'];

  function trackRow(state) {
    const done = state === 'verified';
    const note = ROW_NOTE[state] && ROW_NOTE[state]();
    const cls = 'trk' + (FLAGGED.includes(state) ? ' flag' : '') + (state === 'running' || state === 'offline' ? ' lock' : '');
    return `<div class="${cls}"><div class="trk-main">
        <span class="grip">⋮⋮</span><span class="pos">1</span>
        <span class="tiny muted" style="font-weight:700;letter-spacing:.06em;">AUDIO</span>
        <div class="trk-mid"><b>${esc(SCENE.track)}</b>
          <span class="chip">${esc(done ? L.fao : L.unknown)} ⌄</span>
          <span class="chip mono">${esc(SCENE.codec)}</span>
          ${done ? '' : `<span class="badge warn">${esc(L.triage)}</span>`}
        </div>
        <div class="trk-right"><span style="color:var(--warn)" title="default">★</span></div>
      </div>${note ? `<div class="trk-sub">${note}</div>` : ''}</div>`;
  }

  function skeleton() {
    return `<div class="trk"><div class="trk-main">
        <span class="grip">⋮⋮</span><span class="pos"></span><span></span>
        <div class="trk-mid"><span class="cv-skl" style="width:62%"></span></div><div class="trk-right"></div>
      </div><div class="trk-sub" role="status">${esc(L.loading)}</div></div>`;
  }

  /* ---- state table ------------------------------------------------------------------------ */
  const V = {
    'detail-default': () => '',
    'detail-loading': () => '',
    limitation: nLimitation,
    preview: () => nPreview(false),
    'estimate-unknown': () => nPreview(true),
    confirm: () => nPreview(false),
    'blocked-seeding': nSeeding,
    'blocked-unknown': nUnknown,
    'blocked-name-taken': nTaken,
    'blocked-no-room': nNoRoom,
    'blocked-path-mismatch': nPath,
    running: () => nRunning(2, false),
    offline: () => nRunning(2, true),
    verified: nVerified,
    'failed-tool': nFailedTool,
    'failed-unverified': nFailedUnverified
  };

  /* ---- paint ------------------------------------------------------------------------------ */
  let state = FORCED || 'limitation';
  let scrim = null;

  function closeDialog(returnFocus) {
    if (scrim) { scrim.remove(); scrim = null; }
    if (returnFocus) { const c = mount.querySelector('[data-a="confirm"]'); if (c) c.focus(); }
  }

  function openDialog() {
    scrim = document.createElement('div');
    scrim.className = 'modal-back cv-back open';
    scrim.innerHTML = dialogHtml(state === 'estimate-unknown');
    document.body.appendChild(scrim);
    scrim.querySelector('h3').focus();
    scrim.addEventListener('click', e => {
      const a = e.target.closest('[data-a]');
      if (e.target === scrim) return closeDialog(true);
      if (!a) return;
      if (a.dataset.a === 'dlg-start') { closeDialog(false); state = 'running'; paint(true); run(); }
      else closeDialog(true);
    });
    scrim.addEventListener('keydown', e => {
      if (e.key === 'Escape') { e.stopPropagation(); closeDialog(true); }
      if ((e.key === 'Enter' || e.key === ' ') && e.target.dataset.a) { e.preventDefault(); e.target.click(); }
      if (e.key === 'Tab') {
        const f = [...scrim.querySelectorAll('[tabindex="0"]')];
        const i = f.indexOf(document.activeElement);
        if (e.shiftKey && i <= 0) { e.preventDefault(); f[f.length - 1].focus(); }
        else if (!e.shiftKey && i === f.length - 1) { e.preventDefault(); f[0].focus(); }
      }
    });
  }

  function run() {
    let n = 1;
    const tick = setInterval(() => {
      n += 1;
      if (n > 3) { clearInterval(tick); state = 'verified'; paint(true); return; }
      mount.innerHTML = nRunning(n, false);
    }, 2200);
  }

  function paint(focus) {
    /* FR-2 — an AVI EPISODE gets the refusal sentence and nothing else. The conversion is
       films-only, so there is no notice to link to and none is drawn: offering "Review the
       conversion to MKV" on a row whose conversion will never exist is worse than the link's
       absence, which is the whole reason FR-2 drops the link. */
    mount.innerHTML = EPISODE ? '' : (V[state] ? V[state]() : '');
    const list = document.getElementById('cv-track-list');
    if (list) list.innerHTML = state === 'detail-loading' ? skeleton() : trackRow(state);
    const file = document.getElementById('trk-file');
    if (file) file.textContent = state === 'verified' ? SCENE.mkv : SCENE.avi;
    if (focus) { const h = mount.querySelector('h3'); if (h) h.focus(); }
  }

  mount.addEventListener('click', e => {
    const a = e.target.closest('[data-a]');
    if (!a) return;
    e.preventDefault();
    if (a.dataset.a === 'review') { state = state === 'estimate-unknown' ? 'estimate-unknown' : 'preview'; paint(true); }
    else if (a.dataset.a === 'confirm') openDialog();
    else if (a.dataset.a === 'close' || a.dataset.a === 'keep') { state = 'limitation'; paint(false); }
    else if (a.dataset.a === 'seedtab') { const t = document.querySelector('[data-tab="seeding"]'); if (t) t.click(); }
  });
  mount.addEventListener('keydown', e => {
    if ((e.key === 'Enter' || e.key === ' ') && e.target.dataset.a) { e.preventDefault(); e.target.click(); }
  });

  /* ---- the AVI stand-in: hide the shipped MKV-specific chrome, draw Sample Film's one track - */
  ['cover-video-alert', 'cascade-alert', 'mkv-banner', 'sidecar-card', 'bazarr-card',
    'audio-flags', 'sub-flags', 'trk-explain', 'trk-writethru', 'guard-chip'].forEach(id => {
    const el = document.getElementById(id); if (el) el.style.display = 'none';
  });
  /* The shipped mockup also carries three unrelated page-level banners for its own stand-in title
     (locked fields, drift, Jellyfin behind). They have nothing to do with a container conversion
     and push the notice a screenful down, so the AVI stand-in drops them too. */
  ['lock-banner', 'drift-banner', 'behind-banner'].forEach(id => {
    const el = document.getElementById(id); if (el) el.style.display = 'none';
  });
  const realList = document.getElementById('track-list');
  if (realList) {
    realList.style.display = 'none';
    realList.insertAdjacentHTML('afterend', '<div id="cv-track-list"></div>');
  }
  document.title = 'Jellystructure — ' + SCENE.title;
  const crumb = document.querySelector('.backrow .crumb, .crumb');
  if (crumb) crumb.innerHTML = '<a href="library.html">Movies</a> / ' + esc(SCENE.title);
  const h1 = document.querySelector('.pagebar h1, h1');
  if (h1) h1.innerHTML = esc(SCENE.title) + ' <span class="muted">(2001)</span>';

  paint(false);
})();
