/* swr-data.js — every number, string and state this round renders. One array, three directions.

   Round slug `samsung-tv-which-remote`, 2026-11-24. Surface: the Ravilo ANDROID PHONE only,
   412 x 915 dp portrait — Profile -> Samsung TV -> the guide, step 1 (*Open the hidden menu*).

   PROCESS.md, "What a round must come home with": every number in every picture is derived from
   this file, so "do these pictures agree with each other?" is something a reviewer can check
   rather than trust. Nothing in swr-kit.js or the CSS states a string, a model code or a body
   text; the kit computes which body a state gets from the TV's own `appSettings` flag, exactly as
   the shipped guide computes it from `TvFacts.appSettingsMenu`.

   STAND-IN DATA. The brief names it exactly and this is the whole of it: one TV name and two
   model codes. Both models are real Samsung product codes and neither is the household's —
   `UE50TU7105` is a 2020 European set (letter T = 2020) and `QE55S90D` a 2024 one (letter D =
   2024), which is the single fact the screen reads: the model year picks the *App Settings* path
   (FR-4). The repository is public; no library title, filename, host or address appears anywhere
   in this round, and no TV name but the brief's.

   A NOTE ON WHERE THE TV IS VISIBLE. The shipped step page does not name the TV at all — no step
   of the guide does (SamsungGuideScreen.Page has no TV line, and step 2 and 3 show none either).
   So the two stand-in TVs are not printed on any of these screens: they differ only in which body
   text the screen carries. They are named in the strip captions, which are canvas, not phone. A
   TV line on the step screens would be a new component the brief did not ask for; the report says
   so rather than inventing one.
*/
window.SWR = (function () {
  'use strict';

  /* The status bar's clock. One value, so no two pictures disagree about the time. */
  var CLOCK = '20:14';

  /* The two TVs the brief names. `appSettings` is the shipped `TvFacts.appSettingsMenu`: true for
     a 2024-or-later set, which is the only thing the model code decides on this screen. */
  var TVS = {
    pre2024: { name: 'Living Room TV', model: 'UE50TU7105', year: 2020, appSettings: false },
    y2024:   { name: 'Living Room TV', model: 'QE55S90D',   year: 2024, appSettings: true }
  };

  /* The four states the brief lists, in its order. `pick` is the guide's in-memory choice, which
     FR-13 holds for as long as the guide is open and never persists: null is the question. */
  var STATES = [
    { id: 's1-question',    pick: null,     tv: 'pre2024' },
    { id: 's2-keys',        pick: 'keys',   tv: 'pre2024' },
    { id: 's3-nokeys',      pick: 'nokeys', tv: 'pre2024' },
    { id: 's4-nokeys-2024', pick: 'nokeys', tv: 'y2024'   }
  ];

  /* Studies: drawn to answer a question, not to be picked from.
     x1 — the fourth body text (number keys on a 2024+ TV). The brief's state list does not hold
          it, and it is the one combination a reader could otherwise only assume; FR-13 writes
          four bodies and this round draws all four.
     x2 — the side-by-side choice FORCED to stay side by side at the 200 % font setting. This is
          the evidence behind direction A's stacking rule; see the report. */
  var STUDIES = [
    { id: 'x1-keys-2024',  pick: 'keys', tv: 'y2024' },
    { id: 'x2-sbs-forced', pick: null,   tv: 'pre2024', forceSideBySide: true }
  ];

  /* ---- strings -------------------------------------------------------------------------------
     `step`, `step1.title` and `next` are SHIPPED strings, copied here at their shipped values in
     all three languages (i18n/en.json, da.json, fo.json, keys samsungtv.step, samsungtv.step1.title,
     samsungtv.next). The shipped i18n wins wherever a string already exists (SKILL-house, Words),
     so they are quoted, not re-drafted.

     Everything else is a DRAFT for FR-8's new keys, in English first with Danish and Faroese
     drafts beside it. `**bold**` runs are the shipped rich-text convention (SamsungGuideScreen's
     `rich()`), and nothing else is marked up.

     The two `*_sub` lines are an ADDITION, used only by direction B, whose rows have a second
     line to fill. A and C do not render them. The report names them as two extra keys B costs.

     Retired by FR-13 and so absent here: samsungtv.step1.body, .body_new and .hint.
  -------------------------------------------------------------------------------------------- */
  /* THE FIVE DIGITS ARE ONE WORD. At the 200 % font setting *press 1 2 3 4 5* broke across two
     lines as "1 2 3" / "4 5", which reads as two numbers to type rather than one sequence of
     five keys. The spaces between the digits below are U+00A0 NO-BREAK SPACE, in all three
     languages — a copy fix, not a layout one, so it belongs in the string and survives the port.
     The report names it, because it is invisible in the file. */
  var STR = {
    en: {
      'step': 'Step {n} of 4',
      'step1.title': 'Open the hidden menu',
      'next': 'Next',
      'step1.which': 'Which remote do you have?',
      'step1.keys': 'It has number keys',
      'step1.nokeys': 'No number keys',
      'step1.keys_sub': 'A long remote with 0–9',
      'step1.nokeys_sub': 'A short Smart Remote',
      'step1.keys_body': 'On the TV, go to **Apps**. Don’t type in the search box: press **1 2 3 4 5** on the remote’s number keys.',
      'step1.keys_body_new': 'On the TV, go to **Apps**, then scroll down to **App Settings**. Don’t type in the search box: press **1 2 3 4 5** on the remote’s number keys.',
      'step1.nokeys_body': 'On the TV, go to **Apps**. Don’t type in the search box: press the **123** key — coloured dots on newer remotes — and number keys appear on the TV. Pick **1 2 3 4 5** there.',
      'step1.nokeys_body_new': 'On the TV, go to **Apps**, then scroll down to **App Settings**. Don’t type in the search box: press the **123** key — coloured dots on newer remotes — then pick **1 2 3 4 5** on the TV.',
      'step1.other': 'I have the other remote'
    },
    da: {
      'step': 'Trin {n} af 4',
      'step1.title': 'Åbn den skjulte menu',
      'next': 'Næste',
      'step1.which': 'Hvilken fjernbetjening har du?',
      'step1.keys': 'Den har taltaster',
      'step1.nokeys': 'Ingen taltaster',
      'step1.keys_sub': 'Lang fjernbetjening med 0–9',
      'step1.nokeys_sub': 'Kort Smart Remote',
      'step1.keys_body': 'Gå til **Apps** på TV’et. Skriv ikke i søgefeltet: tryk **1 2 3 4 5** på fjernbetjeningens taltaster.',
      'step1.keys_body_new': 'Gå til **Apps** på TV’et og rul ned til **App Settings**. Skriv ikke i søgefeltet: tryk **1 2 3 4 5** på fjernbetjeningens taltaster.',
      'step1.nokeys_body': 'Gå til **Apps** på TV’et. Skriv ikke i søgefeltet: tryk på **123**-tasten — farvede prikker på nyere fjernbetjeninger — så kommer taltasterne frem på TV’et. Vælg **1 2 3 4 5**.',
      'step1.nokeys_body_new': 'Gå til **Apps** på TV’et og rul ned til **App Settings**. Skriv ikke i søgefeltet: tryk på **123**-tasten — farvede prikker på nyere fjernbetjeninger — og vælg **1 2 3 4 5** på TV’et.',
      'step1.other': 'Jeg har den anden fjernbetjening'
    },
    fo: {
      'step': 'Stig {n} av 4',
      'step1.title': 'Opna loynimenuna',
      'next': 'Næsta',
      'step1.which': 'Hvørja fjarstýring hevur tú?',
      'step1.keys': 'Hon hevur tal-knappar',
      'step1.nokeys': 'Ongir tal-knappar',
      'step1.keys_sub': 'Long fjarstýring við 0–9',
      'step1.nokeys_sub': 'Stutt Smart Remote',
      'step1.keys_body': 'Far til **Apps** á sjónvarpinum. Skriva ikki í leititeigin: trýst **1 2 3 4 5** á tal-knappunum á fjarstýringini.',
      'step1.keys_body_new': 'Far til **Apps** á sjónvarpinum og rulla niður til **App Settings**. Skriva ikki í leititeigin: trýst **1 2 3 4 5** á tal-knappunum á fjarstýringini.',
      'step1.nokeys_body': 'Far til **Apps** á sjónvarpinum. Skriva ikki í leititeigin: trýst á **123**-knappin — litað punkt á nýrri fjarstýringum — so koma tal-knappar fram á sjónvarpinum. Vel **1 2 3 4 5** har.',
      'step1.nokeys_body_new': 'Far til **Apps** á sjónvarpinum og rulla niður til **App Settings**. Skriva ikki í leititeigin: trýst á **123**-knappin — litað punkt á nýrri fjarstýringum — og vel **1 2 3 4 5** á sjónvarpinum.',
      'step1.other': 'Eg havi hina fjarstýringina'
    }
  };

  /* The four coloured keys, in the order every Samsung remote carries them. Written here because
     they are data a picture reads, not styling: the same four values light the basic remote's
     colour row and mark the Smart Remote's Number/Color key, and the two must not drift.
     None of them is the brand accent — a key's colour is the key's own identity. */
  var KEYCOLORS = ['#e05260', '#3fbf7f', '#e8c045', '#4aa3e8'];

  return { CLOCK: CLOCK, TVS: TVS, STATES: STATES, STUDIES: STUDIES, STR: STR, KEYCOLORS: KEYCOLORS };
})();
