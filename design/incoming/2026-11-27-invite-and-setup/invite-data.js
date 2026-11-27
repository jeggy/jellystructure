/* invite-data.js — the ONE array this round renders from.
   Round slug `invite-and-setup`, 2026-11-27. Three surfaces: the admin invite sheet, the public
   `/join` landing page, and the server-setup screen pre-filled from an invite.

   PROCESS.md: "Derive every number in every picture from one array", so "do these pictures agree
   with each other?" is something a reviewer can check rather than trust. invite-kit.js invents no
   string, no host, no width and no colour — every one of them is read from here.

   WHERE EACH STRING COMES FROM, because three sources disagree and the disagreements are the
   report's findings rather than something to quietly pick between:

     SHIPPED   read out of i18n/en.json, da.json and fo.json on 2026-11-27. SKILL-house: the
               shipped table wins wherever a string already exists.
     SPEC      specs/ravilo/requirements/an-invite-link-sets-up-a-new-viewers-app.md, FR-8's own
               table (the two new setup keys) and FR-2/FR-3's prose (the admin sheet and the page,
               which are English-in-Kotlin and not in i18n/ at all).
     BRIEF     the words Bárður's brief names for the landing page.

   The two live disagreements, both drawn and both in REPORT.md:
     1. `setup.server_prompt` SHIPS as "Enter your jellystructure server address". A viewer never
        reads the word jellystructure (owner, 2026-10-23), and DEB-5836 FR-3 replaces it with
        "Enter your Ravilo server address". The constitution's invariant outranks SKILL-house's
        i18n rule, so the Ravilo wording is what is DRAWN; the shipped string is carried below so
        the difference is visible in the data rather than only in prose.
     2. The landing page's three part labels are worded one way in the BRIEF and another in SPEC
        FR-3, and the two put the parts in a different ORDER. Both wordings are below; the frames
        draw the brief's, and the order is the round's question.
*/
(function () {
  'use strict';

  /* =================================================================================================
     THE STAND-INS — the brief's STAND-IN section, exactly, and nothing else invented.
     ================================================================================================= */
  var STAND = {
    host:    'jelly.example.com',                 /* the server's public host, what a TV types   */
    link:    'https://jelly.example.com/join',    /* FR-1's `url` = public_url + "/join"          */
    admin:   'admin',                             /* the admin signed in to the console           */
    webBase: 'https://ravilo.example.com',        /* the owner's 2026-11-27 amendment stand-in …  */
    webJoin: 'https://ravilo.example.com/join',   /* … and the Android button's whole href        */
    example: 'ravilo.example.com',                /* FR-7's new `setup.server_example`            */

    /* NOT IN THE BRIEF, and flagged in REPORT.md rather than passed off as a stand-in. SKILL-house
       requires the longest string to be drawn and the brief names one host, which is short. Both
       values below are deliberately synthetic — they read as placeholders and could not be a
       household's name.

       THERE ARE TWO OF THEM, AND THE REASON IS A FINDING. A QR at version 3-M holds 42 bytes.
       `https://` + host + `/join` is 13 bytes of fixed text, so the admin sheet's code can carry a
       host of at most 29 characters before the encoder has to move to version 4 — and the first
       render of this round FAILED on exactly that, because the 37-character host below makes a
       50-byte link. So:
         longHost    37 chars — the setup field, which has no QR in it, and is the real longest case
         qrLongHost  27 chars — the admin sheet, the longest host its code can still encode
       REPORT.md carries this to Bárður as a requirement on FR-2's shipped `QrEncoder`: the version
       has to be chosen from the link's length, not fixed. */
    longHost:   'a-much-longer-server-name.example.com',
    longLink:   'https://a-much-longer-server-name.example.com/join',
    qrLongHost: 'a-longer-server.example.com',
    qrLongLink: 'https://a-longer-server.example.com/join'
  };

  /* =================================================================================================
     THE SETUP SCREEN'S STRINGS
     ================================================================================================= */

  /* ---- what already ships (i18n/*.json, 2026-11-27) ---- */
  var SHIPPED = {
    'setup.server_label':      { en: 'Server address', da: 'Serveradresse', fo: 'Ambætaraadressa' },
    'receiver.setup_connect':  { en: 'Connect', da: 'Forbind', fo: 'Sambind' },
    'receiver.setup_trying':   { en: 'Trying to connect…', da: 'Prøver at få forbindelse…',
                                 fo: 'Roynir at sambinda…' },
    'receiver.setup_not_found':{ en: 'There’s no Ravilo server at that address',
                                 da: 'Der er ingen Ravilo-server på den adresse',
                                 fo: 'Eingin Ravilo-ambætari er á hasi adressuni' },
    'nav.apps':                { en: 'Apps', da: 'Apps', fo: 'Appir' },
    /* The shipped prompt, carried so the disagreement is in the data. NOT DRAWN — see below. */
    'setup.server_prompt.shipped': {
      en: 'Enter your jellystructure server address',
      da: 'Skriv adressen på jeres jellystructure-server',
      fo: 'Skriva adressuna á jellystructure-ambætaranum tínum'
    }
  };

  /* ---- the new and the corrected strings ---- */
  var NEW_STRINGS = {
    /* SPEC FR-8's table, verbatim. */
    'setup.from_invite': {
      en: 'From your invite. Check the address, then connect.',
      da: 'Fra din invitation. Tjek adressen, og forbind så.',
      fo: 'Úr tíni innbjóðing. Kanna adressuna, og bind so í.'
    },
    'setup.server_example': { en: STAND.example, da: STAND.example, fo: STAND.example },

    /* DEB-5836 FR-3's wording, which the spec cites and does not change. The English is that
       spec's own; the Danish and Faroese are the shipped drafts with the forbidden word replaced
       and nothing else touched, and they are DRAFTS — REPORT.md says so and lists them flat. */
    'setup.server_prompt': {
      en: 'Enter your Ravilo server address',
      da: 'Skriv adressen på jeres Ravilo-server',
      fo: 'Skriva adressuna á Ravilo-ambætaranum tínum'
    },

    /* The hidden-character line from DEB-5002 FR-4, which sits in the same place and is drawn in
       the same round on the brief's instruction. Read out of round 2026-11-19-setup-hidden-
       characters' own data file, which took it from that spec's FR-7 table. */
    'setup.hidden_line_break': {
      en: 'This address has a hidden line break',
      da: 'Adressen har et skjult linjeskift',
      fo: 'Adressan hevur eitt fjalt linjuskift'
    },
    'setup.use_fixed_address': { en: 'Use {address}', da: 'Brug {address}', fo: 'Brúka {address}' }
  };

  /* =================================================================================================
     THE ADMIN SHEET'S TEXT — English, in Kotlin, never in i18n/ (FR-2's last line).
     ================================================================================================= */
  var ADMIN = {
    button:   'Invite a viewer',
    title:    'Invite a viewer',
    sub:      'Send this link, or let them scan it.',
    linkLabel:'Invite link',
    copy:     'Copy',
    copied:   'Copied',
    scan:     'Scan to open',
    tvLine:   'TVs can’t open links. On a TV, open Ravilo and type',   /* + STAND.host */
    who:      'The link holds no password. The viewer signs in with their own account, which must '
            + 'exist on this server first (or is made at their first single sign-on).',
    /* FR-2's unset state. The sheet cannot assemble a link, so it says what to do and links there. */
    unsetTitle: 'No public address yet',
    unsetBody:  'Set the Public address in Settings → Connections first. Until then there is no link '
              + 'to send and no address for a TV to type.',
    unsetBtn:   'Open Settings → Connections',
    close:      'Close'
  };

  /* FR-2's account line names the server, never the server software: the spec writes "must exist in
     Jellyfin first", which is correct for an ADMIN (the admin console names Jellyfin all over) —
     but this sheet's sentence is one an admin reads out to a viewer, so ADMIN.who says "on this
     server". REPORT.md puts that to Bárður as a question rather than treating it as settled. */
  var ADMIN_SPEC_WORDING = 'The link holds no password. The viewer signs in with their own account, '
    + 'which must exist in Jellyfin first (or is made at their first single sign-on).';

  /* =================================================================================================
     THE LANDING PAGE'S TEXT
     Two wordings for the same three parts, because the brief and the spec disagree. The frames
     draw BRIEF; SPEC is carried so the report can show both and Bárður can settle one.
     ================================================================================================= */
  var JOIN = {
    brand:   'Ravilo',
    hostLead:'You’ve been invited to',          /* + STAND.host, as the page's h1 area */
    brief: {
      open:    'Open in Ravilo',
      getApp:  'Get the app',
      browser: 'Use Ravilo in the browser'
    },
    spec: {
      open:    'Open the Ravilo app',
      getApp:  'Get the app',
      browser: 'Watch in your browser'
    },
    again:   'Installed the app? Open this link again.',
    /* The owner's 2026-11-27 amendment: macOS, Linux and Samsung TV keep this line; Android does
       not, because it never needs an install. The line therefore belongs to the DOWNLOAD GROUP and
       not to the Get-the-app section as a whole, which is what the frames draw. */
    afterInstall: 'After installing, open this link again.',
    tvLine:  'On a TV, open Ravilo and type',   /* + STAND.host */
    notFound:{
      title: 'This invite link isn’t set up',
      body:  'Ask whoever sent it to try again.'
    },
    /* Danish and Faroese drafts. The brief says the DRAWING is English only, so these are not
       rendered — they are here so REPORT.md's flat list is generated from the data rather than
       typed beside it, and so slice 3 has them when InvitePages is written. */
    da: {
      hostLead: 'Du er inviteret til', open: 'Åbn i Ravilo', getApp: 'Hent appen',
      browser: 'Brug Ravilo i browseren', again: 'Har du appen? Åbn linket igen.',
      afterInstall: 'Åbn linket igen, når appen er installeret.',
      tvLine: 'På et tv: åbn Ravilo og skriv',
      nfTitle: 'Dette invitationslink er ikke sat op', nfBody: 'Bed afsenderen prøve igen.'
    },
    fo: {
      hostLead: 'Tú ert innbodin til', open: 'Lat upp í Ravilo', getApp: 'Tak appina',
      browser: 'Brúka Ravilo í kagaranum', again: 'Hevur tú appina? Lat leinkjuna upp aftur.',
      afterInstall: 'Lat leinkjuna upp aftur, tá appin er innlagd.',
      tvLine: 'Á einum TV: lat Ravilo upp og skriva',
      nfTitle: 'Henda innbjóðingarleinkja er ikki sett upp', nfBody: 'Bið tann, sum sendi hana, royna aftur.'
    }
  };

  /* ---- the Get-the-app buttons ----
     `kind` is the whole of the owner's 2026-11-27 amendment, expressed as data:

       kind 'web'      ONE button, labelled like the others, href = <webBase>/join. It opens the
                       installed Android app through its App Link filter (FR-5a) and Ravilo Web on
                       any other phone. NO "open this link again" line: it never needs an install.
       kind 'download' a release file. Keeps JOIN.afterInstall, because no install referrer exists
                       and a link never survives an install.

     iOS IS DELIBERATELY ABSENT. The brief's section head reads "Android, iPhone/web, desktop", but
     the spec's Out list is explicit — "iOS" — and FR-3 names exactly Android, macOS, Linux and
     Samsung TV. An iPhone is served by the browser part, which is its own section. REPORT.md says
     this out loud rather than letting a missing button read as an oversight. */
  var PLATFORMS = [
    { id: 'android', label: 'Android',    kind: 'web',      href: STAND.webJoin },
    { id: 'macos',   label: 'macOS',      kind: 'download', href: STAND.host + '/download/macos' },
    { id: 'linux',   label: 'Linux',      kind: 'download', href: STAND.host + '/download/linux' },
    { id: 'samsung', label: 'Samsung TV', kind: 'download', href: STAND.host + '/download/samsung' }
  ];

  /* ---- the three orders this round draws. The question, as a choice. ----
     `parts` is the page's running order top to bottom. `host` is the invited-to block, which every
     direction keeps first — the brief puts it first and no reading of the question moves it. */
  var ORDERS = {
    L1: {
      name: 'L1 — Open in Ravilo first',
      note: 'the brief’s lean: a viewer with the app is one press from done; a viewer without one '
          + 'falls through to Get the app',
      parts: ['open', 'getApp', 'browser', 'tv']
    },
    L2: {
      name: 'L2 — Get the app first',
      note: 'the page leads with the thing that always works; the viewer who already has the app '
          + 'scrolls past a list they do not need',
      parts: ['getApp', 'open', 'browser', 'tv']
    },
    L3: {
      name: 'L3 — the browser first (SPEC FR-3’s own order)',
      note: 'what the spec as written says the page does: browser, open the app, get the app, TV. '
          + 'Drawn because an engineer builds FR-3, not the brief',
      parts: ['browser', 'open', 'getApp', 'tv']
    }
  };

  /* =================================================================================================
     THE LANDING PAGE'S OWN VALUES — the SHIPPED single-sign-on page, read out of
     src/linuxX64Main/kotlin/dev/jellystructure/tv/SingleSignOnPages.kt (CSS and MARK) on
     2026-11-27. This page ships as ONE self-contained document with inline CSS and SVG and no
     token file, so these are written out rather than referenced: that is the honest drawing of
     what the page can carry. A value here that disagrees with that file is a defect in THIS file.
     ================================================================================================= */
  var SSO = {
    bg:        '#0a0c13',
    ink:       '#f3f4fb',
    inkSoft:   '#aeb4cb',
    gradFrom:  '#7b6ef0',
    gradTo:    '#3fb6f5',
    btnText:   '#fff',          /* the SHIPPED value, and low-contrast at the blue end: the
                                   product's, not this round's. Noted once in REPORT.md. */
    wash1:     'radial-gradient(60% 46% at 20% 4%, rgba(123,110,240,.20), transparent 68%)',
    wash2:     'radial-gradient(52% 44% at 86% 98%, rgba(63,182,245,.13), transparent 70%)',
    inner:     420,             /* .in max-width                                             */
    innerWide: 560,             /* at min-width: 900px                                        */
    h1:        27,  h1Wide: 34,
    brand:     30,  brandWide: 36,
    body:      15,
    btnH:      50,  btnR: 14,  btnSize: 15.5,
    pad:       '40px 24px',
    tones:     { ok: '#2dd49a', warn: '#f5b542', bad: '#f5667a' }
  };

  /* The shipped MARK, verbatim from SingleSignOnPages.kt:99, with its one gradient id replaced by
     `{ID}` so invite-kit.js can mint it from a counter — PROCESS.md's SVG trap is real, and a
     gradient defined inside a display:none frame resolves to nothing in a VISIBLE one. */
  var MARK = '<svg width="{W}" height="{W}" viewBox="12 20 76 76" aria-hidden="true">'
    + '<defs><linearGradient id="{ID}" x1="0" y1="0" x2="1" y2="1">'
    + '<stop offset="0" stop-color="#7b6ef0"/><stop offset="1" stop-color="#3fb6f5"/>'
    + '</linearGradient></defs>'
    + '<path d="M22 52 C22 24 78 24 78 52 C66 45 59 45 50 49 C41 45 34 45 22 52 Z" fill="url(#{ID})"/>'
    + '<g stroke="url(#{ID})" stroke-width="4.5" stroke-linecap="round" fill="none">'
    + '<path d="M33 51 q-5 12 1 20 q5 8 0 14" opacity=".9"/>'
    + '<path d="M44 52 q-4 13 1 21 q4 9 0 13" opacity=".72"/>'
    + '<path d="M56 52 q4 13 -1 21 q-4 9 0 13" opacity=".72"/>'
    + '<path d="M67 51 q5 12 -1 20 q-5 8 0 14" opacity=".9"/></g></svg>';

  /* =================================================================================================
     GEOMETRY
     Every number below is either read out of a shipped stylesheet (file and selector named) or is
     this round's own proposal, marked `NEW`. design/ravilo/ is reference only and the Compose port
     reads these numbers rather than the pictures, so they have to be here and they have to be right.
     ================================================================================================= */
  var GEO = {
    /* ---- the admin sheet. wf.css's own modal idiom, measured. ---- */
    admin: {
      modalW:   440,   /* wf.css `.pair-modal` width — unchanged, so the sheet is the shipped size */
      modalPad: 22,    /* wf.css `.pair-modal` padding                                             */
      radius:   16,    /* wf.css `--radius`                                                        */
      radiusS:  10,    /* wf.css `--radius-s`, the control radius                                  */
      qr:       4,     /* NEW: px per QR module. 29 modules + a 4-module quiet zone each side =
                          37 cells = 148 px of code inside a 164 px plate.                         */
      qrQuiet:  4,     /* the MANDATORY quiet zone. A QR with less does not scan.                  */
      qrPlate:  164,   /* NEW: the white plate the code sits on, 148 + 8 px of white each side      */
      frameW:   1240,  /* the admin frame these pictures are taken in                              */
      frameH:   840,
      railW:    248    /* app.css `.shell` sidebar, `248px minmax(0, 1fr)`                          */
    },

    /* ---- the landing page's frames ---- */
    join: {
      phoneW: 393, phoneH: 852,     /* iPhone 16, SKILL-house's phone frame                       */
      pixelW: 412, pixelH: 915,     /* Pixel 9                                                    */
      wideW: 1100, wideH: 900,      /* over the page's own 900 px breakpoint                      */
      gap: 10,                      /* NEW: between two buttons in a group                        */
      groupGap: 30,                 /* NEW: between the page's parts                              */
      headLabel: 11.5               /* NEW: the part's own small caps label                       */
    },

    /* ---- the setup component, in dp. ONE set of numbers; each surface sets its px-per-dp. ----
       Read out of round 2026-11-19-setup-hidden-characters' setup-data.js SPEC, which read them
       out of ServerSetupScreen and the shipped stylesheets. Carried here unchanged so the two
       rounds' pictures of the same component can be diffed.

       The TV stage is 1920 px wide and 960 dp (density 2), so the TV renders these at 2×. The
       phone bezel is 412 dp = 412 px and the desktop window is 1:1, so both render at 1×. */
    setup: {
      colGap:      20,   /* the column's existing 20 dp row gap                                   */
      connectH:    52,   /* Connect                                                               */
      btnR:        8,    /* the control radius the screen already uses                            */
      inviteGap:   10,   /* NEW: the from-invite line to the field                                */
      inviteSize:  13.5, /* NEW: Sora 600 — the SAME size as the hidden-character sentence, on
                            purpose: the two lines sit in the same column and a different size
                            would read as a different kind of thing                               */
      notFoundGap: 10,   /* field bottom to the not-found line (the hidden-char round's warnGapTop)*/
      notFoundSize:13.5,
      railW:       3,    /* the shipped `.dplaynote .pn-bar` / `.ep-playnote` rail                */
      railGap:     11,
      markSize:    14,   /* JetBrains Mono 500, one cell per character                            */
      exampleSize: 16    /* the field's own value size, so a placeholder sits exactly on it       */
    }
  };

  /* ---- the colours the two new lines take, per skin ----
     The from-invite line is INFORMATION, not a warning: it takes the accent's own ink rather than
     --warn, so the one warning colour on this screen keeps meaning "something is wrong". The
     not-found line is an error and takes --bad.

     ravilo.css carries --warn #f5b542 and --bad; ravilo-phone.css and desktop-directions.css carry
     neither, and RaviloColors has no warn or bad field — the promotion adds them. Daylight needs
     its own values for contrast and Noir's accent IS #f5b542, which is why its warn moves.

     `info: null` means DERIVED FROM THE SKIN'S OWN ACCENT in invite.css — not "no value". The
     first render hard-coded Aurora's #8e82ff for every dark skin, and the Midnight frame then drew
     a PURPLE invite line on a screen whose accent, wordmark and Connect button are all teal. A
     colour that says "this came from your invite" has to be the skin's accent or it reads as a
     fourth colour nobody chose. Only Daylight overrides, and only for contrast. */
  var TONE = {
    aurora:   { info: null, bad: '#ff6f61', warn: '#f5b542',
                why: 'info derived from --accent; --bad and --warn unchanged' },
    midnight: { info: null, bad: '#ff6f61', warn: '#f5b542',
                why: 'derived — Midnight’s accent is teal, not Aurora’s violet' },
    noir:     { info: null, bad: '#ff6f61', warn: '#e0792f',
                why: 'derived, so the info line is Noir’s amber; the WARN then moves to its '
                   + 'accent-2, because Noir’s accent IS #f5b542 and the two would be one colour' },
    graphite: { info: null, bad: '#ff6f61', warn: '#f5b542', why: 'derived' },
    daylight: { info: '#4b3bd1', bad: '#c0392b', warn: '#8a5a00',
                why: 'the derived value is a LIGHTENED accent and Daylight needs a darkened one: '
                   + '#8e82ff on #fbfbfd is 2.4 : 1, #4b3bd1 is 7.1 : 1. The warn value is the '
                   + 'hidden-character round’s own daylight pick, unchanged.' }
  };

  /* ---- the setup screen's states, by name ---- */
  /* `focus` is NAMED in every state, and it is named here rather than in the builder, because the
     constitution's rule is absolute — "there is always exactly one visible focus target" — and the
     first render of this round broke it: the field carried the TV's 3 dp ring and Connect carried
     the focused fill at the same time, which a D-pad viewer reads as two targets. One field, one
     answer, one frame. */
  var SETUP_STATES = {
    /* The brief's first state. FR-6: "A link only fills the field. The viewer sees the host and
       presses Connect." The address is already there, so focus starts on Connect and the whole
       invite path is ONE press. Reaching the field to edit it is one D-pad up. */
    invite:    { field: STAND.host, invite: true, connect: 'focused', focus: 'connect' },
    /* The new example, on a field nobody has typed in. Here there is nothing to connect to yet, so
       focus is on the field and the caret is in it. */
    example:   { field: null, placeholder: STAND.example, connect: 'ready', focus: 'field' },
    /* FR-7 while the probe is out: the button reads receiver.setup_trying and a second press does
       nothing. Focus does NOT move — that is the point of the state. */
    trying:    { field: STAND.host, invite: true, connect: 'trying', focus: 'connect' },
    /* FR-7 on a failure: the typed text stays, the line shows under the field, and focus stays on
       Connect, so trying again is one press. */
    notFound:  { field: STAND.host, invite: true, connect: 'focused', notFound: true,
                 focus: 'connect' },
    /* DEB-5002 FR-4's hidden line break, drawn WITH the invite line, because the brief asked for
       the two together and because the pictures are the only way to see that they do not collide:
       the invite line is ABOVE the field and the hidden-character warning is BELOW it. Connect is
       disabled here (FR-6 of that spec), so focus cannot sit on it — it is on the fix button, which
       is where the earlier round put it. */
    hidden:    { field: 'jelly.example.com', invite: true, hidden: true, connect: 'off',
                 focus: 'fix',
                 marks: [{ t: 'jelly.example.com' }, { mark: 'BREAK' }, { t: '[21:20]' }],
                 cleaned: 'jelly.example.com' },
    /* the longest field value, which is not in the brief's stand-ins — see STAND.longHost */
    longest:   { field: STAND.longHost, invite: true, connect: 'focused', focus: 'connect' }
  };

  var CLOCK = '21:20';   /* the phone status strip. Kept from the hidden-character round so the two
                            rounds' phone frames differ only where the design differs. */

  window.IV = {
    STAND: STAND, SHIPPED: SHIPPED, NEW_STRINGS: NEW_STRINGS,
    ADMIN: ADMIN, ADMIN_SPEC_WORDING: ADMIN_SPEC_WORDING,
    JOIN: JOIN, PLATFORMS: PLATFORMS, ORDERS: ORDERS,
    SSO: SSO, MARK: MARK, GEO: GEO, TONE: TONE,
    SETUP_STATES: SETUP_STATES, CLOCK: CLOCK,

    /* one lookup over both tables, so no frame can disagree with i18n */
    str: function (key, lang) {
      var t = NEW_STRINGS[key] || SHIPPED[key];
      return t ? (t[lang] || t.en) : key;
    },
    /* the mark glyphs, from the hidden-character round's own recommendation (the pilcrow, which
       all three self-hosted faces carry; U+23CE is in none of their subsets) */
    GLYPHS: { BREAK: '¶', SPACE: '·' }
  };
})();
