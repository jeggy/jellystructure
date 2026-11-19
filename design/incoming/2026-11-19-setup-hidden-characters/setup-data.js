/* setup-data.js — the ONE array this round renders from.
   PROCESS.md: "Derive every number in every picture from one array", so "do these pictures agree
   with each other?" is something a reviewer can check rather than trust.

   Nothing here is invented. The addresses, the trailer and the three states are the brief's
   STAND-IN section verbatim; the strings are i18n/en.json, da.json and fo.json for what already
   ships, and the spec's own FR-7 table for the three new keys. */
(function () {

  /* ---- the three new strings, straight out of the spec's FR-7 table ---- */
  var NEW_STRINGS = {
    'setup.hidden_line_break': {
      en: 'This address has a hidden line break',
      da: 'Adressen har et skjult linjeskift',
      fo: 'Adressan hevur eitt fjalt linjuskift'
    },
    'setup.hidden_space': {
      en: 'This address has a hidden space',
      da: 'Adressen har et skjult mellemrum',
      fo: 'Adressan hevur eitt fjalt millumrúm'
    },
    'setup.use_fixed_address': { en: 'Use {address}', da: 'Brug {address}', fo: 'Brúka {address}' }
  };

  /* ---- what the screen already says, read out of i18n/*.json on 2026-11-19 ---- */
  var SHIPPED = {
    'setup.server_prompt': {
      en: 'Enter your jellystructure server address',
      da: 'Skriv adressen på jeres jellystructure-server',
      fo: 'Skriva adressuna á jellystructure-ambætaranum tínum'
    },
    'setup.server_label': { en: 'Server address', da: 'Serveradresse', fo: 'Ambætaraadressa' },
    'receiver.setup_connect': { en: 'Connect', da: 'Forbind', fo: 'Sambind' },
    'nav.apps': { en: 'Apps', da: 'Apps', fo: 'Appir' }
  };

  /* ---- the brief's STAND-IN section, exactly ---- */
  var STATES = {
    /* State A: a pasted line break with a time after it. The FIELD shows only the first line —
       that is the whole fault: Compose's singleLine TextField keeps the \n and everything after
       it in its value and simply does not draw it. */
    A: {
      name: 'linebreak',
      fieldValue: 'jelly.example.net\n[21:20]',
      fieldDraws: 'jelly.example.net',
      marks: [
        { t: 'jelly.example.net' }, { mark: 'BREAK' }, { t: '[21:20]' }
      ],
      sentence: 'setup.hidden_line_break',
      cleaned: 'jelly.example.net'
    },
    /* State B: a space in the middle. The field draws it, and it is still invisible to a reader. */
    B: {
      name: 'space',
      fieldValue: 'jelly. example.net',
      fieldDraws: 'jelly. example.net',
      marks: [ { t: 'jelly.' }, { mark: 'SPACE' }, { t: 'example.net' } ],
      sentence: 'setup.hidden_space',
      cleaned: 'jelly.example.net'
    },
    /* State C: after the button. No warning, Connect enabled and focused. */
    C: {
      name: 'clean',
      fieldValue: 'jelly.example.net',
      fieldDraws: 'jelly.example.net',
      marks: null,
      sentence: null,
      cleaned: 'jelly.example.net'
    }
  };

  /* ---- the mark glyphs ----
     FR-4 names U+23CE for a line break and U+00B7 for everything else. glyph-probe.html MEASURED
     both against the three self-hosted woff2 in design/app/fonts/: U+00B7 is in all three subsets;
     U+23CE IS IN NONE OF THEM and is drawn by whatever fallback the device happens to have. So the
     round carries both and recommends one — see REPORT.md. */
  var GLYPHS = {
    /* the recommendation: a pilcrow, which every one of the three faces carries */
    pilcrow: { BREAK: '¶', SPACE: '·', label: 'U+00B6 ¶ / U+00B7 ·' },
    /* the spec's own pair, drawn so the fallback is visible rather than argued about */
    spec:    { BREAK: '⏎', SPACE: '·', label: 'U+23CE ⏎ / U+00B7 ·' }
  };

  /* ---- the component, in dp. One set of numbers; each surface sets its own px-per-dp. ----
     The TV stage is 1920 px wide and 960 dp (density 2), so the TV renders these at 2×. The phone
     bezel is 412 dp = 412 px and the desktop window is 1:1, so both render them at 1×. */
  var SPEC = {
    railW: 3,            /* the warn rail — the shipped .ep-playnote / .dplaynote idiom */
    railGap: 11,         /* rail to text */
    sentenceSize: 13.5,  /* Sora 600 */
    markSize: 14,        /* JetBrains Mono 500 — one cell per character, so a · lands exactly */
    markTrack: 0.2,
    warnGapTop: 10,      /* field bottom to the warning */
    warnGapInner: 6,     /* sentence to mark line */
    btnGapTop: 12,       /* mark line to the fix button (direction A) */
    btnH: 42,
    btnR: 8,             /* the control radius the screen already uses for Connect */
    btnSize: 14,
    connectH: 52,
    colGapBefore: 20     /* the column's existing 20 dp gap, unchanged */
  };

  /* ---- the warning colour, picked from the current palette ----
     ravilo.css already carries --warn: #f5b542 and already uses it for the two "something about
     this needs saying" notes (.dplaynote, .ep-playnote). The marks take it; the address around
     them does NOT — a whole line in warn hides the one character the viewer has to find.

     ravilo-phone.css and desktop-directions.css carry no --warn at all, and RaviloColors has no
     warn field: the promotion adds one. Daylight needs its own value (#f5b542 on #fbfbfd is
     1.8 : 1) and Noir's accent IS #f5b542, so Noir takes its accent-2 instead. */
  var WARN = {
    aurora:   { ink: '#f5b542', rail: '#f5b542', why: 'ravilo.css --warn, unchanged' },
    midnight: { ink: '#f5b542', rail: '#f5b542', why: 'ravilo.css --warn, unchanged' },
    noir:     { ink: '#e0792f', rail: '#e0792f', why: 'Noir’s accent IS #f5b542 — the warn would be the accent. Its accent-2 keeps them apart.' },
    graphite: { ink: '#f5b542', rail: '#f5b542', why: 'ravilo.css --warn, unchanged' },
    daylight: { ink: '#8a5a00', rail: '#c98a12', why: '#f5b542 on #fbfbfd is 1.8 : 1. #8a5a00 is 6.5 : 1.' }
  };

  window.SETUP = {
    NEW_STRINGS: NEW_STRINGS, SHIPPED: SHIPPED, STATES: STATES,
    GLYPHS: GLYPHS, SPEC: SPEC, WARN: WARN,
    /* one lookup for both tables, so no frame can disagree with i18n */
    str: function (key, lang) {
      var t = NEW_STRINGS[key] || SHIPPED[key];
      return t ? (t[lang] || t.en) : key;
    }
  };
})();
