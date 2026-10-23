/* ts-data.js — the round's one data file.

   Round `2026-10-23-tv-signin`. PROCESS.md: "Derive every number in every picture from one
   array." Every string, every stand-in, every measurement in this round is here, so "do these
   pictures agree with each other?" is a question a reviewer can check rather than trust.

   THE STAND-INS ARE THE BRIEF'S, EXACTLY, and nothing else appears:
     TVs      Living room TV · Bedroom TV
     people   Anna (the approver) · Jonas (the second profile)
     code     K7P 4QX        — the alphabet has no 0, O, 1 or I
     QR       https://ravilo.example.net/tv?c=K7P4QX
     host     ravilo.example.net
     SSO      Harbour ID · Fjord Login

   LANGUAGES. The shipped i18n/*.json wins wherever a string already exists (SKILL-house.md), so
   every reused key is marked `shipped` below with the key it comes from, and its three values are
   the shipped values verbatim. The round's NEW strings are marked `new` and their Danish and
   Faroese are DRAFTS. A disagreement between a draft and the shipped table is information for
   Bárður, not something to fix in the drawing.

   Chromium has no Faroese locale, so nothing here goes through toLocaleString — the three
   languages are tabled.
*/
window.TS = (function () {
  'use strict';

  /* =============================================================================================
     STAND-INS — the brief's, exactly
     ============================================================================================= */
  var TV = 'Living room TV';
  var TV2 = 'Bedroom TV';
  var ANNA = 'Anna';
  var JONAS = 'Jonas';
  var CODE = 'K7P4QX';                 /* stored without the space; every view groups 3 + 3 */
  var HOST = 'ravilo.example.net';
  var LINK = 'https://' + HOST + '/tv?c=' + CODE;
  /* The address the four browser pages of surface 4 are served from. FR-10 puts them on a new
     open route on the BACKEND's public origin — `<public_url>/sso/OID/redirect/<P>` — which is
     not necessarily the same host as `ravilo_web_url`. THE BRIEF NAMES ONLY RAVILO WEB'S HOST, so
     rather than invent a second one these frames reuse it and the report raises it as question
     Q4. Nothing is invented here; one stand-in is doing two jobs, visibly.
     `HarbourID` is the provider's path segment: the spec makes `<P>` the button's text with no
     spaces in it. The path is jellystructure's own route and names nothing of Jellyfin's. */
  var SSO_RETURN = 'https://' + HOST + '/sso/OID/redirect/HarbourID';
  /* The desktop app's own loopback hand-back (FR-11, RFC 8252 §7.3). A port is a stand-in. */
  var DESK_RETURN = 'http://127.0.0.1:7845/signin';
  var SSO1 = 'Harbour ID';
  var SSO2 = 'Fjord Login';

  /* The code, grouped. ONE function, so no frame can group it differently from another —
     FR-5 says "grouped 3 + 3" and this is where that lives. */
  function grouped(code) { return code.slice(0, 3) + ' ' + code.slice(3); }

  /* =============================================================================================
     MEASUREMENTS — the numbers the pictures are built from
     ============================================================================================= */
  var M = {
    /* The TV stage is the shipped one: 1920x1080, fixed, scaled to the viewport. Measured at
       1920 (SKILL-house.md), and the gutter is the shipped `raviloHPad`-sized 64 px the appbar
       and every rail already use. */
    tvStage: [1920, 1080],
    tvGutter: 64,

    /* The QR, and the arithmetic behind its size, because "scans from about three metres" is a
       measurement and not a feeling.
         29 modules (version 3) + 4 + 4 quiet  = 37 modules
         12 px per module                      = 444 px square on the 1920 stage
       A 55-inch set is ~122 cm wide, so 1920 px maps to ~0.64 mm per px and the QR is ~28 cm
       across. The usual field rule for a phone camera is a QR side of about a tenth of the
       reading distance, which at 3 m wants ~30 cm. 444 px is that, with the quiet zone inside it.
       A 43-inch set gives ~22 cm, which is the rule's limit — noted in the report as the
       smallest screen this size is honest on. */
    qrModule: 12,
    qrModules: 37,
    qrSide: 444,

    /* DIRECTION B CANNOT HOLD THAT, and the number is the argument. Stacked down the middle of
       1080 lines, FR-5's five blocks — title, code, instruction, address, TV name — plus the
       92 px brand row, the 168 px footer and 56 px of overscan margin leave 764 lines for the
       stack. The other blocks need about 460 of them at A's type sizes, so the QR gets ~300 and
       the code comes down to 80 px with it. 300 px is ~19 cm on a 55-inch set: the 3-metre rule
       wants ~30 cm, so B's QR is a 2-metre code, not a 3-metre one. Drawn, measured, reported —
       not quietly scaled until it fit. */
    qrSideB: 300,
    codePxB: 80,
    /* With no QR at all the code is the only thing left to read across the room, so it grows. */
    codePxNoQr: 136,

    /* The code's own type. The prior art is "Play on a TV - Directions.html" §"The code, and why
       it looks like this": 96 px glyphs in 1920 space, tabular figures, and 218's alphabet
       already excludes the ambiguous characters. It reads from the sofa, so it is reused — and
       raised to 112 px, because here the code is the SCREEN's subject rather than a line in the
       corner of an idle receiver. Both numbers are drawn: see `tv-a-code-96`. */
    codePx: 112,
    codePxPriorArt: 96,
    codeTrack: 14,            /* letter-spacing, px — 3+3 grouping plus this is the whole device */

    /* The phone and the desktop, the shipped frames. */
    phone: [412, 915],        /* Pixel 9 portrait, the default */
    phoneIos: [393, 852],     /* iPhone 16, same screens in the other bezel */
    webNarrow: [390, 844],    /* Ravilo Web in a phone-width browser — surface 2(c) */
    mac: [1440, 900],
    webWide: [1440, 900],

    /* The browser pages of surface 4 are served as SINGLE pages, so they are measured at the two
       widths the brief names and nothing in them may reach for a second file. */
    pagePhone: [390, 844],
    pageDesk: [1440, 900]
  };

  /* =============================================================================================
     STRINGS
     Each row: key, source (`shipped` + the i18n key, or `new`), then en / da / fo.
     ============================================================================================= */
  var S = {

    /* ---- reused from the shipped table, verbatim. These are NOT the round's to change. ---- */
    'login.title':            { src: 'shipped login.title',       en: 'Sign in', da: 'Log ind', fo: 'Rita inn' },
    'login.username':         { src: 'shipped login.username',    en: 'Username', da: 'Brugernavn', fo: 'Brúkaranavn' },
    'login.password':         { src: 'shipped login.password',    en: 'Password', da: 'Kodeord', fo: 'Loyniorð' },
    'login.sign_in':          { src: 'shipped login.sign_in',     en: 'Sign in', da: 'Log ind', fo: 'Rita inn' },
    'login.subtitle':         { src: 'shipped login.subtitle',    en: 'Use your username and password to sign in on this device.', da: 'Brug dit brugernavn og kodeord for at logge ind på denne enhed.', fo: 'Brúka títt brúkaranavn og loyniorð fyri at rita inn á hesa eindina.' },
    'login.error_unreachable':{ src: 'shipped login.error_unreachable', en: "Couldn't reach the server — try again", da: 'Kunne ikke nå serveren — prøv igen', fo: 'Kundi ikki ná ambætaranum — royn aftur' },
    'login.change_server':    { src: 'shipped login.change_server', en: 'Wrong server?', da: 'Forkert server?', fo: 'Skeivur ambætari?' },
    'action.cancel':          { src: 'shipped action.cancel',     en: 'Cancel', da: 'Annuller', fo: 'Angra' },
    'action.back':            { src: 'shipped action.back',       en: 'Back', da: 'Tilbage', fo: 'Aftur' },
    'action.retry':           { src: 'shipped action.retry',      en: 'Retry', da: 'Prøv igen', fo: 'Royn aftur' },
    'profile.add_user':       { src: 'shipped profile.add_user',  en: 'Add user', da: 'Tilføj bruger', fo: 'Legg til brúkara' },
    'profile.sign_out':       { src: 'shipped profile.sign_out',  en: 'Sign out', da: 'Log ud', fo: 'Rita út' },
    /* The desktop window's nav. SCAFFOLDING — the round proposes nothing about it — but it is in
       every Mac picture, so it is drawn with the SHIPPED labels and the shipped translations.
       Two things the first render got wrong by inventing them instead: the second item is
       `nav.movies` = "Movies", not "Films", and in a Danish or Faroese run the whole nav stayed
       in English beside a translated card, which reads as a defect in the design rather than in
       the scaffolding. */
    'nav.home':               { src: 'shipped nav.home',          en: 'Home', da: 'Hjem', fo: 'Heim' },
    'nav.movies':             { src: 'shipped nav.movies',        en: 'Movies', da: 'Film', fo: 'Filmar' },
    'nav.series':             { src: 'shipped nav.series',        en: 'Series', da: 'Serier', fo: 'Seriur' },
    'mode.music':             { src: 'shipped mode.music',        en: 'Music', da: 'Musik', fo: 'Tónleikur' },
    'mnav.now_short':         { src: 'shipped mnav.now_short',    en: 'Playing', da: 'Afspiller', fo: 'Spælir' },
    'profile.switch':         { src: 'shipped profile.switch',    en: 'Switch profile', da: 'Skift profil', fo: 'Skift vanga' },

    /* ---- surface 1: the TV's sign-in screen (FR-5) ---- */
    'tvsignin.title':         { src: 'new', en: 'Sign in on this TV', da: 'Log ind på dette TV', fo: 'Rita inn á hesum sjónvarpinum' },
    /* THE LONGEST LINE IN THE ROUND. FR-5 asks for one instruction naming both ways in. Drawn in
       all three languages and measured in the report: 102 characters in English and Danish, 118
       in Faroese — 16 % longer, and what sets the right column's width. Two lines in all three. */
    'tvsignin.how':           { src: 'new', en: 'Scan the code with your phone’s camera, or open Ravilo on a phone or computer and choose Sign in a TV.', da: 'Skan koden med telefonens kamera, eller åbn Ravilo på en telefon eller computer og vælg Log et TV ind.', fo: 'Skanna koduna við kameranum á telefonini, ella lat Ravilo upp á eini telefon ella teldu og vel Rita eitt sjónvarp inn.' },
    /* State (c): no QR, so no camera wording either — the sentence is not the same sentence with
       a clause removed, it starts at the one way in that is left. */
    'tvsignin.how_noqr':      { src: 'new', en: 'Open Ravilo on a phone or computer and choose Sign in a TV.', da: 'Åbn Ravilo på en telefon eller computer og vælg Log et TV ind.', fo: 'Lat Ravilo upp á eini telefon ella teldu og vel Rita eitt sjónvarp inn.' },
    'tvsignin.code_label':    { src: 'new', en: 'Your code', da: 'Din kode', fo: 'Koda tín' },
    'tvsignin.this_tv':       { src: 'new', en: 'This TV', da: 'Dette TV', fo: 'Hetta sjónvarpið' },
    /* Decision 2: the web address printed for someone with a laptop and no camera. Shown only
       when the server knows the address, which is the same condition the QR has. */
    'tvsignin.or_go_to':      { src: 'new', en: 'Or go to', da: 'Eller gå til', fo: 'Ella far til' },
    /* State (b). The spec fixes these words. The approver may be at a computer and the server
       sends only a name, so "'s phone" can be wrong — raised as question Q1 in the report. */
    'tvsignin.approving':     { src: 'new', en: 'Approving on {name}’s phone…', da: 'Godkendes på {name}s telefon…', fo: 'Verður góðkent á telefonini hjá {name}…' },
    'tvsignin.password_instead': { src: 'new', en: 'Sign in with a password instead', da: 'Log ind med kodeord i stedet', fo: 'Rita inn við loyniorði í staðin' },
    /* State (d), Add user. The only line that differs from state (a) — see the report. */
    'tvsignin.back_to_profiles': { src: 'new', en: 'Back returns to the profiles', da: 'Tilbage fører til profilerne', fo: 'Aftur fer til vangarnar' },

    /* ---- surface 2: Sign in a TV on the phone, the web and the desktop (FR-6) ---- */
    'tvcode.row':             { src: 'new', en: 'Sign in a TV', da: 'Log et TV ind', fo: 'Rita eitt sjónvarp inn' },
    'tvcode.body':            { src: 'new', en: 'Type the six characters shown on the TV.', da: 'Indtast de seks tegn, der står på TV’et.', fo: 'Skriva teir seks teknini, sum standa á sjónvarpinum.' },
    'tvcode.field':           { src: 'new', en: 'Code from the TV', da: 'Kode fra TV’et', fo: 'Koda frá sjónvarpinum' },
    'tvcode.continue':        { src: 'new', en: 'Continue', da: 'Fortsæt', fo: 'Hald fram' },
    /* The confirm screen's question is the spec's sentence. */
    'tvcode.confirm':         { src: 'new', en: 'Sign in on {tv} as {name}?', da: 'Log ind på {tv} som {name}?', fo: 'Rita inn á {tv} sum {name}?' },
    /* A supporting line, PROPOSED (question Q2) — the spec gives the question and the two
       buttons and nothing under them. It says only what is already true of a profile. */
    'tvcode.confirm_body':    { src: 'new (proposed)', en: 'Your list, your languages and where you got to come with you.', da: 'Din liste, dine sprog og hvor du er kommet til følger med.', fo: 'Listin tín, málini tín og hvar tú ert komin fylgja við.' },
    'tvcode.not_you':         { src: 'new', en: 'Not you? Switch', da: 'Ikke dig? Skift', fo: 'Ikki tú? Skift' },
    /* Outcomes. The spec gives the first two words for word; the other three are PROPOSED and
       follow the copy rule — what the viewer can do, no component, no status code. */
    'tvcode.done':            { src: 'new', en: '{tv} is signed in', da: '{tv} er logget ind', fo: '{tv} er innritað' },
    'tvcode.done_body':       { src: 'new (proposed)', en: 'It’s ready to watch.', da: 'Det er klar til at se.', fo: 'Tað er klárt at hyggja.' },
    'tvcode.expired':         { src: 'new', en: 'That code has expired — get a new one on the TV', da: 'Koden er udløbet — hent en ny på TV’et', fo: 'Kodan er útgingin — fá eina nýggja á sjónvarpinum' },
    'tvcode.used':            { src: 'new (proposed)', en: 'That code has already signed a TV in — get a new one on the TV', da: 'Koden har allerede logget et TV ind — hent en ny på TV’et', fo: 'Kodan hevur longu ritað eitt sjónvarp inn — fá eina nýggja á sjónvarpinum' },
    'tvcode.unavailable':     { src: 'new (proposed)', en: 'Signing a TV in isn’t available right now — try again in a few minutes', da: 'Det er ikke muligt at logge et TV ind lige nu — prøv igen om et par minutter', fo: 'Tað ber ikki til at rita eitt sjónvarp inn nú — royn aftur um eitt par minuttir' },
    /* "Could not reach the server" is NOT a new string: the shipped login key says it already. */
    /* NOT "Get a new code". The TV fetches the new code by itself (FR-5: "when the code expires
       it fetches a new one without saying so"), so this button cannot get one — all it does is
       go back to the field so the viewer can type whatever the TV is showing now. A control says
       what happens when you press it. The first render of `mac-out-expired` reads as a button
       that mints a code, under a sentence that just said to go and look at the TV. */
    'tvcode.get_new':         { src: 'new', en: 'Enter a new code', da: 'Indtast en ny kode', fo: 'Skriva eina nýggja kodu' },
    'tvcode.done_cta':        { src: 'new', en: 'Done', da: 'Færdig', fo: 'Liðugt' },

    /* ---- surface 3: the Sign in with ‹name› button (FR-12) ---- */
    'sso.button':             { src: 'new', en: 'Sign in with {name}', da: 'Log ind med {name}', fo: 'Rita inn við {name}' },
    'sso.or':                 { src: 'new', en: 'or', da: 'eller', fo: 'ella' },
    'sso.waiting':            { src: 'new', en: 'Finish signing in in your browser', da: 'Afslut login i din browser', fo: 'Enda innritanina í kagaranum' },

    /* ---- surface 4: the pages the server renders in the browser (FR-10) ---- */
    'ssopage.ok':             { src: 'new', en: 'You’re signed in — go back to Ravilo', da: 'Du er logget ind — gå tilbage til Ravilo', fo: 'Tú ert innritað/ur — far aftur til Ravilo' },
    'ssopage.ok_body':        { src: 'new (proposed)', en: 'Taking you back…', da: 'Fører dig tilbage…', fo: 'Fer aftur við tær…' },
    'ssopage.not_allowed':    { src: 'new', en: 'This account can’t use Ravilo — ask the person who runs it', da: 'Denne konto kan ikke bruge Ravilo — spørg den, der står for den', fo: 'Hesin kontoin kann ikki brúka Ravilo — spyr tann, sum stýrir honum' },
    'ssopage.too_long':       { src: 'new', en: 'That took too long — try again', da: 'Det tog for lang tid — prøv igen', fo: 'Tað tók ov langa tíð — royn aftur' },
    'ssopage.cancelled':      { src: 'new (proposed)', en: 'You didn’t finish signing in — try again when you’re ready', da: 'Du afsluttede ikke login — prøv igen, når du er klar', fo: 'Tú endaðir ikki innritanina — royn aftur, tá tú ert klár/ur' },
    'ssopage.unavailable':    { src: 'new (proposed)', en: 'Signing in isn’t available right now — try again in a few minutes', da: 'Login er ikke muligt lige nu — prøv igen om et par minutter', fo: 'Tað ber ikki til at rita inn nú — royn aftur um eitt par minuttir' },
    'ssopage.close_tab':      { src: 'new', en: 'You can close this tab', da: 'Du kan lukke denne fane', fo: 'Tú kanst lata hetta tabblaðið aftur' },
    'ssopage.open_ravilo':    { src: 'new', en: 'Open Ravilo', da: 'Åbn Ravilo', fo: 'Lat Ravilo upp' },

    /* ---- the admin's sign-in page. The operator's page, so it MAY name Jellyfin (FR-15), and
       its existing copy does. Only the button is new here. ---- */
    'admin.signin.title':     { src: 'shipped, in Login.kt', en: 'Sign in', da: '—', fo: '—' },
    'admin.signin.sub':       { src: 'shipped, in Login.kt', en: 'Use your <b>Jellyfin administrator</b> account. Only admin accounts can manage metadata.', da: '—', fo: '—' },
    'admin.signin.user':      { src: 'shipped, in Login.kt', en: 'Jellyfin username', da: '—', fo: '—' },
    'admin.signin.foot':      { src: 'shipped, in Login.kt', en: 'Authenticates via Jellyfin <span class="mono">/Users/AuthenticateByName</span>. Session lasts 7 days.', da: '—', fo: '—' }
  };

  /* The profile page's existing rows, so the `Sign in a TV` row is drawn in its real company and
     not on an empty page. Read off ravilo-phone.js's `renderProfile`, in its order. */
  var ACCOUNT_ROWS = [
    { icon: 'lang', label: 'App language', sub: 'English' },
    { icon: 'note', label: 'Music' },
    { icon: 'life', label: 'Help' },
    { icon: 'key', label: 'Change password' },
    /* FR-6 puts the new entry "beside the account rows R304 placed". Drawn here, below
       Change password and above Settings — the reasoning is in the report. */
    { icon: 'tv', label: 'tvcode.row', isNew: true },
    { icon: 'gear', label: 'Settings' }
  ];

  /* The six browser pages of surface 4, as one table: the key, the tone, and which call to
     action the page carries. `hand` is the page that hands back by itself (FR-11). */
  var PAGES = [
    { id: 'ok',          key: 'ssopage.ok',          body: 'ssopage.ok_body', tone: 'ok',   cta: null,                 hand: true },
    { id: 'not-allowed', key: 'ssopage.not_allowed', body: null,              tone: 'bad',  cta: null,                 hand: false },
    { id: 'too-long',    key: 'ssopage.too_long',    body: null,              tone: 'warn', cta: 'ssopage.open_ravilo', hand: false },
    { id: 'cancelled',   key: 'ssopage.cancelled',   body: null,              tone: 'warn', cta: 'ssopage.open_ravilo', hand: false },
    { id: 'unavailable', key: 'ssopage.unavailable', body: null,              tone: 'warn', cta: 'ssopage.open_ravilo', hand: false },
    { id: 'close-tab',   key: 'ssopage.close_tab',   body: null,              tone: 'ok',   cta: null,                 hand: false }
  ];

  /* The five outcomes of surface 2(f), as one table, so the phone and the Mac draw the same set. */
  var OUTCOMES = [
    { id: 'done',        key: 'tvcode.done',        body: 'tvcode.done_body', tone: 'ok',   cta: 'tvcode.done_cta', tv: TV },
    { id: 'expired',     key: 'tvcode.expired',     body: null,               tone: 'warn', cta: 'tvcode.get_new',  tv: TV },
    { id: 'used',        key: 'tvcode.used',        body: null,               tone: 'warn', cta: 'tvcode.get_new',  tv: TV },
    { id: 'unavailable', key: 'tvcode.unavailable', body: null,               tone: 'warn', cta: 'action.retry',    tv: TV },
    { id: 'offline',     key: 'login.error_unreachable', body: null,          tone: 'bad',  cta: 'action.retry',    tv: TV }
  ];

  return {
    TV: TV, TV2: TV2, ANNA: ANNA, JONAS: JONAS, CODE: CODE, HOST: HOST, LINK: LINK,
    SSO_RETURN: SSO_RETURN, DESK_RETURN: DESK_RETURN,
    SSO1: SSO1, SSO2: SSO2, grouped: grouped,
    M: M, S: S, ACCOUNT_ROWS: ACCOUNT_ROWS, PAGES: PAGES, OUTCOMES: OUTCOMES
  };
})();
