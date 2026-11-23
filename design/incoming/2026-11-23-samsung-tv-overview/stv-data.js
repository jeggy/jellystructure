/* stv-data.js — the ONE array this round renders from.

   Every TV name, model code, version, count, badge and word in every picture comes out of this
   file. Nothing in stv-kit.js invents a value, and no number is typed twice: the badge, the
   version lines and the primary button are DERIVED in the kit from the three fields the real app
   has (the request's state from the server, the installed version from the TV, the release that
   covers the TV), exactly as FR-10 and FR-11 describe. That is why "do these eight pictures agree
   with each other?" is something a reviewer can check rather than trust.

   The stand-in is the brief's, named exactly, and it is invented — no real TV name and no real
   model from the household appears here (invariant 7; the repository is public).
*/
(function () {
  'use strict';

  /* ---- the two TVs the brief names, and the release tags ------------------------------------ */
  var TVS = {
    a: { name: 'Living Room TV', model: 'UE50TU7105' },
    b: { name: 'Bedroom TV',     model: 'QE43Q60B' }
  };

  /* The release tags. LATEST is what the server keeps in the row's ready_from_release for a TV the
     current release covers; INSTALLED is what TV A answered on :8001/applications. Both are only
     ever read from here. */
  var LATEST = '1.71';
  var INSTALLED = '1.70';

  /* The phone's status bar clock. One value, so no two pictures disagree about the time. */
  var CLOCK = '20:14';

  /* ---- the states --------------------------------------------------------------------------
     `req`    the viewer's request for that TV, from GET /api/tv/samsung-requests — the ONLY source
              of a badge (FR-10: "badges come from the server"). One of:
                'ready'      Approved, and the current release covers the TV   → green Approved
                'approved'   Approved, no release covers it yet                 → green Approved
                'requested'  waiting on the owner                              → Waiting for approval
                'rejected'   the owner said no                                 → Not approved
                null         the viewer has no request for this TV             → Not set up
     `onTv`   what the TV answered for RaviloScrn on :8001/applications (FR-11) — the ONLY source
              of an installed version. One of: a version string, 'none' (a 404: Ravilo is not on
              it), or null (anything else: not known, shown as "—").
     `cover`  the row's ready_from_release. null on 'approved' — no release covers the TV yet.
     `offline` the scan did not find this TV; the card is built from the server row alone (item 4).
     `note`   the owner's decision note on a rejected row.

     Nothing here states a badge, a button or a line of copy. */
  var STATES = [
    { id: 's1-searching', label: 'Searching — before anything is found',
      wifi: true, scanning: true, cards: [] },

    { id: 's2-update', label: 'A approved, update waiting (1.70 → 1.71)',
      wifi: true, cards: [
        { tv: 'a', req: 'ready', onTv: INSTALLED, cover: LATEST }
      ] },

    { id: 's3-uptodate', label: 'A approved, up to date (1.71 = 1.71)',
      wifi: true, cards: [
        { tv: 'a', req: 'ready', onTv: LATEST, cover: LATEST }
      ] },

    /* The owner's own "1-2 TVs" case, and the main picture of the round. */
    { id: 's4-two', label: 'A approved and up to date, plus B Not set up',
      wifi: true, main: true, cards: [
        { tv: 'a', req: 'ready', onTv: LATEST, cover: LATEST },
        { tv: 'b', req: null }
      ] },

    { id: 's5-unknown', label: 'A approved, version not known (—), Install on TV',
      wifi: true, cards: [
        { tv: 'a', req: 'ready', onTv: null, cover: LATEST }
      ] },

    { id: 's6-nextrelease', label: 'A approved but not in a release yet',
      wifi: true, cards: [
        { tv: 'a', req: 'approved', onTv: null, cover: null }
      ] },

    { id: 's7-notfound', label: 'A not found on this Wi-Fi, B found and Not set up',
      wifi: true, cards: [
        { tv: 'b', req: null },
        { tv: 'a', req: 'ready', onTv: null, cover: LATEST, offline: true }
      ] },

    { id: 's8-nowifi', label: 'No Wi-Fi — the server row only',
      wifi: false, cards: [
        { tv: 'a', req: 'ready', onTv: null, cover: LATEST, offline: true }
      ] }
  ];

  /* ---- extra studies, drawn for the recommended direction only ------------------------------
     Not states the brief asked for. Each exists because drawing the eight raised a question the
     eight cannot answer; the report says what each one is for. */
  var STUDIES = [
    /* The server rows are in hand before the scan starts — they were fetched to decide that this
       screen opens at all. This frame asks whether the approved card should therefore render
       straight away, with its version line pending, instead of a skeleton. */
    { id: 'x1-serverfirst', label: 'Searching, with the approved card already drawn from the server',
      /* Only the viewer's OWN request rows exist before the scan answers — a TV with no request
         is not known to the phone at all yet, so TV B is absent from this frame by construction.
         Drawn with B in it first, and its card printed a Latest it cannot have. */
      wifi: true, scanning: true, serverFirst: true, cards: [
        { tv: 'a', req: 'ready', onTv: null, cover: LATEST, pending: true }
      ] },

    /* FR-11's 404 branch. FR-10 writes a line for "not known" ("On the TV: —") but none for
       "not installed", and a TV that has never had Ravilo is the common case, not the rare one. */
    { id: 'x2-notinstalled', label: 'A approved, Ravilo not on the TV (a 404 from :8001)',
      wifi: true, cards: [
        { tv: 'a', req: 'ready', onTv: 'none', cover: LATEST }
      ] },

    /* Every badge on one screen, so the four pills are compared rather than remembered - and so
       the longest of them is seen beside a name rather than alone. */
    { id: 'x3-allbadges', label: 'All four badges on one screen',
      wifi: true, cards: [
        { tv: 'a', req: 'ready', onTv: INSTALLED, cover: LATEST },
        { tv: 'b', req: 'requested' },
        { tv: 'a', req: 'rejected', note: 'Not this one — it is the one in the guest room.' },
        { tv: 'b', req: null }
      ] },

    /* Bárður's addition to the brief (2026-11-23, from the owner): today's screen gives a large
       generic TV drawing a third of the content and says nothing; the fix is information first,
       and "a small TV glyph on each card is fine". Direction A as drawn carries no illustration
       at all, which is the far side of the same line. This is the owner's own case with a 34 dp
       glyph tile on each card, so the choice is made on a picture rather than on that sentence.
       It is an OPTION on the picked direction, not a change to it. */
    { id: 'x4-glyph', label: 'The picked direction with a small TV glyph on each card',
      wifi: true, glyph: true, cards: [
        { tv: 'a', req: 'ready', onTv: LATEST, cover: LATEST },
        { tv: 'b', req: null }
      ] },

    /* The same option on the state that has the most to say, so the glyph is judged against a
       full card and not only against the quietest one. */
    { id: 'x5-glyph-update', label: 'The glyph option on the update-waiting state',
      wifi: true, glyph: true, cards: [
        { tv: 'a', req: 'ready', onTv: INSTALLED, cover: LATEST },
        { tv: 'b', req: null }
      ] }
  ];

  /* ---- the copy ----------------------------------------------------------------------------
     Keys that ALREADY SHIP in i18n/en.json, da.json and fo.json are marked `shipped` and their
     value is the shipped one, character for character. The shipped file wins wherever a string
     exists (SKILL-house, Words); this round reuses six of them rather than writing a seventh way
     to say the same thing. Everything else is a DRAFT this round proposes, listed flat in the
     report for the owner to read.

     Danish and Faroese are drafts in the register the shipped samsungtv.* strings already use
     ("TV’et" / "sjónvarpið", second person, no apology). */
  var STR = {
    en: {
      /* shipped */
      'ready.cta': 'Install on TV',
      'rejected.again': 'Ask again',
      'intro.nowifi': 'This phone isn’t on Wi-Fi. Connect it to the same Wi-Fi as the TV.',
      'retry': 'Try again',
      'trouble': 'It didn’t work?',
      'row': 'Samsung TV',
      /* draft */
      'ov.title': 'Your Samsung TVs',
      'ov.searching': 'Looking for TVs on this Wi-Fi…',
      'ov.on_tv': 'On the TV',
      'ov.latest': 'Latest',
      'ov.unknown': '—',
      'ov.not_installed': 'Not installed',
      'ov.next_release': 'Comes with the next Ravilo release',
      'ov.up_to_date': 'Up to date',
      'ov.reinstall': 'Reinstall',
      'ov.update_to': 'Update to {v}',
      'ov.not_found': 'Not found on this Wi-Fi',
      'ov.set_up': 'Set up this TV',
      'ov.found_one': '1 TV found on this Wi-Fi',
      'ov.found_n': '{n} TVs found on this Wi-Fi',
      'ov.found_none': 'No TVs found on this Wi-Fi',
      'ov.search_again': 'Search again',
      'ov.others': 'Also on this Wi-Fi',
      'badge.approved': 'Approved',
      'badge.waiting': 'Waiting for approval',
      'badge.rejected': 'Not approved',
      'badge.notsetup': 'Not set up',
      /* direction C only: the state as the headline */
      'head.update': 'Update waiting',
      'head.uptodate': 'Up to date',
      'head.install': 'Ready to install',
      'head.next': 'Waiting for the next release',
      'head.offline': 'Not on this Wi-Fi'
    },
    da: {
      /* shipped */
      'ready.cta': 'Installer på TV',
      'rejected.again': 'Bed igen',
      'intro.nowifi': 'Telefonen er ikke på Wi-Fi. Forbind den til samme Wi-Fi som TV’et.',
      'retry': 'Prøv igen',
      'trouble': 'Virkede det ikke?',
      'row': 'Samsung TV',
      /* draft */
      'ov.title': 'Dine Samsung-TV’er',
      'ov.searching': 'Leder efter TV på dette Wi-Fi…',
      'ov.on_tv': 'På TV’et',
      'ov.latest': 'Nyeste',
      'ov.unknown': '—',
      'ov.not_installed': 'Ikke installeret',
      'ov.next_release': 'Kommer med næste Ravilo-udgave',
      'ov.up_to_date': 'Opdateret',
      'ov.reinstall': 'Installer igen',
      'ov.update_to': 'Opdater til {v}',
      'ov.not_found': 'Ikke fundet på dette Wi-Fi',
      'ov.set_up': 'Sæt dette TV op',
      'ov.found_one': '1 TV fundet på dette Wi-Fi',
      'ov.found_n': '{n} TV fundet på dette Wi-Fi',
      'ov.found_none': 'Ingen TV fundet på dette Wi-Fi',
      'ov.search_again': 'Søg igen',
      'ov.others': 'Også på dette Wi-Fi',
      'badge.approved': 'Godkendt',
      'badge.waiting': 'Venter på godkendelse',
      'badge.rejected': 'Ikke godkendt',
      'badge.notsetup': 'Ikke sat op',
      'head.update': 'Opdatering venter',
      'head.uptodate': 'Opdateret',
      'head.install': 'Klar til installation',
      'head.next': 'Venter på næste udgave',
      'head.offline': 'Ikke på dette Wi-Fi'
    },
    fo: {
      /* shipped */
      'ready.cta': 'Legg á sjónvarpið',
      'rejected.again': 'Bið aftur',
      'intro.nowifi': 'Telefonin er ikki á Wi-Fi. Tengdu hana til sama Wi-Fi sum sjónvarpið.',
      'retry': 'Royn aftur',
      'trouble': 'Riggaði tað ikki?',
      'row': 'Samsung sjónvarp',
      /* draft */
      'ov.title': 'Tíni Samsung sjónvørp',
      'ov.searching': 'Leitar eftir sjónvørpum á hesum Wi-Fi…',
      'ov.on_tv': 'Á sjónvarpinum',
      'ov.latest': 'Nýggjasta',
      'ov.unknown': '—',
      'ov.not_installed': 'Ikki á sjónvarpinum',
      'ov.next_release': 'Kemur við næstu Ravilo-útgávu',
      'ov.up_to_date': 'Dagført',
      'ov.reinstall': 'Legg á aftur',
      'ov.update_to': 'Dagfør til {v}',
      'ov.not_found': 'Ikki funnið á hesum Wi-Fi',
      'ov.set_up': 'Set hetta sjónvarpið upp',
      'ov.found_one': '1 sjónvarp funnið á hesum Wi-Fi',
      'ov.found_n': '{n} sjónvørp funnin á hesum Wi-Fi',
      'ov.found_none': 'Einki sjónvarp funnið á hesum Wi-Fi',
      'ov.search_again': 'Leita aftur',
      'ov.others': 'Eisini á hesum Wi-Fi',
      'badge.approved': 'Góðkent',
      'badge.waiting': 'Bíðar eftir góðkenning',
      'badge.rejected': 'Ikki góðkent',
      'badge.notsetup': 'Ikki sett upp',
      'head.update': 'Dagføring bíðar',
      'head.uptodate': 'Dagført',
      'head.install': 'Klárt at leggja á',
      'head.next': 'Bíðar eftir næstu útgávu',
      'head.offline': 'Ikki á hesum Wi-Fi'
    }
  };

  /* Which keys are already in the shipped i18n files. The report's string table is built from
     this, so a key cannot be reported as new when it is not. */
  var SHIPPED_KEYS = ['ready.cta', 'rejected.again', 'intro.nowifi', 'retry', 'trouble', 'row'];

  /* The three directions, named. The order is the recommendation order. */
  var DIRECTIONS = [
    { id: 'a', name: 'Stack',  one: 'Cards; the approved TV’s card is the big one; two labelled version lines; the button inside the card.' },
    { id: 'b', name: 'Ledger', one: 'One plain list, every TV an equal row; the version pair as one 1.70 → 1.71 line; one button pinned to the bottom of the screen.' },
    { id: 'c', name: 'Signal', one: 'The state is the headline, the TV is the kicker; versions as one labelled inline pair; the button inside the state block.' }
  ];

  window.STV = {
    TVS: TVS, LATEST: LATEST, INSTALLED: INSTALLED, CLOCK: CLOCK,
    STATES: STATES, STUDIES: STUDIES, STR: STR, SHIPPED_KEYS: SHIPPED_KEYS,
    DIRECTIONS: DIRECTIONS
  };
})();
