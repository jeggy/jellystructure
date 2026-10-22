/* mac-open-anyway — the five drawn pictures and the copy, defined once.
   Round slug mac-open-anyway, 2026-10-22.

   There is JS in this round for one reason: the same five pictures and the same five sentences
   appear in nine frames (two directions x two widths, collapsed, images-off, Danish, Faroese, and
   the directions document). Written by hand nine times they would drift, and a drifted frame reads
   as a design decision. Nothing here is behaviour the page needs — the shipped page can be static
   HTML, and `renderAll()` runs once at load.

   DRAWN, NEVER SCREENSHOTS. No real user name, no second application in any list, nothing from
   anybody's media library, no host name and no URL. */

(function (global) {
  'use strict';

  /* The Ravilo mark, drawn: a squircle on dark navy carrying a jellyfish — a wide dome and four
     tentacles — in the page's own magenta-violet -> blue gradient. A facsimile for the pictures;
     the build uses the real icon file, which is not a design artefact and is not copied here. */
  /* Every gradient gets its own id, from a counter rather than from the caller.
     Two SVGs sharing a gradient id is not a cosmetic duplicate: a document holds frames that are
     `display:none` (the frames for the other width, and the images-off frame), Chromium will not
     resolve a paint server defined inside a hidden subtree, and every later reference to that id
     then paints nothing. The symptom is a black square where the icon should be, in the VISIBLE
     frame, caused by a hidden one — so it does not reproduce if you open the frame on its own. */
  var seq = 0;

  function ravilo(size) {
    var g = 'rv' + (++seq);
    return '<svg class="moa-appicon" width="' + size + '" height="' + size + '" viewBox="0 0 100 100" aria-hidden="true">' +
      '<defs><linearGradient id="' + g + '" x1="0" y1="0" x2="1" y2="1">' +
      '<stop offset="0" stop-color="#b15cd0"/><stop offset=".5" stop-color="#7b6ef0"/><stop offset="1" stop-color="#00a4dc"/>' +
      '</linearGradient></defs>' +
      '<rect width="100" height="100" rx="23" fill="#0a1020"/>' +
      '<path d="M22 50c0-16 12.5-27 28-27s28 11 28 27c0 2.2-1.6 3.4-3.6 2.6C68 50 59 48.4 50 48.4S32 50 25.6 52.6C23.6 53.4 22 52.2 22 50Z" fill="url(#' + g + ')"/>' +
      '<g stroke="url(#' + g + ')" stroke-width="5.4" stroke-linecap="round" fill="none">' +
      '<path d="M33 54c-2 7 2.5 10 .5 17S31 80 32.5 84"/>' +
      '<path d="M44 56c-1.7 7.4 2.6 10.6.6 17.6S42 84 43.4 88"/>' +
      '<path d="M56 56c1.7 7.4-2.6 10.6-.6 17.6S58 84 56.6 88"/>' +
      '<path d="M67 54c2 7-2.5 10-.5 17S69 80 67.5 84"/>' +
      '</g></svg>';
  }

  /* The Applications folder, drawn. */
  function folder(size) {
    return '<svg class="moa-folder" width="' + size + '" height="' + size + '" viewBox="0 0 100 100" aria-hidden="true">' +
      '<path d="M10 28a6 6 0 0 1 6-6h21l8 9h29a6 6 0 0 1 6 6v6H10Z" fill="#4a93d6"/>' +
      '<path d="M10 37h78a6 6 0 0 1 6 6v29a6 6 0 0 1-6 6H16a6 6 0 0 1-6-6Z" fill="#6fb6ee"/>' +
      '<path d="M10 37h78a6 6 0 0 1 6 6v4H10Z" fill="#87c6f4"/>' +
      '</svg>';
  }

  /* --------------------------------------------------------------- copy

     English ships. The Danish and Faroese are drafts, carried so the longest-string frames are
     drawn with real words rather than padded English, and so the pair exists if the page is ever
     translated. The macOS button names stay in English in all three, because the pictures show an
     English macOS and the walk recorded English — see the report, which flags this. */

  var COPY = {
    en: {
      lang: 'English',
      toggle: 'How to open it',
      forwhich: 'Mac · every download',
      lead: 'Ravilo isn’t registered with Apple, so macOS asks twice — <b>every time you download a version from this page</b>, not just the first.',
      managed: 'On a Mac managed by your work or school, <em>Open Anyway</em> may ask for an administrator’s password you don’t have. That is that Mac’s policy, not Ravilo.',
      steps: [
        'Open the <code>.dmg</code> you downloaded. macOS refuses it — choose <b>Done</b>, not <em>Move to Trash</em>.',
        'Open <b>System Settings ▸ Privacy &amp; Security</b>, scroll down to <em>Security</em>, and choose <b>Open Anyway</b> beside it. The disk image opens.',
        'Drag <b>Ravilo</b> onto <b>Applications</b>.',
        'Open Ravilo from Applications. macOS refuses it again — choose <b>Done</b>.',
        'Go back to <b>Privacy &amp; Security</b>, choose <b>Open Anyway</b> once more, and enter your password. Ravilo opens.'
      ]
    },
    da: {
      lang: 'Dansk',
      toggle: 'Sådan åbner du den',
      forwhich: 'Mac · hver download',
      lead: 'Ravilo er ikke registreret hos Apple, så macOS spørger to gange — <b>hver gang du henter en version fra denne side</b>, ikke kun første gang.',
      managed: 'På en Mac, der styres af dit arbejde eller din skole, kan <em>Open Anyway</em> bede om en administratoradgangskode, du ikke har. Det er den Macs politik, ikke Ravilo.',
      steps: [
        'Åbn den <code>.dmg</code>, du har hentet. macOS afviser den — vælg <b>Done</b>, ikke <em>Move to Trash</em>.',
        'Åbn <b>Systemindstillinger ▸ Anonymitet &amp; sikkerhed</b>, rul ned til <em>Sikkerhed</em>, og vælg <b>Open Anyway</b> ud for den. Diskbilledet åbner.',
        'Træk <b>Ravilo</b> over på <b>Applications</b>.',
        'Åbn Ravilo fra Programmer. macOS afviser den igen — vælg <b>Done</b>.',
        'Gå tilbage til <b>Anonymitet &amp; sikkerhed</b>, vælg <b>Open Anyway</b> igen, og indtast din adgangskode. Ravilo åbner.'
      ]
    },
    fo: {
      lang: 'Føroyskt',
      toggle: 'Soleiiðis letur tú hana upp',
      forwhich: 'Mac · hvørja ferð tú heintar',
      lead: 'Ravilo er ikki skrásett hjá Apple, so macOS spyr tvær ferðir — <b>hvørja ferð tú heintar eina útgávu frá hesi síðu</b>, ikki bert fyrstu ferð.',
      managed: 'Á eini Mac, sum arbeiðið ella skúlin stýrir, kann <em>Open Anyway</em> biðja um eitt umsitaraloyniorð, sum tú ikki hevur. Tað er politikkurin hjá teirri Mac, ikki Ravilo.',
      steps: [
        'Lat ta <code>.dmg</code>, tú heintaði, upp. macOS noktar hana — vel <b>Done</b>, ikki <em>Move to Trash</em>.',
        'Lat <b>System Settings ▸ Privacy &amp; Security</b> upp, rulla niður til <em>Security</em>, og vel <b>Open Anyway</b> við hana. Diskmyndin letur upp.',
        'Drag <b>Ravilo</b> yvir á <b>Applications</b>.',
        'Lat Ravilo upp úr Applications. macOS noktar hana aftur — vel <b>Done</b>.',
        'Far aftur í <b>Privacy &amp; Security</b>, vel <b>Open Anyway</b> aftur, og skriva loyniorðið hjá tær. Ravilo letur upp.'
      ]
    }
  };

  /* Alt text. Each one carries the WHOLE instruction, because the page has to work when the
     pictures do not load — which is a state this round draws rather than asserts. */
  var ALT = {
    en: [
      'Step 1 of 5: a macOS dialog refusing the downloaded disk image by name. Its two buttons are Move to Trash and Done. Choose Done.',
      'Step 2 of 5: the Privacy & Security pane of System Settings, showing the downloaded .dmg file named on a line saying it was blocked to protect your Mac, with an Open Anyway button beside it. Choose Open Anyway, and the disk image opens.',
      'Step 3 of 5: the opened disk image, showing the Ravilo icon and an arrow pointing to the Applications folder. Drag Ravilo onto Applications.',
      'Step 4 of 5: the same macOS dialog, now refusing Ravilo itself after it has been installed. Its buttons are Move to Trash and Done. Choose Done again.',
      'Step 5 of 5: the Privacy & Security pane again with its Open Anyway button, and a password prompt with an empty password field. Choose Open Anyway and enter your password; Ravilo opens.'
    ],
    da: [
      'Trin 1 af 5: en macOS-dialog, der afviser det hentede diskbillede. Dens to knapper er Move to Trash og Done. Vælg Done.',
      'Trin 2 af 5: ruden Anonymitet & sikkerhed i Systemindstillinger hvor den hentede .dmg-fil er nævnt på en linje om, at den blev blokeret for at beskytte din Mac, med en Open Anyway-knap ved siden af. Vælg Open Anyway, og diskbilledet åbner.',
      'Trin 3 af 5: det åbnede diskbillede med Ravilo-ikonet og en pil, der peger mod mappen Applications. Træk Ravilo over på Applications.',
      'Trin 4 af 5: den samme macOS-dialog, der nu afviser selve Ravilo efter installationen. Knapperne er Move to Trash og Done. Vælg Done igen.',
      'Trin 5 af 5: ruden Anonymitet & sikkerhed igen med Open Anyway-knappen og en adgangskodedialog med et tomt felt. Vælg Open Anyway, og indtast din adgangskode; Ravilo åbner.'
    ],
    fo: [
      'Stig 1 av 5: ein macOS-samrøða, sum noktar ta heintaðu diskmyndina. Báðir knappar eru Move to Trash og Done. Vel Done.',
      'Stig 2 av 5: Privacy & Security-rutan í System Settings har hin heintaða .dmg-fílan er nevnd á eini linju um, at hon var blokkerað fyri at verja Mac-in, við einum Open Anyway-knappi við liðina. Vel Open Anyway, og diskmyndin letur upp.',
      'Stig 3 av 5: hin upplatna diskmyndin við Ravilo-ikoninum og einari ørv, sum peikar á Applications-mappuna. Drag Ravilo yvir á Applications.',
      'Stig 4 av 5: sama macOS-samrøða, sum nú noktar sjálvt Ravilo eftir innleggingina. Knappar eru Move to Trash og Done. Vel Done aftur.',
      'Stig 5 av 5: Privacy & Security-rutan aftur við Open Anyway-knappinum, og ein loyniorðssamrøða við tómum feltinum. Vel Open Anyway og skriva loyniorðið; Ravilo letur upp.'
    ]
  };

  /* The two macOS strings the walk did NOT record. Working copy, confirmed at QA. Kept here, named,
     so that confirming them is one edit in one place rather than a hunt through nine frames. */
  /* Steps 1 and 2 refuse the DISK IMAGE; steps 4 and 5 refuse the installed APP. macOS names the
     object it is refusing, so the pictures do too — otherwise a reader is told the .dmg is blocked
     while the picture beside it says Ravilo. The file name keeps its version here because this is a
     picture of one downloaded file; the page's SENTENCES stay version-free, because the live page
     outlives 1.52. */
  var DMG_NAME = 'ravilo-mac-1.52.dmg';
  var APP_NAME = 'Ravilo';

  var WORKING = {
    alertTitle: function (subject) { return '“' + subject + '” can’t be opened'; },
    alertBody: 'macOS cannot check it for malicious software.',
    blockedLine: function (subject) { return '“' + subject + '” was blocked to protect your Mac.'; },
    pwTitle: 'Enter your password.',
    dmgName: DMG_NAME,
    appName: APP_NAME
  };

  /* --------------------------------------------------------------- the five pictures */

  function alert_(subject) {
    return '<div class="moa-alert">' + ravilo(26) +
      '<p class="moa-t">' + WORKING.alertTitle(subject) + '</p>' +
      '<p class="moa-b">' + WORKING.alertBody + '</p>' +
      '<div class="moa-row">' +
        '<span class="moa-btn-mac">Move to Trash</span>' +
        '<span class="moa-btn-mac is-default">Done</span>' +
      '</div></div>';
  }

  function pane(hot, subject) {
    return '<div class="moa-pane">' +
      '<div class="moa-bar"><i class="moa-dot"></i><i class="moa-dot"></i><i class="moa-dot"></i>' +
      '<span class="moa-bartitle">Privacy &amp; Security</span></div>' +
      '<div class="moa-body">' +
        '<p class="moa-h">Privacy &amp; Security</p>' +
        '<p class="moa-sub">Security</p>' +
        '<div class="moa-line">' +
          '<span class="moa-say2">' + WORKING.blockedLine(subject) + '</span>' +
          '<span class="moa-openanyway' + (hot ? ' is-hot' : '') + '">Open Anyway</span>' +
        '</div>' +
      '</div></div>';
  }

  var PIC = [
    function () { return alert_(DMG_NAME); },
    function () { return pane(true, DMG_NAME); },
    function () {
      return '<div class="moa-drag">' +
        '<span class="moa-tile">' + ravilo(54) + '<span class="moa-cap">Ravilo</span></span>' +
        '<span class="moa-arrow">→</span>' +
        '<span class="moa-tile">' + folder(54) + '<span class="moa-cap">Applications</span></span>' +
        '</div>';
    },
    function () { return alert_(APP_NAME); },
    function () {
      return pane(false, APP_NAME) +
        '<div class="moa-pw">' +
        '<p class="moa-t">' + WORKING.pwTitle + '</p>' +
        '<div class="moa-field"></div>' +
        '<div class="moa-row"><span class="moa-btn-mac">Cancel</span>' +
        '<span class="moa-btn-mac is-default">OK</span></div></div>';
    }
  ];

  /* --------------------------------------------------------------- the .dmg background

     Defined here and used by both dmg.html (the bare 1x/2x asset) and dmg-window.html (the same
     asset inside a Light and a Dark Finder window). It lived in both files once and had to be
     edited twice for every change — and the whole claim the two window frames make is that they
     show the IDENTICAL image, which two copies cannot guarantee.

     Steps 1 and 2 are deliberately absent: by the time this window is open they are behind the
     user. Steps 4 and 5 are here with their pictures, at the pictures' full design width. */

  function dmgBackground(lang) {
    lang = lang || 'en';
    var step = function (n, txt) {
      /* The number goes INSIDE the text span. As a sibling of a display:block span it formed
         its own line box and added 21pt of dead height under every picture — which, in an
         image with ~10pt of slack, pushed both sentences off the bottom edge. */
      return '<li>' + picture(n, lang) +
        '<span class="moa-txt"><span class="moa-n">' + n + '</span>' + txt + '</span></li>';
    };
    return '<div class="moa-dmg-head"><h1>Ravilo</h1><p>Drag it onto Applications to install.</p></div>' +
      '<div class="moa-target is-app"></div><div class="moa-target is-applications"></div>' +
      '<div class="moa-draghint">drag</div><div class="moa-dragarrow"></div>' +
      /* The two bands Finder prints its own icon labels into. Flat mid-tone, nothing of ours in
         them: the label is white in Dark appearance and near-black in Light, and this image
         cannot change with either. */
      '<div class="moa-labelband is-app"></div><div class="moa-labelband is-applications"></div>' +
      '<div class="moa-after">' +
        '<p class="moa-afterhead">Then, every time you install a version from the site:</p><ol>' +
        step(4, 'Open Ravilo from Applications. macOS refuses it again &mdash; choose <b>Done</b>.') +
        step(5, '<b>Privacy&nbsp;&amp;&nbsp;Security &#9656; Open&nbsp;Anyway</b> once more, then your password.') +
      '</ol></div>';
  }

  /* --------------------------------------------------------------- rendering */

  function picture(n, lang) {
    var alt = ALT[lang][n - 1];
    return '<div class="moa-pic" role="img" aria-label="' + alt.replace(/"/g, '&quot;') + '" ' +
      'data-alt="' + alt.replace(/"/g, '&quot;') + '">' +
      '<div class="moa-inner">' + PIC[n - 1]() + '</div></div>';
  }

  function steps(lang) {
    var c = COPY[lang], out = '';
    for (var i = 0; i < 5; i++) {
      out += '<li>' + picture(i + 1, lang) +
        '<p class="moa-say"><span class="moa-num">' + (i + 1) + '</span>' + c.steps[i] + '</p></li>';
    }
    return '<ol class="moa-steps">' + out + '</ol>';
  }

  function strip(lang, opts) {
    var c = COPY[lang];
    opts = opts || {};
    var body = '<p class="moa-lead">' + c.lead + '</p>' + steps(lang) +
      '<p class="moa-managed">' + c.managed + '</p>';
    if (opts.toggle) {
      return '<details class="moa-toggle"' + (opts.collapsed ? '' : ' open') + '>' +
        '<summary>' + c.toggle + '</summary>' + body + '</details>';
    }
    return body;
  }

  function renderAll(root) {
    (root || document).querySelectorAll('[data-dmg-bg]').forEach(function (el) {
      el.innerHTML = dmgBackground(el.getAttribute('data-lang') || 'en');
    });
    (root || document).querySelectorAll('[data-strip]').forEach(function (el) {
      var lang = el.getAttribute('data-lang') || 'en';
      el.innerHTML = strip(lang, {
        toggle: el.hasAttribute('data-toggle'),
        collapsed: el.hasAttribute('data-collapsed')
      });
    });
    (root || document).querySelectorAll('[data-pic]').forEach(function (el) {
      el.outerHTML = picture(parseInt(el.getAttribute('data-pic'), 10), el.getAttribute('data-lang') || 'en');
    });
    (root || document).querySelectorAll('[data-icon="ravilo"]').forEach(function (el) {
      el.innerHTML = ravilo(parseInt(el.getAttribute('data-size') || '128', 10));
    });
    (root || document).querySelectorAll('[data-icon="folder"]').forEach(function (el) {
      el.innerHTML = folder(parseInt(el.getAttribute('data-size') || '128', 10));
    });
    (root || document).querySelectorAll('[data-copy]').forEach(function (el) {
      var k = el.getAttribute('data-copy'), lang = el.getAttribute('data-lang') || 'en';
      el.innerHTML = COPY[lang][k];
    });
  }

  global.MOA = {
    COPY: COPY, ALT: ALT, WORKING: WORKING,
    ravilo: ravilo, folder: folder, picture: picture, strip: strip,
    dmgBackground: dmgBackground, renderAll: renderAll
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { renderAll(); });
  } else {
    renderAll();
  }
})(window);
